package KirizsKeve.MoveByText;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

/** A custom UI panel that is its own plugin: a subclass builds its content and overrides the callbacks it needs. */
public abstract class CustomPanel implements CustomUIPanelPlugin {

    protected final CustomPanelAPI mPanel;

    public CustomPanel(float width, float height) {
        mPanel = Global.getSettings().createCustom(width, height, this);
    }

    public void buttonPressed(Object buttonID) {}
    public void positionChanged(PositionAPI position) {}

    public CustomPanelAPI getPanel() { return mPanel; }

    public PositionAPI getPos() { return mPanel.getPosition(); }

    public TooltipMakerAPI getTooltip(float width, float height, boolean withScroller) {
        return mPanel.createUIElement(width, height, withScroller);
    }

    /** Note: the tooltip must be created using {@link #getTooltip}. */
    public PositionAPI add(TooltipMakerAPI a) {
        return mPanel.addUIElement(a);
    }

    public PositionAPI add(CustomPanel a) {
        mPanel.addComponent(a.getPanel());

        return a.getPos();
    }

    public void remove(CustomPanel a) {
        mPanel.removeComponent(a.getPanel());
    }
}
