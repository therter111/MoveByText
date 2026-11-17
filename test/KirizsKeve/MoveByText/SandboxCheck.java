package KirizsKeve.MoveByText;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;

/**
 * Finds the classes of the JDK that the mod uses but Starsector refuses to load for it. The game loads mod code
 * through its own class loader (com.fs.starfarer.loading.scripts.B in starfarer_obf.jar), which throws
 * SecurityException("File access and reflection are not allowed to scripts.") for these. javac doesn't know, so
 * without this check a blocked class only shows when the game runs that code.
 *
 * The rules below are that class loader's, read from its bytecode (Starsector 0.98a):
 * - a name starting with "java.io" or "java.nio.file.File" must be one of IO_ALLOWED;
 * - "javax.script..." and "java.util.prefs..." are refused;
 * - a name starting with "java.lang.reflect" must be one of REFLECT_ALLOWED;
 * - "sun.reflect.misc.MethodUtil" is refused.
 *
 * Which classes the code uses comes from jdeps, so this needs a JDK. Standalone, so build.py can compile and run
 * it on its own:  java KirizsKeve.MoveByText.SandboxCheck jars/MoveByText.jar  (exits with 1 when something is
 * refused). ProjectFilesTest runs the same check.
 */
public final class SandboxCheck {

    /** The java.io (and java.nio) classes the game allows, exactly as listed in its class loader. */
    static final Set<String> IO_ALLOWED = new HashSet<>(Arrays.asList(
            "java.io.BufferedInputStream", "java.io.BufferedReader", "java.io.FilterInputStream",
            "java.io.InputStreamReader", "java.io.Reader", "java.io.Serializable", "java.io.InvalidClassException",
            "java.io.ObjectStreamException", "java.io.InputStream", "java.io.IOException", "java.io.PrintStream",
            "java.io.PrintWriter", "java.io.ByteArrayInputStream", "java.io.FilterOutputStream",
            "java.io.OutputStream", "java.io.Closeable", "java.io.Flushable", "java.nio.ByteBuffer",
            "java.nio.CharBuffer", "java.nio.IntBuffer", "java.io.StringReader", "java.io.FileReader",
            "java.lang.Class"));

    /** The java.lang.reflect classes the game allows. */
    static final Set<String> REFLECT_ALLOWED = new HashSet<>(Arrays.asList(
            "java.lang.reflect.AnnotatedElement", "java.lang.reflect.InvocationTargetException",
            "java.lang.reflect.Type", "java.lang.reflect.GenericDeclaration"));

    private SandboxCheck() {
    }

    /** Why the game refuses to load the class, or null when it loads it. */
    static String refusal(String className) {
        if (className.startsWith("java.io") || className.startsWith("java.nio.file.File")) {
            return IO_ALLOWED.contains(className) ? null : "file access";
        }
        if (className.startsWith("javax.script")) return "javax.script";
        if (className.startsWith("java.util.prefs")) return "java preferences";
        if (className.startsWith("java.lang.reflect")) {
            return REFLECT_ALLOWED.contains(className) ? null : "reflection";
        }
        return className.equals("sun.reflect.misc.MethodUtil") ? "reflection" : null;
    }

    /** Class -> the classes it uses, for a folder of class files or a jar, from jdeps -verbose:class. */
    static Map<String, Set<String>> dependencies(Path classesOrJar) {
        ToolProvider jdeps = ToolProvider.findFirst("jdeps").orElseThrow(() ->
                new IllegalStateException("jdeps not found: run this with a JDK (17 or newer), not a JRE"));
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int code = jdeps.run(new PrintStream(out, true), new PrintStream(err, true),
                "--multi-release", "17", "-verbose:class", classesOrJar.toString());
        String text = out.toString(StandardCharsets.UTF_8);
        if (code != 0) {
            throw new IllegalStateException("jdeps failed on " + classesOrJar + ": " + err.toString(StandardCharsets.UTF_8) + text);
        }
        Map<String, Set<String>> deps = new TreeMap<>();
        // Detail lines are indented: "   KirizsKeve.MoveByText.X   -> java.io.InputStream   java.base"
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith(" ")) continue;
            String[] f = line.trim().split("\\s+");
            if (f.length < 3 || !f[1].equals("->")) continue;
            deps.computeIfAbsent(f[0], k -> new TreeSet<>()).add(f[2]);
        }
        return deps;
    }

    /** "KirizsKeve.X uses java.io.File (file access)" for each refused class used; empty when all is fine. */
    static List<String> violations(Path classesOrJar) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : dependencies(classesOrJar).entrySet()) {
            for (String used : e.getValue()) {
                String why = refusal(used);
                if (why != null) found.add(e.getKey() + " uses " + used + " (" + why + ")");
            }
        }
        return found;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("usage: SandboxCheck <jar or class folder>...");
            System.exit(2);
        }
        boolean ok = true;
        for (String arg : args) {
            Path path = Paths.get(arg);
            if (!Files.exists(path)) {
                System.out.println("sandbox check: " + arg + " doesn't exist");
                ok = false;
                continue;
            }
            List<String> found = violations(path);
            if (found.isEmpty()) {
                System.out.println("sandbox check: " + arg + " uses no class Starsector refuses to load");
            } else {
                ok = false;
                System.out.println("sandbox check: " + arg + " uses classes Starsector refuses to load for mods:");
                for (String f : found) System.out.println("  " + f);
            }
        }
        System.exit(ok ? 0 : 1);
    }
}
