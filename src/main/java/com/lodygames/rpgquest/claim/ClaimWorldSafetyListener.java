package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Filet de sécurité du monde des claims (issues #21/#22/#23) : garantit qu'<strong>aucun joueur ne
 * reste jamais coincé</strong> dans {@link ClaimConfig#world()} et qu'un retour au Hub y est
 * toujours possible <strong>sans commande</strong>. Complète {@link ClaimWorldAccessGuard} (qui
 * bloque l'<em>entrée</em> par portail) en couvrant les autres façons d'y arriver — {@code /tp}
 * d'un administrateur, reconnexion d'un joueur déconnecté dans ce monde, ou joueur déjà présent
 * avant l'ajout du contrôle d'accès.
 *
 * <p>À chaque arrivée dans le monde des claims ({@link PlayerChangedWorldEvent}) et à chaque
 * connexion qui s'y fait ({@link PlayerJoinEvent}) :</p>
 * <ul>
 *   <li><strong>joueur éligible</strong> (possède déjà un claim, ou {@code CLAIM_TIER_1} débloqué) :
 *       on s'assure qu'il détient une <em>Pierre de retour</em> ({@link RpgItemKeys#PIERRE_RETOUR}) —
 *       objet de voyage claims → Hub géré par {@code travel.ItemTravelService}, permanent, jamais
 *       consommé, clic droit sans commande. Idempotent (jamais de second exemplaire) ;</li>
 *   <li><strong>joueur non éligible</strong> et sans le bypass {@code rpgquest.admin.world} : il est
 *       immédiatement renvoyé au Hub avec un message — jamais laissé sur place.</li>
 *   <li><strong>porteur du bypass</strong> {@code rpgquest.admin.world} : <strong>jamais</strong>
 *       téléporté de force — il est venu volontairement — mais il reçoit tout de même une Pierre de
 *       retour. Le bypass dispensait auparavant des deux, ce qui laissait un administrateur éligible
 *       sans aucune sortie (issue #22, blocage signalé le 05/10).</li>
 * </ul>
 *
 * <p>Même patron que {@code player.StarterKitListener} (don unique d'un objet de secours à la
 * connexion) et {@link ClaimsWorldRulesListener} (règles attachées au monde des claims).</p>
 */
public final class ClaimWorldSafetyListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final ClaimReturnService returnService;
    private final Supplier<ClaimConfig> config;
    /** Cible de repli au Hub (spawn du village configuré, sinon spawn du monde Hub) — jamais une position figée. */
    private final Supplier<Optional<Location>> hubReturnTarget;

    public ClaimWorldSafetyListener(RPGQuestPlugin plugin, ClaimService claimService,
                                     ClaimReturnService returnService, Supplier<ClaimConfig> config,
                                     Supplier<Optional<Location>> hubReturnTarget) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.returnService = returnService;
        this.config = config;
        this.hubReturnTarget = hubReturnTarget;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isClaimsWorld(player.getWorld())) {
            handleArrival(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isClaimsWorld(player.getWorld())) {
            handleArrival(player);
        }
    }

    private void handleArrival(Player player) {
        UUID playerId = player.getUniqueId();
        // Issue #200 : même règle que le garde d'accès — nœud dédié OU ombrelle historique.
        // Le correctif #22 est préservé : un porteur du bypass reçoit malgré tout sa Pierre.
        if (RpgPermissions.hasBypass(player, RpgPermissions.BYPASS_CLAIM_WORLD)) {
            // Issue #22, cause racine du blocage signalé le 05/10. Le bypass dispensait de TOUT :
            // ni renvoi au Hub, ni Pierre de retour. Un administrateur éligible, arrivé ici par
            // portail, se retrouvait donc sans aucune sortie — exactement ce que ce filet existe
            // pour empêcher, et ce que l'ancien commentaire décrivait déjà comme « la cause la
            // plus fréquente ».
            //
            // Le bypass garde son seul sens défendable : ne JAMAIS téléporter de force un
            // administrateur hors d'un monde où il est venu volontairement. Mais il ne doit pas le
            // priver du moyen d'en repartir. Une Pierre de retour est donc garantie ici aussi.
            plugin.getSLF4JLogger().info(
                    "[claims-safety] {} présent dans « {} » avec le bypass {} : aucun renvoi forcé, "
                            + "mais Pierre de retour garantie.",
                    player.getName(), player.getWorld().getName(), ClaimWorldAccessGuard.BYPASS_PERMISSION);
            ensureReturnStone(player);
            return;
        }
        if (claimService.mainClaimOf(playerId).isPresent()) {
            plugin.getSLF4JLogger().info(
                    "[claims-safety] {} (propriétaire d'un claim) dans « {} » : garantie d'une Pierre de retour.",
                    player.getName(), player.getWorld().getName());
            ensureReturnStone(player);
            return;
        }
        claimService.hasClaimTierOne(playerId).whenComplete((unlocked, error) -> runOnMainThread(() -> {
            if (!player.isOnline() || !isClaimsWorld(player.getWorld())) {
                return;
            }
            if (error != null) {
                plugin.getSLF4JLogger().error("Filet de sécurité du monde des claims : contrôle impossible pour {}", playerId, error);
                ensureReturnStone(player); // au pire, on garantit le moyen de repartir.
                return;
            }
            if (Boolean.TRUE.equals(unlocked) || claimService.mainClaimOf(playerId).isPresent()) {
                plugin.getSLF4JLogger().info(
                        "[claims-safety] {} éligible dans « {} » (CLAIM_TIER_1 débloqué) : garantie d'une Pierre de retour.",
                        player.getName(), player.getWorld().getName());
                ensureReturnStone(player);
            } else {
                plugin.getSLF4JLogger().info(
                        "[claims-safety] {} NON éligible dans « {} » : renvoi au Hub.",
                        player.getName(), player.getWorld().getName());
                sendBackToHub(player);
            }
        }));
    }

    /**
     * Garantit que le joueur détient une Pierre de retour, via l'unique
     * {@link ClaimReturnService}. Le joueur est <strong>déjà</strong> dans le monde des claims :
     * l'objet est donc déposé à ses pieds si son inventaire est plein — mieux vaut cela que rien.
     */
    private void ensureReturnStone(Player player) {
        returnService.ensureReturnStone(player, true);
    }

    private void sendBackToHub(Player player) {
        Optional<Location> target = hubReturnTarget.get();
        if (target.isEmpty()) {
            plugin.getSLF4JLogger().warn(
                    "Filet de sécurité du monde des claims : aucun point de retour au Hub résolu pour {} — "
                            + "don d'une Pierre de retour à la place.", player.getName());
            ensureReturnStone(player);
            return;
        }
        player.teleport(target.get());
        player.sendMessage(MM.deserialize(
                "<red>Le monde des claims est réservé aux joueurs qui ont débloqué leur premier terrain.</red>"));
        player.sendMessage(MM.deserialize(
                "<gray>Tu es ramené au village. Termine l'histoire principale puis parle à <white>Jo</white> pour obtenir ton acte de propriété.</gray>"));
    }

    private boolean isClaimsWorld(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private void runOnMainThread(Runnable task) {
        // Toujours re-planifié sur un tick — même patron que ClaimService/DeedClaimListener.
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
