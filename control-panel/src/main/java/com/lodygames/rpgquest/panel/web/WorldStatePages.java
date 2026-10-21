package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issue #563 (#331-W4) — charger, décharger, importer un dossier existant et régler « charger au
 * démarrage », d'après le dernier relevé {@code world.state.options}.
 *
 * <p>Deux blocs : la section « Chargement » de la fiche d'un monde, et « Importer un dossier
 * existant » sous la liste. Un bouton n'apparaît que si le relevé dit l'opération possible ; sinon
 * les raisons du refus s'affichent. Chaque envoi exige une case de confirmation ; le plugin
 * revérifie tout sur l'état réel. Rien ici ne supprime un monde ni un fichier.</p>
 */
public final class WorldStatePages {

    static final String ANCHOR = "chargement";
    static final String IMPORT_ANCHOR = "importer";

    /**
     * @param options  dernier relevé réussi ({@code null} = jamais relevé)
     * @param when     date du relevé (HTML sûr, peut être vide)
     * @param refresh  bouton « Relever » (HTML sûr)
     * @param last     dernière opération demandée pour ce monde / ces dossiers ({@code null} = aucune)
     * @param lastDetails détails de {@code last} ({@code null} = pas encore de résultat)
     */
    public record View(String csrf, String agentId, boolean canWrite, Map<String, Object> options, String when,
                       String refresh, AgentActionRow last, Map<String, Object> lastDetails) {
    }

    private WorldStatePages() {
    }

    // ---- Fiche : section « Chargement » ---------------------------------------------------------

    static String fiche(View v, String world) {
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"").append(ANCHOR)
                .append("\"><h3 class=\"w-h\">").append(Icons.icon("diagnostics")).append("Chargement</h3>");
        sb.append(lastOperation(v));
        Map<String, Object> entry = v.options() == null ? null : WorldPages.maps(v.options().get("worlds")).stream()
                .filter(w -> world.equals(WorldPages.str(w.get("name")))).findFirst().orElse(null);
        String head = head(v);
        if (v.options() == null) {
            return sb.append(head).append("<p class=\"muted\">Relever l'état pour savoir si ce monde peut être chargé "
                    + "ou déchargé.</p></section>").toString();
        }
        sb.append(head);
        if (!WorldPages.bool(v.options().get("multiverse"))) {
            return sb.append(Ui.banner("info", "Multiverse-Core n'est pas utilisable ("
                    + Http.esc(WorldPages.str(v.options().get("multiverseReason"))) + ") : aucun monde n'est chargé, "
                    + "déchargé ni importé d'ici.")).append("</section>").toString();
        }
        if (entry == null) {
            return sb.append("<p class=\"muted\">Ce monde n'est ni chargé ni connu de Multiverse-Core au dernier "
                    + "relevé : s'il s'agit d'un dossier, voir « Importer un dossier existant » sur la liste.</p>"
                    + "</section>").toString();
        }
        boolean loaded = WorldPages.bool(entry.get("loaded"));
        sb.append("<p>").append(WorldPages.loadedPill(loaded)).append(" · chargé au démarrage : <strong>")
                .append(WorldPages.bool(entry.get("autoLoad")) ? "oui" : "non").append("</strong>");
        if (loaded) {
            sb.append(" · ").append(Http.esc(WorldPages.str(entry.get("players")))).append(" joueur(s) présent(s)");
        }
        sb.append("</p>");
        Map<String, Object> ops = WorldPages.map(entry.get("operations"));
        Map<String, Object> main = WorldPages.map(ops.get(loaded ? "UNLOAD" : "LOAD"));
        sb.append(operation(v, world, main, loaded ? "world.unload" : "world.load",
                loaded ? "Enregistrer puis décharger" : "Charger", true));
        sb.append(operation(v, world, WorldPages.map(ops.get("AUTOLOAD")), "world.autoload",
                WorldPages.bool(entry.get("autoLoad")) ? "Ne plus charger au démarrage" : "Charger au démarrage", false));
        return sb.append("<p class=\"field-help\">Décharger enregistre le monde puis le retire de la mémoire du "
                + "serveur : ses fichiers restent sur le disque et Multiverse-Core le garde pour le recharger. Aucune "
                + "suppression n'est possible d'ici.</p></section>").toString();
    }

    private static String head(View v) {
        return "<div class=\"btnrow mb-2\">" + v.refresh() + (v.when().isEmpty() ? ""
                : " <span class=\"muted small\">Relevé : " + v.when() + "</span>") + "</div>";
    }

    /** Une opération : refus lisibles, ou formulaire confirmé si le relevé l'autorise et le rôle le permet. */
    private static String operation(View v, String world, Map<String, Object> plan, String type, String label,
                                    boolean askAutoLoad) {
        StringBuilder sb = new StringBuilder("<div class=\"w-op\"><p class=\"mb-1\"><strong>").append(Http.esc(label))
                .append("</strong>");
        if (!WorldPages.str(plan.get("summary")).isEmpty()) {
            sb.append(" — <span class=\"muted\">").append(Http.esc(WorldPages.str(plan.get("summary")))).append("</span>");
        }
        sb.append("</p>");
        List<String> errors = WorldPages.list(plan.get("errors"));
        if (!errors.isEmpty()) {
            sb.append("<ul class=\"w-links\">");
            errors.forEach(e -> sb.append("<li class=\"text-danger\">").append(Icons.icon("lock")).append("<span>")
                    .append(Http.esc(e)).append("</span></li>"));
            return sb.append("</ul></div>").toString();
        }
        WorldPages.list(plan.get("warnings")).forEach(w -> sb.append("<p class=\"w-note\">").append(Icons.icon("warning"))
                .append(Http.esc(w)).append("</p>"));
        if (!v.canWrite()) {
            return sb.append("<p class=\"muted\">Votre rôle permet de consulter les mondes, pas de les charger ni de les "
                    + "décharger (WORLD_WRITE).</p></div>").toString();
        }
        boolean autoLoad = WorldPages.bool(plan.get("autoLoad"));
        sb.append(formStart(v, type)).append(hidden("world", world)).append(hidden("focus", world));
        if (askAutoLoad) {
            sb.append(autoLoadBox(autoLoad));
        } else {
            sb.append(hidden("auto_load", String.valueOf(autoLoad)));
        }
        sb.append(confirmBox("Je confirme : " + label.toLowerCase(java.util.Locale.ROOT) + " « " + world + " »."));
        return sb.append("<button class=\"btn btn-outline-primary btn-sm\" type=\"submit\">").append(Http.esc(label))
                .append("</button></form></div>").toString();
    }

    // ---- Liste : « Importer un dossier existant » -----------------------------------------------

    static String importSection(View v) {
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec mt-3\" id=\"").append(IMPORT_ANCHOR)
                .append("\"><h3 class=\"w-h\">").append(Icons.icon("plus")).append("Importer un dossier existant</h3>")
                .append("<p class=\"field-help\">Un dossier de monde déjà présent sur le serveur (copié par FTP, ancien "
                        + "monde…) que Multiverse-Core ne connaît pas encore. L'import l'ajoute à Multiverse-Core et le "
                        + "charge tel quel : aucun bloc n'est modifié, aucun rôle, portail ni waypoint n'est attribué.</p>");
        sb.append(lastOperation(v)).append(head(v));
        if (v.options() == null) {
            return sb.append("<p class=\"muted\">Relever l'état pour lister les dossiers importables.</p></section>")
                    .toString();
        }
        if (!WorldPages.bool(v.options().get("multiverse"))) {
            return sb.append(Ui.banner("info", "Multiverse-Core n'est pas utilisable ("
                    + Http.esc(WorldPages.str(v.options().get("multiverseReason"))) + ") : import impossible."))
                    .append("</section>").toString();
        }
        List<Map<String, Object>> folders = WorldPages.maps(v.options().get("importable"));
        if (folders.isEmpty()) {
            return sb.append("<p class=\"muted\">Aucun dossier importable : chaque dossier de monde du serveur est déjà "
                    + "chargé ou connu de Multiverse-Core.</p></section>").toString();
        }
        for (Map<String, Object> f : folders) {
            sb.append(folder(v, f));
        }
        return sb.append("</section>").toString();
    }

    private static String folder(View v, Map<String, Object> f) {
        String name = WorldPages.str(f.get("name"));
        String detected = WorldPages.str(f.get("dimension"));
        Map<String, Object> plan = WorldPages.map(f.get("plan"));
        StringBuilder sb = new StringBuilder("<div class=\"w-op\"><p class=\"mb-1\"><code>").append(Http.esc(name))
                .append("</code> — dimension lue dans le dossier : <strong>")
                .append(Http.esc(detected.isEmpty() ? "inconnue" : WorldPages.dimensionLabel(detected)))
                .append("</strong></p>");
        List<String> errors = WorldPages.list(plan.get("errors"));
        if (!errors.isEmpty()) {
            sb.append("<ul class=\"w-links\">");
            errors.forEach(e -> sb.append("<li class=\"text-danger\">").append(Icons.icon("lock")).append("<span>")
                    .append(Http.esc(e)).append("</span></li>"));
            return sb.append("</ul></div>").toString();
        }
        WorldPages.list(plan.get("warnings")).forEach(w -> sb.append("<p class=\"w-note\">").append(Icons.icon("warning"))
                .append(Http.esc(w)).append("</p>"));
        if (!v.canWrite()) {
            return sb.append("<p class=\"muted\">Votre rôle ne permet pas d'importer un monde (WORLD_WRITE).</p></div>")
                    .toString();
        }
        sb.append(formStart(v, "world.import")).append(hidden("world", name));
        sb.append("<label class=\"inline\">Dimension <select name=\"environment\" class=\"form-select form-select-sm\">");
        for (String d : List.of("NORMAL", "NETHER", "THE_END")) {
            sb.append("<option value=\"").append(d).append("\"").append(d.equals(detected) ? " selected" : "")
                    .append(">").append(Http.esc(WorldPages.dimensionLabel(d))).append("</option>");
        }
        sb.append("</select></label> ");
        sb.append(autoLoadBox(true));
        sb.append(confirmBox("Je confirme importer et charger le dossier « " + name + " »."));
        return sb.append("<button class=\"btn btn-outline-primary btn-sm\" type=\"submit\">Importer et charger</button>")
                .append("</form></div>").toString();
    }

    // ---- Dernière opération ---------------------------------------------------------------------

    private static String lastOperation(View v) {
        AgentActionRow row = v.last();
        if (row == null) {
            return "";
        }
        String what = switch (row.type()) {
            case "world.load" -> "Chargement";
            case "world.unload" -> "Déchargement";
            case "world.import" -> "Import";
            default -> "« Charger au démarrage »";
        };
        String world = row.params().getOrDefault("world", "");
        if (!row.status().terminal()) {
            return Ui.banner("info", Http.esc(what) + " de <code>" + Http.esc(world) + "</code> envoyé au serveur, en "
                    + "attente de sa réponse. Rafraîchir dans un instant.");
        }
        String message = row.resultMessage() == null ? "" : row.resultMessage();
        StringBuilder sb = new StringBuilder(Ui.banner("SUCCESS".equals(row.status().name()) ? "success" : "warn",
                Http.esc(what) + " de <code>" + Http.esc(world) + "</code> : " + Http.esc(message)));
        if (v.lastDetails() != null) {
            List<Map<String, Object>> checks = WorldPages.maps(v.lastDetails().get("checks"));
            if (!checks.isEmpty()) {
                sb.append("<ul class=\"w-links\">");
                for (Map<String, Object> c : checks) {
                    sb.append("<li>").append(Icons.icon(WorldPages.bool(c.get("ok")) ? "check" : "warning")).append("<span>")
                            .append(Http.esc(WorldPages.str(c.get("label")))).append(" : demandé ")
                            .append(Http.esc(WorldPages.str(c.get("expected")))).append(", relu ")
                            .append(Http.esc(WorldPages.str(c.get("actual")))).append("</span></li>");
                }
                sb.append("</ul>");
            }
            WorldPages.list(v.lastDetails().get("notes")).forEach(n -> sb.append("<p class=\"w-note\">")
                    .append(Icons.icon("info")).append(Http.esc(n)).append("</p>"));
        }
        return sb.toString();
    }

    // ---- Formulaires ----------------------------------------------------------------------------

    private static String formStart(View v, String type) {
        String token = UUID.randomUUID().toString();
        return "<form method=\"post\" action=\"/agents/action\" class=\"actform\">" + hidden("_csrf", v.csrf())
                + hidden("agent", v.agentId()) + hidden("type", type) + hidden("return", WorldPages.PATH)
                + hidden("submit_token", token) + hidden("token", token);
    }

    private static String autoLoadBox(boolean checked) {
        return "<input type=\"hidden\" name=\"auto_load\" value=\"false\">"
                + "<label class=\"inline\"><input type=\"checkbox\" name=\"auto_load\" value=\"true\""
                + (checked ? " checked" : "") + "> Charger au démarrage du serveur</label> ";
    }

    private static String confirmBox(String text) {
        return "<label class=\"inline confirm\"><input type=\"checkbox\" name=\"confirm\" value=\"true\" required> "
                + Http.esc(text) + "</label> ";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value) + "\">";
    }
}
