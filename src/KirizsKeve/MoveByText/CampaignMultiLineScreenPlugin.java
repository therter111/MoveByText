package KirizsKeve.MoveByText;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.campaign.CampaignState;
import com.fs.state.AppDriver;
import com.fs.state.AppState;

import rolflectionlib.util.RolfLectionUtil;

public class CampaignMultiLineScreenPlugin implements EveryFrameScript {

    private boolean injected = false;
    private MultiLineTextFieldPanel editorPanel;

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

        // 2. Use the 'advance' injection pattern from the documentation:
        // "the advance method is more fit to inject the button, as it can constantly
        // check for the presence of the injected panel, and inject it if missing."
        if (injected) return;

        try {
            // 3. Access CampaignState.getScreenPanel() via reflection as outlined in 'The UI Tree' section
            final Object getScreenPanelMethod = RolfLectionUtil.getMethod(
                    "getScreenPanel", CampaignState.class
            );

            final AppState campaignState = AppDriver.getInstance().getCurrentState();
            final UIPanelAPI screen = (UIPanelAPI) RolfLectionUtil.invokeMethodDirectly(getScreenPanelMethod, campaignState);

            // 4. Create the panel (No changes made to MultiLineTextFieldPanel code)
            editorPanel = new MultiLineTextFieldPanel();

            // 5. Inject into the root panel at the requested coordinates
            screen.addComponent(editorPanel.getPanel()).inTL(450f, 450f);

            injected = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}