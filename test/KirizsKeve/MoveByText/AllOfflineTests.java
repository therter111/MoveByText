package KirizsKeve.MoveByText;

import KirizsKeve.MoveByText.command.CommandFailureTest;
import KirizsKeve.MoveByText.command.CommandParserTest;
import KirizsKeve.MoveByText.game.FakeGame;
import KirizsKeve.MoveByText.game.FleetSpawnerTest;
import KirizsKeve.MoveByText.game.PlaceFinderTest;
import KirizsKeve.MoveByText.game.SectorNamesTest;
import KirizsKeve.MoveByText.nlp.CommandBuilderTest;
import KirizsKeve.MoveByText.nlp.ConsistencyTest;
import KirizsKeve.MoveByText.nlp.RasaClientTest;
import KirizsKeve.MoveByText.nlp.SettingsTest;
import KirizsKeve.MoveByText.nlp.gemini.GeminiClientTest;
import KirizsKeve.MoveByText.nlp.koboldcpp.KoboldCppClientTest;

import java.util.Arrays;
import java.util.List;

/**
 * Every offline suite in one run: no game (FakeGame stands in where one is needed), no network, no API key. python build.py runs this after compiling; from
 * IntelliJ, run main() with the mod folder as the working directory. Pass a word to run only the tests whose name
 * contains it. Exits with 1 on any failure. LlmPromptTest is not in here: it sends real requests.
 */
public final class AllOfflineTests {

    private AllOfflineTests() {
    }

    /** Every offline suite, in the order they run. CodeRulesTest checks that no suite is missing here. */
    static List<TestSuite<?>> suites() {
        return Arrays.asList(
                CommandParserTest.SUITE,
                CommandBuilderTest.SUITE,
                CommandConsoleTest.SUITE,
                ComposerTest.SUITE,
                PanelTest.SUITE,
                SettingsTest.SUITE,
                GameLoadTest.SUITE,
                PlaceFinderTest.SUITE,
                SectorNamesTest.SUITE,
                CommandFailureTest.SUITE,
                FleetSpawnerTest.SUITE,
                ConsistencyTest.SUITE,
                ProjectFilesTest.SUITE,
                CodeRulesTest.SUITE,
                GeminiClientTest.SUITE,
                KoboldCppClientTest.SUITE,
                RasaClientTest.SUITE);
    }

    public static void main(String[] args) {
        TestSuite.silenceLogs(); // before the suites below load the code under test
        FakeGame.loadMisc(); // the game's Misc reads the settings once; it must find fake ones (see FakeGame)
        String filter = args.length > 0 ? args[0] : null;
        List<TestSuite<?>> suites = suites();

        int run = 0, failed = 0;
        StringBuilder summary = new StringBuilder();
        for (TestSuite<?> suite : suites) {
            System.out.println("== " + suite.name() + " ==");
            TestSuite.Result r = suite.run(filter);
            System.out.println();
            run += r.run;
            failed += r.failed;
            summary.append(String.format("  %-22s %d/%d%s%n", suite.name(), r.run - r.failed, r.run,
                    r.failed > 0 ? "   <-- " + r.failed + " failed" : ""));
        }
        System.out.println("== all offline tests ==");
        System.out.print(summary);
        System.out.println((run - failed) + "/" + run + " passed");
        System.exit(failed == 0 && run > 0 ? 0 : 1);
    }
}
