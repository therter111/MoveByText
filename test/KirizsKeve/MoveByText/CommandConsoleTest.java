package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.game.TerminalLog;
import KirizsKeve.MoveByText.nlp.Backend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for CommandConsole: one message at a time (busy until the answer arrives), cancel(), and swapping
 * the backend when a game load reads the settings again. A fake backend holds every callback until the test
 * answers it. Outside the game Global.getSector() is null, so nothing is spawned or shown on a campaign screen;
 * a reply with a fleet command in it would fail there, which is how "ignored" is told apart from "carried out".
 *
 * Run main() from IntelliJ (working directory = the mod folder), or every offline suite with AllOfflineTests.
 */
public class CommandConsoleTest {

    public static final TestSuite<Screen> SUITE = new TestSuite<>("CommandConsoleTest", Screen::new, Screen::stop);

    private static void test(String name, TestSuite.Test<Screen> body) {
        SUITE.add(name, body);
    }

    /** A reply that would try to spawn a fleet, which can't work outside the game. */
    private static final String SPAWN_REPLY = "<SPAWN:pirates:40>";

    static {
        test("submit: the text goes to the backend, busy until the answer arrives", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            check(!console.isBusy(), "idle at first");
            check(console.submit("spawn some pirates"), "sent");
            same(Collections.singletonList("spawn some pirates"), backend.asked, "asked");
            check(console.isBusy(), "busy while waiting");
            same(null, console.pollReply(), "no reply yet");

            backend.answer(0, "<NONE>");
            check(!console.isBusy(), "idle once answered");
            same("<NONE>", console.pollReply(), "the reply");
            has(String.join("\n", s.shown), "Nothing to do", "shown");
        });

        test("submit: empty and blank text is not sent", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            for (String blank : new String[]{null, "", "  \n\t"}) {
                check(!console.submit(blank), "not sent: " + show(blank));
            }
            same(0, backend.asked.size(), "nothing asked");
            check(!console.isBusy(), "idle");
        });

        test("busy: a second message is refused until the first is answered", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            check(console.submit("first"), "first sent");
            check(!console.submit("second"), "second refused");
            check(!console.submit("third"), "third refused");
            same(Collections.singletonList("first"), backend.asked, "only the first reached the backend");

            backend.answer(0, "<NONE>");
            check(console.submit("second"), "sent once the first was answered");
            same(Arrays.asList("first", "second"), backend.asked, "asked");
        });

        test("busy: an error ends the wait too, and its text is never read for commands", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.submit("spawn");
            backend.fail(0, "timed out " + SPAWN_REPLY);
            check(!console.isBusy(), "idle after the error");
            same(null, console.pollReply(), "no reply"); // a tag in the error would throw here if it were run
            has(String.join("\n", s.failed), "No answer: timed out", "shown as a failure");
        });

        test("busy: a backend that fails at once (not configured) never leaves the console busy", s -> {
            FakeBackend backend = new FakeBackend();
            backend.configured = false;
            CommandConsole console = new CommandConsole(backend);
            check(console.submit("spawn"), "handed to the backend");
            check(!console.isBusy(), "not busy");
            same(null, console.pollReply(), "no reply");
            has(String.join("\n", s.failed), "not configured", "the backend's reason is shown");
        });

        test("cancel: stops waiting at once, and the late answer is ignored", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.submit("spawn some pirates");
            console.cancel();
            check(!console.isBusy(), "not busy after cancel");
            has(String.join("\n", s.shown), "Cancelled", "shown");
            check(console.submit("something else"), "the next message can be sent");

            backend.answer(0, SPAWN_REPLY); // would throw outside the game if it were carried out
            same(null, console.pollReply(), "the cancelled answer is not carried out");
            check(console.isBusy(), "still waiting for the second message");
            backend.answer(1, "<NONE>");
            same("<NONE>", console.pollReply(), "the second answer is");
        });

        test("cancel: with nothing awaited it does nothing", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.cancel();
            same(0, s.shown.size(), "nothing shown");
            console.submit("hi");
            backend.answer(0, "<NONE>");
            same("<NONE>", console.pollReply(), "a later answer is not ignored");
        });

        test("waitingSeconds: 0 when idle, counts while waiting", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            same(0, console.waitingSeconds(), "idle");
            console.submit("hi");
            Thread.sleep(1100);
            check(console.waitingSeconds() >= 1, "at least a second: " + console.waitingSeconds());
            console.cancel();
            same(0, console.waitingSeconds(), "cancelled");
        });

        test("useBackend: new messages go to the new backend, the old one is closed and still answers", s -> {
            FakeBackend old = new FakeBackend(), next = new FakeBackend();
            CommandConsole console = new CommandConsole(old);
            console.submit("on the way");
            console.useBackend(next);
            check(old.closed, "old backend closed");
            check(!next.closed, "new one open");
            check(console.isBusy(), "the message on its way is still awaited");
            same("fake " + next.hashCode(), console.modelName(), "modelName from the new backend");

            old.answer(0, "<NONE>");
            same("<NONE>", console.pollReply(), "its answer arrives as usual");
            console.submit("next one");
            same(Collections.singletonList("next one"), next.asked, "asked the new backend");
            console.clearHistory();
            same(1, next.cleared, "clearHistory goes to the new backend");
            same(0, old.cleared, "not the old one");
        });

        test("useBackend: the same backend again is not closed", s -> {
            FakeBackend backend = new FakeBackend();
            CommandConsole console = new CommandConsole(backend);
            console.useBackend(backend);
            check(!backend.closed, "not closed");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    // ------------------------------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------------------------------

    /** Collects what the console shows the player; one per test, so tests don't see each other's messages. */
    static final class Screen {
        final List<String> shown = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        Screen() {
            TerminalLog.setScreenListener((message, isFailure) -> (isFailure ? failed : shown).add(message));
        }

        void stop() {
            TerminalLog.setScreenListener(null);
        }
    }

    /** Keeps every callback until the test answers it; ask() answers at once only when not configured. */
    static final class FakeBackend implements Backend {
        final List<String> asked = new ArrayList<>();
        final List<Callback> callbacks = new ArrayList<>();
        boolean configured = true;
        boolean closed = false;
        int cleared = 0;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void ask(String userText, Callback callback) {
            if (!configured) {
                callback.onError("not configured");
                return;
            }
            asked.add(userText);
            callbacks.add(callback);
        }

        void answer(int i, String reply) {
            callbacks.get(i).onResponse(reply);
        }

        void fail(int i, String message) {
            callbacks.get(i).onError(message);
        }

        @Override
        public String askNow(String userText) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearHistory() {
            cleared++;
        }

        @Override
        public String modelName() {
            return "fake " + hashCode();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
