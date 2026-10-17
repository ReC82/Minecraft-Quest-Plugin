package com.lodygames.rpgquest.economy.price;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Un prix du catalogue {@code prices.yml} (issue #336) : ce qui est échangé, contre combien de
 * pièces et — pour une fabrication — contre quels ingrédients exacts. Tout est saisi explicitement :
 * aucun montant n'est déduit d'un autre.
 *
 * @param item        objet vendu, racheté ou fabriqué (avec sa quantité)
 * @param coins       pièces payées (vente, fabrication) ou reçues (rachat) par le joueur, ≥ 0
 * @param ingredients objets exacts donnés par le joueur, fabrication seulement (vide sinon)
 * @param demo        valeur de démonstration, à revoir avant d'ouvrir l'économie aux joueurs
 * @param enabled     faux = l'offre qui le cite est affichée « indisponible » et ne s'échange pas
 */
public record PriceDefinition(String key, String label, PriceType type, PriceItem item, long coins,
                              List<PriceItem> ingredients, boolean demo, boolean enabled) {

    public PriceDefinition {
        ingredients = List.copyOf(ingredients);
    }

    /**
     * Empreinte courte : un formulaire PlugAdmin ouvert sur une version plus ancienne est refusé, et
     * l'audit d'un échange dit quelle version du prix a été appliquée.
     */
    public String version() {
        String canonical = label + "|" + type + "|" + item + "|" + coins + "|" + ingredients + "|" + demo + "|" + enabled;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Même prix, clé, nom et interrupteur changés (copie, bascule). */
    public PriceDefinition with(String newKey, String newLabel, boolean newEnabled) {
        return new PriceDefinition(newKey, newLabel, type, item, coins, ingredients, demo, newEnabled);
    }

    /** « 4 × BREAD pour 3 pièces », « 1 × rpgquest:forest_blade contre 3 × IRON_INGOT + 50 pièces ». */
    public String summary() {
        return switch (type) {
            case SELL_TO_PLAYER -> item + " vendu pour " + coins + " pièce(s)";
            case BUY_FROM_PLAYER -> item + " racheté pour " + coins + " pièce(s)";
            case CRAFT -> {
                List<String> parts = new ArrayList<>();
                ingredients.forEach(i -> parts.add(i.toString()));
                if (coins > 0) {
                    parts.add(coins + " pièce(s)");
                }
                yield item + " fabriqué contre " + String.join(" + ", parts);
            }
        };
    }

    /**
     * @param itemId nom de matériau Paper ({@code BREAD}) ou id d'objet RPGQuest namespacé
     *               ({@code rpgquest:forest_blade}) — l'objet RPGQuest est reconnu par son
     *               PersistentDataContainer, jamais par son nom
     */
    public record PriceItem(String itemId, int amount) {

        public boolean custom() {
            return itemId.indexOf(':') >= 0;
        }

        @Override
        public String toString() {
            return amount + " × " + itemId;
        }
    }
}
