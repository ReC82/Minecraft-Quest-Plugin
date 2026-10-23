package com.lodygames.rpgquest.web.link;

import com.lodygames.rpgquest.bootstrap.PluginService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Envoie au portail la progression personnelle des joueurs <strong>reliés</strong> (issue #53) :
 * juste après un {@code /lien} réussi, à la déconnexion, et toutes les
 * {@code progress-interval-minutes} pour les joueurs connectés.
 *
 * <p>Aucune statistique d'un joueur non relié ne quitte le serveur : tant que son état est inconnu,
 * seule une <em>sonde</em> (son UUID, sans rien d'autre) demande au portail s'il est relié. La
 * réponse est retenue jusqu'au redémarrage ; un {@code /lien} réussi la remplace, une réponse
 * {@code NOT_LINKED} à un envoi complet (dissociation faite sur le site) aussi. Un seul envoi en vol
 * par joueur ; tout passe hors du thread principal ; rien n'est journalisé du contenu.</p>
 */
public final class LinkedProgressPublisher implements PluginService, Listener {

    /** Envoi effectif, injectable (tests) : {@code (uuid, pseudo, progression ou null pour une sonde)}. */
    @FunctionalInterface
    public interface Sender {
        CompletableFuture<ProgressReply> send(UUID player, String name, Map<String, Object> progress);
    }

    private final Plugin plugin;
    private final Sender sender;
    private final Function<UUID, CompletableFuture<Map<String, Object>>> builder;
    private final int intervalMinutes;
    private final Logger logger;
    private final Set<UUID> linked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notLinked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public LinkedProgressPublisher(Plugin plugin, Sender sender,
                                   Function<UUID, CompletableFuture<Map<String, Object>>> builder,
                                   int intervalMinutes, Logger logger) {
        this.plugin = plugin;
        this.sender = sender;
        this.builder = builder;
        this.intervalMinutes = intervalMinutes;
        this.logger = logger;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (intervalMinutes > 0) {
            long period = intervalMinutes * 60L * 20L;
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    publish(player.getUniqueId(), player.getName());
                }
            }, period, period);
        }
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        publish(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    /** Appelé après un {@code /lien} accepté : le portail vient de confirmer la liaison. */
    public void onLinked(UUID player, String name) {
        notLinked.remove(player);
        linked.add(player);
        publish(player, name);
    }

    /**
     * Envoie la progression si le joueur est relié, sonde d'abord si son état est inconnu. Rend
     * l'envoi en cours (terminé tout de suite si rien n'est à faire).
     */
    public CompletableFuture<ProgressReply> publish(UUID player, String name) {
        if (notLinked.contains(player) || !inFlight.add(player)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<ProgressReply> probe = linked.contains(player)
                ? CompletableFuture.completedFuture(ProgressReply.LINKED)
                : sender.send(player, name, null);
        return probe.thenCompose(reply -> {
            if (reply != ProgressReply.LINKED) {
                return CompletableFuture.completedFuture(reply);
            }
            linked.add(player);
            return builder.apply(player).thenCompose(progress -> sender.send(player, name, progress));
        }).handle((reply, error) -> {
            inFlight.remove(player);
            if (error != null) {
                logger.warn("Progression portail non envoyée pour {} : {}", name, error.toString());
                return ProgressReply.UNAVAILABLE;
            }
            if (reply == ProgressReply.NOT_LINKED) {
                linked.remove(player);
                notLinked.add(player);
            }
            return reply;
        });
    }

    boolean knownLinked(UUID player) {
        return linked.contains(player);
    }

    boolean knownNotLinked(UUID player) {
        return notLinked.contains(player);
    }
}
