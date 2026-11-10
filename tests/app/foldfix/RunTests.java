package app.foldfix;

/**
 * Runs every test of FoldFix's rules on a plain JVM ({@code test.sh}). The tests cover the classes
 * that hold decisions and nothing of Android's: {@link Reconciler}, {@link Ownership} and
 * {@link Stale}, and {@link FoldLog}'s history. They say what FoldFix decides, not what Android
 * then does with it.
 */
public final class RunTests {
    private RunTests() {}

    public static void main(String[] args) {
        Suite t = new Suite(System.out);
        ReconcilerTest.tests(t);
        OwnershipTest.tests(t);
        StaleTest.tests(t);
        ScenarioTest.tests(t);
        FoldLogTest.tests(t);
        if (!t.finish()) System.exit(1);
    }
}
