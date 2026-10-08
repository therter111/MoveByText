package KirizsKeve.MoveByText;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import org.lwjgl.util.vector.Vector2f;

public class FleetOnText {

    public static void spawnAIFleet(String factionId, float combatPoints, LocationAPI location, Vector2f coordinates) {

        FleetParamsV3 params = new FleetParamsV3(
                null,
                coordinates,
                factionId,
                null,
                FleetTypes.PATROL_LARGE,
                combatPoints,
                0f, 0f, 0f, 0f, 0f, 0f
        );
        params.ignoreMarketFleetSizeMult = true;

        // Generate the fleet, blindly trusting the factionId is correct
        CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);

        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_JUMP, true);

        location.addEntity(fleet);
        fleet.setLocation(coordinates.x, coordinates.y);

        SectorEntityToken patrolToken = location.createToken(coordinates.x, coordinates.y);
        fleet.addAssignment(
                FleetAssignment.PATROL_SYSTEM,
                patrolToken,
                1000f,
                "patrolling the sector"
        );
    }
}