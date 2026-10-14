package com.lodygames.rpgquest.panel.dbtarget;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.web.Icons;
import com.lodygames.rpgquest.panel.web.Ui;
import java.util.List;

/**
 * Page {@code /database} (issue #42) : backend actif réel, base cible MariaDB (paramètres + mot de
 * passe chiffré + test de connexion) et assistant de migration en étapes. Issue #43 : seconde
 * section pour la base <strong>propre au panel</strong> — stockage actif ({@code control-panel.db}),
 * cible MariaDB enregistrée mais non utilisée, test d'isolement et plan de migration du panel.
 *
 * <p><strong>Préparation uniquement</strong> (consigne propriétaire du 13/10/2026) : la page ne
 * contient aucune action de migration ni de bascule ; les étapes finales sont des boutons
 * <em>désactivés</em>, sans route serveur derrière eux.</p>
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

    /**
     * Section « base du panel » (issue #43).
     *
     * @param target  cible MariaDB du panel enregistrée (jamais utilisée par le panel)
     * @param tables  inventaire de la base active {@code control-panel.db}
     * @param warning avertissement non bloquant (compte partagé avec la cible RPGQuest…), ou {@code null}
     */
    public record PanelSection(DbTargetSettings target, List<PanelDbInventory.Table> tables, String warning) {
    }

    private DbTargetPages() {
    }

    /**
     * @param testedKind cible concernée par {@code testResult}, affiché dans sa propre section
     */
    public static String render(DbTargetSettings settings, PanelSection panel, ActiveBackend backend,
                                boolean canWrite, String notice, String error, DbTargetKind testedKind,
                                DbConnectionTester.Result testResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>").append(Icons.icon("server")).append("Base de données</h1>");
        sb.append(Ui.banner("info", "<strong>Préparation uniquement.</strong> Cette page enregistre et "
                + "<em>teste</em> des bases MariaDB cibles ; elle ne migre rien et ne change ni le backend du "
                + "serveur ni celui du panel. Toute migration ou bascule exige une autorisation explicite du "
                + "propriétaire (issues #42 et #43)."));
        if (notice != null) {
            sb.append(Ui.banner("success", Http.esc(notice)));
        }
        if (error != null) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        sb.append(activeCard(backend));
        sb.append(targetCard(settings, canWrite, testedKind == DbTargetKind.MIGRATION ? testResult : null));
        sb.append(wizardCard(settings, backend));
        sb.append(panelCard(panel, canWrite, testedKind == DbTargetKind.PANEL ? testResult : null));
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
        sb.append(targetBody(DbTargetKind.MIGRATION, s, canWrite, testResult));
        return sb.append("</section>").toString();
    }

    /** État, dernier test et formulaires d'une cible ; le champ caché {@code scope} désigne la cible. */
    private static String targetBody(DbTargetKind kind, DbTargetSettings s, boolean canWrite,
                                     DbConnectionTester.Result testResult) {
        boolean panel = kind == DbTargetKind.PANEL;
        String prefix = panel ? "pdb" : "db";
        String scope = "<input type=\"hidden\" name=\"scope\" value=\"" + kind.auditLabel() + "\">";
        StringBuilder sb = new StringBuilder("<p class=\"meta-line\">");
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
        sb.append(panel
                ? "<p class=\"field-help\"><strong>Connexion testée ≠ panel migré</strong> : le test ouvre une "
                        + "session en lecture seule, ne lit que des métadonnées et échoue si le compte voit une "
                        + "base RPGQuest.</p>"
                : "<p class=\"field-help\"><strong>Connexion testée ≠ serveur migré</strong> : le test ouvre "
                        + "une session en lecture seule et ne lit que des métadonnées.</p>");

        if (!canWrite) {
            return sb.toString();
        }
        sb.append("<form method=\"post\" action=\"/database\" class=\"actform\" autocomplete=\"off\">%CSRF%")
                .append(scope);
        sb.append("<div class=\"form-grid\">");
        sb.append(field(prefix + "-host", "host", "Hôte", s.host(), "nom DNS ou adresse IP",
                "Sans schéma ni port. Jamais versionné dans Git."));
        sb.append(field(prefix + "-port", "port", "Port", String.valueOf(s.port()), "3306", "Par défaut 3306."));
        sb.append(panel
                ? field(prefix + "-name", "database", "Base", s.database(), "rpgquest_control",
                        "Base DÉDIÉE au panel, distincte de la base RPGQuest.")
                : field(prefix + "-name", "database", "Base", s.database(), "rpgquest",
                        "Base DÉDIÉE à RPGQuest, vide avant migration."));
        sb.append(panel
                ? field(prefix + "-user", "username", "Compte", s.username(), "rpgquest_panel",
                        "Compte dédié au panel, sans aucun droit sur la base RPGQuest.")
                : field(prefix + "-user", "username", "Compte", s.username(), "rpgquest",
                        "Compte dédié limité à cette base — jamais le compte administrateur global."));
        sb.append("<div class=\"field\"><label for=\"").append(prefix).append("-ssl\">Chiffrement TLS</label>")
                .append("<select id=\"").append(prefix).append("-ssl\" name=\"sslMode\">");
        for (String mode : List.of("disable", "trust", "verify-ca", "verify-full")) {
            sb.append("<option value=\"").append(mode).append('"')
                    .append(mode.equalsIgnoreCase(s.sslMode()) ? " selected" : "").append('>')
                    .append(mode).append("</option>");
        }
        sb.append("</select><p class=\"field-help\">Utiliser TLS dès que le serveur le permet "
                + "(verify-full de préférence).</p></div>");
        sb.append("<div class=\"field full\"><label for=\"").append(prefix).append("-pw\">Mot de passe</label>");
        sb.append("<input id=\"").append(prefix).append("-pw\" type=\"password\" name=\"password\" value=\"\" "
                + "autocomplete=\"new-password\" spellcheck=\"false\" placeholder=\"")
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
            sb.append("<form method=\"post\" action=\"/database\" class=\"actform\">%CSRF%").append(scope);
            sb.append("<input type=\"hidden\" name=\"_action\" value=\"clear\">");
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"confirm\" value=\"on\"> "
                    + "Je confirme l'effacement du mot de passe enregistré</label> ");
            sb.append("<button class=\"btn secondary\" type=\"submit\">").append(Icons.icon("admin"))
                    .append("Effacer le mot de passe</button></form>");
        }
        return sb.toString();
    }

    private static String panelCard(PanelSection panel, boolean canWrite, DbConnectionTester.Result testResult) {
        StringBuilder sb = new StringBuilder("<section class=\"card\" id=\"panel-db\">");
        sb.append(Ui.sectionTitle("server", "Base propre au panel (PlugAdmin)"));
        sb.append("<p>Le panel garde ses <strong>propres</strong> données d'administration : comptes, groupes, "
                + "audit, file d'actions de l'agent, brouillons, réglages. Les données de jeu (joueurs, quêtes, "
                + "variables, économie…) restent la propriété de RPGQuest : le panel les lit et les modifie "
                + "uniquement via l'agent et le pont, jamais en SQL sur la base du plugin.</p>");

        sb.append("<h3>Stockage actif du panel</h3>");
        sb.append("<p class=\"meta-line\"><span class=\"badge text-bg-primary\">SQLite — control-panel.db</span> "
                + "<span class=\"badge text-bg-success\">actif</span> <span class=\"muted\">")
                .append(panel.tables().size()).append(" table(s)</span></p>");
        if (!panel.tables().isEmpty()) {
            sb.append("<details><summary>Inventaire des tables (lecture seule)</summary>"
                    + "<table class=\"table\"><thead><tr><th>Table</th><th>Lignes</th></tr></thead><tbody>");
            for (PanelDbInventory.Table table : panel.tables()) {
                sb.append("<tr><td><code>").append(Http.esc(table.name())).append("</code></td><td>")
                        .append(table.rows() < 0 ? "?" : String.valueOf(table.rows())).append("</td></tr>");
            }
            sb.append("</tbody></table></details>");
        }

        sb.append("<h3>Base MariaDB du panel</h3>");
        sb.append("<p class=\"meta-line\"><span class=\"badge text-bg-secondary\">enregistrée — non utilisée</span> "
                + "<span class=\"muted\">Le panel fonctionne toujours sur control-panel.db ; ces paramètres ne "
                + "servent qu'au test de connexion.</span></p>");
        if (panel.warning() != null) {
            sb.append(Ui.banner("warning", Http.esc(panel.warning())));
        }
        sb.append(targetBody(DbTargetKind.PANEL, panel.target(), canWrite, testResult));

        DbTargetSettings s = panel.target();
        sb.append("<h3>Plan de migration du panel</h3><ul class=\"list-unstyled steps\">");
        step(sb, "1. Inventaire", panel.tables().isEmpty() ? "todo" : "done",
                "Tables actuelles de control-panel.db, toutes propres au panel.");
        step(sb, "2. Base dédiée enregistrée", s.configured() && s.passwordConfigured() && !s.passwordUnreadable()
                ? "done" : "todo", "Base et compte DISTINCTS de ceux de RPGQuest.");
        step(sb, "3. Connexion et isolement testés", s.lastTestOk() ? "done" : "todo", s.lastTestOk()
                ? "Dernier test réussi le " + Http.esc(s.lastTestAt()) + " : aucune base RPGQuest visible."
                : "Aucun test réussi pour la base du panel enregistrée.");
        step(sb, "4. Couche SQL portable du panel", "manual", "Prérequis de code non réalisé : historique de "
                + "migrations propre au panel et SQL compatible MariaDB (voir "
                + "<code>docs/deployment/PANEL_DATABASE.md</code>).");
        sb.append("</ul>");
        sb.append("<p><button class=\"btn\" type=\"button\" disabled title=\"Désactivé : aucune autorisation "
                + "de migration du panel\">").append(Icons.icon("admin"))
                .append("5. Basculer le panel sur MariaDB</button> <span class=\"muted\">Désactivé — aucune route "
                        + "ne l'effectue ; la migration du panel exige une nouvelle autorisation du "
                        + "propriétaire.</span></p>");
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
