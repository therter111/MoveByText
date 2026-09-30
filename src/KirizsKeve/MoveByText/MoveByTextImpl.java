package KirizsKeve.MoveByText;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class MoveByTextImpl extends BaseModPlugin {
    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
        //throw new RuntimeException("Mod loaded and working!");
    }

    @Override
    public void onNewGame() {
        super.onNewGame();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Add the script to the sector so it runs its advance() method every frame.
        // We use Transient so it doesn't get serialized into the save XML.
        Global.getSector().addTransientScript(new CampaignMultiLineScreenPlugin());
    }

    public static void writeSubmittedTextToTerminal(String text) {
        // 1. Prints to the terminal / command line
        System.out.println("=== NEW MESSAGE FROM UI ===");
        System.out.println(text);
        System.out.println("===========================\n");

    }



}
