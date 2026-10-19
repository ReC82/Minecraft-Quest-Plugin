package com.lodygames.rpgquest.mob.ability;

import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Issue #542 — politique centrale des capacités : où elles peuvent faire apparaître une entité, et
 * qui elles peuvent viser. Aucune capacité ne teste elle-même le Hub, un claim ou une zone.
 *
 * <ul>
 *   <li><b>Apparitions</b> (renforts, divisions) : refusées dans le monde du Hub, dans un claim, et
 *       dans une zone RPGQuest qui interdit l'apparition d'hostiles. Avant #542, ces apparitions
 *       ({@code world.spawnEntity}) ne consultaient rien.</li>
 *   <li><b>Cibles</b> : seulement un joueur en survie ou aventure, vivant, qui n'est pas un PNJ
 *       (décision Q9 de #541 : ni PNJ Citizens, ni joueur en créatif, ni spectateur).</li>
 *   <li><b>Terrain</b> : aucune capacité ne pose ni ne casse de bloc ({@link AbilityDescriptor.WorldMutation#NONE}).
 *       Les explosions restent de vraies explosions (dégâts aux entités, protections existantes), mais
 *       sans casse de bloc : {@code breakBlocks = false} et liste de blocs vidée par
 *       {@link AbilityEngine#onEntityExplode} (retours TC-361).</li>
 * </ul>
 */
public final class AbilityWorldPolicy {

    /** Vrai si la position est protégée (claim, zone…). */
    @FunctionalInterface
    public interface AreaProbe {
        boolean isProtected(String world, int x, int y, int z);
    }

    private final Supplier<String> hubWorld;
    private final AreaProbe claims;
    private final AreaProbe zones;
    private final Predicate<Entity> isNpc;

    /**
     * @param hubWorld nom du monde du Hub (lu à chaque appel : suit un rechargement de config)
     * @param claims   vrai dans un claim de joueur
     * @param zones    vrai dans une zone RPGQuest qui interdit les hostiles
     * @param isNpc    vrai pour un PNJ (Citizens ou RPGQuest)
     */
    public AbilityWorldPolicy(Supplier<String> hubWorld, AreaProbe claims, AreaProbe zones, Predicate<Entity> isNpc) {
        this.hubWorld = hubWorld;
        this.claims = claims;
        this.zones = zones;
        this.isNpc = isNpc;
    }

    /** Sans Hub, claim, zone ni PNJ : pour les tests. */
    public static AbilityWorldPolicy unrestricted() {
        return new AbilityWorldPolicy(() -> null, (w, x, y, z) -> false, (w, x, y, z) -> false, e -> false);
    }

    /** Une capacité peut-elle faire apparaître une entité ici ? */
    public boolean allowsSpawnAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        String name = world.getName();
        String hub = hubWorld.get();
        if (hub != null && hub.equalsIgnoreCase(name)) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return !claims.isProtected(name, x, y, z) && !zones.isProtected(name, x, y, z);
    }

    /** Ce joueur peut-il être pris pour cible par une capacité ? */
    public boolean isValidTarget(Player player) {
        if (player == null || player.isDead() || !player.isOnline() || isNpc.test(player)) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }
}
