package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the editor's keys and Send button do, without the UI, so it can be tested outside the game. Starsector's
 * text field holds one line, so the lines above it (the draft) are kept here; the panel shows the draft and puts
 * the text returned here into the field. Game thread only.
 */
final class Composer {

    /** What a Send did. */
    enum Send {
        /** The message was empty: nothing happens. */
        NOTHING,
        /** The answer to the last message is still awaited: everything stays as it was. */
        WAIT,
        /** sent() is the message; the draft is cleared, so the field should be emptied. */
        SENT
    }

    private final MessageHistory history;
    /** The lines of the message being written, above the one in the field. */
    private final List<String> draft = new ArrayList<>();
    /** Which history message Up and Down are at; -1 while writing a new one. */
    private int position = -1;
    private String sent;

    Composer(MessageHistory history) {
        this.history = history;
    }

    /** Enter: the field's line goes up into the draft. The field should then be emptied. */
    void newLine(String field) {
        draft.add(field);
    }

    /** Backspace on an empty field: the last draft line, to go back into the field; null when there is none. */
    String takeBackLine() {
        return draft.isEmpty() ? null : draft.remove(draft.size() - 1);
    }

    /** The message the draft and the field make: their non-blank lines joined with "\n"; null when all are blank. */
    String message(String field) {
        List<String> lines = new ArrayList<>(draft);
        lines.add(field);
        lines.removeIf(line -> line == null || line.trim().isEmpty());
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** Send or Ctrl+Enter, with busy saying whether the answer to the last message is still awaited. */
    Send send(String field, boolean busy) {
        String message = message(field);
        if (message == null) return Send.NOTHING;
        if (busy) return Send.WAIT;
        sent = message;
        history.remember(message);
        draft.clear();
        position = -1;
        return Send.SENT;
    }

    /** The message of the last SENT. */
    String sent() {
        return sent;
    }

    /**
     * Up (back) or Down: the text for the field, with the draft set to the other lines of that earlier message;
     * Down past the newest gives an empty editor again. null when nothing changes (no history, or Down while
     * writing a new message).
     */
    String browse(boolean back) {
        if (history.size() == 0) return null;
        if (back) {
            position = position < 0 ? history.size() - 1 : Math.max(0, position - 1);
            return load(history.get(position));
        }
        if (position < 0) return null;
        position++;
        if (position >= history.size()) {
            position = -1;
            return load("");
        }
        return load(history.get(position));
    }

    /** The draft lines, oldest first. */
    List<String> draft() {
        return Collections.unmodifiableList(draft);
    }

    /** Puts a message into the editor: its first lines as the draft; returns the last one, for the field. */
    private String load(String message) {
        List<String> lines = new ArrayList<>(Arrays.asList(message.split("\n", -1)));
        String last = lines.remove(lines.size() - 1);
        draft.clear();
        draft.addAll(lines);
        return last;
    }
}
