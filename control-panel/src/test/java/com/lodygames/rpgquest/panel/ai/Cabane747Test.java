package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #747 (24/10/2026), essai supervisé — projet de test « TEST #747d cabane » : brief de la Cabane du Hub
 * (« une seule pièce vide », « sans cloison, étage ni mobilier »), puis la consigne réelle du propriétaire
 * (lit rouge, coffre, établi, four, torches fixées aux murs, « ne pas ajouter d'étage »). Le plan
 * V-e9035550 (gpt-4.1 puis réparation du moteur) passe les contrôles ; rejoué ici sans appel payant :
 * le mobilier demandé par la consigne est une exigence, plus un ajout ; les torches posées au sol ne
 * sont pas « fixées aux murs » ; l'escalier vers le dessus du « shell » contredit « sans étage ».
 */
class Cabane747Test {

    private static String resource(String name) {
        try (InputStream in = Cabane747Test.class.getResourceAsStream("/ai/cabane747/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String BRIEF = resource("brief.txt");
    private static final String NOTES = resource("notes.txt");
    private static final String PLAN = resource("plan_e9035550.yml");

    private static BriefRequirements.Status status(BriefRequirements.Report r, String id) {
        return r.statuses().stream().filter(s -> id.equals(s.requirement().id())).findFirst().orElseThrow();
    }

    @Test
    void withTheOwnersInstructionsTheFurnitureIsRequiredAndNoLongerAnExtra() {
        BriefRequirements.Report r = BriefRequirements.evaluate(BRIEF + "\n" + NOTES, PLAN, Map.of());
        for (String id : new String[] {"bed", "chest", "crafting", "furnace"}) {
            assertFalse(status(r, id).missing(), status(r, id).line());
        }
        assertTrue(r.extras().stream().noneMatch(e -> e.startsWith("Lit")), r.extras().toString());
        BriefRequirements.Report briefOnly = BriefRequirements.evaluate(BRIEF, PLAN, Map.of());
        assertTrue(briefOnly.extras().stream().anyMatch(e -> e.startsWith("Lit")),
                "sans la consigne, le lit reste un ajout non demandé : " + briefOnly.extras());
    }

    @Test
    void floorTorchesAreNotTorchesFixedToTheWalls() {
        BriefRequirements.Report r = BriefRequirements.evaluate(BRIEF + "\n" + NOTES, PLAN, Map.of());
        assertTrue(status(r, "wall_torch").missing(), status(r, "wall_torch").line());
        String fixed = PLAN.replace("block: minecraft:torch", "block: \"minecraft:wall_torch[facing=east]\"");
        assertFalse(status(BriefRequirements.evaluate(BRIEF + "\n" + NOTES, fixed, Map.of()), "wall_torch").missing());
    }

    @Test
    void aStaircaseInASingleLevelCabinIsFlagged() {
        BriefRequirements.Report r = BriefRequirements.evaluate(BRIEF + "\n" + NOTES, PLAN, Map.of());
        assertTrue(r.extras().stream().anyMatch(e -> e.startsWith("Escalier vers un étage")), r.extras().toString());
        String entranceSteps = "steps:\n  - op: staircase\n    facade: FRONT\n    offset: 4\n    floor: 1\n"
                + "    block: minecraft:oak_stairs\n";
        assertFalse(BriefRequirements.hasInteriorStaircase(entranceSteps), "des marches d'entrée ne font pas un étage");
        assertFalse(BriefRequirements.evaluate("Une maison à deux étages.", PLAN, Map.of()).extras().stream()
                .anyMatch(e -> e.startsWith("Escalier")), "un étage demandé n'est jamais signalé");
    }

    @Test
    void theCabinRoofIsRequiredFromTwoSlopes() {
        assertFalse(status(BriefRequirements.evaluate(BRIEF, PLAN, Map.of()), "roof").missing());
    }
}
