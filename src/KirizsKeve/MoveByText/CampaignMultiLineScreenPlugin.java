package KirizsKeve.MoveByText;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.state.AppDriver;
import com.fs.state.AppState;
import org.apache.log4j.Logger;

import rolflectionlib.util.RolfLectionUtil;

import static KirizsKeve.MoveByText.MoveByTextImpl.pollReply;

public class CampaignMultiLineScreenPlugin implements EveryFrameScript {

    /** Where the panel goes, from the top left corner of the screen; moved up and left on a screen too small for that. */
    private static final float PANEL_X = 450f;
    private static final float PANEL_Y = 450f;

    /** Adding the panel is tried on this many frames; after that something is really wrong and it gives up. */
    private static final int MAX_INJECT_ATTEMPTS = 5;

    private final Logger log = Global.getLogger(CampaignMultiLineScreenPlugin.class);

    private boolean injected = false;
    private int failedAttempts = 0;

    @Override
    public boolean isDone() {
        return false; // Keep the script alive forever
    }

    @Override
    public boolean runWhilePaused() {
        return true; // We want to process UI interactions/rendering even when paused
    }

    @Override
    public void advance(float amount) {
        // 1. Check if we are in the campaign state as per documentation
        if (Global.getSettings().getCurrentState() != GameState.CAMPAIGN) return;
        pollReply();
        // 2. Inject the panel from advance, once
        if (injected || failedAttempts >= MAX_INJECT_ATTEMPTS) return;

        try {
            // 3. Access CampaignState.getScreenPanel() via reflection as outlined in 'The UI Tree' section
            final Object getScreenPanelMethod = RolfLectionUtil.getMethod(
                    "getScreenPanel", CampaignState.class
            );

            final AppState campaignState = AppDriver.getInstance().getCurrentState();
            final UIPanelAPI screen = (UIPanelAPI) RolfLectionUtil.invokeMethodDirectly(getScreenPanelMethod, campaignState);

            // 4. Create the panel
            MultiLineTextFieldPanel editorPanel = new MultiLineTextFieldPanel(MoveByTextImpl.host());

            // 5. Inject into the root panel
            float x = Math.max(0f, Math.min(PANEL_X, Global.getSettings().getScreenWidth() - MultiLineTextFieldPanel.WIDTH));
            float y = Math.max(0f, Math.min(PANEL_Y, Global.getSettings().getScreenHeight() - MultiLineTextFieldPanel.HEIGHT));
            screen.addComponent(editorPanel.getPanel()).inTL(x, y);

            injected = true;
        } catch (Exception e) {
            failedAttempts++;
            boolean givingUp = failedAttempts >= MAX_INJECT_ATTEMPTS;
            log.error("Could not add the MoveByText editor to the campaign screen (attempt " + failedAttempts + " of "
                    + MAX_INJECT_ATTEMPTS + (givingUp ? ", giving up until the next game load" : "") + ")", e);
        }
    }
}
