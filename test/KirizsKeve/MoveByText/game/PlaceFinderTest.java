package KirizsKeve.MoveByText.game;

import KirizsKeve.MoveByText.TestSuite;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for PlaceFinder: which star system, market or planet a name in a command means. Each test gets a
 * fake sector (FakeGame) with a few vanilla-like places in it.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class PlaceFinderTest {

    /** The fake sector of one test, with the places by name. */
    static final class Sector {
        final FakeGame.World world = new FakeGame.World();
        final StarSystemAPI corvus, askonia, aztlan, samarra, hybrasil;
        final MarketAPI jangala, sindria, depot, tigraCity, chicomoztoc, samarraMarket, secret, station;

        Sector() {
            corvus = world.system("Corvus", "*Corvus", "Jangala", "Asharu", "Barad");
            askonia = world.system("Askonia", "*Askonia", "Sindria", "Volturn");
            aztlan = world.system("Aztlan", "*Aztlan", "Chicomoztoc", "Ilm");
            samarra = world.system("Samarra");
            hybrasil = world.system("Hybrasil", "*Thulian Sun", "Culann");
            jangala = world.market("Jangala", corvus, "hegemony");
            // A market whose name starts like Sindria's, listed first: an exact match must still win
            depot = world.market("Sindrian Fuel Depot", askonia, "sindrian_diktat");
            sindria = world.market("Sindria", askonia, "sindrian_diktat");
            // A market named differently from the planet it is on, like vanilla's Tigra City on Ilm
            tigraCity = world.market("Tigra City", "Ilm", aztlan, "hegemony", false);
            chicomoztoc = world.market("Chicomoztoc", aztlan, "hegemony");
            // A market with a system's name, in another system: the system is found first
            samarraMarket = world.market("Samarra", corvus, "independent");
            secret = world.market("Secret Base", "Secret Base", corvus, "pirates", true);
            station = world.market("Deep Space Station", "Deep Space Station", null, "independent", false);
            world.install();
        }

        void stop() {
            FakeGame.uninstall();
        }
    }

    public static final TestSuite<Sector> SUITE = new TestSuite<>("PlaceFinderTest", Sector::new, Sector::stop);

    private static void test(String name, TestSuite.Test<Sector> body) {
        SUITE.add(name, body);
    }

    static {
        test("system: by its name, in any case, with or without \"system\" or \"star system\" after it", s -> {
            for (String name : new String[]{"Corvus", "corvus", "CORVUS", "Corvus system", "Corvus Star System",
                    "corvus star system", "  Corvus  "}) {
                Place p = find(name);
                check(p != null, show(name) + " found");
                check(p.system() == s.corvus, show(name) + " -> Corvus, got " + p.system());
                same(null, p.market(), show(name) + ": a system has no market");
            }
        });

        test("market: by its name, with its system", s -> {
            Place p = find("Jangala");
            check(p.system() == s.corvus && p.market() == s.jangala, "Jangala -> Corvus (Jangala), got " + p.describe());
            same("Corvus Star System (Jangala)", p.describe(), "describe()");
        });

        test("market: by the name of the planet or station it is on", s -> {
            Place p = find("Ilm");
            check(p.market() == s.tigraCity, "Ilm -> Tigra City, got " + p.market());
            check(find("Tigra City").market() == s.tigraCity, "and by its own name");
        });

        test("planet without a market: its system, no market", s -> {
            Place p = find("Barad");
            check(p != null && p.system() == s.corvus, "Barad -> Corvus");
            same(null, p.market(), "no market");
        });

        test("an exact name wins over one it is the start of", s -> {
            check(find("Sindria").market() == s.sindria, "Sindria, not the Sindrian Fuel Depot listed before it");
        });

        test("a name cut short finds the place, from 4 letters on", s -> {
            check(find("Chicomoz").market() == s.chicomoztoc, "Chicomoz -> Chicomoztoc");
            check(find("chic").market() == s.chicomoztoc, "chic (4 letters)");
            same(null, find("Chi"), "Chi (3 letters) is too short");
            check(find("Sindrian Fuel").market() == s.depot, "Sindrian Fuel -> the depot");
        });

        test("systems come before markets, markets before planets", s -> {
            Place samarra = find("Samarra");
            check(samarra.system() == s.samarra && samarra.market() == null, "Samarra -> the system, not the market");
            check(find("Jangala").market() == s.jangala, "the market Jangala, not the planet");
        });

        test("hidden markets, markets outside a system and stars are not places", s -> {
            same(null, find("Secret Base"), "hidden market");
            same(null, find("Deep Space Station"), "market in hyperspace");
            same(null, find("Thulian Sun"), "a star");
            check(find("Hybrasil").system() == s.hybrasil, "its system still is");
        });

        test("unknown names: null", s -> {
            for (String name : new String[]{"Nowhere", "Earth", "Coruscant", "", "system"}) {
                same(null, find(name), "find(" + show(name) + ")");
            }
        });

        test("places with missing names don't break the search", s -> {
            s.world.system(null, "Unnamed Rock");
            s.world.market(null, (String) null, s.corvus, "independent", false);
            s.world.install();
            check(find("Jangala").market() == s.jangala, "still finds Jangala");
            same(null, find("Nowhere"), "still null for unknown names");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    private static Place find(String name) {
        return new PlaceFinder().find(name);
    }
}
