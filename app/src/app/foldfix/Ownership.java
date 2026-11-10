package app.foldfix;

/**
 * {@link RearDisplay}'s bookkeeping: whether the active or pending request is FoldFix's to end,
 * how far this process's own request has got, and which cancel last turned rear-display mode off.
 * RearDisplay talks to the system and reports here what happened. Nothing in this class is
 * Android's, and times are passed in, so the bookkeeping can be tested without a phone
 * ({@code test.sh}).
 *
 * <p>{@code uptime} is {@code SystemClock.uptimeMillis()}, {@code realtime} is
 * {@code SystemClock.elapsedRealtime()}, which counts sleep too. Main thread only, like
 * RearDisplay.
 */
final class Ownership {
    /** A request still waiting for its dialog after this long is considered lost and redone. */
    static final long STALE_SESSION_MS = 10_000;
    /** Rear-display mode going off within this long of a cancel made here is that cancel's doing. */
    static final long CANCEL_EFFECT_MS = 2_000;

    /** Run after each change of what is ours: RearDisplay saves it and tells its listeners. */
    private final Runnable changed;

    /** FoldService is about to request, or this process's request is pending or active. */
    private volatile boolean ownRequest;
    /**
     * FoldFix has to end an active request this process didn't make: an earlier FoldFix process's,
     * or another app's that was {@linkplain #adopt adopted}. Persisted, because such a request
     * outlives this process too (requests are sticky, see {@link RearDisplay}).
     */
    private volatile boolean inherited;
    /** Uptime when this process's current request was accepted, 0 if there is none. */
    private volatile long sessionStart;
    /** The current request has been activated (the dialog was confirmed). */
    private volatile boolean sessionActive;
    /** Bumped per request, so a replaced request's "ended" callback can't clear the new one. */
    private volatile int generation;
    /** How often rear-display mode has turned on, and whether it was on at the last look. */
    private volatile int activations;
    private boolean wasActive;
    /**
     * Real time of the last cancel made here, until its effect is seen. Not set by the user's
     * cancel from the status screen.
     */
    private long cancelledAt;
    /** Real time of the cancel made here that last turned rear-display mode off; else 0. */
    private volatile long endingCancelAt;

    /** {@code inherited} is what an earlier process saved: a request of FoldFix's may be active. */
    Ownership(boolean inherited, Runnable changed) {
        this.inherited = inherited;
        this.changed = changed;
    }

    // ---- what is known ----------------------------------------------------------------------

    /** Whether the active or pending rear-display request (if any) is FoldFix's to end. */
    boolean hasOwnRequest() {
        return ownRequest || inherited;
    }

    /** A request this process didn't make is FoldFix's to end. */
    boolean isInherited() {
        return inherited;
    }

    /** This process's request was accepted and hasn't ended: waiting for its dialog, or active. */
    boolean hasSession() {
        return sessionStart != 0;
    }

    /** Our request is active, or still waiting for its dialog and not yet given up on. */
    boolean isSessionLive(long uptime) {
        long start = sessionStart;
        return start != 0 && (sessionActive || uptime - start < STALE_SESSION_MS);
    }

    /** Our request was accepted and is still waiting for its dialog, and not yet given up on. */
    boolean isSessionWaiting(long uptime) {
        return !sessionActive && isSessionLive(uptime);
    }

    /** See {@link RearDisplay#activations}. */
    int activations() {
        return activations;
    }

    /** See {@link RearDisplay#endingCancelAt}. */
    long endingCancelAt() {
        return endingCancelAt;
    }

    /** See {@link RearDisplay#isCancelPending}. */
    boolean isCancelPending(long realtime) {
        return cancelledAt != 0 && realtime - cancelledAt < CANCEL_EFFECT_MS;
    }

    // ---- what happened ----------------------------------------------------------------------

    /** FoldService is about to launch the trampoline to request. */
    void markRequestPending() {
        setOwn(true);
    }

    /** That request won't be made after all. A request that was accepted earlier stays ours. */
    void dropPendingMark() {
        if (sessionStart == 0) {
            ownRequest = false;
            changed.run();
        }
    }

    /** Whether there is a request to {@linkplain #adopt take over}: an active one that isn't ours. */
    boolean canAdopt(boolean rearActive) {
        return !hasOwnRequest() && rearActive;
    }

    /** Makes the active request FoldFix's to end, like one of its own. */
    void adopt() {
        inherited = true;
        changed.run();
    }

    /** A request is about to be made here. Returns its generation, for its callbacks. */
    int begin() {
        setOwn(true);
        return ++generation;
    }

    /** The system accepted that request: it now waits for its dialog. */
    void sessionStarted(long uptime) {
        sessionStart = uptime;
        sessionActive = false;
    }

    /** The system's callback: the request of that generation became active. */
    void requestActivated(int gen) {
        if (gen == generation && sessionStart != 0) sessionActive = true;
    }

    /** The system's callback: the request of that generation ended. */
    void requestEnded(int gen) {
        if (gen == generation) setOwn(false);
    }

    /**
     * A cancel made here at real time {@code at} (taken before the call) went through. Whatever
     * was active is no longer ours to end. The user's own cancel, from the status screen, isn't
     * one {@link #endingCancelAt} reports.
     */
    void cancelled(long at, boolean byUser) {
        if (!byUser) cancelledAt = at;
        generation++;
        setOwn(false);
    }

    /** Nothing is FoldFix's to end any more. */
    void clear() {
        setOwn(false);
    }

    /** Rear-display mode was seen on or off. */
    void onRearDisplay(boolean active, long realtime) {
        if (active && !wasActive) activations++;
        if (!active && wasActive) {
            long since = realtime - cancelledAt;
            endingCancelAt = cancelledAt != 0 && since < CANCEL_EFFECT_MS ? cancelledAt : 0;
            cancelledAt = 0;
        }
        wasActive = active;
    }

    /**
     * Whether the request FoldFix had to end for an earlier process or another app has ended:
     * rear-display mode is off, and this process has no request of its own under way.
     */
    boolean isInheritedGone(boolean rearActive) {
        return inherited && !ownRequest && !rearActive;
    }

    private void setOwn(boolean own) {
        ownRequest = own;
        if (!own) {
            inherited = false;
            sessionStart = 0;
            sessionActive = false;
        }
        changed.run();
    }
}
