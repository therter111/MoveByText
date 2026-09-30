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
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        if (Global.getSettings().getCurrentState() != GameState.CAMPAIGN) return;
        if (injected) return;

        try {
            final Object getScreenPanelMethod = RolfLectionUtil.getMethod(
                    "getScreenPanel", CampaignState.class
            );

            final AppState campaignState = AppDriver.getInstance().getCurrentState();
            final UIPanelAPI screen = (UIPanelAPI) RolfLectionUtil.invokeMethodDirectly(getScreenPanelMethod, campaignState);

            editorPanel = new MultiLineTextFieldPanel();

            screen.addComponent(editorPanel.getPanel()).inTL(450f, 450f);

            injected = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}