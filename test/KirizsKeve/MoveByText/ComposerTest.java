package KirizsKeve.MoveByText;

import java.util.Arrays;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for what Send and Ctrl+Up / Ctrl+Down do with the message being written (Composer, MessageHistory):
 * what a message is, refusing to send while an answer is awaited, and going back through earlier messages. What the
 * keys do to the text itself is TextEditorTest's; the panel around them is PanelTest's.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class ComposerTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("ComposerTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    static {
        test("message: the lines of the text, blank ones left out", () -> {
            same("spawn a small\npirate fleet\nnear Jangala", Composer.message("spawn a small\n   \npirate fleet\n\nnear Jangala\n"),
                    "message");
            same("  indented stays", Composer.message("  indented stays"), "a line's own spaces stay");
            same(null, Composer.message(""), "nothing written");
            same(null, Composer.message(" \n\t\n"), "only blank lines");
            same(null, Composer.message(null), "null");
        });

        test("send: an empty message does nothing", () -> {
            MessageHistory h = new MessageHistory(30);
            same(Composer.Send.NOTHING, new Composer(h).send(" \n ", false), "result");
            same(0, h.size(), "not in the history");
        });

        test("send: while an answer is awaited nothing is sent", () -> {
            MessageHistory h = new MessageHistory(30);
            Composer c = new Composer(h);
            same(Composer.Send.WAIT, c.send("spawn pirates", true), "result");
            same(0, h.size(), "not in the history");
            same(null, c.sent(), "nothing sent");
        });

        test("send: the message goes out and into the history", () -> {
            MessageHistory h = new MessageHistory(30);
            Composer c = new Composer(h);
            same(Composer.Send.SENT, c.send("spawn a small\n\npirate fleet", false), "result");
            same("spawn a small\npirate fleet", c.sent(), "sent");
            same(1, h.size(), "history");
            same("spawn a small\npirate fleet", h.get(0), "history entry");
        });

        test("history: Ctrl+Up goes back message by message and stops at the oldest", () -> {
            Composer c = composerWith("one", "two", "three");
            same("three", c.browse(true, ""), "newest first");
            same("two", c.browse(true, "three"), "then the one before");
            same("one", c.browse(true, "two"), "the oldest");
            same(null, c.browse(true, "one"), "at the oldest nothing changes");
        });

        test("history: Ctrl+Down goes forward, and past the newest gives back what was being written", () -> {
            Composer c = composerWith("one", "two");
            same(null, c.browse(false, "half a mess"), "Ctrl+Down while writing a new message: nothing");
            same("two", c.browse(true, "half a mess"), "back");
            same("one", c.browse(true, "two"), "back again");
            same("two", c.browse(false, "one"), "forward");
            same("half a mess", c.browse(false, "two"), "past the newest: the unsent text");
            same(null, c.browse(false, "half a mess"), "Ctrl+Down again: nothing");
        });

        test("history: a message of several lines comes back whole", () -> {
            Composer c = composerWith("spawn a small\npirate fleet\nnear Jangala");
            same("spawn a small\npirate fleet\nnear Jangala", c.browse(true, ""), "all its lines");
        });

        test("history: nothing sent yet, Ctrl+Up and Ctrl+Down do nothing", () -> {
            Composer c = new Composer(new MessageHistory(30));
            same(null, c.browse(true, "text"), "Ctrl+Up");
            same(null, c.browse(false, "text"), "Ctrl+Down");
        });

        test("history: sending starts again from the newest, with nothing unsent", () -> {
            Composer c = composerWith("one", "two");
            c.browse(true, "draft");
            c.browse(true, "two");
            c.send("three", false);
            same("three", c.browse(true, ""), "Ctrl+Up after sending: the message just sent");
            same("", c.browse(false, "three"), "and past it: the empty editor of after the send");
        });

        test("history: the same message twice in a row is kept once, and at most max are kept", () -> {
            MessageHistory h = new MessageHistory(3);
            for (String m : new String[]{"a", "a", "b", "a", "c", "d"}) h.remember(m);
            same(3, h.size(), "size");
            same(Arrays.asList("a", "c", "d"), Arrays.asList(h.get(0), h.get(1), h.get(2)), "kept, oldest first");
        });

        test("history: shared by composers, as it outlives the panel across save loads", () -> {
            MessageHistory h = new MessageHistory(30);
            new Composer(h).send("from before the load", false);
            same("from before the load", new Composer(h).browse(true, ""), "a new panel's composer sees it");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    /** A composer whose history already has these messages, oldest first. */
    private static Composer composerWith(String... sent) {
        MessageHistory h = new MessageHistory(30);
        for (String s : sent) h.remember(s);
        return new Composer(h);
    }
}
