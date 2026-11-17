package KirizsKeve.MoveByText.nlp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The size words of the system prompt, plus the words for size math ("twice", "half", "plus 30"). */
public final class SizeWords {

    private static final Map<String, Integer> TIERS = new LinkedHashMap<>();
    private static final List<String> BY_LENGTH;

    static {
        tier(15, "tiny", "little", "lone", "single", "one ship", "a ship", "smallest", "minimal");
        tier(40, "small", "smaller", "minor", "few", "couple", "some", "handful", "scout", "scouts", "detachment");
        tier(100, "medium", "medium sized", "standard", "normal", "regular", "average", "typical", "moderate", "mid sized");
        tier(150, "big", "bigger", "large", "larger", "strong", "sizable", "sizeable");
        tier(225, "huge", "massive", "very large", "very big", "immense", "giant");
        tier(300, "humongous", "gigantic", "enormous", "colossal", "armada", "invasion", "biggest", "largest",
                "maximum", "max", "the biggest possible");

        List<String> keys = new ArrayList<>(TIERS.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        BY_LENGTH = keys;
    }

    private static void tier(int points, String... words) {
        for (String w : words) TIERS.put(w, points);
    }

    private SizeWords() {
    }

    /** 15, 40, 100, 150, 225 or 300 for a size word, or null. */
    public static Integer tier(String text) {
        String n = " " + FactionNames.normalize(text) + " ";
        for (String word : BY_LENGTH) {
            if (n.contains(" " + word + " ")) return TIERS.get(word);
        }
        return null;
    }

    /** "twice" 2, "half" 0.5, "a third of" 1/3, "two thirds" 2/3, "three times" 3, "50 percent bigger" 1.5. */
    static Double multiplier(String text) {
        String t = " " + FactionNames.normalize(text) + " ";
        Double percent = percent(text);
        if (percent != null) {
            if (t.matches(".* (bigger|larger|stronger|more|greater|tougher) .*")) return 1 + percent / 100;
            if (t.matches(".* (smaller|less|weaker|fewer) .*")) return 1 - percent / 100;
            return percent / 100;
        }
        if (t.contains(" two thirds ")) return 2.0 / 3;
        if (t.contains(" three quarters ")) return 0.75;
        if (t.matches(".* (half|halved|halve) .*")) return 0.5;
        if (t.matches(".* (a|one) third .*") || t.contains(" third ")) return 1.0 / 3;
        if (t.matches(".* (a|one) (quarter|fourth) .*") || t.matches(".* (quarter|fourth) .*")) return 0.25;
        if (t.matches(".* (twice|double|doubled|two times) .*")) return 2.0;
        if (t.matches(".* (triple|tripled|thrice|three times) .*")) return 3.0;
        if (t.matches(".* (quadruple|four times) .*")) return 4.0;
        if (t.contains(" times ")) {
            Double n = NumberWords.parse(text);
            if (n != null && n > 0) return n;
        }
        return null;
    }

    /** "plus 30" +30, "10 points smaller" -10, "another 50" +50, "minus 10" -10. */
    static Double offset(String text) {
        Double n = NumberWords.parse(text);
        if (n == null) return null;
        String t = " " + FactionNames.normalize(text) + " ";
        boolean negative = n < 0 || t.matches(".* (minus|less|smaller|fewer|weaker|lower|below|under|off|subtract|take) .*");
        double size = Math.abs(n);
        return negative ? -size : size;
    }

    /** "50 percent", "50%", "10 per cent" -> 50, 50, 10. */
    private static Double percent(String text) {
        String t = text.toLowerCase();
        if (!t.contains("%") && !t.contains("percent") && !t.contains("per cent")) return null;
        return NumberWords.parse(t);
    }
}
