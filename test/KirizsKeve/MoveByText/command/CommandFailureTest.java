package KirizsKeve.MoveByText.command;

import KirizsKeve.MoveByText.TestSuite;
import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.game.FleetSpawner;
import KirizsKeve.MoveByText.game.PlaceFinder;
import KirizsKeve.MoveByText.game.TerminalLog;
import com.fs.starfarer.api.campaign.StarSystemAPI;

import java.util.ArrayList;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for the ways a command fails before any fleet is made: an unknown faction, a place that can't be
 * found, a convoy that would start where it ends, no market to send help from. Each must tell the player why and
 * spawn nothing. Making a fleet needs the real game (FleetFactoryV3), so a test that got that far would throw.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class CommandFailureTest {

    /** A fake sector with a few places and factions, and what the player was shown. */
    static final class Game {
        final FakeGame.World world = new FakeGame.World();
        final List<String> failed = new ArrayList<>();
        final List<String> shown = new ArrayList<>();
        final StarSystemAPI corvus, askonia;

        Game() {
            for (String id : new String[]{"hegemony", "pirates", "independent", "sindrian_diktat", "persean"}) {
                world.faction(id);
            }
            corvus = world.system("Corvus", "*Corvus", "Jangala", "Barad");
            askonia = world.system("Askonia", "*Askonia", "Sindria");
            world.market("Jangala", corvus, "hegemony");
            world.market("Sindria", askonia, "sindrian_diktat");
            world.playerLocation = corvus;
            world.install();
            TerminalLog.setScreenListener((message, isFailure) -> (isFailure ? failed : shown).add(message));
        }

        void stop() {
            TerminalLog.setScreenListener(null);
            FakeGame.uninstall();
        }

        /** Carries the command out and returns the one failure it showed. */
        String fails(FleetCommand command) {
            command.execute(new PlaceFinder(), new FleetSpawner());
            same(1, failed.size(), "failures shown for " + command + ": " + failed);
            return failed.get(0);
        }
    }

    public static final TestSuite<Game> SUITE = new TestSuite<>("CommandFailureTest", Game::new, Game::stop);

    private static void test(String name, TestSuite.Test<Game> body) {
        SUITE.add(name, body);
    }

    static {
        test("spawn: a faction the game doesn't have", g -> {
            has(g.fails(new SpawnCommand("klingons", 100)), "SPAWN FAILED: the game has no faction with the id 'klingons'",
                    "message");
        });

        test("trade: a faction the game doesn't have", g -> {
            has(g.fails(new TradeCommand("klingons", 100, "Jangala", null)), "TRADE FAILED: the game has no faction",
                    "message");
        });

        test("trade: a destination that can't be found", g -> {
            has(g.fails(new TradeCommand("independent", 100, "Nowhere", null)),
                    "TRADE FAILED: no star system, market or planet named 'Nowhere'", "message");
        });

        test("trade: an origin that can't be found", g -> {
            has(g.fails(new TradeCommand("independent", 100, "Jangala", "Nowhere")),
                    "named 'Nowhere' to start from", "message");
        });

        test("trade: a convoy that would start and end at the same market", g -> {
            has(g.fails(new TradeCommand("independent", 100, "Jangala", "jangala")),
                    "TRADE FAILED: the convoy would start and end at Jangala", "message");
        });

        test("help: a faction the game doesn't have", g -> {
            has(g.fails(new HelpCommand("klingons", 100, "Jangala")), "HELP FAILED: the game has no faction", "message");
        });

        test("help: a place that can't be found", g -> {
            has(g.fails(new HelpCommand(null, 100, "Nowhere")),
                    "HELP FAILED: no star system, market or planet named 'Nowhere'", "message");
        });

        test("help: the named faction has no market to send help from", g -> {
            has(g.fails(new HelpCommand("persean", 100, "Jangala")),
                    "HELP FAILED: no 'persean' market found to send help to Corvus", "message");
        });

        test("help: nobody nearby who isn't hostile to the place's owner", g -> {
            g.world.hostile("sindrian_diktat", "hegemony");
            has(g.fails(new HelpCommand(null, 100, "Jangala")), "HELP FAILED: no friendly market found", "message");
        });

        test("a failure is shown once and stops the command: nothing else is shown", g -> {
            g.fails(new HelpCommand(null, 100, "Nowhere"));
            same(0, g.shown.size(), "other messages: " + g.shown);
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }
}
