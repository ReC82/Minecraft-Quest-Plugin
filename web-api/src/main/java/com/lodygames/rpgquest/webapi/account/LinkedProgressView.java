package com.lodygames.rpgquest.webapi.account;

import com.lodygames.rpgquest.webapi.site.Html;
import com.lodygames.rpgquest.webapi.site.PortalIcons;
import com.lodygames.rpgquest.webapi.site.StatLabels;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Rendu « Ma progression » de la page {@code /compte} (issue #53) : niveaux par piste, quêtes,
 * place sur chaque classement — pour le seul titulaire du compte relié. Même vocabulaire visuel que
 * les fiches joueur publiques (#56), sans aucun autre pseudo.
 */
public final class LinkedProgressView {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)
            .withZone(ZoneId.of("Europe/Brussels"));

    private LinkedProgressView() {
    }

    /** Bloc complet ; {@code demo} adapte la note finale (valeurs fictives). */
    public static String render(String playerName, LinkedProgress progress, Instant receivedAt, boolean demo) {
        StringBuilder html = new StringBuilder(PortalIcons.heading("h2", "xp", "Ma progression"));
        html.append(hero(playerName, progress));
        html.append(tracks(progress));
        html.append(boards(progress));
        html.append("<p class=\"note\">");
        if (demo) {
            html.append("Valeurs fictives, pour montrer la page.");
        } else {
            html.append("Visible seulement par toi, quand tu es connecté — même si ton profil public est privé. "
                    + "Envoyé par le serveur de jeu le ").append(Html.escape(DATE.format(receivedAt)))
                    .append(" (à ta déconnexion et pendant que tu joues).");
        }
        return html.append(" Les rangs comptent tous les joueurs classés, profils privés compris.</p>").toString();
    }

    /** Aucun envoi reçu depuis la liaison : explique quand il arrivera, sans valeur inventée. */
    public static String pending() {
        return PortalIcons.heading("h2", "xp", "Ma progression") + PortalIcons.empty("xp",
                "Ta progression arrivera ici après ta prochaine déconnexion du serveur, ou au plus tard un quart "
                        + "d'heure après ta connexion.");
    }

    private static String hero(String playerName, LinkedProgress progress) {
        StringBuilder html = new StringBuilder("<section class=\"profile-hero\"><span class=\"avatar\" aria-hidden=\"true\">")
                .append(Html.escape(playerName.isEmpty() ? "?" : playerName.substring(0, 1).toUpperCase(Locale.ROOT)))
                .append("</span><div>");
        progress.skills().stream().filter(skill -> skill.skill().equals("GLOBAL")).findFirst().ifPresent(global ->
                html.append("<p><span class=\"big\">Niveau ").append(global.level()).append("</span> — ")
                        .append(StatLabels.number(global.xp())).append(" XP d'expérience totale</p>"));
        LinkedProgress.Standing quests = progress.quests();
        html.append("<p>").append(PortalIcons.icon("scroll", 16)).append(" <strong>")
                .append(StatLabels.number(quests.value())).append("</strong> ")
                .append(quests.value() == 1 ? "quête terminée" : "quêtes terminées");
        if (quests.ranked()) {
            html.append(" — ").append(place(quests));
        }
        return html.append("</p></div></section>").toString();
    }

    private static String tracks(LinkedProgress progress) {
        long best = progress.skills().stream().filter(skill -> !skill.skill().equals("GLOBAL"))
                .mapToLong(LinkedProgress.Skill::xp).max().orElse(0);
        StringBuilder html = new StringBuilder(PortalIcons.heading("h3", "xp", "Pistes")).append("<ul class=\"tracks\">");
        for (LinkedProgress.Skill skill : progress.skills()) {
            if (skill.skill().equals("GLOBAL")) {
                continue;
            }
            long percent = best == 0 ? 0 : Math.round(skill.xp() * 100.0 / best);
            html.append("<li>").append(PortalIcons.tile(PortalIcons.forMetric("RPG_XP_" + skill.skill()), false))
                    .append("<strong>").append(Html.escape(StatLabels.label(skill.skill()))).append("</strong><span>niv. ")
                    .append(skill.level()).append(" · ").append(StatLabels.number(skill.xp()))
                    .append(" XP</span><span class=\"meter\" aria-hidden=\"true\"><span style=\"width:").append(percent)
                    .append("%\"></span></span></li>");
        }
        return html.append("</ul>").toString();
    }

    private static String boards(LinkedProgress progress) {
        if (progress.boards().isEmpty()) {
            return PortalIcons.heading("h3", "trophy", "Mes classements")
                    + PortalIcons.empty("trophy", "Aucun classement n'est ouvert sur le serveur pour le moment.");
        }
        StringBuilder html = new StringBuilder(PortalIcons.heading("h3", "trophy", "Mes classements"))
                .append("<ul class=\"medals\">");
        for (LinkedProgress.Board board : progress.boards()) {
            LinkedProgress.Standing standing = board.standing();
            String badge = standing.ranked() ? String.valueOf(standing.rank()) : "–";
            String podium = standing.ranked() && standing.rank() <= 3 ? " podium-" + standing.rank() : "";
            html.append("<li><span class=\"rank").append(podium).append("\">").append(badge).append("</span><span>")
                    .append(PortalIcons.icon(board.icon() != null ? board.icon() : PortalIcons.forMetric(board.metric()), 16))
                    .append(" <strong>").append(Html.escape(board.name())).append("</strong> · ")
                    .append(Html.escape(period(board.period()))).append(board.status().equals("ENDED") ? " (terminé)" : "")
                    .append("<br><span class=\"note\">");
            if (standing.ranked()) {
                html.append(StatLabels.number(standing.value())).append(" — ").append(place(standing));
            } else {
                html.append("Pas encore classé : joue pour y entrer !");
            }
            html.append("</span></span></li>");
        }
        return html.append("</ul>").toString();
    }

    private static String place(LinkedProgress.Standing standing) {
        return standing.rank() + (standing.rank() == 1 ? "<sup>er</sup>" : "<sup>e</sup>") + " sur "
                + StatLabels.number(standing.players()) + (standing.players() == 1 ? " joueur" : " joueurs");
    }

    static String period(String period) {
        return switch (period) {
            case "daily" -> "aujourd'hui";
            case "weekly" -> "cette semaine";
            case "monthly" -> "ce mois-ci";
            case "season" -> "saison";
            case "event" -> "événement";
            case "custom" -> "dates spéciales";
            case "lifetime" -> "depuis toujours";
            default -> period;
        };
    }
}
