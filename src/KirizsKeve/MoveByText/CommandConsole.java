package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.command.CommandParser;
import KirizsKeve.MoveByText.command.FleetCommand;
import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.nlp.LlmBackend;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The conversation between the editor panel and the backend: the player's text goes to the backend, and its
 * replies come back here, where the game thread carries out the commands in them.
 *
 * The backend answers on its own worker thread, so replies wait in a thread-safe queue until pollReply() takes
 * them on the game thread; only there are Starsector objects touched.
 */
public final class CommandConsole {

    private final Logger log = Global.getLogger(CommandConsole.class);

    private final LlmBackend backend;
    private final Queue<String> replies = new ConcurrentLinkedQueue<String>();
    private final CommandParser parser = new CommandParser();
    private final PlaceFinder places = new PlaceFinder();
    private final FleetSpawner fleets = new FleetSpawner();

    public CommandConsole(LlmBackend backend) {
        this.backend = backend;
    }

    /** The player sent text from the editor: shows it and asks the backend. Returns right away. */
    public void submit(String text) {
        printBlock("NEW MESSAGE FROM UI", text);
        log.info("UI Text Submitted:\n" + text);

        if (text == null || text.trim().isEmpty()) return;

        log.info("LLM: sending message to " + backend.modelName() + "...");
        backend.ask(text, new LlmBackend.Callback() {
            @Override
            public void onResponse(String reply) {
                received(reply);
            }

            @Override
            public void onError(String message) {
                log.error("LLM error: " + message);
                replies.add("[LLM error] " + message);
            }
        });
    }

    /** The next reply, after carrying out the commands in it; null when none has arrived. Game thread only. */
    public String pollReply() {
        String reply = replies.poll();
        if (reply != null) {
            for (FleetCommand command : parser.parse(reply)) {
                command.execute(places, fleets);
            }
        }
        return reply;
    }

    /** Forgets the conversation, for backends that keep one. */
    public void clearHistory() {
        backend.clearHistory();
    }

    /** On the backend's worker thread: only shows the reply and queues it for the game thread. */
    private void received(String reply) {
        printBlock("LLM REPLY", reply);
        log.info("LLM reply:\n" + reply);
        replies.add(reply);
    }

    /** "=== TITLE ===", the text, and a closing line as long as the title line. */
    private static void printBlock(String title, String text) {
        String header = "=== " + title + " ===";
        System.out.println(header);
        System.out.println(text);
        System.out.println("=".repeat(header.length()) + "\n");
    }
}
