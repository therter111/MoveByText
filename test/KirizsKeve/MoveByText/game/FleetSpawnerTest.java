package KirizsKeve.MoveByText.game;

import KirizsKeve.MoveByText.TestSuite;
import KirizsKeve.MoveByText.command.FleetCommand;
import KirizsKeve.MoveByText.command.HelpCommand;
import KirizsKeve.MoveByText.command.SpawnCommand;
import KirizsKeve.MoveByText.command.TradeCommand;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import KirizsKeve.MoveByText.game.FleetSpawner.FleetRequest;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for what the commands do when they work: which fleet FleetSpawner asks for (a FleetRequest), where
 * it puts it, what it tells it to do and what the player is told. The game's FleetFactoryV3 is swapped for a maker
 * that records the request and returns a FakeFleet, so everything up to "the fleet has ships" runs offline.
 *
 * The sector (Game below): Corvus at (0, 0); Thule (no market) and Magec (pirates) 0.25 ly away; Askonia 2 ly; Aztlan
 * 5 ly; Galatia 20 ly. 2000 map units are a light year here (FakeGame.UNITS_PER_LIGHT_YEAR).
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class FleetSpawnerTest {

    /** The fake sector, the recording fleet maker and what the player was shown. */
    static final class Game {
        final FakeGame.World world = new FakeGame.World();
        final List<FleetRequest> asked = new ArrayList<>();
        final List<String> shown = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        /** How many ships the maker's fleets have; 0 makes empty fleets, -1 makes none (null). */
        int members = 5;
        final FleetSpawner spawner = new FleetSpawner(this::make);
        final StarSystemAPI corvus, askonia, aztlan, galatia, thule, magec;
        final MarketAPI jangala, asharu, sindria, volturn, chicomoztoc, ancyra, derinkuyu, kantasDen;

        Game() {
            world.faction("pirates", "Pirates", "Pirates", "Pirate", true);
            world.faction("hegemony", "Hegemony", "Hegemony", "Hegemony", true);
            world.faction("independent", "Independent", "Independent", "Independent", true);
            world.faction("sindrian_diktat", "Diktat", "Sindrian Diktat", "Sindrian", true);
            world.faction("persean", "Persean League", "Persean League", "Persean", true);
            world.faction("tritachyon", "Tri-Tachyon", "Tri-Tachyon Corporation", "Tri-Tachyon", true);
            world.faction("mod_x", "Mod X Navy", "The Mod X Navy", "", true);
            // As in the game, pirates are hostile to the factions (not to the player here, unless a test says so)
            for (String f : new String[]{"hegemony", "independent", "sindrian_diktat", "persean", "tritachyon"}) {
                world.hostile("pirates", f);
            }

            corvus = world.system("Corvus", "*Corvus", "Jangala", "Asharu");
            askonia = world.system("Askonia", "*Askonia", "Sindria", "Volturn");
            aztlan = world.system("Aztlan", "*Aztlan", "Chicomoztoc");
            galatia = world.system("Galatia", "*Galatia", "Ancyra", "Derinkuyu");
            thule = world.system("Thule", "*Thule", "Hrimfaxi");
            magec = world.system("Magec", "*Magec", "Kanta's Den");
            world.at(corvus, 0, 0);
            world.at(askonia, 4000, 0);
            world.at(aztlan, 10000, 0);
            world.at(galatia, 40000, 0);
            world.at(thule, 500, 0);
            world.at(magec, 500, 0);

            jangala = world.market("Jangala", "Jangala", corvus, "hegemony", false, 6);
            asharu = world.market("Asharu", "Asharu", corvus, "independent", true, 3); // hidden: never helps, never counts
            sindria = world.market("Sindria", askonia, "sindrian_diktat");
            volturn = world.market("Volturn", askonia, "independent");
            chicomoztoc = world.market("Chicomoztoc", aztlan, "hegemony");
            ancyra = world.market("Ancyra", "Ancyra", galatia, "persean", false, 6);
            derinkuyu = world.market("Derinkuyu", "Derinkuyu", galatia, "independent", false, 3);
            kantasDen = world.market("Kanta's Den", magec, "pirates");

            world.playerLocation = corvus;
            world.playerAt.set(100, 200);
            world.install();
            Global.setSettings(FakeGame.settings(Collections.<String, String>emptyMap(), true));
            TerminalLog.setScreenListener((message, isFailure) -> (isFailure ? failed : shown).add(message));
        }

        private CampaignFleetAPI make(FleetRequest params) {
            asked.add(params);
            if (members < 0) return null;
            int points = Math.round(params.combatPts + params.freighterPts + params.tankerPts);
            return world.newFleet(params.factionId, members, points).api;
        }

        void stop() {
            TerminalLog.setScreenListener(null);
            FakeGame.uninstall();
        }

        void run(FleetCommand command) {
            command.execute(new PlaceFinder(), spawner);
        }

        /** The request for the one fleet asked for. */
        FleetRequest params() {
            same(1, asked.size(), "fleets asked for");
            return asked.get(0);
        }

        /** The one fleet that landed in location. */
        FakeGame.FakeFleet fleetIn(LocationAPI location) {
            List<CampaignFleetAPI> in = world.fleetsIn(location);
            same(1, in.size(), "fleets in " + location + " (failures: " + failed + ")");
            return world.fleet(in.get(0));
        }

        String shownText() {
            return String.join("\n", shown);
        }
    }

    public static final TestSuite<Game> SUITE = new TestSuite<>("FleetSpawnerTest", Game::new, Game::stop);

    private static void test(String name, TestSuite.Test<Game> body) {
        SUITE.add(name, body);
    }

    static {
        // ------------------------------------------------------------------------------------------
        // SPAWN
        // ------------------------------------------------------------------------------------------

        test("spawn: a large patrol of the faction, of the size asked for", g -> {
            g.run(new SpawnCommand("pirates", 40));
            FleetRequest p = g.params();
            same(FleetTypes.PATROL_LARGE, p.fleetType, "fleet type");
            same("pirates", p.factionId, "faction");
            near(40, p.combatPts, "combat points");
            near(0, p.freighterPts + p.tankerPts, "no freighters or tankers");
            same(null, p.source, "no source market");
            check(g.failed.isEmpty(), "no failure: " + g.failed);
        });

        test("spawn: 600 to the right of and above the player, aggressive, staying in the system", g -> {
            g.run(new SpawnCommand("pirates", 40));
            FakeGame.FakeFleet f = g.fleetIn(g.corvus);
            same("Spawned Pirate Patrol", f.name, "name");
            near(700, f.at.x, "x");
            near(800, f.at.y, "y");
            same(true, f.memory.get(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE), "aggressive");
            same(true, f.memory.get(MemFlags.MEMORY_KEY_NO_JUMP), "no jumping out");
        });

        test("spawn: patrols there for 90 days, then leaves", g -> {
            g.run(new SpawnCommand("pirates", 40));
            List<FakeGame.Assignment> a = g.fleetIn(g.corvus).assignments;
            same(2, a.size(), "assignments: " + a);
            assignment(a.get(0), FleetAssignment.PATROL_SYSTEM, 90, "patrolling the sector");
            assignment(a.get(1), FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, 1000, "standing down");
            check(a.get(0).target == a.get(1).target, "both at the same spot");
            near(700, a.get(0).target.getLocation().x, "the spot is where it appeared");
        });

        test("spawn: the player is told what came and where", g -> {
            g.run(new SpawnCommand("pirates", 40));
            has(g.shownText(), "Spawned Pirate Patrol (40 FP) in Corvus Star System, patrolling Corvus Star System", "shown");
        });

        test("spawn: today the patrol's \"hyperspace location\" is its spot in the system (see FleetSpawner)", g -> {
            // FleetParamsV3.locInHyper is meant to be a hyperspace position; spawnAIFleet passes the in-system one.
            // This pins down today's behaviour, so a change to it is a decision, not an accident.
            g.run(new SpawnCommand("pirates", 40));
            near(700, g.params().locInHyper.x, "locInHyper.x");
            near(800, g.params().locInHyper.y, "locInHyper.y");
        });

        test("spawn: a faction without an entity prefix uses its display name", g -> {
            g.run(new SpawnCommand("mod_x", 50));
            same("Spawned Mod X Navy Patrol", g.fleetIn(g.corvus).name, "name");
        });

        // ------------------------------------------------------------------------------------------
        // TRADE
        // ------------------------------------------------------------------------------------------

        test("trade: a trade fleet from the origin market, 15/65/20 escort, freighters and tankers", g -> {
            g.run(new TradeCommand("independent", 100, "Sindria", "Jangala"));
            FleetRequest p = g.params();
            same(FleetTypes.TRADE, p.fleetType, "fleet type");
            same("independent", p.factionId, "faction");
            check(p.source == g.jangala, "source market Jangala, got " + p.source);
            near(15, p.combatPts, "escort");
            near(65, p.freighterPts, "freighters");
            near(20, p.tankerPts, "tankers");
            near(0, p.locInHyper.x, "starts from Corvus' place in hyperspace");
        });

        test("trade: loads at the origin, delivers, unloads, goes home", g -> {
            g.run(new TradeCommand("independent", 100, "Sindria", "Jangala"));
            FakeGame.FakeFleet f = g.fleetIn(g.corvus);
            same("Spawned Independent Trader", f.name, "name");
            same(true, f.memory.get(MemFlags.MEMORY_KEY_TRADE_FLEET), "trade fleet");
            near(100, f.at.x, "appears at Jangala");
            List<FakeGame.Assignment> a = f.assignments;
            same(4, a.size(), "assignments: " + a);
            assignment(a.get(0), FleetAssignment.ORBIT_PASSIVE, 2, "loading cargo");
            assignment(a.get(1), FleetAssignment.DELIVER_RESOURCES, 1000, "delivering goods to Askonia Star System");
            assignment(a.get(2), FleetAssignment.ORBIT_PASSIVE, 3, "unloading cargo");
            assignment(a.get(3), FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, 1000, "returning home");
            check(a.get(0).target == g.jangala.getPrimaryEntity() && a.get(3).target == g.jangala.getPrimaryEntity(), "from and back to Jangala");
            check(a.get(1).target == g.sindria.getPrimaryEntity() && a.get(2).target == g.sindria.getPrimaryEntity(), "to Sindria");
            has(g.shownText(), "Spawned Independent Trader (100 FP) in Corvus Star System, from Jangala to Sindria", "shown");
        });

        test("trade: without an origin, a market of the faction in the player's system is preferred", g -> {
            g.world.playerLocation = g.askonia;
            g.run(new TradeCommand("independent", 50, "Jangala", null));
            check(g.params().source == g.volturn, "Volturn (independent), not Sindria listed before it; got " + g.params().source);
            g.fleetIn(g.askonia);
        });

        test("trade: without a market of the faction there, any market of the system", g -> {
            g.world.playerLocation = g.askonia;
            g.run(new TradeCommand("tritachyon", 50, "Jangala", null));
            check(g.params().source == g.sindria, "the first market, Sindria; got " + g.params().source);
        });

        test("trade: never starts at the market it delivers to", g -> {
            g.world.playerLocation = g.askonia;
            g.run(new TradeCommand("independent", 50, "Volturn", null));
            check(g.params().source == g.sindria, "Sindria, as Volturn is the destination; got " + g.params().source);
        });

        test("trade: from hyperspace it starts at the player's position", g -> {
            LocationAPI hyper = g.world.hyperspace();
            g.world.playerLocation = hyper;
            g.world.playerAt.set(5000, 3000);
            g.run(new TradeCommand("independent", 100, "Jangala", null));
            FleetRequest p = g.params();
            same(null, p.source, "no market to start from");
            near(5000, p.locInHyper.x, "locInHyper is the player's position");
            FakeGame.FakeFleet f = g.fleetIn(hyper);
            near(5000, f.at.x, "appears at the player");
            near(3000, f.at.y, "appears at the player");
            has(g.shownText(), "in Hyperspace, from Hyperspace (no market) to Jangala", "shown");
        });

        test("trade: to a system without a market it delivers to the system's center", g -> {
            g.run(new TradeCommand("independent", 100, "Thule", "Jangala"));
            List<FakeGame.Assignment> a = g.fleetIn(g.corvus).assignments;
            same("Thule center", a.get(1).target.getName(), "delivers to");
            has(g.shownText(), "from Jangala to Thule Star System center (no market)", "shown");
        });

        // ------------------------------------------------------------------------------------------
        // HELP
        // ------------------------------------------------------------------------------------------

        test("help: the nearest market of another faction that isn't hostile sends a task force", g -> {
            g.run(new HelpCommand(null, 100, "Jangala"));
            FleetRequest p = g.params();
            check(p.source == g.sindria, "Sindria (2 ly), the first of the two nearest; got " + p.source);
            same(FleetTypes.TASK_FORCE, p.fleetType, "fleet type");
            same("sindrian_diktat", p.factionId, "the helper's faction");
            near(100, p.combatPts, "combat points");
            near(10, p.tankerPts, "10% tankers");
            near(0, p.freighterPts, "no freighters");
            near(4000, p.locInHyper.x, "from Askonia's place in hyperspace");
        });

        test("help: goes to the place, defends it for 30 days, goes home", g -> {
            g.run(new HelpCommand(null, 100, "Jangala"));
            FakeGame.FakeFleet f = g.fleetIn(g.askonia);
            same("Spawned Sindrian Relief Force", f.name, "name");
            same(true, f.memory.get(MemFlags.MEMORY_KEY_PATROL_FLEET), "patrol fleet");
            List<FakeGame.Assignment> a = f.assignments;
            same(3, a.size(), "assignments: " + a);
            assignment(a.get(0), FleetAssignment.GO_TO_LOCATION, 1000, "moving to help Jangala");
            assignment(a.get(1), FleetAssignment.DEFEND_LOCATION, 30, "defending Jangala");
            assignment(a.get(2), FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, 1000, "returning home");
            check(a.get(1).target == g.jangala.getPrimaryEntity(), "defends Jangala itself");
            check(a.get(2).target == g.sindria.getPrimaryEntity(), "home is Sindria");
            has(g.shownText(), "Spawned Sindrian Relief Force (110 FP) in Askonia Star System, from Sindria to help Jangala (2 ly)"
                    + " for 30 days", "shown");
        });

        test("help: a faction hostile to the place's owner is passed over", g -> {
            g.world.hostile("sindrian_diktat", "hegemony");
            g.run(new HelpCommand(null, 100, "Jangala"));
            check(g.params().source == g.volturn, "Volturn (independent); got " + g.params().source);
        });

        test("help: when every other faction is hostile, the owner's own nearest market helps", g -> {
            g.world.hostile("sindrian_diktat", "hegemony");
            g.world.hostile("independent", "hegemony");
            g.world.hostile("persean", "hegemony");
            g.world.hostile("pirates", "hegemony");
            g.run(new HelpCommand(null, 100, "Jangala"));
            check(g.params().source == g.chicomoztoc, "Chicomoztoc (hegemony, 5 ly); got " + g.params().source);
            same("hegemony", g.params().factionId, "faction");
        });

        test("help: a named faction sends from its own nearest market", g -> {
            g.run(new HelpCommand("independent", 80, "Jangala"));
            check(g.params().source == g.volturn, "Volturn; got " + g.params().source);
            near(80, g.params().combatPts, "combat points");
        });

        test("help for a system: patrols it, for its biggest market's owner", g -> {
            // Galatia: Ancyra (persean, size 6) and Derinkuyu (independent, size 3). Help is for the persean league,
            // so the nearest other faction is Derinkuyu's; for the independents it would have been Ancyra.
            g.run(new HelpCommand(null, 100, "Galatia"));
            check(g.params().source == g.derinkuyu, "Derinkuyu; got " + g.params().source);
            List<FakeGame.Assignment> a = g.fleetIn(g.galatia).assignments;
            assignment(a.get(1), FleetAssignment.PATROL_SYSTEM, 30, "protecting Galatia Star System");
            same("Galatia center", a.get(1).target.getName(), "patrols the system");
        });

        test("help for a system without markets: for the player, so pirates don't come", g -> {
            // Thule has no market; Kanta's Den (pirates) is just as close, but pirates are hostile to the player
            g.world.hostile("pirates", "player");
            g.run(new HelpCommand(null, 100, "Thule"));
            check(g.params().source == g.jangala, "Jangala (0.25 ly); got " + g.params().source);
        });

        // ------------------------------------------------------------------------------------------
        // The game's parameters
        // ------------------------------------------------------------------------------------------

        test("paramsFor: the request's values, nothing else, and the size asked for whatever the market", g -> {
            // Only without a market: with one, FleetParamsV3 asks the running game for its clock and ship quality
            FleetParamsV3 p = FleetSpawner.paramsFor(new FleetRequest(null, new org.lwjgl.util.vector.Vector2f(1, 2),
                    "pirates", FleetTypes.PATROL_LARGE, 40, 5, 7));
            same("pirates", p.factionId, "faction");
            same(FleetTypes.PATROL_LARGE, p.fleetType, "fleet type");
            near(40, p.combatPts, "combat");
            near(5, p.freighterPts, "freighters");
            near(7, p.tankerPts, "tankers");
            near(0, p.transportPts + p.linerPts + p.utilityPts, "no transports, liners or utility ships");
            near(0, p.qualityMod, "no quality change");
            same(null, p.qualityOverride, "no quality override");
            near(1, p.locInHyper.x, "locInHyper.x");
            near(2, p.locInHyper.y, "locInHyper.y");
            same(Boolean.TRUE, p.ignoreMarketFleetSizeMult, "ignoreMarketFleetSizeMult");
        });

        // ------------------------------------------------------------------------------------------
        // When it goes wrong
        // ------------------------------------------------------------------------------------------

        test("an empty fleet is reported and nothing lands", g -> {
            g.members = 0;
            g.run(new SpawnCommand("pirates", 40));
            same(Collections.singletonList("SPAWN FAILED: fleet for faction 'pirates' came out empty (bad faction id or too"
                    + " few points?), nothing spawned"), g.failed, "failures");
            check(g.world.fleetsIn(g.corvus).isEmpty(), "nothing in Corvus");
        });

        test("no fleet at all (null) is reported the same way, for each kind", g -> {
            g.members = -1;
            g.run(new SpawnCommand("pirates", 40));
            g.run(new TradeCommand("independent", 100, "Sindria", "Jangala"));
            g.run(new HelpCommand(null, 100, "Jangala"));
            same(3, g.failed.size(), "failures: " + g.failed);
            has(g.failed.get(0), "SPAWN FAILED", "spawn");
            has(g.failed.get(1), "TRADE FAILED: fleet for faction 'independent' came out empty", "trade");
            has(g.failed.get(2), "HELP FAILED: fleet for faction 'sindrian_diktat' came out empty", "help");
        });

        test("a fleet that didn't land in the location is reported", g -> {
            g.world.refuseFleets(g.corvus);
            g.run(new SpawnCommand("pirates", 40));
            same(Collections.singletonList("SPAWN FAILED: Spawned Pirate Patrol inLocation=false hasOrders=true"), g.failed,
                    "failures");
            check(g.shown.isEmpty(), "no success shown: " + g.shown);
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    private static void near(double expected, double actual, String what) {
        if (Math.abs(expected - actual) > 0.01) {
            throw new AssertionError(what + "\nexpected: " + expected + "\nactual:   " + actual);
        }
    }

    private static void assignment(FakeGame.Assignment a, FleetAssignment type, float days, String text) {
        same(type, a.type, "assignment type of " + a);
        near(days, a.days, "days of " + a);
        same(text, a.text, "text of " + a);
        check(a.target != null, "a target for " + a);
    }
}
