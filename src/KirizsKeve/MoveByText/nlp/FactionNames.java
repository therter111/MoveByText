package KirizsKeve.MoveByText.nlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faction names, nicknames, leaders, units and home worlds -> one of the 11 faction ids the game knows. The same
 * names are in the training templates (nlp/rasa/generate_nlu.py); add new ones in both places.
 */
public final class FactionNames {

    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();
    /** Longest aliases first, so "luddic church" wins over "luddic" and "remnant drones" over "drones". */
    private static final List<String> BY_LENGTH;

    static {
        alias("remnants", "remnant", "remnants", "ordo", "ordos", "nexus", "thinking machines", "rogue ai",
                "ai warships", "ai warship", "ai fleet");
        alias("pirates", "pirate", "pirates", "raider", "raiders", "kanta", "kantas", "buccaneers", "corsairs");
        alias("hegemony", "hegemony", "hegemonic", "xiv battlegroup", "xiv", "inspectorate", "ai inspectorate",
                "inspectors", "chicomoztoc", "hegemon");
        alias("luddic_path", "luddic path", "path", "pather", "pathers", "luddic", "militant luddites", "zealots");
        alias("luddic_church", "luddic church", "church", "church of galactic redemption", "knights of ludd",
                "pilgrims", "the faithful", "gilead", "hesperus");
        alias("persean", "persean", "persean league", "perseans", "league", "kazeron");
        alias("sindrian_diktat", "diktat", "sindrian diktat", "sindrian", "sindria", "askonia", "askonian",
                "askonians", "andrada", "philip andrada", "lion s guard", "lions guard", "lion guard", "executor");
        alias("tritachyon", "tri tachyon", "tritachyon", "tri tach", "tt", "megacorporation", "megacorp",
                "corporation", "hybrasil", "culann");
        alias("independent", "independent", "independents", "indie", "indies", "mercenary", "mercenaries", "mercs", "bounty hunter",
                "bounty hunters", "scavenger", "scavengers", "freelancer", "freelancers", "smugglers", "militia");
        alias("derelict", "derelict", "derelicts", "drone", "drones", "probe", "probes", "survey ships",
                "automated ships", "domain drones");
        alias("omega", "omega");

        List<String> keys = new ArrayList<String>(ALIASES.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        BY_LENGTH = keys;
    }

    private static void alias(String id, String... names) {
        for (String n : names) ALIASES.put(n, id);
    }

    private FactionNames() {
    }

    /** The faction id for a name, or null when it isn't a faction of the game. */
    public static String resolve(String name) {
        String n = normalize(name);
        if (n.isEmpty()) return null;
        String exact = ALIASES.get(n);
        if (exact != null) return exact;

        // A known name inside a longer phrase: "Kanta's raiders", "the Askonian navy"
        String padded = " " + n + " ";
        for (String alias : BY_LENGTH) {
            if (padded.contains(" " + alias + " ")) return ALIASES.get(alias);
        }

        // Typos: "tritachion", "hegemonny" - only for long words, so short made-up names don't slip through
        for (String word : n.split(" ")) {
            if (word.length() < 6) continue;
            int allowed = word.length() >= 8 ? 2 : 1;
            for (String alias : BY_LENGTH) {
                if (alias.indexOf(' ') >= 0 || alias.length() < 6) continue;
                if (distance(word, alias) <= allowed) return ALIASES.get(alias);
            }
        }
        return null;
    }

    /** Lower case, letters and digits only, single spaces: "Kanta's Den" -> "kanta s den". */
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Edits from a to b: a letter added, dropped or changed, or two neighbours swapped ("hegemnoy" is one edit
     * from "hegemony"). The same as distance() in nlp/spacy/rules.py.
     */
    static int distance(String a, String b) {
        int[] before = new int[b.length() + 1]; // the row two letters of a back, for the swap
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    cur[j] = Math.min(cur[j], before[j - 2] + 1);
                }
            }
            int[] t = before;
            before = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
