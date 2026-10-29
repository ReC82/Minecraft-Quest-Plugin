package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.orch.OrchSnapshot;
import com.lodygames.rpgquest.panel.orch.OrchSnapshot.AutoEntry;
import com.lodygames.rpgquest.panel.orch.OrchSnapshot.SessionRun;
import com.lodygames.rpgquest.panel.orch.OrchSnapshot.Ticket;
import com.lodygames.rpgquest.panel.orch.OrchSnapshotReader;
import com.lodygames.rpgquest.panel.security.Session;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Page <strong>/orch</strong> (issue #907) : suivi de la file du superviseur {@code lqorch}, en
 * <strong>lecture seule</strong>. Aucun bouton : mettre un ticket de côté, le libérer ou changer
 * sa priorité reste une commande {@code lqorch} tant qu'une action serveur vérifiée, confirmée et
 * auditée n'a pas été décidée. Les données viennent uniquement de l'instantané publié par le
 * superviseur ({@link OrchSnapshotReader}) : aucun appel GitHub depuis le panel.
 */
public final class OrchPages {

    public static final String PATH = "/orch";
    /** Au-delà, l'instantané est signalé ancien ; le superviseur le republie chaque minute. */
    static final Duration STALE = Duration.ofMinutes(5);
    /** Au-delà, le superviseur est présumé arrêté ou planté. */
    static final Duration DEAD = Duration.ofMinutes(15);
    static final int DONE_SHOWN = 15;
    static final int SESSIONS_SHOWN = 20;

    private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE);
    private static final Pattern PR_NUMBER = Pattern.compile("#(\\d+)");

    /** Regroupement lisible des statuts du superviseur, dans l'ordre d'affichage. */
    enum Group {
        RUNNING("en-cours", "En cours", "Une session travaille (ou reprendra) sur ces tickets."),
        READY("prets", "Prêts", "En file : pris dans l'ordre de priorité, dès que le superviseur peut lancer une session."),
        WAITING("attente", "En attente du propriétaire", "Une version est livrée : il manque une recette en jeu ou une décision."),
        BLOCKED("bloques", "Bloqués", "Impossible d'avancer sans intervention (motif ci-dessous)."),
        DONE("termines", "Terminés récemment", "Rien ne reste à faire côté superviseur.");

        final String key;
        final String label;
        final String help;

        Group(String key, String label, String help) {
            this.key = key;
            this.label = label;
            this.help = help;
        }

        static Optional<Group> of(String status) {
            return switch (status == null ? "" : status) {
                case "RUNNING", "TESTING" -> Optional.of(RUNNING);
                case "READY" -> Optional.of(READY);
                case "WAITING_OWNER" -> Optional.of(WAITING);
                case "BLOCKED", "FAILED" -> Optional.of(BLOCKED);
                case "DONE" -> Optional.of(DONE);
                default -> Optional.empty();
            };
        }

        static Optional<Group> byKey(String key) {
            for (Group g : values()) {
                if (g.key.equals(key)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    private final OrchSnapshotReader reader;
    private final Supplier<Instant> clock;

    public OrchPages(OrchSnapshotReader reader, Supplier<Instant> clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public String render(Session session, Map<String, String> query) {
        Instant now = clock.get();
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("history", "File de développement",
                "Suivi du superviseur lqorch : tickets en cours, prêts, en attente et bloqués. Lecture seule.", ""));
        OrchSnapshotReader.Result result = reader.read();
        if (result.snapshot().isEmpty()) {
            sb.append(unavailable(result));
            return sb.toString();
        }
        OrchSnapshot snap = result.snapshot().get();
        Filter filter = Filter.of(query);
        sb.append(health(snap, now));
        sb.append(summary(snap, now));
        sb.append(filterForm(filter));
        boolean any = false;
        for (Group g : Group.values()) {
            if (filter.group.isEmpty() || filter.group.get() == g) {
                String section = section(snap, g, filter, now);
                any |= !section.isEmpty();
                sb.append(section);
            }
        }
        if (!any) {
            sb.append(Ui.empty("search", "Aucun ticket ne correspond à ces filtres."));
        }
        sb.append(autoExam(snap, filter, now));
        sb.append(sessions(snap, filter, now));
        sb.append("<p class=\"meta-line\">Les rapports de session sont dans <code>docs/claude-reports/</code>, "
                + "joints à la PR du ticket. Mettre un ticket de côté, le libérer ou changer sa priorité : "
                + "commandes <code>lqorch</code> sur le serveur (aucune action depuis cette page).</p>");
        return sb.toString();
    }

    // ---- Disponibilité et santé ----------------------------------------------------------------

    private String unavailable(OrchSnapshotReader.Result result) {
        String where = reader.path().map(p -> " (<code>" + Http.esc(p.toString()) + "</code>)").orElse("");
        String what = switch (result.state()) {
            case NOT_CONFIGURED -> "La vue n'est pas configurée sur ce panel.";
            case MISSING -> "Le superviseur n'a encore publié aucun instantané" + where
                    + ". Il le fait à chaque tour de boucle depuis #907 : installer la nouvelle version "
                    + "(<code>scripts/orchestrator/install.sh</code> puis <code>lqorch reload</code>).";
            case UNREADABLE -> "L'instantané du superviseur est illisible" + where + " : " + Http.esc(result.problem());
            default -> "L'instantané du superviseur est invalide" + where + " : " + Http.esc(result.problem());
        };
        return Ui.banner("err", "<strong>État du superviseur indisponible.</strong> " + what
                + " Cela ne dit rien de l'état réel des tickets : rien n'est affiché plutôt qu'un état faux.");
    }

    private String health(OrchSnapshot snap, Instant now) {
        StringBuilder sb = new StringBuilder();
        Duration age = Duration.between(snap.generatedAt(), now);
        OrchSnapshot.Supervisor sup = snap.supervisor();
        if (sup.alive() && age.compareTo(DEAD) > 0) {
            sb.append(Ui.banner("err", "<strong>Le superviseur ne publie plus depuis "
                    + Http.esc(ActionView.relativeTime(snap.generatedAt(), now).replace("il y a ", ""))
                    + ".</strong> Il est probablement arrêté ou planté : vérifier "
                    + "<code>lqorch status</code> et <code>systemctl status lodyquests-orch</code>. "
                    + "Les informations ci-dessous datent de " + Http.esc(when(snap.generatedAt())) + "."));
        } else if (sup.alive() && age.compareTo(STALE) > 0) {
            sb.append(Ui.banner("warn", "Instantané ancien (" + Http.esc(ActionView.relativeTime(snap.generatedAt(), now))
                    + ") : le superviseur le republie normalement chaque minute."));
        }
        if (!sup.alive()) {
            sb.append(Ui.banner("warn", "<strong>Superviseur arrêté</strong>"
                    + (sup.mode().startsWith("STOP") ? " à la demande (<code>lqorch stop</code>)" : "")
                    + " : aucune session ne démarrera avant <code>lqorch resume</code> / redémarrage du service. "
                    + "État au " + Http.esc(when(snap.generatedAt())) + "."));
        } else if ("PAUSED".equals(sup.mode())) {
            sb.append(Ui.banner("warn", "<strong>Superviseur en pause</strong> : la session en cours se termine, "
                    + "aucune nouvelle ne démarre (<code>lqorch resume</code> pour reprendre)."));
        }
        if (snap.version() > OrchSnapshot.SUPPORTED_VERSION) {
            sb.append(Ui.banner("info", "Instantané d'un superviseur plus récent (format " + snap.version()
                    + ") : certains champs peuvent manquer ici."));
        }
        return sb.toString();
    }

    private String summary(OrchSnapshot snap, Instant now) {
        OrchSnapshot.Supervisor sup = snap.supervisor();
        StringBuilder sb = new StringBuilder("<div class=\"cards orch-cards\">");
        String state = !sup.alive() ? "Arrêté" : "PAUSED".equals(sup.mode()) ? "En pause" : "Actif";
        sb.append(Ui.statCard("server", state, "Superviseur", !sup.alive() ? "err" : "Actif".equals(state) ? "ok" : "warn",
                Http.esc("mis à jour " + ActionView.relativeTime(snap.generatedAt(), now))));
        String current = snap.current().map(c -> "#" + c.issue()).orElse("Aucune");
        sb.append(Ui.statCard("history", current, "Session en cours", "",
                snap.current().map(c -> Http.esc(clip(c.title(), 60) + " — depuis " + when(c.startedAt()))).orElse("")));
        String next;
        String nextExtra;
        if (sup.nextStartAllowed()) {
            next = "Autorisée";
            nextExtra = snap.current().isPresent() ? "après la session en cours" : "";
        } else {
            next = "En attente";
            nextExtra = Http.esc(sup.nextStartReason()) + (sup.nextStartAt() == null ? ""
                    : " Reprise au plus tôt : " + Http.esc(when(sup.nextStartAt())) + ".");
        }
        sb.append(Ui.statCard("clock", next, "Prochaine session", sup.nextStartAllowed() ? "ok" : "", nextExtra));
        sb.append(Ui.statCard("diagnostics", sup.sessionsToday() + " / "
                + (sup.maxSessionsPerDay() > 0 ? sup.maxSessionsPerDay() : "∞"), "Sessions aujourd'hui (UTC)", "", ""));
        sb.append("</div>");
        if (snap.current().isEmpty() && !sup.idleReason().isBlank()) {
            sb.append("<p class=\"meta-line\">").append(Icons.icon("info"))
                    .append(Http.esc(sup.idleReason())).append("</p>");
        }
        return sb.toString();
    }

    // ---- Filtres (formulaire GET, sans JavaScript) ---------------------------------------------

    record Filter(String text, Optional<Group> group, Optional<Integer> rank) {
        static Filter of(Map<String, String> query) {
            String q = query.getOrDefault("q", "").trim();
            Optional<Group> group = Group.byKey(query.getOrDefault("statut", ""));
            String p = query.getOrDefault("prio", "");
            Optional<Integer> rank = p.matches("P[0-3]") ? Optional.of(p.charAt(1) - '0') : Optional.empty();
            return new Filter(q.length() > 80 ? q.substring(0, 80) : q, group, rank);
        }

        boolean matchesText(int issue, String... fields) {
            if (text.isEmpty()) {
                return true;
            }
            String needle = text.toLowerCase(Locale.ROOT).replaceFirst("^#", "");
            if (String.valueOf(issue).equals(needle)) {
                return true;
            }
            for (String f : fields) {
                if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesRank(Optional<Integer> known) {
            return rank.isEmpty() || rank.equals(known);
        }
    }

    private static String filterForm(Filter f) {
        StringBuilder sb = new StringBuilder("<form method=\"get\" action=\"").append(PATH)
                .append("\" class=\"filtersearch orch-filters\" role=\"search\">");
        sb.append("<input type=\"search\" name=\"q\" value=\"").append(Http.esc(f.text()))
                .append("\" placeholder=\"Numéro ou mot du titre…\" aria-label=\"Rechercher un ticket\">");
        sb.append("<select name=\"statut\" aria-label=\"Filtrer par état\"><option value=\"\">Tous les états</option>");
        for (Group g : Group.values()) {
            sb.append("<option value=\"").append(g.key).append('"')
                    .append(f.group().filter(x -> x == g).isPresent() ? " selected" : "")
                    .append('>').append(Http.esc(g.label)).append("</option>");
        }
        sb.append("</select><select name=\"prio\" aria-label=\"Filtrer par priorité\">")
                .append("<option value=\"\">Toutes priorités</option>");
        for (int r = 0; r <= 3; r++) {
            int rank = r;
            sb.append("<option value=\"P").append(r).append('"')
                    .append(f.rank().filter(x -> x == rank).isPresent() ? " selected" : "")
                    .append(">P").append(r).append("</option>");
        }
        sb.append("</select><button class=\"btn secondary sm\" type=\"submit\">").append(Icons.icon("filter"))
                .append("Filtrer</button>");
        if (!f.text().isEmpty() || f.group().isPresent() || f.rank().isPresent()) {
            sb.append("<a class=\"btn secondary sm\" href=\"").append(PATH).append("\">Tout afficher</a>");
        }
        sb.append("</form>");
        if (f.rank().isPresent()) {
            sb.append("<p class=\"meta-line\">La priorité P0–P3 vient des labels <code>priority:P*</code> vus au "
                    + "dernier examen de la file automatique (tickets <code>agent:auto</code>) ; les autres tickets "
                    + "n'ont qu'une priorité locale (« prio N ») et ne sont pas retenus par ce filtre.</p>");
        }
        return sb.toString();
    }

    // ---- Tickets par état ----------------------------------------------------------------------

    private String section(OrchSnapshot snap, Group group, Filter filter, Instant now) {
        List<Ticket> tickets = new ArrayList<>();
        for (Ticket t : snap.tickets()) {
            if (Group.of(t.status()).filter(g -> g == group).isPresent()
                    && filter.matchesText(t.issue(), t.title(), t.nextAction(), t.blockers())
                    && filter.matchesRank(snap.auto().rankOf(t.issue()))) {
                tickets.add(t);
            }
        }
        if (tickets.isEmpty()) {
            return "";
        }
        Comparator<Ticket> order = group == Group.DONE
                ? Comparator.comparing(Ticket::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparingInt(Ticket::priority).thenComparingInt(Ticket::issue);
        tickets.sort(order);
        int total = tickets.size();
        if (group == Group.DONE && total > DONE_SHOWN) {
            tickets = tickets.subList(0, DONE_SHOWN);
        }
        StringBuilder sb = new StringBuilder("<section class=\"orch-group\" id=\"").append(group.key).append("\">");
        sb.append("<h2 class=\"section-title\">").append(Http.esc(group.label)).append(" <span class=\"badge\">")
                .append(total).append("</span></h2>");
        sb.append("<p class=\"muted orch-help\">").append(Http.esc(group.help)).append("</p>");
        for (Ticket t : tickets) {
            sb.append(ticket(snap, t, now));
        }
        if (tickets.size() < total) {
            sb.append("<p class=\"meta-line\">").append(total - tickets.size())
                    .append(" ticket(s) terminé(s) plus ancien(s) non affiché(s) — rechercher par numéro.</p>");
        }
        return sb.append("</section>").toString();
    }

    private String ticket(OrchSnapshot snap, Ticket t, Instant now) {
        StringBuilder sb = new StringBuilder("<details class=\"orch-t\"><summary>");
        sb.append("<span class=\"orch-t-h\">").append(issueLink(snap, t.issue())).append(' ')
                .append(Http.esc(t.title().isBlank() ? "(sans titre)" : t.title())).append("</span>");
        sb.append("<span class=\"orch-t-b\">").append(statusPill(t.status())).append(' ')
                .append(priorityBadge(snap, t)).append(t.autoHold() ? " " + Ui.pill("Retenu", "neutral", "‖") : "");
        Instant seen = t.lastActivity() != null ? t.lastActivity() : t.updatedAt();
        if (seen != null) {
            sb.append(" <span class=\"faint\">").append(Http.esc(ActionView.relativeTime(seen, now))).append("</span>");
        }
        sb.append("</span>");
        String headline = headline(t, now);
        if (!headline.isBlank()) {
            sb.append("<span class=\"orch-t-why\">").append(Http.esc(headline)).append("</span>");
        }
        sb.append("</summary><div class=\"orch-t-body\">");
        field(sb, "Prochaine action", t.nextAction());
        field(sb, "Blocage / attente", t.blockers());
        field(sb, "Résumé de la dernière session", t.summary());
        field(sb, "Tests", t.tests());
        field(sb, "Note du superviseur", t.note());
        snap.auto().entryOf(t.issue()).ifPresent(e -> field(sb, "File automatique",
                e.eligible() ? "admissible (ouvert sur GitHub, agent:auto)" : "écarté : " + e.reason()));
        if (!t.deps().isBlank()) {
            field(sb, "Dépendances", t.deps());
        }
        if (!t.pr().isBlank() || !t.branch().isBlank()) {
            sb.append(Ui.metaLine("PR / branche", prLinks(snap, t.pr())
                    + (t.branch().isBlank() ? "" : " " + Ui.id(t.branch()))));
        }
        if (!t.lastSession().isBlank()) {
            sb.append(Ui.metaLine("Dernière session", Ui.id(t.lastSession()) + " — " + t.attempts()
                    + " tentative(s)" + (t.updatedAt() == null ? "" : ", mise à jour " + Http.esc(when(t.updatedAt())))));
        }
        if (!t.reports().isEmpty()) {
            StringBuilder reports = new StringBuilder();
            for (String r : t.reports()) {
                reports.append(Ui.id(r)).append(' ');
            }
            sb.append(Ui.metaLine("Rapports", reports.toString().trim()));
        }
        return sb.append("</div></details>").toString();
    }

    /** Une ligne lisible sous le titre : pourquoi le ticket est là. */
    private static String headline(Ticket t, Instant now) {
        return switch (Group.of(t.status()).orElse(Group.DONE)) {
            case READY -> t.notBefore() != null && t.notBefore().isAfter(now)
                    ? "Reprise au plus tôt " + when(t.notBefore()) + "." : clip(t.nextAction(), 160);
            case WAITING, BLOCKED -> clip(t.blockers().isBlank() ? t.nextAction() : t.blockers(), 160);
            case RUNNING -> clip(t.nextAction(), 160);
            case DONE -> clip(t.summary(), 160);
        };
    }

    private static void field(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(Ui.metaLine(label, Http.esc(value)));
        }
    }

    // ---- File automatique ----------------------------------------------------------------------

    private String autoExam(OrchSnapshot snap, Filter filter, Instant now) {
        if (filter.group().isPresent()) {
            return "";
        }
        OrchSnapshot.AutoExam auto = snap.auto();
        StringBuilder sb = new StringBuilder("<section class=\"orch-group\" id=\"auto\">");
        sb.append("<h2 class=\"section-title\">File automatique (agent:auto)</h2>");
        if (!auto.enabled()) {
            sb.append(Ui.banner("info", "Sélection automatique désactivée (<code>lqorch config set auto_select 1</code>)."));
        }
        sb.append("<p class=\"muted orch-help\">Quand la file locale est vide, le superviseur examine les tickets "
                + "<strong>ouverts sur GitHub</strong> qui portent le label <code>agent:auto</code> et prend le plus "
                + "prioritaire qui est <strong>admissible</strong>. Un ticket ouvert n'est pas forcément admissible : "
                + "le motif est donné pour chacun. « Aucun admissible » est un état normal, pas une panne.</p>");
        if (auto.examinedAt() == null) {
            sb.append(Ui.empty("info", "Pas encore d'examen depuis le démarrage du superviseur : il n'a lieu que quand la file locale est vide."));
            return sb.append("</section>").toString();
        }
        sb.append("<p class=\"meta-line\">Dernier examen : ").append(Http.esc(when(auto.examinedAt())))
                .append(" (").append(Http.esc(ActionView.relativeTime(auto.examinedAt(), now))).append(")</p>");
        List<AutoEntry> eligible = auto.eligible().stream().filter(e -> keep(filter, e)).toList();
        sb.append("<p><strong>Admissibles, dans l'ordre de prise :</strong> ");
        if (eligible.isEmpty()) {
            sb.append("aucun.</p>");
        } else {
            sb.append("</p><ol class=\"orch-auto\">");
            for (AutoEntry e : eligible) {
                sb.append("<li>").append(issueLink(snap, e.issue())).append(' ').append(Http.esc(e.title())).append(' ')
                        .append(e.rank().map(r -> Ui.badge("P" + r)).orElse(""))
                        .append(e.rearm().isBlank() ? "" : " <span class=\"faint\">repris : retour du propriétaire</span>")
                        .append("</li>");
            }
            sb.append("</ol>");
        }
        List<AutoEntry> excluded = auto.excluded().stream().filter(e -> keep(filter, e))
                .sorted(Comparator.comparingInt(AutoEntry::issue)).toList();
        if (!excluded.isEmpty()) {
            sb.append("<details class=\"orch-excluded\"><summary>Écartés (").append(excluded.size())
                    .append(") et pourquoi</summary>");
            sb.append("<ul class=\"orch-list\">");
            for (AutoEntry e : excluded) {
                sb.append("<li><span class=\"orch-li-h\">").append(issueLink(snap, e.issue())).append(' ')
                        .append(Http.esc(clip(e.title(), 90))).append(e.rank().map(r -> " " + Ui.badge("P" + r)).orElse(""))
                        .append("</span><span class=\"orch-li-d\">").append(Http.esc(e.reason())).append("</span></li>");
            }
            sb.append("</ul></details>");
        }
        return sb.append("</section>").toString();
    }

    private static boolean keep(Filter filter, AutoEntry e) {
        return filter.matchesText(e.issue(), e.title(), e.reason()) && filter.matchesRank(e.rank());
    }

    // ---- Historique des sessions ---------------------------------------------------------------

    private String sessions(OrchSnapshot snap, Filter filter, Instant now) {
        List<SessionRun> runs = snap.sessions().stream()
                .filter(s -> filter.matchesText(s.issue(), s.title(), s.detail()))
                .limit(SESSIONS_SHOWN).toList();
        if (runs.isEmpty() || filter.group().isPresent()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"orch-group\" id=\"sessions\">");
        sb.append("<h2 class=\"section-title\">Dernières sessions</h2>");
        sb.append("<ul class=\"orch-list\">");
        for (SessionRun s : runs) {
            sb.append("<li><span class=\"orch-li-h\">").append(issueLink(snap, s.issue())).append(' ')
                    .append(s.endedAt() == null ? Ui.pill("En cours", "pending", "○") : outcome(s.outcome()))
                    .append(" <span class=\"faint\">").append(Http.esc(when(s.startedAt()))).append(" · ")
                    .append(Http.esc(duration(s.startedAt(), s.endedAt() == null ? now : s.endedAt())))
                    .append("</span></span>");
            if (!s.detail().isBlank()) {
                sb.append("<span class=\"orch-li-d\">").append(Http.esc(clip(s.detail(), 200))).append("</span>");
            }
            sb.append("</li>");
        }
        return sb.append("</ul></section>").toString();
    }

    // ---- Briques -------------------------------------------------------------------------------

    private static String issueLink(OrchSnapshot snap, int issue) {
        if (issue == 0) {
            return "<span class=\"badge\">Tri</span>";
        }
        if (!snap.repo().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return "#" + issue;
        }
        return "<a href=\"https://github.com/" + Http.esc(snap.repo()) + "/issues/" + issue
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">#" + issue + "</a>";
    }

    private static String prLinks(OrchSnapshot snap, String pr) {
        if (pr == null || pr.isBlank()) {
            return "";
        }
        if (!snap.repo().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            return Http.esc(pr);
        }
        // Le champ est libre (« #633 (code), #634 (rapport) ») : seuls les numéros deviennent des liens.
        String escaped = Http.esc(pr);
        return PR_NUMBER.matcher(escaped).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                "<a href=\"https://github.com/" + Http.esc(snap.repo()) + "/pull/" + m.group(1)
                        + "\" target=\"_blank\" rel=\"noopener noreferrer\">#" + m.group(1) + "</a>"));
    }

    private static String priorityBadge(OrchSnapshot snap, Ticket t) {
        return snap.auto().rankOf(t.issue()).map(r -> Ui.badge("P" + r))
                .orElse("<span class=\"badge\" title=\"Priorité locale du superviseur (plus petit = plus urgent)\">prio "
                        + t.priority() + "</span>");
    }

    static String statusPill(String status) {
        return switch (status == null ? "" : status) {
            case "RUNNING" -> Ui.pill("En cours", "pending", "○");
            case "TESTING" -> Ui.pill("Vérification", "pending", "○");
            case "READY" -> Ui.pill("Prêt", "delivered", "→");
            case "WAITING_OWNER" -> Ui.pill("Attend le propriétaire", "neutral", "…");
            case "BLOCKED" -> Ui.pill("Bloqué", "failed", "✕");
            case "FAILED" -> Ui.pill("Échec répété", "failed", "✕");
            case "DONE" -> Ui.pill("Terminé", "success", "✓");
            default -> Ui.pill(status == null || status.isBlank() ? "?" : status, "neutral", "•");
        };
    }

    static String outcome(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "COMPLETED" -> Ui.pill("Rapport rendu", "success", "✓");
            case "RATE_LIMIT" -> Ui.pill("Limite fournisseur", "pending", "!");
            case "TIMEOUT" -> Ui.pill("Coupée (durée)", "failed", "✕");
            case "STOPPED" -> Ui.pill("Arrêtée", "neutral", "‖");
            case "TRANSIENT" -> Ui.pill("Erreur passagère", "pending", "!");
            case "CRASH", "ERROR" -> Ui.pill("Échec", "failed", "✕");
            default -> Ui.pill(outcome == null || outcome.isBlank() ? "?" : outcome, "neutral", "•");
        };
    }

    static String when(Instant at) {
        return at == null ? "?" : WHEN.format(at);
    }

    static String duration(Instant from, Instant to) {
        if (from == null || to == null) {
            return "?";
        }
        long min = Math.max(0, Duration.between(from, to).toMinutes());
        return min < 60 ? min + " min" : (min / 60) + " h " + String.format("%02d", min % 60);
    }

    private static String clip(String text, int max) {
        String t = text == null ? "" : text.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
