package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #637 — relevé des claims : rien d'inventé, principal identifié, palier déduit de la taille réelle. */
class ClaimOverviewTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void listsEveryClaimWithItsOwnerMainFlagTierAndWorldState() {
        Claim tier2 = new Claim("main_a", ALICE, "claims", -5, 60, -5, 4, 120, 4, -50, 60, -50, 49, 120, 49,
                Set.of(BOB), new ClaimFlags(true));
        Claim free = new Claim("atelier", ALICE, "old_world", 0, 0, 0, 6, 10, 2, Set.of(), ClaimFlags.defaults());
        Claim bobs = new Claim("main_b", BOB, "claims", 100, 60, 100, 104, 70, 104, Set.of(), ClaimFlags.defaults());
        List<Claim> all = List.of(tier2, free, bobs);

        ClaimOverview.View view = ClaimOverview.build(all,
                owner -> all.stream().filter(c -> c.owner().equals(owner)).findFirst(),
                owner -> owner.equals(ALICE) ? "Alice" : null, "claims"::equals, "claims");

        assertTrue(view.available());
        assertEquals(2, view.owners());
        assertEquals(3, view.claims().size());
        ClaimOverview.Entry first = view.claims().get(0);
        assertTrue(first.main());
        assertEquals("Alice", first.ownerName());
        assertEquals("TIER_2", first.tier());
        assertEquals(10, first.width());
        assertEquals(1, first.members());
        assertTrue(first.publicRedstone());
        assertTrue(first.worldLoaded());
        assertTrue(first.inClaimsWorld());
        assertEquals(-50, first.reservedMinX());

        ClaimOverview.Entry second = view.claims().get(1);
        assertFalse(second.main(), "un second claim n'est jamais présenté comme principal");
        assertNull(second.tier(), "une forme libre n'a pas de palier : jamais deviné");
        assertFalse(second.worldLoaded());
        assertFalse(second.inClaimsWorld());

        ClaimOverview.Entry third = view.claims().get(2);
        assertNull(third.ownerName(), "pseudo inconnu : absent, jamais inventé");
        assertEquals("TIER_1", third.tier());
        assertEquals(102, third.centerX());
    }

    @Test
    void anEmptyServerIsAvailableButEmptyWhileAMissingServiceIsUnavailable() {
        ClaimOverview.View empty = ClaimOverview.build(List.of(), owner -> Optional.empty(), owner -> null,
                world -> true, "claims");
        assertTrue(empty.available());
        assertTrue(empty.claims().isEmpty());
        assertFalse(ClaimOverview.View.unavailable().available());
    }
}
