package KirizsKeve.MoveByText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what Rasa found in a message (an intent plus labelled pieces of text) into the same command tags the LLMs
 * write, e.g. <SPAWN:pirates:40>, so the game code and the tests work the same for every provider.
 *
 * Rasa only says what is there: "pirate" is the faction, "small" is the size, "Jangala" is the destination. The
 * rules live here: which faction id a name means, what size a word or a bit of math gives, what the defaults are,
 * and when a request can't be carried out (an unknown faction, help without a place).
 *
 * Several fleets come as Rasa entity groups ("1", "2", ...). An entity without a group applies to every fleet
 * that doesn't have its own, so "two convoys to Jangala, one from Asharu and one from Askonia" works.
 */
final class RasaCommandBuilder {

    static final String NONE = "<NONE>";

    static final String SPAWN_INTENT = "spawn_fleet";
    static final String TRADE_INTENT = "trade_convoy";
    static final String HELP_INTENT = "request_help";
    /** "never mind", "cancel that": drops every request in the sentences before it. */
    static final String CANCEL_INTENT = "cancel";

    static final int MIN_POINTS = 1;
    static final int MAX_POINTS = 300;
    static final int DEFAULT_POINTS = 100;
    /** More copies of one fleet than this is almost certainly a misread number. */
    static final int MAX_COUNT = 10;

    private static final List<String> ENTITY_TYPES = Arrays.asList(
            "faction", "size", "points", "destination", "origin", "target", "multiplier", "offset", "reference", "count");

    private RasaCommandBuilder() {
    }

    /**
     * The tags for a message that was read one sentence at a time (one /model/parse answer per sentence, in order).
     * Rasa is trained on sentence-sized examples, so long messages are split; this puts the pieces back together:
     * - sentences that aren't requests (stories, opinions, questions) are skipped;
     * - a "cancel" sentence drops the requests before it;
     * - several sentences with the same request are one request being refined ("spawn a big pirate fleet.
     *   ... make it a small one"), so for each kind of detail the latest sentence that mentions it wins;
     * - sentences with different requests, or with several fleets of their own, each give their own tags.
     */
    static String toTags(List<JSONObject> sentences, double minConfidence) throws Exception {
        List<JSONObject> requests = new ArrayList<JSONObject>();
        for (JSONObject parse : sentences) {
            JSONObject intent = parse.optJSONObject("intent");
            String name = intent == null ? "" : intent.optString("name", "");
            if (intent == null || intent.optDouble("confidence", 0) < minConfidence) continue;
            if (name.equals(CANCEL_INTENT)) {
                requests.clear();
            } else if (isRequest(name)) {
                requests.add(parse);
            }
        }
        if (requests.isEmpty()) return NONE;
        if (requests.size() == 1) return toTags(requests.get(0), minConfidence);

        boolean sameRequest = true;
        String first = requests.get(0).getJSONObject("intent").optString("name");
        for (JSONObject r : requests) {
            if (!r.getJSONObject("intent").optString("name").equals(first) || hasGroups(r)) sameRequest = false;
        }
        if (sameRequest) return toTags(merge(requests), minConfidence);

        StringBuilder tags = new StringBuilder();
        for (JSONObject r : requests) {
            String t = toTags(r, minConfidence);
            if (!t.equals(NONE)) tags.append(t);
        }
        return tags.length() == 0 ? NONE : tags.toString();
    }

    private static boolean isRequest(String intentName) {
        for (String part : intentName.split("\\+")) {
            if (part.equals(SPAWN_INTENT) || part.equals(TRADE_INTENT) || part.equals(HELP_INTENT)) return true;
        }
        return false;
    }

    private static boolean hasGroups(JSONObject parse) {
        JSONArray entities = parse.optJSONArray("entities");
        if (entities == null) return false;
        for (int i = 0; i < entities.length(); i++) {
            JSONObject e = entities.optJSONObject(i);
            if (e != null && !e.optString("group", "").trim().isEmpty()) return true;
        }
        return false;
    }

    /** One parse from several sentences about the same request: per entity type, the latest sentence's values. */
    private static JSONObject merge(List<JSONObject> requests) throws Exception {
        Map<String, JSONArray> latest = new LinkedHashMap<String, JSONArray>();
        for (int s = 0; s < requests.size(); s++) {
            JSONObject parse = requests.get(s);
            String text = parse.optString("text", "");
            Map<String, JSONArray> mine = new LinkedHashMap<String, JSONArray>();
            JSONArray entities = parse.optJSONArray("entities");
            for (int i = 0; entities != null && i < entities.length(); i++) {
                JSONObject e = entities.getJSONObject(i);
                String value = valueOf(e, text);
                if (value == null) continue;
                JSONObject copy = new JSONObject()
                        .put("entity", e.optString("entity"))
                        .put("value", value)
                        .put("start", s * 100000 + e.optInt("start", 0)); // keeps sentence order when sorted
                JSONArray list = mine.get(copy.getString("entity"));
                if (list == null) mine.put(copy.getString("entity"), list = new JSONArray());
                list.put(copy);
            }
            latest.putAll(mine); // a later sentence replaces everything an earlier one said about that detail
        }
        JSONArray all = new JSONArray();
        for (JSONArray list : latest.values()) {
            for (int i = 0; i < list.length(); i++) all.put(list.get(i));
        }
        return new JSONObject()
                .put("text", "")
                .put("intent", requests.get(requests.size() - 1).getJSONObject("intent"))
                .put("entities", all);
    }

    /** The tags for one /model/parse answer; NONE when it isn't a request the game can carry out. */
    static String toTags(JSONObject parse, double minConfidence) throws Exception {
        JSONObject intent = parse.optJSONObject("intent");
        String name = intent == null ? "" : intent.optString("name", "");
        double confidence = intent == null ? 0 : intent.optDouble("confidence", 0);
        if (name.isEmpty() || confidence < minConfidence) return NONE;

        Set<String> intents = new HashSet<String>(Arrays.asList(name.split("\\+")));
        boolean wantsSpawn = intents.contains(SPAWN_INTENT);
        if (!wantsSpawn && !intents.contains(TRADE_INTENT) && !intents.contains(HELP_INTENT)) {
            return NONE; // out_of_scope, nlu_fallback or anything else
        }

        List<Fleet> fleets = fleets(parse.optJSONArray("entities"), parse.optString("text", ""));
        StringBuilder tags = new StringBuilder();
        for (Fleet fleet : fleets) {
            String tag = fleet.tag(wantsSpawn);
            if (tag == null) continue;
            // A shared count only counts for a single fleet (see inherit); a group can have its own: "and two little ones"
            for (int i = 0; i < fleet.count(); i++) tags.append(tag);
        }
        return tags.length() == 0 ? NONE : tags.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Grouping the entities into fleets
    // ------------------------------------------------------------------------------------------

    private static List<Fleet> fleets(JSONArray entities, String text) throws Exception {
        List<JSONObject> list = new ArrayList<JSONObject>();
        if (entities != null) {
            for (int i = 0; i < entities.length(); i++) list.add(entities.getJSONObject(i));
        }
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return a.optInt("start", 0) - b.optInt("start", 0);
            }
        });

        Fleet shared = new Fleet();
        Map<String, Fleet> groups = new LinkedHashMap<String, Fleet>();
        for (JSONObject e : list) {
            String type = e.optString("entity", "");
            if (!ENTITY_TYPES.contains(type)) continue;
            String value = valueOf(e, text);
            if (value == null) continue;
            String group = e.optString("group", "").trim();
            Fleet target = shared;
            if (!group.isEmpty()) {
                target = groups.get(group);
                if (target == null) {
                    target = new Fleet();
                    groups.put(group, target);
                }
            }
            target.add(type, value);
        }

        if (groups.isEmpty()) return Collections.singletonList(shared);
        List<Fleet> result = new ArrayList<Fleet>(groups.values());
        for (Fleet fleet : result) fleet.inherit(shared);
        return result;
    }

    /** The entity's text: Rasa's value, or the span of the message it covers. */
    private static String valueOf(JSONObject e, String text) {
        Object value = e.opt("value");
        String s = value == null ? null : String.valueOf(value);
        if ((s == null || s.trim().isEmpty()) && e.has("start") && e.has("end")) {
            int start = e.optInt("start"), end = e.optInt("end");
            if (start >= 0 && end <= text.length() && start < end) s = text.substring(start, end);
        }
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** Everything said about one fleet: entity type -> its values in message order. */
    private static final class Fleet {
        final Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();

        void add(String type, String value) {
            List<String> list = values.get(type);
            if (list == null) {
                list = new ArrayList<String>();
                values.put(type, list);
            }
            list.add(value);
        }

        /** Takes the shared entities this fleet doesn't have its own of, except the count. */
        void inherit(Fleet shared) {
            for (Map.Entry<String, List<String>> e : shared.values.entrySet()) {
                if (e.getKey().equals("count")) continue; // "two convoys, one to X, one to Y" is already two groups
                if (!values.containsKey(e.getKey())) values.put(e.getKey(), new ArrayList<String>(e.getValue()));
            }
        }

        List<String> all(String type) {
            List<String> list = values.get(type);
            return list == null ? Collections.<String>emptyList() : list;
        }

        String first(String type) {
            List<String> list = all(type);
            return list.isEmpty() ? null : list.get(0);
        }

        int count() {
            String c = first("count");
            Double n = c == null ? null : NumberWords.parse(c);
            if (n == null && c != null && c.toLowerCase().matches(".*\\b(pair|couple)\\b.*")) n = 2.0;
            if (n == null || n < 1) return 1;
            return (int) Math.min(MAX_COUNT, Math.round(n));
        }

        /** The tag for this fleet, or null when it can't be carried out. */
        String tag(boolean wantsSpawn) {
            String destination = place(first("destination"));
            String target = place(first("target"));
            String kind = destination != null ? "TRADE" : target != null ? "HELP" : wantsSpawn ? "SPAWN" : null;
            if (kind == null) return null; // help or trade without a place

            String faction;
            if (values.containsKey("faction")) {
                faction = null;
                for (String name : all("faction")) {
                    faction = FactionNames.resolve(name);
                    if (faction != null) break;
                }
                if (faction == null) return null; // a faction that doesn't exist in the game
            } else {
                faction = kind.equals("TRADE") ? "independent" : kind.equals("HELP") ? "nearby" : null;
                if (faction == null) return null; // a fleet with no faction
            }

            int points = points();
            if (kind.equals("SPAWN")) return "<SPAWN:" + faction + ":" + points + ">";
            if (kind.equals("HELP")) return "<HELP:" + faction + ":" + points + ":" + target + ">";
            String origin = place(first("origin"));
            if (origin != null && origin.equalsIgnoreCase(destination)) origin = null;
            return "<TRADE:" + faction + ":" + points + ":" + destination + (origin != null ? ":" + origin : "") + ">";
        }

        /** Points first (two numbers are a range: its middle), then a size word, then the player's own fleet, then 100. */
        int points() {
            Double base = null;
            List<Double> numbers = new ArrayList<Double>();
            for (String p : all("points")) {
                Double n = NumberWords.parse(p);
                if (n != null) numbers.add(n);
            }
            if (numbers.size() >= 2) base = (numbers.get(0) + numbers.get(1)) / 2;
            else if (numbers.size() == 1) base = numbers.get(0);

            if (base == null && first("size") != null) {
                Integer tier = SizeWords.tier(first("size"));
                if (tier != null) base = tier.doubleValue();
            }
            if (base == null && first("reference") != null) {
                String ref = first("reference");
                Double n = NumberWords.parse(ref);
                if (n == null) {
                    Integer tier = SizeWords.tier(ref);
                    if (tier != null) n = tier.doubleValue();
                }
                base = n;
            }
            if (base == null) base = (double) DEFAULT_POINTS;

            double value = base;
            for (String m : all("multiplier")) {
                Double factor = SizeWords.multiplier(m);
                if (factor != null) value *= factor;
            }
            for (String o : all("offset")) {
                Double change = SizeWords.offset(o);
                if (change != null) value += change;
            }
            long rounded = Math.round(value);
            return (int) Math.max(MIN_POINTS, Math.min(MAX_POINTS, rounded));
        }
    }

    /**
     * A place name as the game's findPlace wants it: no ':' or '>', no "the" in front, no "system" after it.
     * Null for an all-lowercase word that isn't a known place: the model sometimes marks ordinary words
     * ("seizures", "drop") as places, while real names are capitalized or on the list.
     */
    static String place(String name) {
        if (name == null) return null;
        String s = name.replaceAll("[:<>]", " ").trim();
        s = s.replaceAll("^[\\s\"'(\\[]+|[\\s\"'.,!?;)\\]]+$", "");
        s = s.replaceAll("(?i)^the\\s+", "");
        s = s.replaceAll("(?i)\\s+(star\\s+)?system$", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        if (s.equals(s.toLowerCase()) && !KNOWN_PLACES.contains(s)) return null;
        return s;
    }

    /** Vanilla Starsector systems and markets, lowercase, for accepting names the player didn't capitalize. */
    private static final Set<String> KNOWN_PLACES = new HashSet<String>(Arrays.asList(
            "jangala", "asharu", "corvus", "askonia", "sindria", "volturn", "chicomoztoc", "aztlan", "kazeron",
            "fikenhild", "mazalot", "hybrasil", "culann", "eochu bres", "port tse", "eventide", "ilm", "tigra city",
            "agreus", "gilead", "hesperus", "kanta's den", "magec", "new maxios", "mairaath", "salamanca", "nortia",
            "valhalla", "westernesse", "ancyra", "galatia", "tartessus", "thule", "umbra", "cruor", "qaras", "lumen",
            "athulf", "mayasura", "samarra", "arcadia", "baetis", "nachiketa", "tyle", "ragnar complex", "sentinel",
            "canaan", "kumari kandam", "zagan", "penelope's star", "eldfell", "tlalocan", "orthrus", "isirah"));

    // ------------------------------------------------------------------------------------------
    // Faction names
    // ------------------------------------------------------------------------------------------

    /** Faction names, nicknames, leaders, units and home worlds -> one of the 11 faction ids the game knows. */
    static final class FactionNames {
        private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();
        /** Longest aliases first, so "luddic church" wins over "luddic" and "remnant drones" over "drones". */
        private static final List<String> BY_LENGTH;

        static {
            alias("remnants", "remnant", "remnants", "ordo", "ordos", "nexus", "thinking machines", "rogue ai",
                    "ai warships", "ai warship", "ai fleet");
            alias("pirates", "pirate", "pirates", "raider", "raiders", "kanta", "kantas", "buccaneers", "corsairs");
            alias("hegemony", "hegemony", "hegemonic", "xiv battlegroup", "xiv", "inspectorate", "ai inspectorate",
                    "inspectors", "chicomoztoc", "hegemon");
            alias("luddic_path", "luddic path", "path", "pather", "pathers", "luddic", "militant luddites", "zealots");
            alias("luddic_church", "luddic church", "church", "church of galactic redemption", "knights of ludd",
                    "pilgrims", "the faithful", "gilead", "hesperus");
            alias("persean", "persean", "persean league", "perseans", "league", "kazeron");
            alias("sindrian_diktat", "diktat", "sindrian diktat", "sindrian", "sindria", "askonia", "askonian",
                    "askonians", "andrada", "philip andrada", "lion s guard", "lions guard", "lion guard", "executor");
            alias("tritachyon", "tri tachyon", "tritachyon", "tri tach", "tt", "megacorporation", "megacorp",
                    "corporation", "hybrasil", "culann");
            alias("independent", "independent", "independents", "indie", "indies", "mercenary", "mercenaries", "mercs", "bounty hunter",
                    "bounty hunters", "scavenger", "scavengers", "freelancer", "freelancers", "smugglers", "militia");
            alias("derelict", "derelict", "derelicts", "drone", "drones", "probe", "probes", "survey ships",
                    "automated ships", "domain drones");
            alias("omega", "omega");

            List<String> keys = new ArrayList<String>(ALIASES.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            BY_LENGTH = keys;
        }

        private static void alias(String id, String... names) {
            for (String n : names) ALIASES.put(n, id);
        }

        private FactionNames() {
        }

        /** The faction id for a name, or null when it isn't a faction of the game. */
        static String resolve(String name) {
            String n = normalize(name);
            if (n.isEmpty()) return null;
            String exact = ALIASES.get(n);
            if (exact != null) return exact;

            // A known name inside a longer phrase: "Kanta's raiders", "the Askonian navy"
            String padded = " " + n + " ";
            for (String alias : BY_LENGTH) {
                if (padded.contains(" " + alias + " ")) return ALIASES.get(alias);
            }

            // Typos: "tritachion", "hegemonny" - only for long words, so short made-up names don't slip through
            for (String word : n.split(" ")) {
                if (word.length() < 6) continue;
                int allowed = word.length() >= 8 ? 2 : 1;
                for (String alias : BY_LENGTH) {
                    if (alias.indexOf(' ') >= 0 || alias.length() < 6) continue;
                    if (distance(word, alias) <= allowed) return ALIASES.get(alias);
                }
            }
            return null;
        }

        /** Lower case, letters and digits only, single spaces: "Kanta's Den" -> "kanta s den". */
        static String normalize(String s) {
            if (s == null) return "";
            return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
        }

        /** Levenshtein distance. */
        static int distance(String a, String b) {
            int[] prev = new int[b.length() + 1];
            int[] cur = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) prev[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                cur[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                int[] t = prev;
                prev = cur;
                cur = t;
            }
            return prev[b.length()];
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sizes, multipliers and offsets
    // ------------------------------------------------------------------------------------------

    /** The size words of the system prompt, plus the words for size math. */
    static final class SizeWords {
        private static final Map<String, Integer> TIERS = new LinkedHashMap<String, Integer>();
        private static final List<String> BY_LENGTH;

        static {
            tier(15, "tiny", "little", "lone", "single", "one ship", "a ship", "smallest", "minimal");
            tier(40, "small", "smaller", "minor", "few", "couple", "some", "handful", "scout", "scouts", "detachment");
            tier(100, "medium", "medium sized", "standard", "normal", "regular", "average", "typical", "moderate", "mid sized");
            tier(150, "big", "bigger", "large", "larger", "strong", "sizable", "sizeable");
            tier(225, "huge", "massive", "very large", "very big", "immense", "giant");
            tier(300, "humongous", "gigantic", "enormous", "colossal", "armada", "invasion", "biggest", "largest",
                    "maximum", "max", "the biggest possible");

            List<String> keys = new ArrayList<String>(TIERS.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            BY_LENGTH = keys;
        }

        private static void tier(int points, String... words) {
            for (String w : words) TIERS.put(w, points);
        }

        private SizeWords() {
        }

        /** 15, 40, 100, 150, 225 or 300 for a size word, or null. */
        static Integer tier(String text) {
            String n = " " + FactionNames.normalize(text) + " ";
            for (String word : BY_LENGTH) {
                if (n.contains(" " + word + " ")) return TIERS.get(word);
            }
            return null;
        }

        /** "twice" 2, "half" 0.5, "a third of" 1/3, "two thirds" 2/3, "three times" 3, "50 percent bigger" 1.5. */
        static Double multiplier(String text) {
            String t = " " + FactionNames.normalize(text) + " ";
            Double percent = percent(text);
            if (percent != null) {
                if (t.matches(".* (bigger|larger|stronger|more|greater|tougher) .*")) return 1 + percent / 100;
                if (t.matches(".* (smaller|less|weaker|fewer) .*")) return 1 - percent / 100;
                return percent / 100;
            }
            if (t.contains(" two thirds ")) return 2.0 / 3;
            if (t.contains(" three quarters ")) return 0.75;
            if (t.matches(".* (half|halved|halve) .*")) return 0.5;
            if (t.matches(".* (a|one) third .*") || t.contains(" third ")) return 1.0 / 3;
            if (t.matches(".* (a|one) (quarter|fourth) .*") || t.matches(".* (quarter|fourth) .*")) return 0.25;
            if (t.matches(".* (twice|double|doubled|two times) .*")) return 2.0;
            if (t.matches(".* (triple|tripled|thrice|three times) .*")) return 3.0;
            if (t.matches(".* (quadruple|four times) .*")) return 4.0;
            if (t.contains(" times ")) {
                Double n = NumberWords.parse(text);
                if (n != null && n > 0) return n;
            }
            return null;
        }

        /** "plus 30" +30, "10 points smaller" -10, "another 50" +50, "minus 10" -10. */
        static Double offset(String text) {
            Double n = NumberWords.parse(text);
            if (n == null) return null;
            String t = " " + FactionNames.normalize(text) + " ";
            boolean negative = n < 0 || t.matches(".* (minus|less|smaller|fewer|weaker|lower|below|under|off|subtract|take) .*");
            double size = Math.abs(n);
            return negative ? -size : size;
        }

        /** "50 percent", "50%", "10 per cent" -> 50, 50, 10. */
        private static Double percent(String text) {
            String t = text.toLowerCase();
            if (!t.contains("%") && !t.contains("percent") && !t.contains("per cent")) return null;
            return NumberWords.parse(t);
        }
    }
}
