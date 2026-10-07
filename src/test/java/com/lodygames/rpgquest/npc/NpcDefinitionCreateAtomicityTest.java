package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Création de définition PNJ : <strong>une seule</strong> peut réussir pour un identifiant donné.
 *
 * <p>Pourquoi ce test existe. La création faisait un {@code exists()} suivi d'une écriture avec
 * {@code REPLACE_EXISTING} : deux tentatives simultanées du même identifiant — un double clic sur
 * « Créer un PNJ », une requête rejouée, deux administrateurs — passaient toutes les deux le
 * contrôle, la seconde écrasait la première, puis son propre nettoyage compensatoire supprimait la
 * définition que la première venait légitimement de créer. Le système de fichiers arbitre
 * désormais, via {@code CREATE_NEW}.</p>
 */
class NpcDefinitionCreateAtomicityTest {

    @TempDir
    Path dir;

    private static NpcDefinition definition(String id, String name) {
        return new NpcDefinition(id, name, null, null, null, true);
    }

    @Test
    void aSecondCreationOfTheSameIdIsRefusedAndNeverOverwrites() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);

        NpcDefinitionStore.Result first = store.create(definition("bob", "Bob"));
        NpcDefinitionStore.Result second = store.create(definition("bob", "Imposteur"));

        assertTrue(first.ok(), first.message());
        assertEquals("CREATED", first.code());
        assertTrue(!second.ok(), "la seconde création doit être refusée");
        assertEquals("EXISTS", second.code());
        // Le contenu de la première est intact : jamais écrasé par la seconde.
        assertEquals("Bob", store.find("bob").orElseThrow().displayName());
    }

    @Test
    void concurrentCreationsOfTheSameIdYieldExactlyOneSuccess() throws Exception {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<NpcDefinitionStore.Result>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                int n = i;
                tasks.add(() -> store.create(definition("marchand", "Marchand " + n)));
            }
            List<Future<NpcDefinitionStore.Result>> results = pool.invokeAll(tasks, 20, TimeUnit.SECONDS);

            long created = 0;
            long refused = 0;
            for (Future<NpcDefinitionStore.Result> f : results) {
                NpcDefinitionStore.Result r = f.get();
                if (r.ok()) {
                    created++;
                } else {
                    assertEquals("EXISTS", r.code(), "un échec doit être un refus net, pas une erreur : "
                            + r.message());
                    refused++;
                }
            }
            assertEquals(1, created, "exactement une création doit réussir");
            assertEquals(attempts - 1, refused, "toutes les autres sont refusées");
            assertTrue(store.find("marchand").isPresent(), "la définition gagnante existe");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aYamlVariantOfTheSameIdAlsoBlocksCreation() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jo.yaml"), "id: jo\ndisplay_name: \"Jo\"\n");
        NpcDefinitionStore store = new NpcDefinitionStore(dir);

        NpcDefinitionStore.Result r = store.create(definition("jo", "Autre Jo"));

        assertTrue(!r.ok());
        assertEquals("EXISTS", r.code());
    }

    @Test
    void theRollbackDeleteRemovesOnlyThatDefinitionAndIsIdempotent() {
        NpcDefinitionStore store = new NpcDefinitionStore(dir);
        store.create(definition("bob", "Bob"));
        store.create(definition("lily", "Lily"));

        NpcDefinitionStore.Result removed = store.deleteForRollback("bob");
        assertTrue(removed.ok());
        assertEquals("DELETED", removed.code());
        assertTrue(store.find("bob").isEmpty(), "retirée");
        assertTrue(store.find("lily").isPresent(), "les autres définitions sont intactes");

        // Rejouer le nettoyage ne doit pas être une erreur : il doit rester idempotent.
        NpcDefinitionStore.Result again = store.deleteForRollback("bob");
        assertTrue(again.ok());
        assertEquals("ABSENT", again.code());
    }
}
