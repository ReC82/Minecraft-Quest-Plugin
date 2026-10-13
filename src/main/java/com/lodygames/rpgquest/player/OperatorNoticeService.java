package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Issue #316 — prévient le joueur, en privé, quand ses droits opérateur Minecraft (OP) changent
 * depuis PlugAdmin.
 *
 * <ul>
 *   <li>Seulement après un changement <strong>réel</strong> (état relu) : jamais pour une demande
 *       refusée, en échec ou sans effet.</li>
 *   <li>Joueur connecté : message privé immédiat (jamais diffusé au serveur).</li>
 *   <li>Joueur absent : rien n'est prétendu livré. À sa prochaine connexion, s'il est dans un autre
 *       état que le dernier qu'on lui a annoncé, il reçoit <strong>un seul</strong> message qui
 *       résume l'état final (« activés » ou « retirés ») — jamais la suite des changements. La
 *       variable {@value #NOTICE_KEY} retient ce dernier état annoncé.</li>
 * </ul>
 *
 * <p>Ne parle que d'OP Minecraft : ni du rôle PlugAdmin, ni des droits de construction, ni d'un mode
 * bypass.</p>
 */
public final class OperatorNoticeService implements Listener {

    static final String NOTICE_KEY = "OP_NOTICE_STATE";

    /** Ce qui a été fait du message, pour le dire au panel. */
    public enum Delivery { DELIVERED, DEFERRED }

    private final JavaPlugin plugin;
    private final PlayerVariableRepository variables;
    private final QuestMessagesService messages;

    public OperatorNoticeService(JavaPlugin plugin, PlayerVariableRepository variables, QuestMessagesService messages) {
        this.plugin = plugin;
        this.variables = variables;
        this.messages = messages;
    }

    /** Thread principal, après un changement OP relu. */
    public Delivery changed(UUID playerId, boolean before, boolean now) {
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null) {
            online.sendMessage(messages.current().format(now ? "player.op-granted" : "player.op-revoked"));
            remember(playerId, now);
            return Delivery.DELIVERED;
        }
        // Absent : on garde l'état qu'il connaissait (s'il n'y en a pas encore), pour comparer à son retour.
        variables.get(playerId, NOTICE_KEY).thenAccept(known -> {
            if (known.isEmpty()) {
                remember(playerId, before);
            }
        });
        return Delivery.DEFERRED;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        variables.get(id, NOTICE_KEY).thenAccept(known -> known.ifPresent(state ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = plugin.getServer().getPlayer(id);
                    if (player == null) {
                        return;
                    }
                    boolean now = player.isOp();
                    if (Boolean.parseBoolean(state) != now) {
                        player.sendMessage(messages.current().format(now ? "player.op-granted-while-away"
                                : "player.op-revoked-while-away"));
                        remember(id, now);
                    }
                })));
    }

    private void remember(UUID playerId, boolean state) {
        variables.set(playerId, NOTICE_KEY, String.valueOf(state)).exceptionally(error -> {
            plugin.getSLF4JLogger().warn("OP : état annoncé non mémorisé pour {} ({})", playerId, error.toString());
            return null;
        });
    }
}
