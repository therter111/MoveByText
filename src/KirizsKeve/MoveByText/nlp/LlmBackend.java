package KirizsKeve.MoveByText.nlp;

/**
 * What the game needs from whatever reads the player's text: send it, get command tags back. Provider picks the
 * implementation from the settings file. The name says LLM, but the Rasa and spaCy providers use no language
 * model; every provider just turns text into command tags.
 */
public interface LlmBackend {

    interface Callback {
        /** Called on a background thread when the model answered. */
        void onResponse(String text);

        /** Called on a background thread when anything went wrong. */
        void onError(String message);
    }

    /** False when required settings are missing; ask() then reports which ones instead of sending anything. */
    boolean isConfigured();

    /** Sends one user message. Returns immediately; the answer arrives through the callback on a background thread. */
    void ask(String userText, Callback callback);

    /** Blocking request without history. For tests only - never call this from the game thread. */
    String askNow(String userText) throws Exception;

    /** Forgets the conversation so far. */
    void clearHistory();

    /** The model that answers, for logs and test reports. */
    String modelName();

    /**
     * The model the server says it has loaded, e.g. "koboldcpp/Qwen3.5-9B-Q6_K", so test runs with different models
     * can be told apart. Blocking, for tests and tools. Backends that can't ask give modelName().
     */
    default String loadedModel() throws Exception {
        return modelName();
    }
}
