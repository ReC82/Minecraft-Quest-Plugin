package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #227 — les demandes de création en attente de nom.
 *
 * <p>C'est la pièce qui garantit la promesse du lot : <strong>un clic de travers ne laisse
 * rien</strong>. Elle est sans Bukkit, donc ces règles sont réellement exécutées, là où le rendu de
 * la fenêtre chez le client ne pourra jamais l'être.</p>
 */
class PendingBuildingSiteRegistryTest {

    private static final String PLAYER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    private MutableClock clock;
    private PendingBuildingSiteRegistry registry;

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T20:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        registry = new PendingBuildingSiteRegistry(clock, Duration.ofSeconds(60));
    }

    private PendingBuildingSite open(String player, int x, int y, int z) {
        return registry.open(player, "world_hub", new BuildingSiteAnchor(x, y, z), Facing.EAST,
                x, y - 1, z);
    }

    // ---- Ouverture -----------------------------------------------------------------------------

    @Test
    void openingRetainsEverythingTheConfirmationWillNeed() {
        PendingBuildingSite pending = open(PLAYER, 712, 67, -702);

        assertEquals(PLAYER, pending.playerId());
        assertEquals("world_hub", pending.world());
        assertEquals(712, pending.anchor().x());
        assertEquals(67, pending.anchor().y());
        assertEquals(-702, pending.anchor().z());
        assertEquals(Facing.EAST, pending.facing());
        assertEquals(66, pending.clickedY(), "le bloc réellement cliqué est conservé");
        assertEquals("712 / 67 / -702", pending.positionLabel());
        assertEquals(Instant.parse("2026-10-08T20:00:00Z"), pending.openedAt());
        assertEquals(Instant.parse("2026-10-08T20:01:00Z"), pending.expiresAt());
    }

    /** Cliquer ailleurs remplace la demande : on ne veut pas deux formulaires à traiter. */
    @Test
    void clickingElsewhereReplacesThePendingRatherThanStacking() {
        open(PLAYER, 0, 64, 0);

        PendingBuildingSite second = open(PLAYER, 10, 64, 10);

        assertEquals(1, registry.size());
        assertEquals(10, registry.find(PLAYER).orElseThrow().anchor().x());
        assertEquals(second.anchor().x(), registry.find(PLAYER).orElseThrow().anchor().x());
    }

    @Test
    void twoPlayersHoldTheirOwnPendingIndependently() {
        open(PLAYER, 0, 64, 0);
        open(OTHER, 99, 64, 99);

        assertEquals(2, registry.size());
        assertEquals(0, registry.find(PLAYER).orElseThrow().anchor().x());
        assertEquals(99, registry.find(OTHER).orElseThrow().anchor().x());
    }

    // ---- Confirmation (take) -------------------------------------------------------------------

    /**
     * {@code take} est atomique : c'est ce qui rend un double clic sur le bouton de validation
     * inoffensif — le second ne trouve plus rien à confirmer.
     */
    @Test
    void takingConsumesThePendingSoASecondClickFindsNothing() {
        open(PLAYER, 0, 64, 0);

        Optional<PendingBuildingSite> first = registry.take(PLAYER);
        Optional<PendingBuildingSite> second = registry.take(PLAYER);

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty(), "un double clic ne doit pas confirmer deux fois");
        assertEquals(0, registry.size());
    }

    @Test
    void takingAnUnknownPlayerIsHarmless() {
        assertTrue(registry.take(PLAYER).isEmpty());
        assertTrue(registry.take(null).isEmpty());
        assertEquals(0, registry.size());
    }

    // ---- Expiration ----------------------------------------------------------------------------

    @Test
    void aPendingIsStillValidJustBeforeItsDeadline() {
        open(PLAYER, 0, 64, 0);

        clock.advance(Duration.ofSeconds(59));

        assertTrue(registry.find(PLAYER).isPresent());
        assertTrue(registry.take(PLAYER).isPresent());
    }

    /** Le cas « timeout → 0 site » : passé le délai, il n'y a plus rien à confirmer. */
    @Test
    void anExpiredPendingCannotBeConfirmed() {
        open(PLAYER, 0, 64, 0);

        clock.advance(Duration.ofSeconds(61));

        assertTrue(registry.find(PLAYER).isEmpty());
        assertTrue(registry.take(PLAYER).isEmpty());
    }

    /**
     * L'expiration est évaluée <strong>à la lecture</strong>, pas par une tâche de fond : la règle
     * reste vraie même si la purge ne tourne pas, ou tourne en retard.
     */
    @Test
    void expiryHoldsEvenWithoutThePurgeTask() {
        open(PLAYER, 0, 64, 0);
        clock.advance(Duration.ofHours(1));

        assertTrue(registry.find(PLAYER).isEmpty(), "sans purge, la lecture suffit");
    }

    /** Lire une demande expirée la retire au passage : c'est le bon moment pour s'en défaire. */
    @Test
    void readingAnExpiredPendingAlsoRemovesIt() {
        open(PLAYER, 0, 64, 0);
        clock.advance(Duration.ofSeconds(61));

        registry.find(PLAYER);

        assertEquals(0, registry.size());
    }

    @Test
    void purgeReportsWhosePendingJustExpired() {
        open(PLAYER, 0, 64, 0);
        open(OTHER, 1, 64, 0);
        clock.advance(Duration.ofSeconds(30));
        open("33333333-3333-3333-3333-333333333333", 2, 64, 0);
        clock.advance(Duration.ofSeconds(31));

        List<String> expired = registry.purgeExpired();

        assertEquals(2, expired.size(), expired.toString());
        assertTrue(expired.contains(PLAYER));
        assertTrue(expired.contains(OTHER));
        assertEquals(1, registry.size(), "la demande plus récente reste");
    }

    @Test
    void purgingWithNothingExpiredChangesNothing() {
        open(PLAYER, 0, 64, 0);

        assertTrue(registry.purgeExpired().isEmpty());
        assertEquals(1, registry.size());
    }

    // ---- Annulation ----------------------------------------------------------------------------

    /** Fermer la fenêtre, ou se déconnecter : la demande disparaît, et elle n'avait rien écrit. */
    @Test
    void cancellingRemovesThePending() {
        open(PLAYER, 0, 64, 0);

        registry.cancel(PLAYER);

        assertTrue(registry.find(PLAYER).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void cancellingAnUnknownPlayerIsHarmless() {
        registry.cancel(PLAYER);
        registry.cancel(null);

        assertEquals(0, registry.size());
    }

    /**
     * {@code hadPending} distingue « vous n'avez rien demandé » de « votre demande a expiré ». Deux
     * messages très différents, et c'est ce qui permet à la fermeture de se taire après un succès.
     */
    @Test
    void hadPendingTellsWhetherThereWasAnythingAtAll() {
        assertFalse(registry.hadPending(PLAYER));

        open(PLAYER, 0, 64, 0);
        assertTrue(registry.hadPending(PLAYER));

        registry.take(PLAYER);
        assertFalse(registry.hadPending(PLAYER),
                "après une confirmation, la fermeture qui suit ne doit rien annoncer");
    }

    // ---- Garde-fous du modèle ------------------------------------------------------------------

    @Test
    void aPendingKnowsWhetherItDescribesAGivenBlock() {
        PendingBuildingSite pending = open(PLAYER, 5, 64, 5);

        assertTrue(pending.matches("world_hub", 5, 64, 5));
        assertFalse(pending.matches("world_hub", 5, 65, 5));
        assertFalse(pending.matches("claims", 5, 64, 5));
    }

    @Test
    void aZeroOrNegativeTtlFallsBackToTheDefault() {
        var zero = new PendingBuildingSiteRegistry(clock, Duration.ZERO);
        var negative = new PendingBuildingSiteRegistry(clock, Duration.ofSeconds(-5));
        var none = new PendingBuildingSiteRegistry(clock, null);

        for (var reg : List.of(zero, negative, none)) {
            PendingBuildingSite pending = reg.open(PLAYER, "world_hub",
                    new BuildingSiteAnchor(0, 64, 0), Facing.NORTH, 0, 63, 0);
            assertEquals(clock.instant().plus(PendingBuildingSiteRegistry.DEFAULT_TTL),
                    pending.expiresAt());
        }
    }
}
