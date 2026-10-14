package com.lodygames.rpgquest.encounter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.model.EncounterSite;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Issue #13 — mondes autorisés et identité d'occurrence. */
class EncounterWorldPolicyTest {

    private static final Set<String> FORBIDDEN = Set.of("hub", "claims");

    @Test
    void byDefaultOnlyTheWildWorldIsAllowed() {
        assertTrue(EncounterWorldPolicy.allowed("wild", List.of(), "wild", FORBIDDEN));
        assertFalse(EncounterWorldPolicy.allowed("world", List.of(), "wild", FORBIDDEN));
        assertFalse(EncounterWorldPolicy.allowed("hub", List.of(), "wild", FORBIDDEN));
    }

    @Test
    void hubAndClaimsStayExcludedEvenWhenListed() {
        List<String> configured = List.of("wild", "wild2", "hub", "claims");

        assertTrue(EncounterWorldPolicy.allowed("wild2", configured, "wild", FORBIDDEN));
        assertFalse(EncounterWorldPolicy.allowed("hub", configured, "wild", FORBIDDEN));
        assertFalse(EncounterWorldPolicy.allowed("claims", configured, "wild", FORBIDDEN));
        assertFalse(EncounterWorldPolicy.allowed("other", configured, "wild", FORBIDDEN));
    }

    @Test
    void theSiteIdIsDeterministicAndDistinctFromTheWaypointId() {
        String id = EncounterSite.idFor("wild", "minecraft:dark_forest", -3, 12);

        assertTrue(id.equals("enc_wild_dark_forest_-3_12"), id);
        assertFalse(id.startsWith("wp_"));
    }
}
