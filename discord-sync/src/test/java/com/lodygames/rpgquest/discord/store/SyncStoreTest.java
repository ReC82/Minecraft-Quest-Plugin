package com.lodygames.rpgquest.discord.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * État durable (issue #202). Ce qui est testé ici est l'<strong>ordre des écritures</strong> dont
 * dépend l'anti-doublon, et la contrainte d'unicité portée par la base elle-même.
 */
class SyncStoreTest {

    @TempDir
    Path tempDir;

    private SyncStore open() {
        return new SyncStore(tempDir.resolve("sub").resolve("sync.db"));
    }

    @Test
    @DisplayName("La base et son dossier sont créés, et rouvrir ne casse rien (migration idempotente)")
    void createsAndReopens() {
        try (SyncStore store = open()) {
            store.putState("k", "v");
        }
        try (SyncStore store = open()) {
            assertEquals("v", store.state("k").orElseThrow());
            assertEquals(String.valueOf(SyncStore.SCHEMA_VERSION),
                    store.state("schema_version").orElseThrow());
        }
    }

    @Test
    @DisplayName("L'intention de création est tracée AVANT l'appel réseau, et compte les tentatives")
    void beginCreationTracksAttempts() {
        try (SyncStore store = open()) {
            SyncStore.Link first = store.beginCreation("g", "c", "t1", 0);
            assertEquals(1, first.attempts());
            assertFalse(first.linked());
            assertTrue(first.creationAmbiguous(),
                    "dès la première tentative, l'état doit être considéré comme ambigu");

            SyncStore.Link second = store.beginCreation("g", "c", "t1", 0);
            assertEquals(2, second.attempts());
        }
    }

    @Test
    @DisplayName("Confirmer l'appariement rend le lien retrouvable par sujet ET par issue")
    void confirmLinkIsFindableBothWays() {
        try (SyncStore store = open()) {
            store.beginCreation("g", "c", "t1", 0);
            store.confirmLink("t1", 123, "th", "bh");

            SyncStore.Link byThread = store.link("t1").orElseThrow();
            assertTrue(byThread.linked());
            assertEquals(123, byThread.issueNumber());
            assertEquals("th", byThread.titleHash());
            assertFalse(byThread.creationAmbiguous());

            assertEquals("t1", store.linkByIssue(123).orElseThrow().threadId());
        }
    }

    @Test
    @DisplayName("Une même issue ne peut pas être appariée à deux sujets : la base le refuse")
    void oneIssueCannotServeTwoThreads() {
        try (SyncStore store = open()) {
            store.beginCreation("g", "c", "t1", 0);
            store.confirmLink("t1", 123, "a", "b");
            store.beginCreation("g", "c", "t2", 0);

            assertThrows(StoreException.class, () -> store.confirmLink("t2", 123, "a", "b"));
        }
    }

    @Test
    @DisplayName("Confirmer efface la dernière erreur : l'état ne reste pas faussement alarmant")
    void confirmClearsLastError() {
        try (SyncStore store = open()) {
            store.beginCreation("g", "c", "t1", 0);
            store.recordError("t1", "réponse perdue");
            assertEquals("réponse perdue", store.link("t1").orElseThrow().lastError());

            store.confirmLink("t1", 7, "a", "b");
            org.junit.jupiter.api.Assertions.assertNull(store.link("t1").orElseThrow().lastError());
        }
    }

    @Test
    @DisplayName("L'adoption est explicite, persistante et idempotente")
    void adoptionIsExplicitAndPersistent() {
        try (SyncStore store = open()) {
            assertFalse(store.adopted("t9"));
            store.adopt("t9", "sujet TEST");
            store.adopt("t9", "sujet TEST (relancé)");
            assertTrue(store.adopted("t9"));
            assertEquals(1, store.adoptedThreads().size());
        }
        try (SyncStore store = open()) {
            assertTrue(store.adopted("t9"), "l'adoption doit survivre au redémarrage");
        }
    }

    @Test
    @DisplayName("Statut annoncé et publication du lien sont mémorisés séparément")
    void announcementStateIsTracked() {
        try (SyncStore store = open()) {
            store.beginCreation("g", "c", "t1", 0);
            store.confirmLink("t1", 5, "a", "b");

            store.recordAnnouncedStatus("t1", "RESOLVED");
            store.recordLinkPosted("t1");

            SyncStore.Link link = store.link("t1").orElseThrow();
            assertEquals("RESOLVED", link.lastStatus());
            assertTrue(link.linkPosted());
        }
    }

    @Test
    @DisplayName("L'horodatage de la dernière tentative est enregistré : il sert au délai de "
            + "prudence avant toute nouvelle création")
    void recordsLastAttemptInstant() {
        try (SyncStore store = open()) {
            java.time.Instant before = java.time.Instant.now().minusSeconds(1);
            SyncStore.Link link = store.beginCreation("g", "c", "t1", 0);

            assertTrue(link.lastAttemptAt() != null, "l'horodatage doit être posé");
            assertTrue(link.lastAttemptAt().isAfter(before), link.lastAttemptAt().toString());
        }
    }

    @Test
    @DisplayName("Le plancher de balayage est figé à la PREMIÈRE tentative et jamais relevé ensuite")
    void scanFloorIsFrozenAtFirstAttempt() {
        try (SyncStore store = open()) {
            assertEquals(42, store.beginCreation("g", "c", "t1", 42).scanFrom());

            // Une deuxième tentative ne doit pas relever le plancher : un relevé intermédiaire a
            // pu intégrer l'issue cherchée, et repartir de lui la sauterait.
            assertEquals(42, store.beginCreation("g", "c", "t1", 999).scanFrom());
        }
    }

    @Test
    @DisplayName("Le plus grand numéro d'issue apparié est connu : point de départ du balayage")
    void knowsHighestLinkedIssueNumber() {
        try (SyncStore store = open()) {
            assertTrue(store.maxKnownIssueNumber().isEmpty());

            store.beginCreation("g", "c", "t1", 0);
            store.confirmLink("t1", 150, "a", "b");
            store.beginCreation("g", "c", "t2", 0);
            store.confirmLink("t2", 90, "a", "b");

            assertEquals(150, store.maxKnownIssueNumber().orElseThrow());
        }
    }

    @Test
    @DisplayName("Un sujet inconnu ne renvoie rien, plutôt qu'un lien vide trompeur")
    void unknownThreadIsEmpty() {
        try (SyncStore store = open()) {
            assertTrue(store.link("inconnu").isEmpty());
            assertTrue(store.linkByIssue(4242).isEmpty());
            assertTrue(store.state("absent").isEmpty());
        }
    }
}
