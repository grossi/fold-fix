package app.foldfix;

import static app.foldfix.FakePhone.CLOSED;
import static app.foldfix.FakePhone.NO_READING;
import static app.foldfix.FakePhone.OPEN;
import static app.foldfix.Suite.no;
import static app.foldfix.Suite.same;
import static app.foldfix.Suite.yes;

/** The second look of the trampoline and the curtain, when a launch arrives ({@link Stale}). */
final class StaleTest {
    private StaleTest() {}

    private static final boolean FORCE = true, OURS = true, REAR_ON = true, STATE_CLOSED = true;

    static void tests(Suite t) {
        t.group("Stale, the trampoline's cancel");

        t.test("open with a request of ours: goes through", () ->
                same("why stale", null, Stale.whyCancelStale(!FORCE, OPEN, OURS)));

        t.test("the phone was closed meanwhile: dropped, whoever's request it is", () -> {
            same("ours", "the phone is closed", Stale.whyCancelStale(!FORCE, CLOSED, OURS));
            same("not ours", "the phone is closed", Stale.whyCancelStale(!FORCE, CLOSED, !OURS));
        });

        t.test("nothing of ours left to end: dropped", () ->
                same("why stale", "no request of FoldFix's to end", Stale.whyCancelStale(!FORCE, OPEN, !OURS)));

        t.test("no hall reading (service stopped, or only just connected): goes through if it is ours", () -> {
            same("ours", null, Stale.whyCancelStale(!FORCE, NO_READING, OURS));
            same("not ours", "no request of FoldFix's to end", Stale.whyCancelStale(!FORCE, NO_READING, !OURS));
        });

        t.test("forced (tests over adb): always goes through", () -> {
            for (Boolean hall : new Boolean[]{OPEN, CLOSED, NO_READING}) {
                same("ours, hall " + hall, null, Stale.whyCancelStale(FORCE, hall, OURS));
                same("not ours, hall " + hall, null, Stale.whyCancelStale(FORCE, hall, !OURS));
            }
        });

        t.group("Stale, the trampoline's request");

        t.test("the phone was opened meanwhile: dropped", () ->
                same("why stale", "phone is open", Stale.whyRequestStale(OPEN, !STATE_CLOSED)));

        t.test("still closed: goes through", () -> same("why stale", null, Stale.whyRequestStale(CLOSED, !STATE_CLOSED)));

        t.test("no hall reading: goes through", () ->
                // current behaviour, pinned as found: only a definite "open" stops a request, so one that
                // arrives after the service stopped is still made, with nobody left to confirm it.
                same("why stale", null, Stale.whyRequestStale(NO_READING, !STATE_CLOSED)));

        t.test("Android's own state has become CLOSED meanwhile: dropped, whatever the hall reading", () -> {
            String why = "Android's device state is closed";
            same("hall closed", why, Stale.whyRequestStale(CLOSED, STATE_CLOSED));
            same("no reading", why, Stale.whyRequestStale(NO_READING, STATE_CLOSED));
            same("hall open: that is said first", "phone is open", Stale.whyRequestStale(OPEN, STATE_CLOSED));
        });

        t.group("Stale, the curtain");

        // Started for activation 4, with 3 s to go; "now" is 1000.
        t.test("in time, open, another app's request still the one it was started for: ends it", () ->
                same("why stale", null, Stale.whyCurtainStale(1_000, 4_000, OPEN, REAR_ON, 4, 4, !OURS)));

        t.test("too late: stands down, and says by how much", () -> {
            same("at the deadline", null, Stale.whyCurtainStale(4_000, 4_000, OPEN, REAR_ON, 4, 4, !OURS));
            same("1 ms after", "too late by 1 ms", Stale.whyCurtainStale(4_001, 4_000, OPEN, REAR_ON, 4, 4, !OURS));
            same("757 ms after", "too late by 757 ms", Stale.whyCurtainStale(4_757, 4_000, OPEN, REAR_ON, 4, 4, !OURS));
        });

        t.test("the phone isn't open: stands down", () -> {
            String why = "the phone isn't open, or the service is off";
            same("closed", why, Stale.whyCurtainStale(1_000, 4_000, CLOSED, REAR_ON, 4, 4, !OURS));
            same("no reading", why, Stale.whyCurtainStale(1_000, 4_000, NO_READING, REAR_ON, 4, 4, !OURS));
        });

        t.test("rear display is off already: stands down", () ->
                same("why stale", "rear display is off already",
                        Stale.whyCurtainStale(1_000, 4_000, OPEN, !REAR_ON, 4, 4, !OURS)));

        t.test("rear display went off and on again since: stands down", () ->
                same("why stale", "it went off and on again",
                        Stale.whyCurtainStale(1_000, 4_000, OPEN, REAR_ON, 5, 4, !OURS)));

        t.test("the active request is ours: stands down", () ->
                same("why stale", "the request is FoldFix's",
                        Stale.whyCurtainStale(1_000, 4_000, OPEN, REAR_ON, 4, 4, OURS)));

        t.test("the reasons are checked in that order", () -> {
            same("everything wrong", "too late by 1 ms",
                    Stale.whyCurtainStale(4_001, 4_000, CLOSED, !REAR_ON, 5, 4, OURS));
            same("in time", "the phone isn't open, or the service is off",
                    Stale.whyCurtainStale(1_000, 4_000, CLOSED, !REAR_ON, 5, 4, OURS));
            same("and open", "rear display is off already",
                    Stale.whyCurtainStale(1_000, 4_000, OPEN, !REAR_ON, 5, 4, OURS));
            same("and on", "it went off and on again",
                    Stale.whyCurtainStale(1_000, 4_000, OPEN, REAR_ON, 5, 4, OURS));
        });
    }
}
