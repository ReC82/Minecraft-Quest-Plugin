package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PortalCooldownRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.TransactionType;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.travel.model.Destination;
import com.lodygames.rpgquest.travel.model.PortalDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Moteur des portails : détection d'entrée dans une zone d'activation
 * (via {@code PlayerMoveEvent}), canalisation à délai avec annulation sur
 * mouvement/dégâts/déconnexion (même patron « tâche répétée + annulation
 * » que {@code admin.FlattenService}), vérification de sécurité de la
 * destination, débit (uniquement si tout le reste a réussi), téléportation,
 * et cooldown persisté.
 *
 * <p><b>Aucun débit tant que le succès n'est pas garanti</b> : le coût
 * n'est débité qu'après que la destination a été résolue et vérifiée sûre,
 * juste avant la téléportation elle-même — une téléportation qui échoue
 * (destination introuvable/dangereuse, monde absent) ne coûte jamais rien
 * au joueur.</p>
 */
public final class PortalService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Tolérance de mouvement pendant la canalisation, au carré (évite un {@code sqrt} par tick) — 0,6 bloc. */
    private static final double MOVEMENT_TOLERANCE_SQUARED = 0.36;
    private static final int SAFE_SEARCH_RADIUS = 5;

    private final RPGQuestPlugin plugin;
    private final YamlPortalRegistry portalRegistry;
    private final YamlDestinationRegistry destinationRegistry;
    private final EconomyService economyService;
    private final QuestProgressEngine questProgressEngine;
    private final PortalCooldownRepository cooldownRepository;
    private final Logger logger;

    private final Map<UUID, ChannelingSession> channeling = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentPortalByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Instant>> cooldownCache = new ConcurrentHashMap<>();

    public PortalService(RPGQuestPlugin plugin, YamlPortalRegistry portalRegistry, YamlDestinationRegistry destinationRegistry,
                          EconomyService economyService, QuestProgressEngine questProgressEngine,
                          PortalCooldownRepository cooldownRepository) {
        this.plugin = plugin;
        this.portalRegistry = portalRegistry;
        this.destinationRegistry = destinationRegistry;
        this.economyService = economyService;
        this.questProgressEngine = questProgressEngine;
        this.cooldownRepository = cooldownRepository;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        // Rien à démarrer : le chargement des définitions est un service séparé (registres), les
        // cooldowns sont chargés par joueur à la connexion (voir handleJoin).
    }

    @Override
    public void stop() {
        for (ChannelingSession session : channeling.values()) {
            if (session.task != null) {
                session.task.cancel();
            }
        }
        channeling.clear();
        currentPortalByPlayer.clear();
        cooldownCache.clear();
    }

    /** Listener Bukkit unique (mouvement/dégâts/connexion/déconnexion) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new PortalListener(this);
    }

    boolean isChanneling(UUID playerId) {
        return channeling.containsKey(playerId);
    }

    void handleJoin(Player player) {
        UUID playerId = player.getUniqueId();
        cooldownRepository.allForPlayer(playerId)
                .thenAccept(map -> cooldownCache.put(playerId, new ConcurrentHashMap<>(map)))
                .exceptionally(error -> {
                    logger.error("Impossible de charger les cooldowns de portail pour {}", playerId, error);
                    return null;
                });
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        cancelChanneling(playerId, null);
        currentPortalByPlayer.remove(playerId);
        cooldownCache.remove(playerId);
    }

    void handleDamage(Player player) {
        cancelChanneling(player.getUniqueId(), "<red>Téléportation annulée : tu as subi des dégâts.</red>");
    }

    /**
     * Appelé à chaque changement de position de bloc. Ne réagit qu'à une
     * vraie transition (entrée dans un nouveau portail, ou sortie), jamais
     * à chaque micro-mouvement à l'intérieur du même portail — évite de
     * spammer les messages de refus à chaque tick pour un joueur immobile
     * dans une zone d'activation dont il ne remplit pas les conditions.
     */
    void handleMove(Player player, Location to) {
        UUID playerId = player.getUniqueId();
        if (isChanneling(playerId)) {
            return; // la tolérance de mouvement pendant la canalisation est gérée par le tick, pas ici.
        }
        World world = to.getWorld();
        if (world == null) {
            return;
        }
        Optional<PortalDefinition> portalOpt =
                portalRegistry.portalAt(world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        String newPortalId = portalOpt.map(PortalDefinition::id).orElse(null);
        String previousPortalId = currentPortalByPlayer.get(playerId);
        if (Objects.equals(newPortalId, previousPortalId)) {
            return;
        }
        if (newPortalId == null) {
            currentPortalByPlayer.remove(playerId);
            return;
        }
        currentPortalByPlayer.put(playerId, newPortalId);
        attemptActivate(player, portalOpt.get());
    }

    // ---- Activation (conditions) -------------------------------------------

    private void attemptActivate(Player player, PortalDefinition portal) {
        if (!portal.hasDestination()) {
            player.sendMessage(MM.deserialize("<red>Ce portail n'a pas encore de destination configurée.</red>"));
            return;
        }
        if (portal.requiredPermission() != null && !player.hasPermission(portal.requiredPermission())) {
            player.sendMessage(MM.deserialize("<red>Il te manque la permission requise pour ce portail.</red>"));
            return;
        }
        if (portal.requiredLevel() != null && player.getLevel() < portal.requiredLevel()) {
            player.sendMessage(MM.deserialize("<red>Il te manque le niveau requis pour ce portail.</red>"));
            return;
        }
        Instant cooldownExpiry = cooldownCache.getOrDefault(player.getUniqueId(), Map.of()).get(portal.id());
        if (cooldownExpiry != null && Instant.now().isBefore(cooldownExpiry)) {
            long remaining = Duration.between(Instant.now(), cooldownExpiry).toSeconds() + 1;
            player.sendMessage(MM.deserialize(
                    "<red>Ce portail est en recharge encore <seconds> s.</red>",
                    Placeholder.unparsed("seconds", String.valueOf(remaining))));
            return;
        }
        checkQuestThenCostThenChannel(player, portal);
    }

    private void checkQuestThenCostThenChannel(Player player, PortalDefinition portal) {
        CompletableFuture<Boolean> questCheck = portal.requiredQuestId() == null
                ? CompletableFuture.completedFuture(true)
                : questProgressEngine.stateOf(player.getUniqueId(), portal.requiredQuestId())
                        .thenApply(state -> state == portal.requiredQuestState());

        questCheck.thenAccept(questOk -> {
            if (!questOk) {
                runOnMainThread(() -> player.sendMessage(
                        MM.deserialize("<red>Ce portail nécessite une progression de quête différente.</red>")));
                return;
            }
            if (portal.cost() == null) {
                runOnMainThread(() -> startChanneling(player, portal));
                return;
            }
            economyService.balance(player.getUniqueId()).thenAccept(balance -> runOnMainThread(() -> {
                if (balance < portal.cost()) {
                    player.sendMessage(MM.deserialize(
                            "<red>Fonds insuffisants pour utiliser ce portail</red> <gray>(<cost> requis).</gray>",
                            Placeholder.unparsed("cost", String.valueOf(portal.cost()))));
                } else {
                    startChanneling(player, portal);
                }
            }));
        }).exceptionally(error -> {
            logger.error("Échec de la vérification des conditions du portail {} pour {}",
                    portal.id(), player.getUniqueId(), error);
            return null;
        });
    }

    // ---- Canalisation -------------------------------------------------------

    private void startChanneling(Player player, PortalDefinition portal) {
        UUID playerId = player.getUniqueId();
        if (channeling.containsKey(playerId)) {
            return; // défensif : déjà filtré par handleMove, ne devrait jamais arriver.
        }
        long totalTicks = portal.channelSeconds() * 20L;
        ChannelingSession session = new ChannelingSession(playerId, portal, player.getLocation().clone(), totalTicks);
        channeling.put(playerId, session);

        if (totalTicks <= 0) {
            completeChanneling(session);
            return;
        }
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tickChannel(session), 1L, 1L);
        reportProgress(session);
    }

    private void tickChannel(ChannelingSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            channeling.remove(session.playerId);
            if (session.task != null) {
                session.task.cancel();
            }
            return;
        }

        Location current = player.getLocation();
        World startWorld = session.startLocation.getWorld();
        if (startWorld == null || !startWorld.equals(current.getWorld())
                || current.distanceSquared(session.startLocation) > MOVEMENT_TOLERANCE_SQUARED) {
            cancelChanneling(session.playerId, "<red>Téléportation annulée : tu as bougé.</red>");
            return;
        }

        session.elapsedTicks++;
        if (session.elapsedTicks >= session.totalTicks) {
            completeChanneling(session);
            return;
        }
        reportProgress(session);
    }

    private void reportProgress(ChannelingSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null) {
            return;
        }
        int percent = session.totalTicks <= 0 ? 100 : (int) (100L * session.elapsedTicks / session.totalTicks);
        player.sendActionBar(MM.deserialize(
                "<yellow>Téléportation :</yellow> <white><percent>%</white>",
                Placeholder.unparsed("percent", String.valueOf(percent))));
    }

    void cancelChanneling(UUID playerId, String message) {
        ChannelingSession session = channeling.remove(playerId);
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        if (message != null) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(MM.deserialize(message));
            }
        }
    }

    private void completeChanneling(ChannelingSession session) {
        channeling.remove(session.playerId);
        if (session.task != null) {
            session.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        PortalDefinition portal = session.portal;
        Optional<Destination> destinationOpt = destinationRegistry.find(portal.destinationId());
        if (destinationOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Destination introuvable, contacte un administrateur.</red>"));
            logger.warn("Portail {} référence une destination inconnue « {} ».", portal.id(), portal.destinationId());
            return;
        }
        Destination destination = destinationOpt.get();
        World world = plugin.getServer().getWorld(destination.world());
        if (world == null) {
            player.sendMessage(MM.deserialize("<red>Le monde de destination n'existe plus, contacte un administrateur.</red>"));
            logger.warn("Portail {} pointe vers un monde absent « {} ».", portal.id(), destination.world());
            return;
        }
        Optional<Location> safeLocation = findSafeLocation(world, destination);
        if (safeLocation.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Destination dangereuse, contacte un administrateur.</red>"));
            logger.warn("Aucune position sûre trouvée près de la destination « {} » (portail {}).",
                    destination.id(), portal.id());
            return;
        }

        if (portal.cost() == null) {
            finishTeleport(player, portal, safeLocation.get());
            return;
        }
        economyService.debit(player.getUniqueId(), portal.cost(), TransactionType.PORTAL_USE, portal.id())
                .thenAccept(success -> runOnMainThread(() -> {
                    if (!success) {
                        player.sendMessage(MM.deserialize("<red>Fonds insuffisants.</red>"));
                        return;
                    }
                    finishTeleport(player, portal, safeLocation.get());
                }))
                .exceptionally(error -> {
                    logger.error("Échec du débit lors d'une téléportation par portail pour {}", player.getUniqueId(), error);
                    runOnMainThread(() -> player.sendMessage(MM.deserialize("<red>Une erreur est survenue, réessaie.</red>")));
                    return null;
                });
    }

    private void finishTeleport(Player player, PortalDefinition portal, Location safeLocation) {
        player.teleportAsync(safeLocation);
        player.sendMessage(MM.deserialize("<green>Téléportation réussie.</green>"));

        if (portal.cooldownSeconds() > 0) {
            Instant expiresAt = Instant.now().plusSeconds(portal.cooldownSeconds());
            cooldownCache.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>()).put(portal.id(), expiresAt);
            cooldownRepository.setCooldown(player.getUniqueId(), portal.id(), expiresAt).exceptionally(error -> {
                logger.error("Échec de la persistance du cooldown du portail {} pour {}", portal.id(), player.getUniqueId(), error);
                return null;
            });
        }
    }

    // ---- Sécurité de la destination -----------------------------------------

    /**
     * Cherche une position sûre au plus proche de {@code destination},
     * en balayant verticalement (alterne au-dessus/au-dessous) jusqu'à
     * {@link #SAFE_SEARCH_RADIUS} blocs. Charge la colonne de chunk à la
     * demande — un accès ponctuel naturel (comme n'importe quelle
     * téléportation vanilla vers une zone déchargée), jamais un
     * chargement forcé permanent (aucun ticket de chunk posé).
     */
    private Optional<Location> findSafeLocation(World world, Destination destination) {
        int x = (int) Math.floor(destination.x());
        int z = (int) Math.floor(destination.z());
        int baseY = (int) Math.floor(destination.y());
        world.getChunkAt(x >> 4, z >> 4);

        if (isSafe(world, x, baseY, z)) {
            return Optional.of(exactLocation(world, destination, baseY));
        }
        for (int dy = 1; dy <= SAFE_SEARCH_RADIUS; dy++) {
            int up = baseY + dy;
            if (isSafe(world, x, up, z)) {
                return Optional.of(exactLocation(world, destination, up));
            }
            int down = baseY - dy;
            if (isSafe(world, x, down, z)) {
                return Optional.of(exactLocation(world, destination, down));
            }
        }
        return Optional.empty();
    }

    private Location exactLocation(World world, Destination destination, int y) {
        // Garde x/z/yaw/pitch exacts de la destination : seul Y est ajusté par la recherche de sécurité.
        return new Location(world, destination.x(), y, destination.z(), destination.yaw(), destination.pitch());
    }

    private boolean isSafe(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        if (isDangerous(feet) || isDangerous(head)) {
            return false;
        }
        if (feet.getType().isSolid() || head.getType().isSolid()) {
            return false; // pas de place pour se tenir : jamais dans un bloc solide.
        }
        return ground.getType().isSolid(); // sol sous les pieds : jamais dans le vide.
    }

    private boolean isDangerous(Block block) {
        Material type = block.getType();
        return type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.MAGMA_BLOCK || type == Material.CACTUS;
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static final class ChannelingSession {
        private final UUID playerId;
        private final PortalDefinition portal;
        private final Location startLocation;
        private final long totalTicks;
        private long elapsedTicks;
        private BukkitTask task;

        private ChannelingSession(UUID playerId, PortalDefinition portal, Location startLocation, long totalTicks) {
            this.playerId = playerId;
            this.portal = portal;
            this.startLocation = startLocation;
            this.totalTicks = totalTicks;
        }
    }
}
