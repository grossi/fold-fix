package app.foldfix;

import static app.foldfix.SimPhone.CLOSED;
import static app.foldfix.SimPhone.FOLDFIX;
import static app.foldfix.SimPhone.NOBODY;
import static app.foldfix.SimPhone.OPEN;
import static app.foldfix.SimPhone.OPENED;
import static app.foldfix.SimPhone.OTHER;
import static app.foldfix.SimPhone.REAR;
import static app.foldfix.SimPhone.REAR_OUTER_DEFAULT;
import static app.foldfix.SimPhone.STATE_CLOSED;
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
        stateClosed(t);
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

        t.test("taking the place of FoldFix's own on the closed phone: taken over at once", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            // Our request's end is followed by a report that rear-display mode is (still) on, as on
            // the phone: so this isn't the moment before it goes off.
            same("the other request", "adopting the active rear display request", p.took());
            ends(p, REAR_OUTER_DEFAULT, OTHER, true);
            p.hall(OPEN);
            p.trampoline();
            ends(p, OPENED, NOBODY, false);
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
            // FoldFix hears that its request ended a moment before rear-display mode is reported off
            // (on the phone in most taps, not in all). What is still on for that moment isn't
            // another app's request, and isn't taken over.
            same("the tap", lines("request rear display (try 1)", "status: Closed: switching to the outer screen"),
                    p.took());
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

        t.test("overtaken, and the phone is opened before the dialog shows: both requests go", () -> {
            SimPhone p = overtaken();
            p.after(100);
            p.hall(OPEN);
            // Camera's request was active while the phone was closed, so it is FoldFix's to end. The
            // cancel ends it, as the system ends whatever is active (seen on the phone, too).
            same("on open", lines("hall open", TAKEN_OVER_ON_OPEN, "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen"), p.took());
            p.trampoline();
            same("the trampoline arrives", "rear display request cancelled", p.took());
            same("the active request is of", NOBODY, p.holder);
            p.after(100);
            p.dialogShows();  // of our own request, which no cancel reaches
            same("the dialog shows", "clicked Cancel", p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("overtaken, and the phone is opened with the dialog up: Cancel in it, then the other request is ended", () -> {
            SimPhone p = overtaken();
            p.after(100);
            p.dialogUpUnnoticed();
            p.hall(OPEN);
            // Dropping our own request with its dialog doesn't make Camera's request Camera's again.
            same("on open", lines("hall open", TAKEN_OVER_ON_OPEN, "clicked Cancel",
                    "status: Open: switching back to the inner screen", "cancel rear display (try 1)"), p.took());
            same("the active request is of", OTHER, p.holder);
            yes("and FoldFix's to end", p.own.hasOwnRequest());
            p.trampoline();
            same("the trampoline arrives", "rear display request cancelled", p.took());
            ends(p, OPENED, NOBODY, false);
            p.after(3_000);
            same("once 3 s have passed since the request", "status: Open: inner screen on", p.took());
        });

        t.test("overtaken, opened, and the dialog shows before the cancel arrives: still both go", () -> {
            SimPhone p = overtaken();
            p.hall(OPEN);
            p.dialogShows();
            same("on open, and the dialog", lines("hall open", TAKEN_OVER_ON_OPEN, "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen", "clicked Cancel"), p.took());
            p.trampoline();
            same("the trampoline arrives", "rear display request cancelled", p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("overtaken, opened, and the other request ends by itself: only our dialog is left to cancel", () -> {
            SimPhone p = overtaken();
            p.hall(OPEN);
            p.took();
            p.otherAppEnds();  // System UI's control, say, before our cancel arrives
            same("it ends", "earlier or adopted request is gone", p.took());
            yes("our waiting request is still ours", p.own.hasOwnRequest());
            p.trampoline();
            same("the trampoline arrives", "nothing active to cancel, request still waiting for its dialog", p.took());
            p.dialogShows();
            same("the dialog shows", "clicked Cancel", p.took());
            ends(p, OPENED, NOBODY, false);
            // What another app asks for from here on is its own again.
            p.after(5_000);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.after(20_000);
            ends(p, REAR_OUTER_DEFAULT, OTHER, false);
        });

        t.test("opened, closed and opened again within a second, Camera on screen: back on the inner screen", () -> {
            SimPhone p = openedWithCameraOnScreen();
            p.after(350);
            p.hall(CLOSED);
            p.trampoline();                          // our new request waits for its dialog
            p.after(100);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // Camera's repeat request gets in first
            p.after(60);
            p.dialogUpUnnoticed();
            p.took();
            p.hall(OPEN);
            p.trampoline();
            same("the second opening", lines("hall open", TAKEN_OVER_ON_OPEN, "clicked Cancel",
                    "status: Open: switching back to the inner screen", "cancel rear display (try 1)",
                    "rear display request cancelled"), p.took());
            p.after(600);
            p.otherAppRequests(REAR_OUTER_DEFAULT);  // and Camera asks once more
            yes("followed up", p.took().startsWith("another app turned rear display on 600 ms after our cancel: ending that once"));
            p.curtainArrives();
            ends(p, OPENED, NOBODY, false);
        });

        t.test("opened a moment after our dialog was confirmed, before the request is reported active: ended", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.took();
            p.activationLate = true;
            p.dialogShows();  // confirmed, and rear display is on
            same("the dialog shows", lines("clicked Confirm", "status: Closed: outer screen on"), p.took());
            p.hall(OPEN);
            // For all FoldFix knows at this moment, the active request could be another app's.
            same("on open", lines("hall open", TAKEN_OVER_ON_OPEN, "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen"), p.took());
            p.activationArrives();
            p.trampoline();
            same("the trampoline arrives", "rear display request cancelled", p.took());
            ends(p, OPENED, NOBODY, false);
        });

        t.test("our request reported active a moment after rear display came on, phone still closed: nothing more", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.activationLate = true;
            p.dialogShows();
            p.took();
            p.activationArrives();
            p.after(60_000);
            same("a minute", "", p.took());
            ends(p, REAR, FOLDFIX, true);
            p.hall(OPEN);
            p.trampoline();
            same("on open", lines("hall open", "cancel rear display (try 1)",
                    "status: Open: switching back to the inner screen", "rear display request cancelled",
                    "status: Open: inner screen on"), p.took());
        });
    }

    static final String TAKEN_OVER_ON_OPEN =
            "opened with rear display on before a request of ours was reported active: taking the active one over";

    /**
     * A closed phone on which Camera's selfie button ended FoldFix's request, FoldFix asked again,
     * and Camera's own request became active while FoldFix's is still waiting for its dialog.
     */
    private static SimPhone overtaken() {
        SimPhone p = SimPhone.closedOnOuter();
        p.otherAppEnds();
        p.trampoline();
        p.after(70);
        p.otherAppRequests(REAR_OUTER_DEFAULT);
        p.took();
        return p;
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

        t.test("a cancel arriving after another request replaced one FoldFix took over: ends it all the same", () -> {
            SimPhone p = SimPhone.closedOnOuter().restarted();  // the request is an earlier process's
            p.hall(CLOSED);
            p.hall(OPEN);
            p.after(1_100);
            p.took();
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            // A known limit: a request FoldFix took over has no callback of its own, and rear-display
            // mode never went off, so nothing tells FoldFix that the active request is a new one.
            // It takes a request made on top of an active one, in the moment between the unfolding
            // and FoldFix's cancel. Pixel Camera makes none while the mode is on.
            same("the other request", "", p.took());
            yes("still taken for FoldFix's", p.own.hasOwnRequest());
            p.after(900);  // the service's own retry, 200 ms later, would end it as well
            p.trampoline();
            same("the cancel arrives", lines("rear display request cancelled", "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
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
            // As with the selfie button: the request just cancelled isn't taken over for the moment
            // until rear-display mode is reported off.
            same("the cancel", lines("rear display request cancelled", "request rear display (try 1)",
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

    /**
     * Android reaching CLOSED by itself: a hinge sensor that works. On the test phone the state
     * was forced from a computer ({@code cmd device_state base-state 0}, then {@code reset}).
     */
    private static void stateClosed(Suite t) {
        t.group("Scenarios, Android's own CLOSED state");
        String status = "Closed: Android has switched to the outer screen by itself";

        t.test("reached while FoldFix's request is active: the system ends it, FoldFix asks for nothing", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            int looks = p.dialogLooks;
            p.androidCloses();
            same("on CLOSED", "status: " + status, p.took());
            ends(p, STATE_CLOSED, NOBODY, false);
            same("landscape fix", false, p.landscapeFix);
            p.after(600_000);
            same("ten quiet minutes", "", p.took());
            yes("nothing scheduled", p.nothingScheduled());
            same("looks for the dialog since", looks, p.dialogLooks);
        });

        t.test("left again with the phone still shut (the base state was reset): FoldFix requests as usual", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.androidCloses();
            p.after(8_000);
            p.took();
            p.androidOpens();
            same("on OPENED", lines("request rear display (try 1)", "status: Closed: switching to the outer screen"),
                    p.took());
            p.trampoline();
            p.dialogShows();
            same("the request", lines("rear display requested", "clicked Confirm", "status: Closed: outer screen on"),
                    p.took());
            ends(p, REAR, FOLDFIX, true);
        });

        t.test("a phone whose hinge sensor works, closed and opened: no request, no cancel", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.took();
            p.androidCloses();  // the hinge sensor first
            p.hall(CLOSED);
            same("closing", lines("status: " + status, "hall closed"), p.took());
            p.after(60_000);
            p.hall(OPEN);
            p.androidOpens();
            same("opening", lines("hall open", "status: Open: inner screen on"), p.took());
            ends(p, OPENED, NOBODY, false);
            same("looks for the dialog", 0, p.dialogLooks);
        });

        t.test("reached before the request arrives: the trampoline drops it", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);  // the hall sensor first: the request is launched
            p.took();
            p.androidCloses();
            same("on CLOSED", "status: " + status, p.took());
            p.trampoline();
            same("the request arrives", "Android's device state is closed, dropping stale request", p.took());
            ends(p, STATE_CLOSED, NOBODY, false);
            p.after(600_000);
            same("ten quiet minutes", "", p.took());
            yes("nothing scheduled", p.nothingScheduled());
        });

        t.test("reached while the request waits for its dialog, System UI drops it: nothing left behind", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.took();
            p.after(40);
            p.androidCloses();
            same("on CLOSED", "status: " + status, p.took());
            yes("still ours, until it is dropped", p.own.hasOwnRequest());
            p.after(120);
            p.systemDropsWaitingRequest();
            ends(p, STATE_CLOSED, NOBODY, false);
            p.after(600_000);
            same("nothing more", "", p.took());
            yes("nothing scheduled", p.nothingScheduled());
        });

        t.test("reached while the request waits, and its dialog shows there (\"Unfold phone\"): Cancel is tapped", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.took();
            p.after(40);
            p.androidCloses();
            p.after(300);
            p.dialogShows();
            same("the dialog shows", lines("status: " + status, "clicked Cancel"), p.took());
            no("dialog left on screen", p.dialog);
            ends(p, STATE_CLOSED, NOBODY, false);
            p.after(600_000);
            same("nothing more", "", p.took());
            yes("nothing scheduled", p.nothingScheduled());
        });

        t.test("that dialog showing only after 3 s: still cancelled, the request is ours until then", () -> {
            SimPhone p = new SimPhone();
            p.hall(CLOSED);
            p.trampoline();
            p.androidCloses();
            p.after(15_000);
            p.took();
            yes("nothing scheduled while waiting", p.nothingScheduled());
            p.dialogShows();
            same("the dialog shows", "clicked Cancel", p.took());
            ends(p, STATE_CLOSED, NOBODY, false);
        });

        t.test("reached with another app's request taken over: the system ends that too", () -> {
            SimPhone p = new SimPhone();
            p.hall(OPEN);
            p.otherAppRequests(REAR_OUTER_DEFAULT);
            p.hall(CLOSED);
            p.took();
            p.androidCloses();
            same("on CLOSED", lines("earlier or adopted request is gone", "status: " + status), p.took());
            ends(p, STATE_CLOSED, NOBODY, false);
        });

        t.test("the hall sensor reading open in CLOSED: still nothing, and the open phone's rules once it is left", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.androidCloses();
            p.took();
            p.hall(OPEN);
            same("hall open", "hall open", p.took());
            same("status", status, p.status);
            p.androidOpens();
            same("on OPENED", "status: Open: inner screen on", p.took());
            ends(p, OPENED, NOBODY, false);
            same("trampolines launched", 0, p.launchesUnderWay());
        });

        t.test("adb's request in CLOSED: dropped", () -> {
            SimPhone p = SimPhone.closedOnOuter();
            p.androidCloses();
            p.took();
            p.adbRequest();
            same("the request arrives", "Android's device state is closed, dropping stale request", p.took());
            ends(p, STATE_CLOSED, NOBODY, false);
        });

        t.test("FoldFix restarted in CLOSED: the saved flag is cleared, nothing is asked for", () -> {
            SimPhone old = SimPhone.closedOnOuter();
            old.state = STATE_CLOSED;  // reached while FoldFix wasn't running: the system ended the request
            old.holder = NOBODY;
            SimPhone p = old.restarted();
            p.hall(CLOSED);
            same("after the restart", lines("earlier or adopted request is gone", "hall closed", "status: " + status),
                    p.took());
            ends(p, STATE_CLOSED, NOBODY, false);
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

        t.test("restarted while another app's request is active and its own was waiting: ends it on open", () -> {
            SimPhone old = SimPhone.closedOnOuter();
            old.otherAppEnds();
            old.trampoline();
            old.otherAppRequests(REAR_OUTER_DEFAULT);  // overtaken; a waiting request dies with its process
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
