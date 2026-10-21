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
 * Issue #566 (#228-P3) : arrivée (monde, stratégie, point fixe et regard), portail retour préparé
 * depuis la fiche de l'aller, et « Vérifier sans appliquer ».
 * Issue #567 (#228-P4) : conditions d'accès (niveau RPG, conditions joueur existantes, délai, message
 * de refus par condition) et simulation AUTORISÉ / REFUSÉ pour un joueur connecté.
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
        Map<String, Object> outbound = v.returnOf().isEmpty() ? null : PortalPages.maps(v.details().get("portals"))
                .stream().filter(p -> v.returnOf().equals(str(p.get("id"))) && bool(p.get("editable")))
                .findFirst().orElse(null);
        sb.append(formStart(v, "portal.create", ""));
        if (outbound != null) {
            sb.append(Ui.banner("info", "<strong>Portail retour de <code>" + Http.esc(v.returnOf()) + "</code></strong> : "
                    + "il partira de <code>" + Http.esc(str(outbound.get("destinationWorld"))) + "</code> et ramènera "
                    + "à <code>" + Http.esc(str(outbound.get("world"))) + "</code>. C'est un second portail, distinct, "
                    + "créé en brouillon ; l'aller n'est pas modifié."))
                    .append("<input type=\"hidden\" name=\"return_of\" value=\"").append(Http.esc(v.returnOf()))
                    .append("\">");
        }
        sb.append("<div class=\"form-grid\">")
                .append(field("Identifiant (définitif)", "<input type=\"text\" name=\"id\" required pattern=\"[a-z0-9_\\-]{3,48}\" "
                        + "maxlength=\"48\" placeholder=\"arche_du_marche\" autocomplete=\"off\">",
                        "3 à 48 caractères : minuscules, chiffres, « _ », « - ». Il ne changera plus."))
                .append(field("Nom affiché", "<input type=\"text\" name=\"name\" maxlength=\"" + AgentActionCatalog.PORTAL_MAX_NAME
                        + "\" placeholder=\"Arche du marché\">", "Facultatif ; sinon « Portail vers … »."))
                .append("</div>");
        sb.append(zoneFields(v, null, outbound == null ? "" : str(outbound.get("destinationWorld"))));
        sb.append(destinationFields(worlds, outbound == null ? "" : str(outbound.get("world")),
                outbound == null ? "" : "FIXED_POINT", null));
        sb.append(confirm("Je crée un brouillon : il ne téléportera personne tant que je ne l'active pas."));
        sb.append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Icons.icon("plus")).append("Créer le brouillon</button></div></form></section>");
        return sb.toString();
    }

    // ---- Fiche ------------------------------------------------------------------------------------

    /** « Vérifier sans appliquer » et dernier résultat (issue #566). */
    static String verifySection(PortalPages.View v, Map<String, Object> p) {
        if (!"WORLD_PORTAL".equals(str(p.get("kind"))) || v.manage().csrf().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"portal-verify\"><h3 class=\"w-h\">")
                .append(Icons.icon("diagnostics")).append("Vérifier sans appliquer</h3>")
                .append("<p class=\"field-help\">Contrôle le départ, la zone, le monde d'arrivée, la sécurité du point "
                        + "d'arrivée et l'aller-retour. <strong>Rien n'est modifié</strong> : aucun fichier écrit, aucun "
                        + "monde ni chunk chargé, personne n'est téléporté.</p>");
        PortalPages.Verify last = v.verify();
        if (last != null) {
            Map<String, Object> d = last.details();
            boolean stale = !str(d.get("fingerprint")).equals(str(p.get("fingerprint")));
            String verdict = str(d.get("verdict"));
            sb.append("<p>").append(verdictPill(verdict)).append(" <span class=\"muted small\">")
                    .append(last.atHtml()).append("</span></p>");
            if (stale) {
                sb.append(Ui.banner("warn", "Ce résultat précède la dernière modification du portail : vérifier à "
                        + "nouveau."));
            }
            sb.append("<ul class=\"w-links\">");
            for (Map<String, Object> c : maps(d.get("checks"))) {
                sb.append("<li><span>").append(verdictPill(str(c.get("status")))).append(" <strong>")
                        .append(Http.esc(str(c.get("title")))).append("</strong> — ")
                        .append(Http.esc(str(c.get("message")))).append("</span></li>");
            }
            sb.append("</ul>");
        }
        sb.append(formStart(v, "portal.verify", str(p.get("id"))))
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(str(p.get("id")))).append("\">")
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">")
                .append(last == null ? "Vérifier" : "Vérifier à nouveau").append("</button></div></form>");
        return sb.append("</section>").toString();
    }

    private static String verdictPill(String status) {
        return switch (status) {
            case "OK" -> "<span class=\"pill ok\">OK</span>";
            case "WARNING" -> "<span class=\"pill warn\">À regarder</span>";
            case "UNKNOWN" -> "<span class=\"pill pill--neutral\">Non vérifiable</span>";
            case "BLOCKING" -> "<span class=\"pill err\">Bloquant</span>";
            default -> "<span class=\"pill\">" + Http.esc(status) + "</span>";
        };
    }

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
                        .append(zoneFields(v, p, "")).append(essential(p))
                        .append(confirm("Remplacer la zone de « " + str(p.get("id")) + " »"))
                        .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">"
                                + "Enregistrer la zone</button></div></form>");
            }
            sb.append("<h4 class=\"w-h\">Arrivée</h4>");
            if (active) {
                sb.append("<p class=\"muted\">Désactiver le portail avant de changer son arrivée.</p>");
            } else {
                sb.append(formStart(v, "portal.update", str(p.get("id")))).append(hidden(p))
                        .append("<input type=\"hidden\" name=\"zone_mode\" value=\"keep\">")
                        .append("<input type=\"hidden\" name=\"dest_mode\" value=\"set\">")
                        .append(destinationFields(worlds(v), str(p.get("destinationWorld")), str(p.get("strategy")), p))
                        .append(essential(p))
                        .append(confirm("Remplacer l'arrivée de « " + str(p.get("id")) + " »"))
                        .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">"
                                + "Enregistrer l'arrivée</button></div></form>");
            }
            sb.append("<h4 class=\"w-h\" id=\"portal-access\">Conditions d'accès</h4>");
            if (active) {
                sb.append("<p class=\"muted\">Désactiver le portail avant de changer ses conditions d'accès.</p>");
            } else {
                sb.append(accessForm(v, p));
            }
            boolean hasReturn = p.get("returnedBy") instanceof List<?> r && !r.isEmpty();
            if (str(p.get("returnOf")).isEmpty() && !hasReturn) {
                sb.append("<h4 class=\"w-h\">Aller-retour</h4><p class=\"field-help\">Le retour est un second "
                        + "portail, distinct, créé en brouillon dans <code>").append(Http.esc(str(p.get("destinationWorld"))))
                        .append("</code>. Ce portail-ci n'est pas modifié.</p><div class=\"btnrow\"><a class=\"btn btn-sm "
                                + "btn-outline-primary\" href=\"").append(PortalPages.PATH).append("?agent=")
                        .append(PortalPages.enc(v.agentId())).append("&amp;return_of=").append(PortalPages.enc(str(p.get("id"))))
                        .append("#portal-create\">Préparer le portail retour</a></div>");
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

    // ---- Conditions d'accès et simulation (issue #567) ---------------------------------------------

    private static final List<String[]> CONDITION_TYPES = List.of(
            new String[] {"QUEST_STATE", "Quête dans un état"},
            new String[] {"HAS_ITEM", "Avoir un objet"},
            new String[] {"HAS_PERMISSION", "Avoir une permission"},
            new String[] {"VARIABLE_EQUALS", "Variable joueur égale à"},
            new String[] {"HAS_MAIN_CLAIM", "Avoir un claim principal"});
    private static final List<String> QUEST_STATES = List.of("NOT_STARTED", "ACTIVE", "READY_TO_TURN_IN", "COMPLETED",
            "FAILED", "ABANDONED");

    /**
     * Formulaire des conditions d'accès d'un portail non actif. Sans JavaScript : un nombre fixe de
     * lignes (les conditions actuelles, plus deux vides), chaque ligne garde tous ses champs et le
     * serveur ne lit que ceux du type choisi. Type vide = ligne ignorée (retirer une condition).
     */
    private static String accessForm(PortalPages.View v, Map<String, Object> p) {
        Map<String, Object> access = PortalPages.maps(List.of(p.getOrDefault("access", Map.of()))).get(0);
        List<Map<String, Object>> rules = maps(access.get("rules"));
        int rows = Math.min(AgentActionCatalog.PORTAL_MAX_CONDITIONS, Math.max(2, rules.size() + 1));
        String id = str(p.get("id"));
        StringBuilder sb = new StringBuilder("<p class=\"field-help\">Vérifiées <strong>par le serveur</strong> à chaque "
                + "passage, après les règles Claims / Wild ; toutes doivent être remplies. Un joueur refusé reste sur "
                + "place et reçoit le message de la première condition non remplie (vide = message par défaut). Rien "
                + "n'est retiré au joueur : « Avoir un objet » vérifie seulement l'inventaire.</p>");
        sb.append(formStart(v, "portal.access", id)).append(hidden(p));
        sb.append("<div class=\"form-grid\">")
                .append(field("Niveau RPG minimum", "<input type=\"number\" name=\"level\" min=\"1\" max=\"1000\" "
                        + "step=\"1\" value=\"" + Http.esc(str(access.get("level"))) + "\">", "Vide = aucun niveau exigé."))
                .append(field("Attente entre deux passages (s)", "<input type=\"number\" name=\"cooldown\" min=\"0\" "
                        + "max=\"86400\" step=\"1\" value=\"" + Http.esc(num(access.get("cooldown")) == 0 ? ""
                        : String.valueOf(num(access.get("cooldown")))) + "\">", "Par joueur, après un passage réussi. "
                        + "Vide ou 0 = aucune."))
                .append("</div>");
        sb.append(StyleField.render("level_message", "pa-" + id + "-level-msg", "Message si le niveau manque",
                str(access.get("levelMessage")), false, "%level% = niveau exigé."));
        sb.append(StyleField.render("cooldown_message", "pa-" + id + "-cd-msg", "Message pendant l'attente",
                str(access.get("cooldownMessage")), false, "%seconds% = secondes restantes."));
        for (int i = 0; i < rows; i++) {
            Map<String, Object> r = i < rules.size() ? rules.get(i) : Map.of();
            sb.append(conditionRow(id, i, r));
        }
        if (rows < AgentActionCatalog.PORTAL_MAX_CONDITIONS) {
            sb.append("<p class=\"field-help\">Besoin de plus de lignes : enregistrer, la fiche en proposera une "
                    + "nouvelle (").append(AgentActionCatalog.PORTAL_MAX_CONDITIONS).append(" au plus).</p>");
        }
        sb.append(essential(p)).append(confirm("Remplacer les conditions d'accès de « " + id + " »"))
                .append("<div class=\"btnrow\"><button class=\"btn btn-sm btn-primary\" type=\"submit\">Enregistrer "
                        + "les conditions</button></div></form>");
        return sb.toString();
    }

    private static String conditionRow(String portalId, int i, Map<String, Object> r) {
        String prefix = "cond_" + i + "_";
        String type = str(r.get("type"));
        StringBuilder types = new StringBuilder("<select name=\"" + prefix + "type\"><option value=\"\""
                + (type.isEmpty() ? " selected" : "") + ">— aucune —</option>");
        for (String[] t : CONDITION_TYPES) {
            types.append("<option value=\"").append(t[0]).append("\"").append(t[0].equals(type) ? " selected" : "")
                    .append(">").append(Http.esc(t[1])).append("</option>");
        }
        types.append("</select>");
        StringBuilder states = new StringBuilder("<select name=\"" + prefix + "state\">");
        String state = str(r.get("state")).isEmpty() ? "COMPLETED" : str(r.get("state"));
        for (String st : QUEST_STATES) {
            states.append("<option value=\"").append(st).append("\"").append(st.equals(state) ? " selected" : "")
                    .append(">").append(st).append("</option>");
        }
        states.append("</select>");
        StringBuilder sb = new StringBuilder("<fieldset class=\"mb-2\"><legend class=\"small\">Condition ")
                .append(i + 1).append(type.isEmpty() ? "" : " · " + Http.esc(str(r.get("label")))).append("</legend>");
        sb.append("<div class=\"form-grid\">").append(field("Type", types.toString(), ""))
                .append("<label class=\"inline\"><input type=\"checkbox\" name=\"").append(prefix)
                .append("negate\" value=\"true\"").append(bool(r.get("negate")) ? " checked" : "")
                .append("> Inverser (le joueur ne doit PAS remplir la condition)</label></div>");
        sb.append("<div class=\"form-grid\">")
                .append(field("Quête", text(prefix + "quest", str(r.get("quest")), "rpgquest:crystal_hunt"),
                        "Quête dans un état."))
                .append(field("État de la quête", states.toString(), ""))
                .append(field("Objet (matériau)", text(prefix + "material", str(r.get("material")), "DIAMOND"),
                        "Avoir un objet."))
                .append(field("Quantité", "<input type=\"number\" name=\"" + prefix + "amount\" min=\"1\" max=\"2304\" "
                        + "value=\"" + Http.esc(str(r.get("amount"))) + "\">", ""))
                .append(field("Permission", text(prefix + "permission", str(r.get("permission")), "rpgquest.portal.x"),
                        "Avoir une permission."))
                .append(field("Variable", text(prefix + "key", str(r.get("key")), "CLAIM_TIER_1"),
                        "Variable joueur égale à."))
                .append(field("Valeur", text(prefix + "value", str(r.get("value")), "true"), ""))
                .append("</div>");
        sb.append(StyleField.render(prefix + "message", "pa-" + portalId + "-c" + i + "-msg", "Message de refus",
                str(r.get("message")), false, "Montré au joueur refusé par cette condition. Vide = message par défaut."));
        return sb.append("<p class=\"field-help\">Seuls les champs du type choisi sont lus.</p></fieldset>").toString();
    }

    private static String text(String name, String value, String placeholder) {
        return "<input type=\"text\" name=\"" + name + "\" maxlength=\"100\" value=\"" + Http.esc(value)
                + "\" placeholder=\"" + Http.esc(placeholder) + "\" autocomplete=\"off\">";
    }

    /** Simulation AUTORISÉ / REFUSÉ (lecture seule) et dernier résultat du portail ouvert. */
    static String simulateSection(PortalPages.View v, Map<String, Object> p) {
        if (!"WORLD_PORTAL".equals(str(p.get("kind"))) || v.manage().csrf().isEmpty()) {
            return "";
        }
        String id = str(p.get("id"));
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"portal-simulate\"><h3 class=\"w-h\">")
                .append(Icons.icon("diagnostics")).append("Simuler un passage</h3>")
                .append("<p class=\"field-help\">Le serveur évalue les conditions d'accès pour un joueur <strong>"
                        + "connecté</strong> et répond AUTORISÉ ou REFUSÉ. <strong>Personne n'est téléporté</strong>, "
                        + "aucun délai n'est démarré, rien n'est écrit. Les règles Claims / Wild ne sont pas simulées.</p>");
        PortalPages.Simulation last = v.access().simulation();
        if (last != null) {
            Map<String, Object> d = last.details();
            boolean allowed = bool(d.get("allowed"));
            sb.append("<p>").append(!last.success() ? "<span class=\"pill pill--neutral\">Non évalué</span>"
                    : allowed ? "<span class=\"pill ok\">AUTORISÉ</span>" : "<span class=\"pill err\">REFUSÉ</span>")
                    .append(" ").append(Http.esc(last.message())).append(" <span class=\"muted small\">")
                    .append(last.atHtml()).append("</span></p>");
            List<Map<String, Object>> checks = maps(d.get("checks"));
            if (!checks.isEmpty()) {
                sb.append("<ul class=\"w-links\">");
                for (Map<String, Object> c : checks) {
                    boolean ok = bool(c.get("passed"));
                    sb.append("<li><span>").append(ok ? "<span class=\"pill ok\">OK</span>"
                            : "<span class=\"pill err\">Non</span>").append(" ").append(Http.esc(str(c.get("label"))));
                    if (!ok) {
                        sb.append("<br><span class=\"muted small\">Message au joueur : « ")
                                .append(Http.esc(str(c.get("refusal")))).append(" »</span>");
                    }
                    sb.append("</span></li>");
                }
                sb.append("</ul>");
            }
            for (String note : strings(d.get("notes"))) {
                sb.append("<p class=\"muted small\">").append(Http.esc(note)).append("</p>");
            }
        }
        String listId = "pa-sim-players-" + id;
        sb.append(formStart(v, "portal.simulate", id))
                .append("<input type=\"hidden\" name=\"id\" value=\"").append(Http.esc(id)).append("\">")
                .append(field("Joueur connecté (pseudo)", "<input type=\"text\" name=\"player\" required maxlength=\"40\" "
                        + "pattern=\"[A-Za-z0-9_\\-]{1,40}\" list=\"" + listId + "\" autocomplete=\"off\">",
                        v.access().onlinePlayers().isEmpty() ? "Rafraîchir la page Joueurs pour proposer les joueurs connectés."
                                : "Joueurs connectés au dernier relevé proposés."))
                .append("<datalist id=\"").append(listId).append("\">");
        for (String name : v.access().onlinePlayers()) {
            sb.append("<option value=\"").append(Http.esc(name)).append("\">");
        }
        sb.append("</datalist><div class=\"btnrow\"><button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">"
                + "Simuler (sans téléporter)</button></div></form>");
        return sb.append("</section>").toString();
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(PortalPages::str).toList() : List.of();
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

    /**
     * Arrivée : monde, stratégie et point fixe. {@code p} non {@code null} : valeurs actuelles du portail.
     * Sans JavaScript, les champs du point restent visibles ; le serveur les ignore hors « point fixe ».
     */
    private static String destinationFields(List<String> worlds, String world, String strategy, Map<String, Object> p) {
        // Monde actuel absent du relevé (non chargé) : gardé en tête de liste plutôt que remplacé en silence.
        List<String> choices = world.isEmpty() || worlds.contains(world) ? worlds
                : java.util.stream.Stream.concat(java.util.stream.Stream.of(world), worlds.stream()).toList();
        StringBuilder sb = new StringBuilder("<fieldset class=\"mb-2\">")
                .append(p == null ? "<legend class=\"small\">Arrivée</legend>" : "")
                .append("<div class=\"form-grid\">")
                .append(field("Monde d'arrivée", select("destination_world", choices, world), "Un monde chargé ; le "
                        + "portail ne charge jamais un monde lui-même."))
                .append(field("Arrivée", strategySelect(strategy), "« Claim du joueur » : seulement vers le monde des "
                        + "claims."))
                .append("</div><div class=\"form-grid\">");
        String[] keys = {"point_x", "point_y", "point_z", "point_yaw", "point_pitch"};
        String[] labels = {"Point fixe · X", "Point fixe · Y (pieds)", "Point fixe · Z", "Regard horizontal (°)",
                "Regard vertical (°)"};
        String[] current = {"pointX", "pointY", "pointZ", "pointYaw", "pointPitch"};
        for (int i = 0; i < keys.length; i++) {
            Object value = p == null ? null : p.get(current[i]);
            sb.append(field(labels[i], "<input type=\"text\" inputmode=\"decimal\" name=\"" + keys[i] + "\" value=\""
                    + Http.esc(value == null ? "" : decimalText(value)) + "\" maxlength=\"14\">", i == 3
                    ? "0 = sud, 90 = ouest, 180 = nord, -90 = est (F3 en jeu)." : i == 4 ? "-90 ciel, 0 horizon, 90 sol." : ""));
        }
        return sb.append("</div><p class=\"field-help\">Point fixe seulement. Le serveur refuse un point hors des "
                + "hauteurs du monde ou dans la zone d'un portail. À chaque passage, un point non sûr (lave, vide, "
                + "bloc plein) est remplacé par la position sûre la plus proche à 2 blocs au plus ; sinon le passage "
                + "est refusé.</p></fieldset>").toString();
    }

    private static String decimalText(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 1000) / 1000.0);
        }
        return str(value);
    }

    /**
     * Choix de la zone. {@code p} non {@code null} : valeurs actuelles du portail (modification) ;
     * {@code sourceWorld} : monde de départ présélectionné (portail retour).
     */
    private static String zoneFields(PortalPages.View v, Map<String, Object> p, String sourceWorld) {
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
            sb.append(field("Monde de départ", select("world", worlds(v), sourceWorld), ""));
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

    private static String strategySelect(String selected) {
        StringBuilder sb = new StringBuilder("<select name=\"strategy\">");
        for (String s : List.of("WORLD_SPAWN", "RANDOM_SAFE", "CLAIM_HOME", "FIXED_POINT")) {
            sb.append("<option value=\"").append(s).append("\"").append(s.equals(selected) ? " selected" : "")
                    .append(">").append(Http.esc(PortalPages.strategyLabel(s))).append("</option>");
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
            case "ACCESS" -> "Conditions d'accès modifiées";
            default -> op;
        };
    }
}
