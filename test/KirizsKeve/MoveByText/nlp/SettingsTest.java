package KirizsKeve.MoveByText.nlp;

import KirizsKeve.MoveByText.LlmPromptTest;
import KirizsKeve.MoveByText.TestSuite;
import KirizsKeve.MoveByText.nlp.gemini.GeminiClient;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for where the system prompt comes from (SystemPrompt, data/config/systemPrompt.txt) and for the
 * names of the running game (GameNames): which mod factions and places are kept, and how they reach the prompt.
 *
 * Run main() from IntelliJ (working directory = the mod folder), or every offline suite with AllOfflineTests.
 */
public class SettingsTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("SettingsTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    private static final String EXAMPLE_CONFIG = "data/config/llmdata.json.example";

    static {
        // ------------------------------------------------------------------------------------------
        // Where the prompt comes from
        // ------------------------------------------------------------------------------------------

        test("prompt: the file's text is used when the settings have none", () -> {
            JSONObject cfg = SystemPrompt.withPrompt(new JSONObject().put("provider", "gemini"), "From the file.", GameNames.NONE);
            same("From the file.", cfg.getString("systemPrompt"), "systemPrompt");
            same("gemini", cfg.getString("provider"), "other settings stay");
        });

        test("prompt: a systemPrompt in the settings wins over the file", () -> {
            JSONObject cfg = new JSONObject().put("systemPrompt", "From llmdata.json.");
            check(SystemPrompt.overridden(cfg), "overridden");
            same("From llmdata.json.", SystemPrompt.withPrompt(cfg, "From the file.", GameNames.NONE).getString("systemPrompt"),
                    "systemPrompt");
        });

        test("prompt: a blank systemPrompt in the settings counts as none", () -> {
            for (String blank : new String[]{"", "   ", "\n\t"}) {
                JSONObject cfg = new JSONObject().put("systemPrompt", blank);
                check(!SystemPrompt.overridden(cfg), "not overridden by " + show(blank));
                same("File.", SystemPrompt.withPrompt(cfg, "File.", GameNames.NONE).getString("systemPrompt"),
                        "systemPrompt for " + show(blank));
            }
        });

        test("prompt: no file and none in the settings -> no systemPrompt, and Gemini reports it missing", () -> {
            JSONObject cfg = SystemPrompt.withPrompt(new JSONObject().put("apiKey", "k").put("model", "m"), null, GameNames.NONE);
            check(!cfg.has("systemPrompt"), "no systemPrompt key");
            GeminiClient gemini = new GeminiClient(cfg);
            check(!gemini.isConfigured(), "not configured");
            Throwable e = fails(() -> gemini.askNow("hi"), "askNow");
            has(e.getMessage(), "systemPrompt", "message");
        });

        test("prompt: Windows line ends become \\n and outer whitespace goes, inner whitespace stays", () -> {
            same("Line one.\n\n  Line two.", SystemPrompt.fill("\r\n  Line one.\r\n\r\n  Line two.\r\n\r\n", GameNames.NONE), "filled");
            same(null, SystemPrompt.fill(" \r\n ", GameNames.NONE), "blank");
            same(null, SystemPrompt.fill(null, GameNames.NONE), "null");
        });

        test("prompt: without mod factions the placeholder's line goes and nothing else changes", () -> {
            same("A\nB\nC", SystemPrompt.fill("A\nB\n" + SystemPrompt.PLACEHOLDER + "\nC", GameNames.NONE), "own line");
            same("A\nB", SystemPrompt.fill("A\nB\n" + SystemPrompt.PLACEHOLDER, GameNames.NONE), "last line");
            same("A  B", SystemPrompt.fill("A " + SystemPrompt.PLACEHOLDER + " B", GameNames.NONE), "inside a line");
        });

        test("prompt: the shipped systemPrompt.txt has the placeholder once, on a line of its own", () -> {
            String file = shippedPrompt();
            same(1, file.split(java.util.regex.Pattern.quote(SystemPrompt.PLACEHOLDER), -1).length - 1, "placeholders");
            has(file.replace("\r\n", "\n"), "\n" + SystemPrompt.PLACEHOLDER + "\n", "the file");
            String filled = SystemPrompt.fill(file, GameNames.NONE);
            check(!filled.contains("{"), "no placeholder left: " + show(filled.substring(0, 80)));
            same(file.replace("\r\n", "\n").trim().replace("\n" + SystemPrompt.PLACEHOLDER, ""), filled, "the rest unchanged");
        });

        test("prompt: readConfig gives the example the shipped prompt, as the game would", () -> {
            JSONObject example = LlmPromptTest.readConfig(Paths.get(EXAMPLE_CONFIG));
            same(SystemPrompt.fill(shippedPrompt(), GameNames.NONE), example.getString("systemPrompt"), "systemPrompt");
            same("F8", example.getString("panelKey"), "the example's other settings are read too");
        });

        test("prompt: mod factions are listed where the placeholder was", () -> {
            GameNames names = names(faction("blackrock_driveyards", "Blackrock Drive Yards", "Blackrock"),
                    faction("tahlan_legioinfernalis", "Legio Infernalis"));
            String filled = SystemPrompt.fill("STEP 3\n  omega: Omega.\n" + SystemPrompt.PLACEHOLDER + "\n- Every unit...", names);
            same("STEP 3\n  omega: Omega.\n"
                    + "- This game also has factions added by mods. They exist in this game, so they are never \"from"
                    + " another mod\": use their ids like the eleven above.\n"
                    + "  blackrock_driveyards: Blackrock Drive Yards, also Blackrock, blackrock driveyards\n"
                    + "  tahlan_legioinfernalis: Legio Infernalis, also tahlan legioinfernalis\n"
                    + "- Every unit...", filled, "filled prompt");
        });

        // ------------------------------------------------------------------------------------------
        // The running game's names
        // ------------------------------------------------------------------------------------------

        test("names: vanilla ids are left out, the id's own name is added last", () -> {
            GameNames names = names(faction("pirates", "Pirates of Mods"), faction("hegemony", "Hegemony"),
                    faction("blackrock_driveyards", "Blackrock Drive Yards"));
            same(Collections.singletonList("blackrock_driveyards"), new ArrayList<>(names.factions().keySet()), "factions");
            same(Arrays.asList("Blackrock Drive Yards", "blackrock driveyards"), names.factions().get("blackrock_driveyards"),
                    "names");
        });

        test("names: short names, vanilla aliases and repeats are skipped", () -> {
            GameNames names = names(faction("mod_a", "XY", "Pirates", "the faithful", "Mod A Navy", "MOD A NAVY"));
            same(Arrays.asList("Mod A Navy", "mod a"), names.factions().get("mod_a"), "names kept");
            same(null, names.factionFor("pirates"), "a vanilla alias stays vanilla");
            same("mod_a", names.factionFor("mod a navy"), "exact");
            same("mod_a", names.factionFor("the mod a navy fleet"), "inside a phrase");
            same(null, names.factionFor("xy"), "too short");
        });

        test("names: a faction with no usable name is left out, and at most MAX_FACTIONS are kept", () -> {
            same(Collections.singletonList("pirates2"), names(faction("pirates2", "Pirates")).factions().get("pirates2"),
                    "only the id's own name is left");
            same(0, names(faction("xy", "Pirates", "TT")).factions().size(), "nothing left: left out");
            Map<String, List<String>> many = new LinkedHashMap<>();
            for (int i = 0; i < GameNames.MAX_FACTIONS + 5; i++) many.put("modfaction" + i, Collections.singletonList("Mod Faction " + i));
            same(GameNames.MAX_FACTIONS, new GameNames(many, Collections.<String>emptySet()).factions().size(), "capped");
        });

        test("names: places are kept in lower case, short ones dropped", () -> {
            GameNames names = new GameNames(Collections.<String, List<String>>emptyMap(),
                    new java.util.HashSet<>(Arrays.asList("New Caledonia", " Ix ", "Port Mods")));
            check(names.hasPlace("new caledonia"), "new caledonia");
            check(names.hasPlace("port mods"), "port mods");
            check(!names.hasPlace("ix"), "too short");
        });

        test("names: install() makes a snapshot current, null puts NONE back", () -> {
            GameNames names = names(faction("blackrock_driveyards", "Blackrock Drive Yards"));
            try {
                GameNames.install(names);
                check(GameNames.current() == names, "installed");
            } finally {
                GameNames.install(null);
            }
            check(GameNames.current() == GameNames.NONE, "back to NONE");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static String shippedPrompt() throws Exception {
        return new String(Files.readAllBytes(Paths.get(SystemPrompt.FILE)), StandardCharsets.UTF_8);
    }

    /** {id, name, name...} */
    private static String[] faction(String... idAndNames) {
        return idAndNames;
    }

    private static GameNames names(String[]... factions) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String[] f : factions) map.put(f[0], Arrays.asList(f).subList(1, f.length));
        return new GameNames(map, Collections.<String>emptySet());
    }
}
