package com.lodygames.rpgquest.storage;

import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clic droit sur un point d'accès (issue #105) : l'ouverture du conteneur physique est toujours
 * annulée — y compris pour un builder ou un administrateur — et remplacée par le coffre personnel
 * de celui qui clique. Le contenu physique du bloc n'est donc jamais montré à personne.
 *
 * <p>Les deux mains déclenchent un événement : les deux sont annulés (sinon la main secondaire
 * pourrait encore ouvrir ou poser un bloc), seul celui de la main principale ouvre le coffre.</p>
 *
 * <p>Priorité {@code LOWEST} : un point d'accès est déclaré par un administrateur et n'est pas un
 * conteneur public, donc le drapeau de zone {@code public-containers: false} (priorité
 * {@code LOW}, qui ignore les événements déjà annulés) ne doit pas l'empêcher. Un refus posé
 * encore plus tôt est respecté ; les claims sont exclus d'office (liaison refusée dans
 * {@code claims.world}).</p>
 */
final class VaultAccessListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final VaultAccessPointService service;

    VaultAccessListener(VaultAccessPointService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Optional<VaultId> vaultId = service.vaultAt(block);
        if (vaultId.isEmpty()) {
            return; // coffre vanilla non déclaré : comportement normal.
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        service.open(event.getPlayer(), vaultId.get()).thenAccept(outcome -> {
            if (!event.getPlayer().isOnline()) {
                return;
            }
            if (outcome == PersonalVaultService.OpenOutcome.LOCKED) {
                // Générique à dessein : ne révèle ni la quête ni l'étape qui le débloque.
                event.getPlayer().sendMessage(MM.deserialize(
                        "<gold>🔒 Ce coffre est encore verrouillé.</gold> <gray>Il se débloquera au fil de ta "
                                + "progression dans LodyQuests. Tes coffres déjà ouverts restent accessibles.</gray>"));
            } else if (outcome == PersonalVaultService.OpenOutcome.LOAD_FAILED) {
                event.getPlayer().sendMessage(MM.deserialize(
                        "<red>Ton coffre personnel est momentanément indisponible. Préviens un administrateur ; "
                                + "rien n'a été perdu.</red>"));
            }
        });
    }
}
