package app.foldfix;

import java.io.PrintStream;
import java.util.Objects;

/**
 * The whole test framework: named cases, two kinds of check, and a count at the end. A failed
 * check throws, so a case stops at its first failure and the next case still runs.
 */
final class Suite {
    interface Body {
        void run() throws Exception;
    }

    /** A check that didn't hold; the message says what was expected and what came instead. */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private final PrintStream out;
    private String group = "";
    private int cases, failed, groupCases, groupFailed;

    Suite(PrintStream out) {
        this.out = out;
    }

    /** Starts a group of cases; its name is printed with their count. */
    void group(String name) {
        endGroup();
        group = name;
    }

    void test(String name, Body body) {
        cases++;
        groupCases++;
        try {
            body.run();
        } catch (Throwable t) {
            failed++;
            groupFailed++;
            out.println("FAIL  " + group + ": " + name);
            if (t instanceof Failure) {
                out.println("      " + t.getMessage().replace("\n", "\n      "));
            } else {  // the case itself broke
                t.printStackTrace(out);
            }
        }
    }

    /** Prints the totals; returns whether every case passed. */
    boolean finish() {
        endGroup();
        out.println(cases + " cases, " + failed + " failed");
        return failed == 0 && cases > 0;
    }

    private void endGroup() {
        if (groupCases > 0) {
            out.println((groupFailed == 0 ? "ok    " : "FAIL  ") + group + ": " + groupCases + " cases"
                    + (groupFailed == 0 ? "" : ", " + groupFailed + " failed"));
        }
        groupCases = groupFailed = 0;
    }

    static void same(String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new Failure(what + "\nexpected: " + show(expected) + "\nactual:   " + show(actual));
        }
    }

    static void yes(String what, boolean actual) {
        if (!actual) throw new Failure("expected: " + what);
    }

    static void no(String what, boolean actual) {
        if (actual) throw new Failure("not expected: " + what);
    }

    /** Several lines as one value, so that a list of steps or log lines compares and prints well. */
    static String lines(String... lines) {
        return String.join("\n", lines);
    }

    private static String show(Object o) {
        String s = String.valueOf(o);
        if (s.isEmpty()) return "(nothing)";
        return s.contains("\n") ? "\n  " + s.replace("\n", "\n  ") : s;
    }
}
