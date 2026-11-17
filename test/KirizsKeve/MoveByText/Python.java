package KirizsKeve.MoveByText;

import org.json.JSONTokener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs Python for the offline tests that check the Java side against the Python tools (the training data generator,
 * check_overlap.py, test_handwritten.py...). The interpreter is the one build.py runs with (it passes
 * -Dmovebytext.python), else "python" on the PATH. The scripts only use the standard library, so any Python 3 works.
 * The working directory is the test's, i.e. the mod folder.
 */
public final class Python {

    private static final long TIMEOUT_SECONDS = 120;

    private Python() {
    }

    static String interpreter() {
        return System.getProperty("movebytext.python", "python");
    }

    /**
     * Runs the code and returns what it printed. A Python that can't be started, a failing script or one that runs
     * too long is an AssertionError with Python's own error output.
     */
    public static String run(String code) throws Exception {
        // A file, not "-c code": quotes inside command line arguments don't survive Windows reliably
        Path dir = Files.createTempDirectory("movebytext-python");
        Path script = dir.resolve("snippet.py"), out = dir.resolve("out.txt"), err = dir.resolve("err.txt");
        try {
            Files.write(script, code.getBytes(StandardCharsets.UTF_8));
            ProcessBuilder pb = new ProcessBuilder(interpreter(), script.toString());
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONDONTWRITEBYTECODE", "1"); // no __pycache__ in the mod's folders
            pb.redirectOutput(out.toFile()).redirectError(err.toFile());
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new AssertionError("can't start Python (\"" + interpreter() + "\"). This check needs Python 3: on the"
                        + " PATH, or given with -Dmovebytext.python=<python.exe> (build.py does that). " + e.getMessage());
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AssertionError("Python took longer than " + TIMEOUT_SECONDS + " s");
            }
            String stdout = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new AssertionError("Python failed (exit " + p.exitValue() + "):\n"
                        + new String(Files.readAllBytes(err), StandardCharsets.UTF_8).trim());
            }
            return stdout;
        } finally {
            for (Path f : new Path[]{script, out, err, dir}) Files.deleteIfExists(f);
        }
    }

    /** run() for code that prints one JSON value: that value, as org.json reads it (JSONObject, JSONArray...). */
    public static Object json(String code) throws Exception {
        String out = run(code).trim();
        try {
            return new JSONTokener(out).nextValue();
        } catch (Exception e) {
            throw new AssertionError("Python printed no JSON: " + Check.show(out));
        }
    }
}
