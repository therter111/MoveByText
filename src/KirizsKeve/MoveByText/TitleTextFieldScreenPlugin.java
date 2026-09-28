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

public class TitleTextFieldScreenPlugin extends BaseEveryFrameCombatPlugin {

    @Override
    public void init(CombatEngineAPI engine) {
        if (Global.getSettings().getCurrentState() != GameState.TITLE) return;

        final Object getTitleScreenPanelMethod = RolfLectionUtil.getMethod(
                "getScreenPanel", TitleScreenState.class
        );

        final AppState title = AppDriver.getInstance().getCurrentState();
        final UIPanelAPI screen = (UIPanelAPI) RolfLectionUtil.invokeMethodDirectly(getTitleScreenPanelMethod, title);

        final TextFieldPanel textPanel = new TextFieldPanel();

        /** Places the text field panel near the top left corner of the screen */
        screen.addComponent(textPanel.getPanel()).inTL(350f, 350f);
    }
}