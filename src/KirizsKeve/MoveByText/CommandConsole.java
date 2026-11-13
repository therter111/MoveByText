package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.command.CommandParser;
import KirizsKeve.MoveByText.command.FleetCommand;
import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.game.TerminalLog;
import KirizsKeve.MoveByText.nlp.LlmBackend;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.util.List;
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

    /** A reply with more commands than this is a confused model; the rest are skipped. */
    static final int MAX_COMMANDS_PER_REPLY = 10;

    /** What the backend answered: a reply to read commands from, or an error to show. Never both. */
    private static final class Answer {
        final String text;
        final boolean error;

        Answer(String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    private final Logger log = Global.getLogger(CommandConsole.class);
    /** What the player sees on the campaign screen. Game thread only. */
    private final TerminalLog screen = new TerminalLog("MOVE BY TEXT", CommandConsole.class);

    private final LlmBackend backend;
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<Answer>();
    private final CommandParser parser = new CommandParser();
    private final PlaceFinder places = new PlaceFinder();
    private final FleetSpawner fleets = new FleetSpawner();

    public CommandConsole(LlmBackend backend) {
        this.backend = backend;
    }

    /** The player sent text from the editor: shows it and asks the backend. Returns right away. Game thread only. */
    public void submit(String text) {
        printBlock("NEW MESSAGE FROM UI", text);
        log.info("UI Text Submitted:\n" + text);

        if (text == null || text.trim().isEmpty()) return;

        if (backend.isConfigured()) screen.show("Sent to " + backend.modelName() + ", waiting for the answer...");
        backend.ask(text, new LlmBackend.Callback() {
            @Override
            public void onResponse(String reply) {
                printBlock("LLM REPLY", reply);
                log.info("LLM reply:\n" + reply);
                answers.add(new Answer(reply, false));
            }

            @Override
            public void onError(String message) {
                log.error("LLM error: " + message);
                answers.add(new Answer(message, true));
            }
        });
    }

    /**
     * The next reply, after carrying out the commands in it; null when none has arrived or the backend reported
     * an error instead. An error is only shown: its text is never read for commands. Game thread only.
     */
    public String pollReply() {
        Answer answer = answers.poll();
        if (answer == null) return null;
        if (answer.error) {
            screen.fail("No answer: " + answer.text);
            return null;
        }

        List<FleetCommand> commands = parser.parse(answer.text);
        if (commands.isEmpty()) {
            screen.show("Nothing to do: the answer had no fleet command in it.");
        }
        if (commands.size() > MAX_COMMANDS_PER_REPLY) {
            screen.fail("The answer had " + commands.size() + " commands, only the first " + MAX_COMMANDS_PER_REPLY
                    + " are carried out.");
            commands = commands.subList(0, MAX_COMMANDS_PER_REPLY);
        }
        for (FleetCommand command : commands) {
            command.execute(places, fleets);
        }
        return answer.text;
    }

    /** Forgets the conversation, for backends that keep one. */
    public void clearHistory() {
        backend.clearHistory();
    }

    /** "=== TITLE ===", the text, and a closing line as long as the title line. */
    private static void printBlock(String title, String text) {
        String header = "=== " + title + " ===";
        System.out.println(header);
        System.out.println(text);
        System.out.println("=".repeat(header.length()) + "\n");
    }
}
