package com.lodygames.rpgquest.hub;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;

/**
 * Issue #675 (retour du 22/10) — postes de travail vanilla <strong>fermés par défaut</strong> dans le
 * Hub, sauf exception {@code hub.stations.exceptions} ({@link HubStationException}). Chaque poste ouvre
 * un menu ; il peut donc être rouvert par le plugin une fois une exception à conditions asynchrones
 * vérifiée (état de quête, variable lus en base) : {@link #open}.
 *
 * <p>Ne figurent pas ici : les conteneurs de stockage (crafter compris, fermé sans exception par
 * {@link HubResourceLockListener}), le coffre de l'Ender (stockage personnel, libre) et les blocs sans
 * menu qui donnent une ressource (composteur, jukebox, chaudrons, pots de fleurs, coffre des
 * épreuves : {@link #RESOURCE_BLOCKS}, fermés sans exception).</p>
 */
public enum HubStation {

    CRAFTING_TABLE(() -> MenuType.CRAFTING, Material.CRAFTING_TABLE),
    FURNACE(null, Material.FURNACE),
    BLAST_FURNACE(null, Material.BLAST_FURNACE),
    SMOKER(null, Material.SMOKER),
    BREWING_STAND(null, Material.BREWING_STAND),
    ANVIL(() -> MenuType.ANVIL, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL),
    GRINDSTONE(() -> MenuType.GRINDSTONE, Material.GRINDSTONE),
    SMITHING_TABLE(() -> MenuType.SMITHING, Material.SMITHING_TABLE),
    STONECUTTER(() -> MenuType.STONECUTTER, Material.STONECUTTER),
    LOOM(() -> MenuType.LOOM, Material.LOOM),
    CARTOGRAPHY_TABLE(() -> MenuType.CARTOGRAPHY_TABLE, Material.CARTOGRAPHY_TABLE),
    ENCHANTING_TABLE(() -> MenuType.ENCHANTMENT, Material.ENCHANTING_TABLE),
    BEACON(() -> MenuType.BEACON, Material.BEACON);

    /**
     * Blocs sans menu qui rendent une ressource au clic droit (poudre d'os, disque, seau de lave ou de
     * neige poudreuse, fleur d'un pot, butin d'un coffre des épreuves) ou qu'un seau modifierait
     * (chaudron vide) : fermés dans le Hub, sans exception.
     */
    static final Set<Material> RESOURCE_BLOCKS = resourceBlocks();

    /** Menu ouvert à la position du bloc ; {@code null} = bloc conteneur ({@link Container}), son inventaire. */
    private final Supplier<MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>>> menu;
    private final Set<Material> materials;

    HubStation(Supplier<MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>>> menu, Material... materials) {
        this.menu = menu;
        this.materials = Set.of(materials);
    }

    public Set<Material> materials() {
        return materials;
    }

    public static Optional<HubStation> of(Material material) {
        return Arrays.stream(values()).filter(s -> s.materials.contains(material)).findFirst();
    }

    /**
     * Ouvre le poste comme le ferait un clic vanilla, lié au bloc (portée vérifiée en continu, le menu se
     * ferme si le joueur s'éloigne ou si le bloc disparaît). {@code false} si le bloc n'est plus ce poste.
     */
    boolean open(Player player, Block block) {
        if (!materials.contains(block.getType())) {
            return false;
        }
        if (menu == null) {
            if (!(block.getState() instanceof Container container)) {
                return false;
            }
            player.openInventory(container.getInventory());
            return true;
        }
        Location location = block.getLocation();
        menu.get().builder().location(location).checkReachable(true).build(player).open();
        return true;
    }

    private static Set<Material> resourceBlocks() {
        Set<Material> set = EnumSet.of(Material.COMPOSTER, Material.JUKEBOX, Material.CAULDRON, Material.WATER_CAULDRON,
                Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON, Material.VAULT);
        for (Material material : Material.values()) {
            if (material.name().startsWith("POTTED_")) {
                set.add(material);
            }
        }
        return Set.copyOf(set);
    }
}
