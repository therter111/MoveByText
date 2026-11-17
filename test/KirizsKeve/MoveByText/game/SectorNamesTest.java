package KirizsKeve.MoveByText.game;

import KirizsKeve.MoveByText.TestSuite;
import KirizsKeve.MoveByText.nlp.GameNames;
import KirizsKeve.MoveByText.nlp.PlaceNames;
import com.fs.starfarer.api.campaign.StarSystemAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for SectorNames: which factions and places of a (fake) sector reach the NLP side. The place names
 * are checked through what they change: PlaceNames accepting a lower-case place once the names are installed.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class SectorNamesTest {

    public static final TestSuite<FakeGame.World> SUITE = new TestSuite<>("SectorNamesTest", FakeGame.World::new,
            world -> {
                FakeGame.uninstall();
                GameNames.install(GameNames.NONE);
            });

    private static void test(String name, TestSuite.Test<FakeGame.World> body) {
        SUITE.add(name, body);
    }

    static {
        test("factions: mod factions from the intel tab, with long name, name and prefix, sorted by id", w -> {
            w.faction("shadow_industry", "Shadowyards", "Shadowyards Reconstruction Authority", "Shadowyards", true);
            w.faction("blackrock_driveyards", "Blackrock", "Blackrock Drive Yards", "Blackrock", true);
            w.install();
            GameNames names = SectorNames.read();
            same(Arrays.asList("blackrock_driveyards", "shadow_industry"), new ArrayList<>(names.factions().keySet()), "ids");
            same(Arrays.asList("Shadowyards Reconstruction Authority", "Shadowyards", "shadow industry"),
                    names.factions().get("shadow_industry"), "names: long, short (prefix is the same), the id's own");
        });

        test("factions: the player's, ones hidden from the intel tab and vanilla ones are left out", w -> {
            w.faction("player", "Player", "Player Faction", "Player", true);
            w.faction("neutral", "Neutral", "Neutral", "Neutral", false);
            w.faction("poor", "Poor", "Poor", "Poor", false);
            w.faction("hegemony", "Hegemony", "Hegemony", "Hegemony", true);
            w.faction("pirates", "Pirates", "Pirates", "Pirate", true);
            w.faction("scy", "Scy Nation", "Scy Nation", "Scy", true);
            w.install();
            same(Collections.singleton("scy"), SectorNames.read().factions().keySet(), "kept");
        });

        test("factions: missing or blank names are skipped", w -> {
            w.faction("mod_x", null, "  ", "Mod X Prefix", true);
            w.install();
            same(Arrays.asList("Mod X Prefix", "mod x"), SectorNames.read().factions().get("mod_x"), "names");
        });

        test("places: systems, markets and the planets markets are on; hidden markets left out", w -> {
            StarSystemAPI system = w.system("New Caledonia", "Fort Rock");
            w.market("Port Mods", "Fort Rock", system, "independent", false);
            w.market("Smuggler Hideout", "Smuggler Hideout", system, "pirates", true);
            w.install();
            GameNames.install(SectorNames.read());
            for (String place : new String[]{"new caledonia", "port mods", "fort rock"}) {
                check(PlaceNames.isKnown(place), "lower-case " + show(place) + " is a place");
            }
            check(!PlaceNames.isKnown("smuggler hideout"), "a hidden market is not");
        });

        test("an empty sector: no factions, nothing breaks", w -> {
            w.install();
            check(SectorNames.read().factions().isEmpty(), "no factions");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }
}
