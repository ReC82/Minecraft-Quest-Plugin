package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issue #562 (#331-W3) — page « Créer un monde » : options lues sur le serveur, formulaire guidé
 * (nom → identifiant, dimension, terrain, graine, structures, monde plat : préréglage, couches et
 * biome), aperçu validé par le serveur (rien n'est créé), confirmation explicite, puis état
 * « En cours » → « Prêt » (après relecture) ou « Échec » avec la raison.
 *
 * <p>Sans JavaScript (CSP) : le terrain et les options de monde plat sont masqués en CSS
 * ({@code :has}) quand ils ne s'appliquent pas, et PlugAdmin ne transmet pas ce qui est masqué.
 * La création reprend les paramètres de l'aperçu enregistré côté PlugAdmin, jamais ceux du
 * navigateur.</p>
 */
public final class WorldCreatePages {

    static final String PATH = "/worlds/create";
    static final String ANCHOR = "w-create";
    /** Lignes de couches proposées par le formulaire (le serveur en accepte jusqu'à 16). */
    static final int LAYER_ROWS = 6;

    /**
     * @param options       dernières options lues ({@code null} = jamais lues)
     * @param optionsAt     date de cette lecture (HTML sûr, déjà échappé)
     * @param pendingOptions lecture des options en attente ({@code null} = aucune)
     * @param preview       aperçu demandé ({@code ?preview=}), {@code null} sinon
     * @param applied       création demandée ({@code ?applied=}), {@code null} sinon
     */
    public record View(String agentId, String headerHtml, Map<String, Object> options, String optionsAt,
                       AgentActionRow pendingOptions, AgentActionRow preview, Map<String, Object> previewDetails,
                       AgentActionRow applied, Map<String, Object> appliedDetails) {

        static View noAgent(String html) {
            return new View("", html, null, "", null, null, null, null, null);
        }
    }

    private WorldCreatePages() {
    }

    static String href(String agentId) {
        return PATH + "?agent=" + Http.esc(java.net.URLEncoder.encode(agentId, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String render(View v) {
        String back = WorldPages.PATH + (v.agentId().isEmpty() ? "" : "?agent=" + java.net.URLEncoder.encode(v.agentId(),
                java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(Ui.pageHeader("world", "Créer un monde",
                "Nouveau monde géré par Multiverse-Core : aperçu vérifié par le serveur, puis création après "
                        + "confirmation. Aucun monde existant n'est touché.",
                "<a class=\"btn secondary\" href=\"" + Http.esc(back) + "\">Retour aux mondes</a>"))
                .append(v.headerHtml());
        if (v.agentId().isEmpty()) {
            return sb.toString();
        }
        sb.append("<section class=\"card w-sec\" id=\"").append(ANCHOR).append("\">")
                .append("<p class=\"muted\">Seules les options réellement prises en charge par Paper 1.21.11 et "
                        + "Multiverse-Core 5.7.3 sont proposées. <strong>Rien n'est créé avant la confirmation finale.</strong> "
                        + "Le nouveau monde n'a aucun rôle Hub / Wild / Claims, aucun portail, aucun waypoint "
                        + "automatique ; ses réglages se font ensuite depuis sa fiche.</p>");
        if (v.applied() != null) {
            sb.append(appliedBlock(v));
        }
        if (v.preview() != null && v.applied() == null) {
            sb.append(previewBlock(v));
        }
        sb.append(optionsBlock(v));
        return sb.append("</section>").toString();
    }

    // ---- Options et formulaire ------------------------------------------------------------------

    private static String optionsBlock(View v) {
        StringBuilder sb = new StringBuilder();
        if (v.pendingOptions() != null) {
            sb.append(tracker(v.pendingOptions(), "Lecture des options sur le serveur…"));
        }
        Map<String, Object> o = v.options();
        if (o == null) {
            return sb.append("<p class=\"muted\">Les options du serveur (Multiverse-Core, noms déjà pris, biomes, blocs) "
                    + "n'ont pas encore été lues.</p>").append(optionsButton(v, "Lire les options du serveur", true))
                    .toString();
        }
        sb.append("<p class=\"muted small\">Options lues sur le serveur : ").append(v.optionsAt()).append("</p>");
        if (!bool(o.get("multiverse"))) {
            sb.append(Ui.banner("error", "<strong>Création impossible</strong> : " + Http.esc(str(o.get("multiverseReason")))
                    + ". Les nouveaux mondes sont gérés par Multiverse-Core, qui les recharge à chaque démarrage."));
            return sb.append(optionsButton(v, "Relire les options", false)).toString();
        }
        sb.append(form(v, o));
        sb.append(takenNames(o));
        sb.append(optionsButton(v, "Relire les options", false));
        sb.append(history(o));
        return sb.toString();
    }

    private static String optionsButton(View v, String label, boolean primary) {
        return "<form method=\"post\" action=\"" + PATH + "/options\" class=\"btnrow mt-2\">%CSRF%" + hidden("agent", v.agentId())
                + "<button class=\"btn" + (primary ? "" : " secondary") + "\" type=\"submit\">" + Icons.icon("refresh")
                + Http.esc(label) + "</button></form>";
    }

    private static String form(View v, Map<String, Object> o) {
        Map<String, String> p = v.preview() == null ? Map.of() : v.preview().params();
        String env = p.getOrDefault("environment", "NORMAL");
        String terrain = p.getOrDefault("terrain", "NORMAL");
        StringBuilder sb = new StringBuilder("<form method=\"post\" action=\"").append(PATH)
                .append("/preview\" class=\"wc-form mt-2\">%CSRF%").append(hidden("agent", v.agentId()))
                .append(hidden("submit_token", UUID.randomUUID().toString()));
        sb.append("<p class=\"npc-fs-h\">1. Nom et dimension</p><div class=\"form-grid\">")
                .append("<label for=\"f-wc-name\">Nom du monde<input type=\"text\" id=\"f-wc-name\" name=\"name\" "
                        + "maxlength=\"64\" required autocomplete=\"off\" placeholder=\"Terres gelées\" value=\"")
                .append(Http.esc(p.getOrDefault("name", ""))).append("\"><span class=\"field-help\">L'identifiant du "
                        + "dossier en est déduit (minuscules, chiffres, _ et -) et vérifié libre sur le serveur.</span></label>")
                .append("</div><fieldset class=\"wc-choices\"><legend>Dimension</legend>")
                .append(radio("environment", "NORMAL", "Monde normal", "Ciel, jour et nuit", env))
                .append(radio("environment", "NETHER", "Nether", "Génération Nether uniquement", env))
                .append(radio("environment", "THE_END", "End", "Génération End uniquement", env))
                .append("</fieldset>");
        sb.append("<fieldset class=\"wc-choices wc-terrain\"><legend>2. Terrain</legend>")
                .append(radio("terrain", "NORMAL", "Normal", "Comme une partie solo", terrain))
                .append(radio("terrain", "FLAT", "Plat", "Couches personnalisables", terrain))
                .append(radio("terrain", "AMPLIFIED", "Amplifié", "Montagnes extrêmes", terrain))
                .append(radio("terrain", "LARGE_BIOMES", "Grands biomes", "Biomes 4× plus grands", terrain))
                .append("</fieldset><p class=\"field-help wc-terrain-off\">Nether et End n'ont qu'une génération : "
                        + "le choix du terrain ne s'applique pas.</p>");
        sb.append("<p class=\"npc-fs-h\">3. Réglages</p><div class=\"form-grid\">")
                .append("<label for=\"f-wc-seed\">Graine (facultatif)<input type=\"text\" id=\"f-wc-seed\" name=\"seed\" "
                        + "maxlength=\"32\" autocomplete=\"off\" placeholder=\"au hasard\" value=\"")
                .append(Http.esc(p.getOrDefault("seed", ""))).append("\"><span class=\"field-help\">Un nombre, ou un mot "
                        + "(transformé comme le fait Minecraft).</span></label></div>")
                .append("<div class=\"form-check mt-2\"><input class=\"form-check-input\" type=\"checkbox\" id=\"f-wc-structures\" "
                        + "name=\"structures\" value=\"true\"")
                .append("false".equals(p.get("structures")) ? "" : " checked")
                .append("><label class=\"form-check-label\" for=\"f-wc-structures\">Générer les structures (villages, "
                        + "temples…)</label></div>");
        sb.append(flatFieldset(o, p));
        sb.append(Ui.banner("warn", "La création fige le serveur quelques secondes (génération synchrone) : une seule "
                + "à la fois."));
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">").append(Icons.icon("search"))
                .append("Vérifier et voir le résumé (rien n'est créé)</button></div></form>");
        return sb.toString();
    }

    private static String flatFieldset(Map<String, Object> o, Map<String, String> p) {
        StringBuilder sb = new StringBuilder("<fieldset class=\"wc-flat mt-2\"><legend>Monde plat</legend><div class=\"form-grid\">")
                .append("<label for=\"f-wc-preset\">Préréglage<select id=\"f-wc-preset\" name=\"flat_preset\">");
        String preset = p.getOrDefault("flat_preset", "");
        List<String> help = new ArrayList<>();
        for (Map<String, Object> pr : maps(o.get("presets"))) {
            String id = str(pr.get("id"));
            sb.append("<option value=\"").append(Http.esc(id)).append("\"").append(id.equals(preset) ? " selected" : "")
                    .append(">").append(Http.esc(str(pr.get("label")))).append("</option>");
            List<String> layers = new ArrayList<>();
            maps(pr.get("layers")).forEach(l -> layers.add(str(l.get("height")) + " × "
                    + str(l.get("block")).replace("minecraft:", "")));
            help.add(str(pr.get("label")) + " : " + String.join(", ", layers) + " — " + str(pr.get("biome"))
                    .replace("minecraft:", ""));
        }
        sb.append("</select><span class=\"field-help\">").append(Http.esc(String.join(" · ", help)))
                .append("</span></label>");
        sb.append("<label for=\"f-wc-biome\">Biome<select id=\"f-wc-biome\" name=\"biome\"><option value=\"\">— celui du "
                + "préréglage —</option>");
        String biome = p.getOrDefault("biome", "");
        for (String b : strings(o.get("biomes"))) {
            sb.append("<option value=\"").append(Http.esc(b)).append("\"").append(b.equals(biome) ? " selected" : "")
                    .append(">").append(Http.esc(b.replace("minecraft:", ""))).append("</option>");
        }
        sb.append("</select></label></div>");
        sb.append("<p class=\"field-help mt-2\">Couches personnalisées, du bas vers le haut (laisser vide pour garder celles "
                + "du préréglage). Le bloc doit exister sur le serveur.</p><div class=\"wc-layers\">");
        for (int i = 1; i <= LAYER_ROWS; i++) {
            String block = p.getOrDefault("layer." + i + ".block", "");
            String height = p.getOrDefault("layer." + i + ".height", "");
            sb.append("<label for=\"f-wc-l").append(i).append("\">Couche ").append(i)
                    .append("<input type=\"text\" id=\"f-wc-l").append(i).append("\" name=\"layer.").append(i)
                    .append(".block\" list=\"wc-blocks\" maxlength=\"96\" autocomplete=\"off\" placeholder=\"minecraft:stone\" "
                            + "value=\"").append(Http.esc(block)).append("\"></label><label for=\"f-wc-h").append(i)
                    .append("\">Épaisseur<input type=\"number\" id=\"f-wc-h").append(i).append("\" name=\"layer.").append(i)
                    .append(".height\" min=\"1\" max=\"384\" step=\"1\" value=\"").append(Http.esc(height)).append("\"></label>");
        }
        sb.append("</div><datalist id=\"wc-blocks\">");
        for (String b : strings(o.get("blocks"))) {
            sb.append("<option value=\"").append(Http.esc(b)).append("\">");
        }
        return sb.append("</datalist></fieldset>").toString();
    }

    private static String takenNames(Map<String, Object> o) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(strings(o.get("loaded")));
        names.addAll(strings(o.get("multiverseKnown")));
        names.addAll(strings(o.get("folders")));
        List<String> essential = strings(o.get("essential"));
        return "<details class=\"mt-2\"><summary>Noms déjà pris sur le serveur (" + names.size() + ")</summary>"
                + "<p class=\"field-help mt-2\">Mondes chargés, connus de Multiverse-Core et dossiers présents : un nouveau "
                + "monde ne peut porter aucun de ces noms. Mondes essentiels protégés : "
                + Http.esc(String.join(", ", essential)) + ".</p><p class=\"small\">" + Http.esc(String.join(", ", names))
                + "</p></details>";
    }

    private static String history(Map<String, Object> o) {
        List<Map<String, Object>> entries = maps(o.get("history"));
        StringBuilder sb = new StringBuilder("<details class=\"mt-2\"><summary>Dernières demandes de création (")
                .append(entries.size()).append(") — journal du serveur</summary>");
        if (entries.isEmpty()) {
            return sb.append("<p class=\"muted mt-2\">Aucune création demandée depuis PlugAdmin.</p></details>").toString();
        }
        sb.append("<ul class=\"w-links mt-2\">");
        for (Map<String, Object> e : entries) {
            sb.append("<li><i class=\"bi bi-clock-history\" aria-hidden=\"true\"></i><span><strong>")
                    .append(Http.esc(str(e.get("at")).replace('T', ' ').replace("Z", " UTC"))).append("</strong> · ")
                    .append(Http.esc(str(e.get("actor")))).append(" · <code>").append(Http.esc(str(e.get("world"))))
                    .append("</code> · ").append(Http.esc(resultLabel(str(e.get("result"))))).append("<br>")
                    .append(Http.esc(str(e.get("message")))).append("</span></li>");
        }
        return sb.append("</ul></details>").toString();
    }

    // ---- Aperçu ---------------------------------------------------------------------------------

    private static String previewBlock(View v) {
        AgentActionRow row = v.preview();
        StringBuilder sb = new StringBuilder("<div class=\"actform mb-3\" id=\"w-create-preview\">")
                .append("<p class=\"npc-fs-h\">4. Résumé avant création — rien n'a été créé</p>");
        if (!row.status().terminal()) {
            return sb.append(tracker(row, "Vérification en cours sur le serveur…")).append("</div>").toString();
        }
        Map<String, Object> d = v.previewDetails();
        if (row.status() != AgentActionStatus.SUCCESS || d == null || d.get("summary") == null) {
            return sb.append(failure(row, "La demande n'a pas pu être vérifiée")).append("</div>").toString();
        }
        sb.append("<ul class=\"mb-2\">");
        strings(d.get("summary")).forEach(s -> sb.append("<li>").append(Http.esc(s)).append("</li>"));
        sb.append("</ul>");
        List<String> errors = strings(d.get("errors"));
        for (String w : strings(d.get("warnings"))) {
            sb.append(Ui.banner("warn", Http.esc(w)));
        }
        if (!errors.isEmpty()) {
            StringBuilder list = new StringBuilder("<strong>Création impossible en l'état :</strong><ul class=\"mb-0\">");
            errors.forEach(e -> list.append("<li>").append(Http.esc(e)).append("</li>"));
            sb.append(Ui.banner("error", list.append("</ul>Corriger le formulaire ci-dessous puis vérifier à nouveau.")
                    .toString()));
            return sb.append("</div>").toString();
        }
        String world = str(d.get("world"));
        sb.append("<form method=\"post\" action=\"").append(PATH).append("/apply\" class=\"mt-2\">%CSRF%")
                .append(hidden("agent", v.agentId())).append(hidden("preview", row.id()))
                .append(hidden("submit_token", UUID.randomUUID().toString()))
                .append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"f-wc-confirm\" "
                        + "name=\"confirm\" value=\"true\" required><label class=\"form-check-label\" for=\"f-wc-confirm\">")
                .append("Je confirme la création du monde <code>").append(Http.esc(world))
                .append("</code> (le serveur se fige quelques secondes).</label></div>")
                .append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">").append(Icons.icon("plus"))
                .append("Créer le monde</button><a class=\"btn secondary\" href=\"").append(href(v.agentId()))
                .append("\">Annuler (rien n'est créé)</a></div>")
                .append("<p class=\"field-help\">Le serveur revérifie tout au moment de créer : si le nom a été pris "
                        + "entre-temps, rien n'est créé. Cet aperçu ne sert qu'une fois, pendant 15 minutes.</p></form>");
        return sb.append("</div>").toString();
    }

    // ---- Résultat -------------------------------------------------------------------------------

    private static String appliedBlock(View v) {
        AgentActionRow row = v.applied();
        StringBuilder sb = new StringBuilder("<div class=\"actform mb-3\" id=\"w-create-result\">")
                .append("<p class=\"npc-fs-h\">Création et relecture</p>");
        if (!row.status().terminal()) {
            return sb.append(tracker(row, "En cours : création du monde sur le serveur…")).append("</div>").toString();
        }
        Map<String, Object> d = v.appliedDetails();
        if (d == null || d.get("result") == null) {
            return sb.append(failure(row, "Échec : la création n'a pas pu avoir lieu")).append("</div>").toString();
        }
        String result = str(d.get("result"));
        String kind = switch (result) {
            case "CREATED" -> "success";
            case "MISMATCH" -> "warn";
            default -> "error";
        };
        sb.append(Ui.banner(kind, "<strong>" + Http.esc(resultTitle(result)) + "</strong> "
                + Http.esc(str(d.get("message")))));
        List<Map<String, Object>> checks = maps(d.get("checks"));
        if (!checks.isEmpty()) {
            sb.append("<div class=\"table-wrap\"><table><thead><tr><th>Contrôle</th><th>Demandé</th><th>Relu</th>"
                    + "<th>Résultat</th></tr></thead><tbody>");
            for (Map<String, Object> c : checks) {
                sb.append("<tr><td>").append(Http.esc(str(c.get("label")))).append("</td><td><code class=\"wc-code\">")
                        .append(Http.esc(str(c.get("expected")))).append("</code></td><td><code class=\"wc-code\">")
                        .append(Http.esc(str(c.get("actual")))).append("</code></td><td>")
                        .append(bool(c.get("ok")) ? "<span class=\"pill ok\">Confirmé</span>"
                                : "<span class=\"pill warn\">Écart</span>").append("</td></tr>");
            }
            sb.append("</tbody></table></div>");
        }
        for (String note : strings(d.get("notes"))) {
            sb.append(Ui.banner("warn", Http.esc(note)));
        }
        if ("CREATED".equals(result) || "MISMATCH".equals(result)) {
            sb.append("<p class=\"mt-2\"><a class=\"btn secondary\" href=\"")
                    .append(WorldPages.ficheHref(v.agentId(), str(d.get("world"))))
                    .append("\">Ouvrir la fiche du monde</a></p>");
        }
        return sb.append("</div>").toString();
    }

    static String resultTitle(String result) {
        return switch (result) {
            case "CREATED" -> "Prêt : monde créé et relu conforme.";
            case "MISMATCH" -> "Créé, avec des écarts à la relecture.";
            case "STALE" -> "Rien n'a été créé : la demande ne correspond plus à l'aperçu.";
            case "REFUSED" -> "Rien n'a été créé : demande refusée.";
            case "BUSY" -> "Rien n'a été créé : une autre création est en cours.";
            default -> "Échec : aucun monde créé.";
        };
    }

    private static String resultLabel(String result) {
        return switch (result) {
            case "CREATED" -> "créé et relu";
            case "MISMATCH" -> "créé avec écarts";
            case "REFUSED", "STALE", "BUSY" -> "refusé, rien créé";
            default -> "échec";
        };
    }

    // ---- Briques --------------------------------------------------------------------------------

    private static String radio(String name, String value, String label, String help, String current) {
        String id = "f-wc-" + name + "-" + value.toLowerCase(java.util.Locale.ROOT);
        return "<label class=\"wc-choice\" for=\"" + id + "\"><input type=\"radio\" id=\"" + id + "\" name=\"" + name
                + "\" value=\"" + value + "\"" + (value.equals(current) ? " checked" : "") + "><span><strong>"
                + Http.esc(label) + "</strong><span class=\"field-help\">" + Http.esc(help) + "</span></span></label>";
    }

    private static String tracker(AgentActionRow row, String text) {
        return "<div class=\"alert alert-info py-2 d-flex flex-wrap align-items-center gap-2\" role=\"status\" "
                + "aria-live=\"polite\"><span class=\"spinner-border spinner-border-sm\" aria-hidden=\"true\"></span><span>"
                + "<strong>" + Http.esc(text) + "</strong> La page se met à jour toute seule.</span>"
                + "<span hidden data-pa-track-action=\"" + Http.esc(row.id()) + "\" data-pa-track-reload=\"terminal\"></span></div>";
    }

    private static String failure(AgentActionRow row, String what) {
        String why = row.resultMessage() == null || row.resultMessage().isBlank() ? "" : " : " + Http.esc(row.resultMessage());
        return Ui.banner("error", row.status() == AgentActionStatus.EXPIRED
                ? "<strong>Délai dépassé</strong> : le serveur Minecraft n'a pas répondu à temps. Rien n'a été créé."
                : "<strong>" + Http.esc(what) + "</strong>" + why + ".");
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value) + "\">";
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equals(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? l.stream().map(WorldCreatePages::map).toList() : List.of();
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(WorldCreatePages::str).toList() : List.of();
    }
}
