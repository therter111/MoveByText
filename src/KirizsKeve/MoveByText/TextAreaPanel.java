package KirizsKeve.MoveByText;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.List;

/**
 * The box the player writes in: a TextEditor's text, wrapped to the box's width (TextLayout), with the cursor, the
 * selection, and a hint while it is empty. The game's text field holds one line and can't show this, so the box
 * draws the text itself, one label per line it shows; more lines scroll. A game text field the size of the box,
 * invisible and always empty, only holds the keyboard focus, so the game keeps its hotkeys off while the player
 * types and knows when they click elsewhere.
 *
 * The mouse is handled here (click, Shift+click, drag, double click, the wheel); the keys are the editor panel's,
 * which knows what Enter, Tab and Ctrl+Up do. Call sync() after changing the editor. Game thread only.
 */
final class TextAreaPanel extends CustomPanel {

    /** How many lines of text the box shows; more scroll. */
    static final int VISIBLE_LINES = 5;
    /** From the box's border to the text. */
    static final float INSET = 6f;
    /** Room right of the text for the cursor after a full line. */
    private static final float CURSOR_ROOM = 4f;
    /** The labels are this much wider than the text, so the game never wraps a line the layout didn't. */
    private static final float LABEL_SLACK = 60f;
    /** The cursor is on, then off, this long. */
    static final float BLINK_SECONDS = 0.5f;
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private final TextEditor editor;
    private final float width;
    private final float height;
    private final float lineHeight;
    /** How wide a line of text may be. */
    private final float wrapWidth;
    private final String hint;
    private final TextFieldAPI focusField;
    private final LabelAPI[] lineLabels = new LabelAPI[VISIBLE_LINES];
    /** What each line label shows now, to change only the ones that changed. */
    private final String[] shownLines = new String[VISIBLE_LINES];
    private final LabelAPI hintLabel;
    private boolean hintShown = true;
    /** The width of "|", measured with the text, so spaces at the end of a piece count: see measure(). */
    private final float barWidth;

    private TextLayout layout;
    /** The first line shown. */
    private int firstLine = 0;
    /** The text and cursor sync() last saw: when either changed, the box scrolls to the cursor. */
    private String syncedText = null;
    private int syncedCursor = -1;
    /** Time into the cursor's blink, from 0 to twice BLINK_SECONDS. */
    private float blink = 0f;
    /** The left button went down in the box and hasn't come up: moving the mouse selects. */
    private boolean selecting = false;

    /** A box width pixels wide, as tall as VISIBLE_LINES lines need, showing hint while editor is empty. */
    TextAreaPanel(TextEditor editor, float width, String hint) {
        super(width, 100f); // the height is set below, once a label has measured the font
        this.editor = editor;
        this.width = width;
        this.hint = hint;
        this.wrapWidth = width - 2 * INSET - CURSOR_ROOM;

        TooltipMakerAPI text = getTooltip(wrapWidth + LABEL_SLACK, VISIBLE_LINES * 40f, false);
        for (int i = 0; i < VISIBLE_LINES; i++) {
            lineLabels[i] = text.addPara("%s", 0f, Misc.getTextColor(), Misc.getTextColor(), " ");
            shownLines[i] = " ";
        }
        hintLabel = text.addPara("%s", 0f, Misc.getGrayColor(), Misc.getGrayColor(), hint);
        barWidth = lineLabels[0].computeTextWidth("|");
        lineHeight = Math.max(16f, (float) Math.ceil(lineLabels[0].computeTextHeight("Ag")) + 3f);
        for (int i = 0; i < VISIBLE_LINES; i++) lineLabels[i].getPosition().inTL(0f, i * lineHeight);
        hintLabel.getPosition().inTL(0f, 0f);

        this.height = VISIBLE_LINES * lineHeight + 2 * INSET;
        getPos().setSize(width, height);

        // Under the text: the field that holds the keyboard focus, as big as the box so a click anywhere is on it
        TooltipMakerAPI holder = getTooltip(width, height, false);
        focusField = holder.addTextField(width, height, Global.getSettings().getString("defaultFont"), 0f);
        focusField.setMaxChars(PanelState.MAX_CHARS);
        focusField.setLimitByStringWidth(false);
        focusField.setHandleCtrlV(false); // the editor pastes itself
        focusField.setUndoOnEscape(false); // Esc closes the editor
        focusField.setColor(TRANSPARENT);
        focusField.setBgColor(TRANSPARENT);
        focusField.setBorderColor(TRANSPARENT);
        focusField.hideCursor();
        add(holder).inTL(0f, 0f);
        add(text).inTL(INSET, INSET);

        sync();
    }

    float height() {
        return height;
    }

    float lineHeight() {
        return lineHeight;
    }

    /** The first line shown, for the tests. */
    int firstLine() {
        return firstLine;
    }

    /** Whether the player is typing here: the focus field has the keyboard. */
    boolean hasFocus() {
        return focusField.hasFocus();
    }

    /** Gives the box the keyboard. */
    void focus() {
        focusField.grabFocus();
        blink = 0f;
    }

    /** The editor's text as the box wraps it now. */
    TextLayout layout() {
        if (layout == null || !layout.text().equals(editor.text())) {
            layout = TextLayout.of(editor.text(), wrapWidth, this::measure);
        }
        return layout;
    }

    /** How wide text is drawn. "|" goes after it and is taken off again, so spaces at its end count too. */
    private float measure(String s) {
        if (s.isEmpty()) return 0f;
        return lineLabels[0].computeTextWidth(s + "|") - barWidth;
    }

    /**
     * Brings the box up to date with the editor: the lines it shows, the hint, and the scroll, which follows the
     * cursor when the text or the cursor moved. Empties the focus field, which the game types into too.
     */
    void sync() {
        if (!focusField.getText().isEmpty()) focusField.setText("");
        TextLayout l = layout();
        if (!editor.text().equals(syncedText) || editor.cursor() != syncedCursor) {
            int line = l.lineOf(editor.cursor());
            if (line < firstLine) firstLine = line;
            if (line >= firstLine + VISIBLE_LINES) firstLine = line - VISIBLE_LINES + 1;
            syncedText = editor.text();
            syncedCursor = editor.cursor();
            blink = 0f;
        }
        firstLine = Math.max(0, Math.min(firstLine, l.lines() - VISIBLE_LINES));
        for (int i = 0; i < VISIBLE_LINES; i++) {
            int line = firstLine + i;
            String shown = line < l.lines() ? l.line(line) : "";
            if (!shown.equals(shownLines[i])) {
                lineLabels[i].setText(shown);
                shownLines[i] = shown;
            }
        }
        boolean showHint = editor.length() == 0;
        if (showHint != hintShown) {
            hintLabel.setText(showHint ? hint : "");
            hintShown = showHint;
        }
    }

    // ------------------------------------------------------------------------------------------
    // The mouse
    // ------------------------------------------------------------------------------------------

    @Override
    public void processInput(List<InputEventAPI> events) {
        for (InputEventAPI e : events) {
            if (!e.isMouseEvent()) continue;
            if (e.isLMBDownEvent()) {
                if (!contains(e.getX(), e.getY())) continue;
                int index = indexAt(e.getX(), e.getY());
                if (e.isDoubleClick()) {
                    editor.selectWordAt(index);
                    selecting = false;
                } else {
                    editor.moveTo(index, e.isShiftDown());
                    selecting = true;
                }
                focus();
                e.consume(); // not through to the map: a click here mustn't move the fleet
            } else if (selecting && e.isMouseMoveEvent()) {
                editor.moveTo(indexAt(e.getX(), e.getY()), true);
                e.consume();
            } else if (selecting && e.isLMBUpEvent()) {
                selecting = false;
                e.consume();
            } else if (e.isMouseScrollEvent() && contains(e.getX(), e.getY())) {
                // Up the wheel shows earlier lines; the cursor stays where it is
                firstLine += e.getEventValue() > 0 ? -1 : 1;
                firstLine = Math.max(0, Math.min(firstLine, layout().lines() - VISIBLE_LINES));
                e.consume();
            }
        }
        sync();
    }

    private boolean contains(float x, float y) {
        PositionAPI p = getPos();
        return x >= p.getX() && x <= p.getX() + width && y >= p.getY() && y <= p.getY() + height;
    }

    /** The text index nearest to a point on the screen: its line by height, then the nearest gap on it. */
    private int indexAt(float x, float y) {
        PositionAPI p = getPos();
        float textTop = p.getY() + height - INSET;
        int row = (int) Math.floor((textTop - y) / lineHeight);
        TextLayout l = layout();
        int line = Math.max(0, Math.min(firstLine + row, l.lines() - 1));
        return l.indexAt(line, x - (p.getX() + INSET));
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    @Override
    public void advance(float delta) {
        blink = (blink + delta) % (2 * BLINK_SECONDS);
    }

    /** The box, its border (brighter while typing here) and the selection, under the text. */
    @Override
    public void renderBelow(float alpha) {
        PositionAPI p = getPos();
        float x = p.getX(), y = p.getY();
        Color border = hasFocus() ? Misc.getBasePlayerColor() : Misc.getDarkPlayerColor();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glColor4f(0f, 0f, 0f, 0.6f * alpha);
        GL11.glRectf(x, y, x + width, y + height);

        if (editor.hasSelection()) {
            Color c = Misc.getDarkPlayerColor();
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.9f * alpha);
            TextLayout l = layout();
            int from = editor.selectionStart(), to = editor.selectionEnd();
            for (int row = 0; row < VISIBLE_LINES && firstLine + row < l.lines(); row++) {
                int line = firstLine + row;
                int start = Math.max(from, l.start(line)), end = Math.min(to, l.end(line));
                boolean pastEnd = to > l.end(line) && from <= l.end(line); // the line break is selected too
                if (start > end || (start == end && !pastEnd)) continue;
                float left = x + INSET + l.xOnLine(line, start);
                float right = x + INSET + l.xOnLine(line, end) + (pastEnd ? 4f : 0f);
                float top = y + height - INSET - row * lineHeight;
                GL11.glRectf(left, top - lineHeight, right, top);
            }
        }

        GL11.glColor4f(border.getRed() / 255f, border.getGreen() / 255f, border.getBlue() / 255f, alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    /** The cursor, over the text, blinking while the player types here. */
    @Override
    public void render(float alpha) {
        if (!hasFocus() || blink >= BLINK_SECONDS) return;
        TextLayout l = layout();
        int row = l.lineOf(editor.cursor()) - firstLine;
        if (row < 0 || row >= VISIBLE_LINES) return;
        PositionAPI p = getPos();
        float cx = p.getX() + INSET + l.x(editor.cursor());
        float top = p.getY() + height - INSET - row * lineHeight;
        Color c = Misc.getTextColor();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, alpha);
        GL11.glRectf(cx, top - lineHeight + 2f, cx + 1.5f, top - 1f);
        GL11.glPopAttrib();
    }

    /** Whether the cursor is drawn at this moment, for the tests. */
    boolean cursorShown() {
        return hasFocus() && blink < BLINK_SECONDS;
    }
}
