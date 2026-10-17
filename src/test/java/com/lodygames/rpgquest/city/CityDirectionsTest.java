package com.lodygames.rpgquest.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Issue #180 : indication de chemin vers une ville, calculée depuis son centre réel. */
class CityDirectionsTest {

    /** Nerana telle qu'enregistrée sur DEV (centre et distance de détection par défaut). */
    private static final VillageCenter NERANA = new VillageCenter("ville_nerana", "Nerana", "world_hub",
            754.5, 69.0, -1093.5, 0f, 0f, true, Instant.EPOCH, 50);

    @Test
    void fromLilyTheCityIsNorthAboutFourHundredBlocksAway() {
        // Position de Lily sur DEV (Central City).
        assertEquals("au nord, à environ 420 blocs", CityDirections.describe(NERANA, "world_hub", 719.3, -671.0));
    }

    @Test
    void insideThePerimeterThePlayerIsAlreadyThere() {
        assertEquals("tout près : tu y es déjà", CityDirections.describe(NERANA, "world_hub", 780, -1060));
    }

    @Test
    void anotherWorldGivesNoMisleadingDirection() {
        assertEquals("loin d'ici, dans un autre monde que celui où tu te trouves",
                CityDirections.describe(NERANA, "wild", 754, -1093));
        assertEquals("loin d'ici, dans un autre monde que celui où tu te trouves",
                CityDirections.describe(NERANA, null, 0, 0));
    }

    @Test
    void eightCompassPointsFollowMinecraftAxes() {
        assertEquals("au nord", CityDirections.pointOf(0, -100));
        assertEquals("au nord-est", CityDirections.pointOf(100, -100));
        assertEquals("à l'est", CityDirections.pointOf(100, 0));
        assertEquals("au sud-est", CityDirections.pointOf(100, 100));
        assertEquals("au sud", CityDirections.pointOf(0, 100));
        assertEquals("au sud-ouest", CityDirections.pointOf(-100, 100));
        assertEquals("à l'ouest", CityDirections.pointOf(-100, 0));
        assertEquals("au nord-ouest", CityDirections.pointOf(-100, -100));
    }

    @Test
    void distanceIsRoundedToTenBlocks() {
        assertEquals(60, CityDirections.roundedDistance(57));
        assertEquals(10, CityDirections.roundedDistance(1));
    }
}
