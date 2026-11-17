package KirizsKeve.MoveByText;

/**
 * What the editor panel needs from the rest of the mod: the console it sends to, and what it keeps between save
 * loads. In the game MoveByTextImpl.host() is it; the offline tests give the panel a fake one.
 * Game thread only.
 */
interface EditorHost {

    /** Sends the message; false when nothing was sent (empty, or still waiting for the last answer). */
    boolean submit(String message);

    /** Whether the answer to the last message is still awaited. */
    boolean isBusy();

    /** How long the answer has taken so far, in whole seconds. */
    int waitingSeconds();

    /** Stops waiting for the answer; it is ignored when it comes. */
    void cancel();

    /** Who answers, for the waiting line. */
    String modelName();

    /** Forgets the conversation. */
    void clearHistory();

    /** The key that opens and closes the editor (an LWJGL key code). */
    int panelKey();

    /** What the panel keeps when a save is loaded and it is made again. */
    PanelState state();
}
