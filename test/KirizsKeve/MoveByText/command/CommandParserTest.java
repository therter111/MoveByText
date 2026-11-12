package KirizsKeve.MoveByText.command;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.varia.NullAppender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline tests for the fleet commands: reading tags out of a provider's reply (CommandParser), and writing them
 * (toTag), which CommandBuilder uses for the NLU providers. Carrying a command out needs a running game, so
 * execute() is not tested here.
 *
 * Run main() from IntelliJ. Pass a word to run only the tests whose name contains it. Exits with 1 on any failure.
 */
public class CommandParserTest {

    private interface Test {
        void run() throws Exception;
    }

    private static final Map<String, Test> TESTS = new LinkedHashMap<>();

    private static void test(String name, Test body) {
        TESTS.put(name, body);
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

        test("order: every SPAWN, then TRADE, then HELP, each in the order written", () -> {
            List<FleetCommand> c = parse("<HELP:nearby:10:Jangala> <TRADE:independent:20:Askonia> "
                    + "<SPAWN:pirates:30> <HELP:hegemony:40:Asharu> <SPAWN:remnants:50>");
            same("<SPAWN:pirates:30>,<SPAWN:remnants:50>,<TRADE:independent:20:Askonia>,<HELP:nearby:10:Jangala>,"
                    + "<HELP:hegemony:40:Asharu>", tags(c), "order");
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
        BasicConfigurator.configure(new NullAppender()); // the size warnings also go to log4j; keep the output to results
        String filter = args.length > 0 ? args[0] : null;
        int run = 0, failed = 0;
        for (Map.Entry<String, Test> t : TESTS.entrySet()) {
            if (filter != null && !t.getKey().contains(filter)) continue;
            run++;
            try {
                t.getValue().run();
                System.out.println("PASS   " + t.getKey());
            } catch (Throwable e) {
                failed++;
                System.out.println("FAIL   " + t.getKey());
                String detail = e instanceof AssertionError ? e.getMessage() : "unexpected " + e;
                System.out.println("       " + String.valueOf(detail).replace("\n", "\n       "));
            }
        }
        System.out.println();
        System.out.println((run - failed) + "/" + run + " passed");
        System.exit(failed == 0 && run > 0 ? 0 : 1);
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

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static void same(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + "\nexpected: " + show(expected) + "\nactual:   " + show(actual));
        }
    }

    private static String show(Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }
}
