package app.foldfix;

import static app.foldfix.Suite.no;
import static app.foldfix.Suite.same;
import static app.foldfix.Suite.yes;

/** Whose request it is, and which cancel ended rear-display mode ({@link Ownership}). */
final class OwnershipTest {
    private OwnershipTest() {}

    private static final boolean ON = true, OFF = false, BY_USER = true;

    /** An Ownership that counts how often it reported a change, as RearDisplay's save() would see. */
    private static final class Book {
        int changes;
        final Ownership own;

        Book(boolean inherited) {
            own = new Ownership(inherited, () -> changes++);
        }

        /** A request made and accepted at {@code uptime}; returns its generation. */
        int requested(long uptime) {
            own.markRequestPending();
            int gen = own.begin();
            own.sessionStarted(uptime);
            return gen;
        }
    }

    static void tests(Suite t) {
        requests(t);
        takingOver(t);
        cancels(t);
    }

    private static void requests(Suite t) {
        t.group("Ownership, our own request");

        t.test("nothing is ours to begin with", () -> {
            Book b = new Book(false);
            no("ours", b.own.hasOwnRequest());
            no("session", b.own.hasSession());
            no("live", b.own.isSessionLive(1_000));
            same("changes reported", 0, b.changes);
        });

        t.test("marked as pending: ours from the moment the trampoline is launched", () -> {
            Book b = new Book(false);
            b.own.markRequestPending();
            yes("ours", b.own.hasOwnRequest());
            no("session", b.own.hasSession());
            same("changes reported", 1, b.changes);
        });

        t.test("the mark is dropped if the request isn't made after all", () -> {
            Book b = new Book(false);
            b.own.markRequestPending();
            b.own.dropPendingMark();
            no("ours", b.own.hasOwnRequest());
            same("changes reported", 2, b.changes);
        });

        t.test("dropping the mark leaves an accepted request ours", () -> {
            Book b = new Book(false);
            b.requested(1_000);
            b.own.markRequestPending();  // FoldService launches again
            int before = b.changes;
            b.own.dropPendingMark();     // "already on or requested, not requesting again"
            yes("ours", b.own.hasOwnRequest());
            yes("session", b.own.hasSession());
            same("changes reported", before, b.changes);
        });

        t.test("dropping the mark leaves an inherited request ours", () -> {
            Book b = new Book(true);
            b.own.markRequestPending();
            b.own.dropPendingMark();
            yes("ours", b.own.hasOwnRequest());
        });

        t.test("accepted: waiting for its dialog, for 10 s", () -> {
            Book b = new Book(false);
            b.requested(1_000);
            yes("waiting at once", b.own.isSessionWaiting(1_000));
            yes("waiting after 9999 ms", b.own.isSessionWaiting(10_999));
            yes("live after 9999 ms", b.own.isSessionLive(10_999));
            no("waiting after 10 s", b.own.isSessionWaiting(11_000));
            no("live after 10 s", b.own.isSessionLive(11_000));
            // Given up on, to be redone, but until then still ours and still a session.
            yes("ours after 10 s", b.own.hasOwnRequest());
            yes("session after 10 s", b.own.hasSession());
        });

        t.test("activated: live for good, no longer waiting", () -> {
            Book b = new Book(false);
            int gen = b.requested(1_000);
            b.own.requestActivated(gen);
            no("waiting", b.own.isSessionWaiting(1_100));
            yes("live", b.own.isSessionLive(1_100));
            yes("live a day later", b.own.isSessionLive(86_400_000));
        });

        t.test("activated late, after it was given up on: live again", () -> {
            Book b = new Book(false);
            int gen = b.requested(1_000);
            no("live after 12 s", b.own.isSessionLive(13_000));
            b.own.requestActivated(gen);
            yes("live once activated", b.own.isSessionLive(13_000));
        });

        t.test("ended: nothing is ours any more", () -> {
            Book b = new Book(false);
            int gen = b.requested(1_000);
            b.own.requestActivated(gen);
            b.own.requestEnded(gen);
            no("ours", b.own.hasOwnRequest());
            no("session", b.own.hasSession());
            no("live", b.own.isSessionLive(1_100));
        });

        t.test("the end of a request also ends what was inherited", () -> {
            Book b = new Book(true);
            int gen = b.requested(1_000);
            b.own.requestEnded(gen);
            no("ours", b.own.hasOwnRequest());
            no("inherited", b.own.isInherited());
        });

        t.test("a replaced request's callbacks can't touch the new one", () -> {
            Book b = new Book(false);
            int first = b.requested(1_000);
            int second = b.requested(12_000);  // the first was lost and redone
            yes("a new generation", second != first);
            b.own.requestEnded(first);
            yes("ours after the old one's end", b.own.hasOwnRequest());
            yes("waiting after the old one's end", b.own.isSessionWaiting(12_100));
            b.own.requestActivated(first);
            yes("still waiting after the old one's activation", b.own.isSessionWaiting(12_100));
            b.own.requestActivated(second);
            no("waiting after its own activation", b.own.isSessionWaiting(12_100));
        });

        t.test("a refused request: begun, then cleared", () -> {
            Book b = new Book(false);
            b.own.markRequestPending();
            int gen = b.own.begin();
            b.own.clear();  // SecurityException: not the top app
            no("ours", b.own.hasOwnRequest());
            b.own.requestActivated(gen);
            no("live after a stray activation", b.own.isSessionLive(1_000));
        });

        t.test("every change of what is ours is reported, to be saved", () -> {
            Book b = new Book(false);
            b.own.markRequestPending();
            same("after the mark", 1, b.changes);
            int gen = b.own.begin();
            same("after begin", 2, b.changes);
            b.own.sessionStarted(1_000);
            b.own.requestActivated(gen);
            same("accepted and activated change nothing that is saved", 2, b.changes);
            b.own.requestEnded(gen);
            same("after the end", 3, b.changes);
            b.own.requestEnded(gen - 1);
            same("after a stale end", 3, b.changes);
        });
    }

    private static void takingOver(Suite t) {
        t.group("Ownership, requests this process didn't make");

        t.test("an earlier process's request is ours from the start", () -> {
            Book b = new Book(true);
            yes("ours", b.own.hasOwnRequest());
            yes("inherited", b.own.isInherited());
            no("session", b.own.hasSession());
        });

        t.test("an active request that isn't ours can be taken over", () -> {
            Book b = new Book(false);
            yes("can adopt", b.own.canAdopt(ON));
            b.own.adopt();
            yes("ours", b.own.hasOwnRequest());
            yes("inherited", b.own.isInherited());
            same("changes reported", 1, b.changes);
            no("can adopt again", b.own.canAdopt(ON));
        });

        t.test("nothing to take over while rear display is off, or while a request is ours", () -> {
            no("rear display off", new Book(false).own.canAdopt(OFF));
            no("inherited", new Book(true).own.canAdopt(ON));
            Book b = new Book(false);
            b.own.markRequestPending();
            no("marked as pending", b.own.canAdopt(ON));
            b.requested(1_000);
            no("our request waiting", b.own.canAdopt(ON));
        });

        t.test("rear display off: the request we had to end is gone", () -> {
            Book b = new Book(true);
            no("gone while on", b.own.isInheritedGone(ON));
            yes("gone once off", b.own.isInheritedGone(OFF));
            b.own.clear();
            no("ours", b.own.hasOwnRequest());
            no("gone twice", b.own.isInheritedGone(OFF));
        });

        t.test("not gone while a request of this process is under way", () -> {
            Book b = new Book(true);
            b.own.markRequestPending();
            no("gone", b.own.isInheritedGone(OFF));
        });

        t.test("nothing inherited: nothing to be gone", () ->
                no("gone", new Book(false).own.isInheritedGone(OFF)));

        t.test("a cancel ends what was taken over", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.adopt();
            b.own.cancelled(2_000, !BY_USER);
            no("ours", b.own.hasOwnRequest());
            no("inherited", b.own.isInherited());
        });
    }

    private static void cancels(Suite t) {
        t.group("Ownership, cancels and what they ended");

        t.test("activations count each time rear display turns on", () -> {
            Book b = new Book(false);
            same("at first", 0, b.own.activations());
            b.own.onRearDisplay(OFF, 1_000);
            same("seen off", 0, b.own.activations());
            b.own.onRearDisplay(ON, 2_000);
            b.own.onRearDisplay(ON, 2_100);  // another status callback, or one request replacing another
            same("on", 1, b.own.activations());
            b.own.onRearDisplay(OFF, 3_000);
            same("off again", 1, b.own.activations());
            b.own.onRearDisplay(ON, 4_000);
            same("on again", 2, b.own.activations());
        });

        t.test("a cancel made here that turns rear display off within 2 s is what ended it", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, !BY_USER);
            same("before it is seen off", 0L, b.own.endingCancelAt());
            b.own.onRearDisplay(OFF, 6_999);
            same("seen off 1999 ms later: the time of the cancel, not of the callback", 5_000L, b.own.endingCancelAt());
        });

        t.test("off 2 s or more after the cancel: something else ended it", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, !BY_USER);
            b.own.onRearDisplay(OFF, 7_000);
            same("ending cancel", 0L, b.own.endingCancelAt());
        });

        t.test("off without a cancel made here: something else ended it", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.onRearDisplay(OFF, 2_000);
            same("ending cancel", 0L, b.own.endingCancelAt());
        });

        t.test("the user's cancel from the status screen isn't reported as ours", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, BY_USER);
            no("pending", b.own.isCancelPending(5_100));
            b.own.onRearDisplay(OFF, 5_100);
            same("ending cancel", 0L, b.own.endingCancelAt());
        });

        t.test("the user's cancel still ends what was ours", () -> {
            Book b = new Book(false);
            int gen = b.requested(1_000);
            b.own.requestActivated(gen);
            b.own.cancelled(5_000, BY_USER);
            no("ours", b.own.hasOwnRequest());
            no("session", b.own.hasSession());
        });

        t.test("a later switch-off by something else replaces the answer", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, !BY_USER);
            b.own.onRearDisplay(OFF, 5_100);
            same("ours", 5_000L, b.own.endingCancelAt());
            b.own.onRearDisplay(ON, 6_000);
            same("still the answer while on again", 5_000L, b.own.endingCancelAt());
            b.own.onRearDisplay(OFF, 6_500);  // within 2 s of our cancel, but that one is used up
            same("someone else's", 0L, b.own.endingCancelAt());
        });

        t.test("a cancel is pending for 2 s, or until rear display is seen off", () -> {
            Book b = new Book(false);
            no("before any cancel", b.own.isCancelPending(1_000));
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, !BY_USER);
            yes("at once", b.own.isCancelPending(5_000));
            yes("after 1999 ms", b.own.isCancelPending(6_999));
            no("after 2 s", b.own.isCancelPending(7_000));
            b.own.cancelled(8_000, !BY_USER);
            b.own.onRearDisplay(OFF, 8_050);
            no("once seen off", b.own.isCancelPending(8_100));
        });

        t.test("a cancel that had no effect is forgotten after 2 s", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, !BY_USER);
            b.own.onRearDisplay(OFF, 20_000);  // ended much later, by something else
            same("ending cancel", 0L, b.own.endingCancelAt());
            b.own.onRearDisplay(ON, 21_000);
            no("pending", b.own.isCancelPending(21_000));
        });

        t.test("the user's cancel right after one of ours: the switch-off counts as ours", () -> {
            Book b = new Book(false);
            b.own.onRearDisplay(ON, 1_000);
            b.own.cancelled(5_000, !BY_USER);
            b.own.cancelled(5_500, BY_USER);
            b.own.onRearDisplay(OFF, 5_600);
            // current behaviour, pinned as found: the status screen hides its button while a cancel of
            // ours is pending, so the two can only meet through a button that is a moment out of date.
            same("ending cancel", 5_000L, b.own.endingCancelAt());
        });

        t.test("the cancelled request's own end can't clear a request made since", () -> {
            Book b = new Book(false);
            int first = b.requested(1_000);
            b.own.requestActivated(first);
            b.own.cancelled(5_000, !BY_USER);
            int second = b.requested(5_200);  // closed again at once
            b.own.requestEnded(first);        // the system reports the cancelled one late
            yes("ours", b.own.hasOwnRequest());
            yes("waiting", b.own.isSessionWaiting(5_300));
            b.own.requestEnded(second);
            no("ours once the new one ends", b.own.hasOwnRequest());
        });
    }
}
