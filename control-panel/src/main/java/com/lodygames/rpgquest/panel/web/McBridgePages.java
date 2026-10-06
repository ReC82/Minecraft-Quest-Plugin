package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.authz.McRight;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.users.McBridgeRepository;
import com.lodygames.rpgquest.panel.users.PanelUser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rendu du pont vers les droits Minecraft (issue #200) : liaison compte ↔ joueur, droits d'un
 * groupe par monde, provenance et état de synchronisation.
 *
 * <p>Aucun script inline, formulaires par aller-retour serveur, jeton CSRF sur chaque POST.
 * Mobile : grilles fluides et tables en {@code .table-responsive}, comme le reste du panel.</p>
 */
public final class McBridgePages {

    private McBridgePages() {
    }

    // ---- Liaison d'un compte à un joueur -------------------------------------------------------

    /**
     * Bloc « Joueur Minecraft lié » sur la fiche d'un compte.
     *
     * <p>Le texte dit deux choses que l'interface ne doit pas laisser deviner : l'identité est
     * l'<strong>UUID</strong> (un pseudonyme change et ne prouve rien), et une dissociation
     * n'enlève <strong>rien en jeu</strong> tant qu'une synchronisation n'a pas été lancée.</p>
     */
    public static String linkBlock(PanelUser user, Optional<McBridgeRepository.Link> link,
                                    String bridgeStateHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Joueur Minecraft lié</h2>");
        sb.append("<p class=\"muted\">L'identité d'un joueur est son <strong>UUID</strong>. Un "
                + "pseudonyme change et ne constitue <strong>pas</strong> une preuve de contrôle du "
                + "compte : cette liaison est une déclaration <strong>administrative</strong>, pas "
                + "une vérification de propriété. Un joueur <strong>hors ligne</strong> peut être "
                + "lié — c'est volontaire, pour préparer les droits d'un builder avant son arrivée.</p>");

        if (link.isPresent()) {
            McBridgeRepository.Link current = link.get();
            sb.append("<div class=\"cards\">");
            card(sb, "UUID", "<code>" + Http.esc(current.mcUuid()) + "</code>");
            card(sb, "Pseudonyme connu", current.mcName() == null
                    ? "<span class=\"muted\">inconnu</span>" : Http.esc(current.mcName()));
            card(sb, "Lié par", Http.esc(current.linkedBy()));
            sb.append("</div>");
        } else {
            sb.append(Ui.banner("info", "Ce compte n'est lié à aucun joueur : aucun droit Minecraft "
                    + "ne peut lui être appliqué."));
        }

        sb.append(bridgeStateHtml);

        sb.append("<h3>").append(link.isPresent() ? "Modifier la liaison" : "Lier un joueur").append("</h3>");
        sb.append("<form method=\"post\" action=\"/users/mc/link\" class=\"actform\" autocomplete=\"off\">%CSRF%")
                .append("<input type=\"hidden\" name=\"user\" value=\"").append(Http.esc(user.id())).append("\">")
                .append("<label for=\"mc-uuid\">UUID du joueur</label>")
                .append("<input id=\"mc-uuid\" type=\"text\" name=\"uuid\" required ")
                .append("placeholder=\"00000000-0000-0000-0000-000000000000\" maxlength=\"36\">")
                .append("<label for=\"mc-name\">Pseudonyme (facultatif, informatif)</label>")
                .append("<input id=\"mc-name\" type=\"text\" name=\"name\" maxlength=\"32\">")
                .append(confirmBox(link.isPresent()
                        ? "Je confirme le remplacement de la liaison de « " + user.username() + " »."
                        : "Je confirme la liaison de « " + user.username() + " » à ce joueur."))
                .append("<button class=\"btn\" type=\"submit\">").append(Icons.icon("link"))
                .append(link.isPresent() ? "Remplacer la liaison" : "Lier").append("</button></form>");

        if (link.isPresent()) {
            sb.append("<h3>Dissocier</h3>");
            sb.append("<p class=\"muted\">La dissociation retire le lien dans le panel. Elle "
                    + "n'enlève <strong>aucun droit en jeu</strong> par elle-même : lancez ensuite "
                    + "une <strong>synchronisation</strong> pour révoquer les droits gérés du joueur. "
                    + "Ses droits externes, eux, ne sont jamais touchés.</p>");
            sb.append("<form method=\"post\" action=\"/users/mc/unlink\" class=\"actform\">%CSRF%")
                    .append("<input type=\"hidden\" name=\"user\" value=\"").append(Http.esc(user.id())).append("\">")
                    .append("<label for=\"mc-confirm\">Retaper l'UUID pour confirmer</label>")
                    .append("<input id=\"mc-confirm\" type=\"text\" name=\"confirm\" autocomplete=\"off\" required>")
                    .append(confirmBox("Je confirme la dissociation."))
                    .append("<button class=\"btn btn-danger\" type=\"submit\">").append(Icons.icon("trash"))
                    .append("Dissocier</button></form>");
        }
        return sb.toString();
    }

    // ---- Droits Minecraft d'un groupe ----------------------------------------------------------

    /**
     * Bloc « Droits Minecraft » sur la fiche d'un groupe : une case par droit du catalogue, avec un
     * monde quand le droit l'exige.
     *
     * <p>Les droits de construction <strong>exigent</strong> un monde, et c'est dit : sans monde, le
     * droit s'appliquerait partout, et autoriser le Hub autoriserait aussi les claims.</p>
     */
    public static String groupNodesBlock(PanelGroup group, List<McBridgeRepository.GroupNode> current,
                                          List<String> knownWorlds, String syncStateHtml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Droits Minecraft de ce groupe</h2>");
        sb.append("<p class=\"muted\">Ces droits sont <strong>distincts</strong> des permissions "
                + "PlugAdmin ci-dessus, de l'OP Minecraft, et des permissions de création de PNJ du "
                + "panel. Ils sont appliqués dans LuckPerms via un groupe dédié au pont, ce qui rend "
                + "leur provenance visible et permet de les révoquer sans toucher aux droits "
                + "externes d'un joueur.</p>");
        sb.append(Ui.banner("warn", "Aucun droit de construction ne donne de bypass de claim, de zone "
                + "protégée, de waypoint ni de commande d'administration. L'ombrelle "
                + "<code>rpgquest.admin.world</code> n'est <strong>volontairement pas</strong> "
                + "distribuable ici : elle donne tout, et reste à attribuer à la main dans LuckPerms."));

        Set<String> selected = new LinkedHashSet<>();
        for (McBridgeRepository.GroupNode node : current) {
            selected.add(key(node.node(), node.world()));
        }

        sb.append("<form method=\"post\" action=\"/groups/mc\" class=\"actform\">%CSRF%")
                .append("<input type=\"hidden\" name=\"group\" value=\"").append(Http.esc(group.id())).append("\">");

        List<McRight.Definition> catalogue = McRight.catalogue(knownWorlds);
        for (McRight.Family family : McRight.Family.values()) {
            List<McRight.Definition> inFamily = new ArrayList<>();
            for (McRight.Definition definition : catalogue) {
                if (definition.family() == family) {
                    inFamily.add(definition);
                }
            }
            if (inFamily.isEmpty()) {
                continue;
            }
            sb.append("<p class=\"fs-h\">").append(Http.esc(family.label()));
            if (family.worldRequired()) {
                sb.append(" <span class=\"muted\">(monde obligatoire)</span>");
            }
            sb.append("</p><div class=\"row row-cols-1 row-cols-lg-2 g-2\">");
            int index = 0;
            for (McRight.Definition definition : inFamily) {
                String world = family.worldRequired() ? worldOf(definition) : "";
                String id = "mcr-" + sanitise(definition.node()) + "-" + index;
                boolean checked = selected.contains(key(definition.node(), world));
                sb.append("<div class=\"col\"><div class=\"form-check\">")
                        .append("<input class=\"form-check-input\" type=\"checkbox\" id=\"").append(Http.esc(id))
                        .append("\" name=\"mcr_").append(Http.esc(sanitise(definition.node())))
                        .append("\" value=\"on\"").append(checked ? " checked" : "").append(">")
                        .append("<input type=\"hidden\" name=\"mcw_")
                        .append(Http.esc(sanitise(definition.node()))).append("\" value=\"")
                        .append(Http.esc(world)).append("\">")
                        .append("<label class=\"form-check-label\" for=\"").append(Http.esc(id)).append("\">")
                        .append(Http.esc(definition.label()))
                        .append("<br><code class=\"muted\">").append(Http.esc(definition.node())).append("</code>")
                        .append("<br><span class=\"muted\">").append(Http.esc(definition.help())).append("</span>")
                        .append("</label></div></div>");
                index++;
            }
            sb.append("</div>");
        }
        sb.append("<button class=\"btn\" type=\"submit\">").append(Icons.icon("save"))
                .append("Enregistrer les droits Minecraft</button></form>");

        sb.append(syncStateHtml);
        return sb.toString();
    }

    /** Monde déduit d'un droit décliné par monde ({@code rpgquest.build.hub.<monde>}). */
    private static String worldOf(McRight.Definition definition) {
        if (!definition.perWorld()) {
            return "";
        }
        return definition.node().substring(McRight.BUILD_HUB_PREFIX.length());
    }

    /** Clé de comparaison nœud+monde. Le monde fait partie de l'identité du droit. */
    private static String key(String node, String world) {
        return node.toLowerCase(java.util.Locale.ROOT) + "|" + (world == null ? "" : world);
    }

    /** Nom de champ sûr pour un nœud (les points et l'étoile ne font pas de bons noms de formulaire). */
    public static String sanitise(String node) {
        return node.replace('.', '_').replace('*', 'X');
    }

    private static String confirmBox(String label) {
        return "<div class=\"form-check mb-2\"><input class=\"form-check-input\" type=\"checkbox\" "
                + "id=\"cf-" + Integer.toHexString(label.hashCode()) + "\" name=\"confirm\" value=\"true\" required>"
                + "<label class=\"form-check-label\" for=\"cf-" + Integer.toHexString(label.hashCode())
                + "\">" + Http.esc(label) + "</label></div>";
    }

    private static void card(StringBuilder sb, String label, String valueHtml) {
        sb.append("<div class=\"card\"><div class=\"card-l\">").append(Http.esc(label))
                .append("</div><div class=\"card-v\">").append(valueHtml).append("</div></div>");
    }
}
