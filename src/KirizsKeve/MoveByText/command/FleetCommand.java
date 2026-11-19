package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;

/**
 * One fleet the player asked for, e.g. <SPAWN:pirates:40>. Every provider answers with these as tags: the LLMs
 * write them, the NLU providers' CommandBuilder builds them, and CommandParser reads them back on the game side.
 * Each kind knows its own tag (toTag) and how it is carried out (execute).
 */
public abstract class FleetCommand {

    /** Fleet size shared by all commands: 15 tiny ... 300 humongous, or the player's own number. */
    public static final int MIN_POINTS = 1;
    public static final int MAX_POINTS = 300;
    /** When nothing says how big. */
    public static final int DEFAULT_POINTS = 100;

    static final TerminalLog LOG = new TerminalLog("LLM COMMAND", FleetCommand.class);

    private final String factionId;
    private final int points;

    protected FleetCommand(String factionId, int points) {
        this.factionId = factionId;
        this.points = points;
    }

    public String factionId() {
        return factionId;
    }

    public int points() {
        return points;
    }

    /** The tag's name: "SPAWN", "TRADE" or "HELP". */
    public abstract String kind();

    /** The command as the tag the system prompt asks for, e.g. "<TRADE:independent:100:Jangala>". */
    public abstract String toTag();

    /** Finds the places, spawns the fleet and reports what happened. Game thread only. */
    public abstract void execute(PlaceFinder places, FleetSpawner fleets);

    /**
     * The command in words, for the player to OK before it runs: "Pirate patrol of 40 FP, next to you". Game thread
     * only (it asks the game for the faction's name).
     */
    public abstract String describe();

    /** The faction's name as the game shows it ("Hegemony"), or its id when the game doesn't know it. */
    protected static String factionName(String id) {
        FactionAPI faction = Global.getSector() == null ? null : Global.getSector().getFaction(id);
        String name = faction == null ? null : faction.getDisplayName();
        return name == null || name.trim().isEmpty() ? id : name;
    }

    /**
     * A spawn that threw: reported, so one bad command doesn't stop the others. The stack trace goes to the game's
     * log (starsector.log), where printStackTrace's stderr doesn't end up.
     */
    protected final void crashed(Exception e) {
        LOG.fail(kind() + " crashed: " + e);
        Global.getLogger(getClass()).error(kind() + " crashed", e);
    }

    /**
     * False, with a message, when the tag names a faction the running game doesn't have (a model's typo, a
     * made-up id). Game thread only.
     */
    protected final boolean factionExists() {
        if (Global.getSector().getFaction(factionId) != null) return true;
        LOG.fail(kind() + " FAILED: the game has no faction with the id '" + factionId + "', nothing spawned");
        return false;
    }

    @Override
    public String toString() {
        return toTag();
    }

    /** The size in a tag, kept between MIN_POINTS and MAX_POINTS so a typo can't spawn an absurd fleet. */
    static int parsePoints(String digits) {
        long points = Long.parseLong(digits.length() > 9 ? "999999999" : digits);
        long clamped = Math.max(MIN_POINTS, Math.min(MAX_POINTS, points));
        if (clamped != points) {
            LOG.line("size " + digits + " is outside " + MIN_POINTS + "-" + MAX_POINTS + ", using " + clamped);
        }
        return (int) clamped;
    }
}
