package KirizsKeve.MoveByText;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Rules about the code itself, checked in the compiled classes (jdeps through SandboxCheck, javap through Bytecode):
 * - nlp/ never touches the game: no campaign, UI, combat... API, none of the mod's game-side classes, and of Global
 *   only getLogger (and getSettings in Settings). The README promises it, and that code runs on the backends'
 *   worker thread, where Starsector objects must not be touched.
 * - What runs on the worker thread outside nlp/ (the Backend.Callback implementations, and everything they call in
 *   the mod) calls nothing of the game either.
 * - Nothing in src/ calls System.exit, Runtime.exit/halt or Thread.sleep: the game thread must never stop or wait.
 * And about the project: every test suite is run by AllOfflineTests, and every script a .bat file starts exists.
 *
 * Run main() from IntelliJ (working directory = the mod folder), or every offline suite with AllOfflineTests.
 */
public class CodeRulesTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("CodeRulesTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    private static final String MOD = "KirizsKeve.MoveByText.";
    private static final String NLP = MOD + "nlp.";

    /** Packages of the game nlp/ must not use: everything but the plain API types (Global, SettingsAPI...). */
    private static final List<String> GAME_PACKAGES = Arrays.asList("com.fs.starfarer.api.campaign.", "com.fs.starfarer.api.ui.",
            "com.fs.starfarer.api.combat.", "com.fs.starfarer.api.fleet.", "com.fs.starfarer.api.characters.",
            "com.fs.starfarer.api.impl.", "com.fs.starfarer.api.loading.", "com.fs.starfarer.api.input.",
            "com.fs.starfarer.campaign.", "com.fs.state.", "org.lwjgl.opengl.", "rolflectionlib.");

    static {
        test("nlp/ uses none of the game's campaign, UI or combat API", () -> {
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : SandboxCheck.dependencies(classes()).entrySet()) {
                if (!e.getKey().startsWith(NLP)) continue;
                for (String used : e.getValue()) {
                    for (String pkg : GAME_PACKAGES) {
                        if (used.startsWith(pkg)) bad.add(e.getKey() + " uses " + used);
                    }
                }
            }
            check(bad.isEmpty(), String.join("\n", bad));
        });

        test("nlp/ uses none of the mod's game-side classes (game/ and the root package)", () -> {
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : SandboxCheck.dependencies(classes()).entrySet()) {
                if (!e.getKey().startsWith(NLP)) continue;
                for (String used : e.getValue()) {
                    boolean root = used.startsWith(MOD) && used.indexOf('.', MOD.length()) < 0;
                    if (root || used.startsWith(MOD + "game.")) bad.add(e.getKey() + " uses " + used);
                }
            }
            check(bad.isEmpty(), String.join("\n", bad));
        });

        test("nlp/ only logs through Global, and only Settings reads the settings", () -> {
            List<String> bad = new ArrayList<>();
            for (Bytecode.ClassInfo c : bytecode().values()) {
                if (!c.name.startsWith(NLP)) continue;
                boolean isSettings = c.name.equals(NLP + "Settings");
                for (String call : c.allCalls()) {
                    boolean global = call.startsWith("com.fs.starfarer.api.Global.");
                    if (global && call.equals("com.fs.starfarer.api.Global.getLogger")) continue;
                    if (isSettings && (call.equals("com.fs.starfarer.api.Global.getSettings")
                            || call.startsWith("com.fs.starfarer.api.SettingsAPI."))) continue;
                    if (global || call.startsWith("com.fs.starfarer.api.SettingsAPI.")) bad.add(c.name + " calls " + call);
                }
            }
            check(bad.isEmpty(), String.join("\n", bad));
        });

        test("what runs on the worker thread outside nlp/ calls nothing of the game", () -> {
            Map<String, Bytecode.ClassInfo> all = bytecode();
            List<String> callbacks = new ArrayList<>();
            List<String> bad = new ArrayList<>();
            for (Bytecode.ClassInfo c : all.values()) {
                if (c.name.startsWith(NLP) || !c.interfaces.contains(NLP + "Backend$Callback")) continue;
                callbacks.add(c.name);
                for (String method : c.calls.keySet()) bad.addAll(gameCallsFrom(all, c.name, method));
            }
            check(!callbacks.isEmpty(), "no Backend.Callback found outside nlp/; has CommandConsole changed?");
            check(bad.isEmpty(), "on the worker thread:\n" + String.join("\n", new TreeSet<>(bad)));
        });

        test("the mod never stops or makes the game thread wait (System.exit, Runtime.exit/halt, Thread.sleep)", () -> {
            Set<String> forbidden = new HashSet<>(Arrays.asList("java.lang.System.exit", "java.lang.Runtime.exit",
                    "java.lang.Runtime.halt", "java.lang.Thread.sleep"));
            List<String> bad = new ArrayList<>();
            for (Bytecode.ClassInfo c : bytecode().values()) {
                for (Map.Entry<String, Set<String>> m : c.calls.entrySet()) {
                    for (String call : m.getValue()) {
                        if (forbidden.contains(call)) bad.add(c.name + "." + m.getKey() + " calls " + call);
                    }
                }
            }
            check(bad.isEmpty(), String.join("\n", bad));
        });

        test("the rules see what they should: a callback calling TerminalLog would be caught", () -> {
            // TerminalLog.show reaches the campaign screen (Global.getSector()); a callback that called it would break
            List<String> found = gameCallsFrom(bytecode(), MOD + "game.TerminalLog", "show");
            check(found.stream().anyMatch(s -> s.contains("com.fs.starfarer.api.Global.getSector")),
                    "TerminalLog.show reaches Global.getSector: " + found);
        });

        // ------------------------------------------------------------------------------------------
        // The project
        // ------------------------------------------------------------------------------------------

        test("every test suite is run by AllOfflineTests", () -> {
            Path tests = Paths.get(AllOfflineTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> missing = new ArrayList<>();
            int suites = 0;
            try (Stream<Path> walk = Files.walk(tests)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".class") && !f.getFileName().toString().contains("$"))
                        .collect(Collectors.toList())) {
                    String name = tests.relativize(p).toString().replace('\\', '/').replace('/', '.').replaceAll("\\.class$", "");
                    Field f;
                    try {
                        f = Class.forName(name).getDeclaredField("SUITE");
                    } catch (NoSuchFieldException e) {
                        continue;
                    }
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != TestSuite.class) continue;
                    suites++;
                    if (!AllOfflineTests.suites().contains(f.get(null))) missing.add(name);
                }
            }
            check(suites >= 15, "only " + suites + " suites found in " + tests);
            check(missing.isEmpty(), "not in AllOfflineTests.suites(): " + missing);
        });

        test("every script a .bat file starts exists", () -> {
            List<String> missing = new ArrayList<>();
            int scripts = 0;
            // The .bat files cd to their own folder first (cd /d "%~dp0"), so the names are relative to it
            Pattern script = Pattern.compile("(?:^|[\\s\"])((?:\\.\\.\\\\|[\\w.-]+\\\\)*[\\w.-]+\\.(?:py|bat))\\b");
            try (Stream<Path> walk = Files.walk(Paths.get("nlp"))) {
                for (Path bat : walk.filter(f -> f.toString().endsWith(".bat")).collect(Collectors.toList())) {
                    for (String line : Files.readAllLines(bat, StandardCharsets.UTF_8)) {
                        if (line.trim().toLowerCase().startsWith("rem ") || line.contains("activate.bat")) continue;
                        Matcher m = script.matcher(line);
                        while (m.find()) {
                            scripts++;
                            Path target = bat.getParent().resolve(m.group(1).replace('\\', '/')).normalize();
                            if (!Files.isRegularFile(target)) missing.add(bat + " starts " + m.group(1));
                        }
                    }
                }
            }
            check(scripts >= 8, "only " + scripts + " scripts found in the .bat files");
            check(missing.isEmpty(), String.join("\n", missing));
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** The compiled classes of src/: wherever MoveByTextImpl was loaded from (out/build/classes or IntelliJ's). */
    private static Path classes() throws Exception {
        return Paths.get(MoveByTextImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Map<String, Bytecode.ClassInfo> bytecode;

    private static Map<String, Bytecode.ClassInfo> bytecode() throws Exception {
        if (bytecode == null) bytecode = Bytecode.read(classes());
        return bytecode;
    }

    /**
     * The calls into the game (com.fs.*) that a method can reach through the mod's own code outside nlp/ (which the
     * rules above keep game-free), as "path: call". Methods are followed by name, overloads and lambdas together.
     */
    private static List<String> gameCallsFrom(Map<String, Bytecode.ClassInfo> all, String className, String method) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String[]> todo = new ArrayDeque<>();
        todo.add(new String[]{className, method, className + "." + method});
        while (!todo.isEmpty()) {
            String[] at = todo.poll();
            if (!seen.add(at[0] + "." + at[1])) continue;
            Bytecode.ClassInfo c = all.get(at[0]);
            if (c == null) continue;
            for (Map.Entry<String, Set<String>> m : c.calls.entrySet()) {
                // The method itself, and the lambdas written inside it (lambda$<method>$0...)
                if (!m.getKey().equals(at[1]) && !m.getKey().startsWith("lambda$" + at[1] + "$")) continue;
                for (String call : m.getValue()) {
                    if (call.startsWith("com.fs.")) {
                        found.add(at[2] + ": " + call);
                        continue;
                    }
                    int dot = call.lastIndexOf('.');
                    String owner = call.substring(0, dot);
                    if (owner.startsWith(MOD) && !owner.startsWith(NLP)) {
                        todo.add(new String[]{owner, call.substring(dot + 1), at[2] + " -> " + call});
                    }
                }
            }
        }
        return found;
    }
}
