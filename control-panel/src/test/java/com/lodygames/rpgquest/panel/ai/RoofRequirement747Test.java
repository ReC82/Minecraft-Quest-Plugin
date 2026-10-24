package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BuildingVoxels;
import com.lodygames.rpgquest.panel.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #747 (24/10/2026) — la description de l'église de test demande « une toiture à deux pans » et une tour
 * à « toiture pointue » ; aucune exigence ne le contrôlait : un plan qui perd ses toits restait
 * « conforme », le toit plat d'un « shell » fermé suffisant à couvrir. C'est désormais une exigence
 * mesurée. Rejoué sans appel payant sur les grilles réelles de V-8776ad09 et de V-25f197ff (réparation
 * moteur de la réponse gpt-5-mini de l'essai supervisé, qui a DÉPLACÉ ses deux « gable » en fin de plan
 * sans les perdre), puis sur cette même version privée de ses toits.
 */
class RoofRequirement747Test {

    private static String resource(String name) {
        try (InputStream in = RoofRequirement747Test.class.getResourceAsStream("/ai/eglise747/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static BuildingVoxels grid(String version) {
        Map<String, Object> result = Json.parseObject(resource("preview_" + version + ".json"));
        return BuildingVoxels.from((Map<String, Object>) result.get("details"));
    }

    private static final String BRIEF = resource("brief.txt");

    private static BriefRequirements.Status roof(String version, boolean withGrid) {
        BriefRequirements.Report report = BriefRequirements.evaluate(BRIEF, resource("plan_" + version + ".yml"),
                Map.of(), withGrid ? grid(version) : null);
        return report.statuses().stream().filter(s -> "roof".equals(s.requirement().id())).findFirst().orElseThrow();
    }

    @Test
    void theChurchBriefAsksForAPitchedRoof() {
        assertTrue(BriefRequirements.extract(BRIEF).stream().anyMatch(r -> "roof".equals(r.id())));
        assertTrue(BriefRequirements.extract("Un toit à deux pentes bien distinctes, en escaliers de chêne.").stream()
                .anyMatch(r -> "roof".equals(r.id())), "la cabane du 23/10 dit « deux pentes »");
        assertFalse(BriefRequirements.extract("Une cabane au toit plat, sans étage.").stream()
                .anyMatch(r -> "roof".equals(r.id())));
    }

    @Test
    void theBestVersionHasItsTwoPitchedRoofsOnTheCompiledGrid() {
        BriefRequirements.Status s = roof("8776ad09", true);
        assertFalse(s.missing(), s.line());
        assertTrue(s.line().contains("2 instruction(s) « gable »"), s.line());
    }

    @Test
    void theGpt5MiniRepairKeptBothRoofsAfterMovingThem() {
        BriefRequirements.Status s = roof("25f197ff", true);
        assertFalse(s.missing(), s.line());
        assertTrue(s.line().contains("2 instruction(s) « gable »"), s.line());
    }

    @Test
    void aPlanThatDropsItsRoofsIsMissingThePitchedRoof() {
        String withoutRoofs = resource("plan_25f197ff.yml").replaceAll(
                "(?m)^  - op: gable\n(    .*\n)+", "");
        assertFalse(withoutRoofs.contains("op: gable"));
        BriefRequirements.Status s = BriefRequirements.evaluate(BRIEF, withoutRoofs, Map.of(), null).statuses().stream()
                .filter(st -> "roof".equals(st.requirement().id())).findFirst().orElseThrow();
        assertTrue(s.missing(), "sans grille, aucun gable : toit en pente absent — " + s.line());
        BriefRequirements.Status kept = BriefRequirements.evaluate(BRIEF, resource("plan_25f197ff.yml"), Map.of(), null)
                .statuses().stream().filter(st -> "roof".equals(st.requirement().id())).findFirst().orElseThrow();
        assertTrue(kept.unverified() && !kept.missing(), "avec ses gable mais sans grille : non vérifiée, pas manquante");
    }

    @Test
    void theDesignDeclaresTheRoofOnItsVolumeLineNotAsAnElement() {
        String pitched = "VOLUME nef | rôle: corps principal | x 2..12 | z 2..16 | y 0..11 | toit: deux pans axe z\n";
        String flat = "VOLUME nef | rôle: corps principal | x 2..12 | z 2..16 | y 0..11 | toit: plat\n";
        assertTrue(ArchitecturalDesign.parse(pitched).gaps(BRIEF, 19, 19, 23).stream()
                .noneMatch(f -> f.text().startsWith("Toiture en pente")));
        assertTrue(ArchitecturalDesign.parse(flat).gaps(BRIEF, 19, 19, 23).stream()
                .anyMatch(f -> f.text().startsWith("Toiture en pente") && f.text().contains("aucun VOLUME")));
    }
}
