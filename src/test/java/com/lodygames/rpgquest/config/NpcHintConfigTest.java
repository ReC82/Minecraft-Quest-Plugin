package com.lodygames.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Particle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bornage de la configuration du signal visuel (issue #12).
 *
 * <p>Ce qui compte ici : une configuration absurde ne doit ni empêcher le serveur de démarrer, ni
 * le dégrader. Elle est <strong>corrigée</strong>, et la correction est vérifiable.</p>
 */
class NpcHintConfigTest {

    @Test
    @DisplayName("Les valeurs par défaut sont discrètes et peu coûteuses")
    void defaultsAreDiscreet() {
        NpcHintConfig c = NpcHintConfig.defaults();

        assertTrue(c.enabled());
        assertEquals(20, c.periodTicks(), "une passe par seconde, jamais par tick");
        assertEquals(1, c.count(), "une seule particule par passe : discrétion");
        assertTrue(c.requireLineOfSight(), "pas de signal à travers un mur par défaut");
        assertEquals(Particle.HAPPY_VILLAGER, c.questParticle());
        assertEquals(Particle.ENCHANT, c.dialogueParticle());
        org.junit.jupiter.api.Assertions.assertNotEquals(c.questParticle(), c.dialogueParticle(),
                "les deux conventions doivent être distinguables");
    }

    @Test
    @DisplayName("Une période trop courte est relevée : jamais de travail à chaque tick")
    void periodIsClampedUp() {
        NpcHintConfig c = new NpcHintConfig(true, 1, 16, true, 5, 2.0,
                Particle.CRIT, Particle.ENCHANT, 1).bounded();

        assertEquals(NpcHintConfig.MIN_PERIOD_TICKS, c.periodTicks());
    }

    @Test
    @DisplayName("Une période absurde est abaissée, un rayon démesuré est réduit")
    void periodAndRadiusAreClampedDown() {
        NpcHintConfig c = new NpcHintConfig(true, 100_000, 5_000, true, 5, 2.0,
                Particle.CRIT, Particle.ENCHANT, 1).bounded();

        assertEquals(NpcHintConfig.MAX_PERIOD_TICKS, c.periodTicks());
        assertEquals(NpcHintConfig.MAX_RADIUS, c.radius());
    }

    @Test
    @DisplayName("Les autres valeurs numériques sont bornées dans les deux sens")
    void otherValuesAreClamped() {
        NpcHintConfig low = new NpcHintConfig(true, 20, 0.1, true, 0, -5, null, null, 0).bounded();
        assertEquals(NpcHintConfig.MIN_RADIUS, low.radius());
        assertEquals(NpcHintConfig.MIN_REFRESH_SECONDS, low.refreshSeconds());
        assertEquals(NpcHintConfig.MIN_HEIGHT_OFFSET, low.heightOffset());
        assertEquals(NpcHintConfig.MIN_COUNT, low.count());

        NpcHintConfig high = new NpcHintConfig(true, 20, 20, true, 9999, 99,
                Particle.CRIT, Particle.CRIT, 9999).bounded();
        assertEquals(NpcHintConfig.MAX_REFRESH_SECONDS, high.refreshSeconds());
        assertEquals(NpcHintConfig.MAX_HEIGHT_OFFSET, high.heightOffset());
        assertEquals(NpcHintConfig.MAX_COUNT, high.count());
    }

    @Test
    @DisplayName("Une particule absente est remplacée par le défaut, jamais laissée nulle")
    void nullParticlesFallBack() {
        NpcHintConfig c = new NpcHintConfig(true, 20, 16, true, 5, 2.0, null, null, 1).bounded();

        assertEquals(Particle.HAPPY_VILLAGER, c.questParticle());
        assertEquals(Particle.ENCHANT, c.dialogueParticle());
    }

    @Test
    @DisplayName("Le bornage ne touche pas aux interrupteurs : désactivé reste désactivé")
    void booleansAreNotTouched() {
        NpcHintConfig c = new NpcHintConfig(false, 20, 16, false, 5, 2.0,
                Particle.CRIT, Particle.ENCHANT, 1).bounded();

        assertFalse(c.enabled());
        assertFalse(c.requireLineOfSight());
    }

    @Test
    @DisplayName("Borner une configuration déjà valide ne la change pas")
    void boundingIsIdempotent() {
        NpcHintConfig c = NpcHintConfig.defaults();

        assertEquals(c, c.bounded());
        assertEquals(c.bounded(), c.bounded().bounded());
    }
}
