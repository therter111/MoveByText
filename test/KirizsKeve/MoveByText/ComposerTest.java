package KirizsKeve.MoveByText;

import java.util.Arrays;
import java.util.Collections;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for what the editor's keys and Send button do to the text (Composer, MessageHistory): the lines of
 * a message, refusing to send while an answer is awaited, and Up and Down through earlier messages. The panel
 * around them needs the game's UI.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class ComposerTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("ComposerTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    static {
        test("lines: Enter moves the field's line into the draft, Backspace takes the last one back", () -> {
            Composer c = composer();
            c.newLine("spawn a small");
            c.newLine("pirate fleet");
            same(Arrays.asList("spawn a small", "pirate fleet"), c.draft(), "draft");
            same("pirate fleet", c.takeBackLine(), "taken back");
            same(Collections.singletonList("spawn a small"), c.draft(), "draft after");
            same("spawn a small", c.takeBackLine(), "the first line");
            same(null, c.takeBackLine(), "nothing left to take back");
        });

        test("message: the draft and the field, blank lines left out", () -> {
            Composer c = composer();
            c.newLine("spawn a small");
            c.newLine("   ");
            c.newLine("pirate fleet");
            same("spawn a small\npirate fleet\nnear Jangala", c.message("near Jangala"), "message");
            same("spawn a small\npirate fleet", c.message("  "), "blank field");
            same(null, composer().message(""), "nothing written");
        });

        test("send: an empty message does nothing", () -> {
            Composer c = composer();
            c.newLine(" ");
            same(Composer.Send.NOTHING, c.send("", false), "result");
            same(Collections.singletonList(" "), c.draft(), "draft untouched");
        });

        test("send: while an answer is awaited nothing is sent and nothing is lost", () -> {
            MessageHistory h = new MessageHistory(30);
            Composer c = new Composer(h);
            c.newLine("first line");
            same(Composer.Send.WAIT, c.send("second line", true), "result");
            same(Collections.singletonList("first line"), c.draft(), "draft kept");
            same(0, h.size(), "not in the history");
            same(null, c.sent(), "nothing sent");
        });

        test("send: the message goes out, into the history, and the draft is cleared", () -> {
            MessageHistory h = new MessageHistory(30);
            Composer c = new Composer(h);
            c.newLine("spawn a small");
            same(Composer.Send.SENT, c.send("pirate fleet", false), "result");
            same("spawn a small\npirate fleet", c.sent(), "sent");
            same(Collections.emptyList(), c.draft(), "draft cleared");
            same(1, h.size(), "history");
            same("spawn a small\npirate fleet", h.get(0), "history entry");
        });

        test("history: Up goes back message by message and stops at the oldest", () -> {
            Composer c = composerWith("one", "two", "three");
            same("three", c.browse(true), "newest first");
            same("two", c.browse(true), "then the one before");
            same("one", c.browse(true), "the oldest");
            same("one", c.browse(true), "stays at the oldest");
        });

        test("history: Down goes forward, and past the newest gives an empty editor", () -> {
            Composer c = composerWith("one", "two");
            same(null, c.browse(false), "Down while writing a new message: nothing");
            c.browse(true);
            c.browse(true);
            same("two", c.browse(false), "forward");
            same("", c.browse(false), "past the newest: empty field");
            same(Collections.emptyList(), c.draft(), "and no draft");
            same(null, c.browse(false), "Down again: nothing");
        });

        test("history: a message of several lines comes back as draft plus field", () -> {
            Composer c = composerWith("spawn a small\npirate fleet\nnear Jangala");
            same("near Jangala", c.browse(true), "the field gets the last line");
            same(Arrays.asList("spawn a small", "pirate fleet"), c.draft(), "the draft the others");
            same("spawn a small\npirate fleet\nnear Jangala", c.message("near Jangala"), "the same message again");
        });

        test("history: empty history, Up and Down do nothing", () -> {
            Composer c = composer();
            same(null, c.browse(true), "Up");
            same(null, c.browse(false), "Down");
        });

        test("history: sending starts again from the newest", () -> {
            Composer c = composerWith("one", "two");
            c.browse(true);
            c.browse(true);
            c.send("three", false);
            same("three", c.browse(true), "Up after sending: the message just sent");
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
            same("from before the load", new Composer(h).browse(true), "a new panel's composer sees it");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    private static Composer composer() {
        return new Composer(new MessageHistory(30));
    }

    /** A composer whose history already has these messages, oldest first. */
    private static Composer composerWith(String... sent) {
        MessageHistory h = new MessageHistory(30);
        for (String s : sent) h.remember(s);
        return new Composer(h);
    }
}
