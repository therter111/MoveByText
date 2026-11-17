package KirizsKeve.MoveByText;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The mod's panel on the campaign screen: a button that shows and hides the editor (also on a key, "panelKey" in
 * the settings), and the editor itself, where the player types a message of one or more lines, sends it, and reads
 * what came of it.
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

    public static final float WIDTH = TEXT_WIDTH + 2 * PAD;
    public static final float HEIGHT = TOGGLE_ROW_HEIGHT + EDITOR_HEIGHT;

    private static final Object TOGGLE_BTN = new Object();

    /** Kept by the host across save loads: whether the editor is open, and the sent messages. */
    private final PanelState state;
    private final EditorPanel editorPanel;

    /** host: the console the editor sends to (MoveByTextImpl.host() in the game). */
    MultiLineTextFieldPanel(EditorHost host) {
        super(WIDTH, HEIGHT);

        // A separate tooltip for just the toggle button, at the top left of the panel
        TooltipMakerAPI toggleTooltip = getTooltip(WIDTH, TOGGLE_ROW_HEIGHT, false);
        toggleTooltip.setActionListenerDelegate((data, src) -> {
            if (src instanceof ButtonAPI btn && btn.getCustomData() == TOGGLE_BTN) {
                onToggleClicked();
            }
        });
        this.state = host.state();
        int key = host.panelKey();
        ButtonAPI toggle = toggleTooltip.addButton(
                "Fleet commands (" + Keyboard.getKeyName(key) + ")", TOGGLE_BTN,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.ALL, TOGGLE_WIDTH, BUTTON_HEIGHT, 0f
        );
        toggle.setShortcut(key, false); // the key presses the button, wherever the mouse is
        add(toggleTooltip).inTL(0f, 0f);

        // The editor is created now, but only added to the UI tree while it is shown
        editorPanel = new EditorPanel(host);
        if (state.editorOpen) add(editorPanel).inTL(0f, TOGGLE_ROW_HEIGHT);
    }

    private void onToggleClicked() {
        state.editorOpen = !state.editorOpen;

        if (state.editorOpen) {
            add(editorPanel).inTL(0f, TOGGLE_ROW_HEIGHT);
        } else {
            remove(editorPanel);
        }
    }

    @Override public void renderBelow(float alpha) {}
    @Override public void render(float alpha) {}
    @Override public void advance(float delta) {}
    @Override public void processInput(List<InputEventAPI> events) {}

    /** One line of the conversation: what the player sent, or what the mod answered. */
    private static final class Entry {
        final String text;
        final Color color;

        Entry(String text, Color color) {
            this.text = text;
            this.color = color;
        }
    }

    /**
     * The editor: the conversation so far, the text field for the current line, and the buttons. Starsector's text
     * field holds one line, so Enter moves the line up into the conversation as a draft and Backspace on an empty
     * field takes the last one back. Up and Down bring back earlier messages.
     *
     * One message at a time: while its answer is awaited, Send is off, Cancel is on, and the conversation ends
     * with a line saying how long it has taken so far. What the keys and Send do to the text is in Composer.
     */
    private static class EditorPanel extends CustomPanel {

        private static final float LOG_HEIGHT = 285f;
        private static final float INPUT_HEIGHT = EDITOR_HEIGHT - LOG_HEIGHT - 3 * PAD;
        private static final float BUTTON_WIDTH = 100f;
        /** Older entries than this many are dropped from the conversation. */
        private static final int MAX_ENTRIES = 60;

        private static final Object SEND_BTN = new Object();
        private static final Object FORGET_BTN = new Object();
        private static final Object CANCEL_BTN = new Object();


        private final TextFieldAPI textField;
        private final ButtonAPI sendButton;
        private final ButtonAPI cancelButton;

        /** What was sent and what the mod said about it, oldest first. */
        private final List<Entry> entries = new ArrayList<>();
        private final EditorHost host;
        /** The message being written: the lines above the text field, and Up and Down through the sent ones. */
        private final Composer composer;
        /** The conversation as it is drawn now; replaced by a new one whenever its content changes. */
        private LogPanel logPanel;
        /** Whether the conversation changed since logPanel was built; advance() rebuilds it. */
        private boolean logChanged = true;

        /** The field's text on the last frame: the field handles a key before processInput() sees it. */
        private String lastText = "";
        /** Whether the text field had the keyboard on the last frame, before this frame's key presses reached it. */
        private boolean hadFocus = false;

        /** Whether an answer was awaited on the last frame, and for how many seconds, to redraw when that changes. */
        private boolean wasBusy = false;
        private int shownSeconds = -1;

        EditorPanel(EditorHost host) {
            super(WIDTH, EDITOR_HEIGHT);
            this.host = host;
            this.composer = new Composer(host.state().history);

            final TooltipMakerAPI input = getTooltip(TEXT_WIDTH, INPUT_HEIGHT, false);
            textField = input.addTextField(TEXT_WIDTH, 0f);

            input.setActionListenerDelegate((data, src) -> {
                if (!(src instanceof ButtonAPI btn)) return;
                if (btn.getCustomData() == SEND_BTN) {
                    send();
                } else if (btn.getCustomData() == FORGET_BTN) {
                    forget();
                } else if (btn.getCustomData() == CANCEL_BTN) {
                    host.cancel();
                }
            });

            sendButton = input.addButton(
                    "Send", SEND_BTN,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.ALL, BUTTON_WIDTH, BUTTON_HEIGHT, 8f
            );
            // Starts a new conversation: the model no longer sees the earlier messages
            ButtonAPI forgetButton = input.addButton(
                    "Forget chat", FORGET_BTN,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.ALL, BUTTON_WIDTH, BUTTON_HEIGHT, 0f
            );
            forgetButton.getPosition().rightOfMid(sendButton, PAD);
            // Stops waiting for the answer to the last message; only on while one is awaited
            cancelButton = input.addButton(
                    "Cancel", CANCEL_BTN,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.ALL, BUTTON_WIDTH, BUTTON_HEIGHT, 0f
            );
            cancelButton.getPosition().rightOfMid(forgetButton, PAD);
            showBusy(host.isBusy());

            add(input).inTL(PAD, PAD + LOG_HEIGHT + PAD);

            // Everything the mod tells the player from now on is listed here too
            TerminalLog.setScreenListener((message, failed) ->
                    addEntry(message, failed ? Misc.getNegativeHighlightColor() : Misc.getTextColor()));
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
                    addEntry("> " + message.replace("\n", "\n> "), Misc.getBasePlayerColor());
                    if (!host.submit(message)) {
                        addEntry("Not sent: the mod isn't ready yet.", Misc.getNegativeHighlightColor());
                    }
            }
        }

        private void forget() {
            host.clearHistory();
            entries.clear();
            addEntry("Chat forgotten: the next message starts a new conversation.", Misc.getGrayColor());
        }

        private void addEntry(String text, Color color) {
            entries.add(new Entry(text, color));
            if (entries.size() > MAX_ENTRIES) entries.remove(0);
            logChanged = true;
        }

        private void showBusy(boolean busy) {
            sendButton.setEnabled(!busy);
            cancelButton.setEnabled(busy);
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
            // Only while the player is typing here: otherwise Enter and Backspace belong to the game
            if (!hadFocus && !textField.hasFocus()) return;

            for (InputEventAPI event : events) {
                if (!event.isKeyboardEvent() || !event.isKeyDownEvent()) continue;
                int key = event.getEventValue();

                if (key == Keyboard.KEY_RETURN && event.isCtrlDown()) {
                    send();
                    event.consume();
                } else if (key == Keyboard.KEY_RETURN) {
                    composer.newLine(textField.getText());
                    textField.setText("");
                    logChanged = true;
                    event.consume();
                } else if (key == Keyboard.KEY_BACK && lastText.isEmpty() && !composer.draft().isEmpty()) {
                    textField.setText(composer.takeBackLine());
                    logChanged = true;
                    event.consume();
                } else if (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN) {
                    String text = composer.browse(key == Keyboard.KEY_UP);
                    if (text != null) {
                        textField.setText(text);
                        logChanged = true;
                    }
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
                logChanged = true;
            }
            int seconds = busy ? host.waitingSeconds() : -1;
            if (seconds != shownSeconds) {
                shownSeconds = seconds;
                logChanged = true;
            }

            // Here and not where the change happens: that can be in the middle of the UI handling a click or a key
            if (logChanged) {
                logChanged = false;
                if (logPanel != null) remove(logPanel);
                String waiting = busy ? "Waiting for " + host.modelName() + "... " + seconds + " s" : null;
                logPanel = new LogPanel(entries, waiting, composer.draft());
                add(logPanel).inTL(PAD, PAD);
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
     * The conversation, scrolled to its end: the entries, the waiting line while an answer is awaited, then the
     * lines of the message being written. A tooltip can't take its paragraphs back, so a change means a new LogPanel.
     */
    private static class LogPanel extends CustomPanel {

        /** waiting: "Waiting for ... 12 s", or null when no answer is awaited. */
        LogPanel(List<Entry> entries, String waiting, List<String> draftLines) {
            super(TEXT_WIDTH, EditorPanel.LOG_HEIGHT);

            TooltipMakerAPI log = getTooltip(TEXT_WIDTH, EditorPanel.LOG_HEIGHT, true);
            if (entries.isEmpty() && draftLines.isEmpty() && waiting == null) {
                addLine(log, "Type an order for a fleet below. Enter starts a new line, Ctrl+Enter or the Send button"
                        + " sends the message, Up and Down bring back earlier ones.", Misc.getGrayColor(), 0f);
            }
            for (Entry entry : entries) {
                addLine(log, entry.text, entry.color, 5f);
            }
            if (waiting != null) {
                addLine(log, waiting, Misc.getGrayColor(), 5f);
            }
            if (!draftLines.isEmpty()) {
                addLine(log, "Not sent yet:", Misc.getGrayColor(), 10f);
                addLine(log, String.join("\n", draftLines), Misc.getHighlightColor(), 3f);
            }
            add(log).inTL(0f, 0f);

            // The newest lines are the ones to see
            ScrollPanelAPI scroller = log.getExternalScroller();
            if (scroller != null) {
                scroller.setYOffset(Math.max(0f, log.getHeightSoFar() - EditorPanel.LOG_HEIGHT));
            }
        }

        /** The text goes in as a highlight and not as the format, so a % the player typed is just a %. */
        private static void addLine(TooltipMakerAPI log, String text, Color color, float pad) {
            log.addPara("%s", pad, color, color, text);
        }

        @Override public void renderBelow(float alpha) {}
        @Override public void render(float alpha) {}
        @Override public void advance(float delta) {}
        @Override public void processInput(List<InputEventAPI> events) {}
    }
}
