package com.lodygames.rpgquest.config;

import java.util.List;

/**
 * Issue #776 : croissance des plantations rattrapée au rechargement d'un chunk de claim (voir {@code
 * claim.growth.OfflineGrowthService}).
 *
 * <p>{@code worlds} vide = le seul monde des claims ({@link ClaimConfig#world()}). Le rattrapage reste
 * toujours limité aux cuboïdes de claims, quel que soit le monde. {@code maxCatchUpMinutes} plafonne
 * la durée rattrapée (temps de jeu écoulé, en minutes de 1 200 ticks) ; {@code blocksPerTick} borne le
 * nombre de plantes traitées par tick serveur, tous chunks confondus.</p>
 */
public record OfflineGrowthConfig(boolean enabled, List<String> worlds, int maxCatchUpMinutes, int blocksPerTick) {

    public static final int DEFAULT_MAX_CATCH_UP_MINUTES = 1440;
    public static final int DEFAULT_BLOCKS_PER_TICK = 256;
    public static final int MAX_CATCH_UP_MINUTES_LIMIT = 43_200;
    public static final int BLOCKS_PER_TICK_LIMIT = 10_000;

    public static final OfflineGrowthConfig DEFAULTS =
            new OfflineGrowthConfig(true, List.of(), DEFAULT_MAX_CATCH_UP_MINUTES, DEFAULT_BLOCKS_PER_TICK);

    public OfflineGrowthConfig {
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
    }

    /** Monde concerné : la liste explicite si elle est renseignée, sinon le seul monde des claims. */
    public boolean appliesTo(String world, String claimsWorld) {
        return enabled && (worlds.isEmpty() ? world.equals(claimsWorld) : worlds.contains(world));
    }

    public long maxCatchUpTicks() {
        return maxCatchUpMinutes * 1200L;
    }
}
