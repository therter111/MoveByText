package KirizsKeve.MoveByText;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the editor panel keeps while the game runs, when a save is loaded and the panel is made again: whether the
 * editor is open, where the player dragged the panel and how big they made it, the message being written, the
 * conversation, and the messages Ctrl+Up and Ctrl+Down go back through. MoveByTextImpl holds one for the whole run.
 * Game thread only.
 */
final class PanelState {

    /** How many sent messages Ctrl+Up and Ctrl+Down go back through. */
    static final int MAX_HISTORY = 30;
    /** The longest message the editor takes; the long ones of LlmPromptTest are 50 to 100 words. */
    static final int MAX_CHARS = 1000;
    /** Older conversation lines than this many are dropped. */
    static final int MAX_ENTRIES = 60;

    /** One line of the conversation: what the player sent, or what the mod said. */
    static final class Entry {
        final String text;
        final Color color;

        Entry(String text, Color color) {
            this.text = text;
            this.color = color;
        }
    }

    boolean editorOpen = false;
    /** Where the player dragged the panel: its top left corner, in screen pixels from the top left; null if never. */
    float[] position = null;
    /** The size the player dragged the panel to, width and height in pixels; null if never. */
    float[] size = null;
    /** Something came into the conversation while the editor was closed: the toggle button shows it. */
    boolean unseen = false;
    final MessageHistory history = new MessageHistory(MAX_HISTORY);
    /** The message being written, with its cursor: kept when the panel is resized or made again. */
    final TextEditor editor = new TextEditor(MAX_CHARS);
    private final List<Entry> entries = new ArrayList<>();

    /** A line for the conversation; while the editor is closed, the toggle button then shows there is news. */
    void add(String text, Color color) {
        addQuietly(text, color);
        if (!editorOpen) unseen = true;
    }

    /** The same, without telling the toggle button: for the mod's own notes, such as "another save was loaded". */
    void addQuietly(String text, Color color) {
        entries.add(new Entry(text, color));
        if (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    void clearConversation() {
        entries.clear();
    }

    /** The conversation, oldest first. */
    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }
}
