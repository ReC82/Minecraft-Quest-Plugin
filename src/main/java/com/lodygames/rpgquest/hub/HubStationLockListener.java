package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.progression.RequiredLevelRule;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.slf4j.Logger;

/**
 * Issue #675 (retour du 22/10) — dans le monde {@link HubConfig#world()}, les postes de travail vanilla
 * ({@link HubStation}) sont <strong>fermés par défaut</strong> ; une exception {@code
 * hub.stations.exceptions} ({@link HubStationException}) les rouvre, par type et/ou bloc précis, à qui
 * remplit ses conditions. Les blocs qui rendent une ressource au clic ({@link HubStation#RESOURCE_BLOCKS})
 * sont fermés sans exception, et le livre d'un lutrin se lit mais ne se prend pas.
 *
 * <p>Seule l'utilisation du bloc est refusée ({@link PlayerInteractEvent#setUseInteractedBlock}) :
 * l'objet en main reste utilisable, aucun bloc ni contenu n'est modifié, et la règle évaluée à chaque
 * clic tient après un redémarrage comme après un {@code /rpgadmin reload}.</p>
 *
 * <p><strong>Conditions</strong> : niveau RPG ({@link RequiredLevelRule}, #532) et conditions joueur du
 * {@code PlayerConditionEvaluator} partagé avec les dialogues et les portails (#567). Si le verdict est
 * connu tout de suite (permission, objet, niveau, quête active en mémoire), le clic vanilla suit son
 * cours. Sinon (état de quête ou variable lus en base) le clic est retenu, puis le poste est ouvert par
 * le plugin, lié au bloc, si le joueur est toujours là et n'a rien ouvert entre-temps. Une erreur de
 * lecture refuse (fermé par défaut).</p>
 *
 * <p>Restent hors de ce listener, donc libres : PNJ Citizens et menus RPGQuest (boutiques, ateliers
 * d'artisan #18, coffres personnels #104/#105 — {@code storage.VaultAccessListener} annule le clic
 * avant, en {@code LOWEST}), coffre de l'Ender. Wild et Claims ne sont pas concernés. Contournement :
 * {@value HubResourceLockListener#BYPASS_PERMISSION} pendant {@code /rpgadmin bypass on} (#35).</p>
 */
public final class HubStationLockListener implements Listener {

    static final String STATION_REFUSED = "<gray>Les postes de travail du Hub sont fermés.</gray>";
    static final String EXCEPTION_REFUSED = "<gray>Ce poste du Hub ne t'est pas encore ouvert.</gray>";
    static final String RESOURCE_REFUSED = "<gray>Ce bloc du Hub n'est pas utilisable.</gray>";
    static final String LECTERN_REFUSED = "<gray>Ce livre reste sur son lutrin.</gray>";
    /** Portée de rouverture d'un poste après une vérification asynchrone, en blocs. */
    static final double REOPEN_REACH = 6.0;

    /** Conditions joueur, toutes exigées — {@code PlayerConditionEvaluator#evaluateAll} en production. */
    @FunctionalInterface
    public interface Conditions {

        CompletableFuture<Boolean> allPass(Player player, List<DialogueCondition> conditions);
    }

    private final Supplier<HubConfig> config;
    private final AdminBypassMode bypass;
    private final Conditions conditions;
    private final Executor mainThread;
    private final HubRefusalMessages messages;
    private final Logger logger;
    private final BiPredicate<Player, Block> opener;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile RequiredLevelRule levelRule = RequiredLevelRule.VANILLA;

    public HubStationLockListener(Supplier<HubConfig> config, AdminBypassMode bypass, Conditions conditions,
                                  Executor mainThread, HubRefusalMessages messages, Logger logger) {
        this(config, bypass, conditions, mainThread, messages, logger,
                (player, block) -> HubStation.of(block.getType()).map(s -> s.open(player, block)).orElse(false));
    }

    HubStationLockListener(Supplier<HubConfig> config, AdminBypassMode bypass, Conditions conditions,
                           Executor mainThread, HubRefusalMessages messages, Logger logger,
                           BiPredicate<Player, Block> opener) {
        this.config = config;
        this.bypass = bypass;
        this.conditions = conditions;
        this.mainThread = mainThread;
        this.messages = messages;
        this.logger = logger;
        this.opener = opener;
    }

    /** Règle partagée du niveau (#532), câblée par le bootstrap comme pour les portails. */
    public void setRequiredLevelRule(RequiredLevelRule rule) {
        this.levelRule = Objects.requireNonNull(rule, "rule");
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null) {
            return;
        }
        boolean resource = HubStation.RESOURCE_BLOCKS.contains(block.getType());
        Optional<HubStation> station = resource ? Optional.empty() : HubStation.of(block.getType());
        if ((!resource && station.isEmpty()) || !isHub(block.getWorld()) || isBypassing(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        // Les deux mains déclenchent l'évènement : la main principale décide et parle, l'autre suit.
        boolean mainHand = event.getHand() == EquipmentSlot.HAND;
        if (resource) {
            refuse(event, player, mainHand ? RESOURCE_REFUSED : null);
            return;
        }
        List<HubStationException> matching = config.get().stationExceptions().stream()
                .filter(e -> e.appliesTo(station.get(), block)).toList();
        if (matching.isEmpty()) {
            refuse(event, player, mainHand ? STATION_REFUSED : null);
            return;
        }
        if (matching.stream().anyMatch(HubStationException::unconditional)) {
            return;
        }
        if (!mainHand) {
            event.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        decide(event, player, block, matching);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (isHub(event.getLectern().getWorld()) && !isBypassing(event.getPlayer())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), LECTERN_REFUSED);
        }
    }

    private void decide(PlayerInteractEvent event, Player player, Block block, List<HubStationException> matching) {
        String refusal = refusalOf(matching);
        CompletableFuture<Boolean> verdict = anyPasses(player, matching);
        if (verdict.isDone() && !verdict.isCompletedExceptionally()) {
            if (!verdict.join()) {
                refuse(event, player, refusal);
            }
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        UUID playerId = player.getUniqueId();
        if (!pending.add(playerId)) {
            return;
        }
        verdict.whenComplete((ok, error) -> mainThread.execute(() -> {
            pending.remove(playerId);
            if (error != null) {
                logger.warn("Exception de poste du Hub illisible pour {} : accès refusé.", player.getName(), error);
            }
            if (!player.isOnline()) {
                return;
            }
            if (error != null || !Boolean.TRUE.equals(ok)) {
                messages.send(player, refusal);
            } else if (canStillOpen(player, block)) {
                opener.test(player, block);
            }
        }));
    }

    /** Une exception suffit ; chacune exige son niveau puis toutes ses conditions (évaluées ensemble). */
    private CompletableFuture<Boolean> anyPasses(Player player, List<HubStationException> matching) {
        List<CompletableFuture<Boolean>> checks = matching.stream().map(e -> passes(player, e)).toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(v -> checks.stream().anyMatch(CompletableFuture::join));
    }

    private CompletableFuture<Boolean> passes(Player player, HubStationException exception) {
        if (!levelRule.meets(player, exception.requiredLevel())) {
            return CompletableFuture.completedFuture(false);
        }
        return exception.conditions().isEmpty() ? CompletableFuture.completedFuture(true)
                : conditions.allPass(player, exception.conditions());
    }

    /** Toujours au même endroit, à portée, sans autre menu ouvert entre-temps. */
    private static boolean canStillOpen(Player player, Block block) {
        return player.getWorld().equals(block.getWorld())
                && player.getLocation().distanceSquared(block.getLocation().toCenterLocation())
                        <= REOPEN_REACH * REOPEN_REACH
                && isNothingOpen(player);
    }

    /** Sans menu ouvert, le haut de la vue est l'inventaire de craft 2×2 du joueur. */
    private static boolean isNothingOpen(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top == null || top.getType() == InventoryType.CRAFTING;
    }

    private static String refusalOf(List<HubStationException> matching) {
        return matching.stream().map(HubStationException::message).filter(m -> !m.isEmpty()).findFirst()
                .orElse(EXCEPTION_REFUSED);
    }

    private void refuse(PlayerInteractEvent event, Player player, String message) {
        event.setUseInteractedBlock(Event.Result.DENY);
        if (message != null) {
            messages.send(player, message);
        }
    }

    private boolean isHub(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private boolean isBypassing(Player player) {
        return bypass.allowsExact(player, HubResourceLockListener.BYPASS_PERMISSION);
    }
}
