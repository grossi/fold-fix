package app.foldfix;

import java.util.ArrayList;
import java.util.List;

/**
 * A phone whose every reading is set by hand, for testing one step of {@link Reconciler} at a
 * time. It notes what the rules do, in order, and how often they look for the dialog.
 */
final class FakePhone implements Reconciler.Rear, Reconciler.Phone {
    static final Boolean CLOSED = true, OPEN = false, NO_READING = null;

    // What the rules read. The defaults are a working phone that is open with nothing going on.
    String backend = "window extensions";
    boolean known = true, available = true, outer = true;
    boolean rear, ours, live, waiting, cancelPending, dialog;
    /** Taking a request over has to wait: one of ours has just ended (see RearDisplay.adopt). */
    boolean ending;
    long endingCancelAt;
    int activations;
    /** Far from 0, as on a phone that has been on for a while, and apart from each other. */
    long uptime = 1_000_000, realtime = 5_000_000;

    final Reconciler rules = new Reconciler(this, this);
    final List<String> did = new ArrayList<>();
    int dialogLooks;
    Boolean landscapeFix;
    /** The delay of the step's last "look again", -1 if it asked for none. */
    long lookAgain = -1;

    /** One reconcile; returns what it did, one thing per line. */
    String step(Boolean closed) {
        did.clear();
        dialogLooks = 0;
        landscapeFix = null;
        lookAgain = -1;
        rules.reconcile(closed);
        return String.join("\n", did);
    }

    /**
     * Reconciles now and then whenever the rules ask to look again, for {@code ms}. Returns when
     * the trampoline was launched, in ms from the start.
     */
    List<Long> launchTimes(Boolean closed, long ms) {
        List<Long> times = new ArrayList<>();
        long start = uptime;
        while (true) {
            if (step(closed).contains("launch ")) times.add(uptime - start);
            if (lookAgain < 0 || uptime + lookAgain > start + ms) return times;
            pass(lookAgain);
        }
    }

    /** Time passes, awake. */
    void pass(long ms) {
        uptime += ms;
        realtime += ms;
    }

    boolean did(String what) {
        for (String d : did) if (d.startsWith(what)) return true;
        return false;
    }

    // ---- Reconciler.Rear ----------------------------------------------------------------

    @Override public String backend() { return backend; }
    @Override public boolean isKnown() { return known; }
    @Override public boolean isRearActive() { return rear; }
    @Override public boolean isAvailable() { return available; }
    @Override public boolean hasOwnRequest() { return ours; }
    @Override public boolean isSessionLive() { return live; }
    @Override public boolean isSessionWaiting() { return waiting; }
    @Override public boolean isCancelPending() { return cancelPending; }
    @Override public long endingCancelAt() { return endingCancelAt; }
    @Override public int activations() { return activations; }
    @Override
    public boolean adopt() {
        if (!ending) did.add("adopt");
        return !ending;
    }

    @Override public void adoptOnOpen() { did.add("adopt on open"); }

    // ---- Reconciler.Phone ---------------------------------------------------------------

    @Override public long uptime() { return uptime; }
    @Override public long realtime() { return realtime; }
    @Override public boolean outerDisplayPresent() { return outer; }

    @Override
    public boolean tapDialog(boolean confirm) {
        dialogLooks++;
        if (dialog) did.add(confirm ? "tap Confirm" : "tap Cancel");
        return dialog;
    }

    @Override public void launch(String action) { did.add("launch " + action + " (try " + rules.tries + ")"); }
    @Override public void startCurtain(long deadline, int activation) {
        did.add("curtain for activation " + activation + ", until " + (deadline - realtime) + " ms from now");
    }
    @Override public void setLandscapeFix(boolean active) { landscapeFix = active; }
    @Override public void setStatus(String s) { did.add("status: " + s); }
    @Override public void log(String line) { did.add("log: " + line); }

    @Override
    public void lookAgainIn(long ms) {
        lookAgain = ms;
        did.add("look again in " + ms + " ms");
    }
}
