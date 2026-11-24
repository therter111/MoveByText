package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.SectorNames;
import KirizsKeve.MoveByText.nlp.Backend;
import KirizsKeve.MoveByText.nlp.GameNames;
import KirizsKeve.MoveByText.nlp.Provider;
import KirizsKeve.MoveByText.nlp.Settings;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

import java.util.Collections;
import java.util.List;

/**
 * The mod's entry point (modPlugin in mod_info.json): on every game load it reads the settings again, gives the
 * CommandConsole a backend made from them and adds the editor panel to the campaign screen. So a change to
 * llmdata.json or systemPrompt.txt shows on the next load, without restarting the game.
 */
public class MoveByTextImpl extends BaseModPlugin {

    /** The key that opens and closes the editor when "panelKey" doesn't name one. */
    static final int DEFAULT_PANEL_KEY = Keyboard.KEY_F8;

    private static final Logger LOG = Global.getLogger(MoveByTextImpl.class);

    /**
     * One console for the whole run, so a message on its way survives loading a save. Static because the editor
     * panel and its script reach it through the static methods below.
     */
    private static CommandConsole console;
    private static int panelKey = DEFAULT_PANEL_KEY;
    /** "panelPosition" from the settings; null for the default place. */
    private static float[] startPosition;
    /** "panelSize" from the settings; null for the default size. */
    private static float[] startSize;
    /** The names the editor's Tab completes, read from the sector on every game load. */
    private static List<String> completionNames = Collections.emptyList();

    @Override
    public void onGameLoad(boolean newGame) {
        GameNames names = readNames();
        JSONObject cfg = Settings.load(names); // reads the files, so on the game thread
        Backend backend = Provider.backendFor(cfg);
        if (console == null) {
            console = new CommandConsole(backend);
        } else {
            console.useBackend(backend); // a new backend starts with no history: another save, another conversation
            console.dropPending(); // fleets waiting for an OK were meant for the save before
        }
        Settings settings = new Settings(cfg);
        console.setConfirmFirst(settings.flag("confirmBeforeSpawn", true));
        panelKey = keyFor(settings.text("panelKey"));
        startPosition = PanelPlacement.parse(settings.value("panelPosition"));
        if (settings.value("panelPosition") != null && startPosition == null) {
            LOG.warn("panelPosition in " + Settings.FILE + " is not \"x, y\" or [x, y]; using the default place");
        }
        startSize = PanelPlacement.parse(settings.value("panelSize"));
        if (settings.value("panelSize") != null && startSize == null) {
            LOG.warn("panelSize in " + Settings.FILE + " is not \"width, height\" or [width, height]; using the default size");
        }
        completionNames = readCompletionNames();
        Global.getSector().addTransientScript(new CampaignMultiLineScreenPlugin());
    }

    private static List<String> readCompletionNames() {
        try {
            return SectorNames.completionNames();
        } catch (RuntimeException e) {
            LOG.warn("Could not read the names for Tab completion", e);
            return Collections.emptyList();
        }
    }

    /** The mod factions and places of this game, for the prompt and the NLU providers' word lists. */
    private static GameNames readNames() {
        GameNames names;
        try {
            names = SectorNames.read();
        } catch (RuntimeException e) {
            LOG.warn("Could not read the factions and places of this game, using the vanilla ones only", e);
            names = GameNames.NONE;
        }
        GameNames.install(names);
        if (!names.factions().isEmpty()) {
            LOG.info("Factions from other mods: " + String.join(", ", names.factions().keySet()));
        }
        return names;
    }

    /** An LWJGL key name ("F8", "insert"); DEFAULT_PANEL_KEY, with a warning, for a name LWJGL doesn't know. */
    static int keyFor(String name) {
        if (name == null) return DEFAULT_PANEL_KEY;
        int key = Keyboard.getKeyIndex(name.trim().toUpperCase());
        if (key != Keyboard.KEY_NONE) return key;
        LOG.warn("Unknown panelKey \"" + name + "\" in " + Settings.FILE + ", using "
                + Keyboard.getKeyName(DEFAULT_PANEL_KEY) + ". Use a key name such as F8, F9 or INSERT.");
        return DEFAULT_PANEL_KEY;
    }

    /** The editor's Send button: false when nothing was sent (empty, or still waiting for the last answer). */
    public static boolean submit(String text) {
        return console != null && console.submit(text);
    }

    /** Every frame, from CampaignMultiLineScreenPlugin: the next reply, after carrying out its commands. */
    public static String pollReply() {
        return console != null ? console.pollReply() : null;
    }

    /** The editor's "Forget chat" button. */
    public static void clearHistory() {
        if (console != null) console.clearHistory();
    }

    /** Whether the answer to the last message is still awaited; the editor then refuses another one. */
    public static boolean isBusy() {
        return console != null && console.isBusy();
    }

    /** How long the answer has taken so far, in seconds. */
    public static int waitingSeconds() {
        return console != null ? console.waitingSeconds() : 0;
    }

    /** The editor's Cancel button: stop waiting, ignore the answer. */
    public static void cancel() {
        if (console != null) console.cancel();
    }

    /** Who answers, for the editor's waiting line. */
    public static String modelName() {
        return console != null ? console.modelName() : "?";
    }

    /** The key that opens and closes the editor, from "panelKey" in the settings. */
    public static int panelKey() {
        return panelKey;
    }

    /** What the panel keeps for the whole run: open or not, and the sent messages, across save loads. */
    private static final PanelState PANEL_STATE = new PanelState();

    /** The editor panel's link to the console: the static methods above, and the panel state of this run. */
    private static final EditorHost HOST = new EditorHost() {
        @Override public boolean submit(String message) { return MoveByTextImpl.submit(message); }
        @Override public boolean isBusy() { return MoveByTextImpl.isBusy(); }
        @Override public int waitingSeconds() { return MoveByTextImpl.waitingSeconds(); }
        @Override public void cancel() { MoveByTextImpl.cancel(); }
        @Override public String modelName() { return MoveByTextImpl.modelName(); }
        @Override public void clearHistory() { MoveByTextImpl.clearHistory(); }
        @Override public List<String> pendingFleets() {
            return console != null ? console.pendingFleets() : Collections.<String>emptyList();
        }
        @Override public void confirm() { if (console != null) console.confirm(); }
        @Override public void discard() { if (console != null) console.discard(); }
        @Override public List<String> completionNames() { return completionNames; }
        @Override public int panelKey() { return MoveByTextImpl.panelKey(); }
        @Override public float[] startPosition() { return startPosition; }
        @Override public float[] startSize() { return startSize; }
        @Override public String readClipboard() { return SystemClipboard.read(); }
        @Override public boolean writeClipboard(String text) { return SystemClipboard.write(text); }
        @Override public PanelState state() { return PANEL_STATE; }
    };

    /** For the editor panel, which CampaignMultiLineScreenPlugin makes on every game load. */
    static EditorHost host() {
        return HOST;
    }
}
