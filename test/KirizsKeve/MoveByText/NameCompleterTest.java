package KirizsKeve.MoveByText;

import java.util.Arrays;
import java.util.Collections;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for NameCompleter, the editor's Tab: which name the end of the text is completed to, what Tab
 * again gives, and when there is nothing to complete. PanelTest checks that the Tab key reaches it.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class NameCompleterTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("NameCompleterTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    /** Names as the sector gives them: factions, systems, markets and stations, in no order. */
    private static NameCompleter sector() {
        return new NameCompleter(Arrays.asList("Hegemony", "Tri-Tachyon", "Jangala", "Port Tse", "Tigra City", "Asharu",
                "Askonia", "Ancyra", "Kanta's Den", "Church of Galactic Redemption", "Corvus"));
    }

    static {
        test("a word is completed to a name it starts, with a space after it", () -> {
            same("send help to Jangala ", sector().complete("send help to jan"), "completed");
            same("Hegemony ", sector().complete("heg"), "the text is just the word");
        });

        test("any case in, the game's spelling out", () -> {
            same("SEND TO Jangala ", sector().complete("SEND TO jAnG"), "completed");
            same("Tri-Tachyon ", sector().complete("tri-t"), "punctuation inside the name");
        });

        test("the end of several words: \"port t\" is Port Tse, not \"port\" and Tigra City", () -> {
            same("send a convoy to Port Tse ", sector().complete("send a convoy to port t"), "two words");
            same("raid Kanta's Den ", sector().complete("raid kanta's d"), "an apostrophe inside the name");
        });

        test("names of up to four words are completed; a five-word end isn't tried", () -> {
            same("join the Church of Galactic Redemption ", sector().complete("join the church of galactic r"), "four words");
            NameCompleter five = new NameCompleter(Collections.singletonList("One Two Three Four Five"));
            same(null, five.complete("one two three four f"), "five words");
        });

        test("the shortest name first; Tab again gives the next, then alphabetically, and wraps around", () -> {
            NameCompleter c = sector();
            // Asharu and Ancyra have 6 letters, Askonia 7
            String first = c.complete("go to a");
            same("go to Ancyra ", first, "shortest, then alphabetically");
            String second = c.complete(first);
            same("go to Asharu ", second, "Tab again");
            String third = c.complete(second);
            same("go to Askonia ", third, "and again");
            same("go to Ancyra ", c.complete(third), "back to the first");
        });

        test("text changed since the last Tab starts over", () -> {
            NameCompleter c = sector();
            same("Asharu ", c.complete("as"), "first");
            same("go to Asharu ", c.complete("go to as"), "other text: the first name again, not the next");
            same("go to Askonia ", c.complete("go to Asharu "), "Tab on what it just gave goes on");
        });

        test("one name that fits: Tab again does nothing", () -> {
            NameCompleter c = sector();
            String once = c.complete("hel to jan");
            same("hel to Jangala ", once, "completed");
            same(null, c.complete(once), "nothing more");
        });

        test("nothing to complete: null", () -> {
            NameCompleter c = sector();
            same(null, c.complete(null), "null");
            same(null, c.complete(""), "empty");
            same(null, c.complete("send to jan "), "ends with a space");
            same(null, c.complete("send to jan\t"), "ends with a tab");
            same(null, c.complete("send to xyz"), "no name starts like it");
            same(null, c.complete("send to Jangala"), "already the whole name, and no longer one starts like it");
            same(null, new NameCompleter(Collections.<String>emptyList()).complete("jan"), "no names at all");
        });

        test("a whole name is completed to a longer one that starts with it", () -> {
            NameCompleter c = new NameCompleter(Arrays.asList("Jangala", "Jangala Station"));
            same("Jangala Station ", c.complete("jangala"), "longer name");
        });

        test("names: blanks skipped, trimmed, the same name in another case kept once", () -> {
            NameCompleter c = new NameCompleter(Arrays.asList(null, "", "   ", " jangala ", "Jangala", "JANGALA"));
            String once = c.complete("ja");
            same("jangala ", once, "the first spelling, trimmed");
            same(null, c.complete(once), "one name, not three");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }
}
