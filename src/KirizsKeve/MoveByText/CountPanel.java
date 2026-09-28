package KirizsKeve.MoveByText;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.ActionListenerDelegate;
import com.fs.starfarer.api.util.Misc;

public class CountPanel extends CustomPanel {
    /** Unique Id object */
    private static final Object COUNT_BTN = new Object();

    private int count = 0;

    public CountPanel() {
        super(200f, 80f);

        /** Internally calls mPanel.createUIElement(width, height, withScroller); */
        final TooltipMakerAPI content = getTooltip(200f, 80f, false);

        content.addTitle("Counter", Misc.getBasePlayerColor());

        final LabelAPI counterLabel = content.addPara(getCountStr(), 5f);

        content.setButtonFontOrbitron20();
        content.setActionListenerDelegate(new ActionListenerDelegate() {
            @Override
            public void actionPerformed(Object data, Object src) {

                /** Used to identify which button was clicked. Useful for multiple buttons that share the same listener. */
                if (src instanceof ButtonAPI btn && btn.getCustomData() == COUNT_BTN) {
                    count++;
                    final String txt = getCountStr();
                    counterLabel.setText(txt);
                    /** If the width of the new text is higher, the text will be wrapped around to the next line. So resize. */
                    counterLabel.autoSizeToWidth(counterLabel.computeTextWidth(txt));
                }
            }
        });

        /** The second param is the custom data. */
        content.addButton("Increment", COUNT_BTN, Misc.getButtonTextColor(), Global.getSettings().getColor("buttonBgDark"),
                Alignment.MID, CutStyle.ALL, 70f, 28f, 10f);

        /** The addButton method adds the Button to the tooltip using addCustom already. */
        add(content);
    }

    private final String getCountStr() {
        return "Clicks: " + Integer.toString(count);
    }

    @Override public void renderBelow(float alpha) {}
    @Override public void render(float alpha) {}
    @Override public void processInput(List<InputEventAPI> events) {}
    @Override public void advance(float delta) {}
}