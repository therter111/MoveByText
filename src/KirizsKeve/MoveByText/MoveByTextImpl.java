package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.nlp.Provider;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * The mod's entry point (modPlugin in mod_info.json): on every game load it makes sure there is a CommandConsole
 * and adds the editor panel to the campaign screen.
 */
public class MoveByTextImpl extends BaseModPlugin {

    /**
     * One console for the whole run, so the backend and its conversation outlive loading a save. Static because
     * the editor panel and its script reach it through the static methods below.
     */
    private static CommandConsole console;

    @Override
    public void onGameLoad(boolean newGame) {
        if (console == null) {
            console = new CommandConsole(Provider.load()); // reads the settings file, so on the game thread
        }
        Global.getSector().addTransientScript(new CampaignMultiLineScreenPlugin());
    }

    /** The editor's Send button. */
    public static void writeSubmittedTextToTerminal(String text) {
        if (console != null) console.submit(text);
    }

    /** Every frame, from CampaignMultiLineScreenPlugin: the next reply, after carrying out its commands. */
    public static String pollLlmReply() {
        return console != null ? console.pollReply() : null;
    }

    public static void clearLlmHistory() {
        if (console != null) console.clearHistory();
    }
}
