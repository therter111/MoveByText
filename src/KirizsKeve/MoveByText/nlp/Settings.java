package KirizsKeve.MoveByText.nlp;

import com.fs.starfarer.api.Global;
import org.json.JSONObject;

/**
 * One block of the settings file, data/config/llmdata.json: the whole file, or an object inside it such as "rasa".
 * Reading a value never fails; a missing or unreadable value gives the default, as JSONObject's opt methods do.
 */
public final class Settings {

    /** Every setting is read from this file (relative to the mod folder). */
    public static final String FILE = "data/config/llmdata.json";

    private final JSONObject json;

    /** null reads as an empty block. */
    public Settings(JSONObject json) {
        this.json = json != null ? json : new JSONObject();
    }

    /** The settings file, or an empty object when it can't be read. Call on the game thread. */
    public static JSONObject load() {
        try {
            return Global.getSettings().loadJSON(FILE);
        } catch (Exception e) {
            Global.getLogger(Settings.class).warn("Could not read " + FILE + ": " + e.getMessage());
            return new JSONObject();
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
