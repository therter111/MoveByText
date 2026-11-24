package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for the editor's text: how TextLayout cuts it into lines and maps the cursor to them, and what
 * TextEditor's keys and clicks do to the text, the cursor and the selection. Every character is 7 px wide here, so
 * a 70 px box holds 10 of them. PanelTest checks that the keys and the mouse reach these.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class TextEditorTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("TextEditorTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    private static final TextLayout.Measure MONO = s -> 7f * s.length();

    static {
        // ------------------------------------------------------------------------------------------
        // TextLayout
        // ------------------------------------------------------------------------------------------

        test("layout: a line that would be too wide is cut after its last space", () -> {
            same("[hello |world foo]", lines("hello world foo", 70), "lines");
            same("[send a |small |convoy to |Jangala]", lines("send a small convoy to Jangala", 70), "several cuts");
            same("[fits in 10]", lines("fits in 10", 70), "exactly the width");
        });

        test("layout: a typed line break always starts a line, an empty line is a line too", () -> {
            same("[ab|cd]", lines("ab\ncd", 70), "two lines");
            same("[ab||cd]", lines("ab\n\ncd", 70), "an empty line between");
            same("[ab|]", lines("ab\n", 70), "a break at the end: an empty last line");
            same("[]", lines("", 70), "no text: one empty line");
        });

        test("layout: a word too long for a line is cut between its letters", () -> {
            same("[abcdefghij|klmnop]", lines("abcdefghijklmnop", 70), "long word");
            same("[go |abcdefghij|klm]", lines("go abcdefghijklm", 70), "after a short word");
            same("[W|W]", lines("WW", 5), "a line too narrow for one character keeps one");
        });

        test("layout: spaces at the end of a line hang past the edge instead of starting the next one", () -> {
            same("[abcdefghij   |x]", lines("abcdefghij   x", 70), "spaces stay on the line");
        });

        test("layout: an index where a line was cut is on the next line; a typed break's index on its own line", () -> {
            TextLayout l = TextLayout.of("hello world foo", 70, MONO);
            same(0, l.lineOf(5), "inside the first line");
            same(1, l.lineOf(6), "the cut");
            same(1, l.lineOf(15), "the end of the text");
            TextLayout typed = TextLayout.of("ab\ncd", 70, MONO);
            same(0, typed.lineOf(2), "before the \\n");
            same(1, typed.lineOf(3), "after it");
        });

        test("layout: x is the gap's distance from the line's left edge", () -> {
            TextLayout l = TextLayout.of("hello world foo", 70, MONO);
            same(0f, l.x(0), "start");
            same(35f, l.x(5), "after hello");
            same(0f, l.x(6), "the next line's start");
            same(14f, l.x(8), "two letters in");
        });

        test("layout: indexAt picks the nearest gap, and stays on a cut line", () -> {
            TextLayout l = TextLayout.of("hello world foo", 70, MONO);
            same(8, l.indexAt(1, 15), "nearest to 15 px: 14");
            same(9, l.indexAt(1, 18), "nearest to 18 px: 21");
            same(6, l.indexAt(1, -40), "left of the line: its start");
            same(15, l.indexAt(1, 999), "right of the last line: the end of the text");
            same(5, l.indexAt(0, 999), "right of a cut line: before the space it was cut at");
            same(2, TextLayout.of("ab\ncd", 70, MONO).indexAt(0, 999), "right of a typed line: before its \\n");
        });

        // ------------------------------------------------------------------------------------------
        // Typing
        // ------------------------------------------------------------------------------------------

        test("insert: at the cursor, in place of the selection", () -> {
            TextEditor e = editor("spawn pirates");
            e.moveTo(6, false);
            check(e.insert("big "), "fits");
            same("spawn big pirates", e.text(), "inserted");
            same(10, e.cursor(), "cursor after it");
            e.moveTo(6, false);
            e.moveTo(9, true);
            e.insert("huge");
            same("spawn huge pirates", e.text(), "the selection replaced");
            check(!e.hasSelection(), "no selection after");
        });

        test("insert: Windows line ends become \\n, a tab a space, other control characters are left out", () -> {
            TextEditor e = editor("");
            e.insert("one\r\ntwo\rthree\tfour\u0007\u001b\u0000five");
            same("one\ntwo\nthree fourfive", e.text(), "cleaned");
        });

        test("insert: at most maxChars; as much as fits goes in, and it says so", () -> {
            TextEditor e = new TextEditor(10);
            check(e.insert("abcdefgh"), "8 fit");
            check(!e.insert("XYZ"), "3 more don't");
            same("abcdefghXY", e.text(), "cut to 10");
            check(!e.insert("!"), "full");
            same("abcdefghXY", e.text(), "nothing more");
            e.selectAll();
            check(e.insert("new"), "a selection makes room");
            same("new", e.text(), "replaced");
        });

        test("insert: never cuts between the two halves of a surrogate pair", () -> {
            TextEditor e = new TextEditor(3);
            e.insert("ab🚀");
            same("ab", e.text(), "the rocket doesn't fit whole, so it's left out");
        });

        test("setText: cleaned, cut to maxChars, cursor at the end; clear empties", () -> {
            TextEditor e = new TextEditor(5);
            e.setText("ab\tcdefg");
            same("ab cd", e.text(), "text");
            same(5, e.cursor(), "cursor");
            check(!e.hasSelection(), "no selection");
            e.clear();
            same("", e.text(), "cleared");
            same(0, e.cursor(), "cursor");
        });

        test("backspace and delete: a character, a word with Ctrl, the selection when there is one", () -> {
            TextEditor e = editor("send the hegemony now");
            e.moveTo(17, false); // after "hegemony"
            e.backspace(false);
            same("send the hegemon now", e.text(), "Backspace");
            e.backspace(true);
            same("send the  now", e.text(), "Ctrl+Backspace: the word");
            e.backspace(true);
            same("send  now", e.text(), "Ctrl+Backspace again: the space and the word before");
            e.toStart(false);
            e.delete(true);
            same("now", e.text(), "Ctrl+Delete: the word and the spaces after it");
            e.delete(false);
            same("ow", e.text(), "Delete");
            e.selectAll();
            e.backspace(true);
            same("", e.text(), "a selection goes whole");
            e.backspace(false);
            e.delete(false);
            same("", e.text(), "at the edges nothing happens");
        });

        test("backspace and delete take a whole surrogate pair", () -> {
            TextEditor e = editor("a🚀b");
            e.moveTo(3, false);
            e.backspace(false);
            same("ab", e.text(), "Backspace");
            e.setText("a🚀b");
            e.moveTo(1, false);
            e.delete(false);
            same("ab", e.text(), "Delete");
        });

        // ------------------------------------------------------------------------------------------
        // Moving and selecting
        // ------------------------------------------------------------------------------------------

        test("left and right: by a character, by a word with Ctrl; Shift selects", () -> {
            TextEditor e = editor("send Kanta's raiders, now");
            e.toStart(false);
            e.right(true, false);
            same(5, e.cursor(), "Ctrl+Right: to the next word");
            e.right(true, false);
            same(13, e.cursor(), "over \"Kanta's\" as one word");
            e.right(true, false);
            same(20, e.cursor(), "to the comma");
            e.right(true, false);
            same(22, e.cursor(), "over the comma and the space");
            e.left(true, false);
            same(20, e.cursor(), "Ctrl+Left: back over the punctuation");
            e.left(true, false);
            same(13, e.cursor(), "and the word before");
            e.right(false, true);
            e.right(false, true);
            same("ra", e.selected(), "Shift+Right selects");
            e.left(false, false);
            same(13, e.cursor(), "Left without Shift goes to the selection's start");
            check(!e.hasSelection(), "and drops it");
            e.moveTo(13, false);
            e.moveTo(15, true);
            e.right(false, false);
            same(15, e.cursor(), "Right without Shift goes to the selection's end");
            e.toStart(false);
            e.left(false, false);
            same(0, e.cursor(), "nothing left of the start");
            e.toEnd(false);
            e.right(true, false);
            same(e.length(), e.cursor(), "nothing right of the end");
        });

        test("home and end: the line as drawn; Ctrl: the whole text", () -> {
            TextEditor e = editor("hello world foo");
            TextLayout l = TextLayout.of(e.text(), 70, MONO);
            e.moveTo(8, false);
            e.home(l, false);
            same(6, e.cursor(), "Home: the start of the second line");
            e.end(l, true);
            same("world foo", e.selected(), "Shift+End selects to its end");
            e.moveTo(2, false);
            e.end(l, false);
            same(5, e.cursor(), "End on a cut line stays on it, before the space");
            e.toEnd(false);
            same(15, e.cursor(), "Ctrl+End");
            e.toStart(true);
            same("hello world foo", e.selected(), "Shift+Ctrl+Home selects to the start");
        });

        test("up and down: the same column, kept over a shorter line; past the first or last line, the text's ends", () -> {
            TextEditor e = editor("abcdefghij\nab\nabcdefghij");
            TextLayout l = TextLayout.of(e.text(), 100, MONO);
            e.moveTo(8, false);
            e.vertical(l, 1, false);
            same(13, e.cursor(), "Down to the short line: its end");
            e.vertical(l, 1, false);
            same(22, e.cursor(), "Down again: back in column 8");
            e.vertical(l, -1, false);
            e.vertical(l, -1, false);
            same(8, e.cursor(), "Up twice: column 8 of the first line");
            e.vertical(l, -1, false);
            same(0, e.cursor(), "Up on the first line: the start");
            e.toEnd(false);
            e.vertical(l, 1, false);
            same(e.length(), e.cursor(), "Down on the last line: the end");
            e.moveTo(3, false);
            e.vertical(l, 1, true);
            same("defghij\nab", e.selected(), "Shift+Down selects");
        });

        test("up and down: through lines that were cut, not only typed ones", () -> {
            TextEditor e = editor("hello world foo");
            TextLayout l = TextLayout.of(e.text(), 70, MONO);
            e.moveTo(2, false);
            e.vertical(l, 1, false);
            same(8, e.cursor(), "column 2 of the second line");
            e.moveTo(14, false);
            e.vertical(l, -1, false);
            same(5, e.cursor(), "Up from far right: the end of the first line, before its space");
        });

        test("a typed key forgets the column Up and Down kept", () -> {
            TextEditor e = editor("abcdefghij\nab\nabcdefghij");
            TextLayout l = TextLayout.of(e.text(), 100, MONO);
            e.moveTo(8, false);
            e.vertical(l, 1, false);
            e.insert("c");
            l = TextLayout.of(e.text(), 100, MONO);
            e.vertical(l, 1, false);
            same(18, e.cursor(), "column 3, where the cursor was after typing");
        });

        test("select all, a word by double click, and a drag", () -> {
            TextEditor e = editor("send to Kanta's Den now");
            e.selectAll();
            same("send to Kanta's Den now", e.selected(), "Ctrl+A");
            e.selectWordAt(10);
            same("Kanta's", e.selected(), "the word, with its apostrophe");
            e.selectWordAt(7);
            same("to", e.selected(), "at a word's end");
            e.selectWordAt(4);
            same("send", e.selected(), "a space after the word: the word before");
            e.moveTo(8, false);
            e.moveTo(19, true);
            same("Kanta's Den", e.selected(), "drag");
            same(8, e.anchor(), "anchor where the drag began");
            e.moveTo(999, false);
            same(e.length(), e.cursor(), "past the end: the end");
            e.moveTo(-5, false);
            same(0, e.cursor(), "before the start: the start");
        });

        test("replace: a range for another text, the cursor after it (Tab completion)", () -> {
            TextEditor e = editor("go to jan now");
            check(e.replace(0, 9, "go to Jangala "), "fits");
            same("go to Jangala  now", e.text(), "replaced");
            same(14, e.cursor(), "after the name");
            TextEditor full = new TextEditor(10);
            full.insert("go to jan");
            check(!full.replace(0, 9, "go to Jangala "), "doesn't fit whole");
            same("go to Jang", full.text(), "cut to 10");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    private static TextEditor editor(String text) {
        TextEditor e = new TextEditor(1000);
        e.setText(text);
        return e;
    }

    /** The lines of the text at that width, as "[line|line]". */
    private static String lines(String text, float width) {
        TextLayout l = TextLayout.of(text, width, MONO);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < l.lines(); i++) out.add(l.line(i));
        return "[" + String.join("|", out) + "]";
    }
}
