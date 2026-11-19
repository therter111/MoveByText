package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.LocationAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** <SPAWN:remnants:200>: an aggressive fleet of that faction appears next to the player. */
public final class SpawnCommand extends FleetCommand {

    static final Pattern TAG = Pattern.compile("<SPAWN:([^:<>]+):(\\d+)>");

    /** How far from the player the fleet appears, on both axes. */
    private static final float OFFSET = 600f;

    public SpawnCommand(String factionId, int points) {
        super(factionId, points);
    }

    static SpawnCommand from(Matcher tag) {
        return new SpawnCommand(tag.group(1).trim(), parsePoints(tag.group(2)));
    }

    @Override
    public String kind() {
        return "SPAWN";
    }

    @Override
    public String toTag() {
        return "<SPAWN:" + factionId() + ":" + points() + ">";
    }

    @Override
    public String describe() {
        return factionName(factionId()) + " war fleet of " + points() + " FP, next to you";
    }

    @Override
    public void execute(PlaceFinder places, FleetSpawner fleets) {
        if (!factionExists()) return;

        LocationAPI system = Global.getSector().getPlayerFleet().getContainingLocation();
        Vector2f playerCoords = Global.getSector().getPlayerFleet().getLocation();
        Vector2f spawnCoords = new Vector2f(playerCoords.x + OFFSET, playerCoords.y + OFFSET);

        LOG.line("SPAWN " + factionId() + " " + points() + " FP in " + system.getName());
        try {
            fleets.spawnAIFleet(factionId(), points(), system, spawnCoords);
        } catch (Exception e) {
            crashed(e);
        }
    }
}
