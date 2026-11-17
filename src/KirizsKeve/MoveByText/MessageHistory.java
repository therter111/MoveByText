package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.List;

/**
 * The messages the player sent, oldest first, for Up and Down in the editor. Keeps at most max; the same message
 * sent twice in a row is kept once. Game thread only.
 */
final class MessageHistory {

    private final int max;
    private final List<String> sent = new ArrayList<>();

    MessageHistory(int max) {
        this.max = max;
    }

    void remember(String message) {
        if (!sent.isEmpty() && sent.get(sent.size() - 1).equals(message)) return;
        sent.add(message);
        if (sent.size() > max) sent.remove(0);
    }

    int size() {
        return sent.size();
    }

    /** 0 is the oldest. */
    String get(int index) {
        return sent.get(index);
    }
}
