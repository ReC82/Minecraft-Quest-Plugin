package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.WorldPortalAdminService.Kind;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortalAdminParamsTest {

    private static final String FP = "0123456789abcdef01234567";

    @Test
    void everyOperationNeedsAnExplicitConfirmation() {
        assertNotNull(PortalAdminParams.parse(Kind.DISABLE, Map.of("id", "x", "expected", FP)).error());
    }

    @Test
    void createBySiteReadsTheDimensions() {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "Arche", "name", "Arche",
                "zone_mode", "site", "site", "buildsite_0007", "width", "3", "height", "4", "depth", "1"));
        m.putAll(Map.of("destination_world", "wild", "strategy", "random_safe", "by", "lody"));
        var p = PortalAdminParams.parse(Kind.CREATE, m);

        assertNull(p.error());
        assertEquals("arche", p.operation().id());
        assertEquals("buildsite_0007", p.operation().zone().site());
        assertEquals(3, p.operation().zone().width());
        assertEquals(DestinationStrategy.RANDOM_SAFE, p.operation().strategy());
        assertEquals("lody", p.operation().actor());
    }

    @Test
    void coordinatesAreNormalisedMinMax() {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "arche", "zone_mode", "coords",
                "world", "world_hub", "min_x", "5", "min_y", "70", "min_z", "-3"));
        m.putAll(Map.of("max_x", "1", "max_y", "66", "max_z", "-3", "destination_world", "wild"));

        var p = PortalAdminParams.parse(Kind.CREATE, m);

        assertNull(p.error());
        assertEquals(new Zone(1, 66, -3, 5, 70, -3), p.operation().zone().explicit());
    }

    @Test
    void anUpdateWithoutZoneModeKeepsTheZoneAndWithoutNameKeepsTheName() {
        var p = PortalAdminParams.parse(Kind.UPDATE, Map.of("confirm", "true", "id", "hub_to_wild", "expected", FP,
                "confirm_essential", "true"));

        assertNull(p.error());
        assertNull(p.operation().zone());
        assertNull(p.operation().name());
        assertTrue(p.operation().confirmEssential());
    }

    @Test
    void malformedInputsAreRejected() {
        assertNotNull(PortalAdminParams.parse(Kind.ENABLE, Map.of("confirm", "true", "id", "x", "expected", "zz")).error());
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "x")).error());
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "x", "zone_mode", "site",
                "width", "trois", "height", "4", "depth", "1")).error());
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "x", "zone_mode", "site",
                "width", "3", "height", "4", "depth", "1", "strategy", "teleport_anywhere")).error());
    }
}
