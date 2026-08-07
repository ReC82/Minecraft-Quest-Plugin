package com.lodygames.rpgquest.backpack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bukkit.inventory.ItemStack;

/**
 * Sérialise un contenu d'inventaire complet ({@code ItemStack[]}, cases
 * vides comprises) en un unique tableau d'octets, et inversement. Aucun
 * précédent dans le projet (seul un {@code ItemStack} isolé est sérialisé
 * ailleurs, {@code economy.market.MarketService} via {@code
 * ItemStack#serializeAsBytes()}/{@code deserializeBytes}) : ce format
 * réutilise exactement la même sérialisation par case, préfixée par sa
 * longueur, pour que la méta complète (nom, lore, enchantements, PDC d'un
 * objet personnalisé) survive intacte — même garantie que le marché.
 *
 * <p>{@link #SCHEMA_VERSION} est stocké à côté de ce binaire (colonne
 * {@code backpacks.schema_version}, pas dans le binaire lui-même) pour
 * qu'un futur changement de format puisse migrer les lignes existantes sans
 * avoir à d'abord les décoder avec l'ancien format (mission étape 20, point
 * 6 : « stocke le contenu de manière sûre et versionnée »).</p>
 */
public final class ItemArraySerializer {

    /** Version du format binaire produit par {@link #serialize}, indépendante de {@code PRAGMA user_version}. */
    public static final int SCHEMA_VERSION = 1;

    private ItemArraySerializer() {
    }

    public static byte[] serialize(ItemStack[] contents) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(contents.length);
            for (ItemStack item : contents) {
                if (item == null || item.getType().isAir()) {
                    out.writeBoolean(false);
                    continue;
                }
                out.writeBoolean(true);
                byte[] itemBytes = item.serializeAsBytes();
                out.writeInt(itemBytes.length);
                out.write(itemBytes);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream/DataOutputStream n'écrivent jamais réellement sur disque :
            // une IOException ici ne peut être qu'un bug, jamais une condition attendue en jeu.
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /**
     * @throws IOException si {@code bytes} est corrompu ou tronqué — l'appelant doit traiter ce cas
     *                      comme une anomalie (entrée d'audit/récupération), jamais laisser le contenu
     *                      disparaître silencieusement (mission, validation).
     */
    public static ItemStack[] deserialize(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int length = in.readInt();
            if (length < 0 || length > 6 * 9) {
                throw new IOException("Longueur d'inventaire invalide : " + length);
            }
            ItemStack[] contents = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                if (!in.readBoolean()) {
                    continue;
                }
                int itemLength = in.readInt();
                if (itemLength < 0 || itemLength > bytes.length) {
                    throw new IOException("Longueur d'objet invalide à la case " + i + " : " + itemLength);
                }
                byte[] itemBytes = new byte[itemLength];
                in.readFully(itemBytes);
                contents[i] = ItemStack.deserializeBytes(itemBytes);
            }
            return contents;
        }
    }
}
