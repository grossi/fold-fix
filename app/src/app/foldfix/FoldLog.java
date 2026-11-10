package app.foldfix;

import android.util.Log;

import java.time.LocalDate;
import java.util.TimeZone;

/**
 * FoldFix's one way of logging. Every line goes to Android's log as before, under the tag
 * {@code FoldFix}, and into a short history kept here. The status screen shows the history and
 * copies it for a bug report, and {@code dumpsys} prints it (see {@code FoldService.dump}).
 *
 * <p>The history exists because Android's log doesn't keep FoldFix's lines on the phones FoldFix
 * is for: the dead hinge sensor makes the system log thousands of lines per second, which push
 * everything else out within a couple of minutes. And without a computer the log can't be read
 * at all.
 *
 * <p>It is kept in memory only: no file, no setting. It starts again with the process, which
 * {@link App} marks with a first line.
 *
 * <p>It holds what FoldFix logs and nothing else: device states, timings, the view IDs of System
 * UI's dialog buttons, the names and messages of exceptions. Never pass in text read from the
 * screen, or anything about another app.
 *
 * <p>Safe to call from any thread. Keeping a line costs one lock and, unless it is cut or comes
 * with an exception, no allocation; times are only formatted when the history is read.
 */
final class FoldLog {
    static final String TAG = "FoldFix";
    /**
     * Lines kept. Opening and closing the phone once writes 15 lines, 23 with Pixel Camera on
     * screen (see {@link Curtain}), so this holds the last 33 such cycles, or 21 of the long
     * ones. In memory that is about 50 KB.
     */
    static final int CAPACITY = 500;
    /** Longer lines are cut, so that an exception's message can't make the history large. */
    static final int MAX_LINE = 300;

    static final long DAY_MS = 86_400_000;

    private static final long[] times = new long[CAPACITY];
    private static final char[] levels = new char[CAPACITY];
    private static final String[] lines = new String[CAPACITY];
    /** Lines added since the process started; line number n is at index n % CAPACITY. */
    private static long total;

    private FoldLog() {}

    static void i(String msg) {
        Log.i(TAG, msg);
        add('I', msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
        add('W', msg);
    }

    /** Android's log gets the stack trace; the history only what the exception is. */
    static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        add('W', msg + ": " + brief(t));
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        add('E', msg + ": " + brief(t));
    }

    private static String brief(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getName() : t.getClass().getName() + ": " + message;
    }

    private static synchronized void add(char level, String msg) {
        int at = (int) (total++ % CAPACITY);
        times[at] = System.currentTimeMillis();
        levels[at] = level;
        lines[at] = msg.length() > MAX_LINE ? msg.substring(0, MAX_LINE) + "…" : msg;
    }

    /** Lines added since the process started, more than are kept: changes whenever the history does. */
    static synchronized long total() {
        return total;
    }

    /**
     * The newest {@code max} lines, oldest first, each ending in a line break, for example
     * {@code 11-11 02:38:44.184 I hall open}: local time, then the level as in Android's log.
     */
    static String text(int max) {
        long[] t;
        char[] l;
        String[] s;
        synchronized (FoldLog.class) {  // only to copy: formatting doesn't hold up logging
            int n = (int) Math.min(Math.min(total, CAPACITY), Math.max(max, 0));
            t = new long[n];
            l = new char[n];
            s = new String[n];
            for (int k = 0; k < n; k++) {
                int at = (int) ((total - n + k) % CAPACITY);
                t[k] = times[at];
                l[k] = levels[at];
                s[k] = lines[at];
            }
        }
        // Times are put together by hand. The status screen does this on the main thread, which the
        // service shares, and Java's formatters took 0.2 ms per line there (20 ms for 100 lines).
        TimeZone zone = TimeZone.getDefault();
        StringBuilder out = new StringBuilder(t.length * 96);
        long day = Long.MIN_VALUE;
        LocalDate date = null;
        for (int k = 0; k < t.length; k++) {
            long local = t[k] + zone.getOffset(t[k]);
            if (Math.floorDiv(local, DAY_MS) != day) {
                day = Math.floorDiv(local, DAY_MS);
                date = LocalDate.ofEpochDay(day);
            }
            int ms = (int) Math.floorMod(local, DAY_MS);
            digits(out, date.getMonthValue(), 2).append('-');
            digits(out, date.getDayOfMonth(), 2).append(' ');
            digits(out, ms / 3_600_000, 2).append(':');
            digits(out, ms / 60_000 % 60, 2).append(':');
            digits(out, ms / 1_000 % 60, 2).append('.');
            digits(out, ms % 1_000, 3).append(' ').append(l[k]).append(' ').append(s[k]).append('\n');
        }
        return out.toString();
    }

    /** Appends {@code value} with leading zeros, {@code width} digits wide. */
    private static StringBuilder digits(StringBuilder out, int value, int width) {
        for (int unit = width == 3 ? 100 : 10; unit > 0; unit /= 10) out.append((char) ('0' + value / unit % 10));
        return out;
    }
}
