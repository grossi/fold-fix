package app.foldfix;

/**
 * The rules of {@link FoldService}: what to do for a hall reading and the state rear-display mode
 * is in, and when to look again. They are kept apart from the service so that they can be tested
 * without a phone ({@code test.sh}): nothing in this class is Android's. It reads what it needs
 * through {@link Rear} and {@link Phone}, each value only where a rule needs it, and acts through
 * them too.
 *
 * <p>What it keeps between steps is the retry schedule and what is known of this opening's
 * cancels. One instance per service, main thread only.
 */
final class Reconciler {
    /** The two things {@link Trampoline} is launched for. */
    static final String REQUEST = "request", CANCEL = "cancel";

    static final long POLL_MS = 200;
    static final long RETRY_MS = 2_000;
    static final long BACKOFF_MS = 30_000;    // after MAX_FAST_TRIES launches that didn't help
    static final int MAX_FAST_TRIES = 5;
    static final long DIALOG_WAIT_MS = 3_000; // how long a dialog may take to show up
    static final long BLOCKED_RECHECK_MS = 5_000;
    /** Another app's request this soon after our cancel on open is a reflex, not the user's choice. */
    static final long REREQUEST_MS = 3_000;

    /** Rear-display mode and whose request it is: what the rules need of {@link RearDisplay}. */
    interface Rear {
        String backend();
        boolean isKnown();
        boolean isRearActive();
        boolean isAvailable();
        boolean hasOwnRequest();
        boolean isSessionLive();
        boolean isSessionWaiting();
        boolean isCancelPending();
        long endingCancelAt();
        int activations();
        /** False if taking the active request over has to wait a moment (see {@link RearDisplay#adopt}). */
        boolean adopt();
        void adoptOnOpen();
    }

    /** The rest of what the rules read and do: {@link FoldService}. */
    interface Phone {
        /** {@code SystemClock.uptimeMillis()}: stands still while the phone sleeps. */
        long uptime();
        /** {@code SystemClock.elapsedRealtime()}: counts sleep too. */
        long realtime();
        boolean outerDisplayPresent();
        /**
         * Taps Confirm or Cancel in the "Switch screens?" dialog if it is up. Returns whether it
         * was up, also when the button wasn't there yet. This scans windows, so the rules only ask
         * where a dialog matters.
         */
        boolean tapDialog(boolean confirm);
        /** Starts the trampoline for {@link #REQUEST} or {@link #CANCEL}; called once counted. */
        void launch(String action);
        void startCurtain(long deadline, int activation);
        void setLandscapeFix(boolean active);
        void setStatus(String s);
        void log(String line);
        /** Runs {@link #reconcile} again after this long, unless something does so earlier. */
        void lookAgainIn(long ms);
    }

    private final Rear rd;
    private final Phone phone;

    long lastLaunch, lastRequest;
    int tries;
    /** Real time of the first cancel sent since the last fold or unfold, 0 if there was none. */
    long cancelSentAt;
    /** The one follow-up of this opening is used up (see {@link Curtain}). */
    boolean followedUp;
    /** The last of another app's requests that was looked at (see {@link RearDisplay#activations}). */
    int otherSeen;

    Reconciler(Rear rd, Phone phone) {
        this.rd = rd;
        this.phone = phone;
    }

    /**
     * The phone was closed or opened: the retry schedule and the opening's bookkeeping start over.
     * {@code opened} says that it was closed until now and is open. Rear-display mode that is on
     * at that moment was on while the phone was closed, so the active request is ours to end.
     */
    void onFold(boolean opened) {
        if (opened && rd.isKnown()) rd.adoptOnOpen();
        lastLaunch = 0;
        tries = 0;
        cancelSentAt = 0;
        followedUp = false;
        otherSeen = 0;
    }

    /**
     * One step towards "closed ⇔ rear display"; re-arms itself until that holds. {@code closed}
     * is the hall reading being acted on, null before the first one.
     */
    void reconcile(Boolean closed) {
        if (closed == null) return;
        phone.setLandscapeFix(closed && rd.isRearActive());
        if (rd.backend() == null) {
            phone.setStatus("No rear-display access: run the one-time adb command");
            return;  // can only change after a restart
        }
        // Wait until the device state is known too: acting earlier can put a dialog on the
        // outer screen.
        if (!rd.isKnown()) return;
        boolean rear = rd.isRearActive();
        boolean ours = rd.hasOwnRequest();
        long now = phone.uptime();

        if (closed) {
            if (rear) {  // already on the outer screen, whoever asked for it
                // Our own request can still be waiting for its dialog: another app's got in first
                // (Pixel Camera's does, when its selfie button has just ended ours). The dialog
                // then comes up on the outer screen, in front of the user. Confirm it, which puts
                // our request in place of the other one.
                if (rd.isSessionWaiting()) {
                    phone.tapDialog(true);
                    if (now - lastLaunch < DIALOG_WAIT_MS) phone.lookAgainIn(POLL_MS);
                }
                // Another app's request can't be meant for a closed phone, and it never ends by
                // itself: take it over, so that opening the phone ends it like one of ours. Not
                // in the moment between the end of a request of ours and rear-display mode being
                // reported off; looked at again in case it stays on.
                if (!ours && !rd.adopt()) phone.lookAgainIn(Ownership.CANCEL_EFFECT_MS);
                tries = 0;
                phone.setStatus("Closed: outer screen on");
                return;
            }
            String blocked = whyNotPossible();
            if (blocked != null) {
                phone.setStatus(blocked);
                phone.lookAgainIn(BLOCKED_RECHECK_MS);
                return;
            }
            boolean confirmed = ours && phone.tapDialog(true);
            if (!confirmed && !rd.isSessionLive() && now - lastLaunch > retryDelay()) {
                // Not while our request is in flight: a confirmed switch can take a moment.
                launch(REQUEST);
            }
            phone.setStatus("Closed: switching to the outer screen");
        } else {
            // Rear display back on through another app's request, and the last time it went off was
            // the doing of a cancel sent since the phone was opened.
            long cancelled = rd.endingCancelAt();
            if (rear && !ours && cancelSentAt != 0 && cancelled >= cancelSentAt
                    && otherSeen != rd.activations()) {
                followUp(cancelled);
            }
            // Also look for a dialog shortly after our last request: one can still pop up after
            // ownership was dropped, and tapping "Switch" there would strand the phone in
            // rear-display mode while open.
            boolean recent = now - lastRequest < DIALOG_WAIT_MS;
            // Nothing of ours to undo. A request another app made while the phone is open (the
            // real selfie mode) is left alone, apart from the one followUp() deals with.
            if (!ours && !recent) {
                tries = 0;
                // A cancel of ours drops ownership a moment before rear-display mode is seen going
                // off: until then that isn't another app's. Looked at again in case it stays on.
                boolean ending = rear && rd.isCancelPending();
                if (ending) phone.lookAgainIn(Ownership.CANCEL_EFFECT_MS);
                phone.setStatus(ending ? "Open: switching back to the inner screen"
                        : rear ? "Open: another app is using the outer screen" : "Open: inner screen on");
                return;
            }
            // Tapping Cancel is the only way to drop a request that is still waiting for its dialog.
            if (!phone.tapDialog(false)) {
                if (ours && rear && now - lastLaunch > retryDelay()) {
                    // Like requests, cancels are only accepted from the top app, hence the trampoline.
                    launch(CANCEL);
                } else if (!rear && !recent) {
                    // Ours but pending, dialog not up (yet): wait for SystemUI events instead of polling.
                    phone.setStatus("Open: waiting for the request's dialog to cancel it");
                    return;
                }
            }
            phone.setStatus("Open: switching back to the inner screen");
        }
        phone.lookAgainIn(now - lastLaunch < DIALOG_WAIT_MS ? POLL_MS : retryDelay());
    }

    /**
     * Whether a "Switch screens?" dialog showing up now would be one to answer: the service only
     * runs {@link #reconcile} for an accessibility event if so, which skips the window scan when
     * there's nothing to do.
     */
    boolean dialogMatters(Boolean closed) {
        if (closed == null || !rd.isKnown()) return false;
        boolean recent = phone.uptime() - lastRequest < DIALOG_WAIT_MS;
        return closed ? !rd.isRearActive() || rd.isSessionWaiting() : rd.hasOwnRequest() || recent;
    }

    /**
     * Another app got rear-display mode after a cancel of ours, made at real time
     * {@code cancelled}, ended it on open. If that was the first time and within
     * {@link #REREQUEST_MS} of that cancel, it is the app on screen asking again for the mode it
     * was started in (Pixel Camera does): end that request too, once, behind the curtain. Anything
     * later or repeated is the app's business, and is only logged. Real time, not uptime, so that
     * sleeping doesn't stretch the window.
     */
    private void followUp(long cancelled) {
        otherSeen = rd.activations();
        long after = phone.realtime() - cancelled;
        String what = "another app turned rear display on " + after + " ms after our cancel: ";
        if (followedUp || after >= REREQUEST_MS) {
            phone.log(what + "left alone");
            return;
        }
        followedUp = true;
        phone.log(what + "ending that once");
        phone.startCurtain(cancelled + REREQUEST_MS, otherSeen);
    }

    long retryDelay() {
        return tries < MAX_FAST_TRIES ? RETRY_MS : BACKOFF_MS;
    }

    /** Reasons not to request rear-display mode right now (checked each time, it's cheap). */
    private String whyNotPossible() {
        if (!phone.outerDisplayPresent()) {
            // Booted open: the outer panel was never brought up, and forcing rear-display mode
            // would crash the system.
            return "Outer screen not detected: restart the phone while it is closed";
        }
        if (!rd.isAvailable()) return "Rear-display mode is unavailable right now (battery saver or heat?)";
        return null;
    }

    private void launch(String action) {
        long now = phone.uptime();
        lastLaunch = now;
        tries++;
        if (REQUEST.equals(action)) {
            lastRequest = now;
        } else if (cancelSentAt == 0) {
            cancelSentAt = phone.realtime();
        }
        phone.launch(action);
    }
}
