package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.ClickedFace;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.permission.RpgPermissions;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clic droit avec l'outil d'emplacement : <strong>prépare</strong> un emplacement et demande son nom
 * (issues #213 et #227).
 *
 * <h2>Le clic n'écrit plus rien</h2>
 *
 * <p>Avant #227, il enregistrait immédiatement : un clic de travers créait un emplacement qu'il
 * fallait ensuite aller supprimer depuis le Control Panel, et la validation manuelle a montré que
 * c'est précisément ce qui arrive. Le clic calcule désormais l'ancre et l'orientation, les retient
 * dans {@link PendingBuildingSiteRegistry}, et ouvre une fenêtre de saisie du nom. Rien n'est écrit
 * avant la confirmation — un missclick se referme et ne laisse rien.</p>
 *
 * <h2>Ce que le clic calcule</h2>
 *
 * <p>Monde du joueur, ancre résolue par {@link BuildingSiteAnchor} (la case libre contre la face
 * cliquée) et orientation cardinale déduite du regard. <strong>Aucune saisie dans le chat</strong> :
 * le nom se tape dans une enclume vanilla.</p>
 *
 * <h2>Pourquoi l'événement est annulé</h2>
 *
 * <p>Sans cela, le clic droit d'une houe laboure la terre et le clic sur un coffre l'ouvre. L'outil
 * ne doit faire qu'une chose. La main secondaire est ignorée pour ne pas traiter deux fois le même
 * geste — Paper émet un événement par main.</p>
 *
 * <h2>Priorité et {@code ignoreCancelled = false}, comme l'outil de zone</h2>
 *
 * <p>L'outil doit rester utilisable là où un autre plugin de protection annule l'interaction avant
 * nous. Ce n'est pas un risque de conflit : {@link BuildingSiteTool#isTool} n'identifie l'objet que
 * par PDC, donc ce listener ne voit jamais le clic de quelqu'un d'autre.</p>
 *
 * <h2>Le clic gauche ne crée rien, volontairement</h2>
 *
 * <p>Il est simplement annulé quand on tient l'outil (pour ne pas casser de bloc avec lui) et
 * rappelle la bonne manipulation. Réserver la création au clic droit évite d'en créer un par
 * réflexe en minant.</p>
 */
public final class BuildingSiteToolListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BuildingSiteService service;
    private final PendingBuildingSiteRegistry pendings;

    public BuildingSiteToolListener(BuildingSiteService service,
                                    PendingBuildingSiteRegistry pendings) {
        this.service = service;
        this.pendings = pendings;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!BuildingSiteTool.isTool(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();

        // La permission se vérifie AVANT de regarder le clic : un joueur ordinaire qui récupérerait
        // l'outil (mort d'un administrateur, coffre, /give) ne doit rien pouvoir créer, et doit le
        // comprendre plutôt que de cliquer dans le vide.
        if (!RpgPermissions.canManageBuildingSites(player)) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                    || event.getAction() == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                player.sendMessage(MM.deserialize(
                        "<red>Permission manquante :</red> <white><permission></white>",
                        Placeholder.unparsed("permission", RpgPermissions.ADMIN_BUILD_SITE)));
            }
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            player.sendMessage(MM.deserialize(
                    "<gray>Clic <white>droit</white> sur un bloc pour créer un emplacement.</gray>"));
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        event.setCancelled(true);

        ClickedFace face = ClickedFace.of(
                event.getBlockFace() == null ? null : event.getBlockFace().name());
        BuildingSiteAnchor anchor = BuildingSiteAnchor.resolve(
                clicked.getX(), clicked.getY(), clicked.getZ(), face);

        // Les limites réelles du monde ne sont connues que d'ici : cliquer le dessus du bloc le plus
        // haut d'un monde donnerait une ancre hors limites, qu'aucun placement futur ne pourrait
        // utiliser. On refuse tout de suite, en le disant.
        World world = clicked.getWorld();
        if (anchor.y() < world.getMinHeight() || anchor.y() >= world.getMaxHeight()) {
            player.sendMessage(MM.deserialize(
                    "<red>Ancre hors des limites du monde</red> <white><world></white> "
                            + "<gray>(<min> à <max>) : rien n'a été créé.</gray>",
                    Placeholder.unparsed("world", world.getName()),
                    Placeholder.unparsed("min", String.valueOf(world.getMinHeight())),
                    Placeholder.unparsed("max", String.valueOf(world.getMaxHeight() - 1))));
            return;
        }

        Facing facing = Facing.fromYaw(player.getLocation().getYaw());
        String worldName = world.getName();
        String playerKey = player.getUniqueId().toString();

        // Un clic droit émet couramment deux événements : sans cette fenêtre, le second rouvrirait la
        // fenêtre de saisie par-dessus la première, et la fermeture de celle-ci annulerait la demande
        // que le joueur est en train de nommer.
        if (!service.acceptClick(playerKey)) {
            return;
        }

        // Un emplacement déjà posé exactement ici : inutile d'ouvrir une fenêtre de nom pour
        // découvrir ensuite qu'il n'y a rien à créer.
        var existing = service.at(worldName, anchor.x(), anchor.y(), anchor.z());
        if (existing.isPresent()) {
            player.sendMessage(MM.deserialize(
                    "<yellow>Un emplacement existe déjà ici :</yellow> <white><id></white> "
                            + "<gray>(« <name> »). Rien à créer.</gray>",
                    Placeholder.unparsed("id", existing.get().id()),
                    Placeholder.unparsed("name", existing.get().name())));
            return;
        }

        // Voisin immédiat : on AVERTIT, on ne refuse pas. Deux emplacements côte à côte peuvent être
        // légitimes ; mais à un bloc près, c'est bien plus souvent un clic de travers — et le dire
        // avant que le joueur ne valide ne coûte qu'une ligne.
        var adjacent = service.adjacentTo(worldName, anchor.x(), anchor.y(), anchor.z());
        if (!adjacent.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<gold>Attention :</gold> <gray>un autre emplacement est à 1 bloc — "
                            + "<white><name></white> (<id>). Fermez la fenêtre si c'était un clic "
                            + "de travers.</gray>",
                    Placeholder.unparsed("name", adjacent.get(0).name()),
                    Placeholder.unparsed("id", adjacent.get(0).id())));
        }

        // Rien n'est écrit ici : on prépare, et on demande un nom. C'est tout l'objet de #227.
        PendingBuildingSite pendingSite = pendings.open(playerKey, worldName, anchor, facing,
                clicked.getX(), clicked.getY(), clicked.getZ());
        if (BuildingSiteNamePrompt.open(player, pendingSite).isEmpty()) {
            pendings.cancel(playerKey);
            player.sendMessage(MM.deserialize(
                    "<red>Impossible d'ouvrir la fenêtre de saisie du nom.</red>"
                            + "<gray> Rien n'a été créé.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gray>Emplacement préparé en <white><world> <pos></white>, orienté "
                        + "<white><facing></white>.</gray>"
                        + "<newline><gray>Donnez-lui un nom dans l'enclume, puis cliquez le "
                        + "résultat. Fermer la fenêtre annule.</gray>",
                Placeholder.unparsed("world", worldName),
                Placeholder.unparsed("pos", pendingSite.positionLabel()),
                Placeholder.unparsed("facing", facing.label())));
    }
}
