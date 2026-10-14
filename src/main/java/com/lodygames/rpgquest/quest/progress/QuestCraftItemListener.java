package com.lodygames.rpgquest.quest.progress;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Progression de {@code CRAFT_ITEM}. Issue #34 : la progression avance du <strong>nombre réel
 * d'objets fabriqués</strong>, plus d'une unité par événement — une recette de 4 planches compte 4.
 *
 * <p>{@code CraftItemEvent} est émis <em>avant</em> que le serveur ne déplace le résultat, et une
 * seule fois même pour un shift-clic qui enchaîne plusieurs crafts. La quantité dépend du clic :</p>
 *
 * <ul>
 *   <li><strong>Clic gauche/droit</strong> : un seul craft, si le curseur peut recevoir le résultat
 *       (vide, ou même objet sans dépasser la pile). Sinon le serveur ne fabrique rien : 0.</li>
 *   <li><strong>Q / Ctrl+Q</strong> sur le résultat : un craft jeté au sol, curseur vide requis.</li>
 *   <li><strong>Shift-clic, touche numérique, main secondaire</strong> : la quantité dépend de la
 *       place réellement disponible (inventaire plein, slots partiels). Elle est donc
 *       <strong>mesurée</strong> : objets identiques au résultat comptés dans l'inventaire et le
 *       curseur avant l'événement, puis au tick suivant, une fois le déplacement fait. La
 *       différence est plafonnée au maximum théorique (ingrédients disponibles × quantité par
 *       craft), pour qu'un ramassage dans le même tick ne gonfle jamais la progression.</li>
 *   <li>Tout autre clic (molette créative, double-clic…) ne fabrique rien : 0.</li>
 * </ul>
 *
 * <p>Le moteur plafonne ensuite à la quantité requise ; un craft de 0 objet n'appelle jamais le
 * moteur. Priorité {@code MONITOR} : un autre plugin qui annule le craft n'est jamais compté.</p>
 */
final class QuestCraftItemListener implements Listener {

    private final QuestProgressEngine engine;
    private final Plugin plugin;

    QuestCraftItemListener(QuestProgressEngine engine, Plugin plugin) {
        this.engine = engine;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        if (isEmpty(result)) {
            result = event.getRecipe().getResult();
        }
        if (isEmpty(result)) {
            return;
        }
        ItemStack crafted = result.clone();
        ClickType click = event.getClick();
        switch (click) {
            case LEFT, RIGHT -> credit(player, crafted.getType(),
                    cursorAccepts(event.getCursor(), crafted) ? crafted.getAmount() : 0);
            case DROP, CONTROL_DROP -> credit(player, crafted.getType(),
                    isEmpty(event.getCursor()) ? crafted.getAmount() : 0);
            case SHIFT_LEFT, SHIFT_RIGHT, NUMBER_KEY, SWAP_OFFHAND -> {
                int upperBound = click.isShiftClick()
                        ? maxCrafts(event.getInventory().getMatrix()) * crafted.getAmount()
                        : crafted.getAmount();
                int before = countSimilar(player, crafted);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        credit(player, crafted.getType(),
                                measuredAmount(before, countSimilar(player, crafted), upperBound));
                    }
                });
            }
            default -> {
                // Clonage créatif, double-clic… : aucun objet fabriqué.
            }
        }
    }

    private void credit(Player player, Material material, int amount) {
        if (amount > 0) {
            engine.handleCraftItem(player, material, amount);
        }
    }

    /** Écart mesuré, borné à {@code [0, upperBound]}. */
    static int measuredAmount(int before, int after, int upperBound) {
        return Math.max(0, Math.min(after - before, upperBound));
    }

    /**
     * Nombre de crafts possibles avec la grille : chaque case occupée perd un objet par craft, donc
     * la plus petite pile limite la série.
     */
    static int maxCrafts(ItemStack[] matrix) {
        int max = Integer.MAX_VALUE;
        for (ItemStack ingredient : matrix) {
            if (!isEmpty(ingredient)) {
                max = Math.min(max, ingredient.getAmount());
            }
        }
        return max == Integer.MAX_VALUE ? 1 : max;
    }

    static boolean cursorAccepts(ItemStack cursor, ItemStack result) {
        if (isEmpty(cursor)) {
            return true;
        }
        return cursor.isSimilar(result) && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize();
    }

    private static int countSimilar(Player player, ItemStack crafted) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (!isEmpty(stack) && stack.isSimilar(crafted)) {
                total += stack.getAmount();
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (!isEmpty(cursor) && cursor.isSimilar(crafted)) {
            total += cursor.getAmount();
        }
        return total;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
