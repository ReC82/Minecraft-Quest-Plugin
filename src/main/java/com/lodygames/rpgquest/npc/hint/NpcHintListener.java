package com.lodygames.rpgquest.npc.hint;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Cycle de vie du signal visuel sur les PNJ (issue #12).
 *
 * <p>Trois moments, trois raisons :</p>
 * <ul>
 *   <li><strong>connexion</strong> — charger l'état de lecture persistant une fois, pour que la
 *       boucle d'affichage n'interroge jamais la base ;</li>
 *   <li><strong>déconnexion</strong> — libérer la mémoire du joueur. La lecture reste en base, donc
 *       rien n'est perdu ;</li>
 *   <li><strong>changement de monde</strong> — l'état calculé portait sur d'autres PNJ : il est
 *       invalidé immédiatement plutôt que d'attendre l'expiration du cache.</li>
 * </ul>
 *
 * <p>Priorité {@code MONITOR} et {@code ignoreCancelled} : ce listener observe, il ne décide de
 * rien et ne doit jamais influencer un autre plugin.</p>
 */
public final class NpcHintListener implements Listener {

    private final NpcHintService service;

    public NpcHintListener(NpcHintService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.onWorldChange(event.getPlayer().getUniqueId());
    }
}
