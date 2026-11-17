package KirizsKeve.MoveByText.nlp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the running game has beyond the 11 vanilla factions the prompt and the NLU models know: factions added by
 * other mods, and the sector's place names. game/SectorNames reads them from the sector on every game load; this
 * class holds them as plain data, so the backends can use them on their worker thread.
 *
 * - FactionNames falls back to the mod factions' names when no vanilla name matches.
 * - PlaceNames accepts the sector's places as well as the vanilla list.
 * - SystemPrompt lists the mod factions in the LLM prompt.
 *
 * Immutable. install() makes a snapshot the one current() returns, for every thread.
 */
public final class GameNames {

    /** More mod factions than this are left out, so the prompt stays a sensible size. */
    public static final int MAX_FACTIONS = 40;
    /** Shorter names are ignored: "tt"-style abbreviations of mod factions would match too much. */
    static final int MIN_NAME_LENGTH = 3;

    /** No mod factions, no places: the vanilla behavior, and what the offline tests start with. */
    public static final GameNames NONE = new GameNames(Collections.<String, List<String>>emptyMap(),
            Collections.<String>emptySet());

    private static volatile GameNames current = NONE;

    /** Faction id -> the names shown for it in the prompt, as the game writes them; vanilla ids are left out. */
    private final Map<String, List<String>> factions;
    /** Normalized name (see FactionNames.normalize) -> faction id; longest names first. */
    private final Map<String, String> factionByName;
    /** Lower-case place names. */
    private final Set<String> places;

    /**
     * factions: id -> the faction's names (display name, long name, ...) in the order to show them. Vanilla ids,
     * names shorter than MIN_NAME_LENGTH and names that are already a vanilla faction's alias are skipped, and the id
     * itself (with spaces for '_') is added as a name. places: any case.
     */
    public GameNames(Map<String, List<String>> factions, Set<String> places) {
        Map<String, List<String>> shown = new LinkedHashMap<>();
        Map<String, String> byName = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> f : factions.entrySet()) {
            String id = f.getKey() == null ? "" : f.getKey().trim();
            if (id.isEmpty() || FactionNames.ids().contains(id) || shown.size() >= MAX_FACTIONS) continue;

            List<String> names = new ArrayList<>();
            List<String> candidates = new ArrayList<>(f.getValue() == null ? Collections.<String>emptyList() : f.getValue());
            candidates.add(id.replace('_', ' '));
            for (String name : candidates) {
                if (name == null) continue;
                String n = FactionNames.normalize(name);
                if (n.length() < MIN_NAME_LENGTH || FactionNames.isVanillaAlias(n) || byName.containsKey(n)) continue;
                byName.put(n, id);
                names.add(name.trim());
            }
            if (!names.isEmpty()) shown.put(id, Collections.unmodifiableList(names)); // no name left: nothing finds it
        }
        this.factions = Collections.unmodifiableMap(shown);

        List<String> keys = new ArrayList<>(byName.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String k : keys) sorted.put(k, byName.get(k));
        this.factionByName = Collections.unmodifiableMap(sorted);

        Set<String> p = new LinkedHashSet<>();
        for (String place : places) {
            if (place == null) continue;
            String s = place.trim().toLowerCase();
            if (s.length() >= MIN_NAME_LENGTH) p.add(s);
        }
        this.places = Collections.unmodifiableSet(p);
    }

    /** The snapshot the backends use; NONE until a game is loaded. */
    public static GameNames current() {
        return current;
    }

    /** Makes names the current snapshot. Called on every game load, before the backend is created. */
    public static void install(GameNames names) {
        current = names != null ? names : NONE;
    }

    /** Mod faction id -> its names as shown in the prompt, the id's own name last. */
    public Map<String, List<String>> factions() {
        return factions;
    }

    /** The mod faction a normalized name (or a phrase containing one) means, or null. */
    String factionFor(String normalized) {
        String exact = factionByName.get(normalized);
        if (exact != null) return exact;
        String padded = " " + normalized + " ";
        for (Map.Entry<String, String> e : factionByName.entrySet()) {
            if (padded.contains(" " + e.getKey() + " ")) return e.getValue();
        }
        return null;
    }

    /** True for a star system, market or station of the running game; lowerCaseName must be lower case. */
    boolean hasPlace(String lowerCaseName) {
        return places.contains(lowerCaseName);
    }
}
