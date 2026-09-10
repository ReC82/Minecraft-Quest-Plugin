package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.users.PanelUser;
import com.lodygames.rpgquest.panel.users.UserDirectory;
import java.time.Instant;
import java.util.List;

/**
 * Rendu de la gestion des comptes PlugAdmin (issue #50). Motif « liste compacte → clic →
 * détail / actions » : {@link #list} pour {@code /users}, {@link #detail} pour {@code /users/<id>}.
 * Aucun script inline, formulaires par aller-retour serveur (CSP {@code default-src 'self'}).
 * Chaque formulaire porte le jeton CSRF synchroniseur ({@code %CSRF%}, injecté par le handler).
 */
public final class UsersPages {

    private UsersPages() {
    }

    // ---- Liste + création --------------------------------------------------------------

    public static String list(List<PanelUser> users, PanelUser current, String flash, boolean flashIsError) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("users", "Utilisateurs",
                "Comptes d'accès au Control Panel, leurs rôles et leur activation. "
                        + "Ces rôles sont propres à PlugAdmin — sans lien avec OP Minecraft ou LuckPerms.", ""));

        if (flash != null && !flash.isBlank()) {
            sb.append(Ui.banner(flashIsError ? "err" : "ok", Http.esc(flash)));
        }

        sb.append("<h2>Comptes <span class=\"muted\">(").append(users.size()).append(")</span></h2>");
        if (users.isEmpty()) {
            sb.append(Ui.empty("Aucun compte enregistré."));
        } else {
            sb.append("<ul class=\"usr-list\">");
            for (PanelUser u : users) {
                sb.append("<li><a class=\"usr-row\" href=\"/users/").append(Http.esc(u.id())).append("\">")
                        .append("<span class=\"usr-name\">").append(Http.esc(u.username()));
                if (current != null && current.id().equals(u.id())) {
                    sb.append(" <span class=\"badge\">vous</span>");
                }
                sb.append("</span>")
                        .append("<span class=\"usr-tags\">").append(roleBadge(u.role())).append(activePill(u.active()))
                        .append("</span>")
                        .append("<span class=\"usr-meta muted\">créé le ").append(fmtDate(u.createdAt()))
                        .append(" · connexion : ")
                        .append(u.lastLoginAt() == null ? "jamais" : fmtDate(u.lastLoginAt()))
                        .append("</span>")
                        .append("<span class=\"usr-chev\">").append(Icons.icon("chevron")).append("</span>")
                        .append("</a></li>");
            }
            sb.append("</ul>");
        }

        sb.append("<h2>Créer un compte</h2>");
        sb.append("<form method=\"post\" action=\"/users/create\" class=\"actform\" autocomplete=\"off\">%CSRF%")
                .append("<label for=\"u-username\">Identifiant</label>")
                .append("<input id=\"u-username\" type=\"text\" name=\"username\" autocomplete=\"off\" ")
                .append("minlength=\"3\" maxlength=\"32\" required>")
                .append("<label for=\"u-password\">Mot de passe initial</label>")
                .append("<input id=\"u-password\" type=\"password\" name=\"password\" autocomplete=\"new-password\" ")
                .append("minlength=\"").append(UserDirectory.PASSWORD_MIN).append("\" maxlength=\"")
                .append(UserDirectory.PASSWORD_MAX).append("\" required>")
                .append("<p class=\"muted\">Au moins ").append(UserDirectory.PASSWORD_MIN)
                .append(" caractères. Il n'est ni affiché ni journalisé après création — le communiquer "
                        + "à la personne par un canal sûr.</p>")
                .append("<label for=\"u-role\">Rôle</label>")
                .append(roleSelect("u-role", "role", Role.READ_ONLY))
                .append("<button class=\"btn\" type=\"submit\">").append(Icons.icon("plus")).append("Créer le compte</button>")
                .append("</form>");
        return sb.toString();
    }

    // ---- Détail d'un compte -----------------------------------------------------------

    public static String detail(PanelUser user, PanelUser current, int activeOwners, String error) {
        boolean isSelf = current != null && current.id().equals(user.id());
        boolean lastActiveOwner = user.isOwner() && user.active() && activeOwners <= 1;

        StringBuilder sb = new StringBuilder();
        sb.append("<p><a class=\"doc-cm-link\" href=\"/users\">").append(Icons.icon("back"))
                .append("Tous les comptes</a></p>");
        sb.append("<h1>").append(Http.esc(user.username()));
        if (isSelf) {
            sb.append(" <span class=\"badge\">vous</span>");
        }
        sb.append("</h1>");
        sb.append("<div class=\"cards\">");
        card(sb, "Rôle", roleBadge(user.role()));
        card(sb, "Statut", activePill(user.active()));
        card(sb, "Créé le", Http.esc(fmtDate(user.createdAt())));
        card(sb, "Dernière connexion", user.lastLoginAt() == null ? "jamais" : Http.esc(fmtDate(user.lastLoginAt())));
        sb.append("</div>");

        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("err", Http.esc(error)));
        }
        if (lastActiveOwner) {
            sb.append(Ui.banner("", "Ce compte est le <strong>dernier OWNER actif</strong> : son rôle ne peut pas "
                    + "être abaissé et il ne peut pas être désactivé tant qu'aucun autre OWNER actif n'existe."));
        }

        sb.append("<h2>Changer le rôle</h2>");
        sb.append("<form method=\"post\" action=\"/users/").append(Http.esc(user.id())).append("/role\" class=\"actform\">%CSRF%")
                .append("<label for=\"d-role\">Rôle</label>")
                .append(roleSelect("d-role", "role", user.role()))
                .append("<button class=\"btn\" type=\"submit\"").append(lastActiveOwner ? " disabled" : "").append(">")
                .append(Icons.icon("save")).append("Enregistrer le rôle</button>")
                .append("</form>");

        sb.append("<h2>Activation</h2>");
        if (user.active()) {
            sb.append("<form method=\"post\" action=\"/users/").append(Http.esc(user.id())).append("/active\" class=\"actform\">%CSRF%")
                    .append("<input type=\"hidden\" name=\"active\" value=\"false\">")
                    .append("<p class=\"muted\">Un compte désactivé ne peut plus se connecter ; sa session en cours "
                            + "est invalidée à la requête suivante.</p>")
                    .append("<button class=\"btn btn-outline-danger\" type=\"submit\"")
                    .append(isSelf || lastActiveOwner ? " disabled" : "").append(">")
                    .append(Icons.icon("lock")).append("Désactiver le compte</button>")
                    .append("</form>");
        } else {
            sb.append("<form method=\"post\" action=\"/users/").append(Http.esc(user.id())).append("/active\" class=\"actform\">%CSRF%")
                    .append("<input type=\"hidden\" name=\"active\" value=\"true\">")
                    .append("<button class=\"btn\" type=\"submit\">").append(Icons.icon("check"))
                    .append("Réactiver le compte</button>")
                    .append("</form>");
        }
        return sb.toString();
    }

    // ---- helpers -------------------------------------------------------------------------

    private static String roleSelect(String id, String name, Role selected) {
        StringBuilder sb = new StringBuilder("<select id=\"").append(id).append("\" name=\"").append(name).append("\">");
        for (Role r : Role.values()) {
            sb.append("<option value=\"").append(r.name()).append('"')
                    .append(r == selected ? " selected" : "").append('>')
                    .append(Http.esc(r.label())).append(" (").append(r.name()).append(")</option>");
        }
        return sb.append("</select>").toString();
    }

    private static String roleBadge(Role role) {
        return "<span class=\"badge\">" + Http.esc(role.label()) + "</span>";
    }

    private static String activePill(boolean active) {
        return active ? Ui.pill("actif", "success", "✓") : Ui.pill("désactivé", "pending", "○");
    }

    private static void card(StringBuilder sb, String key, String value) {
        sb.append("<div class=\"card\"><div class=\"k\">").append(Http.esc(key)).append("</div><div class=\"v\">")
                .append(value).append("</div></div>");
    }

    private static String fmtDate(Instant instant) {
        String iso = instant.toString();
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
    }
}
