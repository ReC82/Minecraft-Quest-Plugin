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

    // ---- Issue #226 : suppression à la demande, avec sauvegarde ------------------------------

    @Test
    void deletingADefinitionBacksItUpFirstThenRemovesIt() throws Exception {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        store.create(def("pnj_de_test", "PNJ de test", "rpgquest:pnj_de_test", true));
        Path backups = dir.resolveSibling("npc-backups");

        NpcDefinitionStore.Result r = store.deleteDefinition("pnj_de_test", backups);

        assertTrue(r.ok(), r.message());
        assertEquals("DELETED", r.code());
        assertFalse(Files.exists(dir.resolve("pnj_de_test.yml")));
        assertTrue(store.find("pnj_de_test").isEmpty());
        try (var files = Files.list(backups)) {
            Path backup = files.findFirst().orElseThrow();
            assertTrue(backup.getFileName().toString().endsWith("-pnj_de_test.yml"),
                    backup.toString());
            assertTrue(Files.readString(backup).contains("pnj_de_test"), "la copie doit être réelle");
        }
        assertTrue(r.message().contains("Sauvegarde"), r.message());
    }

    /** Un rejeu ou un double clic aboutit au même état, sans erreur : l'état voulu est atteint. */
    @Test
    void deletingTwiceIsIdempotent() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        store.create(def("pnj_de_test", "PNJ de test", null, true));
        Path backups = dir.resolveSibling("npc-backups");

        assertEquals("DELETED", store.deleteDefinition("pnj_de_test", backups).code());
        NpcDefinitionStore.Result again = store.deleteDefinition("pnj_de_test", backups);

        assertTrue(again.ok(), "rejouer n'est pas une erreur");
        assertEquals("ABSENT", again.code());
    }

    /** Sans dossier de sauvegarde, la suppression est refusée — pas faite « quand même ». */
    @Test
    void deletingWithoutABackupDirectoryIsRefusedAndChangesNothing() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        store.create(def("pnj_de_test", "PNJ de test", null, true));

        NpcDefinitionStore.Result r = store.deleteDefinition("pnj_de_test", null);

        assertFalse(r.ok());
        assertEquals("NO_BACKUP_DIR", r.code());
        assertTrue(store.find("pnj_de_test").isPresent(), "le fichier doit être intact");
    }

    /** La suppression ne touche qu'au PNJ visé : ni un homonyme partiel, ni un voisin. */
    @Test
    void deletingOneDefinitionLeavesTheOthersAlone() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        store.create(def("mira_cartographer", "Mira la Cartographe", "rpgquest:mira_first_map", true));
        store.create(def("mira_apprentice", "Apprentie", null, true));
        Path backups = dir.resolveSibling("npc-backups");

        assertTrue(store.deleteDefinition("mira_cartographer", backups).ok());

        assertTrue(store.find("mira_cartographer").isEmpty());
        assertTrue(store.find("mira_apprentice").isPresent(), "le voisin ne doit pas bouger");
    }
}
