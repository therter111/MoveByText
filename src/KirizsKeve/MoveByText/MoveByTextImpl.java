package KirizsKeve.MoveByText;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MoveByTextImpl extends BaseModPlugin {

    private static LlmClient llm;
    private static final Queue<String> llmReplies = new ConcurrentLinkedQueue<String>();

    @Override
    public void onApplicationLoad() throws Exception {
        super.onApplicationLoad();
    }

    @Override
    public void onNewGame() {
        super.onNewGame();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (llm == null) {
            llm = new LlmClient();
        }
        Global.getSector().addTransientScript(new CampaignMultiLineScreenPlugin());
    }

    public static void writeSubmittedTextToTerminal(String text) {
        System.out.println("=== NEW MESSAGE FROM UI ===");
        System.out.println(text);
        System.out.println("===========================\n");
        Global.getLogger(MoveByTextImpl.class).info("UI Text Submitted:\n" + text);
        askLlm(text);
    }

    public static void writeLlmReplyToTerminal(String reply) {
        System.out.println("=== LLM REPLY ===");
        System.out.println(reply);
        System.out.println("=================\n");
        Global.getLogger(MoveByTextImpl.class).info("LLM reply:\n" + reply);
        llmReplies.add(reply);
    }

    private static void askLlm(String text) {
        final Logger log = Global.getLogger(MoveByTextImpl.class);

        if (llm == null || text == null || text.trim().isEmpty()) return;

        log.info("LLM: sending message to Gemini...");
        llm.ask(text, new LlmClient.Callback() {
            @Override
            public void onResponse(String reply) {
                writeLlmReplyToTerminal(reply);
            }

            @Override
            public void onError(String message) {
                log.error("LLM error: " + message);
                llmReplies.add("[LLM error] " + message);
            }
        });
    }

    public static String pollLlmReply() {
        String reply = llmReplies.poll();
        if (reply != null) {
            processAnySpawnCommands(reply);
        }
        return reply;
    }

    /**
     * Scans the LLM text for tags like <SPAWN:remnants:200>
     * Assumes the LLM is always correct, skips error handling/validation.
     */
    private static void processAnySpawnCommands(String text) {
        Pattern pattern = Pattern.compile("<SPAWN:([^:]+):(\\d+)>");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String factionId = matcher.group(1);
            float points = Float.parseFloat(matcher.group(2));

            LocationAPI system = Global.getSector().getPlayerFleet().getContainingLocation();
            Vector2f playerCoords = Global.getSector().getPlayerFleet().getLocation();

            // Offset by 600 units
            Vector2f spawnCoords = new Vector2f(playerCoords.x + 600f, playerCoords.y + 600f);

            FleetOnText.spawnAIFleet(factionId, points, system, spawnCoords);
        }
    }

    public static void clearLlmHistory() {
        if (llm != null) llm.clearHistory();
    }
}