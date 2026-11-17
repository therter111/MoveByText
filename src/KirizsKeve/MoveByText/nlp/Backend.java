package KirizsKeve.MoveByText.nlp;

/**
 * What the game needs from whatever reads the player's text: send it, get command tags back. Provider picks the
 * implementation from the settings file. Gemini and KoboldCpp are language models, Rasa and spaCy are not; every
 * provider just turns text into command tags.
 */
public interface Backend {

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

    /**
     * Done with this backend, e.g. because a game load read new settings: a request that is running still finishes
     * and answers, but new ones get an error. Does nothing by default.
     */
    default void close() {
    }

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
