package com.lodygames.rpgquest.mod;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.ModCompatConfig;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Détecte la compatibilité avec le mod client prototype (mission étape 23,
 * point 4 : canal de plugin messaging {@value #HANDSHAKE_CHANNEL}) et
 * applique la politique configurée (point 9). Toute la logique critique
 * reste ici, entièrement testable sans lancer de client réel (mission,
 * validation) : {@link HandshakeProtocol} fait l'encodage/décodage pur,
 * cette classe n'orchestre que le cycle de vie Bukkit (canaux, événements,
 * délai d'attente).
 *
 * <p><b>Sécurité (mission point 7)</b> : le canal de handshake ne
 * transporte jamais qu'un entier magique et un numéro de version — jamais
 * une déclaration d'état de jeu. Aucun code de ce fichier ne modifie
 * jamais la progression, un drop, un solde, un droit ou un achat en
 * réaction à un message reçu du client ; ces systèmes restent entièrement
 * gouvernés par leurs propres services (mission point 6).</p>
 */
public final class ModCompatService implements PluginService, Listener, PluginMessageListener {

    public static final String HANDSHAKE_CHANNEL = "rpgquest:handshake_hello";
    public static final String COSMETIC_CHANNEL = "rpgquest:mob_variant_tag";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Supplier<ModCompatConfig> config;
    private final Logger logger;

    private final Map<UUID, PlayerModStatus> statuses = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> timeouts = new ConcurrentHashMap<>();

    public ModCompatService(Plugin plugin, Supplier<ModCompatConfig> config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void start() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, HANDSHAKE_CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, HANDSHAKE_CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, COSMETIC_CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        Messenger messenger = Bukkit.getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, HANDSHAKE_CHANNEL);
        messenger.unregisterIncomingPluginChannel(plugin, HANDSHAKE_CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, COSMETIC_CHANNEL);
        for (BukkitTask task : timeouts.values()) {
            task.cancel();
        }
        timeouts.clear();
        statuses.clear();
    }

    /** {@link PlayerModStatus#PENDING} pour un joueur jamais vu (pas encore rejoint, ou déjà reparti). */
    public PlayerModStatus status(UUID playerId) {
        return statuses.getOrDefault(playerId, PlayerModStatus.PENDING);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        // Reconnexion (mission, test dédié) : jamais d'état hérité d'une session précédente,
        // chaque connexion relance un handshake complet.
        statuses.put(playerId, PlayerModStatus.PENDING);
        player.sendPluginMessage(plugin, HANDSHAKE_CHANNEL, HandshakeProtocol.encodeHello());

        int timeoutTicks = config.get().handshakeTimeoutTicks();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            timeouts.remove(playerId);
            if (statuses.get(playerId) == PlayerModStatus.PENDING) {
                // Aucune réponse dans le délai (mission, test "client vanilla") : traité comme
                // absence de mod, jamais une erreur ni un blocage de connexion.
                statuses.put(playerId, PlayerModStatus.NO_MOD);
                enforcePolicy(player, PlayerModStatus.NO_MOD);
            }
        }, timeoutTicks);
        timeouts.put(playerId, task);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        statuses.remove(playerId);
        BukkitTask task = timeouts.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!HANDSHAKE_CHANNEL.equals(channel)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        BukkitTask task = timeouts.remove(playerId);
        if (task != null) {
            task.cancel();
        }

        Optional<HandshakeProtocol.HelloResponse> decoded = HandshakeProtocol.decodeHelloResponse(message);
        PlayerModStatus status;
        if (decoded.isEmpty() || decoded.get().magic() != HandshakeProtocol.PROTOCOL_MAGIC) {
            // Paquet réseau invalide (mission, test dédié) : jamais une exception, jamais interprété
            // comme un handshake valide.
            status = PlayerModStatus.NO_MOD;
            logger.warn("Paquet de handshake invalide reçu de {} (longueur={}).", player.getName(), message.length);
        } else {
            status = decoded.get().clientProtocolVersion() == HandshakeProtocol.SERVER_PROTOCOL_VERSION
                    ? PlayerModStatus.COMPATIBLE
                    : PlayerModStatus.WRONG_VERSION;
        }
        statuses.put(playerId, status);
        enforcePolicy(player, status);
    }

    private void enforcePolicy(Player player, PlayerModStatus status) {
        if (status == PlayerModStatus.COMPATIBLE || !config.get().requireMod() || !player.isOnline()) {
            // Client vanilla autorisé avec repli par défaut (mission point 9) : rien à faire, le
            // joueur continue normalement, simplement sans le contenu cosmétique du mod.
            return;
        }
        String reason = status == PlayerModStatus.WRONG_VERSION
                ? "<red>Le mod RPGQuest installé n'est pas à jour. Mets-le à jour puis reconnecte-toi.</red>"
                : "<red>Le mod RPGQuest est requis sur ce serveur. Voir docs/CLIENT_MOD.md pour l'installation.</red>";
        player.kick(MM.deserialize(reason));
    }

    /**
     * Diffuse un marquage purement cosmétique (mission point 5) — n'accorde jamais rien, affiché
     * seulement si le mod du joueur est détecté compatible.
     */
    public void sendMobVariantTag(Player player, int entityNetworkId, String variantDisplayName) {
        if (status(player.getUniqueId()) != PlayerModStatus.COMPATIBLE) {
            return;
        }
        player.sendPluginMessage(plugin, COSMETIC_CHANNEL,
                HandshakeProtocol.encodeMobVariantTag(entityNetworkId, variantDisplayName));
    }
}
