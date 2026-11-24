package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What Send and Ctrl+Up / Ctrl+Down do with the message being written, without the UI, so it can be tested outside
 * the game: what a Send sends, and going back through the messages sent before. Game thread only.
 */
final class Composer {

    /** What a Send did. */
    enum Send {
        /** The message was empty: nothing happens. */
        NOTHING,
        /** The answer to the last message is still awaited: everything stays as it was. */
        WAIT,
        /** sent() is the message; the editor should be emptied. */
        SENT
    }

    private final MessageHistory history;
    /** Which sent message Ctrl+Up and Ctrl+Down are at; -1 while writing a new one. */
    private int position = -1;
    /** What was being written when Ctrl+Up first went back: Ctrl+Down past the newest gives it back. */
    private String unsent = "";
    private String sent;

    Composer(MessageHistory history) {
        this.history = history;
    }

    /** The message the text makes: its non-blank lines joined with "\n"; null when all are blank. */
    static String message(String text) {
        if (text == null) return null;
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        lines.removeIf(line -> line.trim().isEmpty());
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /** Send, Enter or Ctrl+Enter, with busy saying whether the answer to the last message is still awaited. */
    Send send(String text, boolean busy) {
        String message = message(text);
        if (message == null) return Send.NOTHING;
        if (busy) return Send.WAIT;
        sent = message;
        history.remember(message);
        position = -1;
        unsent = "";
        return Send.SENT;
    }

    /** The message of the last SENT. */
    String sent() {
        return sent;
    }

    /**
     * Ctrl+Up (back) or Ctrl+Down: the text for the editor, which holds current now. The first step back keeps
     * current, and Ctrl+Down past the newest message gives it back. null when nothing changes: no messages sent yet,
     * back at the oldest, or forward while writing a new one.
     */
    String browse(boolean back, String current) {
        if (history.size() == 0) return null;
        if (back) {
            if (position == 0) return null;
            if (position < 0) {
                unsent = current == null ? "" : current;
                position = history.size() - 1;
            } else {
                position--;
            }
            return history.get(position);
        }
        if (position < 0) return null;
        position++;
        if (position >= history.size()) {
            position = -1;
            return unsent;
        }
        return history.get(position);
    }
}
