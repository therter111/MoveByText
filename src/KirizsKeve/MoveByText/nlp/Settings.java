package KirizsKeve.MoveByText.nlp;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONObject;

/**
 * One block of the settings file, data/config/llmdata.json: the whole file, or an object inside it such as "rasa".
 * Reading a value never fails; a missing or unreadable value gives the default, as JSONObject's opt methods do.
 */
public final class Settings {

    /** Every setting is read from this file (relative to the mod folder). */
    public static final String FILE = "data/config/llmdata.json";
    /** The id in mod_info.json, so the prompt file is read from this mod even if another one has the same path. */
    public static final String MOD_ID = "KirizsKeve_MoveByText";

    private final JSONObject json;

    /** null reads as an empty block. */
    public Settings(JSONObject json) {
        this.json = json != null ? json : new JSONObject();
    }

    /** The settings for a game without mod factions. See load(GameNames). */
    public static JSONObject load() {
        return load(GameNames.NONE);
    }

    /**
     * The settings file, with "systemPrompt" filled in from data/config/systemPrompt.txt (unless the file sets its
     * own) and the mod factions of names put into it. An empty object when the settings can't be read. Reads both
     * files fresh, so a change shows on the next game load. Call on the game thread.
     */
    public static JSONObject load(GameNames names) {
        Logger log = Global.getLogger(Settings.class);
        JSONObject cfg;
        try {
            cfg = Global.getSettings().loadJSON(FILE);
        } catch (Exception e) {
            log.warn("Could not read " + FILE + ": " + e.getMessage());
            cfg = new JSONObject();
        }

        String prompt = null;
        if (SystemPrompt.overridden(cfg)) {
            log.info("Using the systemPrompt in " + FILE + " instead of " + SystemPrompt.FILE);
        } else {
            prompt = loadPromptFile(log);
        }
        try {
            return SystemPrompt.withPrompt(cfg, prompt, names);
        } catch (Exception e) {
            log.warn("Could not fill in the system prompt: " + e.getMessage());
            return cfg;
        }
    }

    /** The prompt file's text, or null when it can't be read (the backend then reports systemPrompt as missing). */
    private static String loadPromptFile(Logger log) {
        try {
            return Global.getSettings().loadText(SystemPrompt.FILE, MOD_ID);
        } catch (Exception e) {
            try {
                return Global.getSettings().loadText(SystemPrompt.FILE);
            } catch (Exception again) {
                log.warn("Could not read " + SystemPrompt.FILE + ": " + again.getMessage());
                return null;
            }
        }
    }

    /** The object named name inside this block; an empty block when it is missing. */
    public Settings block(String name) {
        return new Settings(json.optJSONObject(name));
    }

    /** Trimmed text, or null when the value is missing or blank. */
    public String text(String key) {
        String value = json.optString(key, null);
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    public int integer(String key, int defaultValue) {
        return json.optInt(key, defaultValue);
    }

    public double number(String key, double defaultValue) {
        return json.optDouble(key, defaultValue);
    }

    public boolean flag(String key, boolean defaultValue) {
        return json.optBoolean(key, defaultValue);
    }

    /** The raw value (a string, number, list...), or null. */
    public Object value(String key) {
        return json.opt(key);
    }

    /** A timeout given in seconds, in milliseconds; zero, negative or missing gives defaultSeconds. */
    public int timeoutMs(String key, int defaultSeconds) {
        int seconds = json.optInt(key, defaultSeconds);
        return (seconds > 0 ? seconds : defaultSeconds) * 1000;
    }
}
