package KirizsKeve.MoveByText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Offline tests for RasaCommandBuilder and NumberWords: Rasa answers written by hand -> the command tags the game
 * runs. No Rasa server is needed. To see how well the trained model reads real sentences, set "provider": "rasa"
 * in llmdata.json and run LlmPromptTest.
 *
 * Run main() from IntelliJ. Pass a word to run only the tests whose name contains it. Exits with 1 on any failure.
 */
public class RasaCommandBuilderTest {

    private static final double MIN = 0.5;

    private interface Test {
        void run() throws Exception;
    }

    private static final Map<String, Test> TESTS = new LinkedHashMap<>();

    private static void test(String name, Test body) {
        TESTS.put(name, body);
    }

    // ------------------------------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------------------------------

    static {
        test("intent: out_of_scope, nlu_fallback and unknown intents -> NONE, even with entities", () -> {
            for (String intent : new String[]{"out_of_scope", "nlu_fallback", "greet", ""}) {
                same("<NONE>", tags(parse(intent, 0.99, e("faction", "pirates"), e("size", "huge"))), "intent " + show(intent));
            }
        });

        test("intent: below minConfidence -> NONE; at it -> a tag", () -> {
            same("<NONE>", tags(parse("spawn_fleet", 0.49, e("faction", "pirates"))), "confidence 0.49");
            same("<SPAWN:pirates:100>", tags(parse("spawn_fleet", 0.5, e("faction", "pirates"))), "confidence 0.5");
        });

        test("intent: a missing intent object -> NONE", () -> {
            same("<NONE>", RasaCommandBuilder.toTags(new JSONObject().put("text", "hi"), MIN), "reply");
        });
    }

    // ------------------------------------------------------------------------------------------
    // SPAWN, TRADE and HELP
    // ------------------------------------------------------------------------------------------

    static {
        test("spawn: faction and size", () -> {
            same("<SPAWN:pirates:40>", tags(parse("spawn_fleet", 0.9, e("size", "small"), e("faction", "pirate"))), "reply");
        });

        test("spawn: no size -> 100", () -> {
            same("<SPAWN:hegemony:100>", tags(parse("spawn_fleet", 0.9, e("faction", "hegemony"))), "reply");
        });

        test("spawn: no faction, or one the game doesn't have -> NONE", () -> {
            same("<NONE>", tags(parse("spawn_fleet", 0.9, e("size", "huge"))), "no faction");
            for (String f : new String[]{"Klingon", "Interstellar Imperium", "Galactic Federation", "space whales", "templars"}) {
                same("<NONE>", tags(parse("spawn_fleet", 0.9, e("faction", f), e("size", "big"))), "faction " + show(f));
            }
        });

        test("trade: destination, no faction -> independent; origin when given", () -> {
            same("<TRADE:independent:100:Jangala>", tags(parse("trade_convoy", 0.9, e("destination", "Jangala"))), "no origin");
            same("<TRADE:tritachyon:150:Kazeron:Culann>", tags(parse("trade_convoy", 0.9,
                    e("size", "big"), e("faction", "tri-tachyon"), e("origin", "Culann"), e("destination", "Kazeron"))), "with origin");
        });

        test("trade: an origin equal to the destination is left out", () -> {
            same("<TRADE:independent:100:Agreus>", tags(parse("trade_convoy", 0.9,
                    e("origin", "agreus"), e("destination", "Agreus"))), "reply (compared ignoring case)");
        });

        test("trade or help without a place -> NONE", () -> {
            same("<NONE>", tags(parse("trade_convoy", 0.9, e("faction", "independent"), e("size", "small"))), "trade");
            same("<NONE>", tags(parse("request_help", 0.9, e("faction", "pirates"))), "help");
        });

        test("help: no sender -> nearby; a named sender is kept", () -> {
            same("<HELP:nearby:100:Jangala>", tags(parse("request_help", 0.9, e("target", "Jangala"))), "nearby");
            same("<HELP:persean:150:Kazeron>", tags(parse("request_help", 0.9,
                    e("faction", "the League"), e("size", "big"), e("target", "Kazeron"))), "sender");
        });

        test("kind follows the entities: a destination is TRADE, a target is HELP, whatever the intent", () -> {
            same("<TRADE:independent:100:Askonia>", tags(parse("spawn_fleet", 0.9, e("destination", "Askonia"))), "destination");
            same("<HELP:nearby:100:Asharu>", tags(parse("spawn_fleet", 0.9, e("target", "Asharu"))), "target");
        });

        test("place names are cleaned up for the game", () -> {
            String[][] cases = {
                    {"the Askonia system", "Askonia"}, {"Jangala,", "Jangala"}, {"Kanta's Den", "Kanta's Den"},
                    {"Port: Tse>", "Port Tse"}, {"Eochu  Bres", "Eochu Bres"}, {"Corvus star system", "Corvus"},
            };
            for (String[] c : cases) {
                same("<TRADE:independent:100:" + c[1] + ">", tags(parse("trade_convoy", 0.9, e("destination", c[0]))), "place " + show(c[0]));
            }
        });

        test("places: lowercase words that aren't known places are rejected, known ones accepted in any case", () -> {
            same("<NONE>", tags(parse("request_help", 0.9, e("target", "seizures"))), "seizures");
            same("<TRADE:independent:100:askonia>", tags(parse("trade_convoy", 0.9, e("destination", "askonia"))), "askonia");
            same("<TRADE:independent:100:Nowhere Station>", tags(parse("trade_convoy", 0.9, e("destination", "Nowhere Station"))), "capitalized");
        });

        test("an entity without a value uses its span of the text", () -> {
            JSONObject p = new JSONObject().put("text", "send a convoy to Jangala")
                    .put("intent", new JSONObject().put("name", "trade_convoy").put("confidence", 0.9))
                    .put("entities", new JSONArray().put(new JSONObject().put("entity", "destination").put("start", 17).put("end", 24)));
            same("<TRADE:independent:100:Jangala>", RasaCommandBuilder.toTags(p, MIN), "reply");
        });

        test("entity types the builder doesn't know are ignored", () -> {
            same("<SPAWN:pirates:100>", tags(parse("spawn_fleet", 0.9, e("faction", "pirates"), e("mood", "angry"))), "reply");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Faction names
    // ------------------------------------------------------------------------------------------

    static {
        test("factions: names, nicknames, leaders, units and home worlds", () -> {
            String[][] cases = {
                    {"pirates", "pirates"}, {"Kanta's raiders", "pirates"}, {"Lion's Guard", "sindrian_diktat"},
                    {"Philip Andrada", "sindrian_diktat"}, {"the Askonian navy", "sindrian_diktat"}, {"pathers", "luddic_path"},
                    {"luddic", "luddic_path"}, {"Luddic Church", "luddic_church"}, {"Knights of Ludd", "luddic_church"},
                    {"the Persean League", "persean"}, {"Tri-Tachyon", "tritachyon"}, {"TT", "tritachyon"},
                    {"XIV Battlegroup", "hegemony"}, {"bounty hunters", "independent"}, {"mercenaries", "independent"},
                    {"remnant drones", "remnants"}, {"derelict drones", "derelict"}, {"a Remnant ordo", "remnants"},
                    {"Omega", "omega"}, {"thinking machines", "remnants"},
            };
            for (String[] c : cases) same(c[1], RasaCommandBuilder.FactionNames.resolve(c[0]), "faction " + show(c[0]));
        });

        test("factions: typos in long names are forgiven, short made-up names are not", () -> {
            same("tritachyon", RasaCommandBuilder.FactionNames.resolve("tritachion"), "tritachion");
            same("hegemony", RasaCommandBuilder.FactionNames.resolve("hegemonny"), "hegemonny");
            same("pirates", RasaCommandBuilder.FactionNames.resolve("pirrates"), "pirrates");
            for (String f : new String[]{"Klingon", "Borg", "Zerg", "Empire", "Covenant", "Shadowyards"}) {
                same(null, RasaCommandBuilder.FactionNames.resolve(f), "faction " + show(f));
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Sizes
    // ------------------------------------------------------------------------------------------

    static {
        test("size words: the six tiers of the system prompt", () -> {
            String[][] cases = {
                    {"tiny", "15"}, {"little", "15"}, {"a lone", "15"}, {"small", "40"}, {"a few", "40"}, {"a couple of", "40"},
                    {"medium", "100"}, {"standard", "100"}, {"big", "150"}, {"large", "150"}, {"strong", "150"},
                    {"huge", "225"}, {"massive", "225"}, {"very large", "225"}, {"humongous", "300"}, {"armada", "300"},
                    {"enormous", "300"}, {"the biggest possible", "300"},
            };
            for (String[] c : cases) {
                same("<SPAWN:pirates:" + c[1] + ">", tags(parse("spawn_fleet", 0.9, e("size", c[0]), e("faction", "pirates"))), "size " + show(c[0]));
            }
        });

        test("points: numbers as players write them, clamped to 1-300", () -> {
            String[][] cases = {
                    {"120", "120"}, {"1,000", "300"}, {"9999999999999", "300"}, {"-50", "1"}, {"0", "1"}, {"150.6", "151"},
                    {"ninety-five", "95"}, {"two hundred", "200"}, {"a hundred and twenty-five", "125"},
            };
            for (String[] c : cases) {
                same("<SPAWN:hegemony:" + c[1] + ">", tags(parse("spawn_fleet", 0.9, e("faction", "hegemony"), e("points", c[0]))), "points " + show(c[0]));
            }
        });

        test("points win over a size word; two numbers are a range and give its middle", () -> {
            same("<SPAWN:pirates:250>", tags(parse("spawn_fleet", 0.9, e("size", "tiny"), e("points", "250"), e("faction", "pirates"))), "points + size");
            same("<SPAWN:luddic_path:100>", tags(parse("spawn_fleet", 0.9, e("faction", "luddic path"), e("points", "80"), e("points", "120"))), "range");
        });

        test("multipliers on a size word or a number", () -> {
            Object[][] cases = {
                    {"twice", e("size", "small"), "80"}, {"half", e("size", "huge"), "113"}, {"a third of", e("size", "the biggest possible"), "100"},
                    {"three times", e("size", "tiny"), "45"}, {"50 percent bigger", e("points", "120"), "180"},
                    {"two thirds of", e("points", "180"), "120"}, {"a quarter of", e("size", "maximum"), "75"}, {"ten times", e("size", "tiny"), "150"},
            };
            for (Object[] c : cases) {
                same("<SPAWN:remnants:" + c[2] + ">", tags(parse("spawn_fleet", 0.9, e("faction", "remnants"),
                        e("multiplier", (String) c[0]), (String[]) c[1])), "multiplier " + show(c[0]));
            }
        });

        test("offsets: plus and minus a number of points", () -> {
            same("<SPAWN:luddic_church:80>", tags(parse("spawn_fleet", 0.9, e("faction", "luddic church"),
                    e("offset", "20 points smaller"), e("size", "medium"))), "smaller");
            same("<SPAWN:remnants:150>", tags(parse("spawn_fleet", 0.9, e("faction", "remnants"),
                    e("size", "standard"), e("offset", "another 50 points"))), "another");
            same("<SPAWN:remnants:103>", tags(parse("spawn_fleet", 0.9, e("faction", "remnants"),
                    e("multiplier", "half"), e("size", "huge"), e("offset", "minus 10"))), "multiplier then offset");
        });

        test("reference: the player's own fleet as the base", () -> {
            same("<SPAWN:pirates:120>", tags(parse("spawn_fleet", 0.9, e("reference", "180"),
                    e("faction", "pirate"), e("multiplier", "two thirds of"))), "number");
            same("<SPAWN:pirates:15>", tags(parse("spawn_fleet", 0.9, e("reference", "tiny"), e("faction", "pirate"))), "size word");
        });

        test("NumberWords: digits first, then words; null when there is no number", () -> {
            same(225.0, NumberWords.parse("about 225 points"), "digits");
            same(1000.0, NumberWords.parse("1,000"), "comma");
            same(95.0, NumberWords.parse("roughly ninety-five points"), "words");
            same(300.0, NumberWords.parse("three hundred and something"), "stops at the first other word");
            same(2000.0, NumberWords.parse("two thousand"), "thousand");
            same(null, NumberWords.parse("a big fleet"), "no number");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Several fleets
    // ------------------------------------------------------------------------------------------

    static {
        test("count: one fleet repeated, capped at 10", () -> {
            same("<SPAWN:pirates:40><SPAWN:pirates:40>", tags(parse("spawn_fleet", 0.9, e("count", "two"), e("size", "small"), e("faction", "pirate"))), "two");
            same("<SPAWN:remnants:15><SPAWN:remnants:15><SPAWN:remnants:15>", tags(parse("spawn_fleet", 0.9, e("count", "3"), e("size", "tiny"), e("faction", "remnant"))), "3");
            same("<SPAWN:pirates:100><SPAWN:pirates:100>", tags(parse("spawn_fleet", 0.9, e("count", "a pair of"), e("faction", "pirates"))), "a pair");
            same(10, tags(parse("spawn_fleet", 0.9, e("count", "50"), e("faction", "pirates"))).split("<").length - 1, "50 capped");
        });

        test("groups: one tag per group, in order", () -> {
            same("<SPAWN:hegemony:225><SPAWN:persean:40>", tags(parse("spawn_fleet", 0.9,
                    e("size", "huge", "1"), e("faction", "hegemony", "1"), e("size", "small", "2"), e("faction", "persean", "2"))), "reply");
        });

        test("groups: entities without a group apply to every fleet that lacks its own", () -> {
            same("<TRADE:independent:40:Jangala:Asharu><TRADE:independent:40:Jangala:Askonia>", tags(parse("trade_convoy", 0.9,
                    e("count", "two"), e("destination", "Jangala"), e("origin", "Asharu", "1"), e("origin", "Askonia", "2"),
                    e("faction", "independent"), e("size", "small"))), "shared destination, faction and size; count ignored");
            same("<SPAWN:pirates:40><SPAWN:remnants:150>", tags(parse("spawn_fleet", 0.9,
                    e("faction", "pirates", "1"), e("faction", "remnants", "2"), e("size", "small"), e("size", "big", "2"))), "own size wins");
        });

        test("multi-intents: SPAWN, TRADE and HELP in one message", () -> {
            same("<SPAWN:pirates:40><TRADE:independent:100:Askonia>", tags(parse("spawn_fleet+trade_convoy", 0.9,
                    e("size", "small", "1"), e("faction", "pirates", "1"), e("destination", "Askonia", "2"))), "spawn + trade");
            same("<HELP:hegemony:150:Jangala><SPAWN:persean:40>", tags(parse("spawn_fleet+request_help", 0.9,
                    e("faction", "hegemony", "1"), e("size", "big", "1"), e("target", "Jangala", "1"),
                    e("size", "small", "2"), e("faction", "persean", "2"))), "help + spawn");
        });

        test("groups: an unknown faction drops only its own fleet", () -> {
            same("<SPAWN:pirates:150>", tags(parse("spawn_fleet", 0.9,
                    e("faction", "Kanta's pirates", "1"), e("size", "big", "1"), e("faction", "Templars", "2"), e("size", "huge", "2"))), "reply");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Messages read one sentence at a time
    // ------------------------------------------------------------------------------------------

    static {
        test("sentences: stories and questions around the request are skipped", () -> {
            same("<SPAWN:tritachyon:15>", tags(
                    parse("out_of_scope", 0.99, e("faction", "hegemony")),
                    parse("spawn_fleet", 0.95, e("size", "tiny"), e("faction", "tri-tachyon")),
                    parse("out_of_scope", 0.9)), "reply");
        });

        test("sentences: no request at all -> NONE", () -> {
            same("<NONE>", tags(parse("out_of_scope", 0.99), parse("nlu_fallback", 0.4)), "reply");
        });

        test("sentences: a cancel drops the requests before it, not the ones after", () -> {
            same("<NONE>", tags(parse("spawn_fleet", 0.9, e("faction", "diktat"), e("size", "huge")), parse("cancel", 0.9)), "cancel last");
            same("<TRADE:independent:100:Kazeron:Jangala>", tags(
                    parse("trade_convoy", 0.9, e("origin", "Jangala"), e("destination", "Sindria")),
                    parse("cancel", 0.9),
                    parse("trade_convoy", 0.9, e("origin", "Jangala"), e("destination", "Kazeron"))), "request after cancel");
        });

        test("sentences: the same request refined - the latest sentence wins per detail", () -> {
            same("<SPAWN:pirates:40>", tags(
                    parse("spawn_fleet", 0.9, e("size", "big"), e("faction", "pirate")),
                    parse("out_of_scope", 0.9),
                    parse("spawn_fleet", 0.9, e("size", "small"))), "size corrected, faction kept");
            same("<SPAWN:sindrian_diktat:100>", tags(
                    parse("spawn_fleet", 0.9, e("faction", "pirates"), e("points", "80"), e("points", "120")),
                    parse("spawn_fleet", 0.9, e("faction", "diktat"))), "faction corrected, range kept");
        });

        test("sentences: different requests each give their own tags", () -> {
            same("<SPAWN:tritachyon:150><TRADE:tritachyon:100:Culann>", tags(
                    parse("spawn_fleet", 0.9, e("size", "big"), e("faction", "tri-tachyon")),
                    parse("trade_convoy", 0.9, e("faction", "tri-tachyon"), e("destination", "Culann"))), "spawn then trade");
        });

        test("sentences: a sentence with fleet groups stands on its own", () -> {
            same("<SPAWN:hegemony:225><SPAWN:persean:225><SPAWN:pirates:40>", tags(
                    parse("spawn_fleet", 0.9, e("faction", "hegemony", "1"), e("faction", "persean", "2"), e("size", "huge")),
                    parse("spawn_fleet", 0.9, e("size", "small"), e("faction", "pirates"))), "reply");
        });

        test("sentences: low confidence sentences are skipped", () -> {
            same("<SPAWN:pirates:100>", tags(
                    parse("spawn_fleet", 0.9, e("faction", "pirates")),
                    parse("cancel", 0.3)), "a weak cancel doesn't cancel");
        });

        test("count inside a group: a huge omega fleet and two little derelict fleets", () -> {
            same("<SPAWN:omega:225><SPAWN:derelict:15><SPAWN:derelict:15>", tags(parse("spawn_fleet", 0.9,
                    e("size", "huge", "1"), e("faction", "omega", "1"),
                    e("count", "two", "2"), e("size", "little", "2"), e("faction", "derelict", "2"))), "reply");
        });

        test("RasaClient.sentences: split after . ! ? ; and at line breaks, numbers stay whole", () -> {
            same(java.util.Arrays.asList("Long day.", "Spawn a 150.6 point fleet!", "Really?", "Yes;", "now", "go"),
                    RasaClient.sentences("Long day.  Spawn a 150.6 point fleet! Really? Yes; now\ngo"), "pieces");
            same(java.util.Collections.singletonList(""), RasaClient.sentences("   "), "empty message");
            same(RasaClient.MAX_SENTENCES, RasaClient.sentences("a. ".repeat(50)).size(), "capped");
        });
    }

    // ------------------------------------------------------------------------------------------
    // Runner and helpers
    // ------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : null;
        int run = 0, failed = 0;
        for (Map.Entry<String, Test> t : TESTS.entrySet()) {
            if (filter != null && !t.getKey().contains(filter)) continue;
            run++;
            try {
                t.getValue().run();
                System.out.println("PASS   " + t.getKey());
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL   " + t.getKey());
                String detail = e instanceof AssertionError ? e.getMessage() : "unexpected " + e;
                System.out.println("       " + String.valueOf(detail).replace("\n", "\n       "));
            }
        }
        System.out.println();
        System.out.println((run - failed) + "/" + run + " passed");
        System.exit(failed == 0 && run > 0 ? 0 : 1);
    }

    /** An entity: {type, text} or {type, text, group}. */
    private static String[] e(String... fields) {
        return fields;
    }

    /** A /model/parse answer; the entities get increasing start offsets in the order given. */
    private static JSONObject parse(String intent, double confidence, String[]... entities) throws Exception {
        JSONArray list = new JSONArray();
        StringBuilder text = new StringBuilder();
        for (String[] e : entities) {
            int start = text.length();
            text.append(e[1]).append(' ');
            JSONObject o = new JSONObject().put("entity", e[0]).put("value", e[1]).put("start", start).put("end", start + e[1].length());
            if (e.length > 2) o.put("group", e[2]);
            list.put(o);
        }
        return new JSONObject().put("text", text.toString().trim())
                .put("intent", new JSONObject().put("name", intent).put("confidence", confidence))
                .put("entities", list);
    }

    private static String tags(JSONObject parse) throws Exception {
        return RasaCommandBuilder.toTags(parse, MIN);
    }

    /** A message read as several sentences. */
    private static String tags(JSONObject first, JSONObject... more) throws Exception {
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        list.add(first);
        list.addAll(java.util.Arrays.asList(more));
        return RasaCommandBuilder.toTags(list, MIN);
    }

    private static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\nexpected: " + show(expected) + "\nactual:   " + show(actual));
        }
    }

    private static String show(Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }
}
