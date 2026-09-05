package com.lodygames.rpgquest.claim.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimTest {

    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private Claim claim(int minX, int minZ, int maxX, int maxZ) {
        return new Claim("c", owner, "world", minX, 0, minZ, maxX, 255, maxZ, Set.of(member), ClaimFlags.defaults());
    }

    @Test
    void invertedBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> claim(10, 10, 0, 0));
    }

    @Test
    void nullOwnerIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Claim("c", null, "world", 0, 0, 0, 1, 1, 1, Set.of(), ClaimFlags.defaults()));
    }

    @Test
    void invalidIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Claim("Not Valid", owner, "world", 0, 0, 0, 1, 1, 1, Set.of(), ClaimFlags.defaults()));
    }

    @Test
    void containsIsInclusiveOfBounds() {
        Claim claim = claim(0, 0, 10, 10);
        assertTrue(claim.contains("world", 0, 100, 0));
        assertTrue(claim.contains("world", 10, 100, 10));
        assertFalse(claim.contains("world", 11, 100, 0));
        assertFalse(claim.contains("other_world", 0, 100, 0));
    }

    @Test
    void overlapsOnlyWithinTheSameWorld() {
        Claim a = claim(0, 0, 10, 10);
        Claim b = claim(5, 5, 15, 15);
        Claim c = new Claim("c2", owner, "other_world", 0, 0, 0, 10, 255, 10, Set.of(), ClaimFlags.defaults());

        assertTrue(a.overlaps(b));
        assertFalse(a.overlaps(c));
    }

    @Test
    void isTrustedCoversOwnerAndMembersOnly() {
        Claim claim = claim(0, 0, 10, 10);
        assertTrue(claim.isTrusted(owner));
        assertTrue(claim.isTrusted(member));
        assertFalse(claim.isTrusted(stranger));
    }

    @Test
    void nullMembersDefaultsToEmptySet() {
        Claim claim = new Claim("c", owner, "world", 0, 0, 0, 1, 1, 1, null, ClaimFlags.defaults());
        assertFalse(claim.isTrusted(stranger));
        assertTrue(claim.members().isEmpty());
    }
}
