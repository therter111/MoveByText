package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.command.CommandParser;
import KirizsKeve.MoveByText.command.FleetCommand;
import KirizsKeve.MoveByText.nlp.FactionNames;
import KirizsKeve.MoveByText.nlp.Provider;
import com.fs.starfarer.api.BaseModPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline checks of the mod's own files, the ones that only fail in the game or in another tool when they are wrong:
 * - the compiled code and the jar use no JDK class Starsector refuses to load for mods (SandboxCheck);
 * - mod_info.json names a mod plugin that is in the jar, jars that exist, and dependencies that are installed;
 * - llmdata.json.example is strict JSON (nlp/rasa/test_handwritten.py reads it with Python) and mentions every
 *   setting the code reads;
 * - the LlmPromptTest cases are all usable: valid expected replies, real faction ids, no input twice; and the Python
 *   tools read them as Java means them: check_overlap.py finds exactly these inputs, test_handwritten.py and
 *   compare_runs.py read LlmPromptTest's console lines back (these run Python, see Python);
 * - canonical(), which judges every live LlmPromptTest reply, and the provider list in the docs.
 *
 * Run main() from IntelliJ (working directory = the mod folder), or every offline suite with AllOfflineTests.
 */
public class ProjectFilesTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("ProjectFilesTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    private static final Path JAR = Paths.get("jars", "MoveByText.jar");
    private static final Path MOD_INFO = Paths.get("mod_info.json");
    private static final Path EXAMPLE = Paths.get("data", "config", "llmdata.json.example");

    static {
        // ------------------------------------------------------------------------------------------
        // Classes the game refuses to load
        // ------------------------------------------------------------------------------------------

        test("sandbox: the rules match the game's class loader", () -> {
            for (String blocked : new String[]{"java.io.File", "java.io.FileInputStream", "java.io.ByteArrayOutputStream",
                    "java.io.UncheckedIOException", "java.nio.file.Files", "java.nio.file.FileSystems",
                    "java.lang.reflect.Method", "java.lang.reflect.Field", "javax.script.ScriptEngine",
                    "java.util.prefs.Preferences", "sun.reflect.misc.MethodUtil"}) {
                check(SandboxCheck.refusal(blocked) != null, blocked + " is refused");
            }
            for (String allowed : new String[]{"java.io.InputStream", "java.io.OutputStream", "java.io.IOException",
                    "java.io.PrintStream", "java.io.BufferedReader", "java.lang.reflect.Type", "java.nio.file.Path",
                    "java.lang.invoke.LambdaMetafactory", "java.net.HttpURLConnection", "java.util.List"}) {
                same(null, SandboxCheck.refusal(allowed), allowed + " is allowed");
            }
        });

        test("sandbox: the check finds a refused class in compiled code", () -> {
            Path dir = Files.createTempDirectory("sandbox-check");
            try {
                Path src = dir.resolve("UsesBlocked.java");
                Files.write(src, ("public class UsesBlocked { Object o = new java.io.ByteArrayOutputStream(); }")
                        .getBytes(StandardCharsets.UTF_8));
                JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
                check(javac != null, "no compiler: run the tests with a JDK");
                same(0, javac.run(null, null, null, "-d", dir.toString(), src.toString()), "javac exit code");
                List<String> found = SandboxCheck.violations(dir);
                same(1, found.size(), "violations: " + found);
                has(found.get(0), "UsesBlocked uses java.io.ByteArrayOutputStream (file access)", "violation");
            } finally {
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path p : files.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList())) Files.deleteIfExists(p);
                }
            }
        });

        test("sandbox: the mod's compiled classes use no class the game refuses", () -> {
            Path classes = Paths.get(MoveByTextImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> found = SandboxCheck.violations(classes);
            check(found.isEmpty(), "refused by Starsector:\n" + String.join("\n", found));
        });

        test("sandbox: jars/MoveByText.jar uses no class the game refuses", () -> {
            check(Files.exists(JAR), JAR + " doesn't exist: build it (python build.py)");
            List<String> found = SandboxCheck.violations(JAR);
            check(found.isEmpty(), "refused by Starsector:\n" + String.join("\n", found));
        });

        // ------------------------------------------------------------------------------------------
        // mod_info.json
        // ------------------------------------------------------------------------------------------

        test("mod_info: the modPlugin is a BaseModPlugin, and it is in the jar", () -> {
            JSONObject info = modInfo();
            String plugin = info.getString("modPlugin");
            check(BaseModPlugin.class.isAssignableFrom(Class.forName(plugin)), plugin + " extends BaseModPlugin");
            try (ZipFile jar = new ZipFile(JAR.toFile())) {
                check(jar.getEntry(plugin.replace('.', '/') + ".class") != null, plugin + " is in " + JAR);
            }
        });

        test("mod_info: every jar it lists exists", () -> {
            JSONArray jars = modInfo().getJSONArray("jars");
            check(jars.length() > 0, "no jars listed");
            for (int i = 0; i < jars.length(); i++) {
                check(Files.exists(Paths.get(jars.getString(i))), jars.getString(i) + " exists");
            }
        });

        test("mod_info: every dependency is installed next to this mod, under its id", () -> {
            Map<String, Path> installed = new HashMap<>();
            Path mods = Paths.get("").toAbsolutePath().getParent();
            try (Stream<Path> dirs = Files.list(mods)) {
                for (Path dir : dirs.collect(Collectors.toList())) {
                    Path info = dir.resolve("mod_info.json");
                    if (!Files.isRegularFile(info)) continue;
                    try {
                        installed.put(read(info).getString("id"), dir);
                    } catch (Exception e) {
                        // another mod's file this reader can't read: not one of ours to judge
                    }
                }
            }
            JSONArray deps = modInfo().getJSONArray("dependencies");
            for (int i = 0; i < deps.length(); i++) {
                String id = deps.getJSONObject(i).getString("id");
                check(installed.containsKey(id), "dependency " + show(id) + " is installed in " + mods
                        + " (found: " + new TreeSet<>(installed.keySet()) + ")");
            }
        });

        // ------------------------------------------------------------------------------------------
        // llmdata.json.example
        // ------------------------------------------------------------------------------------------

        test("strict JSON: what Python's json accepts and refuses", () -> {
            StrictJson.check("{\"a\": [1, -2.5e3, true, null, \"x\\n\\u00e1\"], \"b\": {}}");
            for (String bad : new String[]{"{\"a\": 1,}", "[1, 2,]", "{'a': 1}", "{a: 1}", "{\"a\": 1} x",
                    "{\"a\": 01}", "{\"a\": \"line\nbreak\"}", "{\"a\": 1, \"a\": 2}", "# comment\n{}", ""}) {
                fails(() -> StrictJson.check(bad), "refuses " + show(bad));
            }
        });

        test("example settings: strict JSON once the # lines are gone, as test_handwritten.py reads it", () -> {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(EXAMPLE, StandardCharsets.UTF_8)) {
                if (!line.trim().startsWith("#")) sb.append(line).append('\n');
            }
            try {
                StrictJson.check(sb.toString());
            } catch (IllegalArgumentException e) {
                // the line number counts the lines left after the # lines are gone
                throw new AssertionError(EXAMPLE + " is not strict JSON without its # lines: " + e.getMessage());
            }
        });

        test("example settings: every setting the code reads is in it", () -> {
            String example = new String(Files.readAllBytes(EXAMPLE), StandardCharsets.UTF_8);
            TreeSet<String> missing = new TreeSet<>();
            for (String key : settingsTheCodeReads()) {
                if (!example.contains("\"" + key + "\"")) missing.add(key);
            }
            check(settingsTheCodeReads().size() >= 15, "only " + settingsTheCodeReads() + " found in src/");
            check(missing.isEmpty(), "read by the code but not in " + EXAMPLE + ": " + missing);
        });

        // ------------------------------------------------------------------------------------------
        // The LlmPromptTest cases
        // ------------------------------------------------------------------------------------------

        test("test cases: every expected reply is one LlmPromptTest can match", () -> {
            List<String> bad = new ArrayList<>();
            for (LlmPromptTest.Case c : LlmPromptTest.cases()) {
                if (c.accepted.length == 0) bad.add(show(c.input) + ": no expected reply");
                for (String reply : c.accepted) {
                    if (LlmPromptTest.canonical(reply) == null) bad.add(show(c.input) + ": " + show(reply));
                }
            }
            check(LlmPromptTest.cases().size() > 500, "only " + LlmPromptTest.cases().size() + " cases");
            check(bad.isEmpty(), bad.size() + " unusable:\n" + String.join("\n", bad));
        });

        test("test cases: expected replies use real faction ids and sizes from 1 to 300", () -> {
            List<String> bad = new ArrayList<>();
            for (LlmPromptTest.Case c : LlmPromptTest.cases()) {
                for (String reply : c.accepted) {
                    for (FleetCommand command : new CommandParser().parse(reply)) {
                        String id = command.factionId();
                        if (id != null && !FactionNames.ids().contains(id)) bad.add(show(c.input) + ": faction " + show(id));
                    }
                    Matcher points = Pattern.compile("<[A-Z]+:[^:>]+:(\\d+)").matcher(reply);
                    while (points.find()) {
                        long p = Long.parseLong(points.group(1));
                        if (p < FleetCommand.MIN_POINTS || p > FleetCommand.MAX_POINTS) {
                            bad.add(show(c.input) + ": size " + p + " in " + show(reply));
                        }
                    }
                }
            }
            check(bad.isEmpty(), String.join("\n", bad));
        });

        test("test cases: no input is in there twice", () -> {
            // Exactly the same text: cases that differ only in spacing or case ("   spawn   a   tiny ...",
            // "SPAWN PIRATES") test exactly that on purpose
            Map<String, String> seen = new HashMap<>();
            List<String> twice = new ArrayList<>();
            for (LlmPromptTest.Case c : LlmPromptTest.cases()) {
                String before = seen.put(c.input, c.group);
                if (before != null) twice.add(show(c.input) + " in \"" + before + "\" and \"" + c.group + "\"");
            }
            check(twice.isEmpty(), twice.size() + " inputs twice:\n" + String.join("\n", twice));
        });
    }

    static {
        // ------------------------------------------------------------------------------------------
        // The Python tools against the Java side
        // ------------------------------------------------------------------------------------------

        test("check_overlap.py: finds exactly the inputs of LlmPromptTest's cases in the Java sources", () -> {
            JSONArray found = (JSONArray) Python.json(String.join("\n",
                    "import json, sys",
                    "sys.path.insert(0, 'nlp/rasa')",
                    "import check_overlap",
                    "print(json.dumps(check_overlap.test_messages()))"));
            List<String> python = new ArrayList<>();
            for (int i = 0; i < found.length(); i++) python.add(found.getString(i));
            List<String> java = new ArrayList<>();
            for (LlmPromptTest.Case c : LlmPromptTest.cases()) java.add(c.input);
            List<String> missing = new ArrayList<>(java), extra = new ArrayList<>(python);
            for (String s : python) missing.remove(s);
            for (String s : java) extra.remove(s);
            check(missing.isEmpty() && extra.isEmpty(), "check_overlap.py reads " + python.size() + " inputs, the cases have "
                    + java.size() + ".\nnot found by it: " + first(missing) + "\nonly found by it: " + first(extra));
        });

        test("console: test_handwritten.py and compare_runs.py read LlmPromptTest's lines back", () -> {
            String console = String.join("\n",
                    LlmPromptTest.reportLine("C:\\mod\\test\\logs\\llm-prompt-test_rasa_2026-11-17.log"),
                    "",
                    LlmPromptTest.groupLine("size words - the same scale"),
                    LlmPromptTest.passLine("spawn a tiny pirate fleet", "<SPAWN:pirates:15>"),
                    LlmPromptTest.retryLine(true, "a big \"hegemony\" fleet"),
                    LlmPromptTest.retryDetails("<NONE>", "<SPAWN:hegemony:150>", new String[]{"<SPAWN:hegemony:150>"}),
                    "",
                    LlmPromptTest.groupLine("advanced - stories"),
                    LlmPromptTest.retryLine(false, "line one\nline two"),
                    LlmPromptTest.retryDetails("hi!", "<NONE>", new String[]{"<SPAWN:pirates:40>", "<SPAWN:pirates:15>"}),
                    LlmPromptTest.errorLine("a case that timed out") + "\n       java.net.SocketTimeoutException: Read timed out",
                    "",
                    LlmPromptTest.groupLine("extensive - long ones"),
                    LlmPromptTest.passLine("a long message", "<NONE>"),
                    "",
                    LlmPromptTest.groupLine("summary"),
                    LlmPromptTest.summaryLine(2, 5),
                    LlmPromptTest.retryLine(true, "a big \"hegemony\" fleet"),
                    LlmPromptTest.retryLine(false, "line one\nline two"),
                    LlmPromptTest.errorLine("a case that timed out"));
            Path file = Files.createTempFile("console", ".txt");
            try {
                Files.write(file, console.getBytes(StandardCharsets.UTF_8));
                JSONObject read = (JSONObject) Python.json(String.join("\n",
                        "import importlib.util, json",
                        // By path: nlp/rasa and nlp/spacy both have a test_handwritten.py
                        "def load(name, path):",
                        "    spec = importlib.util.spec_from_file_location(name, path)",
                        "    module = importlib.util.module_from_spec(spec)",
                        "    spec.loader.exec_module(module)",
                        "    return module",
                        "test_handwritten = load('rasa_test_handwritten', 'nlp/rasa/test_handwritten.py')",
                        "compare_runs = load('spacy_compare_runs', 'nlp/spacy/compare_runs.py')",
                        "text = open(" + pyString(file.toString()) + ", encoding='utf-8').read()",
                        "print(json.dumps({'cases': test_handwritten.parse_console(text),",
                        "                  'counts': test_handwritten.summary_counts(text),",
                        "                  'report': test_handwritten.report_path(text),",
                        "                  'detailed': compare_runs.console_cases(" + pyString(file.toString()) + ")}))"));
                same("[[\"basic\",\"size words - the same scale\",\"PASS\"],[\"basic\",\"size words - the same scale\",\"FLAKY\"],"
                        + "[\"advanced\",\"advanced - stories\",\"FAIL\"],[\"advanced\",\"advanced - stories\",\"ERROR\"],"
                        + "[\"extensive\",\"extensive - long ones\",\"PASS\"]]", read.getJSONArray("cases").toString(),
                        "test_handwritten.parse_console: set, group and status of each case, none from the summary");
                same("[2,5]", read.getJSONArray("counts").toString(), "test_handwritten.summary_counts");
                same("C:\\mod\\test\\logs\\llm-prompt-test_rasa_2026-11-17.log", read.getString("report"),
                        "test_handwritten.report_path");
                JSONArray detailed = read.getJSONArray("detailed");
                same(5, detailed.length(), "compare_runs.console_cases: cases");
                same("[\"size words - the same scale\",\"PASS\",\"spawn a tiny pirate fleet\",\"<SPAWN:pirates:15>\",\"\"]",
                        detailed.get(0).toString(), "a PASS: group, status, input, reply");
                same("[\"size words - the same scale\",\"FLAKY\",\"a big \\\"hegemony\\\" fleet\",\"<NONE>\",\"<SPAWN:hegemony:150>\"]",
                        detailed.get(1).toString(), "a FLAKY: its first reply and what was expected");
                same("[\"advanced - stories\",\"FAIL\",\"line one\\\\nline two\",\"hi!   (not only command tags)\",\"<SPAWN:pirates:40>  or  <SPAWN:pirates:15>\"]",
                        detailed.get(2).toString(), "a FAIL of a two-line input");
                same("[\"advanced - stories\",\"ERROR\",\"a case that timed out\",\"\",\"\"]", detailed.get(3).toString(), "an ERROR");
            } finally {
                Files.deleteIfExists(file);
            }
        });

        test("test cases: group names tell the three sets apart, as parse_console reads them", () -> {
            Map<String, String> prefixes = new HashMap<>();
            prefixes.put("LlmPromptTest.java", "");
            prefixes.put("LlmPromptAdvancedCases.java", "advanced");
            prefixes.put("LlmPromptExtensiveCases.java", "extensive");
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, String> f : prefixes.entrySet()) {
                String src = new String(Files.readAllBytes(Paths.get("test", "KirizsKeve", "MoveByText", f.getKey())), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\\bgroup\\(\"([^\"]*)\"").matcher(src);
                int groups = 0;
                while (m.find()) {
                    groups++;
                    String g = m.group(1);
                    boolean ok = f.getValue().isEmpty() ? !g.startsWith("advanced") && !g.startsWith("extensive") : g.startsWith(f.getValue());
                    if (!ok) bad.add(f.getKey() + ": " + show(g));
                }
                check(groups > 0, "no group(...) in " + f.getKey());
            }
            check(bad.isEmpty(), "groups parse_console would put into the wrong set:\n" + String.join("\n", bad));
        });

        // ------------------------------------------------------------------------------------------
        // canonical(): the judge of every live LlmPromptTest reply
        // ------------------------------------------------------------------------------------------

        test("canonical: tag order and whitespace between tags don't matter", () -> {
            same(LlmPromptTest.canonical("<SPAWN:pirates:40><TRADE:independent:100:Jangala>"),
                    LlmPromptTest.canonical(" <TRADE:independent:100:Jangala>\n <SPAWN:pirates:40> "), "same commands");
        });

        test("canonical: sizes are capped like the game caps them", () -> {
            same(LlmPromptTest.canonical("<SPAWN:pirates:300>"), LlmPromptTest.canonical("<SPAWN:pirates:500>"), "500 -> 300");
            same(LlmPromptTest.canonical("<SPAWN:pirates:1>"), LlmPromptTest.canonical("<SPAWN:pirates:0>"), "0 -> 1");
            check(!LlmPromptTest.canonical("<SPAWN:pirates:40>").equals(LlmPromptTest.canonical("<SPAWN:pirates:41>")), "40 is not 41");
        });

        test("canonical: place names in any case, with or without \" system\"", () -> {
            same(LlmPromptTest.canonical("<TRADE:independent:100:askonia>"),
                    LlmPromptTest.canonical("<TRADE:independent:100:Askonia Star System>"), "trade");
            same(LlmPromptTest.canonical("<HELP:nearby:100:jangala>"), LlmPromptTest.canonical("<HELP:nearby:100:JANGALA system>"), "help");
        });

        test("canonical: faction ids, tag names and the number of tags must match exactly", () -> {
            check(!LlmPromptTest.canonical("<SPAWN:pirate:40>").equals(LlmPromptTest.canonical("<SPAWN:pirates:40>")), "pirate");
            same(null, LlmPromptTest.canonical("<SPAWN:Pirates:40>"), "an id in capitals is no valid reply");
            same(null, LlmPromptTest.canonical("<spawn:pirates:40>"), "a tag name in lower case");
            check(!LlmPromptTest.canonical("<SPAWN:pirates:40><SPAWN:pirates:40>").equals(LlmPromptTest.canonical("<SPAWN:pirates:40>")),
                    "two fleets are not one");
        });

        test("canonical: anything but tags is no valid reply", () -> {
            for (String bad : new String[]{"Sure! <SPAWN:pirates:40>", "<SPAWN:pirates:40> done", "", "NONE", "<NONE><SPAWN:pirates:40>",
                    "<SPAWN:pirates>", "<TRADE:independent:100>", "```<SPAWN:pirates:40>```"}) {
                same(null, LlmPromptTest.canonical(bad), "canonical(" + show(bad) + ")");
            }
            check(LlmPromptTest.canonical("<NONE>") != null, "<NONE> is valid");
        });

        // ------------------------------------------------------------------------------------------
        // The docs
        // ------------------------------------------------------------------------------------------

        test("providers: each one is in the README's table and in the example settings", () -> {
            String readme = new String(Files.readAllBytes(Paths.get("README.md")), StandardCharsets.UTF_8);
            String example = new String(Files.readAllBytes(EXAMPLE), StandardCharsets.UTF_8);
            List<String> missing = new ArrayList<>();
            for (Provider p : Provider.values()) {
                if (!readme.contains("| `" + p.id() + "` |")) missing.add(p.id() + " in README.md");
                if (!example.contains("\"" + p.id() + "\"")) missing.add(p.id() + " in " + EXAMPLE);
            }
            check(missing.isEmpty(), "missing: " + missing);
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    /** The first few, for a failure message. */
    private static List<String> first(List<String> list) {
        return list.subList(0, Math.min(5, list.size()));
    }

    /** A Python string literal for s. */
    private static String pyString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** mod_info.json as the game reads it: it may have single quotes and a trailing comma, which org.json takes. */
    private static JSONObject modInfo() throws Exception {
        return read(MOD_INFO);
    }

    private static JSONObject read(Path path) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    /**
     * The setting names in src/: the keys passed to Settings' readers (text, integer, number, flag, value,
     * timeoutMs, block), to GeminiClient's own(cfg, ...), and read straight from cfg.
     */
    private static TreeSet<String> settingsTheCodeReads() throws Exception {
        Pattern read = Pattern.compile("\\.(?:text|integer|number|flag|value|timeoutMs|block)\\(\"(\\w+)\""
                + "|own\\(cfg, \"(\\w+)\"\\)|\\bcfg\\.opt\\w+\\(\"(\\w+)\"");
        TreeSet<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Paths.get("src"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                Matcher m = read.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                while (m.find()) {
                    for (int g = 1; g <= 3; g++) if (m.group(g) != null) keys.add(m.group(g));
                }
            }
        }
        return keys;
    }
}
