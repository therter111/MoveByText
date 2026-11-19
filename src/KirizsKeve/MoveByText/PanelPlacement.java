package KirizsKeve.MoveByText;

import org.json.JSONArray;

/**
 * Where the panel goes on the screen: by default the left edge under the campaign screen's top bar, out of the way
 * of the player's fleet in the middle of the map; or "panelPosition" from the settings; always kept on the screen.
 * Positions are the panel's top left corner, in screen pixels from the screen's top left, as inTL takes them.
 */
final class PanelPlacement {

    /** From the screen's left edge, by default. */
    static final float LEFT = 20f;
    /** From the screen's top, by default: below the campaign screen's top bar. */
    static final float TOP = 120f;

    private PanelPlacement() {
    }

    static float[] defaultTopLeft() {
        return new float[]{LEFT, TOP};
    }

    /** The corner moved just so far that the whole panel is on the screen; if it can't fit, its top left stays on it. */
    static float[] clamp(float x, float y, float panelWidth, float panelHeight, float screenWidth, float screenHeight) {
        return new float[]{
                Math.max(0f, Math.min(x, screenWidth - panelWidth)),
                Math.max(0f, Math.min(y, screenHeight - panelHeight))};
    }

    /** "panelPosition" as the settings may have it: "x, y" or [x, y]; null when it is missing or not two numbers. */
    static float[] parse(Object value) {
        double[] xy = null;
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            if (a.length() == 2) xy = new double[]{a.optDouble(0, Double.NaN), a.optDouble(1, Double.NaN)};
        } else if (value instanceof String) {
            String[] parts = ((String) value).trim().split("\\s*[,;]\\s*|\\s+");
            if (parts.length == 2) {
                try {
                    xy = new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (xy == null || Double.isNaN(xy[0]) || Double.isNaN(xy[1])) return null;
        return new float[]{(float) xy[0], (float) xy[1]};
    }
}
