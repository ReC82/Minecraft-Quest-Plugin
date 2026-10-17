package com.lodygames.rpgquest.economy.price;

import com.lodygames.rpgquest.economy.price.PriceDefinition.PriceItem;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boucles d'enrichissement entre prix <strong>actifs</strong> (issue #336) : un joueur qui pourrait
 * gagner des pièces sans fin en répétant les mêmes échanges. Calcul exact (fractions, jamais de
 * virgule flottante) sur le prix unitaire, tous PNJ confondus — c'est l'intérêt d'un catalogue
 * central :
 *
 * <ul>
 *   <li>un objet racheté plus cher qu'il n'est vendu ;</li>
 *   <li>une fabrication dont le résultat se rachète plus cher que ce qu'elle coûte, quand chacun de
 *       ses ingrédients s'achète (pièces de la fabrication + ingrédients au prix de vente le plus bas).</li>
 * </ul>
 *
 * <p>Limite connue : les chaînes de plusieurs fabrications ne sont pas suivies.</p>
 */
final class PriceArbitrage {

    private PriceArbitrage() {
    }

    /** Messages d'erreur, un par boucle trouvée (vide = aucune). */
    static List<String> loops(Collection<PriceDefinition> prices) {
        Map<String, PriceDefinition> cheapestSale = new LinkedHashMap<>();
        Map<String, PriceDefinition> bestBuyBack = new LinkedHashMap<>();
        for (PriceDefinition price : prices) {
            if (!price.enabled()) {
                continue;
            }
            String item = price.item().itemId();
            if (price.type() == PriceType.SELL_TO_PLAYER) {
                cheapestSale.merge(item, price, (a, b) -> unit(b).compareTo(unit(a)) < 0 ? b : a);
            } else if (price.type() == PriceType.BUY_FROM_PLAYER) {
                bestBuyBack.merge(item, price, (a, b) -> unit(b).compareTo(unit(a)) > 0 ? b : a);
            }
        }
        List<String> errors = new ArrayList<>();
        for (var entry : bestBuyBack.entrySet()) {
            PriceDefinition sale = cheapestSale.get(entry.getKey());
            PriceDefinition buyBack = entry.getValue();
            if (sale != null && unit(buyBack).compareTo(unit(sale)) > 0) {
                errors.add("Boucle d'enrichissement : " + entry.getKey() + " est vendu « " + sale.key() + " » ("
                        + sale.coins() + " pièce(s) les " + sale.item().amount() + ") et racheté plus cher « "
                        + buyBack.key() + " » (" + buyBack.coins() + " pièce(s) les " + buyBack.item().amount()
                        + ") — un joueur gagnerait des pièces sans fin. Baisser le rachat ou monter la vente.");
            }
        }
        for (PriceDefinition craft : prices) {
            if (!craft.enabled() || craft.type() != PriceType.CRAFT) {
                continue;
            }
            PriceDefinition buyBack = bestBuyBack.get(craft.item().itemId());
            if (buyBack == null) {
                continue;
            }
            Fraction cost = Fraction.of(craft.coins(), 1);
            boolean allBuyable = true;
            for (PriceItem ingredient : craft.ingredients()) {
                PriceDefinition sale = cheapestSale.get(ingredient.itemId());
                if (sale == null) {
                    allBuyable = false;
                    break;
                }
                cost = cost.plus(unit(sale).times(ingredient.amount()));
            }
            Fraction revenue = unit(buyBack).times(craft.item().amount());
            if (allBuyable && revenue.compareTo(cost) > 0) {
                errors.add("Boucle d'enrichissement : la fabrication « " + craft.key() + " » coûte "
                        + cost.display() + " pièce(s) (ingrédients achetés compris) et son résultat se rachète "
                        + revenue.display() + " pièce(s) (« " + buyBack.key() + " »). Monter le coût ou baisser le rachat.");
            }
        }
        return errors;
    }

    private static Fraction unit(PriceDefinition price) {
        return Fraction.of(price.coins(), price.item().amount());
    }

    /** Fraction positive exacte. */
    record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {

        static Fraction of(long numerator, long denominator) {
            return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }

        Fraction plus(Fraction other) {
            return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                    denominator.multiply(other.denominator));
        }

        Fraction times(long factor) {
            return new Fraction(numerator.multiply(BigInteger.valueOf(factor)), denominator);
        }

        @Override
        public int compareTo(Fraction other) {
            return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
        }

        /** Entier si exact, sinon arrondi au centième (affichage seulement). */
        String display() {
            BigInteger[] qr = numerator.divideAndRemainder(denominator);
            if (qr[1].signum() == 0) {
                return qr[0].toString();
            }
            return new java.math.BigDecimal(numerator).divide(new java.math.BigDecimal(denominator), 2,
                    java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        }
    }
}
