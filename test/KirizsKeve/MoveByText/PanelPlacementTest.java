package KirizsKeve.MoveByText;

import org.json.JSONArray;

import java.util.Arrays;

import static KirizsKeve.MoveByText.Check.*;

/**
 * Offline tests for PanelPlacement: reading "panelPosition" from the settings, the default place, and keeping the
 * panel on the screen. PanelTest checks that dragging the panel goes through the same clamp.
 *
 * Run main() from IntelliJ, or every offline suite with AllOfflineTests.
 */
public class PanelPlacementTest {

    public static final TestSuite<Void> SUITE = TestSuite.plain("PanelPlacementTest");

    private static void test(String name, TestSuite.PlainTest body) {
        SUITE.add(name, body);
    }

    static {
        // ------------------------------------------------------------------------------------------
        // "panelPosition" in llmdata.json
        // ------------------------------------------------------------------------------------------

        test("parse: \"x, y\" with a comma, semicolon or space between, padding allowed", () -> {
            for (String s : new String[]{"20, 120", "20,120", "20 120", "20;120", "20 ; 120", "  20 ,   120  "}) {
                same("20.0, 120.0", xy(PanelPlacement.parse(s)), "parse(" + show(s) + ")");
            }
        });

        test("parse: decimals and negative numbers are read as written (clamp keeps them on the screen)", () -> {
            same("-5.5, 7.25", xy(PanelPlacement.parse("-5.5, 7.25")), "string");
            same("1000.0, 0.0", xy(PanelPlacement.parse("1e3, 0")), "an exponent, as Java reads numbers");
        });

        test("parse: a JSON list of two numbers, or of two numbers written as text", () -> {
            same("20.0, 120.0", xy(PanelPlacement.parse(new JSONArray("[20, 120]"))), "numbers");
            same("20.5, 120.0", xy(PanelPlacement.parse(new JSONArray("[20.5, 120]"))), "a decimal");
            same("20.0, 120.0", xy(PanelPlacement.parse(new JSONArray("[\"20\", \"120\"]"))), "text");
        });

        test("parse: anything else is null, so the default place is used", () -> {
            Object[] bad = {null, 20, 20.0, true, "", "20", "a, b", "20, b", "1, 2, 3", "NaN, 5", "5, NaN",
                    "[20, 120]", new JSONArray("[1]"), new JSONArray("[1, 2, 3]"), new JSONArray("[\"x\", 1]"),
                    new JSONArray("[]"), new JSONArray("[null, 1]")};
            for (Object value : bad) {
                same(null, PanelPlacement.parse(value), "parse(" + show(value) + ")");
            }
        });

        // ------------------------------------------------------------------------------------------
        // The default place and the screen's edges
        // ------------------------------------------------------------------------------------------

        test("default: the left edge under the top bar, a fresh array each time", () -> {
            float[] at = PanelPlacement.defaultTopLeft();
            same(PanelPlacement.LEFT + ", " + PanelPlacement.TOP, at[0] + ", " + at[1], "default");
            at[0] = 999f;
            same(PanelPlacement.LEFT, PanelPlacement.defaultTopLeft()[0], "changing one doesn't change the next");
        });

        test("clamp: a panel that fits stays where it is", () -> {
            same("20.0, 120.0", xy(PanelPlacement.clamp(20, 120, 720, 435, 1920, 1080)), "inside");
            same("1200.0, 645.0", xy(PanelPlacement.clamp(1200, 645, 720, 435, 1920, 1080)), "exactly at the bottom right");
        });

        test("clamp: past the right or bottom edge it is pulled in just so far that all of it is on the screen", () -> {
            same("1200.0, 120.0", xy(PanelPlacement.clamp(1800, 120, 720, 435, 1920, 1080)), "right");
            same("20.0, 645.0", xy(PanelPlacement.clamp(20, 1000, 720, 435, 1920, 1080)), "bottom");
            same("1200.0, 645.0", xy(PanelPlacement.clamp(5000, 5000, 720, 435, 1920, 1080)), "both");
        });

        test("clamp: past the left or top edge it goes to 0", () -> {
            same("0.0, 0.0", xy(PanelPlacement.clamp(-50, -10, 720, 435, 1920, 1080)), "negative");
            same("0.0, 120.0", xy(PanelPlacement.clamp(-0.5f, 120, 720, 435, 1920, 1080)), "a little left");
        });

        test("clampSize: between the smallest size and the screen; the smallest wins on a tiny screen", () -> {
            same("800.0, 500.0", xy(PanelPlacement.clampSize(800, 500, 700, 300, 1920, 1080)), "inside");
            same("700.0, 300.0", xy(PanelPlacement.clampSize(100, 50, 700, 300, 1920, 1080)), "too small");
            same("1920.0, 1080.0", xy(PanelPlacement.clampSize(5000, 5000, 700, 300, 1920, 1080)), "too big");
            same("700.0, 300.0", xy(PanelPlacement.clampSize(800, 500, 700, 300, 640, 200)), "a screen smaller than the smallest");
        });

        test("clamp: a panel bigger than the screen keeps its top left corner on it", () -> {
            same("0.0, 0.0", xy(PanelPlacement.clamp(300, 300, 720, 435, 640, 400)), "too big both ways");
            same("0.0, 100.0", xy(PanelPlacement.clamp(300, 100, 720, 435, 640, 1080)), "too wide only");
        });
    }

    public static void main(String[] args) {
        SUITE.main(args);
    }

    /** "x, y", or null; a list instead when it isn't two numbers, to show what came back. */
    private static String xy(float[] at) {
        if (at == null) return null;
        if (at.length != 2) return Arrays.toString(at);
        return at[0] + ", " + at[1];
    }
}
