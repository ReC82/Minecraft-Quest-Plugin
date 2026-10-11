package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot F — la projection du verdict d'audit côté panel.
 *
 * <p>Le détail d'une action arrive en {@code Map} non typée, construite par un codec maison. Ces
 * tests figent ce qui se passe quand il est incomplet : la règle est qu'une clé absente reste à sa
 * valeur neutre et ne se lit <strong>jamais</strong> comme une mesure.</p>
 */
class BuildingPlanVerdictTest {

    private static Map<String, Object> refusedDetails() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("solidCells", 2368);
        metrics.put("glazedCells", 0);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", "ARCHITECTURE_REFUSED");
        details.put("message", "Plan refusé — contrat architectural non respecté.");
        details.put("buildingId", "hub_library");
        details.put("kind", "bâtiment habitable");
        details.put("sizeX", 19);
        details.put("sizeY", 12);
        details.put("sizeZ", 15);
        details.put("blocks", 2368);
        details.put("codes", List.of("NO_ENTRANCE", "GLAZING_MISSING"));
        details.put("warnings", List.of("Matériau dominant."));
        details.put("exists", Boolean.TRUE);
        details.put("metrics", metrics);
        return details;
    }

    @Test
    @DisplayName("Un refus est projeté entièrement, codes et mesures compris")
    void arefusalIsProjectedInFull() {
        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(refusedDetails());

        assertTrue(verdict.available());
        assertFalse(verdict.accepted());
        assertEquals("ARCHITECTURE_REFUSED", verdict.code());
        assertEquals(List.of("NO_ENTRANCE", "GLAZING_MISSING"), verdict.codes());
        assertEquals(List.of("Matériau dominant."), verdict.warnings());
        assertEquals(2368, verdict.blocks());
        assertEquals(19, verdict.sizeX());
        assertTrue(verdict.exists(), "l'écran doit pouvoir prévenir avant de proposer d'écraser");
        assertEquals(0, ((Number) verdict.metrics().get("glazedCells")).intValue());
    }

    /**
     * La distinction qui compte, et celle que la V1 avait brouillée : accepté ≠ construit. Le
     * libellé ne doit pas laisser croire qu'un artefact existe.
     */
    @Test
    @DisplayName("Un plan accepté est dit « conforme », jamais « généré »")
    void anacceptedPlanIsCalledConformingNeverGenerated() {
        Map<String, Object> details = new LinkedHashMap<>(refusedDetails());
        details.put("code", "OK");

        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(details);

        assertTrue(verdict.accepted());
        assertTrue(verdict.label().contains("conforme"), verdict.label());
        assertTrue(verdict.label().contains("aucun artefact"), verdict.label());
        String lower = verdict.label().toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("génér"), "vocabulaire trompeur : " + verdict.label());
        assertFalse(lower.contains("posé"), "vocabulaire trompeur : " + verdict.label());
    }

    @Test
    @DisplayName("Aucun audit demandé ne se lit pas comme un plan bon")
    void nnoAuditRequestedDoesNotReadAsAGoodPlan() {
        for (BuildingPlanVerdict none : List.of(BuildingPlanVerdict.none(),
                BuildingPlanVerdict.from(null), BuildingPlanVerdict.from(Map.of()))) {
            assertFalse(none.available());
            assertFalse(none.accepted(), "l'absence d'audit n'est pas une acceptation");
            assertEquals("Aucun audit demandé", none.label());
        }
    }

    @Test
    @DisplayName("Un détail tronqué ne fabrique ni mesure, ni code, ni acceptation")
    void atruncatedDetailFabricatesNothing() {
        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(Map.of("code", "PLAN_INVALID"));

        assertTrue(verdict.available(), "le serveur a bien répondu");
        assertFalse(verdict.accepted());
        assertEquals(0, verdict.blocks(), "aucun bloc annoncé faute de mesure");
        assertEquals("", verdict.buildingId());
        assertTrue(verdict.codes().isEmpty());
        assertTrue(verdict.metrics().isEmpty());
    }

    /**
     * Le codec maison du panel rend volontiers les entiers en texte selon le chemin parcouru. Les
     * deux formes doivent donner le même verdict, sinon l'écran afficherait zéro bloc pour un
     * bâtiment qui en compte deux mille.
     */
    @Test
    @DisplayName("Un entier arrivé en texte est lu comme un entier")
    void anintegerArrivingAsTextIsReadAsAnInteger() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", "OK");
        details.put("blocks", "2368");
        details.put("sizeX", "19");
        details.put("exists", "true");

        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(details);

        assertEquals(2368, verdict.blocks());
        assertEquals(19, verdict.sizeX());
        // « true » en texte n'est pas Boolean.TRUE : on ne devine pas une présence en bibliothèque.
        assertFalse(verdict.exists(),
                "mieux vaut ne pas affirmer une présence que l'affirmer sur une chaîne");
    }

    @Test
    @DisplayName("Une liste contenant des entrées vides ne produit pas de codes fantômes")
    void alistWithBlankEntriesProducesNoPhantomCodes() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", "ARCHITECTURE_REFUSED");
        details.put("codes", java.util.Arrays.asList("NO_ENTRANCE", "", null, "  "));

        BuildingPlanVerdict verdict = BuildingPlanVerdict.from(details);

        assertEquals(List.of("NO_ENTRANCE"), verdict.codes());
    }
}
