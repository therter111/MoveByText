package KirizsKeve.MoveByText;

import java.util.Objects;

/** The offline tests' assertions; a failed one throws AssertionError with a readable message. */
public final class Check {

    private Check() {
    }

    /** Code that may throw, for fails(). */
    public interface Code {
        void run() throws Exception;
    }

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\nexpected: " + show(expected) + "\nactual:   " + show(actual));
        }
    }

    public static void has(String text, String part, String what) {
        if (text == null || !text.contains(part)) {
            throw new AssertionError(what + " should contain " + show(part) + "\nactual: " + show(text));
        }
    }

    /** Runs code that must throw, and returns what it threw. */
    public static Throwable fails(Code code, String what) {
        try {
            code.run();
        } catch (Throwable e) {
            return e;
        }
        throw new AssertionError(what + ": expected an exception, but it returned normally");
    }

    /** Readable form for failure messages: strings quoted with escapes visible, long ones cut short. */
    public static String show(Object value) {
        if (!(value instanceof String)) return String.valueOf(value);
        String s = ((String) value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        if (s.length() > 300) s = s.substring(0, 300) + "... (" + ((String) value).length() + " chars)";
        return "\"" + s + "\"";
    }
}
