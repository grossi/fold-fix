package app.foldfix;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A model of the phone for whole scenarios. The rules are the real ones ({@link Reconciler},
 * {@link Ownership}, {@link Stale}); what is Android around them is stood in for here, each part
 * following its class step by step: FoldService (hall readings, timers, launches, status),
 * RearDisplay ({@code request}, {@code cancel}, {@code onStateChanged}), Trampoline and Curtain
 * ({@code onResume}). Keep them in step when those classes change.
 *
 * <p>The system is modelled as far as FoldFix sees it: the device state, whose request is active,
 * and the "Switch screens?" dialog. Nothing in it happens by itself. A test says when a launch
 * arrives, when the dialog shows, and what another app does, so that late and overtaken steps can
 * be played through. What the system reports back reaches FoldFix right after the step that
 * caused it, a request's own callback before the change of the device state. On a Pixel Fold on
 * Android 17 that is the usual order for a request that ended; for one that became active either
 * order is as common, so a test can hold that callback back ({@link #activationLate}).
 *
 * <p>Every line FoldFix would write to its history is kept, without the device state that the
 * real lines of launches and take-overs end with.
 */
final class SimPhone implements Reconciler.Rear, Reconciler.Phone {
    static final boolean CLOSED = true, OPEN = false;
    /** Device states, as on the Pixel Fold. */
    static final int STATE_CLOSED = 0, OPENED = 2, REAR = 3, REAR_OUTER_DEFAULT = 5;
    static final String NOBODY = "nobody", FOLDFIX = "FoldFix", OTHER = "another app";

    // ---- the system ---------------------------------------------------------------------

    int state;
    /** Who made the active request. */
    String holder = NOBODY;
    /** FoldFix's request that is active, and the one waiting for its dialog (0: none). */
    private int activeGen, waitingGen;
    boolean dialog;
    /**
     * Set to have the system report FoldFix's request as active only at {@link #activationArrives},
     * after the device state. On the phone that callback comes up to 0.1 s after the state in
     * about half the closings, and just before it in the others.
     */
    boolean activationLate;
    private Runnable heldActivation;
    boolean hasBackend = true, known = true, available = true, outer = true;
    /** Whether the system counts FoldFix as the top app: if not, it refuses requests and cancels. */
    boolean topApp = true;
    /** Uptime and real time: apart on purpose, and real time also runs while asleep. */
    long up = 1_000_000, real = 5_000_000;

    // ---- FoldFix ------------------------------------------------------------------------

    final Ownership own;
    final Reconciler rules;
    /** The hall reading the service acts on; null before the first. */
    Boolean closed;
    boolean serviceOn = true;
    String status;
    /** What is in storage: a request that FoldFix has to end may be active. */
    boolean saved;
    Boolean landscapeFix;
    int dialogLooks;

    private final List<String> log = new ArrayList<>();
    /** Trampolines the service started that haven't arrived yet. */
    private final ArrayDeque<String> launches = new ArrayDeque<>();
    /** The curtain the service started, until it arrives: deadline and activation. */
    private long[] curtain;
    /** When the service will look again (uptime). */
    private final List<Long> timers = new ArrayList<>();
    /** Reconciles posted by RearDisplay's listeners, and what the system has yet to report. */
    private int posted;
    private final ArrayDeque<Runnable> reports = new ArrayDeque<>();

    /** An open phone with nothing going on, FoldFix just started and its service connected. */
    SimPhone() {
        this(OPENED, NOBODY, false);
    }

    private SimPhone(int state, String holder, boolean saved) {
        this.holder = holder;
        this.saved = saved;
        own = new Ownership(saved, this::changed);
        rules = new Reconciler(this, this);
        systemState(state);  // the first status callback
        settle();
    }

    /** A phone that was closed a minute ago and is on the outer screen through FoldFix's request. */
    static SimPhone closedOnOuter() {
        SimPhone p = new SimPhone();
        p.hall(CLOSED);
        p.trampoline();
        p.dialogShows();
        p.after(60_000);
        p.took();
        return p;
    }

    /** FoldFix's process starts again (an update, a crash): only storage and the system carry over. */
    SimPhone restarted() {
        return new SimPhone(state, holder.equals(NOBODY) ? NOBODY : OTHER, saved);
    }

    // ---- what a test makes happen ---------------------------------------------------------

    /** A hall reading (FoldService.onHall). */
    void hall(boolean c) {
        if (closed != null && closed == c) return;
        log.add("hall " + (c ? "closed" : "open"));
        boolean opened = closed != null && !c;
        closed = c;
        rules.onFold(opened);
        reconcile();
        settle();
    }

    /** Time passes with the phone awake; the service looks again when it said it would. */
    void after(long ms) {
        long end = up + ms;
        while (true) {
            long next = Long.MAX_VALUE;
            for (long timer : timers) next = Math.min(next, timer);
            if (next > end) break;
            real += next - up;
            up = next;
            reconcile();
            settle();
        }
        real += end - up;
        up = end;
    }

    /** Time passes with the phone asleep: only real time moves, and no timer runs. */
    void sleep(long ms) {
        real += ms;
    }

    /** The oldest trampoline the service started arrives. False if none is under way. */
    boolean trampoline() {
        String action = launches.poll();
        if (action == null) return false;
        resumeTrampoline(action, false);
        settle();
        return true;
    }

    /** {@code adb shell am start -n app.foldfix/.Trampoline --es action request}. */
    void adbRequest() {
        resumeTrampoline(Reconciler.REQUEST, false);
        settle();
    }

    /** {@code adb shell am start -n app.foldfix/.Trampoline --es action cancel [--ez force true]}. */
    void adbCancel(boolean force) {
        resumeTrampoline(Reconciler.CANCEL, force);
        settle();
    }

    /** The system puts up the dialog of FoldFix's waiting request; the service hears of it. */
    void dialogShows() {
        if (waitingGen == 0) throw new IllegalStateException("no request of FoldFix's is waiting for its dialog");
        dialog = true;
        if (serviceOn && rules.dialogMatters(closed)) reconcile();  // onAccessibilityEvent
        settle();
    }

    /** The callback held back by {@link #activationLate} arrives. */
    void activationArrives() {
        if (heldActivation == null) throw new IllegalStateException("no activation was held back");
        heldActivation.run();
        heldActivation = null;
        settle();
    }

    /** The dialog is up, but the service hasn't heard of it yet: something else happens first. */
    void dialogUpUnnoticed() {
        if (waitingGen == 0) throw new IllegalStateException("no request of FoldFix's is waiting for its dialog");
        dialog = true;
    }

    boolean curtainStarted() {
        return curtain != null;
    }

    /** The curtain the service started arrives (Curtain.onResume). */
    void curtainArrives() {
        if (curtain == null) throw new IllegalStateException("no curtain was started");
        long deadline = curtain[0];
        int activation = (int) curtain[1];
        curtain = null;
        String stale = Stale.whyCurtainStale(real, deadline, hallClosed(), isRearActive(),
                own.activations(), activation, own.hasOwnRequest());
        if (stale != null) {
            log.add("curtain: leaving the request alone, " + stale);
        } else if (cancel(false)) {
            log.add("curtain: up");
        }
        settle();
    }

    /** Another app's request becomes active (Pixel Camera's is for state 5 and needs no dialog). */
    void otherAppRequests(int newState) {
        String was = holder;
        int gen = activeGen;
        holder = OTHER;
        if (was.equals(FOLDFIX)) reports.add(() -> own.requestEnded(gen, real));  // replaced: its callback says it ended
        reports.add(() -> systemState(newState));
        settle();
    }

    /** Another app ends the active request, whoever made it: Camera's selfie button, System UI's control. */
    void otherAppEnds() {
        endActiveRequest();
        settle();
    }

    /** The status screen's "Switch to the inner screen" button (RearDisplay.cancelForUser). */
    void userSwitchesToInner() {
        cancel(true);
        settle();
    }

    /**
     * Android's own state becomes CLOSED: a hinge sensor that works, or
     * {@code cmd device_state base-state 0}. The system ends the active request, whoever made it.
     * A request that is waiting for its dialog stays until {@link #systemDropsWaitingRequest} or
     * {@link #dialogShows}: on the phone either happened.
     */
    void androidCloses() {
        String was = holder;
        int gen = activeGen;
        holder = NOBODY;
        if (was.equals(FOLDFIX)) reports.add(() -> own.requestEnded(gen, real));
        reports.add(() -> systemState(STATE_CLOSED));
        settle();
    }

    /**
     * Android's state goes back to OPENED: the phone was opened, or the base state was reset. A
     * request of FoldFix's that is still waiting stays as it is. (On the phone one whose "Unfold
     * phone" dialog nobody answered went through by itself then; FoldFix now cancels that dialog.)
     */
    void androidOpens() {
        reports.add(() -> systemState(OPENED));
        settle();
    }

    /**
     * System UI drops FoldFix's waiting request by itself, with its dialog if that is up. It does
     * when Android's state becomes CLOSED while "Switch screens?" is showing.
     */
    void systemDropsWaitingRequest() {
        if (waitingGen == 0) throw new IllegalStateException("no request of FoldFix's is waiting for its dialog");
        int gen = waitingGen;
        waitingGen = 0;
        dialog = false;
        reports.add(() -> own.requestEnded(gen, real));
        settle();
    }

    /** The device state becomes known (the first status callback came late). */
    void stateBecomesKnown() {
        known = true;
        systemState(state);
        settle();
    }

    /** The user turns the service off (FoldService.stop). */
    void serviceOff() {
        log.add("service stopped");
        serviceOn = false;
        timers.clear();
        status = null;
    }

    /** The lines logged since the last call, one per line. */
    String took() {
        String lines = String.join("\n", log);
        log.clear();
        return lines;
    }

    int launchesUnderWay() {
        return launches.size();
    }

    boolean nothingScheduled() {
        return timers.isEmpty();
    }

    // ---- FoldService --------------------------------------------------------------------

    private void reconcile() {
        timers.clear();
        if (serviceOn) rules.reconcile(closed);
    }

    /**
     * Runs what was posted, then what the system reports, until nothing is left. Each reconcile
     * that RearDisplay posted runs, also several in a row: they go through RearDisplay's own
     * handler, so the service's removeCallbacks doesn't take them out, only its own timers.
     */
    private void settle() {
        while (true) {
            if (posted > 0) {
                posted--;
                reconcile();
            } else if (!reports.isEmpty()) {
                reports.poll().run();
            } else {
                return;
            }
        }
    }

    /** FoldService.hallClosed: null while the service isn't running. */
    private Boolean hallClosed() {
        return serviceOn ? closed : null;
    }

    @Override public long uptime() { return up; }
    @Override public long realtime() { return real; }
    @Override public boolean outerDisplayPresent() { return outer; }

    @Override
    public boolean tapDialog(boolean confirm) {
        dialogLooks++;
        if (!dialog) return false;
        if (confirm && state == STATE_CLOSED) {  // there the dialog reads "Unfold phone": Cancel only
            log.add("dialog up but no Confirm yet");
            return true;
        }
        log.add("clicked " + (confirm ? "Confirm" : "Cancel"));
        dialog = false;
        int gen = waitingGen;
        waitingGen = 0;
        if (confirm) {
            reports.add(() -> {  // it becomes the active request, in place of any other
                holder = FOLDFIX;
                activeGen = gen;
                if (activationLate) {
                    heldActivation = () -> own.requestActivated(gen);
                } else {
                    own.requestActivated(gen);
                }
                systemState(REAR);
            });
        } else {
            reports.add(() -> own.requestEnded(gen, real));
        }
        return true;
    }

    @Override
    public void launch(String action) {
        if (Reconciler.REQUEST.equals(action)) own.markRequestPending();
        log.add(action + " rear display (try " + rules.tries + ")");
        launches.add(action);
    }

    @Override
    public void startCurtain(long deadline, int activation) {
        curtain = new long[]{deadline, activation};
    }

    @Override public void setLandscapeFix(boolean active) { landscapeFix = active; }

    @Override
    public void setStatus(String s) {
        if (!s.equals(status)) log.add("status: " + s);
        status = s;
    }

    @Override public void log(String line) { log.add(line); }
    @Override public void lookAgainIn(long ms) { timers.add(up + ms); }

    // ---- Trampoline ---------------------------------------------------------------------

    private void resumeTrampoline(String action, boolean force) {
        if (Reconciler.CANCEL.equals(action)) {
            String stale = Stale.whyCancelStale(force, hallClosed(), own.hasOwnRequest());
            if (stale == null) {
                cancel(false);
            } else {
                log.add("dropping stale cancel: " + stale);
            }
        } else {
            String stale = Stale.whyRequestStale(hallClosed(), isStateClosed());
            if (stale == null) {
                request();
            } else {
                log.add(stale + ", dropping stale request");
                own.dropPendingMark();
            }
        }
    }

    // ---- RearDisplay --------------------------------------------------------------------

    @Override public String backend() { return hasBackend ? "window extensions" : null; }
    @Override public boolean isKnown() { return known; }
    @Override public boolean isRearActive() { return state == REAR || state == REAR_OUTER_DEFAULT; }
    @Override public boolean isAvailable() { return available; }
    @Override public boolean isStateClosed() { return state == STATE_CLOSED; }
    @Override public boolean hasOwnRequest() { return own.hasOwnRequest(); }
    @Override public boolean isSessionLive() { return own.isSessionLive(up); }
    @Override public boolean isSessionWaiting() { return own.isSessionWaiting(up); }
    @Override public boolean isCancelPending() { return own.isCancelPending(real); }
    @Override public long endingCancelAt() { return own.endingCancelAt(); }
    @Override public int activations() { return own.activations(); }

    @Override
    public boolean adopt() {
        if (own.canAdopt(isRearActive(), real)) {
            log.add("adopting the active rear display request");
            own.adopt();
        }
        return !own.isEnding(real);
    }

    @Override
    public void adoptOnOpen() {
        if (!own.canAdoptOnOpen(isRearActive())) return;
        log.add("opened with rear display on before a request of ours was reported active: taking the active one over");
        own.adopt();
    }

    private void request() {
        if (!outer) {
            log.add("outer screen not detected, not requesting rear display");
            own.dropPendingMark();
            return;
        }
        if (isRearActive() || isSessionLive()) {
            log.add("rear display already on or requested (ours: " + (own.hasSession() || own.isInherited())
                    + "), not requesting again");
            own.dropPendingMark();
            return;
        }
        int gen = own.begin();
        if (!topApp) {
            log.add("request refused");
            own.clear();
            return;
        }
        waitingGen = gen;  // a lost earlier one is ended and replaced
        dialog = false;
        own.sessionStarted(up);
        log.add("rear display requested");
    }

    private boolean cancel(boolean byUser) {
        if (!isRearActive()) {
            log.add("nothing active to cancel" + (own.hasSession() ? ", request still waiting for its dialog" : ""));
            if (!own.hasSession()) own.clear();
            return true;
        }
        long at = real;
        if (!topApp) {
            log.add("cancel refused");
            return false;
        }
        endActiveRequest();
        log.add("rear display request cancelled" + (byUser ? " from the status screen" : ""));
        own.cancelled(at, byUser);
        return true;
    }

    /** The system ends the active request, whoever made it, and reports that afterwards. */
    private void endActiveRequest() {
        String was = holder;
        int gen = activeGen;
        holder = NOBODY;
        if (was.equals(FOLDFIX)) reports.add(() -> own.requestEnded(gen, real));
        reports.add(() -> systemState(OPENED));
    }

    /** A status callback (RearDisplay.onStateChanged). */
    private void systemState(int newState) {
        state = newState;
        boolean active = isRearActive();
        own.onRearDisplay(active, real);
        if (own.isInheritedGone(active)) {
            log.add("earlier or adopted request is gone");
            own.inheritedGone();
        }
        posted++;
    }

    /** RearDisplay.save. */
    private void changed() {
        saved = own.hasOwnRequest();
        posted++;
    }
}
