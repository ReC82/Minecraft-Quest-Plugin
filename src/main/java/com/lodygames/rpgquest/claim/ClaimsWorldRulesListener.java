package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Règles de <strong>monde</strong> propres au monde des claims ({@link ClaimConfig#world()}) —
 * distinct de {@link ClaimProtectionListener} (protection d'un cuboïde de claim précis, dans
 * n'importe quel monde) : ici, trois règles s'appliquent au monde entier, même patron que {@code
 * hub.HubWorldProtectionListener} :
 *
 * <ul>
 *   <li>zone résidentielle <strong>totalement safe</strong> pour un joueur : tout dégât subi par un
 *       joueur est annulé ({@code EntityDamageEvent}, n'importe quelle {@code DamageCause} —
 *       chute, feu, lave, noyade, suffocation, explosion, projectile, attaque d'entité, PvP, faim,
 *       vide... mission « monde claims = réellement pacifique ») ; jamais un effet persistant
 *       (aucune résistance/invulnérabilité posée sur le joueur) — uniquement l'événement annulé,
 *       donc le comportement normal revient instantanément dès que le joueur quitte ce monde ;</li>
 *   <li>spawn de monstres hostiles empêché ({@code CreatureSpawnEvent}), quelle que soit la cause
 *       (naturel, spawner, renforts de zombie, invasion de patrouille, invocation par commande...) —
 *       pas seulement le spawn naturel : le monde des claims doit être <strong>réellement</strong>
 *       pacifique (mission « monstres hostiles dans claims »), le joueur ne doit jamais y rencontrer
 *       de monstre hostile, quelle qu'en soit l'origine ;</li>
 *   <li>mobs hostiles déjà présents nettoyés à chaque chargement de monde/chunk ({@link
 *       #onWorldLoad}/{@link #onChunkLoad}), plus une purge explicite unique au démarrage du plugin
 *       ({@link #purgeAlreadyLoadedWorld()}, appelée par le bootstrap : les mondes se chargent avant
 *       les plugins, {@code WorldLoadEvent} ne se déclenche donc jamais pour un monde déjà chargé à
 *       ce moment-là) — jamais les animaux/passifs, seulement {@link Monster} ;</li>
 *   <li>casse/pose de bloc refusée <strong>hors de tout claim</strong> (le monde des claims n'est
 *       pas une zone de construction libre — seul l'intérieur d'un claim, protégé/géré par {@link
 *       ClaimProtectionListener}, est constructible). Bypass {@code rpgquest.admin.world}.</li>
 * </ul>
 *
 * <p><strong>Volontairement absent</strong> (contrairement au Hub) : aucun verrouillage du
 * jour/nuit ni de la météo — {@code hub.HubWorldRulesService} n'a pas d'équivalent ici, le monde des
 * claims garde un cycle jour/nuit et une météo normaux.</p>
 */
public final class ClaimsWorldRulesListener implements Listener {

    private static final String BYPASS_PERMISSION = "rpgquest.admin.world";

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final Supplier<ClaimConfig> config;

    public ClaimsWorldRulesListener(RPGQuestPlugin plugin, ClaimService claimService, Supplier<ClaimConfig> config) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.config = config;
    }

    // ---- Zone résidentielle totalement safe (tout dégât joueur annulé, toute cause) -----------

    /**
     * Annule <strong>tout</strong> dégât subi par un joueur dans le monde des claims, quelle que
     * soit la {@code DamageCause} (chute, feu, lave, noyade, suffocation, explosion, projectile,
     * attaque d'entité — PvP inclus, faim, vide...) — englobe strictement {@code
     * EntityDamageByEntityEvent} (PvP) puisqu'elle hérite de {@code EntityDamageEvent}, donc plus
     * besoin d'un second gestionnaire dédié au PvP. Jamais d'effet persistant posé sur le joueur
     * (pas de résistance/invulnérabilité) : uniquement l'événement annulé, comportement normal
     * immédiatement de retour dès la sortie de ce monde (mission « pas de god mode persistant »).
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isClaimsWorld(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    // ---- Spawns de mobs hostiles (toute cause, pas seulement le spawn naturel) ----------------

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Monster && isClaimsWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ---- Nettoyage des mobs hostiles déjà présents ---------------------------------------------

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (isClaimsWorld(event.getWorld())) {
            purgeHostileMobs(event.getWorld().getEntities());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (isClaimsWorld(event.getWorld())) {
            purgeHostileMobs(Arrays.asList(event.getChunk().getEntities()));
        }
    }

    /**
     * Purge immédiate, appelée explicitement par le bootstrap juste après l'enregistrement de ce
     * listener : au démarrage du serveur, les mondes se chargent avant les plugins, donc {@link
     * #onWorldLoad} ne se déclenche jamais pour le monde des claims s'il est déjà chargé à ce
     * moment-là — sans cet appel, un mob hostile déjà présent avant cette fonctionnalité resterait
     * indéfiniment. Sans effet si le monde n'est pas (encore) chargé.
     */
    public void purgeAlreadyLoadedWorld() {
        World world = plugin.getServer().getWorld(config.get().world());
        if (world != null) {
            purgeHostileMobs(world.getEntities());
        }
    }

    private void purgeHostileMobs(Collection<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof Monster) {
                entity.remove();
            }
        }
    }

    // ---- Construction hors de tout claim ------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isClaimsWorld(event.getBlock().getWorld()) && !isBypassing(event.getPlayer())
                && claimAt(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ()).isEmpty()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isClaimsWorld(event.getBlockPlaced().getWorld()) && !isBypassing(event.getPlayer())
                && claimAt(event.getBlockPlaced().getWorld(), event.getBlockPlaced().getX(),
                        event.getBlockPlaced().getY(), event.getBlockPlaced().getZ()).isEmpty()) {
            event.setCancelled(true);
        }
    }

    // ---- Utilitaires ---------------------------------------------------------------------------

    private Optional<Claim> claimAt(World world, int x, int y, int z) {
        return claimService.claimAt(world.getName(), x, y, z);
    }

    private boolean isClaimsWorld(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private boolean isBypassing(Player player) {
        return player != null && player.hasPermission(BYPASS_PERMISSION);
    }
}
