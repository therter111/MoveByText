package KirizsKeve.MoveByText.game;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stand-in for the running game, for offline tests of code that asks Global for the sector or the settings.
 * Everything the mod reads from the game is an interface, so each fake is a java.lang.reflect.Proxy that answers the
 * methods a test names and gives null, 0, false, an empty collection or Color.WHITE for every other one (deep():
 * a "nothing" fake for interfaces too, so call chains like getPosition().inTL(...) just work).
 *
 * A test builds a World (star systems, markets, factions, the player's fleet), calls install(), and calls
 * uninstall() when done, so the next test starts without a game again.
 *
 * Misc, the game's helper class (colors, distances), reads the settings once, when it is first used, and stays
 * broken for the whole JVM if there were none. So this class loads it right away with settings it can live with;
 * AllOfflineTests calls loadMisc() before anything else too.
 */
public final class FakeGame {

    /** The size of a light year in map units, for Misc.getDistanceLY; the game's own is in the same range. */
    public static final float UNITS_PER_LIGHT_YEAR = 2000f;

    private static boolean miscLoaded = false;

    static {
        loadMisc();
    }

    private FakeGame() {
    }

    /** A method's answer, worked out from its arguments. */
    public interface Answer {
        Object answer(Object[] args) throws Exception;
    }

    /**
     * A fake of an interface: namesAndValues is method name, value, name, value...; a value that is an Answer is
     * called with the arguments. label is what toString() gives, for failure messages.
     */
    public static <T> T fake(Class<T> type, String label, Object... namesAndValues) {
        return make(type, label, false, namesAndValues);
    }

    /** Like fake(), but every method not named that returns an interface gives a deep fake of it, not null. */
    public static <T> T deep(Class<T> type, String label, Object... namesAndValues) {
        return make(type, label, true, namesAndValues);
    }

    @SuppressWarnings("unchecked")
    private static <T> T make(Class<T> type, String label, boolean deep, Object... namesAndValues) {
        Map<String, Object> answers = new HashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) answers.put((String) namesAndValues[i], namesAndValues[i + 1]);
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object[] a = args == null ? new Object[0] : args;
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "toString": return label;
                    case "equals": return proxy == a[0];
                    default: return System.identityHashCode(proxy);
                }
            }
            if (answers.containsKey(method.getName())) {
                Object value = answers.get(method.getName());
                return value instanceof Answer ? ((Answer) value).answer(a) : value;
            }
            Class<?> r = method.getReturnType();
            if (deep && r.isInterface() && !Collection.class.isAssignableFrom(r) && !Map.class.isAssignableFrom(r)) {
                return make(r, label + "." + method.getName() + "()", true);
            }
            return nothing(r);
        });
    }

    private static Object nothing(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == List.class || type == Collection.class) return new ArrayList<>();
        if (type == Set.class) return new HashSet<>();
        if (type == Map.class) return new HashMap<>();
        if (type == Color.class) return Color.WHITE;
        return null;
    }

    /** Puts the game back to "not running": no sector, no settings. */
    public static void uninstall() {
        Global.setSector(null);
        Global.setSettings(null);
    }

    /** Loads Misc with settings it can read its constants from (once per JVM; see the class comment). */
    public static synchronized void loadMisc() {
        if (miscLoaded) return;
        SettingsAPI before = Global.getSettings();
        Global.setSettings(settings(Collections.<String, String>emptyMap(), true));
        try {
            Class.forName("com.fs.starfarer.api.util.Misc");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Misc is not on the classpath", e);
        } finally {
            Global.setSettings(before);
        }
        miscLoaded = true;
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    /**
     * Settings that read the given files: path -> text. loadJSON drops '#' comment lines first, as the game's own
     * reader allows them. A path that isn't there throws IOException. With modLookupWorks false, loadText(path, modId)
     * always throws, to test the fallback to loadText(path). They also answer what Misc asks (colors, numbers, the
     * size of a light year). extra: more method name, value pairs, e.g. createCustom for the UI.
     */
    public static SettingsAPI settings(Map<String, String> files, boolean modLookupWorks, Object... extra) {
        Answer loadText = args -> {
            if (args.length > 1 && !modLookupWorks) throw new IOException("no mod lookup in this fake");
            String text = files.get((String) args[0]);
            if (text == null) throw new IOException("not found: " + args[0]);
            return text;
        };
        Answer loadJSON = args -> {
            String text = files.get((String) args[0]);
            if (text == null) throw new IOException("not found: " + args[0]);
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n")) {
                if (!line.trim().startsWith("#")) sb.append(line).append('\n');
            }
            return new JSONObject(sb.toString());
        };
        // Misc.getUnitsPerLightYear() reads "unitsPerLightYear" on every call, so getDistanceLY needs it right
        Answer getFloat = args -> "unitsPerLightYear".equals(args[0]) ? UNITS_PER_LIGHT_YEAR : 1f;
        // A color of its own for each name, as in the game: "textColor" and "textNegativeColor" are different
        Answer getColor = args -> new Color(String.valueOf(args[0]).hashCode() & 0xFFFFFF);
        List<Object> answers = pairs("loadText", loadText, "loadJSON", loadJSON, "getFloat", getFloat, "getInt", 1,
                "getColor", getColor);
        answers.addAll(Arrays.asList(extra));
        return fake(SettingsAPI.class, "fake settings", answers.toArray());
    }

    /** Method name, value pairs as a list that may hold nulls (List.of can't). */
    private static List<Object> pairs(Object... namesAndValues) {
        return new ArrayList<>(Arrays.asList(namesAndValues));
    }

    // ------------------------------------------------------------------------------------------
    // Fleets
    // ------------------------------------------------------------------------------------------

    /** One addAssignment call. */
    public static final class Assignment {
        public final FleetAssignment type;
        public final SectorEntityToken target;
        public final float days;
        public final String text;

        Assignment(FleetAssignment type, SectorEntityToken target, float days, String text) {
            this.type = type;
            this.target = target;
            this.days = days;
            this.text = text;
        }

        @Override
        public String toString() {
            return type + " " + target + " " + days + " \"" + text + "\"";
        }
    }

    /** A fleet the code under test sets up: what it was named, given, told and where it went. */
    public static final class FakeFleet {
        public final CampaignFleetAPI api;
        public final Map<String, Object> memory = new LinkedHashMap<>();
        public final List<Assignment> assignments = new ArrayList<>();
        public String name;
        public LocationAPI location;
        public final Vector2f at = new Vector2f();
        public final FactionAPI faction;
        public final int members;
        public final int points;

        /** members 0: an empty fleet, as FleetFactoryV3 makes for a faction without ships. */
        public FakeFleet(FactionAPI faction, int members, int points) {
            this.faction = faction;
            this.members = members;
            this.points = points;
            MemoryAPI mem = fake(MemoryAPI.class, "fleet memory",
                    "set", (Answer) args -> memory.put((String) args[0], args[1]));
            FleetDataAPI data = fake(FleetDataAPI.class, "fleet data", "getNumMembers", members);
            this.api = fake(CampaignFleetAPI.class, "fake fleet",
                    "setName", (Answer) args -> name = (String) args[0],
                    "getName", (Answer) args -> name,
                    "getFaction", faction,
                    "getFleetData", data,
                    "getFleetPoints", points,
                    "getMemoryWithoutUpdate", mem,
                    "getMemory", mem,
                    "getContainingLocation", (Answer) args -> location,
                    "setLocation", (Answer) args -> {
                        at.set((Float) args[0], (Float) args[1]);
                        return null;
                    },
                    "getLocation", at,
                    "addAssignment", (Answer) args -> assignments.add(new Assignment((FleetAssignment) args[0],
                            (SectorEntityToken) args[1], (Float) args[2], args.length > 3 && args[3] instanceof String
                            ? (String) args[3] : null)),
                    "getCurrentAssignment", (Answer) args -> assignments.isEmpty() ? null
                            : fake(FleetAssignmentDataAPI.class, "current assignment",
                            "getAssignment", assignments.get(0).type,
                            "getTarget", assignments.get(0).target,
                            "getActionText", assignments.get(0).text));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The sector
    // ------------------------------------------------------------------------------------------

    /** A sector to build up, then install(). */
    public static final class World {
        public final List<StarSystemAPI> systems = new ArrayList<>();
        public final List<MarketAPI> markets = new ArrayList<>();
        public final Map<String, FactionAPI> factions = new LinkedHashMap<>();
        /** What the code under test gave to addTransientScript, in order. */
        public final List<Object> transientScripts = new ArrayList<>();
        /** faction id -> the ids it is hostile to. */
        private final Map<String, Set<String>> hostile = new HashMap<>();
        /** Each location's fleets, by the location. */
        private final Map<Object, List<CampaignFleetAPI>> fleets = new HashMap<>();
        /** The fake behind each fleet the code under test was given, by its api. */
        private final Map<Object, FakeFleet> fakeFleets = new HashMap<>();
        /** Locations that ignore addEntity, to test a fleet that didn't land. */
        private final Set<Object> refusing = new HashSet<>();
        private final Map<Object, Vector2f> hyperLocations = new HashMap<>();
        /** Where the player's fleet is; null: no player fleet. */
        public LocationAPI playerLocation;
        public final Vector2f playerAt = new Vector2f();
        /** The campaign screen's message list; null: there is none, as outside the campaign. */
        public CampaignUIAPI campaignUi;
        private LocationAPI hyperspace;

        /**
         * A star system: getBaseName() is name, getName() is "name Star System", as in the game. Planet names
         * starting with '*' are stars. It holds fleets (addEntity, getFleets) and sits at (0, 0) in hyperspace
         * unless at() moves it.
         */
        public StarSystemAPI system(String name, String... planetNames) {
            List<PlanetAPI> planets = new ArrayList<>();
            Object[] self = new Object[1];
            List<Object> answers = locationAnswers(name == null ? null : name + " Star System", self);
            answers.addAll(pairs(
                    "getBaseName", name,
                    "getPlanets", planets,
                    "getLocation", (Answer) args -> hyperLocations.getOrDefault(self[0], new Vector2f()),
                    "getCenter", (Answer) args -> token(name + " center", (LocationAPI) self[0], 0, 0)));
            StarSystemAPI system = fake(StarSystemAPI.class, "system " + name, answers.toArray());
            self[0] = system;
            for (String p : planetNames) {
                boolean star = p.startsWith("*");
                String planetName = star ? p.substring(1) : p;
                planets.add(fake(PlanetAPI.class, "planet " + planetName, "getName", planetName, "isStar", star));
            }
            systems.add(system);
            return system;
        }

        /** Hyperspace: a location that isn't a star system; it holds fleets like one. */
        public LocationAPI hyperspace() {
            if (hyperspace == null) {
                Object[] self = new Object[1];
                hyperspace = fake(LocationAPI.class, "hyperspace", locationAnswers("Hyperspace", self).toArray());
                self[0] = hyperspace;
            }
            return hyperspace;
        }

        /** Puts a system at (x, y) in hyperspace, in map units. */
        public void at(StarSystemAPI system, float x, float y) {
            hyperLocations.put(system, new Vector2f(x, y));
        }

        /**
         * The answers every location shares: its name, and holding fleets (addEntity, getFleets, createToken).
         * self[0] is the location itself, set as soon as its proxy exists: a Proxy can't name itself while it is made.
         */
        private List<Object> locationAnswers(String name, Object[] self) {
            return pairs(
                    "getName", name,
                    "addEntity", (Answer) args -> {
                        if (refusing.contains(self[0])) return null;
                        FakeFleet f = fakeFleets.get(args[0]);
                        if (f != null) {
                            f.location = (LocationAPI) self[0];
                            fleets.computeIfAbsent(self[0], k -> new ArrayList<>()).add(f.api);
                        }
                        return null;
                    },
                    "getFleets", (Answer) args -> new ArrayList<>(fleetsIn((LocationAPI) self[0])),
                    "createToken", (Answer) args -> {
                        Vector2f v = args.length == 1 ? (Vector2f) args[0] : new Vector2f((Float) args[0], (Float) args[1]);
                        return token("token at (" + (int) v.x + ", " + (int) v.y + ")", (LocationAPI) self[0], v.x, v.y);
                    });
        }

        /** The fleets addEntity put into the location; the fake behind one with fleet(). */
        public List<CampaignFleetAPI> fleetsIn(LocationAPI location) {
            return fleets.getOrDefault(location, Collections.<CampaignFleetAPI>emptyList());
        }

        /** The FakeFleet behind a fleet the code under test was handed. */
        public FakeFleet fleet(CampaignFleetAPI api) {
            return fakeFleets.get(api);
        }

        /** Makes a fleet for the code under test, as a FleetSpawner.FleetMaker would. */
        public FakeFleet newFleet(String factionId, int members, int points) {
            FactionAPI faction = factions.containsKey(factionId) ? factions.get(factionId) : faction(factionId);
            FakeFleet f = new FakeFleet(faction, members, points);
            fakeFleets.put(f.api, f);
            return f;
        }

        /** From now on the location ignores addEntity, as if the fleet never landed there. */
        public void refuseFleets(LocationAPI location) {
            refusing.add(location);
        }

        /** A visible market whose primary entity has the same name. */
        public MarketAPI market(String name, StarSystemAPI system, String factionId) {
            return market(name, name, system, factionId, false);
        }

        /** A market on a primary entity named entityName (null: no entity); system null: one in hyperspace. */
        public MarketAPI market(String name, String entityName, StarSystemAPI system, String factionId, boolean hidden) {
            return market(name, entityName, system, factionId, hidden, 5);
        }

        /** The same, with its size (3 to 10 in the game). */
        public MarketAPI market(String name, String entityName, StarSystemAPI system, String factionId, boolean hidden,
                                int size) {
            SectorEntityToken entity = entityName == null ? null : token(entityName, system, 100, 100);
            MarketAPI market = fake(MarketAPI.class, "market " + name,
                    "getName", name,
                    "getPrimaryEntity", entity,
                    "getStarSystem", system,
                    "getContainingLocation", system,
                    "isHidden", hidden,
                    "getFactionId", factionId,
                    "getFaction", (Answer) args -> factions.get(factionId),
                    "getSize", size);
            markets.add(market);
            return market;
        }

        /**
         * A faction with its names; shown in the intel tab unless that is false. The player's faction is the one
         * with the id "player".
         */
        public FactionAPI faction(String id, String displayName, String longName, String prefix, boolean inIntelTab) {
            FactionAPI faction = fake(FactionAPI.class, "faction " + id,
                    "getId", id,
                    "getDisplayName", displayName,
                    "getDisplayNameLong", longName,
                    "getEntityNamePrefix", prefix,
                    "isShowInIntelTab", inIntelTab,
                    "isPlayerFaction", "player".equals(id),
                    "isHostileTo", (Answer) args -> {
                        Object other = args[0];
                        String otherId = other instanceof FactionAPI ? ((FactionAPI) other).getId() : (String) other;
                        return hostile.getOrDefault(id, Collections.<String>emptySet()).contains(otherId);
                    });
            factions.put(id, faction);
            return faction;
        }

        /** A faction known only by its id, shown in the intel tab. */
        public FactionAPI faction(String id) {
            return faction(id, id, id, id, true);
        }

        /** a and b are hostile to each other. */
        public void hostile(String a, String b) {
            hostile.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            hostile.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        /** Makes Global.getSector() this world. */
        public SectorAPI install() {
            SectorAPI sector = sector();
            Global.setSector(sector);
            return sector;
        }

        private SectorAPI sector() {
            EconomyAPI economy = fake(EconomyAPI.class, "economy",
                    "getMarketsCopy", (Answer) args -> new ArrayList<>(markets),
                    "getMarkets", (Answer) args -> {
                        List<MarketAPI> in = new ArrayList<>();
                        for (MarketAPI m : markets) if (m.getStarSystem() == args[0]) in.add(m);
                        return in;
                    });
            FactionAPI player = factions.containsKey("player") ? factions.get("player")
                    : fake(FactionAPI.class, "faction player", "getId", "player", "isPlayerFaction", true);
            return fake(SectorAPI.class, "sector",
                    "getStarSystems", (Answer) args -> new ArrayList<>(systems),
                    "getEconomy", economy,
                    "getFaction", (Answer) args -> factions.get((String) args[0]),
                    "getAllFactions", (Answer) args -> new ArrayList<>(factions.values()),
                    "getPlayerFaction", player,
                    "getPlayerFleet", (Answer) args -> playerLocation == null ? null : fake(CampaignFleetAPI.class,
                            "player fleet", "getContainingLocation", playerLocation, "getLocation", playerAt),
                    "getCampaignUI", (Answer) args -> campaignUi,
                    "addTransientScript", (Answer) args -> transientScripts.add(args[0]));
        }

        private SectorEntityToken token(String name, LocationAPI in, float x, float y) {
            return fake(SectorEntityToken.class, "entity " + name,
                    "getName", name, "getContainingLocation", in, "getLocation", new Vector2f(x, y));
        }
    }
}
