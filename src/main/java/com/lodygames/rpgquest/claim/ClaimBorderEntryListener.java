package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.claim.model.Claim;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Détecte l'entrée d'un propriétaire dans <strong>son propre</strong> claim, pour déclencher
 * automatiquement {@link ClaimBorderRenderer#show} (mission « affichage automatique »). Séparée à
 * dessein du renderer lui-même (architecture explicitement demandée : « architecture séparée entre
 * détection d'entrée et renderer afin de pouvoir changer facilement l'effet plus tard ») — cette
 * classe ne sait dessiner rien du tout, uniquement détecter une transition.
 *
 * <p>Édge-triggered comme {@code travel.PortalService#handleMove} : ne réagit qu'à un vrai
 * changement de claim (bloc par bloc, jamais à chaque micro-mouvement de la souris/caméra), donc
 * jamais de spam tant que le joueur marche à l'intérieur du même claim. Sortir (même brièvement)
 * puis revenir réarme la détection — une nouvelle entrée redéclenche l'affichage.</p>
 */
public final class ClaimBorderEntryListener implements Listener {

    private final ClaimService claimService;
    private final ClaimBorderRenderer renderer;
    private final Map<UUID, String> currentClaimIdByPlayer = new ConcurrentHashMap<>();

    public ClaimBorderEntryListener(ClaimService claimService, ClaimBorderRenderer renderer) {
        this.claimService = claimService;
        this.renderer = renderer;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null
                || (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ())) {
            return; // même bloc qu'avant (juste un changement de vue) : jamais une transition de claim.
        }

        Player player = event.getPlayer();
        Optional<Claim> claimHere = claimService.claimAt(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        String newClaimId = claimHere.map(Claim::id).orElse(null);
        String previousClaimId = currentClaimIdByPlayer.get(player.getUniqueId());
        if (Objects.equals(newClaimId, previousClaimId)) {
            return;
        }

        if (newClaimId == null) {
            currentClaimIdByPlayer.remove(player.getUniqueId());
            return;
        }
        currentClaimIdByPlayer.put(player.getUniqueId(), newClaimId);

        Claim claim = claimHere.get();
        if (claim.owner().equals(player.getUniqueId())) {
            renderer.show(player, claim);
        }
        // Entrée dans le claim d'un autre joueur (visiteur) : jamais de rendu, mission « ne pas
        // envoyer les particules aux visiteurs » — le propriétaire du claim visité ne reçoit non
        // plus rien ici puisqu'il n'est pas le joueur qui bouge.
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        currentClaimIdByPlayer.remove(event.getPlayer().getUniqueId());
    }
}
