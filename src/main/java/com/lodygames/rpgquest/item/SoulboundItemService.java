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
 */
public final class SoulboundItemService implements PluginService {

    private final YamlCustomItemRegistry customItemRegistry;
    private final Set<NamespacedKey> soulboundIds = ConcurrentHashMap.newKeySet();

    public SoulboundItemService(YamlCustomItemRegistry customItemRegistry) {
        this.customItemRegistry = customItemRegistry;
    }

    /** Enregistre {@code itemId} comme soulbound — idempotent, l'ordre d'appel n'a pas d'importance. */
    public void register(NamespacedKey itemId) {
        soulboundIds.add(itemId);
    }

    @Override
    public void start() {
        // Rien à démarrer : les enregistrements sont faits par le bootstrap, l'écouteur fait le reste.
    }

    @Override
    public void stop() {
        soulboundIds.clear();
    }

    /** Écouteur Bukkit unique (drop/mort/réapparition) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new SoulboundItemListener(this);
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
