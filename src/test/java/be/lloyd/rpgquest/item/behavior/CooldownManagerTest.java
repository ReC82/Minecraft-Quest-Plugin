package be.lloyd.rpgquest.item.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Pure JUnit : aucune dépendance Bukkit, l'horloge est injectée pour un contrôle déterministe du temps. */
class CooldownManagerTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final String ABILITY = "rpgquest:forest_blade:leaf_trail_slow";

    @Test
    void readyByDefault() {
        CooldownManager cooldowns = new CooldownManager(() -> 0L);
        assertTrue(cooldowns.isReady(PLAYER, ABILITY));
        assertEquals(0L, cooldowns.remainingMillis(PLAYER, ABILITY));
    }

    @Test
    void notReadyImmediatelyAfterStarting() {
        AtomicLong clock = new AtomicLong(0L);
        CooldownManager cooldowns = new CooldownManager(clock::get);

        cooldowns.start(PLAYER, ABILITY, 5000L);

        assertFalse(cooldowns.isReady(PLAYER, ABILITY));
        assertEquals(5000L, cooldowns.remainingMillis(PLAYER, ABILITY));
    }

    @Test
    void readyAgainOnceDurationElapsed() {
        AtomicLong clock = new AtomicLong(0L);
        CooldownManager cooldowns = new CooldownManager(clock::get);
        cooldowns.start(PLAYER, ABILITY, 5000L);

        clock.set(5000L);

        assertTrue(cooldowns.isReady(PLAYER, ABILITY));
        assertEquals(0L, cooldowns.remainingMillis(PLAYER, ABILITY));
    }

    @Test
    void differentAbilityIdsAreIndependent() {
        AtomicLong clock = new AtomicLong(0L);
        CooldownManager cooldowns = new CooldownManager(clock::get);
        cooldowns.start(PLAYER, "ability-a", 5000L);

        assertFalse(cooldowns.isReady(PLAYER, "ability-a"));
        assertTrue(cooldowns.isReady(PLAYER, "ability-b"), "un cooldown sur une autre capacité ne doit pas déborder");
    }

    @Test
    void differentPlayersAreIndependent() {
        AtomicLong clock = new AtomicLong(0L);
        CooldownManager cooldowns = new CooldownManager(clock::get);
        UUID otherPlayer = UUID.randomUUID();
        cooldowns.start(PLAYER, ABILITY, 5000L);

        assertFalse(cooldowns.isReady(PLAYER, ABILITY));
        assertTrue(cooldowns.isReady(otherPlayer, ABILITY), "un cooldown d'un joueur ne doit jamais affecter un autre");
    }

    @Test
    void isReadyLazilyRemovesExpiredEntry() {
        AtomicLong clock = new AtomicLong(0L);
        CooldownManager cooldowns = new CooldownManager(clock::get);
        cooldowns.start(PLAYER, ABILITY, 1000L);
        clock.set(1000L);

        assertTrue(cooldowns.isReady(PLAYER, ABILITY));
        assertEquals(0, cooldowns.trackedCount(), "l'entrée expirée doit être nettoyée dès sa consultation (rule 5)");
    }

    @Test
    void purgeExpiredRemovesOnlyExpiredEntriesRegardlessOfAccess() {
        AtomicLong clock = new AtomicLong(0L);
        CooldownManager cooldowns = new CooldownManager(clock::get);
        UUID otherPlayer = UUID.randomUUID();
        cooldowns.start(PLAYER, ABILITY, 1000L);   // expirera à t=1000
        cooldowns.start(otherPlayer, ABILITY, 5000L); // expirera à t=5000

        clock.set(2000L);
        int removed = cooldowns.purgeExpired();

        assertEquals(1, removed);
        assertEquals(1, cooldowns.trackedCount());
        assertFalse(cooldowns.isReady(otherPlayer, ABILITY), "le cooldown encore actif ne doit pas être purgé");
    }

    @Test
    void clearRemovesOnlyThatPlayersCooldowns() {
        CooldownManager cooldowns = new CooldownManager(() -> 0L);
        UUID otherPlayer = UUID.randomUUID();
        cooldowns.start(PLAYER, ABILITY, 5000L);
        cooldowns.start(otherPlayer, ABILITY, 5000L);

        cooldowns.clear(PLAYER);

        assertTrue(cooldowns.isReady(PLAYER, ABILITY), "les cooldowns du joueur déconnecté doivent être effacés");
        assertFalse(cooldowns.isReady(otherPlayer, ABILITY), "les cooldowns des autres joueurs ne doivent pas être touchés");
    }

    @Test
    void startWithNonPositiveDurationLeavesAbilityReady() {
        CooldownManager cooldowns = new CooldownManager(() -> 0L);
        cooldowns.start(PLAYER, ABILITY, 0L);
        assertTrue(cooldowns.isReady(PLAYER, ABILITY));
    }
}
