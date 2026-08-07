package be.lloyd.rpgquest.claim;

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
 * État de sélection (outil « wand ») pour {@code /claim create} — en
 * mémoire uniquement, comme {@code zone.ZoneSelectionService} (dont elle
 * est délibérément une copie dédiée plutôt qu'une réutilisation : un joueur
 * qui est aussi administrateur ne doit pas voir sa sélection de claim
 * mélangée à sa sélection de zone protégée).
 */
public final class ClaimSelectionService {

    /** Clé PDC posée sur l'objet de sélection, seule façon de le reconnaître (jamais le nom/lore). */
    public static final NamespacedKey WAND_KEY = new NamespacedKey("rpgquest", "claim_wand");

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

    /** Construit un exemplaire de l'outil de sélection (hache en bois marquée par PDC, jamais par nom/lore). */
    public ItemStack createWandItem() {
        ItemStack stack = new ItemStack(Material.WOODEN_HOE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize("<gold>Outil de sélection de claim</gold>"));
        meta.getPersistentDataContainer().set(WAND_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }
}
