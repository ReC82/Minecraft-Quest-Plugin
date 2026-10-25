package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** #828 : l'identifiant court d'une version et sa recherche. */
class PlanVersionRefTest {

    private static final BuildingPlanDraft A = draft("8776ad09-1111-4aaa-8bbb-000000000001");
    private static final BuildingPlanDraft B = draft("8776ad10-2222-4aaa-8bbb-000000000002");
    private static final BuildingPlanDraft C = draft("007bcdcc-3333-4aaa-8bbb-000000000003");

    @Test
    void theShortLabelIsVAndEightCharacters() {
        assertEquals("V-8776ad09", PlanVersionRef.label(A.id()));
        assertEquals("V-ab", PlanVersionRef.label("ab"));
    }

    @Test
    void shortFullAndCaseInsensitiveReferencesAreAccepted() {
        assertEquals(Optional.of("8776ad09"), PlanVersionRef.normalize(" V-8776AD09 "));
        assertEquals(Optional.of("8776ad09"), PlanVersionRef.normalize("8776ad09"));
        assertEquals(Optional.of(A.id()), PlanVersionRef.normalize(A.id()));
        assertTrue(PlanVersionRef.normalize("V-87").isEmpty(), "trop court");
        assertTrue(PlanVersionRef.normalize("x' OR 1=1").isEmpty(), "pas un identifiant");
        assertTrue(PlanVersionRef.normalize("8776%").isEmpty(), "aucun joker");
    }

    @Test
    void aUniquePrefixResolvesAndOtherwiseTheReasonIsGiven() {
        List<BuildingPlanDraft> all = List.of(A, B, C);
        assertEquals(A, PlanVersionRef.resolve("V-8776ad09", all, "ce projet").draft().orElseThrow());
        assertEquals(C, PlanVersionRef.resolve(C.id(), all, "ce projet").draft().orElseThrow());
        PlanVersionRef.Lookup ambiguous = PlanVersionRef.resolve("V-8776", all, "ce projet");
        assertTrue(ambiguous.draft().isEmpty() && ambiguous.error().contains("plusieurs versions"));
        PlanVersionRef.Lookup missing = PlanVersionRef.resolve("V-ffffffff", all, "ce projet");
        assertTrue(missing.draft().isEmpty() && missing.error().contains("introuvable dans ce projet"));
        assertTrue(PlanVersionRef.resolve("église", all, "ce projet").error().contains("n'est pas un identifiant"));
    }

    private static BuildingPlanDraft draft(String id) {
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        return new BuildingPlanDraft(id, "agent", "", "plan", "", 1, BuildingPlanDraft.Origin.AI_CORRECTION, "",
                "", "", "", "", "", "", "", 19, 19, 14, false, "owner", now, "owner", now, "p1");
    }
}
