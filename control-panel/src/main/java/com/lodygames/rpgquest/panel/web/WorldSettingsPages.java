package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Issue #561 (#331-W2) — section « Réglages » de la fiche d'un monde : valeurs réellement relues,
 * formulaire d'aperçu (rien n'est écrit), aperçu actuel → proposé avec confirmation explicite,
 * résultat relu après application et dernières modifications journalisées par le serveur.
 *
 * <p>Chaque champ du formulaire vaut « inchangé » par défaut : seules les valeurs choisies une à une
 * partent dans l'aperçu, jamais un formulaire entier rempli implicitement. L'application reprend
 * exactement les valeurs de l'aperçu enregistré côté PlugAdmin, pas celles du navigateur.</p>
 */
public final class WorldSettingsPages {

    static final String ANCHOR = "w-settings";

    /**
     * @param reading  dernière relecture réussie de CE monde ({@code null} = jamais relu)
     * @param pending  relecture encore en attente ({@code null} = aucune)
     * @param preview  aperçu demandé ({@code ?preview=}), {@code null} sinon
     * @param applied  application demandée ({@code ?applied=}), {@code null} sinon
     * @param readAt   date de la relecture (HTML sûr, déjà échappé)
     */
    public record View(String agentId, String world, boolean loaded, boolean canWrite, AgentActionRow reading,
                       Map<String, Object> readingDetails, AgentActionRow pending, AgentActionRow preview,
                       Map<String, Object> previewDetails, AgentActionRow applied, Map<String, Object> appliedDetails,
                       String readAt) {
    }

    private WorldSettingsPages() {
    }

    public static String render(View v) {
        StringBuilder sb = new StringBuilder("<section class=\"card w-sec\" id=\"" + ANCHOR + "\"><h3 class=\"w-h\">")
                .append(Icons.icon("wrench")).append("Réglages</h3>");
        if (v.applied() != null) {
            sb.append(appliedBlock(v));
        }
        if (v.preview() != null) {
            sb.append(previewBlock(v));
        }
        sb.append(readingBlock(v));
        return sb.append("</section>").toString();
    }

    // ---- Valeurs relues -------------------------------------------------------------------------

    private static String readingBlock(View v) {
        StringBuilder sb = new StringBuilder();
        if (!v.loaded()) {
            return sb.append("<p class=\"muted\">Monde non chargé : ses réglages ne peuvent être ni relus ni modifiés. "
                    + "Le chargement d'un monde ne se fait pas depuis cette page.</p>").toString();
        }
        if (v.pending() != null) {
            sb.append(tracker(v.pending(), "Relecture des réglages en cours sur le serveur…"));
        }
        Map<String, Object> snap = v.readingDetails() == null ? null : map(v.readingDetails().get("snapshot"));
        if (snap == null || snap.isEmpty()) {
            sb.append("<p class=\"muted\">Les réglages réels de ce monde n'ont pas encore été relus sur le serveur.</p>");
            return sb.append(readButton(v, "Lire les réglages réels", true)).toString();
        }
        sb.append("<p class=\"muted small\">Relu sur le serveur : ").append(v.readAt())
                .append(bool(snap.get("multiverse")) ? " · monde géré par Multiverse-Core" : " · monde hors Multiverse-Core")
                .append(" · ").append(Http.esc(str(snap.get("players")))).append(" joueur(s) présent(s)</p>");
        for (String note : strings(snap.get("notes"))) {
            sb.append(Ui.banner("warn", Http.esc(note)));
        }
        sb.append(protectionText(strings(snap.get("roles"))));
        sb.append(valuesTable(snap));
        sb.append(rulesTable(snap));
        sb.append(readButton(v, "Relire les réglages", false));
        if (v.canWrite()) {
            sb.append(form(v, snap));
        } else {
            sb.append("<p class=\"field-help\">Modifier ces réglages demande la permission « Régler un monde » "
                    + "(WORLD_WRITE).</p>");
        }
        sb.append(history(v.readingDetails()));
        return sb.toString();
    }

    private static String protectionText(List<String> roles) {
        if (roles.isEmpty()) {
            return "";
        }
        List<String> rules = new ArrayList<>();
        if (roles.contains("HUB")) {
            rules.add("Hub : jour et météo permanents, aucun dégât subi par un joueur, aucun monstre hostile — heure, "
                    + "météo, PvP activé et monstres autorisés sont refusés.");
        }
        if (roles.contains("CLAIMS")) {
            rules.add("Claims : aucun dégât subi par un joueur, aucun monstre hostile — PvP activé et monstres autorisés "
                    + "sont refusés.");
        }
        if (roles.contains("WILD")) {
            rules.add("Wild : la difficulté Paisible est refusée (elle supprimerait mobs spéciaux et boss).");
        }
        StringBuilder sb = new StringBuilder("<div class=\"w-note\"><strong>Protections LodyQuests prioritaires</strong>"
                + "<ul class=\"mb-0\">");
        rules.forEach(r -> sb.append("<li>").append(Http.esc(r)).append("</li>"));
        return sb.append("</ul></div>").toString();
    }

    private static String valuesTable(Map<String, Object> snap) {
        Map<String, Object> values = map(snap.get("values"));
        Map<String, Object> writers = map(snap.get("writers"));
        Map<String, Object> unavailable = map(snap.get("unavailable"));
        StringBuilder sb = new StringBuilder("<div class=\"table-wrap mt-2\"><table><thead><tr><th>Réglage</th>"
                + "<th>Valeur réelle</th><th>Écrit par</th></tr></thead><tbody>");
        for (String[] s : SETTINGS) {
            String key = s[0];
            sb.append("<tr><td>").append(Http.esc(s[1])).append("</td>");
            if (values.containsKey(key)) {
                sb.append("<td><strong>").append(Http.esc(valueLabel(key, str(values.get(key))))).append("</strong></td><td>")
                        .append(Http.esc(writerLabel(str(writers.get(key))))).append("</td>");
            } else {
                sb.append("<td colspan=\"2\" class=\"muted\">").append(Http.esc(str(unavailable.getOrDefault(key,
                        "indisponible")))).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String rulesTable(Map<String, Object> snap) {
        List<Map<String, Object>> rules = maps(snap.get("rules"));
        StringBuilder sb = new StringBuilder("<details class=\"mt-2\"><summary>Règles de jeu du monde (")
                .append(rules.size()).append(", lues sur le serveur)</summary><div class=\"table-wrap mt-2\"><table>"
                        + "<thead><tr><th>Règle</th><th>Valeur</th></tr></thead><tbody>");
        for (Map<String, Object> r : rules) {
            sb.append("<tr><td><code>").append(Http.esc(str(r.get("name")))).append("</code>");
            if (r.get("dedicatedTo") != null) {
                sb.append(" <span class=\"muted small\">— réglée par « ").append(Http.esc(label(str(r.get("dedicatedTo")))))
                        .append(" »</span>");
            }
            sb.append("</td><td>").append(Http.esc(boolLabel(str(r.get("value"))))).append("</td></tr>");
        }
        return sb.append("</tbody></table></div></details>").toString();
    }

    private static String readButton(View v, String label, boolean primary) {
        return "<form method=\"post\" action=\"/worlds/settings/read\" class=\"d-inline\">%CSRF%"
                + hidden("agent", v.agentId()) + hidden("world", v.world())
                + "<button class=\"btn sm " + (primary ? "" : "secondary") + "\" type=\"submit\">" + Icons.icon("refresh")
                + Http.esc(label) + "</button></form>";
    }

    // ---- Formulaire d'aperçu --------------------------------------------------------------------

    private static String form(View v, Map<String, Object> snap) {
        Map<String, Object> values = map(snap.get("values"));
        Map<String, Object> unavailable = map(snap.get("unavailable"));
        StringBuilder sb = new StringBuilder("<details class=\"mt-3\"")
                .append(v.preview() == null ? "" : " open").append("><summary><strong>Modifier des réglages</strong> — aperçu d'abord, rien n'est écrit sans "
                        + "confirmation</summary>");
        sb.append("<form method=\"post\" action=\"/worlds/settings/preview\" class=\"actform mt-2\">%CSRF%")
                .append(hidden("agent", v.agentId())).append(hidden("world", v.world()))
                .append(hidden("submit_token", UUID.randomUUID().toString()))
                .append("<p class=\"field-help\">Chaque champ reste « inchangé » tant qu'il n'est pas choisi : seules les "
                        + "valeurs choisies partent dans l'aperçu.</p><div class=\"form-grid\">");
        for (String[] s : SETTINGS) {
            String key = s[0];
            if (!values.containsKey(key)) {
                continue;
            }
            String current = str(values.get(key));
            String name = "set." + key;
            String id = "f-ws-" + key;
            sb.append("<label for=\"").append(id).append("\">").append(Http.esc(s[1]));
            switch (key) {
                case "difficulty" -> sb.append(select(id, name, current, new String[][]{{"PEACEFUL", "Paisible"},
                        {"EASY", "Facile"}, {"NORMAL", "Normale"}, {"HARD", "Difficile"}}));
                case "gamemode" -> sb.append(select(id, name, current, new String[][]{{"SURVIVAL", "Survie"},
                        {"ADVENTURE", "Aventure"}, {"CREATIVE", "Créatif"}, {"SPECTATOR", "Spectateur"}}));
                case "weather" -> sb.append(select(id, name, current, new String[][]{{"CLEAR", "Dégagée"},
                        {"RAIN", "Pluie"}, {"THUNDER", "Orage"}}));
                case "time" -> sb.append(select(id, name, current, new String[][]{{"0", "Lever du soleil (06h00)"},
                        {"1000", "Matin (07h00)"}, {"6000", "Midi (12h00)"}, {"12000", "Coucher (18h00)"},
                        {"18000", "Minuit (00h00)"}}));
                case "pvp", "monsters", "animals", "advance_time", "advance_weather" ->
                        sb.append(select(id, name, current, new String[][]{{"true", "Oui"}, {"false", "Non"}}));
                case "border_size" -> sb.append("<input type=\"number\" id=\"").append(id).append("\" name=\"")
                        .append(name).append("\" min=\"1\" max=\"59999968\" step=\"1\" placeholder=\"inchangé (")
                        .append(Http.esc(current)).append(")\"><span class=\"field-help\">59999968 = pas de bordure.</span>");
                case "border_center" -> sb.append(text(id, name, "x, z — inchangé (" + current + ")"));
                case "alias" -> sb.append(text(id, name, "inchangé (" + valueLabel(key, current) + ")"))
                        .append("<span class=\"field-help\">Nom montré dans PlugAdmin (PNJ, Mondes, Portails, Voyages) ; "
                                + "l'identifiant <code>").append(Http.esc(v.world())).append("</code> ne change pas. "
                                + "« - » revient au nom par défaut (Hub, Claims, Wild…). 32 caractères au plus, "
                                + "sans &lt; &gt; &amp; ni §.</span>");
                case "spawn" -> sb.append(text(id, name, "x, y, z, orientation — inchangé (" + spawnLabel(current) + ")"))
                        .append("<span class=\"field-help\">Ou « prendre la position » d'un joueur présent dans ce monde "
                                + "ci-dessous.</span>");
                default -> sb.append(text(id, name, "inchangé"));
            }
            if (!"border_size".equals(key) && !"spawn".equals(key) && !"alias".equals(key)) {
                sb.append("<span class=\"field-help\">Actuel : ").append(Http.esc(valueLabel(key, current))).append("</span>");
            }
            sb.append("</label>");
        }
        if (values.containsKey("spawn")) {
            sb.append("<label for=\"f-ws-spawn-player\">Prendre la position du joueur").append(text("f-ws-spawn-player",
                    "spawn_player", "pseudo Minecraft (présent dans ce monde)"))
                    .append("<span class=\"field-help\">Sa position est relevée par l'aperçu, puis appliquée telle quelle "
                            + "après confirmation.</span></label>");
        }
        sb.append("</div>");
        if (!unavailable.isEmpty()) {
            sb.append("<p class=\"field-help\">Non proposés ici : ");
            List<String> parts = new ArrayList<>();
            unavailable.forEach((k, why) -> parts.add(label(k) + " (" + str(why) + ")"));
            sb.append(Http.esc(String.join(" ; ", parts))).append("</p>");
        }
        sb.append(rulesForm(snap));
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">").append(Icons.icon("search"))
                .append("Prévisualiser (rien n'est écrit)</button></div></form></details>");
        return sb.toString();
    }

    private static String rulesForm(Map<String, Object> snap) {
        List<Map<String, Object>> rules = maps(snap.get("rules")).stream()
                .filter(r -> r.get("dedicatedTo") == null).toList();
        if (rules.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details class=\"w-adv mt-2\"><summary>Règles de jeu avancées (")
                .append(rules.size()).append(") — liste lue sur le serveur</summary><div class=\"form-grid mt-2\">");
        for (Map<String, Object> r : rules) {
            String rule = str(r.get("name"));
            String id = "f-ws-rule-" + rule;
            String name = "set.rule." + rule;
            String current = str(r.get("value"));
            sb.append("<label for=\"").append(Http.esc(id)).append("\"><code>").append(Http.esc(rule)).append("</code>");
            if ("BOOLEAN".equals(str(r.get("type")))) {
                sb.append(select(id, name, current, new String[][]{{"true", "Oui"}, {"false", "Non"}}));
            } else {
                sb.append("<input type=\"number\" step=\"1\" id=\"").append(Http.esc(id)).append("\" name=\"")
                        .append(Http.esc(name)).append("\" placeholder=\"inchangé (").append(Http.esc(current)).append(")\">");
            }
            sb.append("</label>");
        }
        return sb.append("</div></details>").toString();
    }

    // ---- Aperçu ---------------------------------------------------------------------------------

    private static String previewBlock(View v) {
        AgentActionRow row = v.preview();
        StringBuilder sb = new StringBuilder("<div class=\"actform mb-3\" id=\"w-settings-preview\">")
                .append("<p class=\"npc-fs-h\">Aperçu — rien n'a été écrit</p>");
        if (!row.status().terminal()) {
            return sb.append(tracker(row, "Aperçu en cours sur le serveur…")).append("</div>").toString();
        }
        Map<String, Object> d = v.previewDetails();
        if (row.status() != AgentActionStatus.SUCCESS || d == null || d.get("changes") == null) {
            sb.append(failure(row, "L'aperçu n'a pas pu être calculé"));
            return sb.append("</div>").toString();
        }
        List<Map<String, Object>> changes = maps(d.get("changes"));
        sb.append("<div class=\"table-wrap\"><table><thead><tr><th>Réglage</th><th>Valeur actuelle</th>"
                + "<th>Valeur proposée</th><th>Écrit par</th><th>Effet attendu</th><th>Verdict</th></tr></thead><tbody>");
        for (Map<String, Object> c : changes) {
            String key = str(c.get("key"));
            String status = str(c.get("status"));
            sb.append("<tr><td>").append(Http.esc(str(c.get("label")))).append("</td><td>")
                    .append(Http.esc(c.get("current") == null ? "inconnue" : valueLabel(key, str(c.get("current")))))
                    .append("</td><td><strong>")
                    .append(Http.esc(c.get("proposed") == null ? "—" : valueLabel(key, str(c.get("proposed")))))
                    .append("</strong></td><td>").append(Http.esc(writerLabel(str(c.get("writer"))))).append("</td><td>")
                    .append(Http.esc("REFUSED".equals(status) ? str(c.get("reason")) : str(c.get("effect"))))
                    .append("</td><td>").append(verdictPill(status)).append("</td></tr>");
        }
        sb.append("</tbody></table></div>");
        for (String w : strings(d.get("warnings"))) {
            sb.append(Ui.banner("warn", Http.esc(w)));
        }
        long applicable = changes.stream().filter(c -> "APPLY".equals(str(c.get("status")))).count();
        long refused = changes.stream().filter(c -> "REFUSED".equals(str(c.get("status")))).count();
        if (refused > 0) {
            sb.append(Ui.banner("error", "<strong>" + refused + " réglage(s) refusé(s).</strong> Rien ne peut être "
                    + "appliqué tant que la demande contient un refus : corriger ou retirer ces valeurs, puis "
                    + "prévisualiser à nouveau."));
        } else if (applicable == 0) {
            sb.append("<p class=\"muted\">Rien à appliquer : toutes les valeurs demandées sont déjà en place.</p>");
        } else if (v.canWrite()) {
            sb.append("<form method=\"post\" action=\"/worlds/settings/apply\" class=\"mt-2\">%CSRF%")
                    .append(hidden("agent", v.agentId())).append(hidden("world", v.world()))
                    .append(hidden("preview", row.id())).append(hidden("submit_token", UUID.randomUUID().toString()))
                    .append("<div class=\"form-check\"><input class=\"form-check-input\" type=\"checkbox\" id=\"f-ws-confirm\" "
                            + "name=\"confirm\" value=\"true\" required><label class=\"form-check-label\" for=\"f-ws-confirm\">")
                    .append("Je confirme appliquer ces <strong>").append(applicable).append(" réglage(s)</strong> au monde <code>")
                    .append(Http.esc(v.world())).append("</code>, et à lui seul.</label></div>")
                    .append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">").append(Icons.icon("save"))
                    .append("Appliquer et relire</button><a class=\"btn secondary\" href=\"")
                    .append(WorldPages.ficheHref(v.agentId(), v.world())).append("#").append(ANCHOR)
                    .append("\">Annuler (rien n'est écrit)</a></div>")
                    .append("<p class=\"field-help\">Le serveur refuse d'écrire si les valeurs ont changé depuis cet aperçu ; "
                            + "il relit ensuite chaque réglage et affiche l'écart éventuel.</p></form>");
        }
        return sb.append("</div>").toString();
    }

    // ---- Résultat -------------------------------------------------------------------------------

    private static String appliedBlock(View v) {
        AgentActionRow row = v.applied();
        StringBuilder sb = new StringBuilder("<div class=\"actform mb-3\" id=\"w-settings-result\">")
                .append("<p class=\"npc-fs-h\">Application et relecture</p>");
        if (!row.status().terminal()) {
            return sb.append(tracker(row, "Application en cours sur le serveur…")).append("</div>").toString();
        }
        Map<String, Object> d = v.appliedDetails();
        if (d == null || d.get("result") == null) {
            return sb.append(failure(row, "L'application n'a pas pu avoir lieu")).append("</div>").toString();
        }
        String result = str(d.get("result"));
        String kind = switch (result) {
            case "APPLIED" -> "success";
            case "PARTIAL" -> "warn";
            default -> "error";
        };
        String title = switch (result) {
            case "APPLIED" -> "Appliqué et confirmé par relecture.";
            case "PARTIAL" -> "Appliqué en partie : voir les écarts.";
            case "STALE" -> "Rien n'a été écrit : les valeurs ont changé depuis l'aperçu.";
            case "REFUSED" -> "Rien n'a été écrit : demande refusée.";
            case "NOT_LOADED" -> "Rien n'a été écrit : monde non chargé.";
            default -> "Aucun réglage confirmé par relecture.";
        };
        sb.append(Ui.banner(kind, "<strong>" + Http.esc(title) + "</strong> " + Http.esc(str(d.get("message")))));
        List<Map<String, Object>> rows = maps(d.get("applied"));
        if (!rows.isEmpty()) {
            sb.append("<div class=\"table-wrap\"><table><thead><tr><th>Réglage</th><th>Avant</th><th>Demandé</th>"
                    + "<th>Relu après</th><th>Résultat</th></tr></thead><tbody>");
            for (Map<String, Object> r : rows) {
                String key = str(r.get("key"));
                sb.append("<tr><td>").append(Http.esc(str(r.get("label")))).append("</td><td>")
                        .append(Http.esc(valueLabel(key, str(r.get("before"))))).append("</td><td>")
                        .append(Http.esc(valueLabel(key, str(r.get("requested"))))).append("</td><td><strong>")
                        .append(Http.esc(r.get("after") == null ? "inconnu" : valueLabel(key, str(r.get("after")))))
                        .append("</strong></td><td>").append(outcomePill(str(r.get("outcome")))).append(" <span class=\"muted small\">")
                        .append(Http.esc(str(r.get("detail")))).append("</span></td></tr>");
            }
            sb.append("</tbody></table></div>");
        }
        for (String note : strings(d.get("notes"))) {
            sb.append(Ui.banner("warn", Http.esc(note)));
        }
        return sb.append("</div>").toString();
    }

    private static String history(Map<String, Object> reading) {
        List<Map<String, Object>> entries = reading == null ? List.of() : maps(reading.get("history"));
        StringBuilder sb = new StringBuilder("<details class=\"mt-3\"><summary>Dernières modifications (")
                .append(entries.size()).append(") — journal du serveur</summary>");
        if (entries.isEmpty()) {
            return sb.append("<p class=\"muted mt-2\">Aucun réglage appliqué depuis PlugAdmin sur ce monde.</p></details>")
                    .toString();
        }
        sb.append("<ul class=\"w-links mt-2\">");
        for (Map<String, Object> e : entries) {
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> l : maps(e.get("changes"))) {
                String key = str(l.get("key"));
                parts.add(label(key) + " : " + valueLabel(key, str(l.get("before"))) + " → "
                        + valueLabel(key, str(l.get("after"))) + ("OK".equals(str(l.get("outcome"))) ? ""
                        : " (" + outcomeLabel(str(l.get("outcome"))) + ")"));
            }
            sb.append("<li><i class=\"bi bi-clock-history\" aria-hidden=\"true\"></i><span><strong>")
                    .append(Http.esc(str(e.get("at")).replace('T', ' ').replace("Z", " UTC"))).append("</strong> · ")
                    .append(Http.esc(str(e.get("actor")))).append(" · ").append(Http.esc(resultLabel(str(e.get("result")))))
                    .append("<br>").append(Http.esc(String.join(" ; ", parts))).append("</span></li>");
        }
        return sb.append("</ul></details>").toString();
    }

    // ---- Libellés -------------------------------------------------------------------------------

    /** Ordre et libellés de la fiche, miroir de {@code WorldSetting} côté plugin. */
    static final String[][] SETTINGS = {
            {"alias", "Nom affiché"}, {"difficulty", "Difficulté"}, {"pvp", "PvP (combat entre joueurs)"},
            {"monsters", "Apparition des monstres"}, {"animals", "Apparition des animaux"},
            {"gamemode", "Mode de jeu à l'arrivée"}, {"advance_time", "Jour et nuit qui avancent"},
            {"time", "Heure du monde"}, {"advance_weather", "Météo qui change"}, {"weather", "Météo actuelle"},
            {"border_center", "Centre de la bordure"}, {"border_size", "Diamètre de la bordure"},
            {"spawn", "Point d'arrivée"}};

    static String label(String key) {
        if (key.startsWith("rule.")) {
            return "règle " + key.substring(5);
        }
        for (String[] s : SETTINGS) {
            if (s[0].equals(key)) {
                return s[1];
            }
        }
        return key;
    }

    static String valueLabel(String key, String value) {
        if (value == null || value.isEmpty()) {
            return "—";
        }
        return switch (key) {
            case "alias" -> "-".equals(value) ? "aucun — nom par défaut" : value;
            case "difficulty" -> switch (value) {
                case "PEACEFUL" -> "Paisible";
                case "EASY" -> "Facile";
                case "NORMAL" -> "Normale";
                case "HARD" -> "Difficile";
                default -> value;
            };
            case "gamemode" -> switch (value) {
                case "SURVIVAL" -> "Survie";
                case "ADVENTURE" -> "Aventure";
                case "CREATIVE" -> "Créatif";
                case "SPECTATOR" -> "Spectateur";
                default -> value;
            };
            case "weather" -> switch (value) {
                case "CLEAR" -> "Dégagée";
                case "RAIN" -> "Pluie";
                case "THUNDER" -> "Orage";
                default -> value;
            };
            case "time" -> value.matches("\\d{1,5}") ? value + " (" + timeLabel(Integer.parseInt(value)) + ")" : value;
            case "border_size" -> "59999968".equals(value) ? "aucune bordure (59999968)" : value + " blocs";
            case "border_center" -> value.replace(",", ", ");
            case "spawn" -> spawnLabel(value);
            default -> boolLabel(value);
        };
    }

    static String timeLabel(int ticks) {
        int hours = (ticks / 1000 + 6) % 24;
        int minutes = (ticks % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02dh%02d", hours, minutes);
    }

    private static String spawnLabel(String value) {
        String[] p = value.split(",");
        return p.length == 4 ? p[0] + ", " + p[1] + ", " + p[2] + " · orientation " + p[3] + "°" : value;
    }

    private static String boolLabel(String value) {
        return switch (value) {
            case "true" -> "oui";
            case "false" -> "non";
            default -> value;
        };
    }

    private static String writerLabel(String writer) {
        return switch (writer) {
            case "MULTIVERSE" -> "Multiverse-Core";
            case "BUKKIT" -> "serveur";
            case "GAMERULE" -> "règle de jeu";
            case "RPGQUEST" -> "RPGQuest (world-names.yml)";
            default -> "—";
        };
    }

    private static String verdictPill(String status) {
        return switch (status) {
            case "APPLY" -> "<span class=\"pill ok\">À appliquer</span>";
            case "UNCHANGED" -> "<span class=\"pill pill--neutral\">Inchangé</span>";
            default -> "<span class=\"pill err\">Refusé</span>";
        };
    }

    private static String outcomePill(String outcome) {
        return switch (outcome) {
            case "OK" -> "<span class=\"pill ok\">Confirmé</span>";
            case "MISMATCH" -> "<span class=\"pill warn\">Écart</span>";
            case "NOT_ATTEMPTED" -> "<span class=\"pill pill--neutral\">Non tenté</span>";
            default -> "<span class=\"pill err\">Échec</span>";
        };
    }

    private static String outcomeLabel(String outcome) {
        return switch (outcome) {
            case "MISMATCH" -> "écart à la relecture";
            case "NOT_ATTEMPTED" -> "non tenté";
            case "FAILED" -> "échec";
            default -> outcome;
        };
    }

    private static String resultLabel(String result) {
        return switch (result) {
            case "APPLIED" -> "appliqué et relu";
            case "PARTIAL" -> "appliqué en partie";
            default -> "échec";
        };
    }

    // ---- Briques --------------------------------------------------------------------------------

    private static String tracker(AgentActionRow row, String text) {
        return "<div class=\"alert alert-info py-2 d-flex flex-wrap align-items-center gap-2\" role=\"status\" "
                + "aria-live=\"polite\"><span class=\"spinner-border spinner-border-sm\" aria-hidden=\"true\"></span><span>"
                + "<strong>" + Http.esc(text) + "</strong> La page se met à jour toute seule.</span>"
                + "<span hidden data-pa-track-action=\"" + Http.esc(row.id()) + "\" data-pa-track-reload=\"terminal\"></span></div>";
    }

    private static String failure(AgentActionRow row, String what) {
        String why = row.resultMessage() == null || row.resultMessage().isBlank() ? "" : " : " + Http.esc(row.resultMessage());
        return Ui.banner("error", row.status() == AgentActionStatus.EXPIRED
                ? "<strong>Délai dépassé</strong> : le serveur Minecraft n'a pas répondu à temps. Rien n'a été écrit."
                : "<strong>" + Http.esc(what) + "</strong>" + why + ".");
    }

    private static String select(String id, String name, String current, String[][] options) {
        StringBuilder sb = new StringBuilder("<select id=\"").append(Http.esc(id)).append("\" name=\"").append(Http.esc(name))
                .append("\"><option value=\"\" selected>— inchangé —</option>");
        for (String[] o : options) {
            sb.append("<option value=\"").append(Http.esc(o[0])).append("\">").append(Http.esc(o[1]))
                    .append(o[0].equals(current) ? " (actuel)" : "").append("</option>");
        }
        return sb.append("</select>").toString();
    }

    private static String text(String id, String name, String placeholder) {
        return "<input type=\"text\" id=\"" + Http.esc(id) + "\" name=\"" + Http.esc(name) + "\" maxlength=\"64\" "
                + "autocomplete=\"off\" placeholder=\"" + Http.esc(placeholder) + "\">";
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
        return o instanceof List<?> l ? l.stream().map(WorldSettingsPages::map).toList() : List.of();
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(WorldSettingsPages::str).toList() : List.of();
    }
}
