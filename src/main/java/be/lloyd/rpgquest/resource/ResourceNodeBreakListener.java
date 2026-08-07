package be.lloyd.rpgquest.resource;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Traduit {@link BlockBreakEvent} en appel à {@link ResourceNodeService} —
 * même organisation que {@code QuestBlockBreakListener}/{@code
 * WeaponBehaviorListener} : le listener ne connaît que la conversion
 * événement Bukkit → appel de méthode, toute la logique vit dans le service.
 * {@code ignoreCancelled = true} et une vérification explicite en début de
 * {@link ResourceNodeService#handleBreak} (règle 1, même redondance que les
 * comportements d'équipement) : un événement déjà annulé par un autre plugin
 * (protection de région, etc.) n'est jamais traité comme une récolte de nœud.
 */
public final class ResourceNodeBreakListener implements Listener {

    private final ResourceNodeService service;

    public ResourceNodeBreakListener(ResourceNodeService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        service.handleBreak(event);
    }
}
