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
 * Clic droit avec l'outil d'emplacement : crée un emplacement de construction (issue #213).
 *
 * <h2>Ce que le clic produit</h2>
 *
 * <p>Monde du joueur, ancre résolue par {@link BuildingSiteAnchor} (la case libre contre la face
 * cliquée), orientation cardinale déduite du regard, identifiant automatique
 * {@code buildsite_0001}, nom par défaut. <strong>Aucune saisie dans le chat</strong> : le
 * renommage se fait depuis le Control Panel, qui est fait pour ça.</p>
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

    public BuildingSiteToolListener(BuildingSiteService service) {
        this.service = service;
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

        service.create(worldName, anchor, facing, player.getName(),
                        player.getUniqueId().toString())
                .thenAccept(result -> announce(player, result, worldName, anchor, facing))
                .exceptionally(error -> {
                    player.sendMessage(MM.deserialize(
                            "<red>Création impossible :</red> <white><cause></white>"
                                    + "<gray> — rien n'a été enregistré.</gray>",
                            Placeholder.unparsed("cause", rootName(error))));
                    return null;
                });
    }

    /**
     * Le retour au joueur. Il nomme l'identifiant attribué, parce que c'est lui qu'il retrouvera
     * dans le Control Panel — pas le nom par défaut, identique pour tous.
     *
     * <p>Un clic absorbé par l'anti-rebond ne dit <strong>rien</strong> : c'était le même geste, et
     * afficher « ignoré » à chaque double clic apprendrait à ignorer les messages de l'outil.</p>
     */
    private void announce(Player player, BuildingSiteService.CreateResult result, String world,
                          BuildingSiteAnchor anchor, Facing facing) {
        switch (result.outcome()) {
            case CREATED -> player.sendMessage(MM.deserialize(
                    "<green>Emplacement créé :</green> <white><id></white> "
                            + "<gray>— <world> <x>/<y>/<z>, orienté <facing>.</gray>"
                            + "<newline><gray>Renommez-le depuis le Control Panel "
                            + "(Bâtiments → Emplacements).</gray>",
                    Placeholder.unparsed("id", result.site().id()),
                    Placeholder.unparsed("world", world),
                    Placeholder.unparsed("x", String.valueOf(anchor.x())),
                    Placeholder.unparsed("y", String.valueOf(anchor.y())),
                    Placeholder.unparsed("z", String.valueOf(anchor.z())),
                    Placeholder.unparsed("facing", facing.label())));
            case ALREADY_THERE -> player.sendMessage(MM.deserialize(
                    "<yellow>Un emplacement existe déjà ici :</yellow> <white><id></white> "
                            + "<gray>(« <name> »). Aucun second emplacement n'a été créé.</gray>",
                    Placeholder.unparsed("id", result.site().id()),
                    Placeholder.unparsed("name", result.site().name())));
            case DEBOUNCED -> {
                // Volontairement muet : voir le commentaire de méthode.
            }
        }
    }

    private static String rootName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
