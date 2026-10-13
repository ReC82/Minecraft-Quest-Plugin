package com.lodygames.rpgquest.city;

import com.lodygames.rpgquest.permission.RpgPermissions;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

/**
 * Clic droit avec l'outil de ville, et saisie du nom (issue #178). Même parcours que l'outil
 * d'emplacement (#213/#227) : le clic <strong>prépare</strong>, seul le clic sur le résultat de
 * l'enclume écrit.
 *
 * <p>Le centre est la colonne du bloc cliqué ; l'altitude retenue est la case au-dessus de ce bloc
 * (là où l'on se tient), utilisée comme point d'arrivée du menu de voyage « Villages ». L'orientation
 * du regard est conservée pour cette arrivée. Aucun bloc n'est modifié, aucun PNJ ni bâtiment
 * n'est posé.</p>
 */
public final class CityToolListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final long CLICK_DEBOUNCE_MS = 400;

    private final CityRegistry cities;
    private final PendingCityRegistry pendings;
    private final ConcurrentHashMap<String, Long> lastClick = new ConcurrentHashMap<>();

    public CityToolListener(CityRegistry cities, PendingCityRegistry pendings) {
        this.cities = cities;
        this.pendings = pendings;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !CityTool.isTool(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        if (!RpgPermissions.canManageCities(player)) {
            player.sendMessage(MM.deserialize("<red>Permission manquante :</red> <white><permission></white>",
                    Placeholder.unparsed("permission", RpgPermissions.ADMIN_CITY)));
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            player.sendMessage(MM.deserialize(
                    "<gray>Clic <white>droit</white> sur un bloc pour choisir le centre d'une ville.</gray>"));
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        String key = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        Long previous = lastClick.put(key, now);
        if (previous != null && now - previous < CLICK_DEBOUNCE_MS) {
            return;
        }
        String world = clicked.getWorld().getName();
        double x = clicked.getX() + 0.5;
        double y = clicked.getY() + 1;
        double z = clicked.getZ() + 0.5;
        float yaw = player.getLocation().getYaw();

        var armed = pendings.takeMove(key);
        if (armed.isPresent()) {
            moveCenter(player, armed.get().cityId(), world, x, y, z, yaw);
            return;
        }
        var pending = pendings.openCreate(key, world, x, y, z, yaw);
        if (CityNamePrompt.open(player, pending).isEmpty()) {
            pendings.cancelCreate(key);
            player.sendMessage(MM.deserialize("<red>Impossible d'ouvrir la fenêtre de saisie du nom.</red>"
                    + "<gray> Rien n'a été créé.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gray>Centre choisi en <white><world> <pos></white>. Donnez un nom à la ville dans "
                        + "l'enclume, puis cliquez le résultat. Fermer la fenêtre annule.</gray>",
                Placeholder.unparsed("world", world), Placeholder.unparsed("pos", pending.positionLabel())));
    }

    private void moveCenter(Player player, String cityId, String world, double x, double y, double z, float yaw) {
        cities.move(cityId, world, x, y, z, yaw, 0f).thenAccept(result -> {
            if (!result.ok()) {
                player.sendMessage(MM.deserialize("<red><error></red> <gray>Le centre n'a pas bougé.</gray>",
                        Placeholder.unparsed("error", result.error())));
                return;
            }
            VillageCenter city = result.city();
            CityPerimeter.Bounds b = CityPerimeter.bounds(city);
            player.sendMessage(MM.deserialize(
                    "<green>Centre de la ville déplacé :</green> <white><name></white> <gray>(<id>)</gray>"
                            + "<newline><gray>Nouveau centre : <world> <cx> / <cz> — limites X <minx>…<maxx>, "
                            + "Z <minz>…<maxz></gray>",
                    Placeholder.unparsed("name", city.name()), Placeholder.unparsed("id", city.id()),
                    Placeholder.unparsed("world", city.world()),
                    Placeholder.unparsed("cx", String.valueOf(city.blockX())),
                    Placeholder.unparsed("cz", String.valueOf(city.blockZ())),
                    Placeholder.unparsed("minx", String.valueOf(b.minX())),
                    Placeholder.unparsed("maxx", String.valueOf(b.maxX())),
                    Placeholder.unparsed("minz", String.valueOf(b.minZ())),
                    Placeholder.unparsed("maxz", String.valueOf(b.maxZ()))));
        });
    }

    // ---- Enclume -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPrepare(PrepareAnvilEvent event) {
        Inventory top = event.getInventory();
        if (!CityNamePrompt.isPromptItem(top.getItem(0))) {
            return;
        }
        event.setResult(CityNamePrompt.resultItem(CityNamePrompt.typedName(top)));
        event.getView().setRepairCost(0);
        event.getView().setMaximumRepairCost(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!CityNamePrompt.isPromptItem(top.getItem(0))) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() != CityNamePrompt.RESULT_SLOT || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String key = player.getUniqueId().toString();
        String typed = CityNamePrompt.typedName(top);
        var pending = pendings.takeCreate(key).orElse(null);
        if (pending == null) {
            player.closeInventory();
            player.sendMessage(MM.deserialize("<red>Demande expirée ou déjà traitée :</red> "
                    + "<gray>rien n'a été enregistré. Recliquez avec l'outil.</gray>"));
            return;
        }
        CityName.Checked checked = CityName.check(typed);
        if (!checked.ok()) {
            // Le nom est corrigeable sans recliquer dans le monde : la demande est remise.
            pendings.openCreate(key, pending.world(), pending.x(), pending.y(), pending.z(), pending.yaw());
            player.sendMessage(MM.deserialize("<red><error></red>", Placeholder.unparsed("error", checked.error())));
            return;
        }
        player.closeInventory();
        cities.create(checked.name(), pending.world(), pending.x(), pending.y(), pending.z(), pending.yaw(), 0f)
                .thenAccept(result -> announce(player, result));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        if (!CityNamePrompt.isPromptItem(event.getView().getTopInventory().getItem(0))) {
            return;
        }
        HumanEntity who = event.getPlayer();
        String key = who.getUniqueId().toString();
        if (!pendings.hadCreate(key)) {
            return;
        }
        pendings.cancelCreate(key);
        who.sendMessage(MM.deserialize("<gray>Création annulée : <white>aucune ville n'a été créée</white>.</gray>"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String key = event.getPlayer().getUniqueId().toString();
        pendings.forget(key);
        lastClick.remove(key);
    }

    private void announce(Player player, CityRegistry.Result result) {
        if (!result.ok()) {
            player.sendMessage(MM.deserialize("<red><error></red> <gray>Rien n'a été enregistré.</gray>",
                    Placeholder.unparsed("error", result.error())));
            return;
        }
        VillageCenter city = result.city();
        CityPerimeter.Bounds b = CityPerimeter.bounds(city);
        player.sendMessage(MM.deserialize(
                "<green>Ville créée :</green> <white><name></white>"
                        + "<newline><gray>Identifiant : <id></gray>"
                        + "<newline><gray>Centre : <world> <cx> / <cz></gray>"
                        + "<newline><gray>La ville s'étend de <d> blocs dans chaque direction depuis son centre "
                        + "(<w> × <w> blocs).</gray>"
                        + "<newline><gray>Taille, nom et activation : PlugAdmin › Villes.</gray>",
                Placeholder.unparsed("name", city.name()), Placeholder.unparsed("id", city.id()),
                Placeholder.unparsed("world", city.world()),
                Placeholder.unparsed("cx", String.valueOf(city.blockX())),
                Placeholder.unparsed("cz", String.valueOf(city.blockZ())),
                Placeholder.unparsed("d", String.valueOf(city.detectionDistance())),
                Placeholder.unparsed("w", String.valueOf(b.width()))));
    }
}
