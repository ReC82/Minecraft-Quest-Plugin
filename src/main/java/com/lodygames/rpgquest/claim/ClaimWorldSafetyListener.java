package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.config.ClaimConfig;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.permission.RpgPermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

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
 *   <li><strong>joueur non éligible</strong> et sans le mode bypass administrateur actif (#35) : il est
 *       immédiatement renvoyé au Hub avec un message — jamais laissé sur place.</li>
 *   <li><strong>en mode bypass administrateur</strong> (#35, nœud {@code rpgquest.bypass.claimworld} ou ombrelle) : <strong>jamais</strong>
 *       téléporté de force — il est venu volontairement — mais il reçoit tout de même une Pierre de
 *       retour. Le bypass dispensait auparavant des deux, ce qui laissait un administrateur éligible
 *       sans aucune sortie (issue #22, blocage signalé le 05/10).</li>
 * </ul>
 *
 * <p><strong>Défaillances bornées (issue #829).</strong> Si la lecture de {@code CLAIM_TIER_1}
 * échoue, elle est retentée {@link #DEFAULT_RETRY_DELAYS_TICKS} fois (5 s puis 15 s) ; si elle
 * échoue encore, le joueur est ramené au Hub <em>par précaution</em> (même choix que le portail,
 * qui refuse l'entrée quand la base ne répond pas) — jamais laissé sur place sans vérification. Si
 * aucun point de retour au Hub ne se résout (ou si la téléportation est refusée), le renvoi est
 * retenté avec les mêmes délais, puis l'échec est journalisé en {@code ERROR} et signalé aux
 * administrateurs en ligne. Aucune boucle : chaque arrivée lance une seule chaîne d'essais, annulée
 * dès que le joueur quitte le monde, se déconnecte ou arrive à nouveau.</p>
 *
 * <p><strong>Téléportations internes (issue #829).</strong> Un {@code /tp} (commande, plugin,
 * spectateur) qui reste dans le monde des claims ne change pas de monde : il repasse donc ici par
 * le même contrôle d'éligibilité. Être présent dans le monde n'accorde <strong>aucun</strong> droit
 * sur les claims d'autrui — la protection des blocs reste celle de {@link ClaimProtectionListener}
 * ({@code Claim#isTrusted}).</p>
 *
 * <p><strong>Téléportation directe dans le claim d'autrui (issue #829, décision D2 du 25/10).</strong>
 * Une téléportation par commande, plugin ou spectateur ({@link #REFUSED_DIRECT_CAUSES}), depuis
 * n'importe quel monde, dont la destination est à l'intérieur d'un claim que {@link ClaimVisitPolicy}
 * n'ouvre pas au joueur téléporté est <strong>annulée</strong> avec un message — l'OP ou la seule
 * éligibilité au monde ne suffisent pas ; seul le mode bypass explicite (#35, nœud {@code
 * rpgquest.bypass.claimworld}) fait exception. La présence et l'exploration ne sont pas concernées :
 * marcher jusqu'au claim, une perle de l'Ender ou un {@code /tp} à l'intérieur d'un claim où le joueur
 * se trouve déjà restent permis. La cause {@code UNKNOWN} n'est pas refusée (origine non
 * identifiable) : seulement tracée, comme toute arrivée permise dans le claim d'autrui.</p>
 *
 * <p>Même patron que {@code player.StarterKitListener} (don unique d'un objet de secours à la
 * connexion) et {@link ClaimsWorldRulesListener} (règles attachées au monde des claims).</p>
 */
public final class ClaimWorldSafetyListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Issue #829 : délais (ticks) des nouveaux essais après un échec — 5 s puis 15 s, puis on renonce. */
    static final long[] DEFAULT_RETRY_DELAYS_TICKS = {100L, 300L};
    /** Téléportations internes au monde des claims qui repassent par le contrôle (issue #829). */
    private static final Set<PlayerTeleportEvent.TeleportCause> CHECKED_INTERNAL_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.SPECTATE, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    /** Décision D2 (issue #829) : téléportations directes refusées vers le claim d'autrui. */
    static final Set<PlayerTeleportEvent.TeleportCause> REFUSED_DIRECT_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.SPECTATE);

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final ClaimReturnService returnService;
    private final Supplier<ClaimConfig> config;
    /** Cible de repli au Hub (spawn du village configuré, sinon spawn du monde Hub) — jamais une position figée. */
    private final Supplier<Optional<Location>> hubReturnTarget;
    private final Function<UUID, CompletableFuture<Boolean>> tierOneLookup;
    private final long[] retryDelaysTicks;
    private final ClaimVisitPolicy visitPolicy;

    /** Issue #35 : seul chemin d'un contournement — session explicite + nœud dédié, jamais l'OP seul. */
    private final AdminBypassMode bypass;

    /** Issue #829 : jeton de la dernière arrivée par joueur — une chaîne d'essais périmée s'arrête d'elle-même. */
    private final Map<UUID, Long> arrivalTokens = new ConcurrentHashMap<>();
    private final AtomicLong tokenSequence = new AtomicLong();

    public ClaimWorldSafetyListener(RPGQuestPlugin plugin, ClaimService claimService,
                                     ClaimReturnService returnService, Supplier<ClaimConfig> config,
                                     Supplier<Optional<Location>> hubReturnTarget, AdminBypassMode bypass) {
        this(plugin, claimService, returnService, config, hubReturnTarget, bypass,
                claimService::hasClaimTierOne, DEFAULT_RETRY_DELAYS_TICKS, ClaimVisitPolicy.MEMBERS_ONLY);
    }

    /** Variante de test : lecture d'éligibilité et délais de nouvel essai injectables. */
    ClaimWorldSafetyListener(RPGQuestPlugin plugin, ClaimService claimService,
                             ClaimReturnService returnService, Supplier<ClaimConfig> config,
                             Supplier<Optional<Location>> hubReturnTarget, AdminBypassMode bypass,
                             Function<UUID, CompletableFuture<Boolean>> tierOneLookup, long[] retryDelaysTicks) {
        this(plugin, claimService, returnService, config, hubReturnTarget, bypass, tierOneLookup, retryDelaysTicks,
                ClaimVisitPolicy.MEMBERS_ONLY);
    }

    /** Variante complète : politique de visite injectable (futur système d'amis, tests). */
    ClaimWorldSafetyListener(RPGQuestPlugin plugin, ClaimService claimService,
                             ClaimReturnService returnService, Supplier<ClaimConfig> config,
                             Supplier<Optional<Location>> hubReturnTarget, AdminBypassMode bypass,
                             Function<UUID, CompletableFuture<Boolean>> tierOneLookup, long[] retryDelaysTicks,
                             ClaimVisitPolicy visitPolicy) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.returnService = returnService;
        this.config = config;
        this.hubReturnTarget = hubReturnTarget;
        this.bypass = bypass;
        this.tierOneLookup = tierOneLookup;
        this.retryDelaysTicks = retryDelaysTicks.clone();
        this.visitPolicy = visitPolicy;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        arrivalTokens.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Issue #829, décision D2 : annule une téléportation directe ({@link #REFUSED_DIRECT_CAUSES}) vers
     * l'intérieur d'un claim que {@link ClaimVisitPolicy} n'ouvre pas au joueur téléporté. Priorité
     * {@code HIGH} : les observateurs {@code MONITOR} (filet d'arrivée, traces) voient l'annulation.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDirectTeleportIntoClaim(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !REFUSED_DIRECT_CAUSES.contains(event.getCause())) {
            return;
        }
        Player player = event.getPlayer();
        Optional<Claim> target = claimAt(to).filter(claim -> !visitPolicy.allowsDirectTeleport(player.getUniqueId(), claim));
        if (target.isEmpty() || target.equals(claimAt(event.getFrom()))) {
            return; // hors claim, claim ouvert au joueur, ou déplacement à l'intérieur du claim où il se trouve déjà.
        }
        Claim claim = target.get();
        if (bypass.allows(player, RpgPermissions.BYPASS_CLAIM_WORLD)) {
            plugin.getSLF4JLogger().info("[claims-safety] {} téléporté ({}) dans le claim « {} » de {} : permis par le "
                    + "mode bypass administrateur.", player.getName(), event.getCause(), claim.id(), claim.owner());
            return;
        }
        event.setCancelled(true);
        plugin.getSLF4JLogger().info("[claims-safety] téléportation ({}) de {} vers le claim « {} » de {} REFUSÉE : "
                + "ni propriétaire ni membre, aucun bypass actif.", event.getCause(), player.getName(), claim.id(), claim.owner());
        player.sendMessage(MM.deserialize(
                "<red>Téléportation refusée : cette destination se trouve dans le terrain d'un autre joueur.</red>"));
        player.sendMessage(MM.deserialize(
                "<gray>Tu peux t'y rendre à pied, mais pas t'y téléporter directement sans y être invité.</gray>"));
    }

    private Optional<Claim> claimAt(Location location) {
        World world = location == null ? null : location.getWorld();
        return world == null ? Optional.empty()
                : claimService.claimAt(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Issue #829 : un {@code /tp} qui reste dans le monde des claims ne déclenche aucun
     * {@link PlayerChangedWorldEvent} — on rejoue donc le contrôle d'arrivée au tick suivant (une
     * fois la position appliquée). Les déplacements de gameplay (perle, fruit de chorus, lit…) ne
     * sont pas concernés.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportWithinClaimsWorld(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())
                || !isClaimsWorld(to.getWorld()) || !CHECKED_INTERNAL_CAUSES.contains(event.getCause())) {
            return;
        }
        Player player = event.getPlayer();
        traceForeignClaimArrival(player, to, event.getCause());
        runOnMainThread(() -> {
            if (player.isOnline() && isClaimsWorld(player.getWorld())) {
                handleArrival(player);
            }
        });
    }

    /**
     * Trace seulement une arrivée permise (bypass, cause {@code UNKNOWN}, claim où il se trouvait déjà) : la
     * présence n'accorde aucun droit, {@link ClaimProtectionListener} protège les blocs.
     */
    private void traceForeignClaimArrival(Player player, Location to, PlayerTeleportEvent.TeleportCause cause) {
        claimAt(to)
                .filter(claim -> !claim.isTrusted(player.getUniqueId()))
                .ifPresent(claim -> plugin.getSLF4JLogger().info(
                        "[claims-safety] {} téléporté ({}) dans le claim « {} » de {} sans en être membre : "
                                + "présence seulement, aucun droit de construction (protection du claim inchangée).",
                        player.getName(), cause, claim.id(), claim.owner()));
    }

    private void handleArrival(Player player) {
        UUID playerId = player.getUniqueId();
        long token = tokenSequence.incrementAndGet();
        arrivalTokens.put(playerId, token);
        // Issue #200 : même règle que le garde d'accès — nœud dédié OU ombrelle historique.
        // Le correctif #22 est préservé : un porteur du bypass reçoit malgré tout sa Pierre.
        if (bypass.allows(player, RpgPermissions.BYPASS_CLAIM_WORLD)) {
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
        verifyEligibility(player, token, 0);
    }

    /** Lecture de {@code CLAIM_TIER_1}, retentée au plus {@code retryDelaysTicks.length} fois (issue #829). */
    private void verifyEligibility(Player player, long token, int attempt) {
        UUID playerId = player.getUniqueId();
        tierOneLookup.apply(playerId).whenComplete((unlocked, error) -> runOnMainThread(() -> {
            if (!isCurrent(player, token)) {
                return;
            }
            if (error != null) {
                ensureReturnStone(player); // au pire, on garantit le moyen de repartir pendant l'attente.
                if (attempt < retryDelaysTicks.length) {
                    plugin.getSLF4JLogger().warn(
                            "[claims-safety] contrôle d'éligibilité impossible pour {} (essai {}/{}) — nouvel essai dans {} ticks.",
                            player.getName(), attempt + 1, retryDelaysTicks.length + 1, retryDelaysTicks[attempt], error);
                    later(retryDelaysTicks[attempt], player, token, () -> verifyEligibility(player, token, attempt + 1));
                    return;
                }
                plugin.getSLF4JLogger().error(
                        "[claims-safety] contrôle d'éligibilité impossible pour {} après {} essais : renvoi au Hub par "
                                + "précaution (accès non vérifié).", player.getName(), attempt + 1, error);
                sendBackToHub(player, token, 0, false);
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
                sendBackToHub(player, token, 0, true);
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

    /**
     * Renvoie le joueur au Hub. Un point de retour introuvable (ou situé dans le monde des claims) ou
     * une téléportation refusée sont retentés avec les mêmes délais bornés, puis signalés (issue #829).
     *
     * @param verified {@code true} : non-éligibilité constatée ; {@code false} : accès non vérifiable.
     */
    private void sendBackToHub(Player player, long token, int attempt, boolean verified) {
        Optional<Location> target = hubReturnTarget.get().filter(location -> !isClaimsWorld(location.getWorld()));
        if (target.isPresent() && player.teleport(target.get())) {
            if (verified) {
                player.sendMessage(MM.deserialize(
                        "<red>Le monde des claims est réservé aux joueurs qui ont débloqué leur premier terrain.</red>"));
                player.sendMessage(MM.deserialize(
                        "<gray>Tu es ramené au village. Termine l'histoire principale puis parle à <white>Jo</white> pour obtenir ton acte de propriété.</gray>"));
            } else {
                player.sendMessage(MM.deserialize(
                        "<red>Impossible de vérifier ton accès au monde des claims pour le moment.</red>"));
                player.sendMessage(MM.deserialize(
                        "<gray>Tu es ramené au village par précaution. Réessaie dans un instant ; si cela dure, "
                                + "signale-le à un administrateur.</gray>"));
            }
            return;
        }
        ensureReturnStone(player); // le moyen de repartir reste garanti pendant l'attente.
        if (attempt < retryDelaysTicks.length) {
            plugin.getSLF4JLogger().warn(
                    "[claims-safety] renvoi au Hub de {} impossible (essai {}/{} : {}) — nouvel essai dans {} ticks.",
                    player.getName(), attempt + 1, retryDelaysTicks.length + 1,
                    target.isEmpty() ? "aucun point de retour résolu" : "téléportation refusée", retryDelaysTicks[attempt]);
            later(retryDelaysTicks[attempt], player, token, () -> sendBackToHub(player, token, attempt + 1, verified));
            return;
        }
        plugin.getSLF4JLogger().error(
                "[claims-safety] ÉCHEC du renvoi au Hub de {} après {} essais : le joueur reste dans « {} » avec une "
                        + "Pierre de retour. Vérifier le spawn du village et le monde Hub.",
                player.getName(), attempt + 1, player.getWorld().getName());
        player.sendMessage(MM.deserialize("<red>Le retour au village est indisponible pour le moment.</red>"));
        player.sendMessage(MM.deserialize("<gray>Un administrateur a été prévenu.</gray>"));
        plugin.getServer().broadcast(MM.deserialize(
                "<red>[RPGQuest] Renvoi au Hub impossible pour <white><name></white> (monde des claims) : "
                        + "vérifier le spawn du village.</red>",
                Placeholder.unparsed("name", player.getName())),
                RpgPermissions.LEGACY_ADMIN_WORLD);
    }

    /** Planifie {@code task} si, à l'échéance, cette arrivée est toujours la dernière du joueur. */
    private void later(long delayTicks, Player player, long token, Runnable task) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isCurrent(player, token)) {
                task.run();
            }
        }, delayTicks);
    }

    private boolean isCurrent(Player player, long token) {
        return player.isOnline() && isClaimsWorld(player.getWorld())
                && Long.valueOf(token).equals(arrivalTokens.get(player.getUniqueId()));
    }

    private boolean isClaimsWorld(World world) {
        return world != null && world.getName().equals(config.get().world());
    }

    private void runOnMainThread(Runnable task) {
        // Toujours re-planifié sur un tick — même patron que ClaimService/DeedClaimListener.
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
