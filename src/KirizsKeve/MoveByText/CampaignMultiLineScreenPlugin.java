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

    /** Adding the panel is tried on this many frames; after that something is really wrong and it gives up. */
    private static final int MAX_INJECT_ATTEMPTS = 5;

    private final Logger log = Global.getLogger(CampaignMultiLineScreenPlugin.class);

    private boolean injected = false;
    private int failedAttempts = 0;
    private MultiLineTextFieldPanel panel;

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
        if (injected) {
            // Off the screen while the UI is hidden (H), a dialog or menu is open, or a core tab (map, intel...)
            boolean show = shouldShow(Global.getSector().getCampaignUI());
            if (show != panel.isShown()) {
                log.info("MoveByText panel " + (show ? "shown again" : "hidden while the campaign UI is hidden or covered"));
                panel.setShown(show);
            }
            return;
        }
        // 2. Inject the panel from advance, once
        if (failedAttempts >= MAX_INJECT_ATTEMPTS) return;

        try {
            // 3. Access CampaignState.getScreenPanel() via reflection as outlined in 'The UI Tree' section
            final Object getScreenPanelMethod = RolfLectionUtil.getMethod(
                    "getScreenPanel", CampaignState.class
            );

            final AppState campaignState = AppDriver.getInstance().getCurrentState();
            final UIPanelAPI screen = (UIPanelAPI) RolfLectionUtil.invokeMethodDirectly(getScreenPanelMethod, campaignState);

            // 4. Create the panel
            EditorHost host = MoveByTextImpl.host();
            panel = new MultiLineTextFieldPanel(host);

            // 5. Inject into the root panel: where it was dragged to, else panelPosition, else the left edge; on screen
            float[] at = host.state().position != null ? host.state().position
                    : host.startPosition() != null ? host.startPosition() : PanelPlacement.defaultTopLeft();
            at = PanelPlacement.clamp(at[0], at[1], panel.width(), panel.height(),
                    Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight());
            panel.attachTo(screen, at[0], at[1]);

            injected = true;
        } catch (Exception e) {
            failedAttempts++;
            boolean givingUp = failedAttempts >= MAX_INJECT_ATTEMPTS;
            log.error("Could not add the MoveByText editor to the campaign screen (attempt " + failedAttempts + " of "
                    + MAX_INJECT_ATTEMPTS + (givingUp ? ", giving up until the next game load" : "") + ")", e);
        }
    }

    /** Whether the panel belongs on the screen now: not while the UI is hidden or covered by a dialog, menu or tab. */
    static boolean shouldShow(com.fs.starfarer.api.campaign.CampaignUIAPI ui) {
        if (ui == null) return true;
        return !ui.isHideUI() && !ui.isShowingDialog() && !ui.isShowingMenu() && ui.getCurrentCoreTab() == null;
    }
}
