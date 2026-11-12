package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.Place;
import KirizsKeve.MoveByText.game.PlaceFinder;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <TRADE:independent:50:Askonia>: a trader fleet from the player's system to the named one.
 * <TRADE:independent:50:Askonia:Jangala>: the same, but starting from the named origin.
 */
public final class TradeCommand extends FleetCommand {

    static final Pattern TAG = Pattern.compile("<TRADE:([^:]+):(\\d+):([^:>]+)(?::([^:>]+))?>");

    private final String destination;
    /** null: from the player's location. */
    private final String origin;

    public TradeCommand(String factionId, int points, String destination, String origin) {
        super(factionId, points);
        this.destination = destination;
        this.origin = origin;
    }

    static TradeCommand from(Matcher tag) {
        return new TradeCommand(tag.group(1), parsePoints(tag.group(2)), tag.group(3).trim(),
                tag.group(4) != null ? tag.group(4).trim() : null);
    }

    public String destination() {
        return destination;
    }

    public String origin() {
        return origin;
    }

    @Override
    public String kind() {
        return "TRADE";
    }

    @Override
    public String toTag() {
        return "<TRADE:" + factionId() + ":" + points() + ":" + destination + (origin != null ? ":" + origin : "") + ">";
    }

    @Override
    public void execute(PlaceFinder places, FleetSpawner fleets) {
        // The names come from the player's free text, so these can actually miss
        Place dest = places.find(destination);
        if (dest == null) {
            LOG.line("TRADE FAILED: no star system, market or planet named '" + destination + "', nothing spawned");
            return;
        }

        LocationAPI originLocation;
        MarketAPI originMarket = null;
        if (origin != null) {
            Place from = places.find(origin);
            if (from == null) {
                LOG.line("TRADE FAILED: no star system, market or planet named '" + origin + "' to start from, nothing spawned");
                return;
            }
            originLocation = from.system();
            originMarket = from.market();
        } else {
            originLocation = Global.getSector().getPlayerFleet().getContainingLocation();
        }

        LOG.line("TRADE " + factionId() + " " + points() + " FP, "
                + Place.describe(originLocation, originMarket) + " -> " + dest.describe()
                + ", matched '" + (origin != null ? origin : "player location") + "' -> '" + destination + "'");
        try {
            fleets.spawnTraderFleet(factionId(), points(), originLocation, originMarket, dest.system(), dest.market());
        } catch (Exception e) {
            crashed(e);
        }
    }
}
