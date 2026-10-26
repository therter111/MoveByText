package KirizsKeve.MoveByText;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a number the way players write it: "120", "1,000", "150.6", "-50", "ninety-five", "two hundred",
 * "a hundred and twenty-five". Used by RasaCommandBuilder, which gets the numbers as plain text from Rasa.
 */
final class NumberWords {

    private static final Pattern DIGITS = Pattern.compile("-?\\d[\\d,]*(\\.\\d+)?");
    private static final Map<String, Integer> SMALL = new HashMap<String, Integer>();
    private static final Map<String, Integer> TENS = new HashMap<String, Integer>();

    static {
        String[] small = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < small.length; i++) SMALL.put(small[i], i);
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) TENS.put(tens[i], (i + 2) * 10);
    }

    private NumberWords() {
    }

    /** The first number in the text, or null when it has none. Digits win over words. */
    static Double parse(String text) {
        if (text == null) return null;
        String t = text.toLowerCase();

        Matcher m = DIGITS.matcher(t);
        if (m.find()) {
            try {
                return Double.valueOf(m.group().replace(",", ""));
            } catch (NumberFormatException e) {
                // "1,2,3" and the like: try the words below
            }
        }
        return words(t);
    }

    /** "two hundred and twenty-five" -> 225; skips words before the number and stops at the first word after it. */
    private static Double words(String text) {
        String[] tokens = text.split("[^a-z]+");
        long total = 0;
        long current = 0;
        boolean found = false;
        for (int i = 0; i < tokens.length; i++) {
            String w = tokens[i];
            if (w.isEmpty()) continue;
            Integer small = SMALL.get(w);
            Integer tens = TENS.get(w);
            if (small != null) {
                current += small;
                found = true;
            } else if (tens != null) {
                current += tens;
                found = true;
            } else if (w.equals("hundred")) {
                current = (current == 0 ? 1 : current) * 100;
                found = true;
            } else if (w.equals("thousand")) {
                total += (current == 0 ? 1 : current) * 1000;
                current = 0;
                found = true;
            } else if (w.equals("and") && found) {
                // "a hundred and twenty"
            } else if ((w.equals("a") || w.equals("an")) && i + 1 < tokens.length
                    && (tokens[i + 1].equals("hundred") || tokens[i + 1].equals("thousand"))) {
                // "a hundred": the scale word counts it as one
            } else if (found) {
                break;
            }
        }
        return found ? Double.valueOf(total + current) : null;
    }
}
