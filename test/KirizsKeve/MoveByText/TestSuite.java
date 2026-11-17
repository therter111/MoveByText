package KirizsKeve.MoveByText;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.LogManager;
import org.apache.log4j.varia.NullAppender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The offline tests' runner: named tests, in the order they were added, each run with a fresh fixture (a fake
 * server, or nothing). Prints "PASS   name  (ms)" or "FAIL   name" with the reason, then "N/M passed in X ms".
 *
 * A test class keeps one suite in a static field and adds its tests in a static block; its main() is just
 * SUITE.main(args), and AllOfflineTests runs every suite at once. Pass a word to run only the tests whose name
 * contains it.
 *
 * @param <F> what each test gets, e.g. a fake Gemini server; Void for tests that need nothing
 */
public final class TestSuite<F> {

    public interface Test<F> {
        void run(F fixture) throws Exception;
    }

    /** A test that needs no fixture. */
    public interface PlainTest {
        void run() throws Exception;
    }

    /** Makes the fixture for one test. */
    public interface Factory<F> {
        F create() throws Exception;
    }

    /** Cleans up after one test, e.g. stops the fake server. */
    public interface Cleanup<F> {
        void stop(F fixture) throws Exception;
    }

    private static boolean logsSilenced = false;

    private final String name;
    private final Factory<F> factory;
    private final Cleanup<F> cleanup;
    private final Map<String, Test<F>> tests = new LinkedHashMap<>();

    public TestSuite(String name, Factory<F> factory, Cleanup<F> cleanup) {
        this.name = name;
        this.factory = factory;
        this.cleanup = cleanup;
    }

    /** A suite whose tests need no fixture. */
    public static TestSuite<Void> plain(String name) {
        return new TestSuite<>(name, () -> null, fixture -> {
        });
    }

    public String name() {
        return name;
    }

    public void add(String testName, Test<F> body) {
        if (tests.put(testName, body) != null) throw new IllegalStateException("Two tests named " + testName);
    }

    /** For plain suites: a test that takes no fixture. */
    public void add(String testName, PlainTest body) {
        add(testName, fixture -> body.run());
    }

    /** Runs the tests whose name contains filter (all when it is null) and returns how many ran and failed. */
    public Result run(String filter) {
        silenceLogs();
        int run = 0, failed = 0;
        long allStart = System.nanoTime();
        for (Map.Entry<String, Test<F>> t : tests.entrySet()) {
            if (filter != null && !t.getKey().contains(filter)) continue;
            run++;
            long start = System.nanoTime();
            F fixture = null;
            try {
                fixture = factory.create();
                t.getValue().run(fixture);
                System.out.println("PASS   " + t.getKey() + "  (" + (System.nanoTime() - start) / 1000000 + " ms)");
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL   " + t.getKey());
                String detail = e instanceof AssertionError ? e.getMessage() : "unexpected " + e;
                System.out.println("       " + String.valueOf(detail).replace("\n", "\n       "));
                if (!(e instanceof AssertionError)) {
                    for (StackTraceElement frame : e.getStackTrace()) {
                        if (frame.getClassName().startsWith("KirizsKeve")) System.out.println("         at " + frame);
                    }
                }
            } finally {
                if (fixture != null) {
                    try {
                        cleanup.stop(fixture);
                    } catch (Exception e) {
                        System.out.println("       (cleanup failed: " + e + ")");
                    }
                }
            }
        }
        System.out.println();
        System.out.println((run - failed) + "/" + run + " passed in " + (System.nanoTime() - allStart) / 1000000 + " ms");
        return new Result(run, failed);
    }

    /** Runs the suite with the filter from the command line and exits with 1 on a failure or when nothing ran. */
    public void main(String[] args) {
        Result r = run(args.length > 0 ? args[0] : null);
        System.exit(r.ok() ? 0 : 1);
    }

    /** How a run went. */
    public static final class Result {
        public final int run;
        public final int failed;

        Result(int run, int failed) {
            this.run = run;
            this.failed = failed;
        }

        public boolean ok() {
            return failed == 0 && run > 0;
        }
    }

    /**
     * The clients log every request through log4j; the output should only be the results. The Starsector jars bring
     * a log4j.properties that logs to the console, which log4j reads the first time it is used, so that setup is
     * dropped before the NullAppender goes in.
     */
    public static void silenceLogs() {
        if (logsSilenced) return;
        logsSilenced = true;
        LogManager.resetConfiguration();
        BasicConfigurator.configure(new NullAppender());
    }
}
