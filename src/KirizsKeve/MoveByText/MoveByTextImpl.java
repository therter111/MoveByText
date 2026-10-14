package KirizsKeve.MoveByText;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
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
     * Scans the LLM text for tags like <SPAWN:remnants:200> and <TRADE:independent:50:Askonia>
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

            writeCommandToTerminal("SPAWN " + factionId + " " + (int) points + " FP in " + system.getName());
            try {
                FleetOnText.spawnAIFleet(factionId, points, system, spawnCoords);
            } catch (Exception e) {
                writeCommandToTerminal("SPAWN crashed: " + e);
                e.printStackTrace();
            }
        }

        // <TRADE:independent:50:Askonia> - trader fleet from the player's system to the named one
        // <TRADE:independent:50:Askonia:Jangala> - same, but starting from the named origin
        Matcher tradeMatcher = Pattern.compile("<TRADE:([^:]+):(\\d+):([^:>]+)(?::([^:>]+))?>").matcher(text);

        while (tradeMatcher.find()) {
            String factionId = tradeMatcher.group(1);
            float points = Float.parseFloat(tradeMatcher.group(2));
            String destName = tradeMatcher.group(3).trim();
            String originName = tradeMatcher.group(4) != null ? tradeMatcher.group(4).trim() : null;

            // The names come from the player's free text, so these can actually miss
            Place dest = findPlace(destName);
            if (dest == null) {
                writeCommandToTerminal("TRADE FAILED: no star system, market or planet named '" + destName + "', nothing spawned");
                continue;
            }

            LocationAPI originLocation;
            MarketAPI originMarket = null;
            if (originName != null) {
                Place origin = findPlace(originName);
                if (origin == null) {
                    writeCommandToTerminal("TRADE FAILED: no star system, market or planet named '" + originName + "' to start from, nothing spawned");
                    continue;
                }
                originLocation = origin.system;
                originMarket = origin.market;
            } else {
                originLocation = Global.getSector().getPlayerFleet().getContainingLocation();
            }

            writeCommandToTerminal("TRADE " + factionId + " " + (int) points + " freighter pts, "
                    + describe(originLocation, originMarket) + " -> " + describe(dest.system, dest.market)
                    + ", matched '" + (originName != null ? originName : "player location") + "' -> '" + destName + "'");
            try {
                FleetOnText.spawnTraderFleet(factionId, points, originLocation, originMarket, dest.system, dest.market);
            } catch (Exception e) {
                writeCommandToTerminal("TRADE crashed: " + e);
                e.printStackTrace();
            }
        }
    }

    private static void writeCommandToTerminal(String message) {
        System.out.println("=== LLM COMMAND === " + message);
        Global.getLogger(MoveByTextImpl.class).info(message);
    }

    private static String describe(LocationAPI location, MarketAPI market) {
        return location.getName() + (market != null ? " (" + market.getName() + ")" : "");
    }

    private static class Place {
        final StarSystemAPI system;
        final MarketAPI market; // null when only a system or a market-less planet matched

        Place(StarSystemAPI system, MarketAPI market) {
            this.system = system;
            this.market = market;
        }
    }

    /**
     * Resolves a star system, market/station or planet name to a trade origin or destination.
     * Exact matches win; a second pass accepts names the LLM cut short ("Chicomoz" -> Chicomoztoc).
     */
    private static Place findPlace(String name) {
        name = name.replaceAll("(?i)\\s+(star\\s+)?system$", "").trim();

        for (boolean prefix : new boolean[]{false, true}) {
            for (StarSystemAPI system : Global.getSector().getStarSystems()) {
                if (nameMatches(system.getBaseName(), name, prefix) || nameMatches(system.getName(), name, prefix)) {
                    return new Place(system, null);
                }
            }
            for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
                if (market.isHidden() || market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
                if (nameMatches(market.getName(), name, prefix) || nameMatches(market.getPrimaryEntity().getName(), name, prefix)) {
                    return new Place(market.getStarSystem(), market);
                }
            }
            for (StarSystemAPI system : Global.getSector().getStarSystems()) {
                for (PlanetAPI planet : system.getPlanets()) {
                    if (!planet.isStar() && nameMatches(planet.getName(), name, prefix)) {
                        return new Place(system, null);
                    }
                }
            }
        }
        return null;
    }

    private static boolean nameMatches(String candidate, String name, boolean allowPrefix) {
        if (candidate == null) return false;
        String c = candidate.toLowerCase();
        String n = name.toLowerCase();
        return c.equals(n) || (allowPrefix && n.length() >= 4 && c.startsWith(n));
    }

    public static void clearLlmHistory() {
        if (llm != null) llm.clearHistory();
    }
}