package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Issue #15 lot 2 — onglets « Quotidiennes » / « Hebdomadaires » de la page Quêtes.
 *
 * <p>Un onglet périodique liste les <strong>modèles</strong> {@code period: daily|weekly} (la fiche
 * dépliable habituelle sert d'aperçu, l'éditeur guidé gère déjà {@code period}), et affiche en
 * lecture seule les réglages {@code periodic-quests} que le serveur applique, relevés par
 * {@code quest.list}. PlugAdmin ne modifie pas ces réglages : ce sont des valeurs de démonstration
 * en attente d'arbitrage du propriétaire.</p>
 *
 * <p>« Actif » = chargé par le serveur de jeu (présent dans le dernier relevé) ; un modèle
 * seulement enregistré dans PlugAdmin n'est attribué à personne tant qu'il n'est pas publié.</p>
 */
final class QuestPeriodicView {

    static final String DAILY = "daily";
    static final String WEEKLY = "weekly";

    /** Un modèle de quête périodique tel que vu par la page : actif sur le serveur ou non. */
    record Model(String id, boolean active) {
    }

    private QuestPeriodicView() {
    }

    /** Onglet demandé, normalisé : {@code daily}, {@code weekly} ou {@code ""} (toutes les quêtes). */
    static String normalizeTab(String raw) {
        String tab = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return DAILY.equals(tab) || WEEKLY.equals(tab) ? tab : "";
    }

    /** Période d'une ligne de catalogue ({@code quest.list} ou source), {@code ""} si ordinaire. */
    static String periodOf(Map<String, Object> row) {
        Object raw = row == null ? null : row.get("period");
        return normalizeTab(raw == null ? null : String.valueOf(raw));
    }

    static String label(String period) {
        return DAILY.equals(period) ? "Quotidienne" : "Hebdomadaire";
    }

    /** Barre d'onglets (liens : fonctionne sans JavaScript, et l'onglet se partage par URL). */
    static String tabs(String agentId, String player, String current, int dailyCount, int weeklyCount) {
        String base = "/quests?agent=" + Http.esc(agentId)
                + (player == null || player.isEmpty() ? "" : "&amp;player=" + Http.esc(player));
        return "<nav class=\"pl-tabs pl-tabs-wrap\" aria-label=\"Type de quêtes\">"
                + tab(base, "", "Toutes les quêtes", current)
                + tab(base + "&amp;tab=" + DAILY, DAILY, "Quotidiennes (" + dailyCount + ")", current)
                + tab(base + "&amp;tab=" + WEEKLY, WEEKLY, "Hebdomadaires (" + weeklyCount + ")", current)
                + "</nav>";
    }

    private static String tab(String href, String value, String label, String current) {
        return "<a href=\"" + href + "\"" + (value.equals(current) ? " aria-current=\"page\"" : "") + ">"
                + Http.esc(label) + "</a>";
    }

    /**
     * En-tête d'un onglet périodique : réglages du serveur (lecture seule), état des modèles, bouton
     * de création préréglé.
     *
     * @param settings détails {@code periodic} de {@code quest.list}, {@code null} si inconnus
     */
    static String overview(String period, Map<String, Object> settings, List<Model> models, boolean canEdit) {
        boolean daily = DAILY.equals(period);
        StringBuilder sb = new StringBuilder("<section class=\"card\" data-periodic-overview=\"")
                .append(period).append("\">");
        sb.append("<h2>").append(daily ? "Quêtes quotidiennes" : "Quêtes hebdomadaires").append("</h2>");
        sb.append("<p class=\"muted\">Chaque joueur reçoit ses quêtes ").append(daily ? "du jour" : "de la semaine")
                .append(" en ouvrant son journal. Ces modèles ne sont jamais proposés par un PNJ.</p>");
        long active = models.stream().filter(Model::active).count();
        long unpublished = models.size() - active;
        sb.append(settingsBlock(period, settings));
        Integer perPlayer = settings == null ? null : intOf(settings.get(daily ? "dailyCount" : "weeklyCount"));
        if (active == 0) {
            sb.append(Ui.banner("warn", "Aucun modèle actif sur le serveur : l'onglet du journal des joueurs "
                    + "reste vide."));
        } else if (perPlayer != null && active < perPlayer) {
            sb.append(Ui.banner("warn", "Seulement " + active + " modèle(s) actif(s) pour " + perPlayer
                    + " quête(s) par joueur : chaque joueur n'en reçoit que " + active + "."));
        } else if (perPlayer != null && active == perPlayer) {
            sb.append(Ui.banner("info", "Autant de modèles actifs que de quêtes attribuées : les mêmes "
                    + "reviennent à chaque période. Ajouter des modèles pour varier."));
        }
        if (unpublished > 0) {
            sb.append(Ui.banner("info", unpublished + " modèle(s) enregistré(s) dans PlugAdmin mais pas encore "
                    + "chargé(s) par le serveur : personne ne les reçoit tant qu'ils ne sont pas publiés "
                    + "(bouton « Publier » de la fiche)."));
        }
        if (canEdit) {
            sb.append("<p>").append(Ui.primaryLink("/quests/new?period=" + period, "plus",
                    daily ? "Créer une quête quotidienne" : "Créer une quête hebdomadaire")).append("</p>");
        }
        return sb.append("</section>").toString();
    }

    private static String settingsBlock(String period, Map<String, Object> settings) {
        if (settings == null) {
            return Ui.banner("info", "Réglages du serveur pas encore relevés : cliquer sur « Quêtes » "
                    + "(rafraîchir le catalogue).");
        }
        boolean daily = DAILY.equals(period);
        String time = String.valueOf(settings.getOrDefault("resetTime", "?"));
        String zone = String.valueOf(settings.getOrDefault("timezone", "?"));
        Integer count = intOf(settings.get(daily ? "dailyCount" : "weeklyCount"));
        String renewal = daily ? "chaque jour à " + time
                : "chaque " + dayName(String.valueOf(settings.get("weeklyResetDay"))) + " à " + time;
        StringBuilder sb = new StringBuilder();
        if (Boolean.FALSE.equals(settings.get("enabled"))) {
            sb.append(Ui.banner("warn", "Quêtes périodiques <strong>désactivées</strong> sur le serveur : "
                    + "les joueurs ne voient pas ces onglets."));
        }
        sb.append("<dl class=\"detail-dl\" data-periodic-settings>");
        sb.append(row("Par joueur", count == null ? "?" : count + (daily ? " par jour" : " par semaine")));
        sb.append(row("Renouvellement", renewal + " (" + zone + ")"));
        sb.append(row("Non terminée à l'échéance", Boolean.TRUE.equals(settings.get("expiredReward"))
                ? "récompensée" : "expire, sans récompense"));
        sb.append("</dl>");
        sb.append("<p class=\"form-text\">Valeurs de démonstration, en attente de décision du propriétaire. "
                + "Elles se règlent dans la section <code>periodic-quests</code> du <code>config.yml</code> du "
                + "serveur, pas depuis PlugAdmin.</p>");
        return sb.toString();
    }

    private static String row(String key, String value) {
        return "<div class=\"dl-row\"><dt>" + Http.esc(key) + "</dt><dd>" + Http.esc(value) + "</dd></div>";
    }

    static String dayName(String day) {
        return switch (day == null ? "" : day.toUpperCase(Locale.ROOT)) {
            case "MONDAY" -> "lundi";
            case "TUESDAY" -> "mardi";
            case "WEDNESDAY" -> "mercredi";
            case "THURSDAY" -> "jeudi";
            case "FRIDAY" -> "vendredi";
            case "SATURDAY" -> "samedi";
            case "SUNDAY" -> "dimanche";
            default -> "?";
        };
    }

    private static Integer intOf(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
