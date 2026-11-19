package KirizsKeve.MoveByText.game;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

/**
 * Writes one topic's messages to the terminal ("=== FLEET SPAWN === ...") and to the game's log. The messages
 * the player should see (what was spawned, what went wrong) also go to the campaign screen's message list.
 */
public final class TerminalLog {

    /** Gets every message the player should see, as it is shown; the mod's own panel lists them. */
    public interface ScreenListener {
        void shown(String message, boolean failed);
    }

    /** At most one: the panel of the campaign that is running. Game thread only. */
    private static ScreenListener screenListener;

    public static void setScreenListener(ScreenListener listener) {
        screenListener = listener;
    }

    private final String topic;
    private final Logger logger;

    /** owner names the logger, so the message shows which class wrote it. */
    public TerminalLog(String topic, Class<?> owner) {
        this.topic = topic;
        this.logger = Global.getLogger(owner);
    }

    /** Terminal and log only: details the player doesn't need on screen. */
    public void line(String message) {
        System.out.println("=== " + topic + " === " + message);
        logger.info(message);
    }

    /** Like line(), and shown to the player on the campaign screen. Game thread only. */
    public void show(String message) {
        line(message);
        onScreen(message, false);
    }

    /** Like show(), for something that went wrong: shown in the game's warning color. Game thread only. */
    public void fail(String message) {
        line(message);
        onScreen(message, true);
    }

    /**
     * Like show(), but only in the campaign screen's message list, not in the mod's panel, which says the same its
     * own way (e.g. "Sent to ...": the panel has its waiting line). Game thread only.
     */
    public void notice(String message) {
        line(message);
        onCampaignScreen(message, false);
    }

    /** Does nothing outside a running campaign (the offline tests, the title screen). */
    private static void onScreen(String message, boolean failed) {
        try {
            if (screenListener != null) screenListener.shown(message, failed);
        } catch (RuntimeException e) {
            // The message is already in the terminal and the log; never let showing it break a command
        }
        onCampaignScreen(message, failed);
    }

    private static void onCampaignScreen(String message, boolean failed) {
        try {
            if (Global.getSector() == null || Global.getSector().getCampaignUI() == null) return;
            if (failed) {
                Global.getSector().getCampaignUI().addMessage(message, Misc.getNegativeHighlightColor());
            } else {
                Global.getSector().getCampaignUI().addMessage(message);
            }
        } catch (RuntimeException e) {
            // The message is already in the terminal and the log; never let showing it break a command
        }
    }
}
