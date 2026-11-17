package KirizsKeve.MoveByText;

/**
 * What the editor panel keeps while the game runs, when a save is loaded and the panel is made again: whether the
 * editor is open, and the messages Up and Down go back through. MoveByTextImpl holds one for the whole run.
 * Game thread only.
 */
final class PanelState {

    /** How many sent messages Up and Down go back through. */
    static final int MAX_HISTORY = 30;

    boolean editorOpen = false;
    final MessageHistory history = new MessageHistory(MAX_HISTORY);
}
