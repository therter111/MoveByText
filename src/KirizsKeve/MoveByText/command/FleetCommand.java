package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.game.TerminalLog;

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

    /** A spawn that threw: reported, so one bad command doesn't stop the others. */
    protected final void crashed(Exception e) {
        LOG.line(kind() + " crashed: " + e);
        e.printStackTrace();
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
