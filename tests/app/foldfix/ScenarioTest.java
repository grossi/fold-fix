package app.foldfix;

import static app.foldfix.SimPhone.CLOSED;
import static app.foldfix.SimPhone.FOLDFIX;
import static app.foldfix.SimPhone.NOBODY;
import static app.foldfix.SimPhone.OPEN;
import static app.foldfix.SimPhone.OPENED;
import static app.foldfix.SimPhone.OTHER;
import static app.foldfix.SimPhone.REAR;
import static app.foldfix.SimPhone.REAR_OUTER_DEFAULT;
import static app.foldfix.Suite.lines;
import static app.foldfix.Suite.no;
import static app.foldfix.Suite.same;
import static app.foldfix.Suite.yes;

/**
 * Whole sequences, as they were tested by hand on a phone for earlier changes: the rules working
 * together on a model of the phone ({@link SimPhone}). Each step says what FoldFix logs for it.
 */
final class ScenarioTest {
    private ScenarioTest() {}

    static void tests(Suite t) {
        foldAndUnfold(t);
        otherApps(t);
        camera(t);
        lateLaunches(t);
        blocked(t);
        restarts(t);
    }

    /** Where the phone has ended up: the device state, who made the active request, whether it is ours to end. */
    private static void ends(SimPhone p, int state, String holder, boolean ours) {
        same("device state", state, p.state);
        same("the active request is of", holder, p.holder);
        same("FoldFix has a request to end", ours, p.own.hasOwnRequest());
        same("saved for the next process", ours, p.saved);
        same("trampolines still under way", 0, p.launchesUnderWay());
    }

    private static void foldAndUnfold(Suite t) {
        t.group("Scenarios, folding and unfolding");

        t.test("closing: request, the dialog shows, confirm", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            same("on close", lines("hall closed", "request rear display (try 1)",
                    "status: Closed: switching to the outer screen"), p.took());
            p.trampoline();
            same("the trampoline arrives", "rear display requested", p.took());
            p.after(150);
            p.dialogShows();
            same("the dialog shows", lines("clicked Confirm", "status: Closed: outer screen on"), p.took());
            ends(p, REAR, FOLDFIX, true);
            same("landscape fix", true, p.landscapeFix);
            p.after(600_000);
            same("ten quiet minutes", "", p.took());
            yes("nothing scheduled", p.nothingScheduled());
        });

        t.test("opening: cancel our request", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            same("on open", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen"), p.took());
            p.trampoline();
            same("the trampoline arrives", lines("rear display request cancelled", "status: Open: inner screen on"),
                    p.took());
            ends(p, OPENED, NOBODY, false);
            same("landscape fix", false, p.landscapeFix);
            p.after(600_000);
            same("ten quiet minutes", "", p.took());
        });

        t.test("ten times closed and opened: one request and one cancel each time", () -> {
            SimPhone p = new SimPhone();
            for (int cycle = 1; cycle <= 10; cycle++) {
                p.hall(CLOSED);
                p.trampoline();
                p.after(170);
                p.dialogShows();
                same("closing " + cycle, lines("hall closed", "request rear display (try 1)",
                        "status: Closed: switching to the outer screen", "rear display requested",
                        "clicked Confirm", "status: Closed: outer screen on"), p.took());
                ends(p, REAR, FOLDFIX, true);
                p.after(4_000);
                p.hall(OPEN);
                p.trampoline();
                same("opening " + cycle, lines("hall open", "cancel rear display (try 1)",
                        "status: Open: switching back to the inner screen", "rear display request cancelled",
                        "status: Open: inner screen on"), p.took());
                ends(p, OPENED, NOBODY, false);
                p.after(4_000);
            }
        });

        t.test("opened before the dialog showed: Cancel is tapped in it when it does", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.took();
            p.after(100);
            p.hall(OPEN);
            same("on open", lines("hall open", "status: Open: switching back to the inner screen"), p.took());
            p.after(150);
            p.dialogShows();
            same("the dialog shows", "clicked Cancel", p.took());
            ends(p, OPENED, NOBODY, false);
            p.after(5_000);
            same("once 3 s have passed since the request", "status: Open: inner screen on", p.took());
        });

        t.test("opened before the dialog showed, and it shows more than 3 s after the request", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.after(100);
            p.hall(OPEN);
            p.after(5_000);
            same("status", "Open: waiting for the request's dialog to cancel it", p.status);
            p.took();
            p.dialogShows();
            same("the dialog shows", lines("clicked Cancel", "status: Open: switching back to the inner screen",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("closed, opened and closed again before the dialog showed: the one request is confirmed", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.after(100);
            p.hall(OPEN);
            p.after(100);
            p.hall(CLOSED);
            p.took();
            p.after(100);
            p.dialogShows();
            same("the dialog shows", lines("clicked Confirm", "status: Closed: outer screen on"), p.took());
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("a request the system refuses is sent again: 5 tries, then a long wait", () -> {
            SimPhone p = new SimPhone();
            p.topApp = false;
            p.hall(CLOSED);
            int tries = 0;
            for (int ms = 0; ms <= 45_000; ms += 100) {
                while (p.trampoline()) tries++;
                if (ms == 10_000) same("tries in 10 s", 5, tries);
                if (ms == 40_000) same("tries in 40 s", 5, tries);
                p.after(100);
            }
            same("tries in 45 s", 6, tries);
            no("anything is ours", p.own.hasOwnRequest());
            p.topApp = true;
            p.hall(OPEN);
            p.hall(CLOSED);
            yes("after a fold it is sent at once", p.took().endsWith(lines("hall closed", "request rear display (try 1)",
                    "status: Closed: switching to the outer screen")));
        });

        t.test("a request that gets no dialog is given up on after 10 s and made again", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.took();
            p.after(9_900);
            same("for 10 s", "", p.took());
            p.after(2_200);
            same("after that", "request rear display (try 2)", p.took());
            p.trampoline();
            same("the trampoline arrives", "rear display requested", p.took());
            p.dialogShows();
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("a cancel the system refuses is sent again", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.topApp = false;
            p.hall(OPEN);
            p.trampoline();
            same("refused", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen", "cancel refused"), p.took());
            ends(p, REAR, FOLDFIX, true);
            p.topApp = true;
            p.after(2_200);
            p.trampoline();
            same("2.2 s later", lines("cancel rear display (try 2)", "rear display request cancelled",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });
    }

    private static void otherApps(Suite t) {
        t.group("Scenarios, other apps' requests");

        t.test("made while the phone is open (the real selfie mode): left alone", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            same("on open", lines("hall open", "status: Open: inner screen on"), p.took());
            p.after(12_000);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the request", "status: Open: another app is using the outer screen", p.took());
            p.after(20_000);
            same("20 s later", "", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
            same("looks for a dialog", 0, p.dialogLooks);
        });

        t.test("made 12 s after FoldFix ended its own on open: logged, left alone", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            p.trampoline();
            p.took();
            p.after(11_922);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the request", lines("another app turned rear display on 11922 ms after our cancel: left alone",
                    "status: Open: another app is using the outer screen"), p.took());
            no("curtain", p.curtainStarted());
            p.after(26_000);
            same("26 s later", "", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("found active on the closed phone: taken over, then ended on open", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.hall(CLOSED);
            same("on close", lines("hall closed", "adopting the active rear display request",
                    "status: Closed: outer screen on"), p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, true);
            p.after(6_000);
            p.hall(OPEN);
            p.trampoline();
            same("on open", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen", "rear display request cancelled",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("ended once: what the other app requests after that open is left alone until the next close", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.hall(CLOSED);
            p.hall(OPEN);
            p.trampoline();
            p.after(5_000);
            p.took();
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("its next request", lines("another app turned rear display on 5000 ms after our cancel: left alone",
                    "status: Open: another app is using the outer screen"), p.took());
            p.after(20_000);
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
            p.hall(CLOSED);
            p.hall(OPEN);
            p.trampoline();
            ends(p, OPENED, NOBODY, false);
        });

        t.test("ended by the other app while the phone is closed: FoldFix requests its own", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.hall(CLOSED);
            p.after(5_000);
            p.took();
            p.otherAppEnds();
            same("it ends", lines("earlier or adopted request is gone", "request rear display (try 1)",
                    "status: Closed: switching to the outer screen"), p.took());
            p.trampoline();
            p.dialogShows();
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("the status screen's button ends another app's request; nothing follows its next one", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            p.trampoline();
            p.after(10_000);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.after(5_000);
            p.took();
            p.userSwitchesToInner();
            same("the tap", lines("rear display request cancelled from the status screen",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
            p.after(800);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the other app asks again", "status: Open: another app is using the outer screen", p.took());
            no("curtain", p.curtainStarted());
            p.after(20_000);
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("the button within 3 s of FoldFix's own cancel and of the curtain's: the next request isn't even looked at", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            p.trampoline();  // FoldFix's cancel
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // followed up
            p.curtainArrives();                      // the curtain's cancel
            p.after(900);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // left alone
            p.after(300);
            p.took();
            p.userSwitchesToInner();
            p.after(500);  // 2.3 s after FoldFix's cancel, 1.7 s after the curtain's
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the tap and the next request", lines("rear display request cancelled from the status screen",
                    "status: Open: inner screen on", "status: Open: another app is using the outer screen"), p.took());
            no("curtain", p.curtainStarted());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("the button 1 s after a cancel FoldFix sent but had to drop: no curtain for the next request", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);                            // FoldFix's cancel is sent
            p.after(300);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // and overtaken: another app's request replaces ours
            p.trampoline();
            same("on open", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen",
                    "status: Open: another app is using the outer screen",
                    "dropping stale cancel: no request of FoldFix's to end"), p.took());
            p.after(700);
            p.userSwitchesToInner();
            p.after(500);  // 1.5 s after FoldFix's cancel was sent: the one follow-up of this opening is unused
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the tap and the next request", lines("rear display request cancelled from the status screen",
                    "status: Open: inner screen on", "status: Open: another app is using the outer screen"), p.took());
            no("curtain", p.curtainStarted());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });
    }

    /** A phone that was opened with Pixel Camera on screen: FoldFix's cancel has just gone through. */
    private static SimPhone openedWithCameraOnScreen() {
        SimPhone p = SimPhone.closedOnOuter();
        p.hall(OPEN);
        p.trampoline();
        p.took();
        return p;
    }

    private static void camera(Suite t) {
        t.group("Scenarios, Pixel Camera");

        t.test("on screen when the phone is opened: its repeat request is ended once, behind the curtain", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(628);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("Camera asks again", lines("another app turned rear display on 628 ms after our cancel: ending that once",
                    "status: Open: another app is using the outer screen"), p.took());
            yes("curtain started", p.curtainStarted());
            p.after(85);
            p.curtainArrives();
            same("the curtain arrives", lines("rear display request cancelled", "curtain: up",
                    "status: Open: switching back to the inner screen", "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("asking yet again after the curtain: left alone", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(760);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.after(170);
            p.curtainArrives();
            p.took();
            p.after(1_876);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("Camera asks a third time", lines("another app turned rear display on 1876 ms after our cancel: left alone",
                    "status: Open: another app is using the outer screen"), p.took());
            no("curtain", p.curtainStarted());
            p.after(28_000);
            same("28 s later", "", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("asking again only after 3 s: left alone", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(3_000);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("Camera asks", lines("another app turned rear display on 3000 ms after our cancel: left alone",
                    "status: Open: another app is using the outer screen"), p.took());
            no("curtain", p.curtainStarted());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("asking again while the phone sleeps through the 3 s: left alone", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(200);
            p.sleep(4_000);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            yes("left alone", p.took().startsWith("another app turned rear display on 4200 ms after our cancel: left alone"));
            no("curtain", p.curtainStarted());
        });

        t.test("the next opening gets a follow-up of its own", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.curtainArrives();
            p.after(900);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // left alone: Camera is on the outer screen
            p.after(4_000);
            p.hall(CLOSED);                          // taken over
            p.after(4_000);
            p.hall(OPEN);
            p.trampoline();
            p.took();
            p.after(700);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            yes("followed up", p.took().startsWith("another app turned rear display on 700 ms after our cancel: ending that once"));
            p.curtainArrives();
            ends(p, OPENED, NOBODY, false);
        });

        t.test("the curtain arrives too late: stands down", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.sleep(3_157);  // 3757 ms after our cancel
            p.curtainArrives();
            same("the curtain arrives", "curtain: leaving the request alone, too late by 757 ms", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("the phone is closed before the curtain arrives: stands down, the request is ours by then", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.hall(CLOSED);
            p.took();
            p.curtainArrives();
            same("the curtain arrives", "curtain: leaving the request alone, the phone isn't open, or the service is off",
                    p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, true);
        });

        t.test("rear display went off before the curtain arrives: stands down", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.otherAppEnds();
            p.took();
            p.curtainArrives();
            same("the curtain arrives", "curtain: leaving the request alone, rear display is off already", p.took());
        });

        t.test("rear display went off and on again before the curtain arrives: stands down", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.otherAppEnds();
            p.after(300);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.curtainArrives();
            same("the curtain arrives", "curtain: leaving the request alone, it went off and on again", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("closed and opened again before the curtain arrives: the request is FoldFix's, the curtain stands down", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.hall(CLOSED);  // taken over
            p.hall(OPEN);    // and to be ended by the trampoline
            p.took();
            p.curtainArrives();
            same("the curtain arrives", "curtain: leaving the request alone, the request is FoldFix's", p.took());
            p.trampoline();
            ends(p, OPENED, NOBODY, false);
        });

        t.test("the curtain's cancel is refused: tried once, the request stays", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.topApp = false;
            p.curtainArrives();
            same("the curtain arrives", "cancel refused", p.took());
            p.after(30_000);
            same("30 s later", "", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("the phone is closed while the curtain is up: FoldFix requests as usual", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.curtainArrives();
            p.took();
            p.after(400);
            p.hall(CLOSED);
            p.trampoline();
            p.dialogShows();
            same("on close", lines("hall closed", "request rear display (try 1)",
                    "status: Closed: switching to the outer screen", "rear display requested", "clicked Confirm",
                    "status: Closed: outer screen on"), p.took());
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("its own selfie mode, then closed and opened: taken over, ended, and the repeat request ended too", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.after(30_000);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // the user picks Rear Camera Selfie
            p.after(10_000);
            p.hall(CLOSED);
            p.after(5_000);
            p.hall(OPEN);
            p.trampoline();
            p.took();
            p.after(800);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.curtainArrives();
            same("Camera asks again", lines("another app turned rear display on 800 ms after our cancel: ending that once",
                    "status: Open: another app is using the outer screen", "rear display request cancelled",
                    "curtain: up", "status: Open: switching back to the inner screen",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("selfie button on the closed phone: FoldFix's request ended, requested again", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.otherAppEnds();
            // current behaviour, pinned as found: FoldFix hears that its request ended a moment before
            // it sees rear-display mode go off, and for that moment takes its own request over. On
            // the phone the two arrive in this order in most taps, not in all.
            same("the tap", lines("adopting the active rear display request", "earlier or adopted request is gone",
                    "request rear display (try 1)", "status: Closed: switching to the outer screen"), p.took());
            p.trampoline();
            p.after(1_050);
            p.dialogShows();
            same("the request", lines("rear display requested", "clicked Confirm", "status: Closed: outer screen on"),
                    p.took());
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("selfie button, FoldFix's request refused, Camera's own follows: taken over", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.otherAppEnds();
            p.topApp = false;
            p.trampoline();
            p.took();
            p.after(900);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("Camera's request", lines("adopting the active rear display request", "status: Closed: outer screen on"),
                    p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, true);
            p.after(60_000);
            same("a minute later", "", p.took());
        });

        t.test("selfie button, Camera's request overtakes FoldFix's waiting one: the dialog is confirmed", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.otherAppEnds();
            p.trampoline();
            p.took();
            p.after(70);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("Camera's request becomes active", "status: Closed: outer screen on", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, true);
            p.after(270);
            p.dialogShows();  // on the outer screen, in front of the user
            same("the dialog shows", "clicked Confirm", p.took());
            ends(p, REAR, FOLDFIX, true);
            p.hall(OPEN);
            p.trampoline();
            ends(p, OPENED, NOBODY, false);
        });

        t.test("overtaken, and the phone is opened before the dialog shows", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.otherAppEnds();
            p.trampoline();
            p.after(70);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.after(100);
            p.hall(OPEN);
            // current behaviour, pinned as found: the waiting request makes the active one count as
            // ours, so a cancel is sent. The model has it end Camera's request, as the system ends
            // whatever is active; the docs say only FoldFix's own is dropped here.
            same("on open", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen"), p.took());
            p.trampoline();
            same("the trampoline arrives", "rear display request cancelled", p.took());
            same("the active request is of", NOBODY, p.holder);
            p.after(100);
            p.dialogShows();
            same("the dialog shows", "clicked Cancel", p.took());
            ends(p, OPENED, NOBODY, false);
        });
    }

    private static void lateLaunches(Suite t) {
        t.group("Scenarios, launches that arrive late");

        t.test("a cancel arriving after the phone was closed again: dropped", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            p.after(1_100);
            p.hall(CLOSED);
            same("opened and closed", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen", "hall closed",
                    "status: Closed: outer screen on"), p.took());
            p.after(2_900);
            p.trampoline();
            same("the cancel arrives", "dropping stale cancel: the phone is closed", p.took());
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("a cancel arriving after another app's request replaced ours: dropped", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            p.after(1_100);
            p.took();
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the other request", "status: Open: another app is using the outer screen", p.took());
            p.after(2_900);
            p.trampoline();
            same("the cancel arrives", "dropping stale cancel: no request of FoldFix's to end", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("a cancel arriving after the service was turned off: still goes through", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.hall(OPEN);
            p.serviceOff();
            p.took();
            p.trampoline();
            same("the cancel arrives", "rear display request cancelled", p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("adb's plain cancel on the closed phone: dropped", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.adbCancel(false);
            same("the cancel", "dropping stale cancel: the phone is closed", p.took());
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("adb's plain cancel of another app's request on the open phone: dropped", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.adbCancel(false);
            same("the cancel", "dropping stale cancel: no request of FoldFix's to end", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("adb's forced cancel of another app's request on the open phone: goes through", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.took();
            p.adbCancel(true);
            same("the cancel", lines("rear display request cancelled", "status: Open: switching back to the inner screen",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("adb's forced cancel on the closed phone: goes through, and FoldFix requests again", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.adbCancel(true);
            // current behaviour, pinned as found: as with the selfie button, the request just cancelled
            // is taken over for the moment until rear-display mode is seen off.
            same("the cancel", lines("rear display request cancelled", "adopting the active rear display request",
                    "earlier or adopted request is gone", "request rear display (try 1)",
                    "status: Closed: switching to the outer screen"), p.took());
            p.trampoline();
            p.dialogShows();
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("a request arriving after the phone was opened: dropped", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.after(300);
            p.hall(OPEN);
            p.took();
            p.after(700);
            p.trampoline();
            same("the request arrives", "phone is open, dropping stale request", p.took());
            ends(p, OPENED, NOBODY, false);
            p.after(5_000);
            same("status", "Open: inner screen on", p.status);
        });

        t.test("a second request arriving while the first is waiting for its dialog: not made again", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.hall(OPEN);    // the first trampoline is still under way
            p.hall(CLOSED);  // the fold reset the schedule: a second one is launched
            same("trampolines under way", 2, p.launchesUnderWay());
            p.trampoline();
            p.took();
            p.trampoline();
            same("the second arrives", "rear display already on or requested (ours: true), not requesting again", p.took());
            p.dialogShows();
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("a request arriving when another app's is active already: not made, the other is taken over", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.took();
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            same("the other request", "status: Closed: outer screen on", p.took());
            p.trampoline();
            same("ours arrives", lines("rear display already on or requested (ours: false), not requesting again",
                    "adopting the active rear display request"), p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, true);
        });
    }

    private static void blocked(Suite t) {
        t.group("Scenarios, when FoldFix can't act");

        t.test("outer screen not detected (booted open): never requests", () -> {
            SimPhone p = new SimPhone();
            p.outer = false;
            p.hall(CLOSED);
            same("on close", lines("hall closed", "status: Outer screen not detected: restart the phone while it is closed"),
                    p.took());
            p.after(600_000);
            same("ten minutes later", "", p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("rear-display mode unavailable: waits, and requests once it is back", () -> {
            SimPhone p = new SimPhone();
            p.available = false;
            p.hall(CLOSED);
            same("on close", lines("hall closed", "status: Rear-display mode is unavailable right now (battery saver or heat?)"),
                    p.took());
            p.after(12_000);
            same("12 s later", "", p.took());
            p.available = true;
            p.after(5_000);
            same("available again", lines("request rear display (try 1)", "status: Closed: switching to the outer screen"),
                    p.took());
            p.trampoline();
            p.dialogShows();
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("no backend: says so and stops", () -> {
            SimPhone p = new SimPhone();
            p.hasBackend = false;
            p.hall(CLOSED);
            same("on close", lines("hall closed", "status: No rear-display access: run the one-time adb command"), p.took());
            yes("nothing scheduled", p.nothingScheduled());
            p.hall(OPEN);
            same("on open", "hall open", p.took());
            yes("nothing scheduled", p.nothingScheduled());
        });

        t.test("device state not known yet: nothing until it is", () -> {
            SimPhone p = new SimPhone();
            p.known = false;
            p.hall(CLOSED);
            same("on close", "hall closed", p.took());
            yes("nothing scheduled", p.nothingScheduled());
            p.stateBecomesKnown();
            same("once known", lines("request rear display (try 1)", "status: Closed: switching to the outer screen"),
                    p.took());
        });

        t.test("no hall reading yet: nothing, whatever the device state does", () -> {
            SimPhone p = new SimPhone();
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.otherAppEnds();
            same("logged", "", p.took());
            same("status", null, p.status);
        });
    }

    private static void restarts(Suite t) {
        t.group("Scenarios, FoldFix restarted");

        t.test("restarted while closed on its own request: still ends it on open", () -> {
            SimPhone old = SimPhone.closedOnOuter();
            SimPhone p = old.restarted();
            p.hall(CLOSED);
            same("on start", lines("hall closed", "status: Closed: outer screen on"), p.took());
            p.hall(OPEN);
            p.trampoline();
            same("on open", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen", "rear display request cancelled",
                    "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("restarted while closed on a request it took over: doesn't take it over again, ends it on open", () -> {
            SimPhone old = new SimPhone();
            old.hall(OPEN);
            old.otherAppRequests(REAR_OUTER_DEFAULT);
            old.hall(CLOSED);
            SimPhone p = old.restarted();
            p.hall(CLOSED);
            same("on start", lines("hall closed", "status: Closed: outer screen on"), p.took());
            p.hall(OPEN);
            p.trampoline();
            ends(p, OPENED, NOBODY, false);
        });

        t.test("restarted after the request it had to end was ended: the saved flag is cleared", () -> {
            SimPhone old = SimPhone.closedOnOuter();
            old.state = OPENED;  // ended while FoldFix wasn't running (a reboot does that)
            old.holder = NOBODY;
            SimPhone p = old.restarted();
            same("on start", "earlier or adopted request is gone", p.took());
            no("saved", p.saved);
            p.hall(OPEN);
            same("on open", lines("hall open", "status: Open: inner screen on"), p.took());
        });

        t.test("restarted while open with another app's request: left alone", () -> {
            SimPhone old = new SimPhone();
            old.hall(OPEN);
            old.otherAppRequests(REAR_OUTER_DEFAULT);
            SimPhone p = old.restarted();
            p.hall(OPEN);
            same("on start", lines("hall open", "status: Open: another app is using the outer screen"), p.took());
            p.after(20_000);
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });
    }
}
