package com.lodygames.rpgquest.panel.dbtarget;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.web.Icons;
import com.lodygames.rpgquest.panel.web.Ui;
import java.util.List;

/**
 * Page {@code /database} (issue #42) : backend actif réel, base cible MariaDB (paramètres + mot de
 * passe chiffré + test de connexion) et assistant de migration en étapes.
 *
 * <p><strong>Préparation uniquement</strong> (consigne propriétaire du 13/10/2026) : la page ne
 * contient aucune action de migration ni de bascule ; l'étape finale est un bouton
 * <em>désactivé</em>, sans route serveur derrière lui.</p>
 *
 * <p>Le mot de passe n'est jamais renvoyé au navigateur : champ toujours vide,
 * {@code autocomplete="new-password"}, seul l'état « configuré / non configuré » est affiché.</p>
 */
public final class DbTargetPages {

    /** Backend réellement actif, lu dans le dernier heartbeat du plugin. */
    public record ActiveBackend(String engine, int schemaVersion, String heartbeatAge) {
        public static ActiveBackend unknown(String reason) {
            return new ActiveBackend("", 0, reason);
        }

        boolean known() {
            return !engine.isEmpty() && !"unknown".equals(engine);
        }

        String label() {
            return switch (engine) {
                case "sqlite" -> "SQLite (data.db)";
                case "mysql" -> "MariaDB / MySQL";
                default -> "inconnu";
            };
        }
    }

    private DbTargetPages() {
    }

    public static String render(DbTargetSettings settings, ActiveBackend backend, boolean canWrite,
                                String notice, String error, DbConnectionTester.Result testResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>").append(Icons.icon("server")).append("Base de données</h1>");
        sb.append(Ui.banner("info", "<strong>Préparation uniquement.</strong> Cette page enregistre et "
                + "<em>teste</em> une base MariaDB cible ; elle ne migre rien et ne change pas le backend du "
                + "serveur. La migration se fait hors panel avec l'outil dédié, et la bascule exige une "
                + "autorisation explicite du propriétaire (issue #42)."));
        if (notice != null) {
            sb.append(Ui.banner("success", Http.esc(notice)));
        }
        if (error != null) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        sb.append(activeCard(backend));
        sb.append(targetCard(settings, canWrite, testResult));
        sb.append(wizardCard(settings, backend));
        return sb.toString();
    }

    private static String activeCard(ActiveBackend backend) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("server", "Backend actif réel du serveur"));
        if (backend.known()) {
            sb.append("<p class=\"meta-line\"><span class=\"badge text-bg-primary\">")
                    .append(Http.esc(backend.label())).append("</span> schéma attendu V")
                    .append(backend.schemaVersion()).append(" <span class=\"muted\">— heartbeat ")
                    .append(Http.esc(backend.heartbeatAge())).append("</span></p>");
        } else {
            sb.append("<p class=\"meta-line\"><span class=\"badge text-bg-secondary\">inconnu</span> <span "
                    + "class=\"muted\">").append(Http.esc(backend.heartbeatAge())).append("</span></p>");
        }
        sb.append("<p class=\"field-help\">Source : le heartbeat du plugin, c'est-à-dire le moteur avec "
                + "lequel RPGQuest tourne <strong>réellement</strong>. Une cible testée ci-dessous ne change "
                + "jamais cette valeur.</p>");
        return sb.append("</section>").toString();
    }

    private static String targetCard(DbTargetSettings s, boolean canWrite, DbConnectionTester.Result testResult) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("admin", "Base MariaDB cible (migration future)"));

        sb.append("<p class=\"meta-line\">");
        sb.append(s.configured() ? "<span class=\"badge text-bg-info\">paramètres enregistrés</span>"
                : "<span class=\"badge text-bg-warning\">non configurée</span>");
        if (s.passwordUnreadable()) {
            sb.append(" <span class=\"badge text-bg-danger\">mot de passe illisible — à ressaisir</span>");
        } else {
            sb.append(s.passwordConfigured() ? " <span class=\"badge text-bg-info\">mot de passe configuré</span>"
                    : " <span class=\"badge text-bg-warning\">mot de passe non configuré</span>");
        }
        sb.append("</p>");
        if (!s.updatedAt().isEmpty()) {
            sb.append("<p class=\"muted\">Dernière modification : ").append(Http.esc(s.updatedAt()))
                    .append(" par ").append(Http.esc(s.updatedBy())).append("</p>");
        }

        if (testResult != null) {
            sb.append(Ui.banner(testResult.ok() ? "success" : "error", Http.esc(testResult.summary())));
        } else if (!s.lastTestAt().isEmpty()) {
            sb.append("<p class=\"muted\">Dernier test (").append(Http.esc(s.lastTestAt())).append(") : ")
                    .append(s.lastTestOk() ? "<span class=\"badge text-bg-success\">connexion testée</span> "
                            : "<span class=\"badge text-bg-danger\">échec</span> ")
                    .append(Http.esc(s.lastTestSummary())).append("</p>");
        }
        sb.append("<p class=\"field-help\"><strong>Connexion testée ≠ serveur migré</strong> : le test ouvre "
                + "une session en lecture seule et ne lit que des métadonnées.</p>");

        if (!canWrite) {
            return sb.append("</section>").toString();
        }
        sb.append("<form method=\"post\" action=\"/database\" class=\"actform\" autocomplete=\"off\">%CSRF%");
        sb.append("<div class=\"form-grid\">");
        sb.append(field("db-host", "host", "Hôte", s.host(), "nom DNS ou adresse IP",
                "Sans schéma ni port. Jamais versionné dans Git."));
        sb.append(field("db-port", "port", "Port", String.valueOf(s.port()), "3306", "Par défaut 3306."));
        sb.append(field("db-name", "database", "Base", s.database(), "rpgquest",
                "Base DÉDIÉE à RPGQuest, vide avant migration."));
        sb.append(field("db-user", "username", "Compte", s.username(), "rpgquest",
                "Compte dédié limité à cette base — jamais le compte administrateur global."));
        sb.append("<div class=\"field\"><label for=\"db-ssl\">Chiffrement TLS</label><select id=\"db-ssl\" name=\"sslMode\">");
        for (String mode : List.of("disable", "trust", "verify-ca", "verify-full")) {
            sb.append("<option value=\"").append(mode).append('"')
                    .append(mode.equalsIgnoreCase(s.sslMode()) ? " selected" : "").append('>')
                    .append(mode).append("</option>");
        }
        sb.append("</select><p class=\"field-help\">Utiliser TLS dès que le serveur le permet "
                + "(verify-full de préférence).</p></div>");
        sb.append("<div class=\"field full\"><label for=\"db-pw\">Mot de passe</label>");
        sb.append("<input id=\"db-pw\" type=\"password\" name=\"password\" value=\"\" autocomplete=\"new-password\" "
                + "spellcheck=\"false\" placeholder=\"")
                .append(s.passwordConfigured() ? "laisser vide pour conserver le mot de passe actuel"
                        : "saisir le mot de passe").append("\">");
        sb.append("<p class=\"field-help\">Stocké chiffré (AES-256-GCM) ; jamais réaffiché, jamais journalisé. "
                + "Un champ vide conserve le mot de passe enregistré.</p></div>");
        sb.append("</div><div class=\"btnrow\">");
        sb.append("<button class=\"btn\" type=\"submit\" name=\"_action\" value=\"save\">")
                .append(Icons.icon("save")).append("Enregistrer</button>");
        sb.append("<button class=\"btn secondary\" type=\"submit\" name=\"_action\" value=\"test\">")
                .append(Icons.icon("check")).append("Enregistrer et tester la connexion</button>");
        sb.append("</div></form>");

        if (s.passwordConfigured()) {
            sb.append("<form method=\"post\" action=\"/database\" class=\"actform\">%CSRF%");
            sb.append("<input type=\"hidden\" name=\"_action\" value=\"clear\">");
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"confirm\" value=\"on\"> "
                    + "Je confirme l'effacement du mot de passe enregistré</label> ");
            sb.append("<button class=\"btn secondary\" type=\"submit\">").append(Icons.icon("admin"))
                    .append("Effacer le mot de passe</button></form>");
        }
        return sb.append("</section>").toString();
    }

    private static String wizardCard(DbTargetSettings s, ActiveBackend backend) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("docs", "Assistant de migration SQLite → MariaDB"));
        sb.append("<ul class=\"list-unstyled steps\">");
        step(sb, "1. État", backend.known() ? "done" : "todo",
                "Backend actif : " + Http.esc(backend.label()) + (backend.known()
                        ? ", schéma V" + backend.schemaVersion() + "." : " (heartbeat absent ou plugin ancien)."));
        step(sb, "2. Cible enregistrée", s.configured() && s.passwordConfigured() && !s.passwordUnreadable()
                ? "done" : "todo", "Hôte, base, compte et mot de passe enregistrés ci-dessus.");
        step(sb, "3. Connexion testée", s.lastTestOk() ? "done" : "todo", s.lastTestOk()
                ? "Dernier test réussi le " + Http.esc(s.lastTestAt()) + "."
                : "Aucun test réussi pour la cible actuelle.");
        step(sb, "4. Simulation (dry-run)", "manual", "Hors panel, sur une COPIE de data.db prise serveur "
                + "arrêté : <code>java -jar rpgquest-db-migration.jar dry-run --sqlite-file &lt;copie&gt; "
                + "--target-mysql cible.properties</code>. Le résultat doit être SUCCESS.");
        step(sb, "5. Comparaisons", "manual", "Lire le rapport du dry-run : chaque table « OK » (mêmes "
                + "comptages, mêmes empreintes logiques), séquences OK, « Source SQLite inchangée : OK ».");
        step(sb, "6. Bloquants connus", "done", "Aucun connu. #354 (textes libres limités à 191 caractères "
                + "côté MariaDB) est corrigé par le schéma V35 : la copie de data.db doit venir d'un plugin en "
                + "V35 ou plus récent, comme l'outil.");
        sb.append("</ul>");
        sb.append("<p><button class=\"btn\" type=\"button\" disabled title=\"Désactivé : aucune autorisation "
                + "de bascule\">").append(Icons.icon("admin")).append("7. Basculer le serveur sur MariaDB</button> "
                + "<span class=\"muted\">Désactivé — la bascule exige une autorisation écrite du propriétaire et "
                + "suit le runbook <code>docs/deployment/MIGRATION_SQLITE_MARIADB.md</code>. Aucune route du "
                + "panel ne l'effectue.</span></p>");
        return sb.append("</section>").toString();
    }

    private static void step(StringBuilder sb, String title, String state, String detailHtml) {
        String badge = switch (state) {
            case "done" -> "<span class=\"badge text-bg-success\">fait</span>";
            case "manual" -> "<span class=\"badge text-bg-secondary\">hors panel</span>";
            case "blocked" -> "<span class=\"badge text-bg-danger\">bloquant</span>";
            default -> "<span class=\"badge text-bg-warning\">à faire</span>";
        };
        sb.append("<li><strong>").append(Http.esc(title)).append("</strong> ").append(badge)
                .append("<br><span class=\"muted\">").append(detailHtml).append("</span></li>");
    }

    private static String field(String id, String name, String label, String value, String placeholder,
                                String help) {
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<input id=\"" + id + "\" type=\"text\" name=\"" + name + "\" value=\"" + Http.esc(value)
                + "\" placeholder=\"" + Http.esc(placeholder) + "\" spellcheck=\"false\">"
                + "<p class=\"field-help\">" + Http.esc(help) + "</p></div>";
    }
}
