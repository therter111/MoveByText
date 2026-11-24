package KirizsKeve.MoveByText;

import java.awt.Color;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The mod's panel on the campaign screen: a button that shows and hides the editor (also on a key, "panelKey" in
 * the settings), a grip next to it to drag the panel by, and the editor itself, where the player writes a message,
 * sends it, reads what came of it and OKs the fleets before they spawn. The open editor has a second grip at its
 * bottom right corner that resizes the panel ("panelSize" in the settings sets the size it starts with).
 *
 * While the editor is closed the panel is only as big as the button and the grip, so the map around them can be
 * clicked. CampaignMultiLineScreenPlugin puts the panel on the screen and takes it off while the campaign UI is
 * hidden or covered (setShown).
 */
public class MultiLineTextFieldPanel extends CustomPanel {

    /** Margin between the editor's border and what is inside it. */
    private static final float PAD = 10f;
    /** The row with the toggle button; the editor sits right under it. */
    private static final float TOGGLE_ROW_HEIGHT = 35f;
    private static final float BUTTON_HEIGHT = 25f;
    private static final float TOGGLE_WIDTH = 160f;
    /** The grip right of the toggle button that the panel is dragged by. */
    static final float GRIP_GAP = 4f;
    static final float GRIP_WIDTH = 18f;
    /** The square at the open editor's bottom right corner that resizes the panel. */
    static final float RESIZE_GRIP = 12f;

    /** The panel's size, open, unless the player or "panelSize" made it another. */
    public static final float DEFAULT_WIDTH = 720f;
    public static final float DEFAULT_HEIGHT = 435f;
    /** The smallest size: the buttons need the width, the conversation a few lines of the height. */
    static final float MIN_WIDTH = 700f;
    static final float MIN_HEIGHT = 300f;
    /** The panel's size while the editor is closed: just the button and the grip. */
    static final float CLOSED_WIDTH = TOGGLE_WIDTH + GRIP_GAP + GRIP_WIDTH;
    static final float CLOSED_HEIGHT = BUTTON_HEIGHT;

    private static final Object TOGGLE_BTN = new Object();

    private final EditorHost host;
    /** Kept by the host across save loads: open or not, where it was dragged, its size, the text, the conversation. */
    private final PanelState state;
    private final ButtonAPI toggle;
    private final String toggleText;
    /** Made again when the panel is resized. */
    private EditorPanel editorPanel;
    /** The panel's size while the editor is open. */
    private float width, height;

    /** The screen the panel is on (null until attachTo), its top left corner there, and whether it is on it now. */
    private UIPanelAPI screen;
    private float x, y;
    private boolean shown = false;
    /** While the grip is dragged: where the mouse and the panel were when it began. */
    private boolean dragging = false;
    private float dragMouseX, dragMouseY, dragStartX, dragStartY;
    /** While the corner is dragged: where the mouse was, the size it began at, and the size it would be now. */
    private boolean resizing = false;
    private float resizeMouseX, resizeMouseY, resizeStartWidth, resizeStartHeight, newWidth, newHeight;
    /** Whether the toggle button says there is news ("*"). */
    private boolean marked = false;

    /** host: the console the editor sends to (MoveByTextImpl.host() in the game). */
    MultiLineTextFieldPanel(EditorHost host) {
        super(sizeFor(host)[0], sizeFor(host)[1]);
        this.host = host;
        this.state = host.state();
        float[] size = sizeFor(host);
        this.width = size[0];
        this.height = size[1];

        // A separate tooltip for just the toggle button, at the top left of the panel
        TooltipMakerAPI toggleTooltip = getTooltip(TOGGLE_WIDTH, BUTTON_HEIGHT, false);
        toggleTooltip.setActionListenerDelegate((data, src) -> {
            if (src instanceof ButtonAPI btn && btn.getCustomData() == TOGGLE_BTN) {
                setOpen(!state.editorOpen);
            }
        });
        int key = host.panelKey();
        toggleText = "Fleet commands (" + Keyboard.getKeyName(key) + ")";
        toggle = toggleTooltip.addButton(
                toggleText, TOGGLE_BTN,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.ALL, TOGGLE_WIDTH, BUTTON_HEIGHT, 0f
        );
        toggle.setShortcut(key, false); // the key presses the button, wherever the mouse is
        add(toggleTooltip).inTL(0f, 0f);

        // A save was loaded and the panel made again: the conversation goes on, with a line between
        if (!state.entries().isEmpty()) state.addQuietly("- another save was loaded -", Misc.getGrayColor());

        // The editor is created now, but only added to the UI tree while it is shown
        editorPanel = newEditor();
        if (state.editorOpen) add(editorPanel).inTL(0f, TOGGLE_ROW_HEIGHT);
        fitSize();
    }

    /** The size the panel opens at: where the player dragged it to, else "panelSize", else the default; on the screen. */
    private static float[] sizeFor(EditorHost host) {
        float[] size = host.state().size != null ? host.state().size
                : host.startSize() != null ? host.startSize() : new float[]{DEFAULT_WIDTH, DEFAULT_HEIGHT};
        return PanelPlacement.clampSize(size[0], size[1], MIN_WIDTH, MIN_HEIGHT, screenWidth(), screenHeight());
    }

    private static float screenWidth() {
        return Global.getSettings().getScreenWidth();
    }

    private static float screenHeight() {
        return Global.getSettings().getScreenHeight();
    }

    private EditorPanel newEditor() {
        return new EditorPanel(host, width, height - TOGGLE_ROW_HEIGHT, () -> setOpen(false));
    }

    /** The panel's width and height while the editor is open, which the screen must have room for. */
    float width() {
        return width;
    }

    float height() {
        return height;
    }

    /** Puts the panel on the screen with its top left corner at (x, y). */
    void attachTo(UIPanelAPI screen, float x, float y) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        setShown(true);
    }

    /** Takes the panel off the screen, or puts it back where it was; it can't be clicked or pressed while off. */
    void setShown(boolean show) {
        if (screen == null || show == shown) return;
        shown = show;
        if (show) {
            screen.addComponent(getPanel()).inTL(x, y);
        } else {
            screen.removeComponent(getPanel());
            dragging = false;
            resizing = false;
        }
    }

    boolean isShown() {
        return shown;
    }

    /** The panel's top left corner on the screen. */
    float[] topLeft() {
        return new float[]{x, y};
    }

    private void setOpen(boolean open) {
        if (open == state.editorOpen) return;
        state.editorOpen = open;
        if (open) {
            add(editorPanel).inTL(0f, TOGGLE_ROW_HEIGHT);
            state.unseen = false;
            editorPanel.focus(); // ready to type, without a click
        } else {
            remove(editorPanel);
            resizing = false;
        }
        fitSize();
    }

    /** The whole editor while it is open; only the button and the grip while it is closed. The top left stays. */
    private void fitSize() {
        if (state.editorOpen) {
            getPos().setSize(width, height);
        } else {
            getPos().setSize(CLOSED_WIDTH, CLOSED_HEIGHT);
        }
    }

    /**
     * The panel at a new size, kept between the smallest one and the screen's edges: the editor is made again for
     * it, with the same text and conversation. The top left corner stays.
     */
    private void resizeTo(float newW, float newH) {
        float[] size = PanelPlacement.clampSize(newW, newH, MIN_WIDTH, MIN_HEIGHT, screenWidth() - x, screenHeight() - y);
        state.size = size;
        if (size[0] == width && size[1] == height) return;
        width = size[0];
        height = size[1];
        boolean open = state.editorOpen;
        if (open) remove(editorPanel);
        editorPanel = newEditor();
        if (open) {
            add(editorPanel).inTL(0f, TOGGLE_ROW_HEIGHT);
            editorPanel.focus();
        }
        fitSize();
    }

    @Override
    public void advance(float delta) {
        boolean news = !state.editorOpen && (state.unseen || !host.pendingFleets().isEmpty());
        if (news != marked) {
            marked = news;
            toggle.setText(news ? toggleText + " *" : toggleText);
        }
    }

    /**
     * Dragging by the grip (press on it, move, let go) moves the panel; dragging the open editor's corner resizes
     * it. Other input is left to the editor, the buttons and the game.
     */
    @Override
    public void processInput(List<InputEventAPI> events) {
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isMouseEvent()) continue;
            if (!dragging && !resizing && e.isLMBDownEvent() && onGrip(e)) {
                dragging = true;
                dragMouseX = e.getX();
                dragMouseY = e.getY();
                dragStartX = x;
                dragStartY = y;
                e.consume();
            } else if (!dragging && !resizing && e.isLMBDownEvent() && state.editorOpen && onCorner(e)) {
                resizing = true;
                resizeMouseX = e.getX();
                resizeMouseY = e.getY();
                resizeStartWidth = newWidth = width;
                resizeStartHeight = newHeight = height;
                e.consume();
            } else if (dragging && e.isMouseMoveEvent()) {
                // Mouse y grows upwards, the top left corner's y downwards
                moveTo(dragStartX + (e.getX() - dragMouseX), dragStartY - (e.getY() - dragMouseY));
                e.consume();
            } else if (resizing && e.isMouseMoveEvent()) {
                float[] size = PanelPlacement.clampSize(resizeStartWidth + (e.getX() - resizeMouseX),
                        resizeStartHeight - (e.getY() - resizeMouseY), MIN_WIDTH, MIN_HEIGHT,
                        screenWidth() - x, screenHeight() - y);
                newWidth = size[0];
                newHeight = size[1];
                e.consume();
            } else if (dragging && e.isLMBUpEvent()) {
                dragging = false;
                state.position = new float[]{x, y};
                e.consume();
            } else if (resizing && e.isLMBUpEvent()) {
                resizing = false;
                resizeTo(newWidth, newHeight);
                e.consume();
            }
        }
    }

    /** Whether the mouse is on the grip: right of the toggle button, as high as it. */
    private boolean onGrip(InputEventAPI e) {
        PositionAPI p = getPos();
        float left = p.getX() + TOGGLE_WIDTH + GRIP_GAP, top = p.getY() + p.getHeight();
        return e.getX() >= left && e.getX() <= left + GRIP_WIDTH && e.getY() <= top && e.getY() >= top - BUTTON_HEIGHT;
    }

    /** Whether the mouse is on the open editor's bottom right corner. */
    private boolean onCorner(InputEventAPI e) {
        PositionAPI p = getPos();
        float right = p.getX() + p.getWidth(), bottom = p.getY();
        return e.getX() <= right && e.getX() >= right - RESIZE_GRIP && e.getY() >= bottom && e.getY() <= bottom + RESIZE_GRIP;
    }

    /** Moves the panel, kept on the screen as a whole even when the editor is closed, so opening it never spills over. */
    private void moveTo(float newX, float newY) {
        float[] at = PanelPlacement.clamp(newX, newY, width, height, screenWidth(), screenHeight());
        x = at[0];
        y = at[1];
        getPos().inTL(x, y);
    }

    /** The grip: two columns of dots, right of the toggle button. */
    @Override
    public void renderBelow(float alpha) {
        PositionAPI p = getPos();
        float left = p.getX() + TOGGLE_WIDTH + GRIP_GAP, top = p.getY() + p.getHeight();
        Color c = Misc.getBasePlayerColor();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0f, 0f, 0f, 0.7f * alpha);
        GL11.glRectf(left, top - BUTTON_HEIGHT, left + GRIP_WIDTH, top);
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, (dragging ? 1f : 0.7f) * alpha);
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 4; row++) {
                float dx = left + 5f + col * 6f, dy = top - 5f - row * 5f;
                GL11.glRectf(dx, dy - 2f, dx + 2f, dy);
            }
        }
        GL11.glPopAttrib();
    }

    /** Over the open editor: the corner grip, three diagonal strokes, and while it is dragged, the new size's outline. */
    @Override
    public void render(float alpha) {
        if (!state.editorOpen) return;
        PositionAPI p = getPos();
        float right = p.getX() + p.getWidth(), bottom = p.getY(), left = p.getX(), top = p.getY() + p.getHeight();
        Color c = Misc.getBasePlayerColor();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, (resizing ? 1f : 0.7f) * alpha);
        GL11.glBegin(GL11.GL_LINES);
        for (int i = 1; i <= 3; i++) {
            float d = i * 3.5f;
            GL11.glVertex2f(right - d, bottom + 1f);
            GL11.glVertex2f(right - 1f, bottom + d);
        }
        GL11.glEnd();
        if (resizing) {
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(left, top);
            GL11.glVertex2f(left + newWidth, top);
            GL11.glVertex2f(left + newWidth, top - newHeight);
            GL11.glVertex2f(left, top - newHeight);
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    /**
     * The editor: the conversation so far, the box the message is written in (TextAreaPanel), the buttons and how
     * many characters are used. What the keys do to the text is in TextEditor, what Send and Ctrl+Up do in Composer;
     * Tab completes names, Esc closes the editor.
     *
     * One message at a time: while its answer is awaited, Send is off, Cancel is on, and the conversation ends
     * with a line saying how long it has taken so far. Fleets that wait for the player's OK turn Spawn and Discard
     * on.
     */
    private static class EditorPanel extends CustomPanel {

        private static final float BUTTON_WIDTH = 90f;
        /** Between the box and the buttons. */
        private static final float BOX_GAP = 6f;
        private static final float COUNTER_WIDTH = 100f;
        static final String HINT = "Type an order, e.g. send a small trade convoy from Jangala to Sindria";

        private static final Object SEND_BTN = new Object();
        private static final Object CLEAR_BTN = new Object();
        private static final Object FORGET_BTN = new Object();
        private static final Object CANCEL_BTN = new Object();
        private static final Object SPAWN_BTN = new Object();
        private static final Object DISCARD_BTN = new Object();

        private final float textWidth;
        private final float logHeight;
        private final TextAreaPanel textArea;
        private final ButtonAPI sendButton;
        private final ButtonAPI cancelButton;
        private final ButtonAPI spawnButton;
        private final ButtonAPI discardButton;
        private final LabelAPI counter;

        private final EditorHost host;
        private final PanelState state;
        private final TextEditor editor;
        /** Esc: the outer panel closes the editor. */
        private final Runnable onEscape;
        /** What Send sends, and Ctrl+Up and Ctrl+Down through the sent messages. */
        private final Composer composer;
        private final NameCompleter completer;
        /** The conversation as it is drawn now; replaced by a new one whenever its content changes. */
        private LogPanel logPanel;
        /** Whether the conversation changed since logPanel was built; advance() rebuilds it. */
        private boolean logChanged = true;
        /** The player sent something: the next conversation shows its end, wherever the old one was scrolled to. */
        private boolean toEnd = true;

        /** Whether the box had the keyboard on the last frame, before this frame's key presses reached it. */
        private boolean hadFocus = false;

        /** What was shown on the last frame, to redraw only what changed. */
        private boolean wasBusy = false;
        private boolean wasPending = false;
        private int shownSeconds = -1;
        private int shownCount = -1;

        EditorPanel(EditorHost host, float width, float height, Runnable onEscape) {
            super(width, height);
            this.host = host;
            this.state = host.state();
            this.editor = state.editor;
            this.onEscape = onEscape;
            this.composer = new Composer(state.history);
            this.completer = new NameCompleter(host.completionNames());
            this.textWidth = width - 2 * PAD;

            // From the bottom up: the buttons, the box above them, the conversation in what is left
            float buttonsY = height - PAD - BUTTON_HEIGHT;
            textArea = new TextAreaPanel(editor, textWidth, HINT);
            float boxY = buttonsY - BOX_GAP - textArea.height();
            this.logHeight = boxY - 2 * PAD;
            add(textArea).inTL(PAD, boxY);

            final TooltipMakerAPI buttons = getTooltip(textWidth, BUTTON_HEIGHT, false);
            buttons.setActionListenerDelegate((data, src) -> {
                if (!(src instanceof ButtonAPI btn)) return;
                Object id = btn.getCustomData();
                if (id == SEND_BTN) {
                    send();
                } else if (id == CLEAR_BTN) {
                    editor.clear();
                    textArea.focus();
                    textArea.sync();
                } else if (id == FORGET_BTN) {
                    forget();
                } else if (id == CANCEL_BTN) {
                    host.cancel();
                } else if (id == SPAWN_BTN) {
                    host.confirm();
                } else if (id == DISCARD_BTN) {
                    host.discard();
                }
            });

            sendButton = button(buttons, "Send", SEND_BTN);
            // Empties the box; Ctrl+Up still brings back what was sent
            ButtonAPI clearButton = button(buttons, "Clear", CLEAR_BTN);
            clearButton.getPosition().rightOfMid(sendButton, PAD);
            // Starts a new conversation: the model no longer sees the earlier messages
            ButtonAPI forgetButton = button(buttons, "Forget chat", FORGET_BTN);
            forgetButton.getPosition().rightOfMid(clearButton, PAD);
            // Stops waiting for the answer to the last message; only on while one is awaited
            cancelButton = button(buttons, "Cancel", CANCEL_BTN);
            cancelButton.getPosition().rightOfMid(forgetButton, PAD);
            // OK or drop the fleets of an answer; only on while some wait
            spawnButton = button(buttons, "Spawn", SPAWN_BTN);
            spawnButton.getPosition().rightOfMid(cancelButton, PAD);
            discardButton = button(buttons, "Discard", DISCARD_BTN);
            discardButton.getPosition().rightOfMid(spawnButton, PAD);
            showBusy(host.isBusy());
            showPending(!host.pendingFleets().isEmpty());
            add(buttons).inTL(PAD, buttonsY);

            // How much of the message's room is used, right of the buttons
            TooltipMakerAPI count = getTooltip(COUNTER_WIDTH, BUTTON_HEIGHT, false);
            counter = count.addPara("%s", 0f, Misc.getGrayColor(), Misc.getGrayColor(), "");
            counter.setAlignment(Alignment.RMID);
            add(count).inTL(PAD + textWidth - COUNTER_WIDTH, buttonsY + 5f);
            showCount();

            // Everything the mod tells the player from now on is listed here too
            TerminalLog.setScreenListener((message, failed) ->
                    addEntry(message, failed ? Misc.getNegativeHighlightColor() : Misc.getTextColor()));
        }

        private static ButtonAPI button(TooltipMakerAPI in, String text, Object id) {
            return in.addButton(text, id, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.ALL, BUTTON_WIDTH, BUTTON_HEIGHT, 0f);
        }

        /** Gives the box the keyboard. */
        void focus() {
            textArea.focus();
        }

        private void send() {
            switch (composer.send(editor.text(), host.isBusy())) {
                case NOTHING:
                    return;
                case WAIT:
                    addEntry("Still waiting for the answer to the last message, so this one is kept here. Press"
                            + " Cancel to stop waiting.", Misc.getGrayColor());
                    return;
                case SENT:
                    String message = composer.sent();
                    editor.clear();
                    toEnd = true;
                    addEntry("> " + message.replace("\n", "\n> "), Misc.getBasePlayerColor());
                    if (!host.submit(message)) {
                        addEntry("Not sent: the mod isn't ready yet.", Misc.getNegativeHighlightColor());
                    }
            }
        }

        private void forget() {
            host.clearHistory();
            state.clearConversation();
            toEnd = true;
            addEntry("Chat forgotten: the next message starts a new conversation.", Misc.getGrayColor());
        }

        private void addEntry(String text, Color color) {
            state.add(text, color);
            logChanged = true;
        }

        private void showBusy(boolean busy) {
            sendButton.setEnabled(!busy);
            cancelButton.setEnabled(busy);
        }

        private void showPending(boolean pending) {
            spawnButton.setEnabled(pending);
            discardButton.setEnabled(pending);
        }

        /** "742 / 1000", in the warning colors near and at the limit. */
        private void showCount() {
            int n = editor.length(), max = editor.maxChars();
            if (n == shownCount) return;
            shownCount = n;
            counter.setText(n + " / " + max);
            counter.setColor(n >= max ? Misc.getNegativeHighlightColor() : n > max * 9 / 10 ? Misc.getHighlightColor()
                    : Misc.getGrayColor());
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            // Only while the player is typing here: otherwise the keys belong to the game
            if (!hadFocus && !textArea.hasFocus()) return;
            for (InputEventAPI event : events) {
                if (!event.isKeyboardEvent() || !event.isKeyDownEvent()) continue;
                if (key(event)) event.consume();
            }
            textArea.sync();
        }

        /** One key press while typing here; true when it is the editor's (nearly all are, so the game gets none). */
        private boolean key(InputEventAPI e) {
            int key = e.getEventValue();
            if (key == host.panelKey()) return false; // the toggle button's: it closes the editor
            boolean shift = e.isShiftDown();
            // Ctrl+Alt is AltGr, which types characters such as @ on many keyboards: those are typed, not shortcuts
            boolean ctrl = e.isCtrlDown() && !e.isAltDown();
            switch (key) {
                case Keyboard.KEY_RETURN:
                case Keyboard.KEY_NUMPADENTER:
                    if (shift) {
                        editor.insert("\n");
                    } else {
                        send(); // Enter, and Ctrl+Enter as before
                    }
                    return true;
                case Keyboard.KEY_BACK:
                    editor.backspace(ctrl);
                    return true;
                case Keyboard.KEY_DELETE:
                    editor.delete(ctrl);
                    return true;
                case Keyboard.KEY_LEFT:
                    editor.left(ctrl, shift);
                    return true;
                case Keyboard.KEY_RIGHT:
                    editor.right(ctrl, shift);
                    return true;
                case Keyboard.KEY_HOME:
                    if (ctrl) editor.toStart(shift); else editor.home(textArea.layout(), shift);
                    return true;
                case Keyboard.KEY_END:
                    if (ctrl) editor.toEnd(shift); else editor.end(textArea.layout(), shift);
                    return true;
                case Keyboard.KEY_UP:
                    if (ctrl) browse(true); else editor.vertical(textArea.layout(), -1, shift);
                    return true;
                case Keyboard.KEY_DOWN:
                    if (ctrl) browse(false); else editor.vertical(textArea.layout(), 1, shift);
                    return true;
                case Keyboard.KEY_TAB:
                    complete();
                    return true;
                case Keyboard.KEY_ESCAPE:
                    onEscape.run(); // closes the editor; the text stays for when it opens again
                    return true;
                default:
                    break;
            }
            if (ctrl) {
                switch (key) {
                    case Keyboard.KEY_A:
                        editor.selectAll();
                        break;
                    case Keyboard.KEY_C:
                        copy();
                        break;
                    case Keyboard.KEY_X:
                        if (copy()) editor.insert("");
                        break;
                    case Keyboard.KEY_V:
                        paste();
                        break;
                    default:
                        break; // other Ctrl keys do nothing here, and don't reach the game either
                }
                return true;
            }
            char c = e.getEventChar();
            if (c != '\0' && !Character.isISOControl(c)) editor.insert(String.valueOf(c));
            return true;
        }

        /** Ctrl+Up (back) or Ctrl+Down through the messages sent before. */
        private void browse(boolean back) {
            String text = composer.browse(back, editor.text());
            if (text != null) editor.setText(text);
        }

        /** Tab: the name before the cursor completed, the rest of the text after it; nothing when it wouldn't fit. */
        private void complete() {
            int at = editor.cursor();
            String before = editor.text().substring(0, at);
            String done = completer.complete(before);
            if (done != null && done.length() + editor.length() - at <= editor.maxChars()) editor.replace(0, at, done);
        }

        /** Ctrl+C: the selection onto the clipboard; false when nothing is selected or it didn't work. */
        private boolean copy() {
            if (!editor.hasSelection()) return false;
            if (host.writeClipboard(editor.selected())) return true;
            addEntry("Couldn't copy: the clipboard can't be reached.", Misc.getNegativeHighlightColor());
            return false;
        }

        /** Ctrl+V: the clipboard's text at the cursor, as much as fits. */
        private void paste() {
            String text = host.readClipboard();
            if (text == null || text.isEmpty()) return;
            if (!editor.insert(text)) {
                addEntry("Pasted what fits: a message holds at most " + editor.maxChars() + " characters.",
                        Misc.getGrayColor());
            }
        }

        @Override
        public void advance(float delta) {
            hadFocus = textArea.hasFocus();
            textArea.sync();
            showCount();

            boolean busy = host.isBusy();
            if (busy != wasBusy) {
                wasBusy = busy;
                showBusy(busy);
                logChanged = true; // the waiting line comes or goes
            }
            boolean pending = !host.pendingFleets().isEmpty();
            if (pending != wasPending) {
                wasPending = pending;
                showPending(pending);
            }
            int seconds = busy ? host.waitingSeconds() : -1;
            String waiting = busy ? "Waiting for " + host.modelName() + "... " + seconds + " s" : null;

            // Here and not where the change happens: that can be in the middle of the UI handling a click or a key
            if (logChanged || logPanel == null) {
                logChanged = false;
                float offset = logPanel == null ? LogPanel.END : logPanel.offsetToKeep(toEnd);
                if (logPanel != null) remove(logPanel);
                logPanel = new LogPanel(textWidth, logHeight, state.entries(), waiting, offset);
                add(logPanel).inTL(PAD, PAD);
                toEnd = false;
                shownSeconds = seconds;
            } else if (seconds != shownSeconds) {
                // Only the count: changed in place, so a player reading further up isn't thrown to the end
                shownSeconds = seconds;
                logPanel.setWaiting(waiting);
            }
        }

        /** A dark background with a border, so the text stays readable over the campaign map. */
        @Override
        public void renderBelow(float alpha) {
            PositionAPI pos = getPos();
            float x = pos.getX(), y = pos.getY(), w = pos.getWidth(), h = pos.getHeight();
            Color border = Misc.getDarkPlayerColor();

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glColor4f(0f, 0f, 0f, 0.85f * alpha);
            GL11.glRectf(x, y, x + w, y + h);

            GL11.glColor4f(border.getRed() / 255f, border.getGreen() / 255f, border.getBlue() / 255f, alpha);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x, y + h);
            GL11.glEnd();

            GL11.glPopAttrib();
        }

        @Override public void render(float alpha) {}
    }

    /**
     * The conversation: the entries, then the waiting line while an answer is awaited. A tooltip can't take its
     * paragraphs back, so a change means a new LogPanel; only the waiting line's count is changed in place. It opens
     * at its end, or where the one before it was scrolled to.
     */
    private static class LogPanel extends CustomPanel {

        /** As the offset: scrolled to the end. */
        static final float END = -1f;

        private final ScrollPanelAPI scroller;
        /** The offset that shows the end. */
        private final float endOffset;
        private LabelAPI waitingLabel;

        /** waiting: "Waiting for ... 12 s", or null when no answer is awaited; offset: where to scroll to, or END. */
        LogPanel(float width, float height, List<PanelState.Entry> entries, String waiting, float offset) {
            super(width, height);

            TooltipMakerAPI log = getTooltip(width, height, true);
            if (entries.isEmpty() && waiting == null) {
                addLine(log, "Type an order for a fleet below and press Enter to send it. Shift+Enter starts a new"
                        + " line, Ctrl+Up and Ctrl+Down bring back earlier messages, Tab completes faction and place"
                        + " names, Esc closes this.", Misc.getGrayColor(), 0f);
            }
            for (PanelState.Entry entry : entries) {
                addLine(log, entry.text, entry.color, 5f);
            }
            if (waiting != null) {
                waitingLabel = addLine(log, waiting, Misc.getGrayColor(), 5f);
            }
            add(log).inTL(0f, 0f);

            scroller = log.getExternalScroller();
            endOffset = Math.max(0f, log.getHeightSoFar() - height);
            if (scroller != null) scroller.setYOffset(offset == END ? endOffset : Math.min(offset, endOffset));
        }

        /** The new count of the waiting line, without building the conversation again. */
        void setWaiting(String text) {
            if (waitingLabel != null && text != null) waitingLabel.setText(text);
        }

        /** Where the next LogPanel should be scrolled to: END when this one shows its end (or toEnd), else here. */
        float offsetToKeep(boolean toEnd) {
            if (toEnd || scroller == null) return END;
            float at = scroller.getYOffset();
            return at >= endOffset - 1f ? END : at;
        }

        /** The text goes in as a highlight and not as the format, so a % the player typed is just a %. */
        private static LabelAPI addLine(TooltipMakerAPI log, String text, Color color, float pad) {
            return log.addPara("%s", pad, color, color, text);
        }

        @Override public void renderBelow(float alpha) {}
        @Override public void render(float alpha) {}
        @Override public void advance(float delta) {}
        @Override public void processInput(List<InputEventAPI> events) {}
    }
}
