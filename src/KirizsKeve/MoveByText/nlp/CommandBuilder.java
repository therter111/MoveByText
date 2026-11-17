package KirizsKeve.MoveByText.nlp;

import KirizsKeve.MoveByText.command.FleetCommand;
import KirizsKeve.MoveByText.command.HelpCommand;
import KirizsKeve.MoveByText.command.SpawnCommand;
import KirizsKeve.MoveByText.command.TradeCommand;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what an NLU server found in a message (an intent plus labelled pieces of text, in Rasa's /model/parse
 * format) into fleet commands, and those into the same tags the LLMs write, e.g. <SPAWN:pirates:40>, so the game
 * code and the tests work the same for every provider. Rasa and the spaCy server answer in that format, so this
 * serves both.
 *
 * The server only says what is there: "pirate" is the faction, "small" is the size, "Jangala" is the destination.
 * The rules live here: which faction id a name means (FactionNames), what size a word or a bit of math gives
 * (SizeWords), what the defaults are, and when a request can't be carried out (an unknown faction, help without a
 * place).
 *
 * Several fleets come as entity groups ("1", "2", ...). An entity without a group applies to every fleet that
 * doesn't have its own, so "two convoys to Jangala, one from Asharu and one from Askonia" works.
 */
public final class CommandBuilder {

    public static final String NONE = "<NONE>";

    public static final String SPAWN_INTENT = "spawn_fleet";
    public static final String TRADE_INTENT = "trade_convoy";
    public static final String HELP_INTENT = "request_help";
    /** "never mind", "cancel that": drops every request in the sentences before it. */
    public static final String CANCEL_INTENT = "cancel";

    /** More copies of one fleet than this is almost certainly a misread number. */
    public static final int MAX_COUNT = 10;

    /** The entity types read here; the training data labels exactly these (ConsistencyTest checks it). */
    static final List<String> ENTITY_TYPES = Arrays.asList(
            "faction", "size", "points", "destination", "origin", "target", "multiplier", "offset", "reference", "count");

    /** Intents the server is less sure about than this count as "not a request". */
    private final double minConfidence;

    public CommandBuilder(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    /**
     * The tags for a message that was read one sentence at a time (one /model/parse answer per sentence, in order).
     * The models are trained on sentence-sized examples, so long messages are split; this puts the pieces together:
     * - sentences that aren't requests (stories, opinions, questions) are skipped;
     * - a "cancel" sentence drops the requests before it;
     * - several sentences with the same request are one request being refined ("spawn a big pirate fleet.
     *   ... make it a small one"), so for each kind of detail the latest sentence that mentions it wins;
     * - sentences with different requests, or with several fleets of their own, each give their own tags.
     */
    public String toTags(List<JSONObject> sentences) throws Exception {
        List<JSONObject> requests = new ArrayList<>();
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
        if (requests.size() == 1) return toTags(requests.get(0));

        boolean sameRequest = true;
        String first = requests.get(0).getJSONObject("intent").optString("name");
        for (JSONObject r : requests) {
            if (!r.getJSONObject("intent").optString("name").equals(first) || hasGroups(r)) sameRequest = false;
        }
        if (sameRequest) return toTags(merge(requests));

        StringBuilder tags = new StringBuilder();
        for (JSONObject r : requests) {
            String t = toTags(r);
            if (!t.equals(NONE)) tags.append(t);
        }
        return tags.length() == 0 ? NONE : tags.toString();
    }

    /** The tags for one /model/parse answer; NONE when it isn't a request the game can carry out. */
    public String toTags(JSONObject parse) throws Exception {
        JSONObject intent = parse.optJSONObject("intent");
        String name = intent == null ? "" : intent.optString("name", "");
        double confidence = intent == null ? 0 : intent.optDouble("confidence", 0);
        if (name.isEmpty() || confidence < minConfidence) return NONE;

        Set<String> intents = new HashSet<>(Arrays.asList(name.split("\\+")));
        boolean wantsSpawn = intents.contains(SPAWN_INTENT);
        if (!wantsSpawn && !intents.contains(TRADE_INTENT) && !intents.contains(HELP_INTENT)) {
            return NONE; // out_of_scope, nlu_fallback or anything else
        }

        List<Fleet> fleets = fleets(parse.optJSONArray("entities"), parse.optString("text", ""));
        StringBuilder tags = new StringBuilder();
        for (Fleet fleet : fleets) {
            FleetCommand command = fleet.command(wantsSpawn);
            if (command == null) continue;
            String tag = command.toTag();
            // A shared count only counts for a single fleet (see inherit); a group can have its own: "and two little ones"
            for (int i = 0; i < fleet.count(); i++) tags.append(tag);
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
        Map<String, JSONArray> latest = new LinkedHashMap<>();
        for (int s = 0; s < requests.size(); s++) {
            JSONObject parse = requests.get(s);
            String text = parse.optString("text", "");
            Map<String, JSONArray> mine = new LinkedHashMap<>();
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

    // ------------------------------------------------------------------------------------------
    // Grouping the entities into fleets
    // ------------------------------------------------------------------------------------------

    private static List<Fleet> fleets(JSONArray entities, String text) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        if (entities != null) {
            for (int i = 0; i < entities.length(); i++) list.add(entities.getJSONObject(i));
        }
        list.sort((a, b) -> a.optInt("start", 0) - b.optInt("start", 0));

        Fleet shared = new Fleet();
        Map<String, Fleet> groups = new LinkedHashMap<>();
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
        List<Fleet> result = new ArrayList<>(groups.values());
        for (Fleet fleet : result) fleet.inherit(shared);
        return result;
    }

    /** The entity's text: the server's value, or the span of the message it covers. */
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
        final Map<String, List<String>> values = new LinkedHashMap<>();

        void add(String type, String value) {
            List<String> list = values.get(type);
            if (list == null) {
                list = new ArrayList<>();
                values.put(type, list);
            }
            list.add(value);
        }

        /** Takes the shared entities this fleet doesn't have its own of, except the count. */
        void inherit(Fleet shared) {
            for (Map.Entry<String, List<String>> e : shared.values.entrySet()) {
                if (e.getKey().equals("count")) continue; // "two convoys, one to X, one to Y" is already two groups
                if (!values.containsKey(e.getKey())) values.put(e.getKey(), new ArrayList<>(e.getValue()));
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

        /** The command for this fleet, or null when it can't be carried out. */
        FleetCommand command(boolean wantsSpawn) {
            String destination = PlaceNames.clean(first("destination"));
            String target = PlaceNames.clean(first("target"));
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
            } else if (kind.equals("SPAWN")) {
                return null; // a fleet with no faction
            } else {
                faction = kind.equals("TRADE") ? "independent" : null; // help: the closest friendly faction
            }

            int points = points();
            if (kind.equals("SPAWN")) return new SpawnCommand(faction, points);
            if (kind.equals("HELP")) return new HelpCommand(faction, points, target);
            String origin = PlaceNames.clean(first("origin"));
            if (origin != null && origin.equalsIgnoreCase(destination)) origin = null;
            return new TradeCommand(faction, points, destination, origin);
        }

        /** Points first (two numbers are a range: its middle), then a size word, then the player's own fleet, then 100. */
        int points() {
            Double base = null;
            List<Double> numbers = new ArrayList<>();
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
            if (base == null) base = (double) FleetCommand.DEFAULT_POINTS;

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
            return (int) Math.max(FleetCommand.MIN_POINTS, Math.min(FleetCommand.MAX_POINTS, rounded));
        }
    }
}
