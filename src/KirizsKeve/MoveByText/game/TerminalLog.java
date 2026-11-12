package KirizsKeve.MoveByText.game;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/** Writes one topic's messages to the terminal ("=== FLEET SPAWN === ...") and to the game's log. */
public final class TerminalLog {

    private final String topic;
    private final Logger logger;

    /** owner names the logger, so the message shows which class wrote it. */
    public TerminalLog(String topic, Class<?> owner) {
        this.topic = topic;
        this.logger = Global.getLogger(owner);
    }

    public void line(String message) {
        System.out.println("=== " + topic + " === " + message);
        logger.info(message);
    }
}
