package KirizsKeve.MoveByText.nlp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Place names as the NLU models mark them, cleaned up for the game's place lookup. */
public final class PlaceNames {

    /** Vanilla Starsector systems and markets, lowercase, for accepting names the player didn't capitalize. */
    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "jangala", "asharu", "corvus", "askonia", "sindria", "volturn", "chicomoztoc", "aztlan", "kazeron",
            "fikenhild", "mazalot", "hybrasil", "culann", "eochu bres", "port tse", "eventide", "ilm", "tigra city",
            "agreus", "gilead", "hesperus", "kanta's den", "magec", "new maxios", "mairaath", "salamanca", "nortia",
            "valhalla", "westernesse", "ancyra", "galatia", "tartessus", "thule", "umbra", "cruor", "qaras", "lumen",
            "athulf", "mayasura", "samarra", "arcadia", "baetis", "nachiketa", "tyle", "ragnar complex", "sentinel",
            "canaan", "kumari kandam", "zagan", "penelope's star", "eldfell", "tlalocan", "orthrus", "isirah"));

    private PlaceNames() {
    }

    /**
     * A place name as the game's place lookup wants it: no ':' or '>', no "the" in front, no "system" after it.
     * Null for an all-lowercase word that isn't a known place: the model sometimes marks ordinary words
     * ("seizures", "drop") as places, while real names are capitalized or on the list.
     */
    static String clean(String name) {
        if (name == null) return null;
        String s = name.replaceAll("[:<>]", " ").trim();
        s = s.replaceAll("^[\\s\"'(\\[]+|[\\s\"'.,!?;)\\]]+$", "");
        s = s.replaceAll("(?i)^the\\s+", "");
        s = s.replaceAll("(?i)\\s+(star\\s+)?system$", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        if (s.equals(s.toLowerCase()) && !KNOWN.contains(s)) return null;
        return s;
    }

    /** True for a vanilla Starsector system or market ("Jangala", "the Askonia system"). */
    public static boolean isKnown(String name) {
        String p = clean(name);
        return p != null && KNOWN.contains(p.toLowerCase());
    }
}
