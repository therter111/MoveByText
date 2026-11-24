package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.nlp.GameNames;
import KirizsKeve.MoveByText.nlp.Settings;
import KirizsKeve.MoveByText.nlp.SystemPrompt;
import KirizsKeve.MoveByText.nlp.gemini.GeminiClient;
import com.fs.starfarer.api.Global;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for what happens on a game load: reading the settings and the prompt through the game's
 * SettingsAPI (Settings.load), and MoveByTextImpl.onGameLoad reading them again on every load. FakeGame stands in
 * for the game's settings and sector. Whether the real game caches the files can only be seen in the game.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class GameLoadTest {

    /** The files of one test, path -> text, and a fake sector; nothing is installed until a test does it. */
    static final class Files {
        final Map<String, String> files = new HashMap<>();
        final FakeGame.World world = new FakeGame.World();

        void settings(String json) {
            files.put(Settings.FILE, json);
        }

        void prompt(String text) {
            files.put(SystemPrompt.FILE, text);
        }

        void install(boolean modLookupWorks) {
            Global.setSettings(FakeGame.settings(files, modLookupWorks));
            world.install();
        }

        void stop() {
            FakeGame.uninstall();
            GameNames.install(GameNames.NONE);
        }
    }

    public static final TestSuite<Files> SUITE = new TestSuite<>("GameLoadTest", Files::new, Files::stop);

    private static void test(String name, TestSuite.Test<Files> body) {
        SUITE.add(name, body);
    }

    private static final String GEMINI = "{\n  # a comment, as the game allows\n  \"provider\": \"gemini\",\n"
            + "  \"apiKey\": \"test-key\",\n  \"model\": \"%s\"%s\n}";

    private static String gemini(String model, String extra) {
        return String.format(GEMINI, model, extra);
    }

    static {
        // ------------------------------------------------------------------------------------------
        // Settings.load
        // ------------------------------------------------------------------------------------------

        test("settings: the prompt comes from systemPrompt.txt, read from this mod", f -> {
            f.settings(gemini("m", ""));
            f.prompt("Prompt from the file.\r\n");
            f.install(true);
            JSONObject cfg = Settings.load();
            same("Prompt from the file.", cfg.getString("systemPrompt"), "systemPrompt");
            same("gemini", cfg.getString("provider"), "the settings themselves");
            check(new GeminiClient(cfg).isConfigured(), "Gemini configured with it");
        });

        test("settings: when the lookup by mod id fails, the plain path is tried", f -> {
            f.settings(gemini("m", ""));
            f.prompt("Prompt.");
            f.install(false);
            same("Prompt.", Settings.load().getString("systemPrompt"), "systemPrompt");
        });

        test("settings: a systemPrompt in llmdata.json wins over the file", f -> {
            f.settings(gemini("m", ",\n  \"systemPrompt\": \"From llmdata.json.\""));
            f.prompt("From the file.");
            f.install(true);
            same("From llmdata.json.", Settings.load().getString("systemPrompt"), "systemPrompt");
        });

        test("settings: no prompt file -> no systemPrompt, and Gemini reports it missing", f -> {
            f.settings(gemini("m", ""));
            f.install(true);
            JSONObject cfg = Settings.load();
            check(!cfg.has("systemPrompt"), "no systemPrompt");
            check(!new GeminiClient(cfg).isConfigured(), "Gemini not configured");
        });

        test("settings: no llmdata.json -> empty settings, the prompt still filled in", f -> {
            f.prompt("Prompt.");
            f.install(true);
            JSONObject cfg = Settings.load();
            same(Collections.singletonList("systemPrompt"), keys(cfg), "keys");
        });

        test("settings: the mod factions go into the prompt's placeholder", f -> {
            f.settings(gemini("m", ""));
            f.prompt("Factions:\n  omega: Omega.\n" + SystemPrompt.PLACEHOLDER + "\nEnd.");
            f.install(true);
            Map<String, List<String>> mods = new HashMap<>();
            mods.put("blackrock_driveyards", Arrays.asList("Blackrock Drive Yards"));
            String prompt = Settings.load(new GameNames(mods, Collections.<String>emptySet())).getString("systemPrompt");
            has(prompt, "\n  blackrock_driveyards: Blackrock Drive Yards, also blackrock driveyards\nEnd.", "prompt");
            same("Factions:\n  omega: Omega.\nEnd.", Settings.load().getString("systemPrompt"), "without mod factions");
        });

        // ------------------------------------------------------------------------------------------
        // MoveByTextImpl.onGameLoad
        // ------------------------------------------------------------------------------------------

        test("game load: every load reads the settings again and swaps the backend", f -> {
            f.settings(gemini("model-one", ",\n  \"panelKey\": \"F9\""));
            f.prompt("Prompt.");
            f.install(true);
            new MoveByTextImpl().onGameLoad(false);
            same("model-one", MoveByTextImpl.modelName(), "the first load's model");
            same(Keyboard.KEY_F9, MoveByTextImpl.panelKey(), "panelKey F9");
            same(1, f.world.transientScripts.size(), "the panel's script was added");
            check(f.world.transientScripts.get(0) instanceof CampaignMultiLineScreenPlugin, "it is the panel's script");

            f.settings(gemini("model-two", "")); // the player edited llmdata.json, then loaded a save
            new MoveByTextImpl().onGameLoad(false);
            same("model-two", MoveByTextImpl.modelName(), "the second load's model");
            same(Keyboard.KEY_F8, MoveByTextImpl.panelKey(), "no panelKey any more: the default");
            same(2, f.world.transientScripts.size(), "a script for each load");
        });

        test("game load: the sector's mod factions are installed for the NLP side", f -> {
            f.settings(gemini("m", ""));
            f.prompt("Prompt.");
            f.world.faction("hegemony");
            f.world.faction("shadow_industry", "Shadowyards", "Shadowyards Reconstruction Authority", "Shadowyards", true);
            f.install(true);
            new MoveByTextImpl().onGameLoad(true);
            same(Collections.singleton("shadow_industry"), GameNames.current().factions().keySet(), "installed");
            same("shadow_industry", KirizsKeve.MoveByText.nlp.FactionNames.resolve("Shadowyards"), "resolves");
        });

        // ------------------------------------------------------------------------------------------
        // The panel's key
        // ------------------------------------------------------------------------------------------

        test("panelKey: LWJGL key names in any case; unknown or missing names give F8", f -> {
            same(Keyboard.KEY_F8, MoveByTextImpl.keyFor("F8"), "F8");
            same(Keyboard.KEY_F9, MoveByTextImpl.keyFor("f9"), "f9");
            same(Keyboard.KEY_INSERT, MoveByTextImpl.keyFor(" insert "), "insert");
            same(Keyboard.KEY_GRAVE, MoveByTextImpl.keyFor("GRAVE"), "GRAVE");
            same(MoveByTextImpl.DEFAULT_PANEL_KEY, MoveByTextImpl.keyFor("banana"), "unknown");
            same(MoveByTextImpl.DEFAULT_PANEL_KEY, MoveByTextImpl.keyFor(null), "missing");
            same(Keyboard.KEY_F8, MoveByTextImpl.DEFAULT_PANEL_KEY, "the default is F8");
        });

        test("panelKey: the example settings name a real key", f -> {
            JSONObject example = LlmPromptTest.readConfig(java.nio.file.Paths.get("data/config/llmdata.json.example"));
            String name = example.getString("panelKey");
            check(Keyboard.getKeyIndex(name.toUpperCase()) != Keyboard.KEY_NONE, show(name) + " is an LWJGL key name");
        });

        // ------------------------------------------------------------------------------------------
        // Where the panel starts, and how big
        // ------------------------------------------------------------------------------------------

        test("panelPosition and panelSize: read on every load; unreadable or missing ones give the defaults", f -> {
            f.settings(gemini("m", ",\n  \"panelPosition\": [30, 140],\n  \"panelSize\": \"900, 500\""));
            f.prompt("Prompt.");
            f.install(true);
            new MoveByTextImpl().onGameLoad(false);
            same("[30.0, 140.0]", java.util.Arrays.toString(MoveByTextImpl.host().startPosition()), "panelPosition");
            same("[900.0, 500.0]", java.util.Arrays.toString(MoveByTextImpl.host().startSize()), "panelSize");

            f.settings(gemini("m", ",\n  \"panelSize\": \"big\""));
            new MoveByTextImpl().onGameLoad(false);
            same(null, MoveByTextImpl.host().startSize(), "an unreadable panelSize");
            same(null, MoveByTextImpl.host().startPosition(), "no panelPosition any more");
        });

        test("panelPosition and panelSize: the example settings' are readable, and its size is the default one", f -> {
            JSONObject example = LlmPromptTest.readConfig(java.nio.file.Paths.get("data/config/llmdata.json.example"));
            check(PanelPlacement.parse(example.get("panelPosition")) != null, "panelPosition " + show(example.get("panelPosition")));
            float[] size = PanelPlacement.parse(example.get("panelSize"));
            check(size != null, "panelSize " + show(example.get("panelSize")));
            same(MultiLineTextFieldPanel.DEFAULT_WIDTH + ", " + MultiLineTextFieldPanel.DEFAULT_HEIGHT, size[0] + ", " + size[1],
                    "the example shows the default size");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    private static List<String> keys(JSONObject json) {
        List<String> out = new java.util.ArrayList<>();
        for (java.util.Iterator<?> it = json.keys(); it.hasNext(); ) out.add(String.valueOf(it.next()));
        return out;
    }
}
