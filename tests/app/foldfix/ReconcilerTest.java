package app.foldfix;

import static app.foldfix.FakePhone.CLOSED;
import static app.foldfix.FakePhone.NO_READING;
import static app.foldfix.FakePhone.OPEN;
import static app.foldfix.Suite.lines;
import static app.foldfix.Suite.no;
import static app.foldfix.Suite.same;
import static app.foldfix.Suite.yes;

import java.util.Arrays;

/**
 * One step of the reconcile loop at a time: for a hall reading and a state of the phone, what
 * {@link Reconciler} does, in which order, and when it looks again.
 */
final class ReconcilerTest {
    private ReconcilerTest() {}

    static void tests(Suite t) {
        table(t);
        followUp(t);
        timing(t);
        guards(t);
        dialog(t);
        always(t);
    }

    /** The table in docs/how-it-works.md, "The reconcile loop", row by row. */
    private static void table(Suite t) {
        t.group("Reconciler, the table in the docs");

        t.test("closed, rear display on through our request: nothing to do", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = true;
            same("step", "status: Closed: outer screen on", p.step(CLOSED));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("closed, rear display on through another app's request: take it over", () -> {
            FakePhone p = new FakePhone();
            p.rear = true;
            same("step", lines("adopt", "status: Closed: outer screen on"), p.step(CLOSED));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("closed, on, one of ours ended a moment ago: no take-over yet, look again in 2 s", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ending = true;  // its callback came before rear display is reported off
            same("step", lines("look again in 2000 ms", "status: Closed: outer screen on"), p.step(CLOSED));
            p.ending = false;          // it stayed on: another request took its place
            same("2 s later", lines("adopt", "status: Closed: outer screen on"), p.step(CLOSED));
        });

        t.test("opened with rear display on: the active request is taken over before the step", () -> {
            FakePhone p = new FakePhone();
            p.rear = true;
            p.rules.onFold(true);
            same("on open", Arrays.asList("adopt on open"), p.did);
            p.did.clear();
            p.rules.onFold(false);  // closed, or the first reading: an open phone is left as it is
            same("otherwise", Arrays.asList(), p.did);
            p.known = false;
            p.rules.onFold(true);
            same("device state not known yet", Arrays.asList(), p.did);
        });

        t.test("closed, on through another app's request, ours still waiting: confirm the dialog once it shows", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = p.live = p.waiting = true;
            same("dialog not up yet", "status: Closed: outer screen on", p.step(CLOSED));
            same("looks for the dialog", 1, p.dialogLooks);
            p.dialog = true;
            same("dialog up", lines("tap Confirm", "status: Closed: outer screen on"), p.step(CLOSED));
        });

        t.test("closed, rear display off, our request waiting: confirm the dialog once it shows", () -> {
            FakePhone p = new FakePhone();
            p.ours = p.live = p.waiting = true;
            same("dialog not up yet",
                    lines("status: Closed: switching to the outer screen", "look again in 2000 ms"), p.step(CLOSED));
            p.dialog = true;
            same("dialog up",
                    lines("tap Confirm", "status: Closed: switching to the outer screen", "look again in 2000 ms"),
                    p.step(CLOSED));
        });

        t.test("closed, rear display off, no request in flight: request", () -> {
            FakePhone p = new FakePhone();
            same("step", lines("launch request (try 1)", "status: Closed: switching to the outer screen",
                    "look again in 200 ms"), p.step(CLOSED));
            same("looks for the dialog (nothing is ours yet)", 0, p.dialogLooks);
        });

        t.test("open, rear display on through our request or one taken over: end it", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = true;
            same("step", lines("launch cancel (try 1)", "status: Open: switching back to the inner screen",
                    "look again in 200 ms"), p.step(OPEN));
        });

        t.test("open, our request waiting for its dialog: tap Cancel when it shows, wait until then", () -> {
            FakePhone p = new FakePhone();
            p.ours = p.live = p.waiting = true;
            // No timer: the dialog showing up is an accessibility event.
            same("dialog not up yet", "status: Open: waiting for the request's dialog to cancel it", p.step(OPEN));
            p.dialog = true;
            same("dialog up", lines("tap Cancel", "status: Open: switching back to the inner screen",
                    "look again in 2000 ms"), p.step(OPEN));
        });

        t.test("open, another app's request, the first within 3 s of our cancel: end it too, behind the curtain", () -> {
            FakePhone p = afterOurCancel(1_400);
            p.activations = 2;
            same("step", lines(
                    "log: another app turned rear display on 1400 ms after our cancel: ending that once",
                    "curtain for activation 2, until 1600 ms from now",
                    "status: Open: another app is using the outer screen"), p.step(OPEN));
        });

        t.test("open, another app's request made while open: left alone", () -> {
            FakePhone p = new FakePhone();
            p.rear = true;
            p.activations = 1;
            same("step", "status: Open: another app is using the outer screen", p.step(OPEN));
            same("looks for the dialog", 0, p.dialogLooks);
            p.pass(20_000);
            same("20 s later", "status: Open: another app is using the outer screen", p.step(OPEN));
        });

        t.test("open, rear display off, nothing of ours: nothing to do", () -> {
            FakePhone p = new FakePhone();
            same("step", "status: Open: inner screen on", p.step(OPEN));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("open, nothing of ours, but a dialog within 3 s of our last request: tap Cancel", () -> {
            FakePhone p = new FakePhone();
            p.dialog = true;
            p.rules.lastRequest = p.uptime - 2_999;
            same("2999 ms after the request", lines("tap Cancel",
                    "status: Open: switching back to the inner screen", "look again in 2000 ms"), p.step(OPEN));
            p.pass(1);
            same("3000 ms after it", "status: Open: inner screen on", p.step(OPEN));
            same("looks for the dialog", 0, p.dialogLooks);
        });
    }

    /**
     * A phone that is open, on which a cancel of ours ended rear-display mode and another app
     * turned it on again {@code ms} after that cancel was made.
     */
    private static FakePhone afterOurCancel(long ms) {
        FakePhone p = new FakePhone();
        p.rules.cancelSentAt = p.realtime - ms - 30;  // the service sent it a moment before it was made
        p.endingCancelAt = p.realtime - ms;
        p.rear = true;
        p.activations = 1;
        return p;
    }

    private static void followUp(Suite t) {
        t.group("Reconciler, another app's request after our cancel");

        t.test("the second one of an opening is left alone", () -> {
            FakePhone p = afterOurCancel(600);
            yes("the first is followed up", p.step(OPEN).contains("ending that once"));
            // The curtain's cancel ended it, and the other app asks once more.
            p.pass(900);
            p.endingCancelAt = p.realtime - 700;
            p.activations = 2;
            same("step", lines("log: another app turned rear display on 700 ms after our cancel: left alone",
                    "status: Open: another app is using the outer screen"), p.step(OPEN));
        });

        t.test("each request is looked at once", () -> {
            FakePhone p = afterOurCancel(600);
            p.step(OPEN);
            same("the same request again", "status: Open: another app is using the outer screen", p.step(OPEN));
            p.pass(10_000);
            same("still the same, later", "status: Open: another app is using the outer screen", p.step(OPEN));
        });

        t.test("the window is 3 s from our cancel: 2999 ms is in, 3000 ms is out", () -> {
            yes("2999 ms", afterOurCancel(2_999).step(OPEN).contains("2999 ms after our cancel: ending that once"));
            FakePhone late = afterOurCancel(3_000);
            same("3000 ms", lines("log: another app turned rear display on 3000 ms after our cancel: left alone",
                    "status: Open: another app is using the outer screen"), late.step(OPEN));
            no("the follow-up is used up by one that was left alone", late.rules.followedUp);
        });

        t.test("one that was too late doesn't use up the follow-up, but a later one is too late as well", () -> {
            FakePhone p = afterOurCancel(3_536);
            yes("left alone", p.step(OPEN).contains("3536 ms after our cancel: left alone"));
            p.pass(5_000);
            p.activations = 2;
            yes("left alone again", p.step(OPEN).contains("8536 ms after our cancel: left alone"));
        });

        t.test("the window is real time: a phone that slept through it leaves the request alone", () -> {
            FakePhone p = afterOurCancel(500);
            p.realtime += 5_000;  // asleep: uptime stands still
            yes("left alone", p.step(OPEN).contains("5500 ms after our cancel: left alone"));
        });

        t.test("nothing follows a switch-off that wasn't our cancel's doing", () -> {
            FakePhone p = afterOurCancel(600);
            p.endingCancelAt = 0;  // the status screen's button, System UI's control, the other app
            same("step", "status: Open: another app is using the outer screen", p.step(OPEN));
        });

        t.test("nothing follows a cancel from before this opening", () -> {
            FakePhone p = afterOurCancel(600);
            p.rules.cancelSentAt = p.realtime - 100;  // this opening's cancel came later and ended nothing
            same("an earlier cancel's switch-off", "status: Open: another app is using the outer screen", p.step(OPEN));
            p.rules.cancelSentAt = 0;                 // or no cancel was sent since the phone was opened
            same("no cancel this opening", "status: Open: another app is using the outer screen", p.step(OPEN));
        });

        t.test("nothing follows while the request is ours: that one is simply ended", () -> {
            FakePhone p = afterOurCancel(600);
            p.ours = true;
            p.rules.lastLaunch = p.uptime - 630;
            same("step", lines("status: Open: switching back to the inner screen", "look again in 200 ms"),
                    p.step(OPEN));
        });

        t.test("closing and opening again allows one more follow-up", () -> {
            FakePhone p = afterOurCancel(600);
            p.step(OPEN);
            yes("used up", p.rules.followedUp);
            p.rules.onFold(false);
            no("used up after a fold", p.rules.followedUp);
            same("this opening's cancel is forgotten", 0L, p.rules.cancelSentAt);
            same("requests looked at", 0, p.rules.otherSeen);
        });
    }

    private static void timing(Suite t) {
        t.group("Reconciler, timing");

        t.test("retries come every 2 s for 5 tries, then every 30 s", () -> {
            FakePhone p = new FakePhone();
            for (int tries = 0; tries < 5; tries++) {
                p.rules.tries = tries;
                same("delay after " + tries + " tries", 2_000L, p.rules.retryDelay());
            }
            p.rules.tries = 5;
            same("delay after 5 tries", 30_000L, p.rules.retryDelay());
            p.rules.tries = 12;
            same("delay after 12 tries", 30_000L, p.rules.retryDelay());
        });

        t.test("a launch isn't repeated until the retry delay is over", () -> {
            FakePhone p = new FakePhone();
            p.step(CLOSED);
            p.pass(2_000);
            no("2000 ms after the launch", p.step(CLOSED).contains("launch"));
            p.pass(1);
            yes("2001 ms after the launch", p.step(CLOSED).contains("launch request (try 2)"));
        });

        t.test("within 3 s of a launch it looks again every 200 ms, after that at the retry delay", () -> {
            FakePhone p = new FakePhone();
            p.ours = p.live = true;  // our request is in flight, so nothing is launched
            p.rules.lastLaunch = p.uptime - 2_999;
            yes("2999 ms after the launch", p.step(CLOSED).endsWith("look again in 200 ms"));
            p.pass(1);
            yes("3000 ms after the launch", p.step(CLOSED).endsWith("look again in 2000 ms"));
            p.rules.tries = 5;
            yes("after 5 tries", p.step(CLOSED).endsWith("look again in 30000 ms"));
        });

        t.test("requests that never get through: when they are sent", () -> {
            FakePhone p = new FakePhone();
            // current behaviour, pinned as found: the spacing is 2.2 s and 33 s where the docs say
            // 2 s and 30 s. 2.2 s, because a retry needs more than 2 s since the last launch and the
            // loop looks every 200 ms then: at 2000 ms it is too early. 33 s, because the 30 s timer
            // is only set once the 3 s of looking every 200 ms are over.
            same("launch times in ms", Arrays.asList(0L, 2_200L, 4_400L, 6_600L, 8_800L, 41_800L, 74_800L),
                    p.launchTimes(CLOSED, 100_000));
            same("tries", 7, p.rules.tries);
        });

        t.test("cancels that never get through follow the same schedule", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = true;
            same("launch times in ms", Arrays.asList(0L, 2_200L, 4_400L, 6_600L, 8_800L, 41_800L, 74_800L),
                    p.launchTimes(OPEN, 100_000));
        });

        t.test("nothing is resent while our request is in flight", () -> {
            FakePhone p = new FakePhone();
            p.ours = p.live = true;
            same("launches in 9 s", Arrays.asList(), p.launchTimes(CLOSED, 9_000));
            p.live = false;  // 10 s without a dialog: given up on (see Ownership)
            yes("once it is given up on", p.step(CLOSED).contains("launch request (try 1)"));
        });

        t.test("each fold or unfold starts the schedule over", () -> {
            FakePhone p = new FakePhone();
            p.launchTimes(CLOSED, 20_000);
            same("tries", 5, p.rules.tries);
            p.rules.onFold(false);
            same("tries after a fold", 0, p.rules.tries);
            p.rear = p.ours = true;
            yes("a cancel goes out at once", p.step(OPEN).startsWith("launch cancel (try 1)"));
        });

        t.test("a fold doesn't forget when the last request was made", () -> {
            FakePhone p = new FakePhone();
            p.step(CLOSED);
            p.pass(500);
            p.rules.onFold(false);
            same("last request", p.uptime - 500, p.rules.lastRequest);
            p.dialog = true;  // its dialog can still pop up after the phone was opened
            yes("the dialog is cancelled", p.step(OPEN).startsWith("tap Cancel"));
        });

        t.test("the schedule also starts over once the phone is where it should be", () -> {
            FakePhone p = new FakePhone();
            p.rules.tries = 5;
            p.rear = p.ours = true;
            p.step(CLOSED);
            same("tries, closed with rear display on", 0, p.rules.tries);
            p.rules.tries = 5;
            p.rear = p.ours = false;
            p.step(OPEN);
            same("tries, open with nothing of ours", 0, p.rules.tries);
        });

        t.test("a launch is counted and timed before the trampoline is started", () -> {
            FakePhone p = new FakePhone();
            p.step(CLOSED);
            same("last launch", p.uptime, p.rules.lastLaunch);
            same("last request", p.uptime, p.rules.lastRequest);
            same("cancel sent", 0L, p.rules.cancelSentAt);

            FakePhone q = new FakePhone();
            q.rear = q.ours = true;
            q.step(OPEN);
            same("last launch", q.uptime, q.rules.lastLaunch);
            same("last request (a cancel isn't one)", 0L, q.rules.lastRequest);
            same("cancel sent, in real time", q.realtime, q.rules.cancelSentAt);
        });

        t.test("of an opening's cancels, the first one's time is kept", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = true;
            p.step(OPEN);
            long first = p.rules.cancelSentAt;
            p.pass(2_200);
            yes("a second cancel", p.step(OPEN).contains("launch cancel (try 2)"));
            same("cancel sent", first, p.rules.cancelSentAt);
        });

        t.test("open, our cancel made but rear display still on: not another app's yet, look again in 2 s", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.cancelPending = true;
            same("step", lines("look again in 2000 ms", "status: Open: switching back to the inner screen"),
                    p.step(OPEN));
            p.cancelPending = false;
            same("it stayed on", "status: Open: another app is using the outer screen", p.step(OPEN));
        });

        t.test("open, rear display off: a cancel still counted as pending changes nothing", () -> {
            FakePhone p = new FakePhone();
            p.cancelPending = true;  // RearDisplay forgets the cancel when it sees rear display go off
            same("step", "status: Open: inner screen on", p.step(OPEN));
        });
    }

    private static void guards(Suite t) {
        t.group("Reconciler, when not to act");

        t.test("no hall reading yet: nothing at all", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = true;
            same("step", "", p.step(NO_READING));
            same("landscape fix touched", null, p.landscapeFix);
        });

        t.test("no backend: say so and stop", () -> {
            FakePhone p = new FakePhone();
            p.backend = null;
            same("closed", "status: No rear-display access: run the one-time adb command", p.step(CLOSED));
            same("open", "status: No rear-display access: run the one-time adb command", p.step(OPEN));
        });

        t.test("device state not known yet: do nothing, and don't look again", () -> {
            FakePhone p = new FakePhone();
            p.known = false;
            same("closed", "", p.step(CLOSED));
            p.rear = p.ours = true;
            same("open", "", p.step(OPEN));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("outer screen not detected: never request, look again in 5 s", () -> {
            FakePhone p = new FakePhone();
            p.outer = false;
            same("step", lines("status: Outer screen not detected: restart the phone while it is closed",
                    "look again in 5000 ms"), p.step(CLOSED));
            same("launches in 2 minutes", Arrays.asList(), p.launchTimes(CLOSED, 120_000));
        });

        t.test("rear-display mode unavailable: wait, look again in 5 s, request once it is back", () -> {
            FakePhone p = new FakePhone();
            p.available = false;
            same("step", lines("status: Rear-display mode is unavailable right now (battery saver or heat?)",
                    "look again in 5000 ms"), p.step(CLOSED));
            same("launches in a minute", Arrays.asList(), p.launchTimes(CLOSED, 60_000));
            p.available = true;
            yes("available again", p.step(CLOSED).startsWith("launch request (try 1)"));
        });

        t.test("a missing outer screen is reported before unavailability", () -> {
            FakePhone p = new FakePhone();
            p.outer = p.available = false;
            yes("status", p.step(CLOSED).startsWith("status: Outer screen not detected"));
        });

        t.test("neither matters once rear display is on, or while the phone is open", () -> {
            FakePhone p = new FakePhone();
            p.outer = p.available = false;
            p.rear = p.ours = true;
            same("closed and on", "status: Closed: outer screen on", p.step(CLOSED));
            yes("open with our request", p.step(OPEN).startsWith("launch cancel"));
        });

        t.test("while blocked, a dialog of ours isn't looked for", () -> {
            FakePhone p = new FakePhone();
            p.available = false;
            p.ours = p.live = p.waiting = p.dialog = true;
            // current behaviour, pinned as found: a request made just before rear-display mode became
            // unavailable keeps its dialog until it is available again.
            no("tapped", p.step(CLOSED).contains("tap"));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("the landscape fix is on exactly while closed with rear display on", () -> {
            FakePhone p = new FakePhone();
            p.step(CLOSED);
            same("closed, rear display off", false, p.landscapeFix);
            p.rear = true;
            p.step(CLOSED);
            same("closed, rear display on", true, p.landscapeFix);
            p.step(OPEN);
            same("open, rear display on", false, p.landscapeFix);
            p.known = false;
            p.step(CLOSED);
            same("closed, on, before the state is known", true, p.landscapeFix);
            p.backend = null;
            p.step(CLOSED);
            same("closed, on, no backend", true, p.landscapeFix);
        });
    }

    private static void dialog(Suite t) {
        t.group("Reconciler, the dialog");

        t.test("ours overtaken by another app's request while closed: polled for 3 s after the launch", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = p.live = p.waiting = true;
            p.rules.lastLaunch = p.uptime - 2_999;
            same("2999 ms after the launch", lines("look again in 200 ms", "status: Closed: outer screen on"),
                    p.step(CLOSED));
            p.dialog = true;
            same("the dialog shows", lines("tap Confirm", "look again in 200 ms", "status: Closed: outer screen on"),
                    p.step(CLOSED));
            p.pass(1);
            // After that no timer: the dialog showing up is an accessibility event.
            same("3000 ms after the launch", lines("tap Confirm", "status: Closed: outer screen on"), p.step(CLOSED));
        });

        t.test("ours overtaken, and given up on after 10 s: a dialog is no longer looked for", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = p.dialog = true;
            p.live = p.waiting = false;
            // By then the request is taken for lost. On the phone its dialog showed within half a
            // second each time and was confirmed; one that went with the screen turning off didn't
            // come back. A dialog up at this point couldn't be told from another app's.
            same("step", "status: Closed: outer screen on", p.step(CLOSED));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("closed, rear display off: a dialog is only confirmed while a request is ours", () -> {
            FakePhone p = new FakePhone();
            p.dialog = true;  // another app's, for all FoldFix knows
            no("tapped", p.step(CLOSED).contains("tap"));
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("closed: the marked request's dialog is confirmed, also before the request was accepted", () -> {
            FakePhone p = new FakePhone();
            p.ours = p.dialog = true;  // the trampoline was launched, nothing is known of it yet
            p.rules.lastLaunch = p.uptime - 100;
            same("step", lines("tap Confirm", "status: Closed: switching to the outer screen", "look again in 200 ms"),
                    p.step(CLOSED));
        });

        t.test("a confirmed dialog isn't followed by a new request in the same step", () -> {
            FakePhone p = new FakePhone();
            p.ours = p.dialog = true;
            p.live = false;  // retry due, and the request given up on
            same("step", lines("tap Confirm", "status: Closed: switching to the outer screen", "look again in 2000 ms"),
                    p.step(CLOSED));
        });

        t.test("open, our request active and a dialog up: the dialog is cancelled first", () -> {
            FakePhone p = new FakePhone();
            p.rear = p.ours = p.dialog = true;
            // current behaviour, pinned as found: whose dialog it is can't be told. The cancel of the
            // active request waits for a step in which no dialog is up.
            same("step", lines("tap Cancel", "status: Open: switching back to the inner screen", "look again in 2000 ms"),
                    p.step(OPEN));
            p.dialog = false;
            yes("then the cancel", p.step(OPEN).startsWith("launch cancel (try 1)"));
        });

        t.test("open, only the mark of a request that was never made: waits for a dialog", () -> {
            FakePhone p = new FakePhone();
            p.ours = true;  // marked, the trampoline hasn't arrived
            // current behaviour, pinned as found: without a timer, so the status stays until the
            // trampoline arrives and drops the mark, or the phone is closed.
            same("step", "status: Open: waiting for the request's dialog to cancel it", p.step(OPEN));
        });

        t.test("opened 300 ms after our request: looks for its dialog for 3 s, also once nothing is ours", () -> {
            FakePhone p = new FakePhone();
            p.step(CLOSED);  // request launched
            p.pass(300);
            p.rules.onFold(false);
            p.ours = true;
            // current behaviour, pinned as found: the fold reset the launch time, so it looks again in
            // 2 s, not 200 ms. The dialog is found sooner through its accessibility event.
            same("ours, no dialog yet", lines("status: Open: switching back to the inner screen", "look again in 2000 ms"),
                    p.step(OPEN));
            p.ours = false;
            same("the mark was dropped", lines("status: Open: switching back to the inner screen", "look again in 2000 ms"),
                    p.step(OPEN));
            p.pass(2_700);
            same("3 s after the request", "status: Open: inner screen on", p.step(OPEN));
        });

        t.test("an accessibility event only leads to a step where a dialog would be answered", () -> {
            FakePhone p = new FakePhone();
            no("no hall reading", p.rules.dialogMatters(NO_READING));
            p.known = false;
            no("state unknown", p.rules.dialogMatters(CLOSED));
            p.known = true;
            yes("closed, rear display off", p.rules.dialogMatters(CLOSED));
            p.rear = true;
            no("closed, rear display on", p.rules.dialogMatters(CLOSED));
            p.waiting = true;
            yes("closed, rear display on, our request waiting", p.rules.dialogMatters(CLOSED));
            no("open, nothing of ours", p.rules.dialogMatters(OPEN));
            p.ours = true;
            yes("open, a request of ours", p.rules.dialogMatters(OPEN));
            p.ours = false;
            p.rules.lastRequest = p.uptime - 2_999;
            yes("open, 2999 ms after our last request", p.rules.dialogMatters(OPEN));
            p.pass(1);
            no("open, 3000 ms after it", p.rules.dialogMatters(OPEN));
        });
    }

    /** One of the states {@link #everyState} goes through, as it was before the step. */
    private static final class State {
        boolean closed;
        /** The last launch was a second ago; with {@code manyTries}, it was the fifth, at most 10 s ago. */
        boolean recentLaunch, manyTries;
        /** Our last request was made a second ago. */
        boolean recentRequest;
        /** A cancel of ours, sent since the phone was opened, ended rear-display mode 1.4 s ago. */
        boolean cancelEnded;
        String did;
    }

    private interface Each {
        void check(FakePhone p, State s);
    }

    /**
     * Runs one step in every combination of what the rules read (also combinations RearDisplay
     * never reports), and of the timings that matter.
     */
    private static void everyState(Each each) {
        for (int bits = 0; bits < 1 << 16; bits++) {
            FakePhone p = new FakePhone();
            State s = new State();
            s.closed = bit(bits, 0);
            p.known = bit(bits, 1);
            p.rear = bit(bits, 2);
            p.ours = bit(bits, 3);
            p.live = bit(bits, 4);
            p.waiting = bit(bits, 5);
            p.cancelPending = bit(bits, 6);
            p.available = bit(bits, 7);
            p.outer = bit(bits, 8);
            p.dialog = bit(bits, 9);
            if (bit(bits, 10)) p.backend = null;
            s.recentLaunch = bit(bits, 11);
            if (s.recentLaunch) p.rules.lastLaunch = p.uptime - 1_000;
            s.recentRequest = bit(bits, 12);
            if (s.recentRequest) p.rules.lastRequest = p.uptime - 1_000;
            s.manyTries = bit(bits, 13);
            if (s.manyTries) {
                p.rules.tries = 5;
                if (!s.recentLaunch) p.rules.lastLaunch = p.uptime - 10_000;
            }
            p.ending = bit(bits, 15);
            s.cancelEnded = bit(bits, 14);
            if (s.cancelEnded) {
                p.rules.cancelSentAt = p.realtime - 1_500;
                p.endingCancelAt = p.realtime - 1_400;
                p.activations = 1;
            }
            boolean matters = p.rules.dialogMatters(s.closed);
            s.did = p.step(s.closed);
            try {
                each.check(p, s);
                if (p.dialogLooks > 0) yes("an accessibility event leads to a step here", matters);
            } catch (Suite.Failure f) {
                throw new Suite.Failure(f.getMessage() + "\nin state: closed=" + s.closed + " known=" + p.known
                        + " backend=" + p.backend + " rear=" + p.rear + " ours=" + p.ours + " live=" + p.live
                        + " waiting=" + p.waiting + " cancelPending=" + p.cancelPending + " ending=" + p.ending
                        + " available=" + p.available
                        + " outer=" + p.outer + " dialog=" + p.dialog + " recentLaunch=" + s.recentLaunch
                        + " recentRequest=" + s.recentRequest + " manyTries=" + s.manyTries
                        + " cancelEnded=" + s.cancelEnded + "\nit did: " + s.did.replace("\n", "; "));
            }
        }
    }

    private static boolean bit(int bits, int n) {
        return (bits >> n & 1) != 0;
    }

    /** What must hold in every state, whatever else is going on. */
    private static void always(Suite t) {
        t.group("Reconciler, in every state");

        t.test("closed: never a cancel, never the curtain; open: never a request, a confirm or a take-over", () -> everyState((p, s) -> {
            if (s.closed) {
                no("launch cancel", p.did("launch cancel"));
                no("tap Cancel", p.did("tap Cancel"));
                no("curtain", p.did("curtain"));
            } else {
                no("launch request", p.did("launch request"));
                no("tap Confirm", p.did("tap Confirm"));
                no("adopt", p.did("adopt"));
            }
        }));

        t.test("never a request without the outer screen, or while rear-display mode is unavailable", () -> everyState((p, s) -> {
            if (!p.outer || !p.available) no("launch request", p.did("launch request"));
        }));

        t.test("never a request while rear display is on or our request is in flight", () -> everyState((p, s) -> {
            if (p.rear || p.live) no("launch request", p.did("launch request"));
        }));

        t.test("open: another app's request is never cancelled by the trampoline", () -> everyState((p, s) -> {
            if (!s.closed && !p.ours) no("launch cancel", p.did("launch cancel"));
        }));

        t.test("open: a cancel is only launched for an active request", () -> everyState((p, s) -> {
            if (!p.rear) no("launch cancel", p.did("launch cancel"));
        }));

        t.test("nothing is launched within 2 s of the last launch, or within 30 s after 5 tries", () -> everyState((p, s) -> {
            if (s.recentLaunch || s.manyTries) no("launch", p.did("launch"));
        }));

        t.test("without a backend or a known state: no launch, no tap, no take-over, no timer", () -> everyState((p, s) -> {
            if (p.backend != null && p.known) return;
            same("step", p.backend == null ? "status: No rear-display access: run the one-time adb command" : "", s.did);
            same("looks for the dialog", 0, p.dialogLooks);
        }));

        t.test("the dialog is looked for once at most, and only where a dialog would be answered", () -> everyState((p, s) -> {
            boolean acting = p.backend != null && p.known;
            boolean where = !acting ? false
                    : !s.closed ? p.ours || s.recentRequest
                    : p.rear ? p.waiting
                    : p.ours && p.outer && p.available;
            same("looks for the dialog", where ? 1 : 0, p.dialogLooks);
            same("taps it", where && p.dialog, p.did("tap "));
        }));

        t.test("a step launches once at most, and not when it tapped the dialog", () -> everyState((p, s) -> {
            int launches = s.did.split("launch ", -1).length - 1;
            yes("one launch at most", launches <= 1);
            if (p.did("tap ")) same("launches after a tap", 0, launches);
        }));

        t.test("after a launch it looks again in 200 ms", () -> everyState((p, s) -> {
            if (p.did("launch ")) yes("ends with a look in 200 ms", s.did.endsWith("look again in 200 ms"));
        }));

        t.test("the curtain only for another app's request on the open phone, after a cancel of ours", () -> everyState((p, s) -> {
            same("curtain", !s.closed && p.backend != null && p.known && p.rear && !p.ours && s.cancelEnded,
                    p.did("curtain"));
        }));

        t.test("a take-over only on the closed phone, of an active request that isn't ours", () -> everyState((p, s) -> {
            boolean where = s.closed && p.backend != null && p.known && p.rear && !p.ours;
            same("adopt", where && !p.ending, p.did("adopt"));
            // Held up by a request of ours that has just ended: looked at again once that is settled.
            if (where && p.ending) yes("looks again in 2 s", s.did.contains("look again in 2000 ms"));
        }));

        t.test("every step that acts ends with a status, and one status only", () -> everyState((p, s) -> {
            if (p.backend == null || !p.known) return;
            same("statuses", 1, s.did.split("status: ", -1).length - 1);
        }));
    }
}
