package KirizsKeve.MoveByText.game;

import KirizsKeve.MoveByText.nlp.GameNames;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the names the NLP side needs from the loaded sector: factions added by other mods, and the names of star
 * systems, markets and stations. GameNames keeps them as plain data and leaves out the vanilla ones. Game thread only.
 */
public final class SectorNames {

    private SectorNames() {
    }

    /**
     * Factions the player can see in the intel tab (so not internal ones like "neutral" or "poor"), sorted by id, each
     * with its long name, short name and entity prefix ("Hegemony", as in "Hegemony patrol"); and the places.
     */
    public static GameNames read() {
        SectorAPI sector = Global.getSector();

        Map<String, List<String>> factions = new TreeMap<>();
        for (FactionAPI faction : sector.getAllFactions()) {
            if (faction == null || faction.isPlayerFaction() || !faction.isShowInIntelTab()) continue;
            List<String> names = new ArrayList<>();
            add(names, faction.getDisplayNameLong());
            add(names, faction.getDisplayName());
            add(names, faction.getEntityNamePrefix());
            factions.put(faction.getId(), names);
        }

        Set<String> places = new LinkedHashSet<>();
        for (StarSystemAPI system : sector.getStarSystems()) {
            add(places, system.getBaseName());
        }
        for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
            if (market.isHidden()) continue;
            add(places, market.getName());
            if (market.getPrimaryEntity() != null) add(places, market.getPrimaryEntity().getName());
        }
        return new GameNames(factions, places);
    }

    /**
     * The names the editor's Tab completes, as the game spells them: the factions of the intel tab (vanilla ones
     * included: long name, short name and entity prefix), and the star systems, markets and stations.
     */
    public static List<String> completionNames() {
        SectorAPI sector = Global.getSector();
        Set<String> names = new LinkedHashSet<>();
        for (FactionAPI faction : sector.getAllFactions()) {
            if (faction == null || faction.isPlayerFaction() || !faction.isShowInIntelTab()) continue;
            add(names, faction.getDisplayNameLong());
            add(names, faction.getDisplayName());
            add(names, faction.getEntityNamePrefix());
        }
        for (StarSystemAPI system : sector.getStarSystems()) add(names, system.getBaseName());
        for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
            if (market.isHidden()) continue;
            add(names, market.getName());
            if (market.getPrimaryEntity() != null) add(names, market.getPrimaryEntity().getName());
        }
        return new ArrayList<>(names);
    }

    private static void add(java.util.Collection<String> to, String name) {
        if (name != null && !name.trim().isEmpty() && !to.contains(name.trim())) to.add(name.trim());
    }
}
