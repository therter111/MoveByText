package KirizsKeve.MoveByText;

import java.util.HashSet;
import java.util.Set;

/**
 * Checks that text is strict JSON, as Python's json module reads it. Starsector's reader and org.json are lenient
 * (trailing commas, single quotes, unquoted words), so a settings file the game reads can still break a Python
 * script, e.g. nlp/rasa/test_handwritten.py, which reads llmdata.json.example with json.loads. A key twice in the
 * same object counts as a mistake too: Python keeps the last one without a word.
 */
final class StrictJson {

    private final String s;
    private int i = 0;

    private StrictJson(String s) {
        this.s = s;
    }

    /** Throws IllegalArgumentException, with the line and what was wrong, unless text is one strict JSON value. */
    static void check(String text) {
        StrictJson p = new StrictJson(text);
        p.space();
        p.value();
        p.space();
        if (p.i < p.s.length()) throw p.error("text after the end of the JSON value");
    }

    private void value() {
        if (i >= s.length()) throw error("a value is missing");
        char c = s.charAt(i);
        if (c == '{') object();
        else if (c == '[') array();
        else if (c == '"') string();
        else if (c == '-' || (c >= '0' && c <= '9')) number();
        else if (!word("true") && !word("false") && !word("null")) throw error("not a JSON value: '" + c + "'");
    }

    private void object() {
        i++; // {
        space();
        if (peek('}')) {
            i++;
            return;
        }
        Set<String> keys = new HashSet<>();
        while (true) {
            space();
            if (!peek('"')) throw error(peek('}') ? "a comma before '}'" : "a key must be a string in double quotes");
            int start = i;
            string();
            String key = s.substring(start + 1, i - 1);
            if (!keys.add(key)) throw error("the key \"" + key + "\" twice in one object");
            space();
            expect(':');
            space();
            value();
            space();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return;
        }
    }

    private void array() {
        i++; // [
        space();
        if (peek(']')) {
            i++;
            return;
        }
        while (true) {
            space();
            if (peek(']')) throw error("a comma before ']'");
            value();
            space();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return;
        }
    }

    private void string() {
        i++; // "
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return;
            if (c < 0x20) throw error("a control character (e.g. a line break) inside a string");
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                if (e == 'u') {
                    if (i + 4 > s.length() || !s.substring(i, i + 4).matches("[0-9a-fA-F]{4}")) throw error("a bad \\u escape");
                    i += 4;
                } else if ("\"\\/bfnrt".indexOf(e) < 0) {
                    throw error("a bad escape \\" + e);
                }
            }
        }
        throw error("a string that never ends");
    }

    private void number() {
        int start = i;
        if (peek('-')) i++;
        if (peek('0')) {
            i++;
        } else if (i < s.length() && Character.isDigit(s.charAt(i))) {
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        } else {
            throw error("a bad number");
        }
        if (peek('.')) {
            i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("a bad number");
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        if (peek('e') || peek('E')) {
            i++;
            if (peek('+') || peek('-')) i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("a bad number");
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        if (i == start) throw error("a bad number");
    }

    private boolean word(String w) {
        if (!s.startsWith(w, i)) return false;
        i += w.length();
        return true;
    }

    private void space() {
        while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String what) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
        return new IllegalArgumentException("line " + line + ": " + what);
    }
}
