package android.util;

/**
 * Stands in for Android's log while the tests run on a plain JVM: {@code FoldLog} writes every
 * line there, and it is the one class of Android's that the tested code touches. It notes the
 * last call, so that a test can see what Android's log would have got.
 */
public final class Log {
    public static String last;

    private Log() {}

    public static int i(String tag, String msg) {
        return note("I", tag, msg, null);
    }

    public static int w(String tag, String msg) {
        return note("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable t) {
        return note("W", tag, msg, t);
    }

    public static int e(String tag, String msg, Throwable t) {
        return note("E", tag, msg, t);
    }

    private static int note(String level, String tag, String msg, Throwable t) {
        last = level + "/" + tag + ": " + msg + (t == null ? "" : " [" + t.getClass().getSimpleName() + "]");
        return 0;
    }
}
