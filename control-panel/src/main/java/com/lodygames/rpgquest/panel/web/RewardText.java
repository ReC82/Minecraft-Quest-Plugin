package com.lodygames.rpgquest.panel.web;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture <em>admin</em> des récompenses de quêtes (issue #77).
 *
 * <p>L'agent envoie les récompenses en <strong>chaînes déjà formatées</strong> (voir
 * {@code BukkitAgentActions.describeRewards} : {@code "+100 XP"}, {@code "+1x DIAMOND_SWORD"},
 * {@code "variable CLAIM_TIER_1 = true"}, {@code "commande console : customitem give %player%
 * rpgquest:miner_pickaxe 1"}). Ce parseur reconnaît uniquement ces formats <strong>connus et
 * produits par ce même dépôt</strong> et en tire un libellé fonctionnel, en conservant
 * <strong>toujours</strong> la valeur technique complète en secondaire.</p>
 *
 * <p>Limites assumées : la chaîne d'une {@code commande console} est tronquée à ~60 caractères
 * côté agent ; les commandes non reconnues restent affichées telles quelles. Une vraie
 * structuration (types de récompense sérialisés par l'agent) est un chantier séparé — ticket
 * d'évolution.</p>
 */
public final class RewardText {

    private RewardText() {
    }

    /**
     * @param label      libellé lisible (jamais vide)
     * @param rawDetail  valeur technique à garder consultable en secondaire, ou {@code null} si le
     *                   libellé est déjà la donnée brute (rien à cacher)
     */
    public record Reward(String label, String rawDetail) {
        boolean hasDetail() {
            return rawDetail != null && !rawDetail.isBlank();
        }
    }

    private static final Pattern XP = Pattern.compile("^\\+?\\s*(\\d+)\\s*XP$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM = Pattern.compile("^\\+?\\s*(\\d+)\\s*x\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VARIABLE = Pattern.compile("^variable\\s+(\\S+)\\s*=\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND = Pattern.compile("^commande console\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern CMD_CUSTOMITEM =
            Pattern.compile("customitem\\s+give\\s+\\S+\\s+(\\S+)(?:\\s+(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_GIVE =
            Pattern.compile("^give\\s+\\S+\\s+(\\S+)(?:\\s+(\\d+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_XP =
            Pattern.compile("(?:xp|experience)\\s+add\\s+\\S+\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final java.util.Set<String> TRUTHY = java.util.Set.of("true", "1", "yes", "on", "oui");

    public static Reward parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return new Reward("—", null);
        }

        Matcher m = XP.matcher(s);
        if (m.matches()) {
            return new Reward("+" + m.group(1) + " XP", null);
        }

        m = ITEM.matcher(s);
        if (m.matches()) {
            return new Reward("Objet : " + MinecraftNames.humanize(m.group(2).trim()) + " ×" + m.group(1),
                    m.group(2).trim());
        }

        m = VARIABLE.matcher(s);
        if (m.matches()) {
            String key = m.group(1);
            String value = m.group(2).trim();
            String label = TRUTHY.contains(value.toLowerCase(Locale.ROOT))
                    ? "Débloque : " + MiniText.prettifyId(key)
                    : "Variable : " + MiniText.prettifyId(key) + " → " + value;
            return new Reward(label, "variable " + key + " = " + value);
        }

        m = COMMAND.matcher(s);
        if (m.matches()) {
            String cmd = m.group(1).trim();
            return fromCommand(cmd);
        }

        // format inconnu : on garde la chaîne telle quelle (juste normalisée), rien à cacher.
        return new Reward(MiniText.prettifyTokens(s), null);
    }

    private static Reward fromCommand(String cmd) {
        Matcher c = CMD_CUSTOMITEM.matcher(cmd);
        if (c.find()) {
            String amount = c.group(2) == null ? "1" : c.group(2);
            return new Reward("Objet : " + MiniText.prettifyId(c.group(1)) + " ×" + amount, cmd);
        }
        c = CMD_GIVE.matcher(cmd);
        if (c.find()) {
            String amount = c.group(2) == null ? "1" : c.group(2);
            return new Reward("Objet : " + MinecraftNames.humanize(c.group(1)) + " ×" + amount, cmd);
        }
        c = CMD_XP.matcher(cmd);
        if (c.find()) {
            return new Reward("+" + c.group(1) + " XP", cmd);
        }
        return new Reward("Commande de récompense", cmd);
    }
}
