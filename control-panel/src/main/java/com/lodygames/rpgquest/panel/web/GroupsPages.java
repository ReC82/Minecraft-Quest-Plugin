package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.authz.EffectivePermissions;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.users.GroupDirectory;
import com.lodygames.rpgquest.panel.users.PanelUser;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rendu de la gestion des groupes PlugAdmin (issue #199). Même motif que {@link UsersPages} :
 * liste compacte → clic → détail et actions, aucun script inline, formulaires par aller-retour
 * serveur (CSP {@code default-src 'self'}), jeton CSRF sur chaque POST.
 *
 * <p><strong>Mobile</strong> : aucune table pour les permissions — une liste de cases à cocher en
 * grille fluide (`row-cols-1 row-cols-sm-2 row-cols-lg-3`), des libellés cliquables assez grands
 * pour le doigt, et les deux seules tables de la page sont enveloppées dans
 * {@code .table-responsive}. C'est la même approche que les pages déjà livrées, pour ne pas
 * introduire un second style de mise en page.</p>
 */
public final class GroupsPages {

    private GroupsPages() {
    }

    // ---- Liste + création ----------------------------------------------------------------------

    public static String list(List<PanelGroup> groups, Map<String, Integer> memberCounts,
                              EffectivePermissions actor, String flash, boolean flashIsError) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("users", "Groupes",
                "Ensembles de permissions attribuables à plusieurs comptes. Un compte peut "
                        + "appartenir à plusieurs groupes : ses droits sont alors l'UNION de son rôle "
                        + "et de ses groupes.", ""));

        if (flash != null && !flash.isBlank()) {
            sb.append(Ui.banner(flashIsError ? "err" : "ok", Http.esc(flash)));
        }

        sb.append(Ui.banner("info", "<strong>Règle de combinaison : l'union.</strong> Un compte a un "
                + "droit s'il le tient de son rôle <em>ou</em> d'au moins un de ses groupes. Il "
                + "n'existe <strong>aucun refus explicite</strong> : pour retirer un droit, retirez-le "
                + "du groupe, ou retirez le compte du groupe. Un groupe ne peut donc jamais "
                + "<em>réduire</em> les droits d'un rôle."));
        sb.append(Ui.banner("warn", "Vous ne pouvez accorder ni retirer un droit que vous ne détenez "
                + "pas vous-même. C'est ce qui empêche de se fabriquer indirectement un droit réservé "
                + "au propriétaire, comme l'élévation OP."));

        sb.append("<h2>Groupes <span class=\"muted\">(").append(groups.size()).append(")</span></h2>");
        if (groups.isEmpty()) {
            sb.append(Ui.empty("Aucun groupe. Les comptes n'ont donc que les droits de leur rôle."));
        } else {
            sb.append("<ul class=\"usr-list\">");
            for (PanelGroup group : groups) {
                int members = memberCounts.getOrDefault(group.id(), 0);
                sb.append("<li><a class=\"usr-row\" href=\"/groups/").append(Http.esc(group.id())).append("\">")
                        .append("<span class=\"usr-name\">").append(Http.esc(group.name())).append("</span>")
                        .append("<span class=\"usr-tags\"><span class=\"badge\">")
                        .append(group.permissions().size()).append(" droit(s)</span>")
                        .append("<span class=\"badge\">").append(members).append(" membre(s)</span></span>")
                        .append("<span class=\"usr-meta muted\">")
                        .append(Http.esc(group.description() == null ? "" : group.description()))
                        .append("</span>")
                        .append("<span class=\"usr-chev\">").append(Icons.icon("chevron")).append("</span>")
                        .append("</a></li>");
            }
            sb.append("</ul>");
        }

        sb.append("<h2>Créer un groupe</h2>");
        sb.append("<form method=\"post\" action=\"/groups/create\" class=\"actform\" autocomplete=\"off\">%CSRF%")
                .append("<label for=\"g-name\">Nom</label>")
                .append("<input id=\"g-name\" type=\"text\" name=\"name\" minlength=\"2\" maxlength=\"48\" required>")
                .append("<label for=\"g-desc\">Description (facultative)</label>")
                .append("<input id=\"g-desc\" type=\"text\" name=\"description\" maxlength=\"")
                .append(GroupDirectory.DESCRIPTION_MAX).append("\">");
        sb.append(permissionChecklist(Set.of(), actor, "create"));
        sb.append("<button class=\"btn\" type=\"submit\">").append(Icons.icon("plus"))
                .append("Créer le groupe</button></form>");
        return sb.toString();
    }

    // ---- Détail d'un groupe --------------------------------------------------------------------

    public static String detail(PanelGroup group, List<PanelUser> members, EffectivePermissions actor,
                                String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><a class=\"doc-cm-link\" href=\"/groups\">").append(Icons.icon("back"))
                .append("Tous les groupes</a></p>");
        sb.append("<h1>").append(Http.esc(group.name())).append("</h1>");
        if (group.description() != null && !group.description().isBlank()) {
            sb.append("<p class=\"muted\">").append(Http.esc(group.description())).append("</p>");
        }

        boolean editable = actor != null && actor.granted().containsAll(group.permissions());
        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("err", Http.esc(error)));
        }
        if (!editable) {
            sb.append(Ui.banner("warn", "Ce groupe accorde des droits que vous ne détenez pas : vous "
                    + "pouvez le consulter, mais ni le modifier ni le supprimer."));
        }

        sb.append("<div class=\"cards\">");
        card(sb, "Droits accordés", String.valueOf(group.permissions().size()));
        card(sb, "Membres", String.valueOf(members.size()));
        sb.append("</div>");

        sb.append("<h2>Nom et description</h2>");
        sb.append("<form method=\"post\" action=\"/groups/rename\" class=\"actform\" autocomplete=\"off\">%CSRF%")
                .append("<input type=\"hidden\" name=\"group\" value=\"").append(Http.esc(group.id())).append("\">")
                .append("<label for=\"g-rname\">Nom</label>")
                .append("<input id=\"g-rname\" type=\"text\" name=\"name\" minlength=\"2\" maxlength=\"48\" value=\"")
                .append(Http.esc(group.name())).append("\" required").append(editable ? "" : " disabled").append(">")
                .append("<label for=\"g-rdesc\">Description</label>")
                .append("<input id=\"g-rdesc\" type=\"text\" name=\"description\" maxlength=\"")
                .append(GroupDirectory.DESCRIPTION_MAX).append("\" value=\"")
                .append(Http.esc(group.description() == null ? "" : group.description()))
                .append("\"").append(editable ? "" : " disabled").append(">")
                .append("<button class=\"btn\" type=\"submit\"").append(editable ? "" : " disabled")
                .append(">").append(Icons.icon("save")).append("Enregistrer</button></form>");

        sb.append("<h2>Permissions du groupe</h2>");
        sb.append("<form method=\"post\" action=\"/groups/permissions\" class=\"actform\">%CSRF%")
                .append("<input type=\"hidden\" name=\"group\" value=\"").append(Http.esc(group.id())).append("\">");
        sb.append(permissionChecklist(group.permissions(), actor, "edit"));
        sb.append("<button class=\"btn\" type=\"submit\"").append(editable ? "" : " disabled").append(">")
                .append(Icons.icon("save")).append("Enregistrer les permissions</button></form>");

        sb.append("<h2>Membres</h2>");
        if (members.isEmpty()) {
            sb.append(Ui.empty("Aucun membre. Les appartenances se règlent depuis la fiche d'un compte."));
        } else {
            sb.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle\">")
                    .append("<thead><tr><th>Compte</th><th>Rôle</th></tr></thead><tbody>");
            for (PanelUser member : members) {
                sb.append("<tr><td><a href=\"/users/").append(Http.esc(member.id())).append("\">")
                        .append(Http.esc(member.username())).append("</a></td><td>")
                        .append(Http.esc(member.role().label())).append("</td></tr>");
            }
            sb.append("</tbody></table></div>");
        }

        sb.append("<h2>Supprimer</h2>");
        sb.append("<p class=\"muted\">La suppression retire ce groupe à <strong>tous</strong> ses "
                + "membres. Leurs rôles ne changent pas : ils perdent uniquement les droits que ce "
                + "groupe ajoutait, et l'effet est immédiat sur les sessions ouvertes.</p>");
        sb.append("<form method=\"post\" action=\"/groups/delete\" class=\"actform\">%CSRF%")
                .append("<input type=\"hidden\" name=\"group\" value=\"").append(Http.esc(group.id())).append("\">")
                .append("<label for=\"g-confirm\">Retaper le nom du groupe pour confirmer</label>")
                .append("<input id=\"g-confirm\" type=\"text\" name=\"confirm\" autocomplete=\"off\" placeholder=\"")
                .append(Http.esc(group.name())).append("\" required").append(editable ? "" : " disabled").append(">")
                .append("<button class=\"btn btn-danger\" type=\"submit\"").append(editable ? "" : " disabled")
                .append(">").append(Icons.icon("trash")).append("Supprimer le groupe</button></form>");
        return sb.toString();
    }

    // ---- Appartenances et droits effectifs, sur la fiche d'un compte ---------------------------

    /**
     * Bloc « Groupes et droits effectifs » affiché sur {@code /users/<id>}.
     *
     * <p>C'est la réponse à « d'où vient ce droit ? » : chaque permission accordée est listée avec
     * <strong>toutes</strong> ses origines. Un « oui » sans origine serait inexploitable le jour où
     * un compte a un droit qu'on ne lui voulait pas.</p>
     */
    public static String membershipBlock(PanelUser user, List<PanelGroup> allGroups,
                                         EffectivePermissions effective, EffectivePermissions actor) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Groupes</h2>");
        if (allGroups.isEmpty()) {
            sb.append(Ui.empty("Aucun groupe n'existe encore. "
                    + "<a href=\"/groups\">Créer un groupe</a>."));
        } else {
            sb.append("<form method=\"post\" action=\"/users/groups\" class=\"actform\">%CSRF%")
                    .append("<input type=\"hidden\" name=\"user\" value=\"").append(Http.esc(user.id())).append("\">");
            sb.append("<div class=\"row row-cols-1 row-cols-sm-2 row-cols-lg-3 g-2\">");
            Set<String> memberOf = new java.util.LinkedHashSet<>();
            effective.groups().forEach(group -> memberOf.add(group.id()));
            for (PanelGroup group : allGroups) {
                boolean checked = memberOf.contains(group.id());
                boolean allowed = actor != null && actor.granted().containsAll(group.permissions());
                String id = "mg-" + group.id();
                sb.append("<div class=\"col\"><div class=\"form-check\">")
                        .append("<input class=\"form-check-input\" type=\"checkbox\" id=\"").append(Http.esc(id))
                        .append("\" name=\"group_").append(Http.esc(group.id())).append("\" value=\"on\"")
                        .append(checked ? " checked" : "").append(allowed ? "" : " disabled").append(">")
                        .append("<label class=\"form-check-label\" for=\"").append(Http.esc(id)).append("\">")
                        .append(Http.esc(group.name()));
                if (!allowed) {
                    sb.append(" <span class=\"muted\">(droits que vous ne détenez pas)</span>");
                }
                sb.append("</label></div></div>");
            }
            sb.append("</div>");
            sb.append("<p class=\"muted\">Décocher un groupe retire ses droits <strong>immédiatement</strong>, "
                    + "y compris pour une session déjà ouverte — aucune reconnexion n'est nécessaire.</p>");
            sb.append("<button class=\"btn\" type=\"submit\">").append(Icons.icon("save"))
                    .append("Enregistrer les groupes</button></form>");
        }

        sb.append("<h2>Droits effectifs <span class=\"muted\">(")
                .append(effective.granted().size()).append(")</span></h2>");
        sb.append("<p class=\"muted\">Union du rôle <strong>")
                .append(Http.esc(effective.role() == null ? "—" : effective.role().label()))
                .append("</strong> et des groupes du compte. La colonne « provenance » dit "
                        + "<em>par où</em> chaque droit arrive.</p>");
        if (effective.granted().isEmpty()) {
            sb.append(Ui.empty("Ce compte n'a aucun droit."));
            return sb.toString();
        }
        sb.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle\">")
                .append("<thead><tr><th>Permission</th><th>Provenance</th></tr></thead><tbody>");
        for (Permission permission : Permission.values()) {
            if (!effective.has(permission)) {
                continue;
            }
            sb.append("<tr><td><code>").append(Http.esc(permission.name())).append("</code></td><td>");
            List<EffectivePermissions.Source> sources = effective.sourcesOf(permission);
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) {
                    sb.append(" · ");
                }
                sb.append("<span class=\"badge\">").append(Http.esc(sources.get(i).label())).append("</span>");
            }
            sb.append("</td></tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    // ---- interne -------------------------------------------------------------------------------

    /**
     * Grille de cases à cocher pour les permissions. Une permission que l'acteur ne détient pas est
     * rendue <strong>désactivée et expliquée</strong> plutôt que masquée : la cacher laisserait
     * croire qu'elle n'existe pas, alors que le refus est une décision.
     */
    private static String permissionChecklist(Set<Permission> selected, EffectivePermissions actor,
                                               String formKey) {
        StringBuilder sb = new StringBuilder("<p class=\"fs-h\">Permissions accordées</p>");
        sb.append("<div class=\"row row-cols-1 row-cols-sm-2 row-cols-lg-3 g-2\">");
        for (Permission permission : Permission.values()) {
            boolean held = actor != null && actor.has(permission);
            boolean checked = selected.contains(permission);
            String id = "p-" + formKey + "-" + permission.name();
            sb.append("<div class=\"col\"><div class=\"form-check\">")
                    .append("<input class=\"form-check-input\" type=\"checkbox\" id=\"").append(Http.esc(id))
                    .append("\" name=\"perm_").append(permission.name()).append("\" value=\"on\"")
                    .append(checked ? " checked" : "")
                    .append(held ? "" : " disabled").append(">")
                    .append("<label class=\"form-check-label\" for=\"").append(Http.esc(id)).append("\"><code>")
                    .append(Http.esc(permission.name())).append("</code>");
            if (!held) {
                sb.append(" <span class=\"muted\">(vous ne détenez pas ce droit)</span>");
            }
            sb.append("</label></div></div>");
        }
        return sb.append("</div>").toString();
    }

    private static void card(StringBuilder sb, String label, String valueHtml) {
        sb.append("<div class=\"card\"><div class=\"card-l\">").append(Http.esc(label))
                .append("</div><div class=\"card-v\">").append(valueHtml).append("</div></div>");
    }
}
