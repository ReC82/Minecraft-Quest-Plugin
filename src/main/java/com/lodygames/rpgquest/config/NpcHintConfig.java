package com.lodygames.rpgquest.config;

import org.bukkit.Particle;

/**
 * Section {@code npc-hints:} (issue #12) — signal visuel discret au-dessus d'un PNJ quand une
 * <strong>quête est réellement disponible</strong> ou qu'un <strong>dialogue accessible n'a pas
 * été lu</strong>.
 *
 * <p><strong>Propre à chaque joueur.</strong> Les particules sont envoyées au seul joueur concerné
 * (API publique Paper, {@code Player#spawnParticle}) : deux joueurs devant le même PNJ voient donc
 * des états différents, sans jamais toucher au nom affiché du PNJ ni à quoi que ce soit de global.
 * Aucun mod client n'est requis.</p>
 *
 * <p><strong>Discrétion assumée.</strong> Les valeurs par défaut sont volontairement basses — une
 * à deux particules par passe, une fois par seconde. L'objectif est d'attirer l'œil, pas de
 * transformer le Hub en sapin de Noël. Toutes les valeurs sont <strong>bornées</strong> au
 * chargement : une configuration trop agressive est ramenée dans la plage utile au lieu d'être
 * subie.</p>
 *
 * @param enabled           active le signal
 * @param periodTicks       période de la passe d'affichage. <strong>Jamais par tick</strong> : une
 *                          passe par joueur, bornée {@value #MIN_PERIOD_TICKS}–{@value
 *                          #MAX_PERIOD_TICKS} ticks
 * @param radius            distance maximale, en blocs, à laquelle un PNJ est signalé. Au-delà,
 *                          rien n'est calculé ni envoyé
 * @param requireLineOfSight n'affiche que si le joueur voit réellement le PNJ (pas à travers un mur)
 * @param refreshSeconds    intervalle minimal entre deux <em>recalculs</em> de l'état d'un joueur.
 *                          L'affichage, lui, réutilise le cache — c'est ce qui rend la cadence
 *                          soutenable avec beaucoup de PNJ
 * @param heightOffset      hauteur du signal au-dessus des yeux du PNJ, en blocs
 * @param questParticle     particule signalant une quête disponible
 * @param dialogueParticle  particule signalant un dialogue accessible non lu
 * @param count             nombre de particules par passe (discrétion : 1 ou 2 suffisent)
 */
public record NpcHintConfig(boolean enabled, int periodTicks, double radius,
                            boolean requireLineOfSight, int refreshSeconds, double heightOffset,
                            Particle questParticle, Particle dialogueParticle, int count) {

    public static final int MIN_PERIOD_TICKS = 10;
    public static final int MAX_PERIOD_TICKS = 100;
    public static final double MIN_RADIUS = 4.0;
    public static final double MAX_RADIUS = 48.0;
    public static final int MIN_REFRESH_SECONDS = 1;
    public static final int MAX_REFRESH_SECONDS = 60;
    public static final double MIN_HEIGHT_OFFSET = 0.0;
    public static final double MAX_HEIGHT_OFFSET = 3.0;
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 10;

    /**
     * Valeurs par défaut : signal actif, discret, et peu coûteux. {@code HAPPY_VILLAGER} pour une
     * quête (vert, associé à « quelque chose de bien ») et {@code ENCHANT} pour un dialogue non lu
     * (glyphes, évoque la lecture) — deux conventions immédiatement distinguables, et toutes deux
     * rendues par un client vanilla.
     */
    public static NpcHintConfig defaults() {
        return new NpcHintConfig(true, 20, 16.0, true, 5, 2.2,
                Particle.HAPPY_VILLAGER, Particle.ENCHANT, 1);
    }

    /**
     * Ramène chaque valeur dans sa plage utile. Une configuration hors bornes est
     * <strong>corrigée</strong>, jamais refusée : un signal visuel ne doit pas empêcher le serveur
     * de démarrer.
     */
    public NpcHintConfig bounded() {
        return new NpcHintConfig(
                enabled,
                clamp(periodTicks, MIN_PERIOD_TICKS, MAX_PERIOD_TICKS),
                clamp(radius, MIN_RADIUS, MAX_RADIUS),
                requireLineOfSight,
                clamp(refreshSeconds, MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS),
                clamp(heightOffset, MIN_HEIGHT_OFFSET, MAX_HEIGHT_OFFSET),
                questParticle == null ? Particle.HAPPY_VILLAGER : questParticle,
                dialogueParticle == null ? Particle.ENCHANT : dialogueParticle,
                clamp(count, MIN_COUNT, MAX_COUNT));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
