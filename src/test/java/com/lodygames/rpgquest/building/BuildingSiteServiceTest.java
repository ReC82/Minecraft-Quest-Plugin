package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.database.BuildingSiteRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #213 — les emplacements de construction, sur une vraie base SQLite temporaire.
 *
 * <p>Ce qui est réellement vérifié ici, c'est que la <strong>base</strong> est la source de vérité :
 * plusieurs tests reconstruisent un service neuf sur la même base, ce qui est exactement ce que fait
 * un redémarrage du serveur. Un emplacement qui ne survivrait qu'en mémoire passerait tous les
 * autres tests et échouerait au premier restart réel.</p>
 */
class BuildingSiteServiceTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private BuildingSiteRepository repository;
    private BuildingSiteService service;
    /** Horloge pilotée : l'anti-rebond se teste sans attendre une demi-seconde. */
    private MutableClock clock;

    /** Horloge avançable à la main — l'anti-rebond dépend du temps, pas d'un hasard. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T18:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        repository = new BuildingSiteRepository(database);
        clock = new MutableClock();
        service = new BuildingSiteService(repository, clock);
        service.load().get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private BuildingSiteService.CreateResult create(String world, int x, int y, int z, Facing facing)
            throws Exception {
        return service.create(world, new BuildingSiteAnchor(x, y, z), facing, "Lody", null)
                .get(TIMEOUT, TimeUnit.SECONDS);
    }

    // ---- Création ------------------------------------------------------------------------------

    @Test
    void creatingRecordsWorldCoordinatesAndFacing() throws Exception {
        BuildingSiteService.CreateResult result = create("world_hub", 712, 67, -702, Facing.WEST);

        assertTrue(result.created());
        BuildingSite site = result.site();
        assertEquals("buildsite_0001", site.id());
        assertEquals("world_hub", site.world());
        assertEquals(712, site.x());
        assertEquals(67, site.y());
        assertEquals(-702, site.z());
        assertEquals(Facing.WEST, site.facing());
        assertEquals(SiteStatus.EMPTY, site.status());
        assertEquals(BuildingSite.DEFAULT_NAME, site.name());
        assertEquals("", site.description());
        assertEquals("Lody", site.createdBy());
        assertEquals(Instant.parse("2026-10-08T18:00:00Z"), site.createdAt());
    }

    /** Les quatre orientations traversent la création et la relecture sans se déformer. */
    @Test
    void everyFacingSurvivesCreationAndReload() throws Exception {
        int z = 0;
        for (Facing facing : Facing.values()) {
            create("world_hub", 0, 64, z++, facing);
        }
        BuildingSiteService reopened = new BuildingSiteService(repository, clock);
        reopened.load().get(TIMEOUT, TimeUnit.SECONDS);

        List<BuildingSite> sites = reopened.all();
        assertEquals(4, sites.size());
        for (Facing facing : Facing.values()) {
            assertTrue(sites.stream().anyMatch(s -> s.facing() == facing), facing.name());
        }
    }

    @Test
    void identifiersAreSequentialAndUnique() throws Exception {
        assertEquals("buildsite_0001", create("world_hub", 0, 64, 0, Facing.NORTH).site().id());
        assertEquals("buildsite_0002", create("world_hub", 1, 64, 0, Facing.NORTH).site().id());
        assertEquals("buildsite_0003", create("world_hub", 2, 64, 0, Facing.NORTH).site().id());
        assertEquals(3, service.all().stream().map(BuildingSite::id).distinct().count());
    }

    /**
     * Le point important de l'allocateur : supprimer puis recréer ne <strong>réutilise pas</strong>
     * l'identifiant. Un identifiant recyclé ferait pointer un futur placement sur le mauvais
     * emplacement.
     */
    @Test
    void identifiersAreNeverReusedAfterADeletion() throws Exception {
        String first = create("world_hub", 0, 64, 0, Facing.NORTH).site().id();
        assertTrue(service.delete(first).get(TIMEOUT, TimeUnit.SECONDS));

        String next = create("world_hub", 0, 64, 0, Facing.NORTH).site().id();

        assertNotEquals(first, next);
        assertEquals("buildsite_0002", next);
    }

    @Test
    void theIdentifierIsZeroPaddedAndStillCorrectBeyondFourDigits() {
        assertEquals("buildsite_0001", BuildingSiteService.idFor(1));
        assertEquals("buildsite_0042", BuildingSiteService.idFor(42));
        assertEquals("buildsite_9999", BuildingSiteService.idFor(9999));
        assertEquals("buildsite_12345", BuildingSiteService.idFor(12345));
    }

    // ---- Anti-doublon --------------------------------------------------------------------------

    /**
     * Le cas du spam de clics : on ne crée pas dix emplacements sur le même bloc. On renvoie celui
     * qui existe, ce qui est aussi la bonne réponse à un double clic légitime.
     */
    @Test
    void clickingTheSameBlockTwiceReturnsTheExistingSite() throws Exception {
        BuildingSiteService.CreateResult first = create("world_hub", 10, 64, 10, Facing.NORTH);

        BuildingSiteService.CreateResult second = create("world_hub", 10, 64, 10, Facing.SOUTH);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(BuildingSiteService.CreateOutcome.ALREADY_THERE, second.outcome());
        assertEquals(first.site().id(), second.site().id());
        assertEquals(1, service.all().size(), "un seul emplacement, pas deux");
        assertEquals(Facing.NORTH, service.all().get(0).facing(),
                "l'emplacement existant n'est pas réorienté par un second clic");
    }

    /** Le même bloc dans un AUTRE monde est un autre emplacement : ce n'est pas un doublon. */
    @Test
    void theSameCoordinatesInAnotherWorldAreNotADuplicate() throws Exception {
        create("world_hub", 10, 64, 10, Facing.NORTH);

        BuildingSiteService.CreateResult other = create("claims", 10, 64, 10, Facing.NORTH);

        assertTrue(other.created());
        assertEquals(2, service.all().size());
    }

    /** Un bloc voisin est un emplacement légitime : aucune distance minimale n'est inventée. */
    @Test
    void anAdjacentBlockIsAllowed() throws Exception {
        create("world_hub", 10, 64, 10, Facing.NORTH);

        BuildingSiteService.CreateResult neighbour = create("world_hub", 11, 64, 10, Facing.NORTH);

        assertTrue(neighbour.created());
        assertEquals(2, service.all().size());
    }

    /**
     * Le double événement d'un seul clic droit : deux blocs <em>différents</em> en quelques
     * millisecondes. L'anti-rebond l'absorbe, là où la règle « même bloc » ne pouvait pas.
     */
    @Test
    void twoClicksWithinTheDebounceWindowCreateOnlyOne() throws Exception {
        String player = "11111111-1111-1111-1111-111111111111";

        var first = service.create("world_hub", new BuildingSiteAnchor(0, 64, 0), Facing.NORTH,
                "Lody", player).get(TIMEOUT, TimeUnit.SECONDS);
        var second = service.create("world_hub", new BuildingSiteAnchor(1, 64, 0), Facing.NORTH,
                "Lody", player).get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(first.created());
        assertEquals(BuildingSiteService.CreateOutcome.DEBOUNCED, second.outcome());
        assertEquals(1, service.all().size());
    }

    @Test
    void aDeliberateSecondClickAfterTheWindowIsAccepted() throws Exception {
        String player = "11111111-1111-1111-1111-111111111111";
        service.create("world_hub", new BuildingSiteAnchor(0, 64, 0), Facing.NORTH, "Lody", player)
                .get(TIMEOUT, TimeUnit.SECONDS);

        clock.advance(Duration.ofMillis(BuildingSiteService.DEBOUNCE_MILLIS + 1));
        var second = service.create("world_hub", new BuildingSiteAnchor(1, 64, 0), Facing.NORTH,
                "Lody", player).get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(second.created());
        assertEquals(2, service.all().size());
    }

    /** L'anti-rebond est par joueur : deux administrateurs ne se bloquent pas l'un l'autre. */
    @Test
    void theDebounceIsPerPlayer() throws Exception {
        service.create("world_hub", new BuildingSiteAnchor(0, 64, 0), Facing.NORTH, "A", "player-a")
                .get(TIMEOUT, TimeUnit.SECONDS);

        var other = service.create("world_hub", new BuildingSiteAnchor(1, 64, 0), Facing.NORTH,
                "B", "player-b").get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(other.created());
        assertEquals(2, service.all().size());
    }

    // ---- Persistance ---------------------------------------------------------------------------

    /** Le test qui compte : un service neuf sur la même base, c'est-à-dire un redémarrage. */
    @Test
    void sitesSurviveACompleteServiceRestart() throws Exception {
        create("world_hub", 712, 67, -702, Facing.WEST);
        service.rename("buildsite_0001", "Taverne du village").get(TIMEOUT, TimeUnit.SECONDS);
        service.describe("buildsite_0001", "Deux étages, entrée au sud.")
                .get(TIMEOUT, TimeUnit.SECONDS);

        BuildingSiteService restarted = new BuildingSiteService(repository, clock);
        assertTrue(restarted.all().isEmpty(), "avant load(), on ne prétend rien connaître");
        assertEquals(1, restarted.load().get(TIMEOUT, TimeUnit.SECONDS));

        BuildingSite site = restarted.find("buildsite_0001").orElseThrow();
        assertEquals("Taverne du village", site.name());
        assertEquals("Deux étages, entrée au sud.", site.description());
        assertEquals("world_hub", site.world());
        assertEquals(712, site.x());
        assertEquals(67, site.y());
        assertEquals(-702, site.z());
        assertEquals(Facing.WEST, site.facing());
        assertEquals("Lody", site.createdBy());
    }

    /** Une suppression aussi doit survivre : elle est écrite, pas seulement oubliée du cache. */
    @Test
    void aDeletionSurvivesARestart() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);
        service.delete("buildsite_0001").get(TIMEOUT, TimeUnit.SECONDS);

        BuildingSiteService restarted = new BuildingSiteService(repository, clock);
        assertEquals(0, restarted.load().get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(restarted.find("buildsite_0001").isEmpty());
    }

    // ---- Modification --------------------------------------------------------------------------

    @Test
    void renamingChangesOnlyTheLabel() throws Exception {
        create("world_hub", 712, 67, -702, Facing.WEST);

        BuildingSite renamed = service.rename("buildsite_0001", "Test hutte")
                .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow();

        assertEquals("Test hutte", renamed.name());
        assertEquals("buildsite_0001", renamed.id(), "l'identité ne bouge jamais");
        assertEquals(712, renamed.x());
        assertEquals(Facing.WEST, renamed.facing());
    }

    @Test
    void anEmptyNameFallsBackToTheDefaultLabel() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);
        service.rename("buildsite_0001", "Test").get(TIMEOUT, TimeUnit.SECONDS);

        BuildingSite blanked = service.rename("buildsite_0001", "   ")
                .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow();

        assertEquals(BuildingSite.DEFAULT_NAME, blanked.name(),
                "une ligne sans nom à l'écran serait pire qu'un nom par défaut");
    }

    @Test
    void aTooLongNameIsTruncatedRatherThanRefused() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);

        BuildingSite renamed = service.rename("buildsite_0001", "n".repeat(200))
                .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow();

        assertEquals(BuildingSite.MAX_NAME_LENGTH, renamed.name().length());
    }

    @Test
    void refacingKeepsThePositionExactly() throws Exception {
        create("world_hub", 712, 67, -702, Facing.WEST);

        BuildingSite refaced = service.reface("buildsite_0001", Facing.EAST)
                .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow();

        assertEquals(Facing.EAST, refaced.facing());
        assertEquals(712, refaced.x());
        assertEquals(67, refaced.y());
        assertEquals(-702, refaced.z());
    }

    @Test
    void aDescriptionCanBeClearedAgain() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);
        service.describe("buildsite_0001", "Une note.").get(TIMEOUT, TimeUnit.SECONDS);

        BuildingSite cleared = service.describe("buildsite_0001", "")
                .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow();

        assertEquals("", cleared.description());
    }

    @Test
    void mutatingAnUnknownSiteChangesNothing() throws Exception {
        assertTrue(service.rename("buildsite_9999", "x").get(TIMEOUT, TimeUnit.SECONDS).isEmpty());
        assertTrue(service.describe("buildsite_9999", "x").get(TIMEOUT, TimeUnit.SECONDS).isEmpty());
        assertTrue(service.reface("buildsite_9999", Facing.NORTH)
                .get(TIMEOUT, TimeUnit.SECONDS).isEmpty());
        assertTrue(service.all().isEmpty());
    }

    // ---- Suppression ---------------------------------------------------------------------------

    @Test
    void deletingAnEmptySiteRemovesItEverywhere() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);

        assertTrue(service.delete("buildsite_0001").get(TIMEOUT, TimeUnit.SECONDS));

        assertTrue(service.all().isEmpty());
        assertTrue(repository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).isEmpty());
    }

    /** Double clic sur « Supprimer » : le second aboutit au même état, sans erreur. */
    @Test
    void deletingTwiceIsIdempotent() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);

        assertTrue(service.delete("buildsite_0001").get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(service.delete("buildsite_0001").get(TIMEOUT, TimeUnit.SECONDS),
                "rien à supprimer n'est pas une erreur");
        assertTrue(service.all().isEmpty());
    }

    @Test
    void deletingAnUnknownOrBlankIdIsHarmless() throws Exception {
        assertFalse(service.delete("buildsite_9999").get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(service.delete("").get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(service.delete(null).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void deletingOneSiteLeavesTheOthersAlone() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);
        create("world_hub", 1, 64, 0, Facing.NORTH);

        service.delete("buildsite_0001").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(1, service.all().size());
        assertEquals("buildsite_0002", service.all().get(0).id());
    }

    // ---- Lecture -------------------------------------------------------------------------------

    @Test
    void listingIsSortedByIdentifierSoByCreationOrder() throws Exception {
        create("b_world", 0, 64, 0, Facing.NORTH);
        create("a_world", 0, 64, 0, Facing.NORTH);

        assertEquals(List.of("buildsite_0001", "buildsite_0002"),
                service.all().stream().map(BuildingSite::id).toList());
    }

    @Test
    void worldsAreTheDistinctSortedWorldsThatActuallyHoldASite() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);
        create("world_hub", 1, 64, 0, Facing.NORTH);
        create("claims", 0, 64, 0, Facing.NORTH);

        assertEquals(List.of("claims", "world_hub"), service.worlds());
    }

    @Test
    void findIsCaseInsensitiveAndSafeOnBlanks() throws Exception {
        create("world_hub", 0, 64, 0, Facing.NORTH);

        assertTrue(service.find("BUILDSITE_0001").isPresent());
        assertTrue(service.find("  buildsite_0001 ").isPresent());
        assertTrue(service.find("").isEmpty());
        assertTrue(service.find(null).isEmpty());
    }

    @Test
    void atFindsTheSiteSittingExactlyOnABlock() throws Exception {
        create("world_hub", 5, 64, 5, Facing.NORTH);

        Optional<BuildingSite> found = service.at("world_hub", 5, 64, 5);

        assertTrue(found.isPresent());
        assertTrue(service.at("world_hub", 5, 65, 5).isEmpty(), "un bloc au-dessus est un autre bloc");
        assertTrue(service.at("claims", 5, 64, 5).isEmpty());
    }

    /** Une valeur d'état inconnue en base ne doit pas faire perdre l'emplacement entier. */
    @Test
    void anUnknownStoredStatusIsReadAsEmpty() {
        assertEquals(SiteStatus.EMPTY, SiteStatus.of("OCCUPIED_BY_A_FUTURE_VERSION"));
        assertEquals(SiteStatus.EMPTY, SiteStatus.of(null));
        assertEquals(SiteStatus.EMPTY, SiteStatus.of(""));
        assertTrue(SiteStatus.parse("OCCUPIED").isEmpty(), "mais on sait dire qu'elle est inconnue");
        assertTrue(SiteStatus.parse("empty").isPresent());
    }
}
