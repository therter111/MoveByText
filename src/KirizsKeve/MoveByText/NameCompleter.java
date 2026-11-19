package KirizsKeve.MoveByText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab completion in the editor: the last word or words of the text completed to a name the running game has (a
 * faction, star system, market or station), e.g. "send a convoy to port t" -> "send a convoy to Port Tse ". Pressing
 * Tab again, on the text it gave, gives the next name that fits. Game thread only.
 */
final class NameCompleter {

    /** Names of up to this many words are completed ("Church of Galactic Redemption" is four). */
    private static final int MAX_WORDS = 4;

    /** The names, shortest first, then alphabetically; one spelling per name. */
    private final List<String> names;

    /** The last completion, so the next Tab on the text it gave moves on to the next name. */
    private String lastResult;
    private String lastBase;
    private List<String> lastCandidates;
    private int lastIndex;

    NameCompleter(Collection<String> names) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String n : names) {
            if (n == null || n.trim().isEmpty()) continue;
            byKey.putIfAbsent(n.trim().toLowerCase(Locale.ROOT), n.trim());
        }
        List<String> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> a.length() != b.length() ? a.length() - b.length() : a.compareToIgnoreCase(b));
        this.names = sorted;
    }

    /** The text with its end completed to a name, and a space after it; null when no name starts like its end. */
    String complete(String text) {
        if (text == null) return null;
        if (text.equals(lastResult) && lastCandidates != null && lastCandidates.size() > 1) {
            lastIndex = (lastIndex + 1) % lastCandidates.size();
            lastResult = lastBase + lastCandidates.get(lastIndex) + " ";
            return lastResult;
        }
        lastResult = null;
        lastCandidates = null;
        if (text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1))) return null;

        // The longest end of the text that some name starts with: "port t" before "t"
        List<Integer> wordStarts = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i)) && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) wordStarts.add(i);
        }
        for (int k = Math.min(MAX_WORDS, wordStarts.size()); k >= 1; k--) {
            int start = wordStarts.get(wordStarts.size() - k);
            String end = text.substring(start).toLowerCase(Locale.ROOT);
            List<String> candidates = new ArrayList<>();
            for (String n : names) {
                if (n.length() > end.length() && n.toLowerCase(Locale.ROOT).startsWith(end)) candidates.add(n);
            }
            if (!candidates.isEmpty()) {
                lastBase = text.substring(0, start);
                lastCandidates = candidates;
                lastIndex = 0;
                lastResult = lastBase + candidates.get(0) + " ";
                return lastResult;
            }
        }
        return null;
    }
}
