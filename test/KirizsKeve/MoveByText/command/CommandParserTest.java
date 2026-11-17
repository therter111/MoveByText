package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.TestSuite;

import java.util.ArrayList;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for the fleet commands: reading tags out of a provider's reply (CommandParser), and writing them
 * (toTag), which CommandBuilder uses for the NLU providers. Carrying a command out needs a running game, so
 * execute() is not tested here.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests. Pass a word to run only the tests whose name
 * contains it. Exits with 1 on any failure.
 */
public class CommandParserTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("CommandParserTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    static {
        test("spawn: faction and points", () -> {
            List<FleetCommand> c = parse("<SPAWN:pirates:40>");
            same(1, c.size(), "commands");
            check(c.get(0) instanceof SpawnCommand, "a SpawnCommand");
            same("pirates", c.get(0).factionId(), "faction");
            same(40, c.get(0).points(), "points");
            same("SPAWN", c.get(0).kind(), "kind");
        });

        test("trade: destination, with and without an origin", () -> {
            TradeCommand to = (TradeCommand) parse("<TRADE:independent:50:Askonia>").get(0);
            same("independent", to.factionId(), "faction");
            same(50, to.points(), "points");
            same("Askonia", to.destination(), "destination");
            same(null, to.origin(), "no origin: from the player's location");

            TradeCommand from = (TradeCommand) parse("<TRADE:independent:50:Askonia:Jangala>").get(0);
            same("Askonia", from.destination(), "destination");
            same("Jangala", from.origin(), "origin");
        });

        test("trade: names are trimmed", () -> {
            TradeCommand t = (TradeCommand) parse("<TRADE:independent:50: Port Tse : Jangala >").get(0);
            same("Port Tse", t.destination(), "destination");
            same("Jangala", t.origin(), "origin");
        });

        test("help: nearby means no faction, a named faction is kept", () -> {
            HelpCommand nearby = (HelpCommand) parse("<HELP:nearby:150:Jangala>").get(0);
            same(null, nearby.factionId(), "nearby -> null");
            same("Jangala", nearby.target(), "target");
            same(150, nearby.points(), "points");
            same("hegemony", parse("<HELP: hegemony :150:Jangala>").get(0).factionId(), "named faction, trimmed");
        });

        test("points: kept between 1 and 300", () -> {
            same(FleetCommand.MIN_POINTS, parse("<SPAWN:pirates:0>").get(0).points(), "0 -> 1");
            same(FleetCommand.MAX_POINTS, parse("<SPAWN:pirates:99999>").get(0).points(), "99999 -> 300");
            same(FleetCommand.MAX_POINTS, parse("<SPAWN:pirates:123456789012345>").get(0).points(), "too many digits -> 300");
            same(300, parse("<SPAWN:pirates:300>").get(0).points(), "300 stays");
            same(1, parse("<SPAWN:pirates:1>").get(0).points(), "1 stays");
        });

        test("order: the order written, whatever the kinds", () -> {
            List<FleetCommand> c = parse("<HELP:nearby:10:Jangala> <TRADE:independent:20:Askonia> "
                    + "<SPAWN:pirates:30> <HELP:hegemony:40:Asharu> <SPAWN:remnants:50>");
            same("<HELP:nearby:10:Jangala>,<TRADE:independent:20:Askonia>,<SPAWN:pirates:30>,"
                    + "<HELP:hegemony:40:Asharu>,<SPAWN:remnants:50>", tags(c), "order");
        });

        test("faction: trimmed, and never reaches past the end of a tag", () -> {
            same("pirates", parse("<SPAWN: pirates :40>").get(0).factionId(), "spawn faction, trimmed");
            same("independent", parse("<TRADE: independent :50:Askonia>").get(0).factionId(), "trade faction, trimmed");
            // A tag quoted without its values must not swallow the text up to the next real one
            same("<SPAWN:pirates:40>", tags(parse("<SPAWN:faction_id> means <SPAWN:pirates:40>")), "spawn");
            same("<HELP:nearby:100:Jangala>", tags(parse("<HELP:x> text <HELP:nearby:100:Jangala>")), "help");
        });

        test("no commands: <NONE>, plain text, broken tags", () -> {
            for (String text : new String[]{"<NONE>", "", "Sure, here you go!", "<SPAWN:pirates>", "<SPAWN:pirates:-5>",
                    "<TRADE:independent:50>", "<HELP:nearby:abc:Jangala>", "<spawn:pirates:40>"}) {
                same(0, parse(text).size(), "commands in " + show(text));
            }
        });

        test("tags in prose: found anywhere in the text", () -> {
            same("<SPAWN:pirates:40>,<HELP:nearby:100:Jangala>",
                    tags(parse("On it! <SPAWN:pirates:40> And help is coming: <HELP:nearby:100:Jangala>.")), "tags");
        });

        test("toTag: the tag each command is read from", () -> {
            same("<SPAWN:pirates:40>", new SpawnCommand("pirates", 40).toTag(), "spawn");
            same("<TRADE:independent:100:Jangala>", new TradeCommand("independent", 100, "Jangala", null).toTag(), "trade");
            same("<TRADE:independent:100:Jangala:Asharu>", new TradeCommand("independent", 100, "Jangala", "Asharu").toTag(),
                    "trade with origin");
            same("<HELP:nearby:150:Jangala>", new HelpCommand(null, 150, "Jangala").toTag(), "help, no faction");
            same("<HELP:hegemony:150:Jangala>", new HelpCommand("hegemony", 150, "Jangala").toTag(), "help, faction");
        });

        test("round trip: parse(toTag()) gives the same tag", () -> {
            FleetCommand[] commands = {new SpawnCommand("remnants", 300), new TradeCommand("persean", 1, "Kazeron", "Port Tse"),
                    new TradeCommand("independent", 75, "Kanta's Den", null), new HelpCommand(null, 225, "Chicomoztoc"),
                    new HelpCommand("luddic_church", 15, "Gilead")};
            for (FleetCommand command : commands) {
                List<FleetCommand> back = parse(command.toTag());
                same(1, back.size(), "commands from " + command.toTag());
                same(command.getClass(), back.get(0).getClass(), "kind of " + command.toTag());
                same(command.toTag(), back.get(0).toTag(), "tag");
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Runner and helpers
    // ------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        SUITE.main(args);
    }

    private static List<FleetCommand> parse(String text) {
        return new CommandParser().parse(text);
    }

    /** The commands' tags, comma separated. */
    private static String tags(List<FleetCommand> commands) {
        List<String> tags = new ArrayList<>();
        for (FleetCommand c : commands) tags.add(c.toTag());
        return String.join(",", tags);
    }
}
