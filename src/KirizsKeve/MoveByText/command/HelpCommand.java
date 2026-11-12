package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.Place;
import KirizsKeve.MoveByText.game.PlaceFinder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <HELP:nearby:150:Jangala>: the closest friendly faction sends a fleet to defend the named place.
 * <HELP:hegemony:150:Jangala>: the same, but the named faction sends it from its closest market.
 */
public final class HelpCommand extends FleetCommand {

    static final Pattern TAG = Pattern.compile("<HELP:([^:]+):(\\d+):([^:>]+)>");

    /** The faction in a tag that names none: the closest friendly one helps. */
    static final String NEARBY = "nearby";

    private final String target;

    /** factionId null: the closest faction that isn't hostile to the target helps. */
    public HelpCommand(String factionId, int points, String target) {
        super(factionId, points);
        this.target = target;
    }

    static HelpCommand from(Matcher tag) {
        String factionId = tag.group(1).trim();
        return new HelpCommand(factionId.equals(NEARBY) ? null : factionId, parsePoints(tag.group(2)), tag.group(3).trim());
    }

    public String target() {
        return target;
    }

    @Override
    public String kind() {
        return "HELP";
    }

    @Override
    public String toTag() {
        return "<HELP:" + (factionId() != null ? factionId() : NEARBY) + ":" + points() + ":" + target + ">";
    }

    @Override
    public void execute(PlaceFinder places, FleetSpawner fleets) {
        Place place = places.find(target);
        if (place == null) {
            LOG.line("HELP FAILED: no star system, market or planet named '" + target + "', nothing spawned");
            return;
        }

        LOG.line("HELP " + (factionId() != null ? factionId() : "nearest friendly faction") + " "
                + points() + " FP to " + place.describe() + ", matched '" + target + "'");
        try {
            fleets.spawnHelpFleet(factionId(), points(), place.system(), place.market());
        } catch (Exception e) {
            crashed(e);
        }
    }
}
