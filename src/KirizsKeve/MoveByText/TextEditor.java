package KirizsKeve.MoveByText;

/**
 * The message being written in the editor: its text, the cursor, and the selection, which runs from the anchor to
 * the cursor (none when they are the same). Everything the keys and the mouse do to the text is here, without the
 * UI, so it can be tested outside the game; moves by line need the TextLayout the box draws. PanelState keeps one,
 * so what is being written survives resizing the panel and loading another save. Game thread only.
 */
final class TextEditor {

    private final int maxChars;
    private String text = "";
    private int cursor = 0;
    private int anchor = 0;
    /** Where Up and Down aim, in pixels from the line's left edge, so they keep the column over short lines; NaN: unset. */
    private float column = Float.NaN;

    TextEditor(int maxChars) {
        this.maxChars = maxChars;
    }

    String text() {
        return text;
    }

    int length() {
        return text.length();
    }

    int maxChars() {
        return maxChars;
    }

    int cursor() {
        return cursor;
    }

    int anchor() {
        return anchor;
    }

    boolean hasSelection() {
        return cursor != anchor;
    }

    int selectionStart() {
        return Math.min(cursor, anchor);
    }

    int selectionEnd() {
        return Math.max(cursor, anchor);
    }

    /** The selected text; empty when nothing is selected. */
    String selected() {
        return text.substring(selectionStart(), selectionEnd());
    }

    // ------------------------------------------------------------------------------------------
    // Changing the text
    // ------------------------------------------------------------------------------------------

    /** Replaces the whole text (cut to maxChars) and puts the cursor at its end. */
    void setText(String newText) {
        text = cut(clean(newText), maxChars);
        cursor = anchor = text.length();
        column = Float.NaN;
    }

    void clear() {
        setText("");
    }

    /**
     * Types or pastes s at the cursor, in place of the selection. Windows line ends become "\n", a tab a space, and
     * other control characters are left out. False when not all of it fit into maxChars: as much as fits goes in.
     */
    boolean insert(String s) {
        return replace(selectionStart(), selectionEnd(), s);
    }

    /**
     * Puts s in place of text[start, end), cut to what fits, with the cursor after it. False when not all of s fit.
     */
    boolean replace(int start, int end, String s) {
        String clean = clean(s);
        int room = maxChars - (text.length() - (end - start));
        String fits = cut(clean, Math.max(0, room));
        text = text.substring(0, start) + fits + text.substring(end);
        cursor = anchor = start + fits.length();
        column = Float.NaN;
        return fits.length() == clean.length();
    }

    /** Backspace: the selection, else the character (word: the word) before the cursor. */
    void backspace(boolean word) {
        if (!hasSelection()) anchor = word ? wordLeft(cursor) : before(cursor);
        insert("");
    }

    /** Delete: the selection, else the character (word: the rest of the word) after the cursor. */
    void delete(boolean word) {
        if (!hasSelection()) anchor = word ? wordRight(cursor) : after(cursor);
        insert("");
    }

    // ------------------------------------------------------------------------------------------
    // Moving the cursor; select: extend the selection instead of dropping it
    // ------------------------------------------------------------------------------------------

    /** Left, or Ctrl+Left by a word. Without select, a selection collapses to its start. */
    void left(boolean word, boolean select) {
        if (hasSelection() && !select && !word) {
            moveTo(selectionStart(), false);
            return;
        }
        moveTo(word ? wordLeft(cursor) : before(cursor), select);
    }

    /** Right, or Ctrl+Right to the start of the next word. Without select, a selection collapses to its end. */
    void right(boolean word, boolean select) {
        if (hasSelection() && !select && !word) {
            moveTo(selectionEnd(), false);
            return;
        }
        moveTo(word ? wordRight(cursor) : after(cursor), select);
    }

    /** Home: the start of the line the cursor is on, as drawn. */
    void home(TextLayout layout, boolean select) {
        moveTo(layout.start(layout.lineOf(cursor)), select);
    }

    /** End: the end of the line the cursor is on, as drawn (before the space it was cut at). */
    void end(TextLayout layout, boolean select) {
        moveTo(layout.lastCaret(layout.lineOf(cursor)), select);
    }

    /** Ctrl+Home. */
    void toStart(boolean select) {
        moveTo(0, select);
    }

    /** Ctrl+End. */
    void toEnd(boolean select) {
        moveTo(text.length(), select);
    }

    /**
     * Up (lines -1) or Down (1): the same column on that line, as near as it has one; the column is kept over
     * shorter lines on the way. Up on the first line goes to the start of the text, Down on the last to its end.
     */
    void vertical(TextLayout layout, int lines, boolean select) {
        if (Float.isNaN(column)) column = layout.x(cursor);
        float keep = column;
        int line = layout.lineOf(cursor) + lines;
        if (line < 0) {
            moveTo(0, select);
        } else if (line >= layout.lines()) {
            moveTo(text.length(), select);
        } else {
            moveTo(layout.indexAt(line, keep), select);
        }
        column = keep;
    }

    /** A click (select: Shift+click) or a drag to index. */
    void moveTo(int index, boolean select) {
        cursor = Math.max(0, Math.min(index, text.length()));
        if (!select) anchor = cursor;
        column = Float.NaN;
    }

    /** Ctrl+A. */
    void selectAll() {
        anchor = 0;
        cursor = text.length();
        column = Float.NaN;
    }

    /** A double click: the word at index (with an apostrophe or hyphen inside, as in "Kanta's"), else nothing. */
    void selectWordAt(int index) {
        int at = Math.max(0, Math.min(index, text.length()));
        int start = at, end = at;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        anchor = start;
        cursor = end;
        column = Float.NaN;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** The index one character back, not into the middle of a surrogate pair. */
    private int before(int index) {
        if (index <= 0) return 0;
        return index >= 2 && Character.isLowSurrogate(text.charAt(index - 1)) && Character.isHighSurrogate(text.charAt(index - 2))
                ? index - 2 : index - 1;
    }

    private int after(int index) {
        if (index >= text.length()) return text.length();
        return index + 1 < text.length() && Character.isHighSurrogate(text.charAt(index)) && Character.isLowSurrogate(text.charAt(index + 1))
                ? index + 2 : index + 1;
    }

    /** Ctrl+Left: back over spaces, then over a word (or a run of punctuation). */
    private int wordLeft(int index) {
        int i = index;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        if (i > 0) {
            boolean word = isWordChar(text.charAt(i - 1));
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1)) && isWordChar(text.charAt(i - 1)) == word) i--;
        }
        return i;
    }

    /** Ctrl+Right: over the word (or run of punctuation) the cursor is in, then over the spaces after it. */
    private int wordRight(int index) {
        int i = index;
        if (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            boolean word = isWordChar(text.charAt(i));
            while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && isWordChar(text.charAt(i)) == word) i++;
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-';
    }

    /** Text as the editor holds it: "\n" line ends, spaces for tabs, no other control characters. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || !Character.isISOControl(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** s cut to at most max characters, never between the two halves of a surrogate pair. */
    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}
