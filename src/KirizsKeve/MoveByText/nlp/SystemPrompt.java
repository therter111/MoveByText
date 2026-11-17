package KirizsKeve.MoveByText.nlp;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * The language models' system prompt (Gemini, KoboldCpp). It lives in its own file, data/config/systemPrompt.txt,
 * so it can be read and diffed as text. A "systemPrompt" in llmdata.json still wins, for trying out a variant.
 *
 * The prompt is a template with one placeholder line, {MOD_FACTIONS}. In a game with factions from other mods it
 * becomes a list of them; without any, the line goes away and the prompt is exactly the vanilla one.
 *
 * Nothing here touches Starsector objects: Settings reads the file in the game, LlmPromptTest reads it from disk.
 */
public final class SystemPrompt {

    /** Relative to the mod folder. */
    public static final String FILE = "data/config/systemPrompt.txt";
    public static final String PLACEHOLDER = "{MOD_FACTIONS}";
    /** The key in llmdata.json that the backends read the finished prompt from. */
    public static final String KEY = "systemPrompt";

    private SystemPrompt() {
    }

    /**
     * cfg with its systemPrompt filled in: cfg's own when it has one that isn't blank, else fileText (null when the
     * file couldn't be read). Leaves no systemPrompt when there is none, so the backend reports it as missing.
     * Changes and returns cfg.
     */
    public static JSONObject withPrompt(JSONObject cfg, String fileText, GameNames names) throws Exception {
        String own = new Settings(cfg).text(KEY);
        String filled = fill(own != null ? own : fileText, names);
        if (filled != null) {
            cfg.put(KEY, filled);
        } else {
            cfg.remove(KEY);
        }
        return cfg;
    }

    /** True when cfg has a systemPrompt of its own, which then wins over the file. */
    public static boolean overridden(JSONObject cfg) {
        return new Settings(cfg).text(KEY) != null;
    }

    /**
     * The template with Windows line ends made "\n", outer whitespace trimmed and the placeholder replaced: by the
     * mod factions, or by nothing (its whole line goes) when there are none. null for a null or blank template.
     */
    public static String fill(String template, GameNames names) {
        if (template == null) return null;
        String t = template.replace("\r\n", "\n").trim();
        if (t.isEmpty()) return null;
        String block = modFactions(names);
        if (t.contains("\n" + PLACEHOLDER)) {
            return t.replace("\n" + PLACEHOLDER, block.isEmpty() ? "" : "\n" + block);
        }
        return t.replace(PLACEHOLDER, block);
    }

    /**
     * The prompt lines for the mod factions, in the style of the faction descriptions above the placeholder:
     * "- This game also has ...", then "  id: name, also name" for each. Empty without mod factions.
     */
    static String modFactions(GameNames names) {
        Map<String, List<String>> factions = names == null ? null : names.factions();
        if (factions == null || factions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("- This game also has factions added by mods. They exist in this game, so"
                + " they are never \"from another mod\": use their ids like the eleven above.");
        for (Map.Entry<String, List<String>> f : factions.entrySet()) {
            sb.append("\n  ").append(f.getKey()).append(':');
            List<String> shown = f.getValue();
            for (int i = 0; i < shown.size(); i++) {
                sb.append(i == 0 ? " " : i == 1 ? ", also " : ", ").append(shown.get(i));
            }
        }
        return sb.toString();
    }
}
