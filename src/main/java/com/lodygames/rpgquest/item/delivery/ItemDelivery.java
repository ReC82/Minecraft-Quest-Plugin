package com.lodygames.rpgquest.item.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;

/**
 * Remise sécurisée commune de tous les objets donnés par RPGQuest à un joueur (issue #318).
 *
 * <p>Avant ce service, chaque point d'appel faisait {@code addItem(...)} puis jetait le reste au
 * sol avec {@code dropItemNaturally} — tout en annonçant « Vous avez reçu ». Un objet remis à un
 * joueur à l'inventaire plein finissait donc par terre, ramassable par n'importe qui, alors que la
 * progression (variable « déjà donné », quête terminée, paiement débité) avançait comme si la
 * remise avait eu lieu.</p>
 *
 * <p>Règles tenues ici, une fois pour toutes :</p>
 * <ul>
 *   <li><strong>capacité réelle</strong> : {@link #missingSlots} simule l'ajout sur une copie de
 *       l'inventaire principal — piles partielles <em>identiques</em> ({@link ItemStack#isSimilar},
 *       donc PDC et métadonnées compris) puis cases vides, taille maximale de pile respectée. Deux
 *       objets custom non empilables comptent bien pour deux cases ;</li>
 *   <li><strong>tout ou rien</strong> : {@link #deliver} ne modifie l'inventaire que si TOUT tient,
 *       et restaure l'instantané si Bukkit laissait malgré tout un reste — jamais de remise
 *       partielle, jamais de drop ;</li>
 *   <li><strong>résultat explicite</strong> : l'appelant reçoit {@link Result}, et n'annonce un
 *       succès, ne marque « donné » ou ne débite que sur {@link Result#delivered()}.</li>
 * </ul>
 *
 * <p>{@link #restoreOrDrop} est l'unique exception, réservée à la <em>restitution</em> d'un bien
 * déjà acquis dont la transaction asynchrone est irréversible (objet acheté et payé, offre de
 * marché annulée, surplus de sac réclamé) : là, l'objet au sol vaut mieux que sa disparition, et
 * le joueur est prévenu honnêtement. Les drops de gameplay (butin de monstres, ressources) ne sont
 * pas des remises et ne passent pas par ici.</p>
 *
 * <p>Thread principal uniquement (API Bukkit d'inventaire).</p>
 */
public final class ItemDelivery {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ItemDelivery() {
    }

    /**
     * Issue d'une remise.
     *
     * @param delivered    {@code true} si tous les objets sont dans l'inventaire
     * @param missingSlots cases supplémentaires qu'il faudrait libérer (0 si remis)
     * @param dropped      {@code true} seulement pour {@link #restoreOrDrop} quand le reste est au sol
     */
    public record Result(boolean delivered, int missingSlots, boolean dropped) {

        static final Result DELIVERED = new Result(true, 0, false);

        static Result refused(int missingSlots) {
            return new Result(false, Math.max(1, missingSlots), false);
        }
    }

    /** Nombre de cases à libérer pour que {@code items} tienne entièrement ; 0 = tout tient. */
    public static int missingSlots(Inventory inventory, List<ItemStack> items) {
        ItemStack[] simulated = copyOf(inventory.getStorageContents());
        int missing = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            int maxStack = Math.max(1, Math.min(item.getMaxStackSize(), inventory.getMaxStackSize()));
            int remaining = fillPartialStacks(simulated, item, maxStack, item.getAmount());
            remaining = fillEmptySlots(simulated, item, maxStack, remaining);
            missing += (remaining + maxStack - 1) / maxStack;
        }
        return missing;
    }

    public static boolean fits(Inventory inventory, List<ItemStack> items) {
        return missingSlots(inventory, items) == 0;
    }

    /**
     * Remet TOUS les objets ou aucun. L'inventaire n'est jamais modifié en cas de refus, et rien
     * n'est jamais jeté au sol.
     */
    public static Result deliver(Player player, List<ItemStack> items) {
        Inventory inventory = player.getInventory();
        int missing = missingSlots(inventory, items);
        if (missing > 0) {
            return Result.refused(missing);
        }
        ItemStack[] snapshot = copyOf(inventory.getStorageContents());
        Map<Integer, ItemStack> leftover = inventory.addItem(clones(items));
        if (!leftover.isEmpty()) {
            // Ne devrait jamais arriver après la simulation ; si Bukkit en décide autrement, on
            // revient exactement à l'état d'avant plutôt que de laisser une remise partielle.
            inventory.setStorageContents(snapshot);
            return Result.refused(1);
        }
        return Result.DELIVERED;
    }

    public static Result deliver(Player player, ItemStack item) {
        return deliver(player, List.of(item));
    }

    /**
     * Restitution d'un bien déjà acquis (voir la note de classe) : dans l'inventaire si possible,
     * sinon au sol aux pieds du joueur, avec un message qui le dit et une trace dans le journal.
     */
    public static Result restoreOrDrop(Player player, List<ItemStack> items, Logger logger, String context) {
        Result result = deliver(player, items);
        if (result.delivered()) {
            return result;
        }
        for (ItemStack item : clones(items)) {
            for (ItemStack rest : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        logger.warn("[remise] inventaire plein pour {} ({}) : restitution déposée à ses pieds.",
                player.getName(), context);
        player.sendMessage(MM.deserialize("<yellow>Ton inventaire était plein :</yellow> <gray>une partie de "
                + "ce qui te revenait est tombée à tes pieds. Ramasse-la vite.</gray>"));
        return new Result(false, result.missingSlots(), true);
    }

    /** Message joueur commun d'un refus : rien n'a été remis ni perdu, libérer de la place puis réessayer. */
    public static Component inventoryFullMessage(int missingSlots) {
        return MM.deserialize("<red>Ton inventaire est plein.</red> <gray>Libère <white><slots></white> "
                        + "emplacement(s) puis réessaie — rien n'a été remis ni perdu.</gray>",
                Placeholder.unparsed("slots", String.valueOf(Math.max(1, missingSlots))));
    }

    private static int fillPartialStacks(ItemStack[] slots, ItemStack item, int maxStack, int remaining) {
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            ItemStack slot = slots[i];
            if (slot == null || slot.getType().isAir() || !slot.isSimilar(item)) {
                continue;
            }
            int room = maxStack - slot.getAmount();
            if (room > 0) {
                int moved = Math.min(room, remaining);
                slot.setAmount(slot.getAmount() + moved);
                remaining -= moved;
            }
        }
        return remaining;
    }

    private static int fillEmptySlots(ItemStack[] slots, ItemStack item, int maxStack, int remaining) {
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (slots[i] == null || slots[i].getType().isAir()) {
                int placed = Math.min(maxStack, remaining);
                ItemStack copy = item.clone();
                copy.setAmount(placed);
                slots[i] = copy;
                remaining -= placed;
            }
        }
        return remaining;
    }

    private static ItemStack[] copyOf(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static ItemStack[] clones(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                result.add(item.clone());
            }
        }
        return result.toArray(ItemStack[]::new);
    }
}
