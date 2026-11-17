package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.command.CommandParser;
import KirizsKeve.MoveByText.command.FleetCommand;
import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.game.TerminalLog;
import KirizsKeve.MoveByText.nlp.Backend;
import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The conversation between the editor panel and the backend: the player's text goes to the backend, and its
 * replies come back here, where the game thread carries out the commands in them.
 *
 * The backend answers on its own worker thread, so replies wait in a thread-safe queue until pollReply() takes
 * them on the game thread; only there are Starsector objects touched.
 *
 * One message at a time: while an answer is awaited the console is busy and refuses the next message, so impatient
 * clicks can't queue up minutes of requests. cancel() stops waiting; the answer is then ignored when it comes.
 */
public final class CommandConsole {

    /** A reply with more commands than this is a confused model; the rest are skipped. */
    static final int MAX_COMMANDS_PER_REPLY = 10;

    /** What the backend answered to one message: a reply to read commands from, or an error to show. Never both. */
    private static final class Answer {
        final long id;
        final String text;
        final boolean error;

        Answer(long id, String text, boolean error) {
            this.id = id;
            this.text = text;
            this.error = error;
        }
    }

    private final Logger log = Global.getLogger(CommandConsole.class);
    /** What the player sees on the campaign screen. Game thread only. */
    private final TerminalLog screen = new TerminalLog("MOVE BY TEXT", CommandConsole.class);

    /** Replaced by useBackend() when a game load reads the settings again. */
    private volatile Backend backend;
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();
    /** Messages sent and not answered yet: id -> System.nanoTime() when sent. */
    private final Map<Long, Long> waiting = new ConcurrentHashMap<>();
    /** The id of the last message sent; ids start at 1. */
    private final AtomicLong lastId = new AtomicLong();
    /** Answers to messages up to this id are ignored: the player cancelled them. Game thread only. */
    private long cancelledUpTo = 0;

    private final CommandParser parser = new CommandParser();
    private final PlaceFinder places = new PlaceFinder();
    private final FleetSpawner fleets = new FleetSpawner();

    public CommandConsole(Backend backend) {
        this.backend = backend;
    }

    /**
     * The player sent text from the editor: shows it and asks the backend. Returns right away: true when the text
     * was sent, false when it is empty or the answer to the last message is still awaited. Game thread only.
     */
    public boolean submit(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (isBusy()) {
            log.info("Still waiting for the last answer, not sent:\n" + text);
            return false;
        }

        printBlock("NEW MESSAGE FROM UI", text);
        log.info("UI Text Submitted:\n" + text);

        final Backend to = backend;
        final long id = lastId.incrementAndGet();
        waiting.put(id, System.nanoTime());
        if (to.isConfigured()) screen.show("Sent to " + to.modelName() + ", waiting for the answer...");
        to.ask(text, new Backend.Callback() {
            @Override
            public void onResponse(String reply) {
                printBlock("LLM REPLY", reply);
                log.info("LLM reply:\n" + reply);
                answered(new Answer(id, reply, false));
            }

            @Override
            public void onError(String message) {
                log.error("LLM error: " + message);
                answered(new Answer(id, message, true));
            }
        });
        return true;
    }

    /** On the worker thread, or on the game thread when the backend fails at once. Queued before it stops waiting. */
    private void answered(Answer answer) {
        answers.add(answer);
        waiting.remove(answer.id);
    }

    /** True while the answer to a message that wasn't cancelled is awaited. */
    public boolean isBusy() {
        for (Long id : waiting.keySet()) {
            if (id > cancelledUpTo) return true;
        }
        return false;
    }

    /** How long the oldest awaited answer has taken so far, in whole seconds; 0 when not busy. */
    public int waitingSeconds() {
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<Long, Long> e : waiting.entrySet()) {
            if (e.getKey() > cancelledUpTo) oldest = Math.min(oldest, e.getValue());
        }
        return oldest == Long.MAX_VALUE ? 0 : (int) ((System.nanoTime() - oldest) / 1000000000L);
    }

    /**
     * Stops waiting for the answers to the messages sent so far: they are ignored when they arrive. A request
     * already on its way can't be called back, so the next message waits behind it. Game thread only.
     */
    public void cancel() {
        if (!isBusy()) return;
        cancelledUpTo = lastId.get();
        screen.show("Cancelled: the answer will be ignored when it comes.");
    }

    /**
     * The next reply, after carrying out the commands in it; null when none has arrived, the backend reported
     * an error instead, or the message was cancelled. An error is only shown: its text is never read for commands.
     * Game thread only.
     */
    public String pollReply() {
        Answer answer = answers.poll();
        if (answer == null) return null;
        if (answer.id <= cancelledUpTo) {
            screen.line("Ignored the answer to a cancelled message: " + answer.text);
            return null;
        }
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

    /**
     * Answers with next from now on, e.g. after a game load read the settings again, and closes the old backend.
     * A message the old one is still working on is answered as usual.
     */
    public void useBackend(Backend next) {
        Backend old = backend;
        backend = next;
        if (old != null && old != next) old.close();
    }

    /** Who answers, for the panel: "gemini-3.1-flash-lite", "KoboldCpp", "Rasa NLU"... */
    public String modelName() {
        return backend.modelName();
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
