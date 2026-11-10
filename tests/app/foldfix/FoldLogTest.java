package app.foldfix;

import static app.foldfix.Suite.lines;
import static app.foldfix.Suite.same;
import static app.foldfix.Suite.yes;

import java.time.ZonedDateTime;
import java.util.TimeZone;

/**
 * The history ({@link FoldLog}): which lines are kept, in which order, and how their times read.
 * There is one history per process, so each case reads back only the lines it added.
 */
final class FoldLogTest {
    private FoldLogTest() {}

    static void tests(Suite t) {
        t.group("FoldLog, the history");

        t.test("the newest lines, oldest first, each with its level", () -> {
            FoldLog.i("hall open");
            FoldLog.w("dialog up but no button yet");
            FoldLog.i("hall closed");
            same("last 3", lines("I hall open", "W dialog up but no button yet", "I hall closed"), messages(3));
            same("last 2", lines("W dialog up but no button yet", "I hall closed"), messages(2));
            same("none", "", FoldLog.text(0));
            same("fewer than none", "", FoldLog.text(-1));
        });

        t.test("every line also goes to Android's log, under the tag FoldFix", () -> {
            FoldLog.i("hall open");
            same("i", "I/FoldFix: hall open", android.util.Log.last);
            FoldLog.w("no button yet");
            same("w", "W/FoldFix: no button yet", android.util.Log.last);
            FoldLog.w("can't add the overlay", new IllegalStateException("gone"));
            same("w with an exception", "W/FoldFix: can't add the overlay [IllegalStateException]", android.util.Log.last);
            FoldLog.e("could not watch device state", new SecurityException("denied"));
            same("e", "E/FoldFix: could not watch device state [SecurityException]", android.util.Log.last);
        });

        t.test("of an exception the history keeps the name and the message, not the stack trace", () -> {
            FoldLog.w("can't add the overlay", new IllegalStateException("not attached"));
            FoldLog.e("could not watch device state", new SecurityException());
            same("lines", lines("W can't add the overlay: java.lang.IllegalStateException: not attached",
                    "E could not watch device state: java.lang.SecurityException"), messages(2));
        });

        t.test("a line longer than 300 characters is cut; Android's log gets all of it", () -> {
            String exact = "x".repeat(300), longer = "y".repeat(301);
            FoldLog.i(exact);
            FoldLog.i(longer);
            same("lines", lines("I " + exact, "I " + "y".repeat(300) + "…"), messages(2));
            same("Android's log", "I/FoldFix: " + longer, android.util.Log.last);
        });

        t.test("500 lines are kept; older ones drop out one by one", () -> {
            for (int n = 1; n <= FoldLog.CAPACITY + 37; n++) FoldLog.i("line " + n);
            String[] kept = messages(FoldLog.CAPACITY + 100).split("\n");
            same("lines kept, asking for more", FoldLog.CAPACITY, kept.length);
            same("oldest kept", "I line 38", kept[0]);
            same("newest", "I line 537", kept[FoldLog.CAPACITY - 1]);
            for (int k = 0; k < kept.length; k++) same("line " + k + " of those kept", "I line " + (38 + k), kept[k]);
            FoldLog.i("one more");
            kept = messages(FoldLog.CAPACITY).split("\n");
            same("oldest kept after one more", "I line 39", kept[0]);
            same("newest after one more", "I one more", kept[FoldLog.CAPACITY - 1]);
        });

        t.test("the count of lines added goes on past 500", () -> {
            long before = FoldLog.total();
            for (int n = 0; n < FoldLog.CAPACITY + 1; n++) FoldLog.i("counted");
            same("added", (long) FoldLog.CAPACITY + 1, FoldLog.total() - before);
        });

        t.group("FoldLog, times");

        t.test("local time to the millisecond, month and day first", () -> {
            same("line", "11-11 02:38:44.184 I hall open",
                    at("Europe/Paris", "2026-11-11T02:38:44.184+01:00[Europe/Paris]", "hall open"));
        });

        t.test("every part has its leading zeros", () -> {
            same("line", "01-02 03:04:05.006 I x", at("UTC", "2026-01-02T03:04:05.006Z", "x"));
            same("midnight", "03-09 00:00:00.000 I x", at("UTC", "2026-03-09T00:00:00Z", "x"));
            same("last millisecond of a day", "12-31 23:59:59.999 I x", at("UTC", "2026-12-31T23:59:59.999Z", "x"));
        });

        t.test("the date is the local one: east of UTC it is the next day sooner, west of it later", () -> {
            same("Kolkata, +5:30", "11-11 04:10:00.000 I x", at("Asia/Kolkata", "2026-11-10T22:40:00Z", "x"));
            same("Kiritimati, +14", "01-01 13:59:59.999 I x", at("Pacific/Kiritimati", "2026-12-31T23:59:59.999Z", "x"));
            same("St. John's, -3:30", "02-28 20:45:00.000 I x", at("America/St_Johns", "2027-03-01T00:15:00Z", "x"));
            same("29 February", "02-29 23:30:00.000 I x", at("America/Sao_Paulo", "2028-03-01T02:30:00Z", "x"));
        });

        t.test("summer time: the offset is that of each line's own moment", () -> {
            TimeZone was = TimeZone.getDefault();
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
                // The night the clocks go back: 02:30 comes twice.
                FoldLog.add(ms("2026-10-25T00:30:00Z"), 'I', "first");
                FoldLog.add(ms("2026-10-25T01:30:00Z"), 'I', "second");
                FoldLog.add(ms("2026-10-25T02:30:00Z"), 'I', "third");
                same("lines", lines("10-25 02:30:00.000 I first", "10-25 02:30:00.000 I second",
                        "10-25 03:30:00.000 I third", ""), FoldLog.text(3));
            } finally {
                TimeZone.setDefault(was);
            }
        });

        t.test("lines of different days in one reading each get their own date", () -> {
            TimeZone was = TimeZone.getDefault();
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
                FoldLog.add(ms("2026-11-10T23:59:59.999Z"), 'I', "before midnight");
                FoldLog.add(ms("2026-11-11T00:00:00Z"), 'W', "after midnight");
                FoldLog.add(ms("2026-11-11T08:00:00Z"), 'I', "same day");
                FoldLog.add(ms("2026-11-10T08:00:00Z"), 'I', "clock set back");
                same("lines", lines("11-10 23:59:59.999 I before midnight", "11-11 00:00:00.000 W after midnight",
                        "11-11 08:00:00.000 I same day", "11-10 08:00:00.000 I clock set back", ""), FoldLog.text(4));
            } finally {
                TimeZone.setDefault(was);
            }
        });

        t.test("a clock before 1970 (not set yet) still gives a date", () -> {
            same("line", "12-31 23:59:59.999 I x", at("UTC", "1969-12-31T23:59:59.999Z", "x"));
            same("west of UTC at 0", "12-31 19:00:00.000 I x", at("America/New_York", "1970-01-01T00:00:00Z", "x"));
        });

        t.test("a line logged now carries the present time", () -> {
            long before = System.currentTimeMillis();
            FoldLog.i("now");
            long after = System.currentTimeMillis();
            String line = FoldLog.text(1);
            boolean found = false;
            for (long time = before; time <= after && !found; time++) {
                found = line.equals(format(time, TimeZone.getDefault()) + " I now\n");
            }
            yes("the time is between just before and just after: " + line.trim(), found);
        });
    }

    /** The last {@code n} lines without their times. */
    private static String messages(int n) {
        StringBuilder out = new StringBuilder();
        for (String line : FoldLog.text(n).split("\n")) {
            if (line.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line.substring("11-11 02:38:44.184 ".length()));
        }
        return out.toString();
    }

    private static long ms(String moment) {
        return ZonedDateTime.parse(moment).toInstant().toEpochMilli();
    }

    /** The history's line for one logged at {@code moment} on a phone in {@code zone}. */
    private static String at(String zone, String moment, String msg) {
        TimeZone was = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            FoldLog.add(ms(moment), 'I', msg);
            String line = FoldLog.text(1);
            yes("the line ends in a line break", line.endsWith("\n"));
            return line.substring(0, line.length() - 1);
        } finally {
            TimeZone.setDefault(was);
        }
    }

    /** The same time the way java.time writes it, to check against. */
    private static String format(long time, TimeZone zone) {
        ZonedDateTime z = java.time.Instant.ofEpochMilli(time).atZone(zone.toZoneId());
        return String.format(java.util.Locale.ROOT, "%02d-%02d %02d:%02d:%02d.%03d", z.getMonthValue(),
                z.getDayOfMonth(), z.getHour(), z.getMinute(), z.getSecond(), z.getNano() / 1_000_000);
    }
}
