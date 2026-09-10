package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lecture de la source éditable pour le catalogue fusionné (issue #144). */
class SourceCatalogTest {

    @TempDir
    Path root;

    @Test
    void notConfiguredWorkspaceYieldsEmptyLists() {
        SourceCatalog cat = new SourceCatalog(new ContentWorkspace(null));
        assertFalse(cat.available());
        assertTrue(cat.quests().isEmpty());
        assertTrue(cat.stories().isEmpty());
    }

    @Test
    void readsQuestFilesAndExposesPlainId() throws Exception {
        Files.createDirectories(root.resolve("quests"));
        Files.writeString(root.resolve("quests/lily_pumpkin.yml"), """
                id: rpgquest:lily_pumpkin
                title: "Une citrouille pour Lily"
                description: "d"
                category: "lily"
                icon: BOOK
                repeatable: false
                giver: lily

                steps:
                  - id: s1
                    objectives:
                      - type: COLLECT_ITEM
                        material: PUMPKIN
                        amount: 1
                """);
        SourceCatalog cat = new SourceCatalog(new ContentWorkspace(root));
        assertTrue(cat.available());
        List<SourceCatalog.QuestSource> quests = cat.quests();
        assertEquals(1, quests.size());
        SourceCatalog.QuestSource qs = quests.get(0);
        assertEquals("lily_pumpkin", qs.plainId());
        assertEquals("Une citrouille pour Lily", qs.draft().title);
        assertEquals("lily", qs.draft().giver);
        assertTrue(qs.parseOk());
    }

    @Test
    void oddQuestFileIsNeverHiddenAndFallsBackToItsSlug() throws Exception {
        Files.createDirectories(root.resolve("quests"));
        // Fichier sans id ni steps exploitables : il ne doit pas disparaître du catalogue.
        Files.writeString(root.resolve("quests/broken.yml"), "just a scalar\n");
        List<SourceCatalog.QuestSource> quests = new SourceCatalog(new ContentWorkspace(root)).quests();
        assertEquals(1, quests.size());
        assertEquals("broken", quests.get(0).plainId(), "repli sur le slug de fichier");
    }

    @Test
    void readsStoryFiles() throws Exception {
        Files.createDirectories(root.resolve("stories"));
        Files.writeString(root.resolve("stories/lily_memories.yml"),
                "id: lily_memories\nname: \"Souvenirs\"\nquests:\n  - rpgquest:lily_pumpkin\n");
        List<SourceCatalog.StorySource> stories = new SourceCatalog(new ContentWorkspace(root)).stories();
        assertEquals(1, stories.size());
        assertEquals("lily_memories", stories.get(0).plainId());
        assertEquals(List.of("lily_pumpkin"), stories.get(0).draft().questIds);
    }
}
