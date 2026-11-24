package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's text as it is drawn: cut into lines no wider than the box. A line break the player typed ("\n")
 * always starts a new line; a line that would get too wide is cut after its last space, and a word too long for a
 * whole line is cut between its letters. Spaces at the end of a line hang past the edge, as in any text box.
 *
 * Positions are indexes into the text, as TextEditor's cursor is. An index where a line was cut belongs to the
 * next line; the index of a typed "\n" belongs to the line it ends. Immutable.
 */
final class TextLayout {

    /** How wide a piece of text is drawn, in pixels. */
    interface Measure {
        float width(String text);
    }

    private final String text;
    private final Measure measure;
    /** Where each line starts and ends in text; the end leaves out the "\n" that ends a line. */
    private final int[] starts;
    private final int[] ends;

    private TextLayout(String text, Measure measure, List<int[]> lines) {
        this.text = text;
        this.measure = measure;
        this.starts = new int[lines.size()];
        this.ends = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = lines.get(i)[0];
            ends[i] = lines.get(i)[1];
        }
    }

    /** The text cut into lines that fit width. Always at least one line, empty for an empty text. */
    static TextLayout of(String text, float width, Measure measure) {
        List<int[]> lines = new ArrayList<>();
        int from = 0;
        while (true) {
            int newline = text.indexOf('\n', from);
            int to = newline < 0 ? text.length() : newline;
            wrap(text, from, to, width, measure, lines);
            if (newline < 0) break;
            from = newline + 1;
        }
        return new TextLayout(text, measure, lines);
    }

    /** The lines of one typed line, text[from, to): cut after the last space that fits, or inside a long word. */
    private static void wrap(String text, int from, int to, float width, Measure measure, List<int[]> lines) {
        int start = from;
        do {
            int afterSpace = -1;
            int i = start;
            while (i < to) {
                char c = text.charAt(i);
                if (c == ' ') {
                    i++;
                    afterSpace = i; // spaces hang: they never push a line over
                    continue;
                }
                // The first character of a line always stays on it, however wide it is
                if (i > start && measure.width(text.substring(start, i + 1)) > width) break;
                i++;
            }
            if (i >= to) {
                lines.add(new int[]{start, to});
                return;
            }
            int cut = afterSpace > start ? afterSpace : i;
            lines.add(new int[]{start, cut});
            start = cut;
        } while (start < to);
    }

    String text() {
        return text;
    }

    int lines() {
        return starts.length;
    }

    /** Where the line starts in the text. */
    int start(int line) {
        return starts[line];
    }

    /** Where the line ends in the text, leaving out a "\n" after it. */
    int end(int line) {
        return ends[line];
    }

    /** The line's text, as drawn. */
    String line(int line) {
        return text.substring(starts[line], ends[line]);
    }

    /** Whether the line ends where the player typed "\n", or at the end of the text: not where it was cut. */
    boolean endsTypedLine(int line) {
        return line == starts.length - 1 || ends[line] < starts[line + 1];
    }

    /**
     * The last place on the line the cursor can be, to stay on it: its end, or for a line that was cut, just before
     * its last character (the space it was cut after), as the cut itself is the next line's start.
     */
    int lastCaret(int line) {
        return endsTypedLine(line) || ends[line] == starts[line] ? ends[line] : ends[line] - 1;
    }

    /** The line an index is on. */
    int lineOf(int index) {
        int line = 0;
        while (line + 1 < starts.length && starts[line + 1] <= index) line++;
        return line;
    }

    /** How far from the left edge of its line the gap before index is, in pixels. */
    float x(int index) {
        return xOnLine(lineOf(index), index);
    }

    /** The same on a given line, with index kept inside it: the end of a cut line is its own right edge here. */
    float xOnLine(int line, int index) {
        int at = Math.min(Math.max(index, starts[line]), ends[line]);
        return at == starts[line] ? 0f : measure.width(text.substring(starts[line], at));
    }

    /** The index on the line whose gap is nearest to x (pixels from the line's left edge). */
    int indexAt(int line, float x) {
        int best = starts[line];
        float bestDistance = Math.abs(x);
        int last = lastCaret(line);
        for (int i = starts[line] + 1; i <= last; i++) {
            float distance = Math.abs(measure.width(text.substring(starts[line], i)) - x);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }
}
