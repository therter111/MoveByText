package KirizsKeve.MoveByText.nlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The conversation so far, so follow-up messages have context. Keeps at most maxMessages messages and drops the
 * oldest user/reply pair at a time, so it always starts with a user message. Not thread-safe: the backend only
 * touches it from its worker thread.
 */
public final class ChatHistory {

    /** One message: who said it ("user", "model", "assistant") and what. */
    public static final class Turn {
        private final String role;
        private final String text;

        Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }

        public String role() {
            return role;
        }

        public String text() {
            return text;
        }
    }

    private final int maxMessages;
    private final List<Turn> turns = new ArrayList<Turn>();

    public ChatHistory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void add(String role, String text) {
        turns.add(new Turn(role, text));
        while (turns.size() > maxMessages) {
            turns.remove(0);
            if (!turns.isEmpty()) turns.remove(0);
        }
    }

    /** Oldest first. */
    public List<Turn> turns() {
        return Collections.unmodifiableList(turns);
    }

    public void clear() {
        turns.clear();
    }
}
