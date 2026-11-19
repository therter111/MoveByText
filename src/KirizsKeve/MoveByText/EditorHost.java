package KirizsKeve.MoveByText;

import java.util.List;

/**
 * What the editor panel needs from the rest of the mod: the console it sends to, the names it completes, and what
 * it keeps between save loads. In the game MoveByTextImpl.host() is it; the offline tests give the panel a fake one.
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

    /** The fleets of the last answer that wait for the player's OK, in words; empty when none do. */
    List<String> pendingFleets();

    /** Spawns the fleets that wait for the player's OK. */
    void confirm();

    /** Drops them: nothing is spawned. */
    void discard();

    /** The names Tab completes: the running game's factions, star systems, markets and stations. */
    List<String> completionNames();

    /** The key that opens and closes the editor (an LWJGL key code). */
    int panelKey();

    /** "panelPosition" from the settings, the top left corner the panel starts at; null for the default. */
    float[] startPosition();

    /** What the panel keeps when a save is loaded and it is made again. */
    PanelState state();
}
