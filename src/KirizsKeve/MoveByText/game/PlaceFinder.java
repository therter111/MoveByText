package KirizsKeve.MoveByText.game;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

/** Finds the star system, market/station or planet a command names, in the current sector. Game thread only. */
public final class PlaceFinder {

    /**
     * Resolves a star system, market/station or planet name to a trade origin, destination or HELP target, or null.
     * Exact matches win; a second pass accepts names the LLM cut short ("Chicomoz" -> Chicomoztoc).
     */
    public Place find(String name) {
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
}
