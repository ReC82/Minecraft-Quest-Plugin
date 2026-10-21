package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import org.junit.jupiter.api.Test;

class PortalZoneTest {

    @Test
    void facingNorthIsWideAlongXAndThinAlongZ() {
        assertEquals(new Zone(99, 64, 200, 101, 67, 200), PortalZone.around(100, 64, 200, Facing.NORTH, 3, 4, 1));
        assertEquals(new Zone(99, 64, 200, 101, 67, 200), PortalZone.around(100, 64, 200, Facing.SOUTH, 3, 4, 1));
    }

    @Test
    void facingEastIsWideAlongZAndThinAlongX() {
        assertEquals(new Zone(100, 64, 199, 100, 67, 201), PortalZone.around(100, 64, 200, Facing.EAST, 3, 4, 1));
        assertEquals(new Zone(100, 64, 199, 100, 67, 201), PortalZone.around(100, 64, 200, Facing.WEST, 3, 4, 1));
    }

    @Test
    void anEvenWidthPutsTheExtraBlockTowardsIncreasingCoordinates() {
        assertEquals(new Zone(-1, 0, 0, 2, 2, 0), PortalZone.around(0, 0, 0, Facing.NORTH, 4, 3, 1));
    }

    @Test
    void sidesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> PortalZone.around(0, 0, 0, Facing.NORTH, 0, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> PortalZone.around(0, 0, 0, Facing.NORTH, 33, 3, 1));
        assertNull(PortalZone.checkExplicit(new Zone(0, 0, 0, 31, 31, 31)));
        assertNotNull(PortalZone.checkExplicit(new Zone(0, 0, 0, 32, 1, 1)));
    }
}
