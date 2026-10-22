package com.lodygames.rpgquest.web.push;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.web.Json;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Relevé d'état agrégé envoyé au portail AWS (issue #421) : « le serveur est en ligne, N joueurs
 * sur M », toutes les {@code status-interval-seconds} secondes, <strong>indépendamment</strong> de
 * {@code web-export.enabled}. C'est ce qui permet au portail d'annoncer un état réel sans publier
 * la moindre donnée de joueur : ni pseudo, ni UUID, ni position — le corps est en périmètre fermé
 * et le portail refuse tout champ supplémentaire.
 *
 * <p>Les compteurs sont lus sur le thread principal (deux appels Bukkit en O(1)), l'envoi part d'un
 * fil dédié : jamais de réseau sur le thread principal. Un envoi encore en cours fait sauter le
 * suivant (pas de file qui grossit pendant une panne). Chaque relevé porte un {@code bootId} tiré au
 * hasard à chaque démarrage et un {@code sequence} croissant : le portail ignore doublons et
 * retards. À l'arrêt, un dernier relevé {@code online = false} annonce l'arrêt.</p>
 */
public final class ServerStatusReporter implements PluginService {

    static final int SCHEMA_VERSION = 1;
    private static final long STOP_WAIT_SECONDS = 5;

    private final Plugin plugin;
    private final SnapshotPushClient client;
    private final int intervalSeconds;
    private final Clock clock;
    private final String bootId;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean sending = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RPGQuest-PortalStatus");
        thread.setDaemon(true);
        return thread;
    });
    private BukkitTask task;

    public ServerStatusReporter(Plugin plugin, SnapshotPushClient client, int intervalSeconds) {
        this(plugin, client, intervalSeconds, Clock.systemUTC(), newBootId());
    }

    ServerStatusReporter(Plugin plugin, SnapshotPushClient client, int intervalSeconds, Clock clock, String bootId) {
        this.plugin = plugin;
        this.client = client;
        this.intervalSeconds = intervalSeconds;
        this.clock = clock;
        this.bootId = bootId;
    }

    @Override
    public void start() {
        long period = intervalSeconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> submit(true, Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()), 20L, period);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        String json = nextReport(false, 0, Bukkit.getMaxPlayers());
        try {
            executor.execute(() -> client.push(json));
        } catch (RejectedExecutionException e) {
            // déjà arrêté
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** Programme un envoi sur le fil dédié, sauf si le précédent n'est pas terminé. */
    boolean submit(boolean online, int playerCount, int maxPlayers) {
        if (!sending.compareAndSet(false, true)) {
            return false;
        }
        String json = nextReport(online, playerCount, maxPlayers);
        try {
            executor.execute(() -> {
                try {
                    client.push(json);
                } finally {
                    sending.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            sending.set(false);
            return false;
        }
    }

    /** Un envoi est en cours sur le fil dédié (tests). */
    boolean isSending() {
        return sending.get();
    }

    String nextReport(boolean online, int playerCount, int maxPlayers) {
        return Json.write(report(bootId, sequence.incrementAndGet(), clock.instant(), online, playerCount, maxPlayers));
    }

    /** Corps exact du relevé : les seules clés que le portail accepte (périmètre fermé). */
    static Map<String, Object> report(String bootId, long sequence, Instant generatedAt, boolean online,
                                      int playerCount, int maxPlayers) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("kind", "status");
        root.put("generatedAt", generatedAt.toString());
        root.put("bootId", bootId);
        root.put("sequence", sequence);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("online", online);
        server.put("playerCount", Math.max(0, playerCount));
        server.put("maxPlayers", Math.max(0, maxPlayers));
        root.put("server", server);
        return root;
    }

    /** Identifiant de démarrage aléatoire (aucun lien avec un joueur ni avec la machine). */
    static String newBootId() {
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        return "boot-" + HexFormat.of().formatHex(random);
    }
}
