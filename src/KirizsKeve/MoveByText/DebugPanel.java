package KirizsKeve.MoveByText;

import java.util.List;
import java.awt.Color;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;

public class DebugPanel extends CustomPanel {

    public DebugPanel() {
        /** The panel will have a width of 100 and height of 100. */
        super(100f, 100f);

        final LabelAPI title = Global.getSettings().createLabel("Debug Panel", Fonts.INSIGNIA_LARGE);

        /** Will be added to the top left corner of the panel with a vertical and horizontal gap of 5 pixels. */
        add(title).inTL(5f, 5f);
    }

    @Override
    public void renderBelow(float alpha) {
        /** Uses the convenience method provided by CustomPanel. */
        final PositionAPI pos = pos();
        final float x = pos.getX();
        final float y = pos.getY();
        final float w = pos.getWidth();
        final float h = pos.getHeight();

        final Color green = new Color(0f, 1f, 0f, alpha * 0.3f);

        /** Standard quad draw using immediate mode. */
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glColor4f(green.getRed() / 255f,
                green.getGreen() / 255f,
                green.getBlue() / 255f,
                green.getAlpha() / 255f);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_BLEND);
    }

    /** These methods are not needed for this example, but must be implemented to satisfy the interface. */
    public void render(float alpha) {}
    public void advance(float delta) {}
    public void processInput(List<InputEventAPI> events) {}
}