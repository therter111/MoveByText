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
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The mod's panel on the campaign screen: a button that shows and hides the editor (also on a key, "panelKey" in
 * the settings), a grip next to it to drag the panel by, and the editor itself, where the player types a message,
 * sends it, reads what came of it and OKs the fleets before they spawn.
 *
 * While the editor is closed the panel is only as big as the button and the grip, so the map around them can be
 * clicked. CampaignMultiLineScreenPlugin puts the panel on the screen and takes it off while the campaign UI is
 * hidden or covered (setShown).
 */
public class MultiLineTextFieldPanel extends CustomPanel {

    /** Margin between the editor's border and what is inside it. */
    private static final float PAD = 10f;
    /** How wide the text field and the conversation are: wide enough for a long order on one line. */
    private static final float TEXT_WIDTH = 700f;
    /** The row with the toggle button; the editor sits right under it. */
    private static final float TOGGLE_ROW_HEIGHT = 35f;
    private static final float EDITOR_HEIGHT = 400f;
    private static final float BUTTON_HEIGHT = 25f;
    private static final float TOGGLE_WIDTH = 160f;
    /** The grip right of the toggle button that the panel is dragged by. */
    static final float GRIP_GAP = 4f;
    static final float GRIP_WIDTH = 18f;

    public static final float WIDTH = TEXT_WIDTH + 2 * PAD;
    public static final float HEIGHT = TOGGLE_ROW_HEIGHT + EDITOR_HEIGHT;
    /** The panel's size while the editor is closed: just the button and the grip. */
    static final float CLOSED_WIDTH = TOGGLE_WIDTH + GRIP_GAP + GRIP_WIDTH;
    static final float CLOSED_HEIGHT = BUTTON_HEIGHT;

    private static final Object TOGGLE_BTN = new Object();

    private final EditorHost host;
    /** Kept by the host across save loads: open or not, where it was dragged, the conversation, the sent messages. */
    private final PanelState state;
    private final ButtonAPI toggle;
    private final String toggleText;
    private final EditorPanel editorPanel;

    /** The screen the panel is on (null until attachTo), its top left corner there, and whether it is on it now. */
    private UIPanelAPI screen;
    private float x, y;
    private boolean shown = false;
    /** While the grip is dragged: where the mouse and the panel were when it began. */
    private boolean dragging = false;
    private float dragMouseX, dragMouseY, dragStartX, dragStartY;
    /** Whether the toggle button says there is news ("*"). */
    private boolean marked = false;

    /** host: the console the editor sends to (MoveByTextImpl.host() in the game). */
    MultiLineTextFieldPanel(EditorHost host) {
        super(WIDTH, HEIGHT);
        this.host = host;
        this.state = host.state();

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
        editorPanel = new EditorPanel(host, () -> setOpen(false));
        if (state.editorOpen) add(editorPanel).inTL(0f, TOGGLE_ROW_HEIGHT);
        fitSize();
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
        }
        fitSize();
    }

    /** The whole editor while it is open; only the button and the grip while it is closed. The top left stays. */
    private void fitSize() {
        if (state.editorOpen) {
            getPos().setSize(WIDTH, HEIGHT);
        } else {
            getPos().setSize(CLOSED_WIDTH, CLOSED_HEIGHT);
        }
    }

    @Override
    public void advance(float delta) {
        boolean news = !state.editorOpen && (state.unseen || !host.pendingFleets().isEmpty());
        if (news != marked) {
            marked = news;
            toggle.setText(news ? toggleText + " *" : toggleText);
        }
    }

    /** Dragging by the grip: press on it, move, let go. Other input is left to the buttons and the game. */
    @Override
    public void processInput(List<InputEventAPI> events) {
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isMouseEvent()) continue;
            if (!dragging && e.isLMBDownEvent() && onGrip(e)) {
                dragging = true;
                dragMouseX = e.getX();
                dragMouseY = e.getY();
                dragStartX = x;
                dragStartY = y;
                e.consume();
            } else if (dragging && e.isMouseMoveEvent()) {
                // Mouse y grows upwards, the top left corner's y downwards
                moveTo(dragStartX + (e.getX() - dragMouseX), dragStartY - (e.getY() - dragMouseY));
                e.consume();
            } else if (dragging && e.isLMBUpEvent()) {
                dragging = false;
                state.position = new float[]{x, y};
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

    /** Moves the panel, kept on the screen as a whole even when the editor is closed, so opening it never spills over. */
    private void moveTo(float newX, float newY) {
        float[] at = PanelPlacement.clamp(newX, newY, WIDTH, HEIGHT,
                Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight());
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

    @Override public void render(float alpha) {}

    /**
     * The editor: the conversation so far, the text field for the current line, and the buttons. Starsector's text
     * field holds one line, so Shift+Enter moves the line up as a draft and Backspace on an empty field takes the
     * last one back; Enter sends. Up and Down bring back earlier messages, Tab completes names, Esc closes it.
     *
     * One message at a time: while its answer is awaited, Send is off, Cancel is on, and the conversation ends
     * with a line saying how long it has taken so far. Fleets that wait for the player's OK turn Spawn and Discard
     * on. What the keys and Send do to the text is in Composer.
     */
    private static class EditorPanel extends CustomPanel {

        private static final float LOG_HEIGHT = 285f;
        private static final float INPUT_HEIGHT = EDITOR_HEIGHT - LOG_HEIGHT - 3 * PAD;
        private static final float BUTTON_WIDTH = 100f;
        /** The longest message the field takes; the long ones of LlmPromptTest are 50 to 100 words. */
        static final int MAX_CHARS = 1000;

        private static final Object SEND_BTN = new Object();
        private static final Object FORGET_BTN = new Object();
        private static final Object CANCEL_BTN = new Object();
        private static final Object SPAWN_BTN = new Object();
        private static final Object DISCARD_BTN = new Object();

        private final TextFieldAPI textField;
        private final ButtonAPI sendButton;
        private final ButtonAPI cancelButton;
        private final ButtonAPI spawnButton;
        private final ButtonAPI discardButton;

        private final EditorHost host;
        private final PanelState state;
        /** Esc: the outer panel closes the editor. */
        private final Runnable onEscape;
        /** The message being written: the lines above the text field, and Up and Down through the sent ones. */
        private final Composer composer;
        private final NameCompleter completer;
        /** The conversation as it is drawn now; replaced by a new one whenever its content changes. */
        private LogPanel logPanel;
        /** Whether the conversation changed since logPanel was built; advance() rebuilds it. */
        private boolean logChanged = true;
        /** The player typed or sent: the next conversation shows its end, wherever the old one was scrolled to. */
        private boolean toEnd = true;

        /** The field's text on the last frame: the field handles a key before processInput() sees it. */
        private String lastText = "";
        /** Whether the text field had the keyboard on the last frame, before this frame's key presses reached it. */
        private boolean hadFocus = false;

        /** What was shown on the last frame, to redraw only what changed. */
        private boolean wasBusy = false;
        private boolean wasPending = false;
        private int shownSeconds = -1;

        EditorPanel(EditorHost host, Runnable onEscape) {
            super(WIDTH, EDITOR_HEIGHT);
            this.host = host;
            this.state = host.state();
            this.onEscape = onEscape;
            this.composer = new Composer(state.history);
            this.completer = new NameCompleter(host.completionNames());

            final TooltipMakerAPI input = getTooltip(TEXT_WIDTH, INPUT_HEIGHT, false);
            textField = input.addTextField(TEXT_WIDTH, 0f);
            textField.setMaxChars(MAX_CHARS);
            textField.setLimitByStringWidth(false); // a long order goes on past the field's width
            textField.setHandleCtrlV(true); // paste
            textField.setUndoOnEscape(false); // Esc closes the editor and keeps the text

            input.setActionListenerDelegate((data, src) -> {
                if (!(src instanceof ButtonAPI btn)) return;
                Object id = btn.getCustomData();
                if (id == SEND_BTN) {
                    send();
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

            sendButton = button(input, "Send", SEND_BTN, 8f);
            // Starts a new conversation: the model no longer sees the earlier messages
            ButtonAPI forgetButton = button(input, "Forget chat", FORGET_BTN, 0f);
            forgetButton.getPosition().rightOfMid(sendButton, PAD);
            // Stops waiting for the answer to the last message; only on while one is awaited
            cancelButton = button(input, "Cancel", CANCEL_BTN, 0f);
            cancelButton.getPosition().rightOfMid(forgetButton, PAD);
            // OK or drop the fleets of an answer; only on while some wait
            spawnButton = button(input, "Spawn", SPAWN_BTN, 0f);
            spawnButton.getPosition().rightOfMid(cancelButton, PAD);
            discardButton = button(input, "Discard", DISCARD_BTN, 0f);
            discardButton.getPosition().rightOfMid(spawnButton, PAD);
            showBusy(host.isBusy());
            showPending(!host.pendingFleets().isEmpty());

            add(input).inTL(PAD, PAD + LOG_HEIGHT + PAD);

            // Everything the mod tells the player from now on is listed here too
            TerminalLog.setScreenListener((message, failed) ->
                    addEntry(message, failed ? Misc.getNegativeHighlightColor() : Misc.getTextColor()));
        }

        private static ButtonAPI button(TooltipMakerAPI in, String text, Object id, float pad) {
            return in.addButton(text, id, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.ALL, BUTTON_WIDTH, BUTTON_HEIGHT, pad);
        }

        /** Gives the text field the keyboard. */
        void focus() {
            textField.grabFocus();
        }

        private void send() {
            switch (composer.send(textField.getText(), host.isBusy())) {
                case NOTHING:
                    return;
                case WAIT:
                    addEntry("Still waiting for the answer to the last message, so this one is kept here. Press"
                            + " Cancel to stop waiting.", Misc.getGrayColor());
                    return;
                case SENT:
                    String message = composer.sent();
                    textField.setText("");
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

        /** The draft changed (or the field's text from Up/Down): show the end, where it is. */
        private void draftChanged() {
            logChanged = true;
            toEnd = true;
        }

        private void showBusy(boolean busy) {
            sendButton.setEnabled(!busy);
            cancelButton.setEnabled(busy);
        }

        private void showPending(boolean pending) {
            spawnButton.setEnabled(pending);
            discardButton.setEnabled(pending);
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            // Only while the player is typing here: otherwise Enter, Esc and the arrows belong to the game
            if (!hadFocus && !textField.hasFocus()) return;

            for (InputEventAPI event : events) {
                if (!event.isKeyboardEvent() || !event.isKeyDownEvent()) continue;
                int key = event.getEventValue();

                if (key == Keyboard.KEY_RETURN && event.isShiftDown()) {
                    composer.newLine(textField.getText());
                    textField.setText("");
                    draftChanged();
                    event.consume();
                } else if (key == Keyboard.KEY_RETURN) {
                    send(); // Enter, and Ctrl+Enter as before
                    event.consume();
                } else if (key == Keyboard.KEY_BACK && lastText.isEmpty() && !composer.draft().isEmpty()) {
                    textField.setText(composer.takeBackLine());
                    draftChanged();
                    event.consume();
                } else if (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN) {
                    String text = composer.browse(key == Keyboard.KEY_UP);
                    if (text != null) {
                        textField.setText(text);
                        draftChanged();
                    }
                    event.consume();
                } else if (key == Keyboard.KEY_TAB) {
                    String completed = completer.complete(textField.getText());
                    if (completed != null) textField.setText(completed);
                    event.consume();
                } else if (key == Keyboard.KEY_ESCAPE) {
                    onEscape.run(); // closes the editor; the text stays for when it opens again
                    event.consume();
                }
            }
        }

        @Override
        public void advance(float delta) {
            lastText = textField.getText();
            hadFocus = textField.hasFocus();

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
                logPanel = new LogPanel(state.entries(), waiting, composer.draft(), offset);
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
            // The line between the conversation and the text field
            float lineY = y + h - PAD - LOG_HEIGHT - PAD / 2f;
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(x + PAD, lineY);
            GL11.glVertex2f(x + w - PAD, lineY);
            GL11.glEnd();

            GL11.glPopAttrib();
        }

        @Override public void render(float alpha) {}
    }

    /**
     * The conversation: the entries, the waiting line while an answer is awaited, then the lines of the message being
     * written. A tooltip can't take its paragraphs back, so a change means a new LogPanel; only the waiting line's
     * count is changed in place. It opens at its end, or where the one before it was scrolled to.
     */
    private static class LogPanel extends CustomPanel {

        /** As the offset: scrolled to the end. */
        static final float END = -1f;

        private final ScrollPanelAPI scroller;
        /** The offset that shows the end. */
        private final float endOffset;
        private LabelAPI waitingLabel;

        /** waiting: "Waiting for ... 12 s", or null when no answer is awaited; offset: where to scroll to, or END. */
        LogPanel(List<PanelState.Entry> entries, String waiting, List<String> draftLines, float offset) {
            super(TEXT_WIDTH, EditorPanel.LOG_HEIGHT);

            TooltipMakerAPI log = getTooltip(TEXT_WIDTH, EditorPanel.LOG_HEIGHT, true);
            if (entries.isEmpty() && draftLines.isEmpty() && waiting == null) {
                addLine(log, "Type an order for a fleet below and press Enter to send it. Shift+Enter starts a new"
                        + " line, Up and Down bring back earlier messages, Tab completes faction and place names, Esc"
                        + " closes this.", Misc.getGrayColor(), 0f);
            }
            for (PanelState.Entry entry : entries) {
                addLine(log, entry.text, entry.color, 5f);
            }
            if (waiting != null) {
                waitingLabel = addLine(log, waiting, Misc.getGrayColor(), 5f);
            }
            if (!draftLines.isEmpty()) {
                addLine(log, "Your message so far (Enter sends it):", Misc.getGrayColor(), 10f);
                addLine(log, String.join("\n", draftLines), Misc.getHighlightColor(), 3f);
            }
            add(log).inTL(0f, 0f);

            scroller = log.getExternalScroller();
            endOffset = Math.max(0f, log.getHeightSoFar() - EditorPanel.LOG_HEIGHT);
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
