package KirizsKeve.MoveByText.game;

import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

/** A place the player named: a star system, and the market in it when a market or station was named. */
public final class Place {

    private final StarSystemAPI system;
    /** null when only a system or a market-less planet matched. */
    private final MarketAPI market;

    Place(StarSystemAPI system, MarketAPI market) {
        this.system = system;
        this.market = market;
    }

    public StarSystemAPI system() {
        return system;
    }

    public MarketAPI market() {
        return market;
    }

    /** "Askonia (Sindria)", or "Askonia" without a market. */
    public String describe() {
        return describe(system, market);
    }

    /** The same for any location, e.g. hyperspace around the player. market may be null. */
    public static String describe(LocationAPI location, MarketAPI market) {
        return location.getName() + (market != null ? " (" + market.getName() + ")" : "");
    }
}
