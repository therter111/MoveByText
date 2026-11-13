package KirizsKeve.MoveByText.game;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/** Creates the fleets the commands ask for, gives them their orders and reports how it went. Game thread only. */
public final class FleetSpawner {

    // How long a HELP fleet stays at its target before heading home
    private static final float HELP_DAYS = 30f;
    // How long a SPAWN fleet patrols before it leaves, so spawned fleets don't pile up in the save
    private static final float SPAWN_DAYS = 90f;

    private final TerminalLog log = new TerminalLog("FLEET SPAWN", FleetSpawner.class);

    public void spawnAIFleet(String factionId, float combatPoints, LocationAPI location, Vector2f coordinates) {

        FleetParamsV3 params = new FleetParamsV3(
                null,
                coordinates,
                factionId,
                null,
                FleetTypes.PATROL_LARGE,
                combatPoints,
                0f, 0f, 0f, 0f, 0f, 0f
        );
        params.ignoreMarketFleetSizeMult = true;

        // Generate the fleet, blindly trusting the factionId is correct
        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (isEmpty(fleet, "SPAWN", factionId)) return;

        fleet.setName(spawnedName(fleet, "Patrol"));

        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_JUMP, true);

        location.addEntity(fleet);
        fleet.setLocation(coordinates.x, coordinates.y);

        SectorEntityToken patrolToken = location.createToken(coordinates.x, coordinates.y);
        fleet.addAssignment(
                FleetAssignment.PATROL_SYSTEM,
                patrolToken,
                SPAWN_DAYS,
                "patrolling the sector"
        );
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, patrolToken, 1000f, "standing down");

        verifySpawn(fleet, location, "SPAWN", "patrolling " + location.getName());
    }

    // sourceMarket / destMarket may be null, then a market in that location is picked (never the same one twice).
    // start is where the fleet appears when localSystem has no market to start from, e.g. the player's position
    // in hyperspace; null means the center of localSystem.
    public void spawnTraderFleet(String factionId, float fleetPoints, LocationAPI localSystem, MarketAPI sourceMarket,
                                 Vector2f start, StarSystemAPI targetSystem, MarketAPI destMarket) {

        if (sourceMarket != null && sourceMarket == destMarket) {
            log.fail("TRADE FAILED: the convoy would start and end at " + sourceMarket.getName() + ", nothing spawned");
            return;
        }
        if (destMarket == null) destMarket = pickMarket(targetSystem, factionId, sourceMarket);
        if (sourceMarket == null) sourceMarket = pickMarket(localSystem, factionId, destMarket);
        if (start == null) start = new Vector2f();

        Vector2f locInHyper = localSystem instanceof StarSystemAPI
                ? ((StarSystemAPI) localSystem).getLocation()
                : start; // in hyperspace the start already is a hyperspace position

        FleetParamsV3 params = new FleetParamsV3(
                sourceMarket,
                locInHyper,
                factionId,
                null,
                FleetTypes.TRADE,
                fleetPoints * 0.15f, // light escort - the three parts add up to the requested size
                fleetPoints * 0.65f,
                fleetPoints * 0.2f, // tankers for the jump
                0f, 0f, 0f, 0f
        );
        params.ignoreMarketFleetSizeMult = true;

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (isEmpty(fleet, "TRADE", factionId)) return;

        fleet.setName(spawnedName(fleet, "Trader"));

        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);

        // Spawn at the local market if there is one, otherwise at the start position
        SectorEntityToken source = sourceMarket != null
                ? sourceMarket.getPrimaryEntity()
                : localSystem.createToken(start.x, start.y);
        SectorEntityToken dest = destMarket != null
                ? destMarket.getPrimaryEntity()
                : targetSystem.getCenter();

        localSystem.addEntity(fleet);
        fleet.setLocation(source.getLocation().x, source.getLocation().y);

        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, source, 2f, "loading cargo");
        fleet.addAssignment(FleetAssignment.DELIVER_RESOURCES, dest, 1000f, "delivering goods to " + targetSystem.getName());
        fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, dest, 3f, "unloading cargo");
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, source, 1000f, "returning home");

        String from = sourceMarket != null ? sourceMarket.getName() : localSystem.getName() + " (no market)";
        String to = destMarket != null ? destMarket.getName() : targetSystem.getName() + " center (no market)";
        verifySpawn(fleet, localSystem, "TRADE", "from " + from + " to " + to);
    }

    // factionId may be null, then the faction with the closest market that isn't hostile to the target helps.
    // targetMarket may be null when only a system or a market-less planet was named.
    public void spawnHelpFleet(String factionId, float combatPoints, StarSystemAPI targetSystem, MarketAPI targetMarket) {

        String protectedFactionId = protectedFaction(targetSystem, targetMarket);
        MarketAPI sourceMarket = pickHelperMarket(targetSystem, targetMarket, protectedFactionId, factionId);
        if (sourceMarket == null) {
            log.fail("HELP FAILED: no " + (factionId != null ? "'" + factionId + "' " : "friendly ")
                    + "market found to send help to " + targetSystem.getName() + ", nothing spawned");
            return;
        }

        FleetParamsV3 params = new FleetParamsV3(
                sourceMarket,
                sourceMarket.getStarSystem().getLocation(),
                sourceMarket.getFactionId(),
                null,
                FleetTypes.TASK_FORCE,
                combatPoints,
                0f,
                combatPoints * 0.1f, // tankers in case the trip crosses hyperspace
                0f, 0f, 0f, 0f
        );
        params.ignoreMarketFleetSizeMult = true;

        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
        if (isEmpty(fleet, "HELP", sourceMarket.getFactionId())) return;

        fleet.setName(spawnedName(fleet, "Relief Force"));

        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PATROL_FLEET, true);

        SectorEntityToken source = sourceMarket.getPrimaryEntity();
        SectorEntityToken dest = targetMarket != null ? targetMarket.getPrimaryEntity() : targetSystem.getCenter();
        String destName = targetMarket != null ? targetMarket.getName() : targetSystem.getName();

        LocationAPI sourceLocation = source.getContainingLocation();
        sourceLocation.addEntity(fleet);
        fleet.setLocation(source.getLocation().x, source.getLocation().y);

        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, dest, 1000f, "moving to help " + destName);
        if (targetMarket != null) {
            fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, dest, HELP_DAYS, "defending " + destName);
        } else {
            fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, dest, HELP_DAYS, "protecting " + destName);
        }
        fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, source, 1000f, "returning home");

        verifySpawn(fleet, sourceLocation, "HELP", "from " + sourceMarket.getName() + " to help " + destName
                + " (" + Math.round(Misc.getDistanceLY(sourceMarket.getStarSystem().getLocation(), targetSystem.getLocation()))
                + " ly) for " + (int) HELP_DAYS + " days");
    }

    // Who the help is for: the target market's owner, else the biggest market in the system, else the player
    private static String protectedFaction(StarSystemAPI system, MarketAPI market) {
        if (market != null) return market.getFactionId();
        MarketAPI biggest = null;
        for (MarketAPI m : Global.getSector().getEconomy().getMarkets(system)) {
            if (m.isHidden()) continue;
            if (biggest == null || m.getSize() > biggest.getSize()) biggest = m;
        }
        return biggest != null ? biggest.getFactionId() : Factions.PLAYER;
    }

    // Closest market (by hyperspace distance) that can send help. With no faction given, another faction
    // not hostile to the protected one is preferred; the protected faction itself is the fallback.
    private static MarketAPI pickHelperMarket(StarSystemAPI targetSystem, MarketAPI targetMarket,
                                              String protectedFactionId, String factionId) {
        MarketAPI best = null, bestOwn = null;
        float bestDist = Float.MAX_VALUE, bestOwnDist = Float.MAX_VALUE;

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market == targetMarket || market.isHidden()) continue;
            if (market.getPrimaryEntity() == null || market.getStarSystem() == null) continue;

            String owner = market.getFactionId();
            if (factionId != null) {
                if (!factionId.equals(owner)) continue;
            } else if (market.getFaction().isHostileTo(protectedFactionId)) {
                continue;
            }

            float dist = Misc.getDistanceLY(market.getStarSystem().getLocation(), targetSystem.getLocation());
            if (factionId == null && owner.equals(protectedFactionId)) {
                if (dist < bestOwnDist) { bestOwn = market; bestOwnDist = dist; }
            } else if (dist < bestDist) {
                best = market;
                bestDist = dist;
            }
        }
        return best != null ? best : bestOwn;
    }

    // "Spawned Hegemony Trader" - tells our fleets apart from the vanilla ones
    private static String spawnedName(CampaignFleetAPI fleet, String kind) {
        String faction = fleet.getFaction().getEntityNamePrefix(); // "Pirate", not "Pirates"
        if (faction == null || faction.isEmpty()) faction = fleet.getFaction().getDisplayName();
        return "Spawned " + faction + " " + kind;
    }

    private boolean isEmpty(CampaignFleetAPI fleet, String tag, String factionId) {
        if (fleet != null && fleet.getFleetData().getNumMembers() > 0) return false;
        log.fail(tag + " FAILED: fleet for faction '" + factionId + "' came out empty (bad faction id or too few points?), nothing spawned");
        return true;
    }

    // Checks the fleet really landed in the location with orders, and prints the result
    private void verifySpawn(CampaignFleetAPI fleet, LocationAPI location, String tag, String route) {
        boolean inLocation = fleet.getContainingLocation() == location && location.getFleets().contains(fleet);
        boolean hasOrders = fleet.getCurrentAssignment() != null;

        if (!inLocation || !hasOrders) {
            log.fail(tag + " FAILED: " + fleet.getName() + " inLocation=" + inLocation + " hasOrders=" + hasOrders);
            return;
        }

        // The short version for the player; the details below stay in the terminal and the log
        log.show(fleet.getName() + " (" + fleet.getFleetPoints() + " FP) in " + location.getName() + ", " + route);

        log.line(tag + " OK: " + fleet.getName()
                + " [" + fleet.getFaction().getDisplayName() + ", " + fleet.getFleetData().getNumMembers() + " ships, "
                + fleet.getFleetPoints() + " FP] in " + location.getName()
                + " at (" + (int) fleet.getLocation().x + ", " + (int) fleet.getLocation().y + "), " + route
                + ", current order: " + fleet.getCurrentAssignment().getActionText());
    }

    // Prefers a market of the given faction, falls back to any visible market in the location. Never picks
    // other (may be null): the market at the other end of the trip.
    private static MarketAPI pickMarket(LocationAPI location, String factionId, MarketAPI other) {
        MarketAPI fallback = null;
        for (MarketAPI market : Global.getSector().getEconomy().getMarkets(location)) {
            if (market == other || market.isHidden() || market.getPrimaryEntity() == null) continue;
            if (factionId.equals(market.getFactionId())) return market;
            if (fallback == null) fallback = market;
        }
        return fallback;
    }
}
