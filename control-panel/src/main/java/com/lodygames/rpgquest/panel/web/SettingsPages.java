package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Issue #335 — PlugAdmin > Paramètres généraux : les réglages du jeu, par rubrique, sans éditer de
 * fichier. La source de vérité est {@code settings.yml} sur le serveur, lu et écrit par le plugin
 * ({@code settings.get} / {@code settings.set}) : la page n'affiche que ce que le serveur a relu.
 */
public final class SettingsPages {

    /**
     * Ce que le panel sait des paramètres.
     *
     * @param values    valeurs relues sur le serveur (vide si jamais lues)
     * @param defaults  valeurs par défaut
     * @param readAt    date de la dernière lecture (ou de l'écriture relue), {@code null} si aucune
     * @param pending   un enregistrement est en cours
     * @param lastError motif du dernier enregistrement refusé, plus récent que la lecture ; vide sinon
     */
    public record State(Map<String, String> values, Map<String, String> defaults, Instant readAt,
                        String updatedAt, String updatedBy, String problem, boolean pending, String lastError) {
        boolean known() {
            return !values.isEmpty();
        }

        String value(String key) {
            return values.getOrDefault(key, defaults.getOrDefault(key, ""));
        }
    }

    static final List<String[]> TABS = List.of(
            new String[] {"economie", "Économie"},
            new String[] {"connexion", "Connexion"},
            new String[] {"administration", "Administration"},
            new String[] {"plus", "À venir"});

    private SettingsPages() {
    }

    public static String page(String csrf, String agentId, State st, boolean canWrite, String rawTab) {
        String tab = TABS.stream().map(t -> t[0]).filter(t -> t.equals(rawTab)).findFirst().orElse("economie");
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("admin", "Paramètres généraux",
                "Les réglages du jeu, appliqués à tous les joueurs. Ils sont enregistrés sur le serveur.", ""));

        sb.append("<div class=\"dlg-live tone-").append(st.pending() ? "info" : !st.lastError().isEmpty() ? "err"
                : st.known() ? "ok" : "warn").append("\" role=\"status\"><span>");
        if (st.pending()) {
            sb.append("Enregistrement en cours… La page se met à jour quand le serveur a répondu.");
        } else if (!st.lastError().isEmpty()) {
            sb.append("Enregistrement refusé : ").append(Http.esc(st.lastError())).append(" Rien n'a changé.");
        } else if (st.known()) {
            sb.append("Valeurs relues sur le serveur");
            if (st.readAt() != null) {
                sb.append(" <span class=\"muted\">(").append(Http.esc(ActionView.relativeTime(st.readAt(), Instant.now())))
                        .append(")</span>");
            }
            sb.append(".");
            if (!st.updatedBy().isEmpty()) {
                sb.append(" Dernière modification par <strong>").append(Http.esc(st.updatedBy())).append("</strong>");
                if (!st.updatedAt().isEmpty()) {
                    sb.append(" le <time datetime=\"").append(Http.esc(st.updatedAt())).append("\" data-local-time>")
                            .append(Http.esc(st.updatedAt())).append("</time>");
                }
                sb.append(".");
            }
        } else {
            sb.append("Les paramètres n'ont pas encore été lus sur le serveur.");
        }
        sb.append("</span><form method=\"post\" action=\"/settings/refresh\" data-submit-once>")
                .append(DialoguePages.hidden("_csrf", csrf)).append(DialoguePages.hidden("agent", agentId))
                .append(DialoguePages.hidden("tab", tab))
                .append("<button class=\"btn secondary sm\" type=\"submit\">Relire</button></form></div>");
        if (!st.problem().isEmpty()) {
            sb.append(Ui.banner("warn", Http.esc(st.problem())));
        }

        sb.append("<nav class=\"pl-tabs\" aria-label=\"Rubriques\">");
        for (String[] t : TABS) {
            sb.append("<a href=\"/settings?tab=").append(t[0]).append("\"")
                    .append(t[0].equals(tab) ? " aria-current=\"page\"" : "").append(">").append(Http.esc(t[1])).append("</a>");
        }
        sb.append("</nav>");

        boolean editable = canWrite && st.known() && !st.pending() && st.problem().isEmpty();
        switch (tab) {
            case "connexion" -> sb.append(connexion(csrf, agentId, st, editable));
            case "administration" -> sb.append(administration(csrf, agentId, st, editable));
            case "plus" -> sb.append(upcoming());
            default -> sb.append(economie(csrf, agentId, st, editable));
        }
        if (canWrite && !st.known()) {
            sb.append("<p class=\"form-text\">Cliquez sur « Relire » : les réglages deviennent modifiables une fois les "
                    + "valeurs actuelles connues.</p>");
        } else if (!canWrite) {
            sb.append("<p class=\"form-text\">Votre rôle permet de voir ces réglages, pas de les modifier.</p>");
        }
        return sb.toString();
    }

    private static String formOpen(String csrf, String agentId, String tab) {
        return "<form method=\"post\" action=\"/settings/save\" class=\"card pl-card set-form\" data-submit-once data-dirty-form>"
                + DialoguePages.hidden("_csrf", csrf) + DialoguePages.hidden("agent", agentId)
                + DialoguePages.hidden("tab", tab);
    }

    private static String formClose(boolean editable) {
        return editable ? "<div class=\"dlg-savebar\"><span class=\"dlg-dirty\" data-dirty-note hidden>"
                + Icons.icon("warning") + "Modifications non enregistrées</span>"
                + "<button class=\"btn\" type=\"submit\" data-busy-label=\"Enregistrement…\">" + Icons.icon("save")
                + "Enregistrer</button></div></form>" : "</form>";
    }

    private static String economie(String csrf, String agentId, State st, boolean editable) {
        String current = st.value("currency_name");
        StringBuilder sb = new StringBuilder(formOpen(csrf, agentId, "economie"));
        sb.append("<h2>Monnaie</h2>");
        sb.append("<label for=\"set-currency\">Nom affiché de la monnaie</label>");
        sb.append("<input class=\"form-control set-input\" id=\"set-currency\" name=\"currency_name\" maxlength=\"24\" required ")
                .append("value=\"").append(Http.esc(current)).append("\"")
                .append(editable ? "" : " disabled").append(">");
        sb.append("<p class=\"form-text\">Affiché partout où le jeu parle d'argent : bourse, marchands, marché, "
                + "récompenses de quêtes. Exemple : « 25 ").append(Http.esc(current)).append(" ». Les soldes et les "
                + "achats ne changent pas. Valeur par défaut : ").append(Http.esc(st.defaults().getOrDefault("currency_name", "")))
                .append(".</p>");
        return sb.append(formClose(editable)).toString();
    }

    private static String connexion(String csrf, String agentId, State st, boolean editable) {
        boolean on = "true".equals(st.value("beta_warning_enabled"));
        String dis = editable ? "" : " disabled";
        StringBuilder sb = new StringBuilder(formOpen(csrf, agentId, "connexion"));
        sb.append("<h2>Avertissement « version bêta »</h2>");
        sb.append("<p class=\"form-text\">Une fenêtre à chaque connexion, avec « OK » (il reviendra la fois suivante) "
                + "et « OK et ne plus afficher » (choix gardé pour ce joueur).</p>");
        sb.append("<label for=\"set-beta-on\">Afficher l'avertissement</label>");
        sb.append("<select class=\"form-select set-input\" id=\"set-beta-on\" name=\"beta_warning_enabled\"").append(dis).append(">")
                .append("<option value=\"true\"").append(on ? " selected" : "").append(">Oui, à chaque connexion</option>")
                .append("<option value=\"false\"").append(on ? "" : " selected").append(">Non, pour personne</option></select>");
        sb.append("<p class=\"form-text\">Le désactiver n'efface pas les choix des joueurs : le réactiver les retrouve.</p>");
        sb.append("<label for=\"set-beta-text\">Texte affiché</label>");
        sb.append("<textarea class=\"form-control\" id=\"set-beta-text\" name=\"beta_warning_text\" rows=\"4\" maxlength=\"500\" ")
                .append("required data-charcount").append(dis).append(">").append(Http.esc(st.value("beta_warning_text")))
                .append("</textarea>");
        sb.append("<p class=\"form-text\">500 caractères au plus, sans mise en forme.</p>");
        return sb.append(formClose(editable)).toString();
    }

    /**
     * Issue #35 — réglages GLOBAUX du mode bypass administrateur. Aucun ne l'active pour personne :
     * chaque administrateur autorisé l'ouvre lui-même en jeu, pour sa session ({@code /rpgadmin bypass on}).
     */
    private static String administration(String csrf, String agentId, State st, boolean editable) {
        StringBuilder sb = new StringBuilder();
        boolean supported = !st.known() || st.values().containsKey("admin_bypass_available");
        boolean canEdit = editable && supported;
        String dis = canEdit ? "" : " disabled";
        boolean on = !"false".equals(st.value("admin_bypass_available"));
        String minutes = st.value("admin_bypass_max_minutes").isEmpty() ? "0" : st.value("admin_bypass_max_minutes");
        sb.append(formOpen(csrf, agentId, "administration"));
        sb.append("<h2>Mode bypass administrateur</h2>");
        sb.append("<p class=\"form-text\">Un OP joue comme un joueur normal : les protections (monde Claims, claims, zones, "
                + "combat dans le Hub) s'appliquent à lui. Pour intervenir, un administrateur autorisé "
                + "(<code>rpgquest.admin.bypass</code>) active lui-même le mode en jeu avec <code>/rpgadmin bypass on</code>. "
                + "Il voit alors en permanence « ⚠ MODE ADMIN / BYPASS ACTIF », et le mode se coupe à sa déconnexion, au "
                + "redémarrage ou avec <code>/rpgadmin bypass off</code>.</p>");
        if (!supported) {
            sb.append(Ui.banner("warn", "Le plugin installé sur le serveur ne connaît pas encore ces réglages : "
                    + "déployez la version qui contient le ticket #35."));
        }
        sb.append("<label for=\"set-bypass-on\">Mode bypass sur ce serveur</label>");
        sb.append("<select class=\"form-select set-input\" id=\"set-bypass-on\" name=\"admin_bypass_available\"").append(dis).append(">")
                .append("<option value=\"true\"").append(on ? " selected" : "").append(">Proposé aux administrateurs autorisés</option>")
                .append("<option value=\"false\"").append(on ? "" : " selected").append(">Désactivé pour tout le monde</option></select>");
        sb.append("<p class=\"form-text\">« Désactivé » coupe aussitôt les sessions en cours (l'administrateur en est "
                + "prévenu) et empêche d'en ouvrir. « Proposé » n'active le mode pour personne.</p>");
        sb.append("<label for=\"set-bypass-max\">Durée maximale d'une activation (minutes)</label>");
        sb.append("<input class=\"form-control set-input\" id=\"set-bypass-max\" name=\"admin_bypass_max_minutes\" type=\"number\" ")
                .append("min=\"0\" max=\"480\" step=\"1\" required value=\"").append(Http.esc(minutes)).append("\"").append(dis).append(">");
        sb.append("<p class=\"form-text\">0 = jusqu'à la déconnexion. Sinon le mode se coupe tout seul après ce délai. "
                + "L'indicateur en jeu n'est pas réglable : il est toujours affiché.</p>");
        sb.append("<p class=\"form-text\">Aucun réglage de cette page n'accorde un droit. Qui peut activer le mode se règle "
                + "par les permissions (<code>rpgquest.admin.bypass</code>, par défaut les OP) ; ce que le mode contourne dépend des "
                + "nœuds de bypass du compte (voir Permissions).</p>");
        return sb.append(formClose(canEdit)).toString();
    }

    private static String upcoming() {
        return "<div class=\"card pl-card\"><h2>Rubriques à venir</h2><ul class=\"pl-facts-list\">"
                + "<li>Quêtes</li><li>Mondes</li><li>Marchands</li><li>Sécurité</li></ul>"
                + "<p class=\"form-text\">Elles apparaîtront ici au fur et à mesure.</p></div>";
    }
}
