package com.lodygames.rpgquest.mob.community;

/**
 * Issue #175 — réglages globaux du scheduler des boss communautaires ({@code mobs/community-bosses.yml},
 * édités depuis PlugAdmin, jamais à la main).
 *
 * <p>Désactivé par défaut : un serveur qui reçoit ce JAR ne voit apparaître aucun boss tant qu'un
 * administrateur n'a pas explicitement activé le scheduler. Les profils éligibles sont portés par les
 * profils eux-mêmes ({@code category: BOSS} + {@code boss-mode: COMMUNITY}) ; les mondes et biomes
 * autorisés aussi ({@code worlds}/{@code biomes} du profil, toujours intersectés avec les mondes Wild).</p>
 *
 * @param enabled         scheduler actif (false = pause immédiate, aucun boss existant n'est supprimé)
 * @param intervalMinutes délai entre deux échéances (défaut 15)
 * @param maxActive       nombre maximum de boss communautaires actifs en même temps (défaut 1)
 * @param participationRadius issue #176 : distance maximale (blocs, distance 3D) entre un participant et la
 *                        position du boss à sa mort pour être récompensé (défaut 100)
 * @param glow            issue #471 : le boss brille (contour visible à travers les blocs) quand un joueur est
 *                        à moins de {@code glowRadius} blocs (défaut activé)
 * @param glowRadius      issue #471 : distance d'allumage du contour (défaut 48 = portée de la barre de vie).
 *                        Le client ne reçoit l'entité qu'à sa portée de suivi (48 blocs par défaut pour les
 *                        monstres sur Paper) : au-delà, un rayon plus grand ne montre rien de plus
 * @param surfaceOnly     issue #471 : apparition seulement sur un sol naturel à ciel ouvert, plat, au sec,
 *                        loin des bords (défaut activé) ; désactivé = règle d'origine (plus haut bloc praticable)
 */
public record CommunityBossSettings(boolean enabled, int intervalMinutes, int maxActive, int participationRadius,
                                    boolean glow, int glowRadius, boolean surfaceOnly) {

    public static final int DEFAULT_INTERVAL_MINUTES = 15;
    public static final int DEFAULT_MAX_ACTIVE = 1;
    public static final int MIN_INTERVAL_MINUTES = 1;
    public static final int MAX_INTERVAL_MINUTES = 24 * 60;
    public static final int MAX_MAX_ACTIVE = 10;
    public static final int DEFAULT_PARTICIPATION_RADIUS = 100;
    public static final int MIN_PARTICIPATION_RADIUS = 5;
    public static final int MAX_PARTICIPATION_RADIUS = 500;
    public static final int DEFAULT_GLOW_RADIUS = 48;
    public static final int MIN_GLOW_RADIUS = 8;
    public static final int MAX_GLOW_RADIUS = 128;

    public CommunityBossSettings {
        if (intervalMinutes < MIN_INTERVAL_MINUTES || intervalMinutes > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("L'intervalle doit être compris entre " + MIN_INTERVAL_MINUTES
                    + " et " + MAX_INTERVAL_MINUTES + " minutes : " + intervalMinutes);
        }
        if (maxActive < 1 || maxActive > MAX_MAX_ACTIVE) {
            throw new IllegalArgumentException("Le nombre maximum de boss simultanés doit être compris entre 1 et "
                    + MAX_MAX_ACTIVE + " : " + maxActive);
        }
        if (participationRadius < MIN_PARTICIPATION_RADIUS || participationRadius > MAX_PARTICIPATION_RADIUS) {
            throw new IllegalArgumentException("La distance de participation doit être comprise entre "
                    + MIN_PARTICIPATION_RADIUS + " et " + MAX_PARTICIPATION_RADIUS + " blocs : " + participationRadius);
        }
        if (glowRadius < MIN_GLOW_RADIUS || glowRadius > MAX_GLOW_RADIUS) {
            throw new IllegalArgumentException("La distance d'allumage du contour doit être comprise entre "
                    + MIN_GLOW_RADIUS + " et " + MAX_GLOW_RADIUS + " blocs : " + glowRadius);
        }
    }

    /** Réglages antérieurs à #471 : contour et surface à leurs valeurs par défaut. */
    public CommunityBossSettings(boolean enabled, int intervalMinutes, int maxActive, int participationRadius) {
        this(enabled, intervalMinutes, maxActive, participationRadius, true, DEFAULT_GLOW_RADIUS, true);
    }

    /** Réglages du scheduler seuls (#175) : distance de participation par défaut. */
    public CommunityBossSettings(boolean enabled, int intervalMinutes, int maxActive) {
        this(enabled, intervalMinutes, maxActive, DEFAULT_PARTICIPATION_RADIUS);
    }

    public static CommunityBossSettings defaults() {
        return new CommunityBossSettings(false, DEFAULT_INTERVAL_MINUTES, DEFAULT_MAX_ACTIVE);
    }

    /** Mêmes réglages, contour et surface remplacés (#471). */
    public CommunityBossSettings withLocating(boolean newGlow, int newGlowRadius, boolean newSurfaceOnly) {
        return new CommunityBossSettings(enabled, intervalMinutes, maxActive, participationRadius,
                newGlow, newGlowRadius, newSurfaceOnly);
    }

    public long intervalMillis() {
        return intervalMinutes * 60_000L;
    }
}
