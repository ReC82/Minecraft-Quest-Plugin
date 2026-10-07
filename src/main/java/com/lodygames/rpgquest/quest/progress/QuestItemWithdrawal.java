package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Retrait <strong>exact</strong> d'un matériau dans l'inventaire d'un joueur, pour la remise
 * d'objets à un PNJ (issue #123). Séparé de {@code QuestProgressEngine} pour une raison précise :
 * c'est ici que vit la seule règle « cette pile satisfait-elle l'objectif ? », donc le seul endroit
 * à toucher pour accepter plus tard des objets personnalisés RPGQuest.
 *
 * <p>Garanties, dans cet ordre :</p>
 * <ul>
 *   <li><strong>Jamais plus que demandé</strong> : {@link #withdraw} s'arrête exactement à {@code
 *       max} (le reliquat encore nécessaire), même si le joueur en a cent fois plus.</li>
 *   <li><strong>Plusieurs piles</strong> : le stockage est parcouru emplacement par emplacement et
 *       plusieurs piles partielles sont additionnées — jamais une seule pile, jamais
 *       {@code removeItem} dont le comportement sur les métadonnées est moins explicite.</li>
 *   <li><strong>Le nombre retourné est le nombre réellement retiré</strong> : l'appelant ne fait
 *       progresser l'objectif que de cette valeur, donc il est impossible de progresser sans que
 *       les objets aient quitté l'inventaire.</li>
 *   <li><strong>Aucun objet personnalisé n'est consommé</strong> : une pile portant une identité
 *       RPGQuest dans son {@code PersistentDataContainer} (Rune de rappel, journal, Acte…) est
 *       ignorée même si son {@link Material} correspond — c'est la règle d'identité du projet
 *       (jamais reconnu par matériau seul), et sans elle une remise de {@code BOOK} détruirait le
 *       journal de quêtes du joueur.</li>
 *   <li><strong>Lecture et écriture au même instant</strong> : le comptage et le retrait se font
 *       dans la même passe, sur le thread principal. Il n'existe aucune fenêtre entre « j'ai
 *       compté » et « je retire » pendant laquelle l'inventaire pourrait changer.</li>
 * </ul>
 *
 * <p>Seul le <em>stockage normal</em> est touché ({@link PlayerInventory#getStorageContents()}) :
 * ni l'armure, ni la main secondaire, exactement comme {@code player.StarterToolKitService} compte
 * la place disponible. Un objet porté ne peut donc pas disparaître dans une remise.</p>
 */
public final class QuestItemWithdrawal {

    private QuestItemWithdrawal() {
    }

    /**
     * Compte, sans rien modifier, combien d'exemplaires de {@code material} sont remisables —
     * utilisé pour prévenir le joueur qu'il n'a rien d'utile avant de toucher à quoi que ce soit.
     */
    public static int available(PlayerInventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matches(stack, material)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Retire au plus {@code max} exemplaires de {@code material} et renvoie le nombre
     * <strong>réellement</strong> retiré (0 si le joueur n'en a pas, ou si {@code max <= 0}).
     */
    public static int withdraw(PlayerInventory inventory, Material material, int max) {
        if (max <= 0) {
            return 0;
        }
        ItemStack[] storage = inventory.getStorageContents();
        int taken = 0;
        boolean changed = false;
        for (int slot = 0; slot < storage.length && taken < max; slot++) {
            ItemStack stack = storage[slot];
            if (!matches(stack, material)) {
                continue;
            }
            int take = Math.min(stack.getAmount(), max - taken);
            if (take >= stack.getAmount()) {
                storage[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
            taken += take;
            changed = true;
        }
        if (changed) {
            inventory.setStorageContents(storage);
        }
        return taken;
    }

    /**
     * Seule règle d'éligibilité d'une pile — <strong>point d'extension</strong> pour les objets
     * personnalisés RPGQuest (voir {@code DeliverItemToNpcObjective}). Aujourd'hui : le matériau
     * doit correspondre et la pile ne doit porter <em>aucune</em> identité d'objet personnalisé.
     */
    static boolean matches(ItemStack stack, Material material) {
        if (stack == null || stack.getType() != material || stack.getAmount() <= 0) {
            return false;
        }
        return YamlCustomItemRegistry.identityOf(stack).isEmpty();
    }
}
