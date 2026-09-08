package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Édition texte minimale du champ giver: — commentaires et reste du fichier préservés. */
class QuestGiverEditorTest {

    private static final String QUEST = """
            # La chasse aux cristaux
            id: rpgquest:crystal_hunt
            title: "<gold>La chasse aux cristaux</gold>"
            description: "<gray>...</gray>"
            category: crafting
            icon: AMETHYST_SHARD
            repeatable: false

            steps:
              - id: hunt_spiders
                objectives:
                  - type: KILL_ENTITY
                    entity: SPIDER
                    amount: 5
            """;

    @Test
    void insertsGiverRightAfterCategoryAndKeepsEverythingElse() {
        String out = QuestGiverEditor.setGiver(QUEST, "guard");
        assertTrue(out.contains("category: crafting\ngiver: guard\n"), out);
        assertTrue(out.contains("# La chasse aux cristaux"), "commentaire d'en-tête préservé");
        assertTrue(out.contains("      - type: KILL_ENTITY"), "corps préservé");
        assertTrue(out.endsWith("\n"), "saut de ligne final préservé");
    }

    @Test
    void replacesAnExistingGiverLineInPlace() {
        String withGiver = QUEST.replace("category: crafting\n", "category: crafting\ngiver: old_guy\n");
        String out = QuestGiverEditor.setGiver(withGiver, "guard");
        assertTrue(out.contains("giver: guard"));
        assertTrue(!out.contains("old_guy"));
        assertEquals(withGiver.lines().count(), out.lines().count(), "aucune ligne ajoutée/retirée");
    }

    @Test
    void isIdempotent() {
        String once = QuestGiverEditor.setGiver(QUEST, "guard");
        assertEquals(once, QuestGiverEditor.setGiver(once, "guard"));
    }

    @Test
    void fallsBackToAfterIdWhenNoCategory() {
        String noCategory = "id: rpgquest:x\ntitle: \"x\"\nsteps: []\n";
        String out = QuestGiverEditor.setGiver(noCategory, "guard");
        assertTrue(out.contains("id: rpgquest:x\ngiver: guard\n"), out);
    }

    @Test
    void preservesCrlfLineEndings() {
        String crlf = "id: rpgquest:x\r\ncategory: c\r\n";
        String out = QuestGiverEditor.setGiver(crlf, "guard");
        assertTrue(out.contains("category: c\r\ngiver: guard\r\n"), out.replace("\r", "<CR>"));
    }
}
