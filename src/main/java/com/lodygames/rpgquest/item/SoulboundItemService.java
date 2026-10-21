package com.lodygames.rpgquest.item;

import com.lodygames.rpgquest.bootstrap.PluginService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * Registre générique des objets personnalisés « liés au joueur » (soulbound) : un objet enregistré
 * ici ne peut jamais être jeté volontairement ni tomber au sol à la mort — voir
 * {@link SoulboundItemListener}, qui applique ces deux règles pour <em>tous</em> les objets
 * enregistrés, plutôt qu'un écouteur dédié recopié par objet (l'ancienne
 * {@code travel.ReturnStoneGuardListener} n'était que le premier cas concret).
 *
 * <p><strong>Identification</strong> : toujours via {@link YamlCustomItemRegistry#identify}
 * (conteneur de données persistantes / PDC), jamais le matériau seul — un objet vanilla du même
 * matériau n'est jamais concerné (mission : « identification via custom item/PDC, jamais matériau
 * seul »).</p>
 *
 * <p>Une fois les chemins « drop volontaire » et « drop à la mort » fermés, un objet soulbound ne
 * devient plus jamais une entité item posée dans le monde : aucune autre voie de destruction
 * (lave/feu/cactus/explosion) ne peut plus s'y appliquer. La restauration à la réapparition ne
 * duplique jamais rien : elle ne rend que les exemplaires effectivement retirés des drops de
 * <em>cette</em> mort précise.</p>
 *
 * <p><strong>Non stockable (issue #625)</strong> : un objet enregistré via
 * {@link #registerStorageLocked} est en plus interdit de tout stockage — coffres, tonneaux, shulkers,
 * coffre de l'Ender, hoppers, wagonnets, inventaires du plugin, sacs, cadres, pots… — dans
 * <em>tous</em> les mondes ; voir {@link StorageLockedItemListener}. C'est une politique distincte
 * de l'anti-perte : la Rune de rappel consommable reste soulbound sans être non stockable
 * (décision propriétaire du 20/10/2026).</p>
 */
public final class SoulboundItemService implements PluginService {

    private final YamlCustomItemRegistry customItemRegistry;
    private final Set<NamespacedKey> soulboundIds = ConcurrentHashMap.newKeySet();
    private final Set<NamespacedKey> storageLockedIds = ConcurrentHashMap.newKeySet();

    public SoulboundItemService(YamlCustomItemRegistry customItemRegistry) {
        this.customItemRegistry = customItemRegistry;
    }

    /** Enregistre {@code itemId} comme soulbound — idempotent, l'ordre d'appel n'a pas d'importance. */
    public void register(NamespacedKey itemId) {
        soulboundIds.add(itemId);
    }

    /**
     * Enregistre {@code itemId} comme objet permanent lié au joueur <em>et</em> non stockable : il
     * devient aussi soulbound. Idempotent.
     */
    public void registerStorageLocked(NamespacedKey itemId) {
        register(itemId);
        storageLockedIds.add(itemId);
    }

    @Override
    public void start() {
        // Rien à démarrer : les enregistrements sont faits par le bootstrap, l'écouteur fait le reste.
    }

    @Override
    public void stop() {
        soulboundIds.clear();
        storageLockedIds.clear();
    }

    /** Écouteur Bukkit unique (drop/mort/réapparition) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new SoulboundItemListener(this);
    }

    /** Écouteur Bukkit du refus de stockage (#625) à enregistrer via {@code PlayerListenerService}. */
    public Listener storageListener() {
        return new StorageLockedItemListener(this);
    }

    public boolean isStorageLocked(ItemStack stack) {
        return identify(stack).map(storageLockedIds::contains).orElse(false);
    }

    public boolean isSoulbound(ItemStack stack) {
        return identify(stack).map(soulboundIds::contains).orElse(false);
    }

    Optional<ItemStack> create(NamespacedKey itemId) {
        return customItemRegistry.create(itemId, 1);
    }

    private Optional<NamespacedKey> identify(ItemStack stack) {
        if (stack == null) {
            return Optional.empty();
        }
        return customItemRegistry.identify(stack);
    }
}
