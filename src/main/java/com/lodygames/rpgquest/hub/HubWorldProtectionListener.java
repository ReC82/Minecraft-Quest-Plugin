package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Applique les protections d'<strong>événement</strong> du Hub (dégâts, casse/pose de bloc, spawn
 * de mob hostile, explosions), scoped par le nom de monde de {@link HubConfig#world()} — jamais un
 * cuboïde comme {@code zone.ZoneProtectionListener} : le Hub est un monde entier dédié, pas une
 * région à l'intérieur d'un monde partagé.
 *
 * <p><strong>Dégâts joueur — règle unique, sans exception</strong> : {@code onEntityDamage}
 * annule tout dégât subi par un joueur dans le Hub, quelle que soit la cause (PvP, mob, chute,
 * explosion, environnement...).</p>
 *
 * <p><strong>Dégâts aux entités protégées (issues #30/#31)</strong> : tout dégât <strong>causé par
 * un joueur</strong> (mêlée ou projectile) à une entité vivante non-joueur du Hub est annulé — sauf
 * un PNJ Citizens, jamais concerné (Citizens gère déjà sa propre invulnérabilité). Ceci couvre
 * animaux passifs, mobs décoratifs, villageois et toute autre entité vivante, pas seulement les
 * drops. Bypass {@code rpgquest.admin.hub.combat}, <strong>explicite et distinct</strong> du droit
 * de construire ({@code rpgquest.admin.world}) — jamais accordé implicitement par ce dernier. Depuis
 * l'issue #35, il ne compte <strong>qu'en mode bypass administrateur activé</strong> : son défaut
 * {@code op} ne laisse plus un OP frapper les entités du Hub sans geste explicite.</p>
 *
 * <p><strong>Spawns de mobs indésirables (issues #121/#155)</strong> : tout spawn (quelle que soit
 * la raison — naturel, génération de chunk, spawner...) d'un mob hostile (tout {@link Monster},
 * {@code ENDERMAN}) ou d'un marchand ambulant/lama de commerce ({@code WANDERING_TRADER}/
 * {@code TRADER_LLAMA}) dans le Hub est annulé à la source. Un nettoyage ciblé des entités déjà
 * présentes (avant l'activation de cette protection) s'applique aux chunks déjà chargés au
 * démarrage ({@link #sweepAlreadyLoaded}) et à chaque nouveau chargement de chunk
 * ({@link #onEntitiesLoad}) — jamais un chargement forcé de tout le monde.</p>
 *
 * <p><strong>Construction (casse, pose, seaux, feu, décor)</strong> — jamais pour les dégâts aux
 * joueurs, qui n'ont pas de raison de viser un administrateur dans un monde de spawn paisible :
 * <ul>
 *   <li>un compte <strong>non OP</strong> portant un nœud builder explicite
 *       ({@code rpgquest.build.hub.<monde>} ou {@code rpgquest.build.hub.*}, #200) construit en
 *       permanence dans ce Hub ;</li>
 *   <li>tout autre droit — statut OP, ombrelle {@code rpgquest.admin.world} (défaut {@code op}),
 *       nœud builder détenu par un OP — ne compte que pendant une session
 *       {@code /rpgadmin bypass on} (#35, réouverture du 23/10 : un OP bypass OFF cassait un bloc
 *       du Hub, parce que l'ombrelle était acceptée en permanence). Pour un OP, Bukkit et
 *       LuckPerms ne distinguent pas un octroi explicite d'un défaut OP : il passe donc toujours
 *       par la session.</li>
 * </ul>
 * Le contrôle est refait à chaque événement : {@code /rpgadmin bypass off} refuse immédiatement,
 * sans reconnexion.</p>
 *
 * <p>Au-delà des blocs, les modifications du décor par un joueur suivent le même droit : poser,
 * casser ou frapper un cadre/tableau, tourner ou garnir un cadre, manipuler un porte-armure,
 * réécrire un panneau, piétiner de la terre labourée, fertiliser à la poudre d'os.</p>
 *
 * <p><strong>Salles de préparation (issue #107)</strong> : les établis et autres blocs utilitaires
 * vanilla du Hub restent <strong>utilisables</strong> (aucun clic droit n'est annulé ici) mais ne
 * peuvent être ni cassés, ni remplacés, ni brûlés. Vider ou remplir un seau compte comme poser ou
 * casser un bloc (même droit de construction) — sauf traire une vache ; le feu ne prend et ne brûle
 * rien, sauf un feu allumé par un constructeur du Hub (décor volontaire).</p>
 */
public final class HubWorldProtectionListener implements Listener {

    private static final String COMBAT_BYPASS_PERMISSION = "rpgquest.admin.hub.combat";

    private final Supplier<HubConfig> config;
    private final NpcIdentityService npcIdentityService;

    /** Issue #35 : seul chemin d'un contournement — session explicite + nœud dédié, jamais l'OP seul. */
    private final AdminBypassMode bypass;

    public HubWorldProtectionListener(Supplier<HubConfig> config, NpcIdentityService npcIdentityService,
                                      AdminBypassMode bypass) {
        this.config = config;
        this.npcIdentityService = npcIdentityService;
        this.bypass = bypass;
    }

    // ---- Dégâts aux joueurs (PvP inclus) --------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isHub(player.getWorld())) {
            event.setCancelled(true);
            return;
        }
        // ---- Cadres et tableaux : frapper un cadre en fait tomber l'objet (décor, #35) -----------
        if (event.getEntity() instanceof Hanging hanging) {
            Player actor = resolveAttacker(event);
            if (actor != null && isHub(hanging.getWorld()) && !isBypassingBuild(actor)) {
                event.setCancelled(true);
            }
            return;
        }
        // ---- Dégâts aux entités protégées (issues #30/#31) --------------------------------------
        if (!(event.getEntity() instanceof LivingEntity victim) || !isHub(victim.getWorld())) {
            return;
        }
        if (npcIdentityService.isCitizensNpc(victim)) {
            return; // Citizens gère sa propre invulnérabilité, jamais concerné ici.
        }
        // Dégât non causé par un joueur identifiable (environnement, autre mob...) : la protection
        // ne vise que les dégâts provoqués par un joueur — laisser passer.
        Player attacker = resolveAttacker(event);
        if (attacker != null && !bypass.allowsExact(attacker, COMBAT_BYPASS_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    /** Joueur à l'origine du dégât (mêlée directe ou projectile tiré par un joueur), sinon {@code null}. */
    private static Player resolveAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        return playerBehind(byEntity.getDamager());
    }

    /** Joueur derrière une entité : lui-même, ou le tireur d'un projectile ; sinon {@code null}. */
    static Player playerBehind(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- Blocs -----------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        if (isHub(event.getBlock().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        if (isHub(event.getBlockPlaced().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isHub(event.getBlock().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Ramasser de l'eau, de la lave ou de la neige poudreuse modifie le décor ; traire une vache, non. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (event.getItemStack() != null && event.getItemStack().getType() == Material.MILK_BUCKET) {
            return;
        }
        if (isHub(event.getBlock().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ---- Décor non-bloc : cadres, tableaux, porte-armures, panneaux, cultures (#35) ---------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (isHub(event.getEntity().getWorld()) && (player == null || !isBypassingBuild(player))) {
            event.setCancelled(true);
        }
    }

    /** Casser un cadre ou un tableau n'est ni un {@link BlockBreakEvent}, ni un dégât d'entité vivante. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player remover = playerBehind(event.getRemover());
        if (remover != null && isHub(event.getEntity().getWorld()) && !isBypassingBuild(remover)) {
            event.setCancelled(true);
        }
    }

    /** Tourner un cadre, y poser ou en retirer un objet. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame && isHub(frame.getWorld())
                && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isHub(event.getRightClicked().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onSignChange(SignChangeEvent event) {
        if (isHub(event.getBlock().getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Piétiner une terre labourée (PHYSICAL n'a pas de main : pas de filtre de main ici). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onTrample(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL && block != null && block.getType() == Material.FARMLAND
                && isHub(block.getWorld()) && !isBypassingBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onFertilize(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player != null && isHub(event.getBlock().getWorld()) && !isBypassingBuild(player)) {
            event.setCancelled(true);
        }
    }

    // ---- Feu (établis, bois du décor) ------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onIgnite(BlockIgniteEvent event) {
        if (!isHub(event.getBlock().getWorld())) {
            return;
        }
        Player igniter = event.getPlayer();
        if (igniter == null || !isBypassingBuild(igniter)) {
            event.setCancelled(true); // propagation, lave, foudre, ou joueur sans droit de construction.
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBurn(BlockBurnEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ---- Explosions (protection du terrain — les dégâts aux joueurs sont déjà couverts ci-dessus) ---

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isHub(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isHub(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    // ---- Spawns de mobs indésirables (issues #121/#155) -------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (isHub(event.getEntity().getWorld()) && isUnwanted(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nettoyage ciblé des entités déjà présentes dans un chunk qui vient de se charger (jamais un
     * chargement forcé de tout le monde) — couvre les entités persistées avant l'activation de
     * cette protection (ex. import Multiverse, génération antérieure).
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!isHub(event.getWorld())) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            removeIfUnwanted(entity);
        }
    }

    /** À appeler une fois au démarrage (chunks déjà chargés au moment où le plugin s'active, ex. spawn). */
    public void sweepAlreadyLoaded(World world) {
        if (!isHub(world)) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            removeIfUnwanted(entity);
        }
    }

    private void removeIfUnwanted(Entity entity) {
        if (isUnwanted(entity)) {
            entity.remove();
        }
    }

    private boolean isUnwanted(Entity entity) {
        if (npcIdentityService.isCitizensNpc(entity)) {
            return false; // jamais un PNJ Citizens ni une entité administrée explicitement.
        }
        if (entity instanceof Monster) {
            return true;
        }
        EntityType type = entity.getType();
        return type == EntityType.ENDERMAN || type == EntityType.WANDERING_TRADER || type == EntityType.TRADER_LLAMA;
    }

    // ---- Utilitaires -------------------------------------------------------------------------------

    private boolean isHub(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    /**
     * Construction autorisée dans CE Hub, <em>à cet instant</em> (issues #200/#35) : nœud builder
     * explicite d'un compte non OP, ou session {@code /rpgadmin bypass on} avec un droit de
     * construction (nœud builder ou ombrelle historique). L'identifiant de Hub vient de la
     * configuration réelle ({@code hub.world}), jamais d'une constante en dur.
     */
    private boolean isBypassingBuild(Player player) {
        String hubWorld = config.get().world();
        if (bypass.isActive(player)) {
            return RpgPermissions.canBuildInHub(player, hubWorld);
        }
        return !player.isOp() && RpgPermissions.hasHubBuilderNode(player, hubWorld);
    }
}
