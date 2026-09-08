package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Attribution du giver: à une quête existante sur disque, par recherche du fichier via son id. */
class QuestGiverStoreTest {

    @TempDir
    Path dir;

    private void quest(String file, String id) throws Exception {
        Files.writeString(dir.resolve(file), "# quête\nid: " + id + "\ntitle: \"t\"\ncategory: c\n"
                + "steps:\n  - id: s\n    objectives:\n      - type: TALK_TO_NPC\n        npc: x\n");
    }

    @Test
    void setsGiverOnTheRightFileAndPreservesComments() throws Exception {
        quest("crystal_hunt.yml", "rpgquest:crystal_hunt");
        quest("first_steps.yml", "rpgquest:first_steps");

        QuestGiverStore store = new QuestGiverStore(dir);
        QuestGiverStore.Result r = store.setGiver("rpgquest:crystal_hunt", "guard");

        assertTrue(r.ok());
        assertEquals("crystal_hunt.yml", r.file());
        String content = Files.readString(dir.resolve("crystal_hunt.yml"));
        assertTrue(content.contains("category: c\ngiver: guard\n"), content);
        assertTrue(content.startsWith("# quête"));
        assertFalse(Files.readString(dir.resolve("first_steps.yml")).contains("giver:"), "autre fichier intact");
    }

    @Test
    void acceptsABareQuestIdAndNormalises() throws Exception {
        quest("crystal_hunt.yml", "rpgquest:crystal_hunt");
        assertTrue(new QuestGiverStore(dir).setGiver("crystal_hunt", "guard").ok());
    }

    @Test
    void unknownQuestIsAReadableFailure() throws Exception {
        quest("crystal_hunt.yml", "rpgquest:crystal_hunt");
        QuestGiverStore.Result r = new QuestGiverStore(dir).setGiver("rpgquest:nope", "guard");
        assertFalse(r.ok());
        assertEquals("UNKNOWN_QUEST", r.code());
    }

    @Test
    void secondCallWithSameGiverIsANoOpSuccess() throws Exception {
        quest("crystal_hunt.yml", "rpgquest:crystal_hunt");
        QuestGiverStore store = new QuestGiverStore(dir);
        assertTrue(store.setGiver("rpgquest:crystal_hunt", "guard").ok());
        String after1 = Files.readString(dir.resolve("crystal_hunt.yml"));
        assertTrue(store.setGiver("rpgquest:crystal_hunt", "guard").ok());
        assertEquals(after1, Files.readString(dir.resolve("crystal_hunt.yml")));
    }
}
