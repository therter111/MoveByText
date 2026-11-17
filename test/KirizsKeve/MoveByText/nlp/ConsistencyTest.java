package KirizsKeve.MoveByText.nlp;

import KirizsKeve.MoveByText.Python;
import KirizsKeve.MoveByText.TestSuite;
import KirizsKeve.MoveByText.command.CommandParser;
import KirizsKeve.MoveByText.command.FleetCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline checks that files kept in step by hand still agree with the code:
 * - the system prompt (data/config/systemPrompt.txt): its faction ids, its example answers and its size scale;
 * - the Rasa/spaCy training data (nlp/rasa/generate_nlu.py): its factions, places, intents and entity types, read
 *   straight from the Python source; and every size, multiplier, offset, count and number it labels, which Python
 *   itself lists (see Python), so CommandBuilder never gets a labelled value it can't read;
 * - the typo distance, which exists in Java and in nlp/spacy/rules.py: both against test/data/distance_cases.txt.
 *
 * Run main() from IntelliJ (working directory = the mod folder), or every offline suite with AllOfflineTests.
 */
public class ConsistencyTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("ConsistencyTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    private static final String TRAINING = "nlp/rasa/generate_nlu.py";
    private static final String DISTANCE_CASES = "test/data/distance_cases.txt";

    /** The intents of the training data that aren't requests, and that CommandBuilder therefore reads as NONE. */
    private static final Set<String> NOT_REQUESTS = new LinkedHashSet<>(Arrays.asList("out_of_scope"));

    static {
        // ------------------------------------------------------------------------------------------
        // The system prompt
        // ------------------------------------------------------------------------------------------

        test("prompt: the faction ids it allows are exactly FactionNames' vanilla ids", () -> {
            Matcher m = Pattern.compile("faction_id is exactly one of: ([a-z_, ]+)\\.").matcher(prompt());
            check(m.find(), "no 'faction_id is exactly one of: ...' line in the prompt");
            same(FactionNames.ids(), new LinkedHashSet<>(Arrays.asList(m.group(1).split(",\\s*"))), "ids");
        });

        test("prompt: each vanilla faction has a description line, and nothing else has one", () -> {
            Set<String> described = new LinkedHashSet<>();
            Matcher m = Pattern.compile("(?m)^  ([a-z_]+): ").matcher(prompt());
            while (m.find()) described.add(m.group(1));
            same(FactionNames.ids(), described, "faction description lines");
        });

        test("prompt: every example answer is made of tags the game reads exactly as written", () -> {
            Matcher m = Pattern.compile("(?m)^Response: (.+)$").matcher(prompt());
            int examples = 0;
            List<String> problems = new ArrayList<>();
            while (m.find()) {
                examples++;
                String response = m.group(1).trim();
                if (response.equals(CommandBuilder.NONE)) continue;
                List<FleetCommand> commands = new CommandParser().parse(response);
                StringBuilder again = new StringBuilder();
                for (FleetCommand c : commands) {
                    again.append(c.toTag());
                    if (c.factionId() != null && !FactionNames.ids().contains(c.factionId())) {
                        problems.add(response + ": faction " + show(c.factionId()) + " is not a vanilla id");
                    }
                }
                // Anything that doesn't come back the same is extra text, a malformed tag or a size outside 1-300
                if (!again.toString().equals(response)) problems.add(response + " reads back as " + show(again.toString()));
            }
            check(examples >= 20, "only " + examples + " examples found; has the EXAMPLES format changed?");
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("prompt: its size scale is SizeWords' (every word in STEP 4 gives its tier)", () -> {
            Matcher m = Pattern.compile("(?m)^\\s+- (\\d+) for \\w+: (.+)$").matcher(prompt());
            int tiers = 0;
            List<String> problems = new ArrayList<>();
            while (m.find()) {
                tiers++;
                int points = Integer.parseInt(m.group(1));
                for (String word : m.group(2).split(",\\s*")) {
                    Integer tier = SizeWords.tier(word);
                    if (tier == null || tier != points) problems.add(show(word) + " is " + points + " in the prompt, " + tier + " in SizeWords");
                }
            }
            same(6, tiers, "size tiers found in the prompt");
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("prompt: its range and default size are FleetCommand's", () -> {
            Matcher range = Pattern.compile("fleet_points is a whole number from (\\d+) to (\\d+)").matcher(prompt());
            check(range.find(), "no 'fleet_points is a whole number from X to Y' in the prompt");
            same(FleetCommand.MIN_POINTS, Integer.parseInt(range.group(1)), "smallest");
            same(FleetCommand.MAX_POINTS, Integer.parseInt(range.group(2)), "largest");
            Matcher dflt = Pattern.compile("d\\) No number and no size word for that fleet: (\\d+)\\.").matcher(prompt());
            check(dflt.find(), "no 'd) No number and no size word for that fleet: N.' in the prompt");
            same(FleetCommand.DEFAULT_POINTS, Integer.parseInt(dflt.group(1)), "default");
        });

        // ------------------------------------------------------------------------------------------
        // The training data
        // ------------------------------------------------------------------------------------------

        test("training data: FACTIONS has exactly the vanilla ids", () -> {
            same(FactionNames.ids(), dict(training(), "FACTIONS").keySet(), "FACTIONS keys");
        });

        test("training data: every way it names a faction resolves to that faction", () -> {
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, List<String>> f : dict(training(), "FACTIONS").entrySet()) {
                for (String name : f.getValue()) {
                    String id = FactionNames.resolve(name);
                    if (!f.getKey().equals(id)) problems.add(show(name) + " -> " + id + ", should be " + f.getKey());
                }
            }
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("training data: the leaders and home worlds it labels as factions resolve to one", () -> {
            List<String> problems = new ArrayList<>();
            for (String list : new String[]{"LEADERS", "HOMES"}) {
                for (String name : list(training(), list)) {
                    if (FactionNames.resolve(name) == null) problems.add(list + ": " + show(name) + " is no faction");
                }
            }
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("training data: the made-up factions resolve to none, so they give <NONE>", () -> {
            List<String> problems = new ArrayList<>();
            for (String name : list(training(), "UNKNOWN_FACTIONS")) {
                String id = FactionNames.resolve(name);
                if (id != null) problems.add(show(name) + " -> " + id);
            }
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("training data: every place it uses is a place PlaceNames knows", () -> {
            List<String> problems = new ArrayList<>();
            for (String place : list(training(), "PLACES")) {
                if (!PlaceNames.isKnown(place)) problems.add(show(place) + " is not in PlaceNames.KNOWN");
            }
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("training data: it labels exactly the entity types CommandBuilder reads", () -> {
            Set<String> labelled = new TreeSet<>();
            Matcher m = Pattern.compile("label\\(.*,\\s*\"(\\w+)\",\\s*g\\)").matcher(training());
            while (m.find()) labelled.add(m.group(1));
            check(!labelled.isEmpty(), "no label(..., \"type\", g) calls found; has generate_nlu.py changed?");
            same(new TreeSet<>(CommandBuilder.ENTITY_TYPES), labelled, "entity types");
        });

        test("training data: every intent is one CommandBuilder knows", () -> {
            Set<String> known = new LinkedHashSet<>(Arrays.asList(CommandBuilder.SPAWN_INTENT, CommandBuilder.TRADE_INTENT,
                    CommandBuilder.HELP_INTENT, CommandBuilder.CANCEL_INTENT));
            known.addAll(NOT_REQUESTS);
            Set<String> intents = intents(training());
            check(intents.size() >= 5, "only " + intents + " found; has main() in generate_nlu.py changed?");
            List<String> problems = new ArrayList<>();
            for (String intent : intents) {
                for (String part : intent.split("\\+")) {
                    if (!known.contains(part)) problems.add(intent + ": " + part + " is not one of " + known);
                }
            }
            check(problems.isEmpty(), String.join("\n", problems));
            check(intents.containsAll(known), "intents never trained: " + minus(known, intents));
        });

        test("training data: every size, multiplier, offset, count and reference word it labels is understood", () -> {
            JSONArray slots = vocabulary().getJSONArray("slots");
            check(slots.length() >= 15, "only " + slots.length() + " slots read from slot(); has it changed?");
            List<String> problems = new ArrayList<>();
            int words = 0;
            for (int i = 0; i < slots.length(); i++) {
                JSONObject s = slots.getJSONObject(i);
                if (s.isNull("words")) continue; // made by code (points_text, a random number): checked below
                String entity = s.getString("entity");
                JSONArray list = s.getJSONArray("words");
                for (int w = 0; w < list.length(); w++) {
                    String word = list.getString(w);
                    words++;
                    String why = understood(entity, word);
                    if (why != null) problems.add("{" + s.getString("slot") + "} labels " + show(word) + " as " + entity + ": " + why);
                }
            }
            check(words >= 50, "only " + words + " words checked");
            check(problems.isEmpty(), String.join("\n", problems));
        });

        test("training data: every size in points it writes is a number NumberWords reads", () -> {
            JSONArray points = vocabulary().getJSONArray("points");
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < points.length(); i++) {
                if (NumberWords.parse(points.getString(i)) == null) problems.add(show(points.getString(i)));
            }
            check(problems.isEmpty(), "not read as a number: " + problems);
        });

        test("training data: number_words(n) reads back as n, for 0 to 999", () -> {
            JSONArray numbers = vocabulary().getJSONArray("numbers");
            same(1000, numbers.length(), "numbers");
            List<String> problems = new ArrayList<>();
            for (int n = 0; n < numbers.length(); n++) {
                Double read = NumberWords.parse(numbers.getString(n));
                if (read == null || read != n) problems.add(show(numbers.getString(n)) + " -> " + read + ", not " + n);
            }
            check(problems.isEmpty(), problems.size() + " wrong:\n" + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
        });

        // ------------------------------------------------------------------------------------------
        // The typo distance
        // ------------------------------------------------------------------------------------------

        test("distance: FactionNames.distance gives the shared table's answers (rules.py is checked against it too)", () -> {
            List<String> problems = new ArrayList<>();
            int cases = 0;
            List<String> lines = Files.readAllLines(Paths.get(DISTANCE_CASES), StandardCharsets.UTF_8);
            for (int n = 0; n < lines.size(); n++) {
                String line = lines.get(n).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\s+");
                String a = f[0].equals("-") ? "" : f[0], b = f[1].equals("-") ? "" : f[1];
                int got = FactionNames.distance(a, b);
                cases++;
                if (got != Integer.parseInt(f[2])) {
                    problems.add("line " + (n + 1) + ": distance(" + show(a) + ", " + show(b) + ") is " + got + ", the table says " + f[2]);
                }
            }
            check(cases >= 10, "only " + cases + " cases in " + DISTANCE_CASES);
            check(problems.isEmpty(), String.join("\n", problems));
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    // ------------------------------------------------------------------------------------------
    // Reading the files
    // ------------------------------------------------------------------------------------------

    /** The shipped prompt as a game without mod factions gets it. */
    private static String prompt() throws Exception {
        return SystemPrompt.fill(read(SystemPrompt.FILE), GameNames.NONE);
    }

    private static String training() throws Exception {
        return read(TRAINING);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The entity types whose labelled words are checked by understood(); the others are checked elsewhere. */
    private static final Set<String> WORD_TYPES = new LinkedHashSet<>(Arrays.asList("size", "multiplier", "offset", "count",
            "reference"));
    private static final Set<String> CHECKED_ELSEWHERE = new LinkedHashSet<>(Arrays.asList("faction", "destination", "origin",
            "target", "points"));

    /** Why CommandBuilder can't read this labelled word, or null when it can. */
    private static String understood(String entity, String word) throws Exception {
        switch (entity) {
            case "size":
                return SizeWords.tier(word) != null ? null : "SizeWords.tier gives nothing";
            case "multiplier":
                return SizeWords.multiplier(word) != null ? null : "SizeWords.multiplier gives nothing";
            case "offset":
                return SizeWords.offset(word) != null ? null : "SizeWords.offset gives nothing";
            case "reference":
                return NumberWords.parse(word) != null || SizeWords.tier(word) != null ? null : "neither a number nor a size";
            case "count": {
                // Through CommandBuilder itself, where "a pair of" is read too: every count here is 2 or more
                JSONObject parse = new JSONObject().put("text", word + " pirate fleets")
                        .put("intent", new JSONObject().put("name", CommandBuilder.SPAWN_INTENT).put("confidence", 1.0))
                        .put("entities", new JSONArray()
                                .put(new JSONObject().put("entity", "count").put("value", word).put("start", 0).put("end", word.length()))
                                .put(new JSONObject().put("entity", "faction").put("value", "pirate")));
                int tags = new CommandBuilder(0.5).toTags(parse).split("<SPAWN:", -1).length - 1;
                return tags >= 2 ? null : "gives " + tags + " fleet(s)";
            }
            default:
                return CHECKED_ELSEWHERE.contains(entity) ? null
                        : "a new entity type: add a check for it here (known: " + WORD_TYPES + ", " + CHECKED_ELSEWHERE + ")";
        }
    }

    private static JSONObject vocabulary;

    /**
     * What generate_nlu.py labels, as Python itself sees it: each {slot} of slot() with its entity type and the
     * words it picks from (null when code makes the text), 300 points_text() samples and number_words(0..999).
     */
    private static JSONObject vocabulary() throws Exception {
        if (vocabulary == null) vocabulary = (JSONObject) Python.json(String.join("\n",
                "import ast, json, random, sys",
                "sys.path.insert(0, 'nlp/rasa')",
                "import generate_nlu as g",
                "tree = ast.parse(open('nlp/rasa/generate_nlu.py', encoding='utf-8').read())",
                "slot = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'slot')",
                "slots = []",
                "for node in ast.walk(slot):",
                "    t = node.test if isinstance(node, ast.If) else None",
                "    if not (isinstance(t, ast.Compare) and isinstance(t.left, ast.Name) and t.left.id == 'name'):",
                "        continue",
                "    for st in node.body:",
                "        v = st.value if isinstance(st, ast.Return) else None",
                "        if not (isinstance(v, ast.Call) and getattr(v.func, 'id', '') == 'label'):",
                "            continue",
                "        arg, words = v.args[0], None",
                "        if isinstance(arg, ast.Call) and getattr(arg.func, 'attr', '') == 'choice':",
                "            inner = arg.args[0]",
                "            if isinstance(inner, ast.List):",
                "                words = [ast.literal_eval(e) for e in inner.elts]",
                "            elif isinstance(inner, ast.Name):",
                "                words = list(getattr(g, inner.id))",
                "        slots.append({'slot': t.comparators[0].value, 'entity': v.args[1].value, 'words': words})",
                "g.RNG = random.Random(7)",
                "print(json.dumps({'slots': slots, 'points': [g.points_text() for _ in range(300)],",
                "                  'numbers': [g.number_words(n) for n in range(1000)]}))"));
        return vocabulary;
    }

    /** A Python string in double or single quotes. */
    private static final Pattern PY_STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'");

    private static List<String> strings(String python) {
        List<String> out = new ArrayList<>();
        Matcher m = PY_STRING.matcher(python);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    /** The strings of a top-level "NAME = [ ... ]" in Python source. */
    private static List<String> list(String python, String name) {
        Matcher m = Pattern.compile("(?ms)^" + name + " = \\[(.*?)\\]\\s*$").matcher(python);
        check(m.find(), "no list " + name + " = [...] in " + TRAINING);
        return strings(m.group(1));
    }

    /** A top-level "NAME = { "key": [ "...", ... ], ... }" in Python source, closed by a "}" at the start of a line. */
    private static Map<String, List<String>> dict(String python, String name) {
        Matcher block = Pattern.compile("(?ms)^" + name + " = \\{(.*?)^\\}").matcher(python);
        check(block.find(), "no dict " + name + " = {...} in " + TRAINING);
        Map<String, List<String>> out = new LinkedHashMap<>();
        Matcher entry = Pattern.compile("(?s)\"(\\w+)\":\\s*\\[(.*?)\\]").matcher(block.group(1));
        while (entry.find()) out.put(entry.group(1), strings(entry.group(2)));
        return out;
    }

    /** The intents main() writes ("name": build(...)) and the MIXED ones ("a+b": [...]). */
    private static Set<String> intents(String python) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([a-z_]+)\": build\\(").matcher(python);
        while (m.find()) out.add(m.group(1));
        Matcher mixed = Pattern.compile("(?ms)^MIXED = \\{(.*?)^\\}").matcher(python);
        check(mixed.find(), "no MIXED = {...} in " + TRAINING);
        Matcher key = Pattern.compile("(?m)^    \"([a-z_+]+)\": \\[").matcher(mixed.group(1));
        while (key.find()) out.add(key.group(1));
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }
}
