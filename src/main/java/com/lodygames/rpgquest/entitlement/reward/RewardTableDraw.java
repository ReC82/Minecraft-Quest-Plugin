package com.lodygames.rpgquest.entitlement.reward;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tirage d'une table de récompenses (issue #177). Le tirage est une fonction <strong>pure</strong>
 * d'une graine stable (cycle de quête, instance de boss + joueur) : rejouer une remise, une
 * déclaration ou un redémarrage retombe exactement sur les mêmes gains, et chaque gain devient un
 * droit à récompense dont la clé d'idempotence empêche tout doublon. Rien n'est jamais « relancé ».
 *
 * <p>La graine contient toujours un UUID aléatoire (jeton de complétion, entité du boss) : un
 * joueur ne peut pas prévoir son tirage.</p>
 */
public final class RewardTableDraw {

    private RewardTableDraw() {
    }

    /** Un gain : l'entrée tirée et le droit qui la remettra. */
    public record Win(RewardTable.Entry entry, RewardEntitlement.Declaration declaration) {
    }

    /** Entrées gagnées, dans l'ordre de la table ; vide = aucun gain (ou table désactivée). */
    public static List<RewardTable.Entry> draw(RewardTable table, String seed) {
        List<RewardTable.Entry> won = new ArrayList<>();
        if (!table.enabled()) {
            return won;
        }
        if (table.mode() == RewardTable.Mode.INDEPENDENT) {
            for (int i = 0; i < table.entries().size(); i++) {
                RewardTable.Entry entry = table.entries().get(i);
                if (roll(seed + "|" + i) < entry.chance()) {
                    won.add(entry);
                }
            }
            return won;
        }
        int roll = roll(seed + "|single");
        int cumulative = 0;
        for (RewardTable.Entry entry : table.entries()) {
            cumulative += entry.chance();
            if (roll < cumulative) {
                won.add(entry);
                break;
            }
        }
        return won;
    }

    /**
     * Tire la table et prépare un droit par gain. Clé {@code <base>:<table>.<entrée>:<joueur>} :
     * stable pour une même base, donc idempotente.
     *
     * @param base  préfixe propre à la source et à l'occasion ({@code quest:<jeton>}, {@code boss:<entité>})
     * @param label libellé figé du droit (récompense du catalogue), avant la trace de la table
     */
    public static List<Win> wins(RewardTable table, String base, UUID player, RewardSource source, String sourceRef,
                                 java.util.function.Function<String, String> label) {
        List<Win> wins = new ArrayList<>();
        String version = table.version();
        for (RewardTable.Entry entry : draw(table, base + ":" + table.id() + ":" + player)) {
            String key = base + ":" + table.id() + "." + entry.id() + ":" + player;
            String trace = label.apply(entry.rewardKey()) + " — table " + table.id() + " v" + version + " ("
                    + RewardTable.percent(entry.chance()) + ")";
            wins.add(new Win(entry, new RewardEntitlement.Declaration(key, player, source, sourceRef,
                    entry.rewardKey(), trace)));
        }
        return wins;
    }

    /**
     * Explication des chances réelles, pour l'administrateur : ce que voit vraiment un joueur à
     * chaque tirage.
     */
    public static String explain(RewardTable table) {
        int total = table.totalChance();
        if (table.mode() == RewardTable.Mode.SINGLE) {
            return "Une récompense maximum par tirage. Chaque entrée a exactement la chance indiquée ; "
                    + "aucun gain : " + RewardTable.percent(RewardTable.FULL - total) + ".";
        }
        double nothing = 1.0;
        for (RewardTable.Entry entry : table.entries()) {
            nothing *= 1.0 - entry.chance() / (double) RewardTable.FULL;
        }
        double expected = total / (double) RewardTable.FULL;
        return "Chances indépendantes : chaque entrée est tirée séparément, plusieurs gains sont possibles. "
                + "Aucun gain : " + String.format(java.util.Locale.FRANCE, "%.2f", nothing * 100) + " % ; "
                + "gains moyens par tirage : " + String.format(java.util.Locale.FRANCE, "%.2f", expected) + ".";
    }

    /** Valeur uniforme dans [0, {@link RewardTable#FULL}) dérivée de la graine. */
    static int roll(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFF);
            }
            return (int) Long.remainderUnsigned(value, RewardTable.FULL);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
