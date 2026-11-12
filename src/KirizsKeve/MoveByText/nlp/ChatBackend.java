package KirizsKeve.MoveByText.nlp;

import java.util.List;

/**
 * A backend that talks to a language model and keeps the conversation: every exchange ask() completes is
 * remembered and sent along with the next request. askNow() remembers nothing, so test calls stay independent.
 */
public abstract class ChatBackend extends AbstractBackend {

    /** Max number of stored messages (user + model together). */
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final ChatHistory history = new ChatHistory(MAX_HISTORY_MESSAGES);

    /** What the model's messages are called in this API: "model" for Gemini, "assistant" for ChatML. */
    private final String replyRole;

    protected ChatBackend(int readTimeoutMs, String replyRole) {
        super(readTimeoutMs);
        this.replyRole = replyRole;
    }

    /** The conversation so far, oldest first. */
    protected final List<ChatHistory.Turn> history() {
        return history.turns();
    }

    @Override
    protected final void answered(String userText, String reply) {
        history.add("user", userText);
        history.add(replyRole, reply);
    }

    /** Forgets the conversation, after the requests asked before it. */
    @Override
    public final void clearHistory() {
        onWorker(new Runnable() {
            @Override
            public void run() {
                history.clear();
            }
        });
    }
}
