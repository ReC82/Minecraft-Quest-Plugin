package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.ops.RestartService;
import com.lodygames.rpgquest.panel.security.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Page « Exploitation serveur » (issue #95, lot 1) : état réel du serveur et de l'agent, annonce
 * globale, redémarrage immédiat ou différé avec suivi jusqu'au retour en ligne, et console des
 * dernières lignes de log.
 *
 * <p><strong>Ce que la page ne fait pas, et le dit.</strong> Chaque fonction absente est affichée
 * avec son motif réel plutôt que masquée : démarrage/arrêt explicite (l'hébergeur n'expose pas
 * d'API), mode maintenance, sauvegarde restaurable, et rechargement de contenu (ticket #131) ou
 * actions joueur / OP-DEOP (ticket #210) qui sont des lots suivants. Un bouton qui semble marcher
 * sans marcher est pire qu'un bouton absent.</p>
 *
 * <p><strong>Aucune console libre.</strong> L'annonce passe par l'action agent whitelistée
 * {@code server.announce} (texte, jamais une commande) ; le redémarrage par
 * {@link RestartService}, qui n'émet que les trois commandes de
 * {@code RconCommand}. Le navigateur ne transporte jamais ni commande shell, ni commande RCON.</p>
 */
public final class OpsPages {

    /** Mot à saisir pour un redémarrage immédiat — confirmation forte, pas une simple case. */
    public static final String CONFIRM_WORD = "REDEMARRER";
    static final String CONFIRM_FIELD = "confirm_word";

    private final AgentStore agentStore;
    private final PermissionService permissions;
    private final RestartService restartService;
    private final AgentLiveness.Thresholds thresholds;
    private final String defaultAgentId;
    private final String consoleUnavailableFallback;

    public OpsPages(AgentStore agentStore, PermissionService permissions, RestartService restartService,
                    AgentLiveness.Thresholds thresholds, String defaultAgentId,
                    String consoleUnavailableFallback) {
        this.agentStore = agentStore;
        this.permissions = permissions;
        this.restartService = restartService;
        this.thresholds = thresholds;
        this.defaultAgentId = defaultAgentId;
        this.consoleUnavailableFallback = consoleUnavailableFallback;
    }

    /** Rend la page complète. {@code query} peut porter {@code agent}. */
    public String render(Session session, Map<String, String> query) {
        String agentId = agentOf(query);
        Instant now = Instant.now();
        Optional<HeartbeatRecord> heartbeat = agentId == null ? Optional.empty() : agentStore.latestHeartbeat(agentId);
        AgentLiveness liveness = AgentLiveness.of(heartbeat, thresholds, now);

        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("server", "Exploitation serveur",
                "État réel, annonce aux joueurs, redémarrage vérifié et console récente.", ""));
        sb.append(stateBlock(heartbeat, liveness, now, agentId));
        sb.append(restartBlock(session, agentId));
        if (permissions.can(session.role(), Permission.OPS_ANNOUNCE)) {
            sb.append(announceBlock(session, agentId, liveness, heartbeat));
        }
        if (permissions.can(session.role(), Permission.ACTION_CONTENT_RELOAD)) {
            sb.append(reloadBlock(session, agentId));
        }
        if (permissions.can(session.role(), Permission.OPS_LOGS)) {
            sb.append(consoleBlock(agentId));
        }
        sb.append(unavailableBlock());
        return sb.toString();
    }

    // ---- État serveur -------------------------------------------------------------------

    private String stateBlock(Optional<HeartbeatRecord> heartbeat, AgentLiveness liveness, Instant now,
                              String agentId) {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("server", "État du serveur"));
        if (agentId == null) {
            return sb.append(Ui.banner("warn", "Aucun agent RPGQuest configuré : l'état du serveur, "
                    + "l'annonce et la console sont indisponibles. Voir <code>agents</code> dans la "
                    + "configuration du panel.")).toString();
        }
        sb.append("<div class=\"cards\">");
        sb.append(Ui.statCard("agents", liveness.name(), "Agent " + agentId,
                switch (liveness) {
                    case ONLINE -> "ok";
                    case STALE -> "warn";
                    default -> "err";
                },
                heartbeat.map(hb -> "dernier relevé " + Http.esc(AgentLiveness.ageHuman(hb.receivedAt(), now)))
                        .orElse("aucun relevé reçu")));
        sb.append(Ui.statCard("players",
                heartbeat.map(hb -> hb.playersOnline() < 0 ? "—" : Long.toString(hb.playersOnline())).orElse("—"),
                "Joueurs connectés", "",
                heartbeat.filter(hb -> hb.maxPlayers() > 0)
                        .map(hb -> "capacité " + hb.maxPlayers()).orElse("")));
        sb.append(Ui.statCard("uptime", heartbeat.map(HeartbeatRecord::uptimeHuman).orElse("—"),
                "Uptime du plugin", "",
                heartbeat.map(hb -> "état annoncé : " + Http.esc(nz(hb.serverState()))).orElse("")));
        sb.append(Ui.statCard("version", heartbeat.map(hb -> nz(hb.pluginVersion())).orElse("—"),
                "Version du plugin", "",
                heartbeat.map(hb -> Http.esc(nz(hb.pluginName()))).orElse("")));
        sb.append("</div>");

        // La fraîcheur est une information de premier plan : tout ce bloc décrit le DERNIER RELEVÉ,
        // pas l'instant présent. Le dire évite de lire « ONLINE » comme une mesure en direct.
        sb.append(heartbeat
                .map(hb -> Ui.metaLine("Fraîcheur :",
                        "ces valeurs datent du dernier heartbeat ("
                                + Http.esc(AgentLiveness.ageHuman(hb.receivedAt(), now))
                                + ", envoyé toutes les ~20 s par l'agent) — ce n'est pas une mesure en direct."))
                .orElse(Ui.metaLine("Fraîcheur :", "aucune donnée : l'agent n'a jamais envoyé de relevé.")));
        return sb.toString();
    }

    // ---- Redémarrage ---------------------------------------------------------------------

    private String restartBlock(Session session, String agentId) {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("wrench", "Redémarrage"));
        RestartService.Availability availability = restartService.availability();
        RestartService.Operation operation = restartService.current();

        if (operation.phase() != RestartService.Phase.IDLE) {
            sb.append(operationCard(session, operation));
        }
        if (!availability.available()) {
            return sb.append(Ui.banner("warn", "<strong>Redémarrage indisponible.</strong> "
                    + Http.esc(nz(availability.reason())))).toString();
        }
        if (!permissions.can(session.role(), Permission.OPS_RESTART)) {
            return sb.append(Ui.banner("info",
                    "Votre rôle ne permet pas de redémarrer le serveur.")).toString();
        }
        if (operation.phase().active()) {
            return sb.append(Ui.banner("info", "Une opération est en cours : aucune nouvelle demande "
                    + "n'est acceptée tant qu'elle n'est pas terminée.")).toString();
        }

        sb.append("<div class=\"panelbox\">");
        // Différé : annulable, donc une case suffit. Immédiat : irréversible pour les joueurs
        // connectés, donc saisie explicite du mot. « Confirmations adaptées », pas uniformes.
        sb.append("<form method=\"post\" action=\"/ops/restart\" class=\"mb-3\">");
        sb.append(csrf(session));
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(nz(agentId))).append("\">");
        sb.append("<input type=\"hidden\" name=\"delay_seconds\" value=\"300\">");
        sb.append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" value=\"true\" "
                + "name=\"confirm\" id=\"ops-rst-5\" required>"
                + "<label class=\"form-check-label\" for=\"ops-rst-5\">Je confirme un redémarrage "
                + "<strong>dans 5 minutes</strong>, avec annonces aux joueurs (annulable).</label></div>");
        sb.append("<button class=\"btn btn-outline-primary mt-2\" type=\"submit\">")
                .append(Icons.icon("clock")).append("Redémarrer dans 5 minutes</button>");
        sb.append("</form>");

        sb.append("<form method=\"post\" action=\"/ops/restart\">");
        sb.append(csrf(session));
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(nz(agentId))).append("\">");
        sb.append("<input type=\"hidden\" name=\"delay_seconds\" value=\"0\">");
        sb.append("<label class=\"form-label\" for=\"ops-rst-now\">Redémarrage <strong>immédiat</strong> — "
                + "taper <code>").append(CONFIRM_WORD).append("</code> pour confirmer :</label>");
        sb.append("<input class=\"form-control\" style=\"max-width:16rem\" id=\"ops-rst-now\" name=\"")
                .append(CONFIRM_FIELD).append("\" autocomplete=\"off\" required>");
        sb.append("<button class=\"btn btn-outline-danger mt-2\" type=\"submit\">")
                .append(Icons.icon("warning")).append("Redémarrer maintenant</button>");
        sb.append("<p class=\"muted mt-2 mb-0\">Les joueurs connectés sont déconnectés. "
                + "PlugAdmin vérifie ensuite que le serveur revient réellement en ligne ; "
                + "un simple arrêt n'est jamais présenté comme un redémarrage.</p>");
        sb.append("</form></div>");
        return sb.toString();
    }

    /** Carte d'opération seule, pour le rafraîchissement JSON de {@code /ops/state.json}. */
    String operationCardHtml(Session session, RestartService.Operation operation) {
        return operation.phase() == RestartService.Phase.IDLE ? "" : operationCard(session, operation);
    }

    private String operationCard(Session session, RestartService.Operation operation) {
        String kind = switch (operation.phase()) {
            case DONE -> "ok";
            case FAILED -> "err";
            case CANCELLED -> "info";
            default -> "warn";
        };
        StringBuilder sb = new StringBuilder("<div class=\"panelbox\" data-ops-operation=\"")
                .append(Http.esc(operation.id())).append("\">");
        sb.append(Ui.banner(kind, "<strong>" + Http.esc(phaseLabel(operation.phase())) + "</strong> — "
                + Http.esc(nz(operation.detail())) + " " + Ui.id(operation.id())));
        if (operation.executeAt() != null && operation.phase() == RestartService.Phase.SCHEDULED) {
            sb.append(Ui.metaLine("Exécution prévue :", Http.esc(operation.executeAt().toString())));
        }
        if (!operation.steps().isEmpty()) {
            sb.append("<details><summary>Étapes réellement franchies (")
                    .append(operation.steps().size()).append(")</summary><ul class=\"small\">");
            for (String step : operation.steps()) {
                sb.append("<li>").append(Http.esc(step)).append("</li>");
            }
            sb.append("</ul></details>");
        }
        if (operation.phase() == RestartService.Phase.SCHEDULED
                && permissions.can(session.role(), Permission.OPS_RESTART)) {
            sb.append("<form method=\"post\" action=\"/ops/restart/cancel\" class=\"mt-2\">");
            sb.append(csrf(session));
            sb.append("<input type=\"hidden\" name=\"operation\" value=\"").append(Http.esc(operation.id())).append("\">");
            sb.append("<button class=\"btn btn-sm btn-outline-secondary\" type=\"submit\">")
                    .append(Icons.icon("error")).append("Annuler le redémarrage</button>");
            sb.append("</form>");
        }
        return sb.append("</div>").toString();
    }

    static String phaseLabel(RestartService.Phase phase) {
        return switch (phase) {
            case IDLE -> "Aucune opération";
            case SCHEDULED -> "Redémarrage programmé";
            case STOPPING -> "Arrêt en cours";
            case WAITING_BACK -> "Attente du retour en ligne";
            case DONE -> "Serveur revenu en ligne (vérifié)";
            case FAILED -> "Échec du redémarrage";
            case CANCELLED -> "Redémarrage annulé";
        };
    }

    // ---- Annonce -------------------------------------------------------------------------

    private String announceBlock(Session session, String agentId, AgentLiveness liveness,
                                 Optional<HeartbeatRecord> heartbeat) {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("bell", "Annonce globale"));
        if (agentId == null) {
            return sb.append(Ui.empty("Aucun agent : impossible d'envoyer une annonce.")).toString();
        }
        long online = heartbeat.map(HeartbeatRecord::playersOnline).orElse(-1L);
        if (liveness == AgentLiveness.OFFLINE || liveness == AgentLiveness.UNKNOWN) {
            sb.append(Ui.banner("warn", "L'agent ne répond pas : une annonce resterait en attente "
                    + "jusqu'à son retour."));
        } else if (online == 0) {
            sb.append(Ui.banner("info", "Aucun joueur connecté au dernier relevé : l'annonce ne sera "
                    + "affichée à personne, et le résultat le dira."));
        }
        sb.append("<div class=\"panelbox\">");
        sb.append("<form method=\"post\" action=\"/agents/action\" data-ops-announce>");
        sb.append(csrf(session));
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"type\" value=\"server.announce\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/ops\">");
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");

        sb.append("<p class=\"form-label mb-1\">Modèles rapides</p><div class=\"chips mb-2\">");
        for (String template : AgentActionCatalog.ANNOUNCE_TEMPLATES) {
            sb.append("<button type=\"button\" class=\"chip\" data-ops-template=\"")
                    .append(Http.esc(template)).append("\">").append(Http.esc(shorten(template, 34)))
                    .append("</button>");
        }
        sb.append("</div>");

        sb.append("<label class=\"form-label\" for=\"ops-msg\">Message</label>");
        sb.append("<input class=\"form-control\" id=\"ops-msg\" name=\"message\" maxlength=\"")
                .append(AgentActionCatalog.MAX_ANNOUNCE_CHARS)
                .append("\" required data-ops-message autocomplete=\"off\">");
        sb.append("<p class=\"muted mt-1 mb-2\"><span data-ops-count>0</span>/")
                .append(AgentActionCatalog.MAX_ANNOUNCE_CHARS)
                .append(" caractères. Le message est envoyé comme <strong>texte</strong> : "
                        + "ni commande, ni mise en forme interprétée.</p>");

        sb.append("<p class=\"form-label mb-1\">Canal</p><div class=\"mb-2\">");
        for (String channel : AgentActionCatalog.ANNOUNCE_CHANNELS) {
            boolean first = "chat".equals(channel);
            sb.append("<div class=\"form-check form-check-inline\">")
                    .append("<input class=\"form-check-input\" type=\"radio\" name=\"channel\" id=\"ops-ch-")
                    .append(Http.esc(channel)).append("\" value=\"").append(Http.esc(channel)).append("\"")
                    .append(first ? " checked" : "").append(" data-ops-channel>")
                    .append("<label class=\"form-check-label\" for=\"ops-ch-").append(Http.esc(channel))
                    .append("\">").append(Http.esc(channelLabel(channel))).append("</label></div>");
        }
        sb.append("</div>");

        sb.append("<p class=\"form-label mb-1\">Aperçu</p>");
        sb.append("<div class=\"ops-preview\" data-ops-preview aria-live=\"polite\">")
                .append("<span class=\"ops-preview-pfx\">[Serveur]</span> <span data-ops-preview-body "
                        + "class=\"ops-preview-body\">…</span></div>");
        sb.append("<button class=\"btn btn-outline-primary mt-2\" type=\"submit\">")
                .append(Icons.icon("check")).append("Envoyer l'annonce</button>");
        sb.append("</form></div>");
        return sb.toString();
    }

    static String channelLabel(String channel) {
        return switch (channel) {
            case "chat" -> "Chat (reste lisible dans l'historique)";
            case "actionbar" -> "Barre d'action (discret, au-dessus de la barre d'objets)";
            case "title" -> "Titre plein écran (impossible à manquer)";
            default -> channel;
        };
    }

    // ---- Rechargement du contenu (issue #131) ----------------------------------------------

    /**
     * Bloc de rechargement. Il énonce d'emblée les <strong>trois états distincts</strong> du
     * ticket, parce que la confusion entre eux est le défaut de fond : un administrateur qui a
     * enregistré une quête dans PlugAdmin (source, sur AWS) croit volontiers qu'elle est sur le
     * serveur. Elle ne l'est pas, et <strong>aucun rechargement ne la transférera</strong>.
     */
    private String reloadBlock(Session session, String agentId) {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("refresh", "Rechargement du contenu"));
        if (agentId == null) {
            return sb.append(Ui.empty("Aucun agent : rechargement indisponible.")).toString();
        }
        sb.append("<div class=\"panelbox\">");
        sb.append("<p class=\"muted mb-2\">Trois états, à ne pas confondre :</p>");
        sb.append("<ol class=\"small mb-3\">");
        sb.append("<li><strong>Enregistré dans la source</strong> — le fichier existe dans la source "
                + "éditable, sur AWS. C'est ce que font les éditeurs du panel.</li>");
        sb.append("<li><strong>Publié sur le serveur</strong> — le fichier est présent sur VeryGames. "
                + "Cela exige un <strong>déploiement</strong> : un rechargement ne transfère "
                + "<strong>rien</strong> depuis AWS.</li>");
        sb.append("<li><strong>Chargé en jeu</strong> — le serveur utilise réellement cette version. "
                + "C'est ce que fait le rechargement ci-dessous.</li>");
        sb.append("</ol>");
        sb.append(Ui.banner("info", "L'aperçu lit le <strong>disque du serveur</strong> : il dit donc "
                + "ce qui est réellement publié, et distingue « pas encore chargé » (un rechargement "
                + "suffit) de « jamais publié » (il faut déployer)."));

        sb.append("<form method=\"post\" action=\"/agents/action\" class=\"mt-2\">");
        sb.append(csrf(session));
        sb.append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"return\" value=\"/ops\">");
        sb.append("<p class=\"form-label mb-1\">Familles à traiter</p><div class=\"mb-2\">");
        for (String family : AgentActionCatalog.RELOAD_FAMILIES) {
            sb.append("<div class=\"form-check form-check-inline\">")
                    // Un nom DISTINCT par famille : des cases homonymes seraient écrasées par le
                    // parseur de formulaire, et cocher trois familles n'en rechargerait qu'une.
                    .append("<input class=\"form-check-input\" type=\"checkbox\" name=\"family_")
                    .append(Http.esc(family)).append("\" id=\"rl-").append(Http.esc(family))
                    .append("\" value=\"true\" checked>")
                    .append("<label class=\"form-check-label\" for=\"rl-").append(Http.esc(family))
                    .append("\">")
                    .append(Http.esc(AgentActionCatalog.RELOAD_FAMILY_LABELS.getOrDefault(family, family)))
                    .append("</label></div>");
        }
        sb.append("</div>");
        sb.append("<button class=\"btn btn-outline-secondary\" type=\"submit\" name=\"type\" "
                + "value=\"content.reload.preview\">")
                .append(Icons.icon("check")).append("Valider sans appliquer</button> ");
        sb.append("<button class=\"btn btn-outline-primary\" type=\"submit\" name=\"type\" "
                + "value=\"content.reload\">")
                .append(Icons.icon("refresh")).append("Recharger en jeu</button>");
        sb.append("<input type=\"hidden\" name=\"confirm\" value=\"true\">");
        sb.append("<p class=\"muted mt-2 mb-0\">Une erreur de contenu ou une référence cassée "
                + "<strong>annule tout</strong> : le runtime précédent est conservé. La progression, "
                + "les quêtes actives, les inventaires et les mobs déjà vivants ne sont jamais "
                + "touchés — aucune récompense n'est redistribuée, rien n'est despawné.</p>");
        sb.append("</form></div>");
        return sb.toString();
    }

    // ---- Console -------------------------------------------------------------------------

    private String consoleBlock(String agentId) {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("diagnostics", "Console récente"));
        if (agentId == null) {
            return sb.append(Ui.empty("Aucun agent : console indisponible.")).toString();
        }
        sb.append("<div class=\"toolbar\">");
        sb.append("<div class=\"search\">").append(Icons.icon("search"))
                .append("<input type=\"search\" data-ops-search placeholder=\"Filtrer le texte "
                        + "(RPGQuest, un pseudo, Citizens…)\" aria-label=\"Filtrer la console\"></div>");
        sb.append("<div class=\"chips\" data-ops-levels>");
        for (String level : new String[] {"ERROR", "WARN", "INFO"}) {
            sb.append("<button type=\"button\" class=\"chip on\" data-ops-level=\"").append(level)
                    .append("\">").append(level).append("</button>");
        }
        sb.append("</div>");
        sb.append("<div class=\"chips\">")
                .append("<button type=\"button\" class=\"chip\" data-ops-pause>Pause</button>")
                .append("<button type=\"button\" class=\"chip on\" data-ops-autoscroll>Suivi auto</button>")
                .append("<button type=\"button\" class=\"chip\" data-ops-bottom>Aller en bas</button>")
                .append("</div>");
        sb.append("</div>");
        sb.append("<p class=\"muted mb-1\" data-ops-console-status>Chargement de la console…</p>");
        sb.append("<pre class=\"ops-console\" data-ops-console tabindex=\"0\" "
                + "aria-label=\"Console serveur, lecture seule\"></pre>");
        sb.append("<p class=\"muted mt-1 mb-0\">Lecture seule. Les lignes remontent par l'agent sortant "
                + "(scrutation toutes les ~15 s) : la console est <strong>récente</strong>, pas "
                + "instantanée. Aucun terminal, aucune saisie possible ici.</p>");
        return sb.toString();
    }

    // ---- Indisponible / lots suivants ----------------------------------------------------

    private String unavailableBlock() {
        StringBuilder sb = new StringBuilder(Ui.sectionTitle("info", "Non disponible dans ce lot"));
        sb.append("<ul class=\"small\">");
        sb.append("<li><strong>Démarrer / arrêter explicitement</strong> — l'hébergeur n'expose aucune "
                + "API de supervision : un plugin arrêté ne peut pas se relancer lui-même, et PlugAdmin "
                + "ne prétendra pas le contraire. Seul le redémarrage (arrêt + relance automatique de "
                + "l'hébergeur) est possible.</li>");
        sb.append("<li><strong>Console complète de l'hébergeur</strong> — le fichier de log du serveur "
                + "n'est pas atteignable (racine FTP = <code>plugins/</code>, remontée de dossier "
                + "refusée par le serveur FTP). La console affichée est celle captée par le plugin.</li>");
        sb.append("<li><strong>Sauvegarde restaurable</strong> — <code>save-all</code> écrit les mondes "
                + "sur disque ; ce n'est pas un point de restauration. Rien ici ne doit être lu comme "
                + "une sauvegarde complète.</li>");
        sb.append("<li><strong>Mode maintenance</strong> et <strong>annonces programmées</strong> — "
                + "hors périmètre de ce lot.</li>");
        sb.append("<li><strong>Rechargement du contenu</strong> (quêtes, stories, dialogues, PNJ, "
                + "objets, profils de mobs) — ticket #131, lot suivant. L'emplacement est prévu ici.</li>");
        sb.append("<li><strong>Actions joueur et OP / DEOP</strong> — ticket #210, lot suivant, "
                + "accessible depuis les fiches joueurs.</li>");
        sb.append("</ul>");
        if (consoleUnavailableFallback != null && !consoleUnavailableFallback.isBlank()) {
            sb.append(Ui.banner("warn", Http.esc(consoleUnavailableFallback)));
        }
        return sb.toString();
    }

    // ---- Utilitaires ---------------------------------------------------------------------

    private String agentOf(Map<String, String> query) {
        String requested = query == null ? null : query.get("agent");
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return defaultAgentId;
    }

    private static String csrf(Session session) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">";
    }

    /** Dernière action d'annonce connue, pour afficher son résultat réel. */
    Optional<AgentActionRow> lastAnnounce(String agentId) {
        return agentId == null ? Optional.empty() : agentStore.latestActionOfType(agentId, "server.announce");
    }

    static String nz(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String shorten(String raw, int max) {
        return raw.length() <= max ? raw : raw.substring(0, max - 1) + "…";
    }

    /** Durées proposées pour un redémarrage différé — bornées par {@link RestartService#MAX_DELAY}. */
    static List<Duration> offeredDelays() {
        return List.of(Duration.ZERO, Duration.ofMinutes(5));
    }
}
