package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persistance de la lecture des nœuds de dialogue (issue #12, migration V24).
 *
 * <p>Ce que ces tests figent : la lecture est <strong>par joueur</strong>, <strong>par nœud</strong>,
 * <strong>idempotente</strong>, et elle <strong>survit</strong> à la fermeture de la base — c'est
 * exactement ce que le ticket demande (« préserver l'état par UUID après reconnexion/redémarrage »).
 * L'absence de ligne vaut « jamais lu » : rien n'est pré-rempli.</p>
 */
class DialogueReadRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private DialogueReadRepository repository;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new DialogueReadRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private Set<String> read(UUID playerId, String dialogueId) throws Exception {
        return repository.readNodes(playerId, dialogueId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void mark(UUID playerId, String dialogueId, String nodeId) throws Exception {
        repository.markRead(playerId, dialogueId, nodeId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Sans aucune lecture, rien n'est marqué comme lu")
    void nothingIsReadByDefault() throws Exception {
        assertTrue(read(alice, "rpgquest:tc12_guide").isEmpty(),
                "un contenu jamais ouvert ne doit pas être considéré comme lu");
    }

    @Test
    @DisplayName("Seul le nœud marqué est lu : les autres branches restent non lues")
    void onlyTheMarkedNodeIsRead() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");

        Set<String> nodes = read(alice, "rpgquest:tc12_guide");

        assertEquals(Set.of("start"), nodes);
        assertFalse(nodes.contains("branche_secrete"),
                "marquer le nœud de départ ne doit JAMAIS marquer les branches non parcourues");
    }

    @Test
    @DisplayName("La lecture est propre à chaque joueur")
    void readIsPerPlayer() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");

        assertEquals(Set.of("start"), read(alice, "rpgquest:tc12_guide"));
        assertTrue(read(bob, "rpgquest:tc12_guide").isEmpty(),
                "deux joueurs doivent pouvoir voir des états différents");
    }

    @Test
    @DisplayName("La lecture est propre à chaque dialogue")
    void readIsPerDialogue() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");

        assertTrue(read(alice, "rpgquest:tc12_libraire").isEmpty());
    }

    @Test
    @DisplayName("Marquer deux fois le même nœud est sans effet : aucun doublon possible")
    void markingTwiceIsIdempotent() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");
        mark(alice, "rpgquest:tc12_guide", "start");

        assertEquals(1, read(alice, "rpgquest:tc12_guide").size());
    }

    @Test
    @DisplayName("Plusieurs nœuds lus sont tous conservés")
    void severalNodesAreKept() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");
        mark(alice, "rpgquest:tc12_guide", "suite");
        mark(alice, "rpgquest:tc12_guide", "fin");

        assertEquals(Set.of("start", "suite", "fin"), read(alice, "rpgquest:tc12_guide"));
    }

    @Test
    @DisplayName("La lecture SURVIT à la fermeture et à la réouverture de la base")
    void readSurvivesRestart() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");
        database.shutdown();

        // Réouverture : c'est l'équivalent d'un redémarrage du serveur.
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new DialogueReadRepository(database);

        assertEquals(Set.of("start"), read(alice, "rpgquest:tc12_guide"),
                "l'état de lecture doit être persistant, pas en mémoire");
    }

    @Test
    @DisplayName("Le chargement initial d'un joueur renvoie tous ses nœuds, tous dialogues confondus")
    void allForPlayerCoversEveryDialogue() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");
        mark(alice, "rpgquest:tc12_libraire", "accueil");
        mark(bob, "rpgquest:tc12_guide", "start");

        Set<DialogueReadRepository.ReadNode> nodes =
                repository.allForPlayer(alice).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Set.of(
                new DialogueReadRepository.ReadNode("rpgquest:tc12_guide", "start"),
                new DialogueReadRepository.ReadNode("rpgquest:tc12_libraire", "accueil")), nodes);
    }

    @Test
    @DisplayName("Supprimer la lecture d'un joueur ne touche pas celle des autres")
    void deleteIsScopedToOnePlayer() throws Exception {
        mark(alice, "rpgquest:tc12_guide", "start");
        mark(bob, "rpgquest:tc12_guide", "start");

        int removed = repository.deleteAllForPlayer(alice).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, removed);
        assertTrue(read(alice, "rpgquest:tc12_guide").isEmpty());
        assertEquals(Set.of("start"), read(bob, "rpgquest:tc12_guide"),
                "le reset d'un joueur ne doit jamais effacer la lecture d'un autre");
    }
}
