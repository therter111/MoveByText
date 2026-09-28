package KirizsKeve.MoveByText;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.title.TitleScreenState;
import com.fs.state.AppDriver;
import com.fs.state.AppState;

import rolflectionlib.util.RolfLectionUtil;

public class TitleCountScreenPlugin extends BaseEveryFrameCombatPlugin {

    @Override
    public void init(CombatEngineAPI engine) {
        /** Only works when the game state is TITLE. */
        if (Global.getSettings().getCurrentState() != GameState.TITLE) return;

        /** The getScreenPanel uses the obfuscated UIPanel type, and thus must be called using reflection. */
        final Object getTitleScreenPanelMethod = RolfLectionUtil.getMethod(
                "getScreenPanel", TitleScreenState.class
        );

        final AppState title = AppDriver.getInstance().getCurrentState();
        final UIPanelAPI screen = (UIPanelAPI) RolfLectionUtil.invokeMethodDirectly(getTitleScreenPanelMethod, title);

        final CountPanel countPanel = new CountPanel();

        /** The getPanel call is needed, since CountPanel is the wrapper around the actual panel, and the plugin of that panel. */
        screen.addComponent(countPanel.getPanel()).inTL(650f, 250f);
    }
}