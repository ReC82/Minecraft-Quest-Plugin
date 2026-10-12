package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.ItemTravelCooldownRepository;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.travel.model.ItemTravelDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Moteur générique de voyage par objet (mission « mécanique RPG générique de voyage par objet ») :
 * clic droit avec un objet enregistré ({@link #register}) démarre une canalisation (annulée sur
 * mouvement/dégâts/déconnexion — même patron que {@code PortalService}, sans en partager le code :
 * déclencheur différent — entrée en zone côté portails, clic sur objet ici — et aucune notion de
 * coût/cooldown/prérequis de quête à ce stade), puis téléporte vers la destination résolue au
 * moment de la complétion. <strong>Ne consomme jamais l'objet</strong> — la persistance/non-
 * consommation d'une pierre de voyage est une propriété de l'objet lui-même (ou de l'appelant), pas
 * de ce moteur.
 *
 * <p>Ajouter une future pierre/destination : un seul appel à {@link #register}, aucun changement de
 * ce moteur (mission « réutilisable plus tard sans réécrire tout le système »).</p>
 */
public final class ItemTravelService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final double MOVEMENT_TOLERANCE_SQUARED = 0.36;

    private final RPGQuestPlugin plugin;
    private final YamlCustomItemRegistry customItemRegistry;
    private final ItemTravelCooldownRepository cooldownRepository;
    private final Logger logger;

    private final Map<NamespacedKey, ItemTravelDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<UUID, ChannelSession> channeling = new ConcurrentHashMap<>();
    /** Cooldowns par joueur (chargés à la connexion, jamais relus en base à chaque clic). */
    private final Map<UUID, Map<String, Instant>> cooldowns = new ConcurrentHashMap<>();

    public ItemTravelService(RPGQuestPlugin plugin, YamlCustomItemRegistry customItemRegistry,
                              ItemTravelCooldownRepository cooldownRepository, Logger logger) {
        this.plugin = plugin;
        this.customItemRegistry = customItemRegistry;
        this.cooldownRepository = cooldownRepository;
        this.logger = logger;
    }

    public void register(ItemTravelDefinition definition) {
        definitions.put(definition.itemId(), definition);
    }

    /** Issue #221 : point de sauvegarde des durées restantes, en ticks (une minute). */
    static final long CHECKPOINT_TICKS = 20L * 60;

    private org.bukkit.scheduler.BukkitTask checkpoint;

    @Override
    public void start() {
        // Les définitions sont enregistrées par le bootstrap avant/après start(), sans ordre imposé.
        // Issue #221 : toutes les minutes, la durée restante des cooldowns en cours est enregistrée.
        // Un arrêt brutal coûte au plus une minute d'attente en plus — jamais une remise à zéro.
        checkpoint = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkpointAll,
                CHECKPOINT_TICKS, CHECKPOINT_TICKS);
    }

    @Override
    public void stop() {
        if (checkpoint != null) {
            checkpoint.cancel();
        }
        for (ChannelSession session : channeling.values()) {
            if (session.task != null) {
                session.task.cancel();
            }
        }
        channeling.clear();
        // Issue #221 : l'arrêt du serveur suspend le cooldown — la durée restante est enregistrée
        // et attendue (bornée), sinon l'écriture partirait après la fermeture de la base.
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (UUID playerId : cooldowns.keySet()) {
            writes.add(persistRemaining(playerId));
        }
        try {
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception error) {
            logger.warn("Durées restantes des cooldowns de voyage non toutes enregistrées à l'arrêt : {}",
                    error.toString());
        }
        cooldowns.clear();
    }

    /** Enregistre la durée restante de chaque cooldown en cours d'un joueur. */
    CompletableFuture<Void> persistRemaining(UUID playerId) {
        Map<String, Instant> byItem = cooldowns.get(playerId);
        if (byItem == null || byItem.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Instant now = Instant.now();
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : byItem.entrySet()) {
            Duration remaining = Duration.between(now, entry.getValue());
            writes.add(cooldownRepository.saveRemaining(playerId, entry.getKey(),
                    remaining.isNegative() ? Duration.ZERO : remaining).exceptionally(error -> {
                        logger.error("Impossible d'enregistrer la durée restante de {} pour {}", entry.getKey(),
                                playerId, error);
                        return null;
                    }));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private void checkpointAll() {
        for (UUID playerId : cooldowns.keySet()) {
            persistRemaining(playerId);
        }
    }

    public Listener listener() {
        return new ItemTravelListener(this);
    }

    boolean isChanneling(UUID playerId) {
        return channeling.containsKey(playerId);
    }

    /** Charge en mémoire les cooldowns persistés du joueur — appelé une fois à la connexion. */
    void handleJoin(Player player) {
        reloadCooldownsForPlayer(player.getUniqueId());
    }

    /**
     * Recharge en mémoire les cooldowns de voyage par objet persistés d'un joueur (aussi appelé par
     * le reset admin « nouveau joueur » après suppression des lignes en base — voir {@code
     * player.PlayerResetService}). Un joueur hors ligne n'a aucun cache à invalider.
     */
    public void reloadCooldownsForPlayer(UUID playerId) {
        cooldownRepository.allForPlayer(playerId)
                .thenAccept(loaded -> cooldowns.put(playerId, new ConcurrentHashMap<>(loaded)))
                .exceptionally(error -> {
                    logger.error("Impossible de charger les cooldowns de voyage par objet pour {}", playerId, error);
                    return null;
                });
    }

    private Instant cooldownExpiry(UUID playerId, NamespacedKey itemId) {
        Map<String, Instant> byItem = cooldowns.get(playerId);
        return byItem == null ? null : byItem.get(itemId.toString());
    }

    private void applyCooldown(UUID playerId, ItemTravelDefinition definition) {
        // La durée réellement configurée de l'objet, enregistrée comme durée restante (#221).
        Instant expiresAt = Instant.now().plusSeconds(definition.cooldownSeconds());
        cooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(definition.itemId().toString(), expiresAt);
        cooldownRepository.saveRemaining(playerId, definition.itemId().toString(),
                Duration.ofSeconds(definition.cooldownSeconds())).exceptionally(error -> {
            logger.error("Impossible de persister le cooldown de {} pour {}", definition.itemId(), playerId, error);
            return null;
        });
    }

    private static String formatRemaining(Duration remaining) {
        long totalSeconds = Math.max(1, remaining.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + " min " + seconds + " s" : seconds + " s";
    }

    void handleInteract(Player player, ItemStack item) {
        UUID playerId = player.getUniqueId();
        if (isChanneling(playerId)) {
            return;
        }
        Optional<NamespacedKey> itemId = customItemRegistry.identify(item);
        if (itemId.isEmpty()) {
            return;
        }
        ItemTravelDefinition definition = definitions.get(itemId.get());
        if (definition == null) {
            return;
        }
        Optional<String> freeRescueWorld = definition.freeRescueWorld().get();
        if (freeRescueWorld.isPresent() && freeRescueWorld.get().equals(player.getWorld().getName())) {
            performFreeRescue(player, definition);
            return;
        }
        Optional<String> requiredWorld = definition.requiredWorld().get();
        if (requiredWorld.isPresent() && !requiredWorld.get().equals(player.getWorld().getName())) {
            player.sendMessage(MM.deserialize("<red>Cet objet ne fonctionne pas ici.</red>"));
            return;
        }
        if (definition.hasCooldown()) {
            Instant expiry = cooldownExpiry(playerId, definition.itemId());
            if (expiry != null && expiry.isAfter(Instant.now())) {
                player.sendMessage(MM.deserialize("<red>Cet objet se recharge encore :</red> <white><time></white>",
                        Placeholder.unparsed("time", formatRemaining(Duration.between(Instant.now(), expiry)))));
                return;
            }
        }
        startChanneling(player, definition);
    }

    /**
     * Secours Hub (issue #154) : téléportation immédiate et gratuite, sans canalisation ni
     * cooldown — l'objet reste de toute façon jamais consommé par ce moteur. Un joueur coincé dans
     * un trou doit pouvoir revenir sans attendre ni risquer une annulation par un petit dégât
     * pendant la canalisation normale, qui ne s'applique jamais ici.
     */
    private void performFreeRescue(Player player, ItemTravelDefinition definition) {
        Optional<Location> destination = definition.destination().get();
        if (destination.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Destination indisponible, contacte un administrateur.</red>"));
            logger.warn("Destination indisponible pour un secours gratuit ({}), joueur {}.",
                    definition.itemId(), player.getUniqueId());
            return;
        }
        player.teleportAsync(destination.get());
        player.sendMessage(MM.deserialize("<green>Retour au point d'apparition du Hub.</green>"));
    }

    void handleDamage(Player player) {
        cancelChanneling(player.getUniqueId(), "<red>Voyage annulé : tu as subi des dégâts.</red>");
    }

    void handleQuit(Player player) {
        ChannelSession session = channeling.remove(player.getUniqueId());
        if (session != null && session.task != null) {
            session.task.cancel();
        }
        // Issue #221 : la déconnexion SUSPEND le cooldown — sa durée restante est enregistrée et
        // reprendra à la reconnexion ; le temps hors ligne ne la consomme pas.
        persistRemaining(player.getUniqueId());
        cooldowns.remove(player.getUniqueId());
    }

    private void startChanneling(Player player, ItemTravelDefinition definition) {
        UUID playerId = player.getUniqueId();
        long totalTicks = definition.channelSeconds() * 20L;
        ChannelSession session = new ChannelSession(playerId, definition, player.getLocation().clone(), totalTicks);
        channeling.put(playerId, session);
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
        reportProgress(session);
    }

    private void tick(ChannelSession session) {
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
            cancelChanneling(session.playerId, "<red>Voyage annulé : tu as bougé.</red>");
            return;
        }

        session.elapsedTicks++;
        // Rapporte la progression avant de compléter (jamais après) : le dernier tick doit pouvoir
        // afficher 100%, jamais s'arrêter net à un pourcentage tronqué (ex. 98%) juste avant.
        reportProgress(session);
        if (session.elapsedTicks >= session.totalTicks) {
            complete(session);
        }
    }

    private void reportProgress(ChannelSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null) {
            return;
        }
        int percent = session.totalTicks <= 0 ? 100 : (int) (100L * session.elapsedTicks / session.totalTicks);
        player.sendActionBar(MM.deserialize(
                "<yellow>Voyage :</yellow> <white><percent>%</white>", Placeholder.unparsed("percent", String.valueOf(percent))));
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, player.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.01);
    }

    void cancelChanneling(UUID playerId, String message) {
        ChannelSession session = channeling.remove(playerId);
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            clearIndicator(player);
            if (message != null) {
                player.sendMessage(MM.deserialize(message));
            }
        }
    }

    /** Retire immédiatement l'actionbar de progression — jamais de résidu après succès/annulation. */
    private void clearIndicator(Player player) {
        player.sendActionBar(Component.empty());
    }

    private void complete(ChannelSession session) {
        channeling.remove(session.playerId);
        if (session.task != null) {
            session.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        clearIndicator(player);

        Optional<Location> destination = session.definition.destination().get();
        if (destination.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Destination indisponible, contacte un administrateur.</red>"));
            logger.warn("Destination indisponible pour un voyage par objet ({}), joueur {}.",
                    session.definition.itemId(), session.playerId);
            return;
        }
        player.teleportAsync(destination.get());
        player.sendMessage(MM.deserialize("<green>Voyage réussi.</green>"));
        if (session.definition.hasCooldown()) {
            applyCooldown(session.playerId, session.definition);
        }
    }

    private static final class ChannelSession {
        private final UUID playerId;
        private final ItemTravelDefinition definition;
        private final Location startLocation;
        private final long totalTicks;
        private long elapsedTicks;
        private BukkitTask task;

        private ChannelSession(UUID playerId, ItemTravelDefinition definition, Location startLocation, long totalTicks) {
            this.playerId = playerId;
            this.definition = definition;
            this.startLocation = startLocation;
            this.totalTicks = totalTicks;
        }
    }
}
