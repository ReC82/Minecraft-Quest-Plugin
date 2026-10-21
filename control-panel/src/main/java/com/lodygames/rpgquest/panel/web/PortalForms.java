package com.lodygames.rpgquest.panel.web;

import static com.lodygames.rpgquest.panel.web.PortalPages.bool;
import static com.lodygames.rpgquest.panel.web.PortalPages.maps;
import static com.lodygames.rpgquest.panel.web.PortalPages.num;
import static com.lodygames.rpgquest.panel.web.PortalPages.str;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Map;

/**
 * Issue #565 (#228-P2) — formulaires de la page Portails : création d'un brouillon, renommage,
 * zone d'activation, activation / désactivation, suppression, et journal des modifications.
 *
 * <p>Tous passent par {@code POST /agents/action} (contrôle de permission et de forme par {@link
 * AgentActionCatalog}, revérification complète par le plugin). Sans JavaScript : la CSP du panel
 * interdit le script en ligne, le choix « emplacement » / « coordonnées » est un simple bouton radio
 * et le serveur ignore le groupe de champs non choisi.</p>
 */
final class PortalForms {

    private PortalForms() {
    }

    // ---- Création ---------------------------------------------------------------------------------

    static String createSection(PortalPages.View v) {
        List<String> worlds = worlds(v);
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec mt-3\" id=\"portal-create\"><h3 class=\"w-h\">")
                .append(Icons.icon("plus")).append("Créer un portail en brouillon</h3>")
                .append("<p class=\"field-help\">Le brouillon est enregistré sur le serveur mais <strong>ne téléporte "
                        + "personne</strong> tant qu'il n'est pas activé depuis sa fiche. Aucun bloc n'est posé : la "
                        + "zone d'activation se règle sur un emplacement marqué en jeu avec l'outil d'emplacement, "
                        + "ou en coordonnées.</p>");
        if (worlds.isEmpty()) {
            return sb.append(Ui.banner("info", "Aucun monde chargé dans le relevé : rafraîchir les portails."))
                    .append("</section>").toString();
        }
        sb.append(formStart(v, "portal.create", ""));
        sb.append("<div class=\"form-grid\">")
                .append(field("Identifiant (définitif)", "<input type=\"text\" name=\"id\" required pattern=\"[a-z0-9_\\-]{3,48}\" "
                        + "maxlength=\"48\" placeholder=\"arche_du_marche\" autocomplete=\"off\">",
                        "3 à 48 caractères : minuscules, chiffres, « _ », « - ». Il ne changera plus."))
                .append(field("Nom affiché", "<input type=\"text\" name=\"name\" maxlength=\"" + AgentActionCatalog.PORTAL_MAX_NAME
                        + "\" placeholder=\"Arche du marché\">", "Facultatif ; sinon « Portail vers … »."))
                .append("</div>");
        sb.append(zoneFields(v, null));
        sb.append("<div class=\"form-grid\">")
                .append(field("Monde d'arrivée", select("destination_world", worlds, ""), ""))
                .append(field("Arrivée", strategySelect(), "Le point fixe et les autres réglages d'arrivée viendront "
                        + "avec la fiche Destination."))
                .append("</div>");
        sb.append(confirm("Je crée un brouillon : il ne téléportera personne tant que je ne l'active pas."));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Icons.icon("plus")).append("Créer le brouillon</button></div></form></section>");
        return sb.toString();
    }

    // ---- Fiche ------------------------------------------------------------------------------------

    static String manageSection(PortalPages.View v, Map<String, Object> p) {
        PortalPages.Manage m = v.manage();
        if (!m.any()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"portal-manage\"><h3 class=\"w-h\">")
                .append(Icons.icon("edit")).append("Gérer ce portail</h3>");
        if (!bool(p.get("editable"))) {
            return sb.append("<p class=\"muted\">").append("ZONE_PORTAL".equals(str(p.get("kind")))
                    ? "Portail de zone (ancien format, portals/) : il se gère avec /rpgadmin portal."
                    : "Plusieurs fichiers déclarent cet identifiant : corriger d'abord les fichiers du serveur.")
                    .append("</p></section>").toString();
        }
        String state = str(p.get("state"));
        boolean active = "ACTIVE".equals(state);
        if (bool(p.get("essential"))) {
            sb.append(Ui.banner("warn", "<strong>Portail essentiel</strong> : il mène au Hub, au Wild ou aux Claims. "
                    + "Chaque modification demande une confirmation supplémentaire."));
        }
        if (m.canEnable()) {
            sb.append("<h4 class=\"w-h\">").append(active ? "Désactiver" : "Activer").append("</h4>");
            sb.append(formStart(v, active ? "portal.disable" : "portal.enable", str(p.get("id"))))
                    .append(hidden(p))
                    .append("<p class=\"field-help\">").append(active
                            ? "Entrer dans la zone ne téléportera plus personne. Toute la configuration est conservée."
                            : "Entrer dans la zone téléportera aussitôt vers " + Http.esc(str(p.get("destinationWorld")))
                            + ". Le serveur vérifie d'abord que les deux mondes sont chargés et que la zone ne "
                            + "recouvre aucun autre portail.").append("</p>")
                    .append(essential(p))
                    .append(confirm(active ? "Désactiver « " + str(p.get("id")) + " »"
                            : "Activer « " + str(p.get("id")) + " » : les joueurs seront téléportés"))
                    .append("<div class=\"btnrow\"><button class=\"btn btn-sm ")
                    .append(active ? "btn-outline-secondary" : "btn-success").append("\" type=\"submit\">")
                    .append(active ? "Désactiver le portail" : "Activer le portail")
                    .append("</button></div></form>");
        }
        if (m.canEdit()) {
            sb.append("<h4 class=\"w-h\">Nom affiché</h4>")
                    .append(formStart(v, "portal.update", str(p.get("id")))).append(hidden(p))
                    .append("<input type=\"hidden\" name=\"zone_mode\" value=\"keep\">")
                    .append(field("Nom affiché", "<input type=\"text\" name=\"name\" maxlength=\"" + AgentActionCatalog.PORTAL_MAX_NAME
                            + "\" value=\"" + Http.esc(str(p.get("storedName"))) + "\">",
                            "Vide = nom déduit de la destination. L'identifiant ne change pas."))
                    .append(essential(p)).append(confirm("Renommer « " + str(p.get("id")) + " »"))
                    .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">Enregistrer "
                            + "le nom</button></div></form>");
            sb.append("<h4 class=\"w-h\">Zone d'activation</h4>");
            if (active) {
                sb.append("<p class=\"muted\">Désactiver le portail avant de déplacer ou de redimensionner sa zone.</p>");
            } else {
                sb.append(formStart(v, "portal.update", str(p.get("id")))).append(hidden(p))
                        .append(zoneFields(v, p)).append(essential(p))
                        .append(confirm("Remplacer la zone de « " + str(p.get("id")) + " »"))
                        .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">"
                                + "Enregistrer la zone</button></div></form>");
            }
            sb.append("<div class=\"danger-zone\"><div class=\"dz-title\">").append(Icons.icon("warning"))
                    .append("Supprimer le portail (sa configuration seulement)</div>");
            if (active) {
                sb.append("<p class=\"muted\">Désactiver le portail avant de le supprimer.</p></div>");
            } else {
                sb.append("<p class=\"muted\">Supprime le fichier <code>world-portals/").append(Http.esc(str(p.get("file"))))
                        .append("</code>. <strong>Aucun bloc n'est modifié</strong> ; l'emplacement et un bâtiment "
                                + "éventuellement posé restent en place.</p>")
                        .append(formStart(v, "portal.delete", "")).append(hidden(p)).append(essential(p))
                        .append(confirm("Supprimer définitivement le portail « " + str(p.get("id")) + " »"))
                        .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-danger\" type=\"submit\">")
                        .append(Icons.icon("warning")).append("Supprimer le portail</button></div></form></div>");
            }
        }
        return sb.append("</section>").toString();
    }

    static String historySection(PortalPages.View v, Map<String, Object> p) {
        if (v.details() == null) {
            return "";
        }
        String id = str(p.get("id"));
        List<Map<String, Object>> rows = maps(v.details().get("history")).stream()
                .filter(h -> id.equals(str(h.get("portal")))).limit(10).toList();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\"><h3 class=\"w-h\">")
                .append(Icons.icon("history")).append("Modifications depuis PlugAdmin</h3><ul class=\"w-links\">");
        for (Map<String, Object> h : rows) {
            sb.append("<li><span><strong>").append(Http.esc(operationLabel(str(h.get("operation"))))).append("</strong> · ")
                    .append(Http.esc(str(h.get("at")))).append(" · ").append(Http.esc(str(h.get("actor"))));
            if (!str(h.get("before")).isEmpty()) {
                sb.append("<br><span class=\"muted small\">Avant : ").append(Http.esc(str(h.get("before")))).append("</span>");
            }
            if (!str(h.get("after")).isEmpty()) {
                sb.append("<br><span class=\"muted small\">Après : ").append(Http.esc(str(h.get("after")))).append("</span>");
            }
            sb.append("</span></li>");
        }
        return sb.append("</ul><p class=\"field-help\">Journal du serveur (portal-audit.yml).</p></section>").toString();
    }

    // ---- Briques ----------------------------------------------------------------------------------

    /** Choix de la zone. {@code p} non {@code null} : valeurs actuelles du portail (modification). */
    private static String zoneFields(PortalPages.View v, Map<String, Object> p) {
        boolean bySite = p == null || !str(p.get("site")).isEmpty();
        List<PortalPages.SiteOption> sites = p == null ? v.manage().sites()
                : v.manage().sites().stream().filter(s -> s.world().equals(str(p.get("world")))).toList();
        String currentSite = p == null ? "" : str(p.get("site"));
        StringBuilder sb = new StringBuilder("<fieldset class=\"mb-2\">")
                .append(p == null ? "<legend class=\"small\">Zone d'activation</legend>" : "");
        sb.append("<label class=\"inline\"><input type=\"radio\" name=\"zone_mode\" value=\"site\"")
                .append(bySite ? " checked" : "").append(sites.isEmpty() ? " disabled" : "")
                .append("> Autour d'un emplacement marqué en jeu</label>");
        if (sites.isEmpty()) {
            sb.append("<p class=\"field-help\">Aucun emplacement").append(p == null ? "" : " dans ce monde")
                    .append(" dans le dernier relevé de la page <a href=\"/buildings/sites\">Emplacements</a> : en "
                            + "marquer un en jeu avec l'outil d'emplacement, puis rafraîchir.</p>");
        } else {
            StringBuilder options = new StringBuilder();
            for (PortalPages.SiteOption s : sites) {
                options.append("<option value=\"").append(Http.esc(s.id())).append("\"")
                        .append(s.id().equals(currentSite) ? " selected" : "").append(">")
                        .append(Http.esc(s.name() + " (" + s.id() + ") — " + s.world() + " " + s.position() + ", face "
                                + s.facing())).append("</option>");
            }
            int w = p == null || num(p.get("width")) == 0 ? 3 : num(p.get("width"));
            int h = p == null || num(p.get("height")) == 0 ? 4 : num(p.get("height"));
            int d = p == null || num(p.get("depth")) == 0 ? 1 : num(p.get("depth"));
            sb.append("<div class=\"form-grid\">")
                    .append(field("Emplacement", "<select name=\"site\">" + options + "</select>", ""))
                    .append(field("Largeur", number("width", w, 1), "en travers de la face"))
                    .append(field("Hauteur", number("height", h, 1), "vers le haut depuis le bloc d'ancrage"))
                    .append(field("Profondeur", number("depth", d, 1), "le long de la face"))
                    .append("</div><p class=\"field-help\">Largeur et profondeur sont centrées sur l'emplacement. "
                            + "Exemple : 3 × 4 × 1 = une arche de 3 blocs de large, 4 de haut, 1 d'épaisseur.</p>");
        }
        sb.append("<label class=\"inline\"><input type=\"radio\" name=\"zone_mode\" value=\"coords\"")
                .append(bySite && !sites.isEmpty() ? "" : " checked").append("> En coordonnées (deux coins)</label>");
        sb.append("<div class=\"form-grid\">");
        if (p == null) {
            sb.append(field("Monde de départ", select("world", worlds(v), ""), ""));
        }
        String[] keys = {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z"};
        String[] labels = {"Coin 1 · X", "Coin 1 · Y", "Coin 1 · Z", "Coin 2 · X", "Coin 2 · Y", "Coin 2 · Z"};
        String[] current = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        for (int i = 0; i < keys.length; i++) {
            String value = p == null ? "" : String.valueOf(num(p.get(current[i])));
            sb.append(field(labels[i], "<input type=\"number\" name=\"" + keys[i] + "\" value=\"" + Http.esc(value)
                    + "\" step=\"1\">", ""));
        }
        return sb.append("</div><p class=\"field-help\">").append(AgentActionCatalog.PORTAL_MAX_SIDE)
                .append(" blocs au plus de chaque côté. La zone ne doit recouvrir aucun autre portail : le serveur "
                        + "le vérifie à l'enregistrement et à chaque rechargement.</p></fieldset>").toString();
    }

    private static List<String> worlds(PortalPages.View v) {
        return v.details() != null && v.details().get("worlds") instanceof List<?> l
                ? l.stream().map(PortalPages::str).toList() : List.of();
    }

    private static String formStart(PortalPages.View v, String type, String focus) {
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"/agents/action\" class=\"actform\">")
                .append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(v.manage().csrf())).append("\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(v.agentId())).append("\">")
                .append("<input type=\"hidden\" name=\"type\" value=\"").append(Http.esc(type)).append("\">")
                .append("<input type=\"hidden\" name=\"return\" value=\"").append(PortalPages.PATH).append("\">");
        if (!focus.isEmpty()) {
            sb.append("<input type=\"hidden\" name=\"focus\" value=\"").append(Http.esc(focus)).append("\">");
        }
        return sb.toString();
    }

    private static String hidden(Map<String, Object> p) {
        return "<input type=\"hidden\" name=\"id\" value=\"" + Http.esc(str(p.get("id"))) + "\">"
                + "<input type=\"hidden\" name=\"expected\" value=\"" + Http.esc(str(p.get("fingerprint"))) + "\">";
    }

    private static String essential(Map<String, Object> p) {
        return bool(p.get("essential")) ? "<label class=\"inline confirm\"><input type=\"checkbox\" "
                + "name=\"confirm_essential\" value=\"true\" required> Je confirme modifier un portail essentiel "
                + "(Hub, Wild ou Claims)</label>" : "";
    }

    private static String confirm(String text) {
        return "<label class=\"inline confirm\"><input type=\"checkbox\" name=\"confirm\" value=\"true\" required> "
                + Http.esc(text) + "</label>";
    }

    private static String field(String label, String inputHtml, String help) {
        return "<label class=\"field\"><span>" + Http.esc(label) + "</span>" + inputHtml
                + (help.isEmpty() ? "" : "<small class=\"field-help\">" + Http.esc(help) + "</small>") + "</label>";
    }

    private static String number(String name, int value, int min) {
        return "<input type=\"number\" name=\"" + name + "\" value=\"" + value + "\" min=\"" + min + "\" max=\""
                + AgentActionCatalog.PORTAL_MAX_SIDE + "\" step=\"1\" required>";
    }

    private static String select(String name, List<String> values, String selected) {
        StringBuilder sb = new StringBuilder("<select name=\"").append(name).append("\" required>");
        for (String value : values) {
            sb.append("<option value=\"").append(Http.esc(value)).append("\"").append(value.equals(selected) ? " selected" : "")
                    .append(">").append(Http.esc(value)).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    private static String strategySelect() {
        StringBuilder sb = new StringBuilder("<select name=\"strategy\">");
        for (String s : List.of("WORLD_SPAWN", "RANDOM_SAFE", "CLAIM_HOME")) {
            sb.append("<option value=\"").append(s).append("\">").append(Http.esc(PortalPages.strategyLabel(s)))
                    .append("</option>");
        }
        return sb.append("</select>").toString();
    }

    private static String operationLabel(String op) {
        return switch (op) {
            case "CREATE_DRAFT" -> "Brouillon créé";
            case "UPDATE" -> "Modifié";
            case "ENABLE" -> "Activé";
            case "DISABLE" -> "Désactivé";
            case "DELETE" -> "Supprimé";
            default -> op;
        };
    }
}
