package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Écriture sûre des définitions PNJ : création, refus d'écrasement, mise à jour, round-trip. */
class NpcDefinitionStoreTest {

    @TempDir
    Path dir;

    private NpcDefinition def(String id, String name, String dialogue, boolean enabled) {
        return new NpcDefinition(id, name, null, dialogue, "quest_giver", enabled);
    }

    @Test
    void createWritesAFileThatReloadsIdentically() throws Exception {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        NpcDefinitionStore.Result r = store.create(def("woodcutter_bob", "Bûcheron Bob", "rpgquest:woodcutter_bob", true));

        assertTrue(r.ok());
        assertEquals("CREATED", r.code());
        assertEquals("woodcutter_bob.yml", r.file());
        assertTrue(Files.exists(dir.resolve("woodcutter_bob.yml")));
        assertEquals("Bûcheron Bob", store.find("woodcutter_bob").orElseThrow().displayName());
        assertEquals("rpgquest:woodcutter_bob", store.find("woodcutter_bob").orElseThrow().dialogueId());
    }

    @Test
    void createRefusesToOverwriteAnExistingId() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        assertTrue(store.create(def("guard", "Garde", null, true)).ok());
        NpcDefinitionStore.Result second = store.create(def("guard", "Autre", null, true));
        assertFalse(second.ok());
        assertEquals("EXISTS", second.code());
        assertEquals("Garde", store.find("guard").orElseThrow().displayName(), "l'original est intact");
    }

    @Test
    void updateReplacesFieldsButFailsWhenAbsent() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        assertEquals("NOT_FOUND", store.update(def("ghost", "Ghost", null, true)).code());

        store.create(def("guard", "Garde", "rpgquest:guard", true));
        NpcDefinitionStore.Result upd = store.update(def("guard", "<yellow>Garde</yellow>", null, false));
        assertTrue(upd.ok());
        assertEquals("UPDATED", upd.code());
        NpcDefinition after = store.find("guard").orElseThrow();
        assertEquals("<yellow>Garde</yellow>", after.displayName());
        assertFalse(after.enabled());
        assertEquals(null, after.dialogueId());
    }

    @Test
    void listSurfacesLoadIssues() throws Exception {
        Files.writeString(dir.resolve("broken.yml"), "id: 123\n:::not yaml");
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        assertTrue(store.list().hasIssues());
    }
}
