package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.Place;
import KirizsKeve.MoveByText.game.PlaceFinder;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <TRADE:independent:50:Askonia>: a trader fleet from the player's system to the named one.
 * <TRADE:independent:50:Askonia:Jangala>: the same, but starting from the named origin.
 */
public final class TradeCommand extends FleetCommand {

    static final Pattern TAG = Pattern.compile("<TRADE:([^:<>]+):(\\d+):([^:>]+)(?::([^:>]+))?>");

    private final String destination;
    /** null: from the player's location. */
    private final String origin;

    public TradeCommand(String factionId, int points, String destination, String origin) {
        super(factionId, points);
        this.destination = destination;
        this.origin = origin;
    }

    static TradeCommand from(Matcher tag) {
        return new TradeCommand(tag.group(1).trim(), parsePoints(tag.group(2)), tag.group(3).trim(),
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
    public String describe() {
        return factionName(factionId()) + " trade convoy of " + points() + " FP to " + destination
                + (origin != null ? ", from " + origin : ", from where you are");
    }

    @Override
    public void execute(PlaceFinder places, FleetSpawner fleets) {
        if (!factionExists()) return;

        // The names come from the player's free text, so these can actually miss
        Place dest = places.find(destination);
        if (dest == null) {
            LOG.fail("TRADE FAILED: no star system, market or planet named '" + destination + "', nothing spawned");
            return;
        }

        LocationAPI originLocation;
        MarketAPI originMarket = null;
        // Where the convoy appears when it has no market to start from: next to the player, unless an origin was named
        Vector2f start = null;
        if (origin != null) {
            Place from = places.find(origin);
            if (from == null) {
                LOG.fail("TRADE FAILED: no star system, market or planet named '" + origin + "' to start from, nothing spawned");
                return;
            }
            originLocation = from.system();
            originMarket = from.market();
        } else {
            originLocation = Global.getSector().getPlayerFleet().getContainingLocation();
            start = Global.getSector().getPlayerFleet().getLocation();
        }

        LOG.line("TRADE " + factionId() + " " + points() + " FP, "
                + Place.describe(originLocation, originMarket) + " -> " + dest.describe()
                + ", matched '" + (origin != null ? origin : "player location") + "' -> '" + destination + "'");
        try {
            fleets.spawnTraderFleet(factionId(), points(), originLocation, originMarket, start, dest.system(), dest.market());
        } catch (Exception e) {
            crashed(e);
        }
    }
}
