package com.lodygames.rpgquest.zone;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * État de sélection (outil « wand ») pour {@code /rpgadmin zone create} —
 * en mémoire uniquement, comme les sessions de dialogue/journal : une
 * sélection en cours n'a aucune raison de survivre à une reconnexion.
 */
public final class ZoneSelectionService {

    /** Clé PDC posée sur l'objet de sélection, seule façon de le reconnaître (jamais le nom/lore). */
    public static final NamespacedKey WAND_KEY = new NamespacedKey("rpgquest", "zone_wand");

    private record Selection(Location pos1, Location pos2) {
    }

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public boolean isWand(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        Byte marker = meta.getPersistentDataContainer().get(WAND_KEY, PersistentDataType.BYTE);
        return marker != null && marker == 1;
    }

    public void setPos1(UUID playerId, Location location) {
        selections.merge(playerId, new Selection(location, null),
                (existing, fresh) -> new Selection(location, existing.pos2()));
    }

    public void setPos2(UUID playerId, Location location) {
        selections.merge(playerId, new Selection(null, location),
                (existing, fresh) -> new Selection(existing.pos1(), location));
    }

    public Optional<Location> pos1(UUID playerId) {
        Selection selection = selections.get(playerId);
        return selection == null ? Optional.empty() : Optional.ofNullable(selection.pos1());
    }

    public Optional<Location> pos2(UUID playerId) {
        Selection selection = selections.get(playerId);
        return selection == null ? Optional.empty() : Optional.ofNullable(selection.pos2());
    }

    public void clear(UUID playerId) {
        selections.remove(playerId);
    }

    /**
     * Construit un exemplaire de l'outil de sélection (tige de blaze marquée par PDC, jamais par
     * nom/lore). Volontairement pas une hache en bois : c'est l'item de wand par défaut de WorldEdit
     * (config {@code wand-item}, WorldEdit reconnaît n'importe quelle hache en bois par son type
     * d'objet, pas par PDC), donc les deux plugins se disputaient le même clic — WorldEdit posait sa
     * propre sélection et annulait l'événement avant que RPGQuest ne voie le clic. Un matériau que
     * WorldEdit n'associe à aucune wand par défaut rend les deux outils réellement indépendants.
     */
    public ItemStack createWandItem() {
        ItemStack stack = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize("<gold>Outil de sélection de zone</gold>"));
        meta.getPersistentDataContainer().set(WAND_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }
}
