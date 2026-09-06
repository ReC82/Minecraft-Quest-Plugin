package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.util.Arrays;
import java.util.Objects;
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
import org.bukkit.inventory.ItemStack;

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
 * </ul>
 *
 * <p>Même patron que {@code player.StarterKitListener} (don unique d'un objet de secours à la
 * connexion) et {@link ClaimsWorldRulesListener} (règles attachées au monde des claims).</p>
 */
public final class ClaimWorldSafetyListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final Supplier<ClaimConfig> config;
    /** Cible de repli au Hub (spawn du village configuré, sinon spawn du monde Hub) — jamais une position figée. */
    private final Supplier<Optional<Location>> hubReturnTarget;

    public ClaimWorldSafetyListener(RPGQuestPlugin plugin, ClaimService claimService,
                                     YamlCustomItemRegistry customItemRegistry, Supplier<ClaimConfig> config,
                                     Supplier<Optional<Location>> hubReturnTarget) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.customItemRegistry = customItemRegistry;
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
        if (player.hasPermission(ClaimWorldAccessGuard.BYPASS_PERMISSION)) {
            return; // bypass explicite : ni renvoi, ni objet imposé dans l'inventaire.
        }
        if (claimService.mainClaimOf(playerId).isPresent()) {
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
                ensureReturnStone(player);
            } else {
                sendBackToHub(player);
            }
        }));
    }

    private void ensureReturnStone(Player player) {
        if (hasReturnStone(player)) {
            return;
        }
        customItemRegistry.create(RpgItemKeys.PIERRE_RETOUR, 1).ifPresent(stack -> {
            player.getInventory().addItem(stack).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            player.sendMessage(MM.deserialize(
                    "<aqua>Tu reçois une Pierre de retour.</aqua> <gray>Clic droit avec pour revenir au village depuis le monde des claims.</gray>"));
        });
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

    private boolean hasReturnStone(Player player) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(stack -> customItemRegistry.identify(stack).map(RpgItemKeys.PIERRE_RETOUR::equals).orElse(false));
    }

    private boolean isClaimsWorld(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private void runOnMainThread(Runnable task) {
        // Toujours re-planifié sur un tick — même patron que ClaimService/DeedClaimListener.
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
