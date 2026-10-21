package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.world.WorldService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.slf4j.Logger;

/**
 * Détecte l'entrée d'un joueur dans un {@link WorldPortalDefinition} et le téléporte immédiatement
 * au spawn <em>actuel</em> du monde destination (résolu à chaque activation via {@code
 * WorldService#find}, jamais mémorisé) — aucune canalisation, aucun coût, contrairement à {@code
 * PortalService}.
 *
 * <p>Anti-boucle : même patron que {@code ZoneProtectionListener#onMove}/{@code
 * PortalService#handleMove} — un {@code Map<UUID, String>} retient le dernier portail où se
 * trouvait chaque joueur ; l'activation ne se déclenche que sur une vraie transition
 * extérieur → intérieur (ou d'un portail vers un autre), jamais tant qu'il reste dans le même
 * portail à chaque micro-mouvement, et jamais une seconde fois de suite si la téléportation a
 * échoué sans qu'il ait bougé entre-temps.</p>
 *
 * <p><strong>Répit d'arrivée</strong> (bug constaté en test réel) : cette anti-boucle ne protège
 * que contre le <em>même</em> portail répété ; elle ne protège pas un joueur qui vient d'être
 * placé quelque part par autre chose qu'un pas volontaire (connexion, {@code /tp} d'un
 * administrateur, ou l'atterrissage d'un <em>autre</em> portail) et dont la position se trouve
 * être à l'intérieur d'une zone d'activation — dans ce cas, le tout premier {@code
 * PlayerMoveEvent} de règlement (chute, micro-mouvement client) déclenchait une téléportation
 * silencieuse et immédiate, avec l'illusion d'un état persistant par joueur puisque ça se
 * reproduit à chaque connexion tant qu'il revient à cette position. {@link #arrivalGrace} accorde
 * {@value #ARRIVAL_GRACE_TICKS} ticks d'immunité après {@link PlayerJoinEvent}/{@link
 * PlayerTeleportEvent} (donc après une connexion, un {@code /tp} externe, ou l'atterrissage d'un
 * autre portail) avant qu'un portail simple puisse se déclencher automatiquement — le temps
 * qu'un joueur (ou un administrateur) remarque et s'écarte d'une zone mal placée, sans désactiver
 * l'usage volontaire du portail au-delà de ce court répit.</p>
 *
 * <p><strong>Limite connue de ce répit, découverte en investiguant plus loin (voir le rapport de
 * la session « diagnostic WorldPortal »)</strong> : il ne corrige pas une zone réellement mal
 * positionnée qui engloberait un point d'arrivée du Hub — il ne fait que <em>retarder</em> le
 * déclenchement de {@value #ARRIVAL_GRACE_TICKS} ticks (2 s), ce qui correspond exactement au délai
 * « après 1-2 secondes » rapporté depuis VeryGames. {@code onMove} ne remet jamais à jour {@link
 * #currentPortalByPlayer} pendant le répit (retour anticipé avant toute lecture du registre) ; si
 * la zone couvre réellement la position d'arrivée, le tout premier {@code PlayerMoveEvent} de
 * règlement après l'expiration du répit voit donc {@code previousPortalId == null} (jamais
 * enregistré) et déclenche une téléportation « neuve », immédiate. Ce n'est <strong>pas encore
 * corrigé ici</strong> (mission : outils de diagnostic d'abord) — voir {@code TpTraceLogger}
 * (champ {@code grace=}) et {@code /rpgadmin worldportal here}/{@code debug} pour confirmer ou
 * infirmer cette hypothèse sur le serveur réel avant tout correctif.</p>
 */
public final class WorldPortalTeleportListener implements Listener, PortalTeleporter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long ARRIVAL_GRACE_TICKS = 40L; // 2 s.
    public static final String FIXED_POINT_UNSAFE = "<red>L'arrivée de ce portail n'est pas sûre en ce moment : "
            + "téléportation annulée, vous restez ici. Prévenez un administrateur.</red>";
    static final String FIXED_POINT_UNAVAILABLE = "<red>L'arrivée de ce portail n'a pas pu être préparée : "
            + "téléportation annulée, vous restez ici. Vous pouvez réessayer.</red>";
    static final String ACCESS_UNAVAILABLE = "<red>Les conditions de ce portail n'ont pas pu être vérifiées : "
            + "vous restez ici. Vous pouvez réessayer.</red>";
    public static final String WORLD_UNAVAILABLE =
            "<red>Le monde de destination de ce portail n'est pas chargé, contacte un administrateur.</red>";
    public static final String SUCCESS = "<green>Téléportation réussie.</green>";
    static final String FAILED =
            "<red>La téléportation a échoué : vous n'avez pas quitté cet endroit. Vous pouvez réessayer.</red>";

    private final RPGQuestPlugin plugin;
    private final WorldPortalRegistry registry;
    private final WorldService worldService;
    private final Supplier<RandomSafeArrivalConfig> randomSafeArrivalConfig;
    private final Logger logger;

    private final Map<UUID, String> currentPortalByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> arrivalGrace = ConcurrentHashMap.newKeySet();
    /** Issue #73 : l'arrivée « CLAIM_HOME », fournie par le module claims (absent : repli RANDOM_SAFE). */
    private volatile ArrivalResolver claimArrival;
    /** Politique optionnelle d'autorisation d'entrée (mission « avertissement avant entrée dans le Wild »). */
    private volatile WorldPortalEntryGuard entryGuard;
    /** Issue #567 : conditions d'accès ; {@code null} = non câblé (un portail à conditions refuse alors tout passage). */
    private volatile PortalAccessChecker accessChecker;
    /** Issue #567 : joueurs dont les conditions sont en cours d'évaluation (un seul passage à la fois). */
    private final Set<UUID> pendingAccess = ConcurrentHashMap.newKeySet();
    /** TODO(debug bug TP hub) : marqueur de ré-entrance — voir {@link #onTeleport}. */
    private final Set<UUID> selfInitiatedTeleport = ConcurrentHashMap.newKeySet();

    public WorldPortalTeleportListener(RPGQuestPlugin plugin, WorldPortalRegistry registry, WorldService worldService,
                                        Supplier<RandomSafeArrivalConfig> randomSafeArrivalConfig, Logger logger) {
        this.plugin = plugin;
        this.registry = registry;
        this.worldService = worldService;
        this.randomSafeArrivalConfig = randomSafeArrivalConfig;
        this.logger = logger;
    }

    /**
     * Opérations asynchrones de Paper utilisées par le point fixe (issue #566) : {@code
     * World#getChunkAtAsync} et {@code Entity#teleportAsync} en jeu.
     */
    interface AsyncWorldAccess {

        CompletableFuture<?> loadChunk(World world, int chunkX, int chunkZ);

        CompletableFuture<Boolean> teleport(Player player, Location target);
    }

    static final AsyncWorldAccess PAPER = new AsyncWorldAccess() {
        @Override
        public CompletableFuture<?> loadChunk(World world, int chunkX, int chunkZ) {
            return world.getChunkAtAsync(chunkX, chunkZ);
        }

        @Override
        public CompletableFuture<Boolean> teleport(Player player, Location target) {
            return player.teleportAsync(target);
        }
    };

    private volatile AsyncWorldAccess async = PAPER;

    /** Visible pour les tests : MockBukkit n'implémente ni {@code getChunkAtAsync} ni {@code teleportAsync}. */
    void setAsyncWorldAccess(AsyncWorldAccess access) {
        this.async = access;
    }

    /** Une arrivée calculée pour un joueur précis — vide : laisser le repli s'appliquer. */
    @FunctionalInterface
    public interface ArrivalResolver {
        Optional<Location> resolve(Player player, World world);
    }

    /** Branche l'arrivée « CLAIM_HOME » (issue #73). */
    public void setClaimArrival(ArrivalResolver resolver) {
        this.claimArrival = resolver;
    }

    /** Durée du répit d'arrivée, en ticks — exposé pour {@code /rpgadmin worldportal info} (diagnostic, voir mission). */
    public static long arrivalGraceTicks() {
        return ARRIVAL_GRACE_TICKS;
    }

    /**
     * Installe la politique d'autorisation d'entrée (mission « avertissement avant entrée dans le
     * Wild »). {@code null} rétablit le comportement par défaut (aucune restriction). Setter plutôt
     * qu'argument de constructeur : le garde a besoin d'une référence vers {@link #teleportNow} pour
     * relancer la téléportation après un « Continuer », ce qui créerait un cycle de construction.
     */
    public void setEntryGuard(WorldPortalEntryGuard entryGuard) {
        this.entryGuard = entryGuard;
    }

    /** Issue #567 : vérification des conditions d'accès au franchissement. */
    public void setAccessChecker(PortalAccessChecker checker) {
        this.accessChecker = checker;
    }

    /**
     * Téléporte immédiatement {@code player} via {@code portal}, <strong>sans</strong> consulter le
     * garde d'entrée — appelé par le garde lui-même après un « Continuer » explicite du joueur.
     */
    public void teleportNow(Player player, WorldPortalDefinition portal) {
        currentPortalByPlayer.put(player.getUniqueId(), portal.id());
        teleport(player, portal);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return; // ne traite que les changements de bloc, jamais chaque micro-mouvement.
        }
        World world = to.getWorld();
        if (world == null) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (arrivalGrace.contains(playerId)) {
            return; // vient d'arriver (connexion/téléportation) : voir le répit documenté ci-dessus.
        }
        Optional<WorldPortalDefinition> portalOpt = registry.portalAt(world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ())
                .filter(WorldPortalDefinition::enabled);
        String newPortalId = portalOpt.map(WorldPortalDefinition::id).orElse(null);
        String previousPortalId = currentPortalByPlayer.get(playerId);
        if (Objects.equals(newPortalId, previousPortalId)) {
            return;
        }
        if (newPortalId == null) {
            currentPortalByPlayer.remove(playerId);
            TpTraceLogger.log(logger, "portal_exit", playerId, player.getName(), previousPortalId,
                    world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
                    false, true, null, null, portalLocationString(from), null);
            return;
        }
        currentPortalByPlayer.put(playerId, newPortalId);
        TpTraceLogger.log(logger, "portal_enter", playerId, player.getName(), newPortalId,
                world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
                true, previousPortalId != null, null, null, portalLocationString(from), null);
        WorldPortalEntryGuard guard = entryGuard;
        if (guard != null && !guard.allowEntry(player, portalOpt.get())) {
            return; // le garde a bloqué ce passage (ex. avertissement Wild sans Rune) et gère la suite.
        }
        // #569 : message d'attente du portail (aucun par défaut sans avertissement, comme avant).
        String preparing = portalOpt.get().messages().preparing();
        if (!preparing.isEmpty()) {
            player.sendMessage(MM.deserialize(preparing));
        }
        teleport(player, portalOpt.get());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        boolean inside = loc.getWorld() != null
                && registry.portalAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).isPresent();
        TpTraceLogger.log(logger, "player_join", player.getUniqueId(), player.getName(), null,
                loc.getWorld() != null ? loc.getWorld().getName() : "?", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                inside, null, ARRIVAL_GRACE_TICKS, null, null, null);
        grantArrivalGrace(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        World world = loc.getWorld();
        boolean inside = world != null
                && registry.portalAt(world.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).isPresent();
        TpTraceLogger.log(logger, "world_change", player.getUniqueId(), player.getName(), null,
                world != null ? world.getName() : "?", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                inside, null, arrivalGrace.contains(player.getUniqueId()) ? "active" : null, null,
                event.getFrom().getName(), null);
    }

    /**
     * TODO(debug bug TP hub) : {@code event=external_teleport} peut aussi apparaître pour une
     * téléportation déclenchée par {@link #teleport(Player, WorldPortalDefinition)} lui-même — voir
     * {@link #selfInitiatedTeleport}, qui supprime le doublon quand la ré-entrance est détectable de
     * façon fiable (appel synchrone). {@code PortalService#finishTeleport} utilise {@code
     * teleportAsync}, dont l'événement peut être différé au tick suivant : pas de garde-fou partagé
     * entre les deux classes pour cette raison (complexité jugée disproportionnée pour une
     * instrumentation temporaire) — corréler par {@code uuid=}/l'horodatage avec la ligne {@code
     * teleport_start}/{@code teleport_success} adjacente en cas de doute.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        grantArrivalGrace(playerId);
        if (selfInitiatedTeleport.contains(playerId)) {
            return; // notre propre teleport() plus bas : déjà tracé par teleport_start/teleport_success.
        }
        Location to = event.getTo();
        World world = to != null ? to.getWorld() : null;
        TpTraceLogger.log(logger, "external_teleport", playerId, player.getName(), null,
                world != null ? world.getName() : "?",
                to != null ? to.getBlockX() : 0, to != null ? to.getBlockY() : 0, to != null ? to.getBlockZ() : 0,
                null, null, ARRIVAL_GRACE_TICKS, null,
                portalLocationString(event.getFrom()), portalLocationString(to));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        currentPortalByPlayer.remove(playerId);
        pendingAccess.remove(playerId);
        arrivalGrace.remove(playerId);
        selfInitiatedTeleport.remove(playerId);
    }

    private void grantArrivalGrace(UUID playerId) {
        arrivalGrace.add(playerId);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> arrivalGrace.remove(playerId), ARRIVAL_GRACE_TICKS);
    }

    /**
     * Issue #567 — conditions d'accès vérifiées par le serveur, <em>après</em> les gardes Claims /
     * Wild (déjà passés à ce stade, ou relancés par eux via {@link #teleportNow}). Un portail sans
     * condition garde exactement le chemin d'avant, synchrone et dans le même tick ; avec conditions,
     * l'évaluation est asynchrone puis le passage reprend sur le thread principal. Refus, erreur de
     * lecture ou vérificateur absent : le joueur reste sur place, avec un message.
     */
    private void teleport(Player player, WorldPortalDefinition portal) {
        if (portal.access().isEmpty()) {
            transport(player, portal, () -> { });
            return;
        }
        PortalAccessChecker checker = accessChecker;
        if (checker == null) {
            logger.warn("Portail simple {} : conditions d'accès configurées mais non vérifiables — passage refusé.",
                    portal.id());
            player.sendMessage(MM.deserialize(ACCESS_UNAVAILABLE));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!pendingAccess.add(playerId)) {
            return; // une évaluation est déjà en cours pour ce joueur.
        }
        evaluateSafely(checker, player, portal, true).whenComplete((verdict, error) -> onMainThread(() -> {
            pendingAccess.remove(playerId);
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                logger.error("Portail simple {} : conditions d'accès de {} non vérifiables — passage refusé.",
                        portal.id(), player.getName(), error);
                player.sendMessage(MM.deserialize(ACCESS_UNAVAILABLE));
                return;
            }
            if (!verdict.allowed()) {
                PortalAccessChecker.Check refused = verdict.firstRefusal().orElseThrow();
                logger.info("Portail simple {} : passage refusé pour {} — {}.", portal.id(), player.getName(),
                        refused.label());
                player.sendMessage(PortalAccessChecker.render(refused));
                return;
            }
            if (!portal.world().equals(player.getWorld().getName())) {
                return; // a changé de monde pendant la vérification : plus de passage à faire.
            }
            transport(player, portal, () -> checker.recordPassage(player, portal));
        }));
    }

    private static CompletableFuture<PortalAccessChecker.Verdict> evaluateSafely(PortalAccessChecker checker,
            Player player, WorldPortalDefinition portal, boolean withCooldown) {
        try {
            return checker.evaluate(player, portal, withCooldown);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Issue #567 — issue d'un « Téléporter pour test » (voir {@link #testTeleport}). */
    public enum TestOutcome {
        /** Conditions remplies, gardes passés : la téléportation est lancée. */
        TELEPORTING,
        /** Une condition d'accès refuse : personne n'est déplacé. */
        REFUSED,
        /** Un garde Claims / Wild a pris la main (fenêtre ou refus montré en jeu). */
        GUARD_TOOK_OVER,
        /** Le joueur s'est déconnecté. */
        PLAYER_OFFLINE
    }

    public record TestResult(TestOutcome outcome, PortalAccessChecker.Verdict verdict) {
    }

    /**
     * Issue #567 — « Téléporter pour test » : le chemin réel d'un passage, sans entrer dans la zone
     * et quel que soit l'état du portail (un brouillon se teste) : conditions d'accès (sans le délai,
     * ni lu ni démarré), puis gardes Claims / Wild, puis téléportation. À appeler sur le thread principal.
     */
    public CompletableFuture<TestResult> testTeleport(Player player, WorldPortalDefinition portal) {
        PortalAccessChecker checker = accessChecker;
        CompletableFuture<PortalAccessChecker.Verdict> verdict = portal.access().isEmpty() || checker == null
                ? CompletableFuture.completedFuture(new PortalAccessChecker.Verdict(portal.access().isEmpty(),
                List.of()))
                : evaluateSafely(checker, player, portal, false);
        CompletableFuture<TestResult> result = new CompletableFuture<>();
        verdict.whenComplete((v, error) -> onMainThread(() -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            if (!player.isOnline()) {
                result.complete(new TestResult(TestOutcome.PLAYER_OFFLINE, v));
                return;
            }
            if (!v.allowed()) {
                v.firstRefusal().ifPresent(c -> player.sendMessage(PortalAccessChecker.render(c)));
                result.complete(new TestResult(TestOutcome.REFUSED, v));
                return;
            }
            logger.info("Portail simple {} : téléportation de test de {} demandée depuis PlugAdmin.", portal.id(),
                    player.getName());
            WorldPortalEntryGuard guard = entryGuard;
            if (guard != null && !guard.allowEntry(player, portal)) {
                result.complete(new TestResult(TestOutcome.GUARD_TOOK_OVER, v));
                return;
            }
            currentPortalByPlayer.put(player.getUniqueId(), portal.id());
            transport(player, portal, () -> { });
            result.complete(new TestResult(TestOutcome.TELEPORTING, v));
        }));
        return result;
    }

    /** Le passage lui-même (monde d'arrivée, stratégie, sécurité) ; {@code onSuccess} après une téléportation réussie. */
    private void transport(Player player, WorldPortalDefinition portal, Runnable onSuccess) {
        Optional<World> destination = worldService.find(portal.destinationWorld());
        if (destination.isEmpty()) {
            player.sendMessage(text(portal.messages().unavailable(), WORLD_UNAVAILABLE));
            logger.warn("Portail simple {} pointe vers un monde non chargé « {} ».", portal.id(), portal.destinationWorld());
            return;
        }
        World world = destination.get();
        if (portal.destinationStrategy() == DestinationStrategy.FIXED_POINT) {
            teleportToFixedPoint(player, portal, world, onSuccess);
            return;
        }
        Location source = player.getLocation();

        // Diagnostic de latence (issue #161) : les trois étapes du passage sont mesurées séparément
        // — recherche d'une colonne sûre, chargement/génération des chunks, téléportation. Aucun
        // contrôle de sécurité n'est allégé pour aller plus vite ; seule la mesure est ajoutée.
        long startNanos = System.nanoTime();
        RandomSafeLocationFinder.SearchMetrics searchMetrics = new RandomSafeLocationFinder.SearchMetrics();
        long searchNanos = 0L;
        Location target;
        if (portal.destinationStrategy() == DestinationStrategy.CLAIM_HOME) {
            long searchStart = System.nanoTime();
            ArrivalResolver resolver = claimArrival;
            target = (resolver == null ? Optional.<Location>empty() : resolver.resolve(player, world))
                    .orElseGet(() -> {
                        logger.warn("Portail simple {} (CLAIM_HOME) : aucune arrivée de claim pour {} — repli "
                                + "RANDOM_SAFE.", portal.id(), player.getName());
                        return resolveRandomSafeLocation(portal, world, searchMetrics);
                    });
            searchNanos = System.nanoTime() - searchStart;
        } else if (portal.destinationStrategy() == DestinationStrategy.RANDOM_SAFE) {
            long searchStart = System.nanoTime();
            target = resolveRandomSafeLocation(portal, world, searchMetrics);
            searchNanos = System.nanoTime() - searchStart;
        } else {
            target = world.getSpawnLocation();
        }

        UUID playerId = player.getUniqueId();
        TpTraceLogger.log(logger, "teleport_start", playerId, player.getName(), portal.id(),
                world.getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                null, null, null, null, portalLocationString(source), portalLocationString(target));

        // teleport() synchrone plutôt que teleportAsync() : ce portail simple vise en priorité la
        // robustesse et la testabilité (voir travel.WorldPortalTeleportListenerTest) — contrairement
        // à PortalService, aucun chargement de chunk distant coûteux n'est en jeu ici (spawn ou
        // colonne déjà chargée/générée à la demande par RandomSafeLocationFinder juste au-dessus).
        selfInitiatedTeleport.add(playerId);
        boolean success;
        long teleportNanos;
        try {
            long teleportStart = System.nanoTime();
            success = player.teleport(target);
            teleportNanos = System.nanoTime() - teleportStart;
        } finally {
            selfInitiatedTeleport.remove(playerId);
        }

        TpTraceLogger.log(logger, success ? "teleport_success" : "teleport_failed", playerId, player.getName(), portal.id(),
                world.getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                null, null, null, null, portalLocationString(source), portalLocationString(target));
        TpTraceLogger.logLatency(logger, playerId, player.getName(), portal.id(), world.getName(),
                portal.destinationStrategy().name(), searchMetrics.attempts(),
                TpTraceLogger.toMillis(searchNanos - searchMetrics.chunkLoadNanos()),
                TpTraceLogger.toMillis(searchMetrics.chunkLoadNanos()),
                TpTraceLogger.toMillis(teleportNanos),
                TpTraceLogger.toMillis(System.nanoTime() - startNanos));

        if (!success) {
            logger.warn("player.teleport() a renvoyé false pour le portail simple {} (joueur {}) — "
                    + "un autre plugin a probablement annulé la téléportation.", portal.id(), player.getName());
            // Issue #161 : un retour d'échec compréhensible, jamais un faux « réussie » — le joueur
            // reste là où il était et peut réessayer en reprenant le portail.
            player.sendMessage(MM.deserialize(FAILED));
            return;
        }
        onSuccess.run();
        announceArrival(player, portal);
    }

    /** Réussite puis bienvenue éventuelle (issue #569) : textes du portail, sinon ceux d'avant. */
    private static void announceArrival(Player player, WorldPortalDefinition portal) {
        player.sendMessage(text(portal.messages().success(), SUCCESS));
        String welcome = portal.messages().welcome();
        if (!welcome.isEmpty()) {
            player.sendMessage(MM.deserialize(welcome));
        }
    }

    /** Texte personnalisé du portail (MiniMessage), sinon le texte par défaut. */
    private static net.kyori.adventure.text.Component text(String custom, String fallback) {
        return MM.deserialize(custom.isEmpty() ? fallback : custom);
    }

    /**
     * Issue #566 — arrivée sur un point fixe, entièrement <strong>asynchrone</strong> : les chunks de
     * la boîte de recherche ({@link ArrivalSafety#chunksAround}) sont chargés par {@code
     * getChunkAtAsync}, puis la position est contrôlée sur le thread principal et le joueur déplacé
     * par {@code teleportAsync}. Point non sûr et aucune position sûre à proximité immédiate, ou point
     * dans la zone d'un portail actif : la téléportation est <strong>refusée</strong>, le joueur reste
     * sur place. Les stratégies historiques (Hub → Wild, Hub → Claims) gardent leur chemin
     * synchrone ci-dessus, inchangé.
     */
    private void teleportToFixedPoint(Player player, WorldPortalDefinition portal, World world, Runnable onSuccess) {
        WorldPortalDefinition.FixedPoint point = portal.fixedPoint();
        List<CompletableFuture<?>> loads = new ArrayList<>();
        AsyncWorldAccess access = async;
        try {
            for (long[] c : ArrivalSafety.chunksAround(point.blockX(), point.blockZ())) {
                loads.add(access.loadChunk(world, (int) c[0], (int) c[1]));
            }
        } catch (RuntimeException e) {
            failFixedPoint(player, portal, e);
            return;
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                onMainThread(() -> {
                    try {
                        finishFixedPoint(player, portal, world, point, error, access, onSuccess);
                    } catch (RuntimeException e) {
                        failFixedPoint(player, portal, e);
                    }
                }));
    }

    /** Erreur inattendue du chemin asynchrone : journalisée, le joueur prévenu et laissé sur place. */
    private void failFixedPoint(Player player, WorldPortalDefinition portal, Throwable error) {
        logger.error("Portail simple {} (FIXED_POINT) : erreur pendant la téléportation de {}.", portal.id(),
                player.getName(), error);
        if (player.isOnline()) {
            player.sendMessage(MM.deserialize(FIXED_POINT_UNAVAILABLE));
        }
    }

    private void finishFixedPoint(Player player, WorldPortalDefinition portal, World world,
                                  WorldPortalDefinition.FixedPoint point, Throwable chunkError,
                                  AsyncWorldAccess access, Runnable onSuccess) {
        if (!player.isOnline()) {
            return;
        }
        if (chunkError != null || plugin.getServer().getWorld(world.getName()) == null) {
            logger.warn("Portail simple {} (FIXED_POINT) : arrivée dans « {} » impossible à préparer.", portal.id(),
                    world.getName(), chunkError);
            player.sendMessage(MM.deserialize(FIXED_POINT_UNAVAILABLE));
            return;
        }
        Optional<ArrivalSafety.Spot> spot = ArrivalSafety.findNear(ArrivalSafety.worldView(world),
                point.blockX(), point.blockY(), point.blockZ());
        if (spot.isEmpty()) {
            logger.warn("Portail simple {} (FIXED_POINT) : arrivée {} {},{},{} non sûre et aucune position sûre "
                            + "à {} bloc(s) autour — téléportation refusée.", portal.id(), world.getName(),
                    point.blockX(), point.blockY(), point.blockZ(), ArrivalSafety.HORIZONTAL_RADIUS);
            player.sendMessage(text(portal.messages().unsafe(), FIXED_POINT_UNSAFE));
            return;
        }
        ArrivalSafety.Spot s = spot.get();
        if (registry.portalAt(world.getName(), s.x(), s.y(), s.z()).filter(WorldPortalDefinition::enabled).isPresent()) {
            logger.warn("Portail simple {} (FIXED_POINT) : l'arrivée {} {},{},{} est dans la zone d'un portail "
                    + "actif — téléportation refusée (boucle).", portal.id(), world.getName(), s.x(), s.y(), s.z());
            player.sendMessage(text(portal.messages().unsafe(), FIXED_POINT_UNSAFE));
            return;
        }
        boolean exact = s.x() == point.blockX() && s.y() == point.blockY() && s.z() == point.blockZ();
        Location target = exact
                ? new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch())
                : new Location(world, s.x() + 0.5, s.y(), s.z() + 0.5, point.yaw(), point.pitch());
        UUID playerId = player.getUniqueId();
        Location source = player.getLocation();
        TpTraceLogger.log(logger, "teleport_start", playerId, player.getName(), portal.id(),
                world.getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                null, null, null, null, portalLocationString(source), portalLocationString(target));
        access.teleport(player, target).whenComplete((success, error) -> onMainThread(() -> {
            boolean ok = error == null && Boolean.TRUE.equals(success);
            if (error != null) {
                logger.warn("Portail simple {} (FIXED_POINT) : téléportation de {} en échec.", portal.id(),
                        player.getName(), error);
            }
            TpTraceLogger.log(logger, ok ? "teleport_success" : "teleport_failed", playerId, player.getName(),
                    portal.id(), world.getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(),
                    null, null, null, null, portalLocationString(source), portalLocationString(target));
            if (ok) {
                onSuccess.run();
            }
            if (!player.isOnline()) {
                return;
            }
            if (ok) {
                announceArrival(player, portal);
            } else {
                player.sendMessage(MM.deserialize(FAILED));
            }
        }));
    }

    private void onMainThread(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Résout une position aléatoire sûre autour du spawn du monde destination (voir {@link
     * RandomSafeLocationFinder}) ; repli automatique — journalisé — sur le spawn du monde si aucune
     * position sûre n'est trouvée en {@code max-attempts} tentatives (jamais de boucle infinie, et
     * le joueur est toujours téléporté proprement).
     */
    private Location resolveRandomSafeLocation(WorldPortalDefinition portal, World world,
                                                RandomSafeLocationFinder.SearchMetrics metrics) {
        RandomSafeArrivalConfig config = randomSafeArrivalConfig.get();
        RandomSafeLocationFinder finder = new RandomSafeLocationFinder(config.minRadius(), config.maxRadius(), config.maxAttempts());
        return finder.find(world, world.getSpawnLocation(), metrics).orElseGet(() -> {
            logger.warn(
                    "Portail simple {} (RANDOM_SAFE) : aucune position sûre trouvée en {} tentative(s) (rayon {}-{}) dans « {} », repli sur son spawn.",
                    portal.id(), config.maxAttempts(), config.minRadius(), config.maxRadius(), world.getName());
            return world.getSpawnLocation();
        });
    }

    private static String portalLocationString(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
