package KirizsKeve.MoveByText.nlp.spacy;

import KirizsKeve.MoveByText.nlp.CommandBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One sentence's /model/parse answer while the rules work on it: its text, intent and entities. */
final class Sentence {

    /**
     * Lookups (faction, size) only run in sentences up to this many words. In longer ones the word found often
     * belongs to something that is only mentioned (an enemy, a story), which cost more cases than it fixed.
     */
    static final int MAX_LOOKUP_WORDS = 8;

    private final JSONObject parse;
    private final String text;
    private String intent;
    private final List<JSONObject> entities = new ArrayList<JSONObject>();

    Sentence(JSONObject parse) throws Exception {
        this.parse = parse;
        this.text = parse.optString("text", "");
        JSONObject i = parse.optJSONObject("intent");
        this.intent = i == null ? "" : i.optString("name", "");
        JSONArray list = parse.optJSONArray("entities");
        for (int n = 0; list != null && n < list.length(); n++) entities.add(list.getJSONObject(n));
    }

    String text() {
        return text;
    }

    /** The intent's name, e.g. "spawn_fleet"; "" when there is none. */
    String intent() {
        return intent;
    }

    /** The parse the server sent, for its other fields (e.g. intent_ranking). */
    JSONObject parse() {
        return parse;
    }

    void setIntent(JSONObject intent) throws Exception {
        parse.put("intent", intent);
        this.intent = intent.optString("name", "");
    }

    /** The entities, which the rules may change, remove or add to. */
    List<JSONObject> entities() {
        return entities;
    }

    boolean has(String type) {
        return hasAny(Collections.singletonList(type));
    }

    boolean hasAny(List<String> types) {
        for (JSONObject e : entities) {
            if (types.contains(e.optString("entity"))) return true;
        }
        return false;
    }

    /** A request the game can carry out: spawn, trade or help. */
    boolean isRequest() {
        return intent.equals(CommandBuilder.SPAWN_INTENT) || intent.equals(CommandBuilder.TRADE_INTENT)
                || intent.equals(CommandBuilder.HELP_INTENT);
    }

    /** Short enough for the lookups, see MAX_LOOKUP_WORDS. */
    boolean isShort() {
        String t = text.trim();
        return (t.isEmpty() ? 0 : t.split("\\s+").length) <= MAX_LOOKUP_WORDS;
    }

    /** An entity covering the whole sentence, as the lookups add them. */
    void addWholeSentence(String type, String value) throws Exception {
        entities.add(new JSONObject().put("entity", type).put("value", value).put("start", 0).put("end", text.length()));
    }

    /** The parse with the entities as the rules left them. */
    JSONObject toParse() throws Exception {
        JSONArray out = new JSONArray();
        for (JSONObject e : entities) out.put(e);
        parse.put("entities", out);
        return parse;
    }
}
