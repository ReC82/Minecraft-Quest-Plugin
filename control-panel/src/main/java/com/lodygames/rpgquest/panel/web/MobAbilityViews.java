package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Issue #543 — bibliothèque « Capacités » et cartes de capacités du profil de mob.
 *
 * <p>Tout ce qui est affiché vient du catalogue publié par le plugin ({@code mob.ability.catalog},
 * repris dans {@code mob.list} sous {@code abilityCatalog}) : libellés, réglages, bornes, aides,
 * préréglages. Aucune capacité n'est codée ici ; une capacité ajoutée au moteur apparaît sans
 * changement du panel. Le plugin revalide tout à l'enregistrement.</p>
 *
 * <p>Champs du formulaire (lus par {@code AgentActionCatalog}) : {@code ab_form=1} (liste structurée
 * envoyée), puis pour chaque carte existante {@code ab_<i>_type}, {@code ab_<i>_enabled},
 * {@code ab_<i>_remove}, {@code ab_<i>_pos}, {@code ab_<i>_<clé YAML>} ; pour une capacité cochée dans
 * « Ajouter une capacité » : {@code ab_new_<TYPE>=true} et {@code ab_new_<TYPE>_<clé YAML>}.</p>
 */
final class MobAbilityViews {

    static final String PATH = "/mobs/abilities";

    private MobAbilityViews() {
    }

    /** Catalogue publié par le plugin. */
    record Catalog(int maxPerProfile, List<Map<String, Object>> types) {

        Optional<Map<String, Object>> type(String id) {
            return types.stream().filter(t -> str(t.get("type")).equalsIgnoreCase(id)).findFirst();
        }
    }

    /** Lit le bloc catalogue ({@code {"maxPerProfile":…, "types":[…]}}), absent si le plugin est antérieur à #543. */
    static Optional<Catalog> catalog(Map<String, Object> block) {
        if (block == null || !(block.get("types") instanceof List<?> raw) || raw.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> types = raw.stream().map(MobAbilityViews::asMap).toList();
        int max = block.get("maxPerProfile") instanceof Number n ? n.intValue() : 5;
        return Optional.of(new Catalog(max, types));
    }

    // ---- Libellés ------------------------------------------------------------------------------

    static String categoryLabel(String category) {
        return switch (category) {
            case "EXPLOSION" -> "Explosion";
            case "REACTION" -> "Réaction aux coups";
            case "ENHANCEMENT" -> "Renforcement";
            case "SUMMON" -> "Invocation";
            default -> category;
        };
    }

    static String categoryIcon(String category) {
        return "ability-" + category.toLowerCase(Locale.ROOT);
    }

    static String statusPill(String status) {
        return switch (status) {
            case "AVAILABLE" -> Ui.pill("Disponible", "success", "✓");
            case "IN_DEVELOPMENT" -> Ui.pill("En développement", "pending", "…");
            default -> Ui.pill("Prévue", "neutral", "");
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "AVAILABLE" -> "Disponible";
            case "IN_DEVELOPMENT" -> "En développement";
            default -> "Prévue";
        };
    }

    private static String kindLabel(String kind) {
        return "ACTION".equals(kind) ? "Action (une seule à la fois par mob)" : "Passive (se cumule)";
    }

    private static String triggerLabel(String trigger) {
        return switch (trigger) {
            case "DAMAGED" -> "quand elle subit des dégâts";
            case "HIT_PLAYER" -> "quand elle frappe un joueur";
            case "EXPLOSION_PRIME" -> "quand elle va exploser";
            case "TICK" -> "vérifiée chaque seconde";
            case "DEATH" -> "à sa mort";
            default -> trigger.toLowerCase(Locale.ROOT);
        };
    }

    private static String triggers(Map<String, Object> type) {
        List<String> out = new ArrayList<>();
        asList(type.get("triggers")).forEach(t -> out.add(triggerLabel(str(t))));
        return out.isEmpty() ? "—" : String.join(", ", out);
    }

    private static String effectiveOn(Map<String, Object> type) {
        List<Object> on = asList(type.get("effectiveOn"));
        return on.isEmpty() ? "toutes les créatures" : String.join(", ", on.stream().map(MobAbilityViews::str).toList());
    }

    /** Vrai si la capacité a un effet sur ce type d'entité (liste vide = toutes). */
    static boolean effectiveFor(Map<String, Object> type, String entityType) {
        List<Object> on = asList(type.get("effectiveOn"));
        return on.isEmpty() || entityType == null || entityType.isBlank()
                || on.stream().anyMatch(e -> str(e).equalsIgnoreCase(entityType));
    }

    private static String valueTypeLabel(String valueType) {
        return switch (valueType) {
            case "DECIMAL" -> "nombre";
            case "INT" -> "entier";
            case "BOOL" -> "oui / non";
            case "ENTITY_TYPE" -> "créature vivante";
            default -> valueType;
        };
    }

    private static String defaultText(Map<String, Object> param) {
        if (Boolean.TRUE.equals(param.get("required"))) {
            return "aucun — obligatoire";
        }
        Object def = param.get("defaultValue");
        if (def instanceof Boolean b) {
            return b ? "oui" : "non";
        }
        return def == null ? "aucun" : number(def);
    }

    private static List<Map<String, Object>> params(Map<String, Object> type) {
        return asList(type.get("params")).stream().map(MobAbilityViews::asMap)
                .filter(p -> !"enabled".equals(str(p.get("key"))))
                .toList();
    }

    // ---- Résumé d'une capacité configurée ------------------------------------------------------

    /** Phrase-résumé d'une capacité d'un profil, dérivée du descripteur : réglages principaux non vides. */
    static String instanceSummary(Map<String, Object> type, Map<String, Object> instance) {
        Map<String, Object> values = asMap(instance.get("values"));
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> p : params(type)) {
            Object v = values.get(str(p.get("key")));
            if (v == null) {
                continue;
            }
            boolean common = Boolean.TRUE.equals(p.get("common"));
            // Champ commun à sa valeur par défaut : rien à dire.
            if (common && number(v).equals(number(p.get("defaultValue")))) {
                continue;
            }
            parts.add(str(p.get("label")) + " " + (v instanceof Boolean b ? (b ? "oui" : "non") : number(v)));
        }
        return parts.isEmpty() ? str(type.get("summary")) : String.join(" · ", parts);
    }

    // ---- Page catalogue ------------------------------------------------------------------------

    /** Grille des capacités, filtrée par catégorie, état et créature (paramètres GET). */
    static String catalogueBody(String agentId, Catalog catalog, List<Map<String, Object>> profiles,
                                Map<String, String> q) {
        String cat = str(q.get("cat")).toUpperCase(Locale.ROOT);
        String status = str(q.get("status")).toUpperCase(Locale.ROOT);
        String entity = str(q.get("entity")).toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        StringBuilder sb = new StringBuilder();
        sb.append("<form method=\"get\" action=\"").append(PATH).append("\" class=\"cap-filters\">")
                .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agentId)).append("\">");
        sb.append("<label class=\"visually-hidden\" for=\"cap-f-cat\">Catégorie</label>")
                .append("<select id=\"cap-f-cat\" name=\"cat\"><option value=\"\">Toutes les catégories</option>");
        List<String> categories = catalog.types().stream().map(t -> str(t.get("category"))).distinct().toList();
        for (String c : categories) {
            sb.append(option(c, cat, categoryLabel(c)));
        }
        sb.append("</select>");
        sb.append("<label class=\"visually-hidden\" for=\"cap-f-st\">État</label>")
                .append("<select id=\"cap-f-st\" name=\"status\"><option value=\"\">Tous les états</option>")
                .append(option("AVAILABLE", status, "Disponibles"))
                .append(option("IN_DEVELOPMENT", status, "En développement"))
                .append(option("PLANNED", status, "Prévues")).append("</select>");
        sb.append("<label class=\"visually-hidden\" for=\"cap-f-en\">Créature</label>")
                .append("<input id=\"cap-f-en\" type=\"text\" name=\"entity\" list=\"dl-mob-entity\" value=\"")
                .append(Http.esc(entity)).append("\" placeholder=\"Compatible avec (ex. ZOMBIE)\" autocomplete=\"off\">");
        sb.append("<button class=\"btn secondary sm\" type=\"submit\">").append(Icons.icon("filter")).append("Filtrer</button>");
        if (!cat.isEmpty() || !status.isEmpty() || !entity.isEmpty()) {
            sb.append("<a class=\"btn secondary sm\" href=\"").append(PATH).append("?agent=").append(Http.esc(agentId))
                    .append("\">Tout afficher</a>");
        }
        sb.append("</form>");

        List<Map<String, Object>> shown = catalog.types().stream()
                .filter(t -> cat.isEmpty() || cat.equals(str(t.get("category"))))
                .filter(t -> status.isEmpty() || status.equals(str(t.get("status"))))
                .filter(t -> entity.isEmpty() || effectiveFor(t, entity))
                .toList();
        sb.append("<p class=\"count-note\">").append(shown.size()).append(" capacité(s) sur ")
                .append(catalog.types().size()).append(" · ").append(catalog.maxPerProfile())
                .append(" au plus par profil, une seule de chaque type.</p>");
        if (shown.isEmpty()) {
            return sb.append(Ui.empty("ability", "Aucune capacité ne correspond à ces filtres.")).toString();
        }
        sb.append("<div class=\"cap-grid\">");
        for (Map<String, Object> t : shown) {
            sb.append(card(agentId, t, usages(t, profiles)));
        }
        return sb.append("</div>").toString();
    }

    private static String card(String agentId, Map<String, Object> t, List<String> usedBy) {
        String type = str(t.get("type"));
        String category = str(t.get("category"));
        StringBuilder sb = new StringBuilder("<article class=\"cap-card")
                .append("PLANNED".equals(str(t.get("status"))) ? " planned" : "").append("\">");
        sb.append("<div class=\"cap-head\"><span class=\"cap-ic\">").append(Icons.icon(categoryIcon(category)))
                .append("</span><div><div class=\"cap-name\">").append(Http.esc(str(t.get("label"))))
                .append("</div><div class=\"cap-cat\">").append(Http.esc(categoryLabel(category)))
                .append("</div></div></div>");
        sb.append("<p class=\"cap-desc\">").append(Http.esc(str(t.get("summary")))).append("</p>");
        sb.append("<ul class=\"cap-facts\">")
                .append(fact("Type", "ACTION".equals(str(t.get("kind"))) ? "Action" : "Passive"))
                .append(fact("Déclenchée", triggers(t)))
                .append(fact("Effet sur", effectiveOn(t)))
                .append(fact("Profils", usedBy.isEmpty() ? "aucun" : usedBy.size() + " — " + String.join(", ", usedBy)))
                .append("</ul>");
        sb.append("<div class=\"cap-foot\">").append(statusPill(str(t.get("status"))))
                .append("<a class=\"btn secondary sm\" href=\"").append(PATH).append("?agent=").append(Http.esc(agentId))
                .append("&amp;type=").append(Http.esc(type)).append("\">Voir la fiche</a></div>");
        return sb.append("</article>").toString();
    }

    private static String fact(String k, String v) {
        return "<li><span class=\"k\">" + Http.esc(k) + "</span><span>" + Http.esc(v) + "</span></li>";
    }

    /** Noms des profils qui utilisent ce type (« (désactivée) » si l'instance est désactivée). */
    static List<String> usages(Map<String, Object> type, List<Map<String, Object>> profiles) {
        List<String> out = new ArrayList<>();
        String id = str(type.get("type"));
        for (Map<String, Object> p : profiles) {
            for (Object o : asList(p.get("abilities"))) {
                Map<String, Object> a = asMap(o);
                if (id.equals(str(a.get("type")))) {
                    out.add(shortId(str(p.get("id"))) + (Boolean.FALSE.equals(a.get("enabled")) ? " (désactivée)" : ""));
                    break;
                }
            }
        }
        return out;
    }

    // ---- Fiche d'une capacité ------------------------------------------------------------------

    static String detailBody(String agentId, Map<String, Object> t, List<Map<String, Object>> profiles) {
        String category = str(t.get("category"));
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"crumbs\"><a href=\"").append(PATH).append("?agent=").append(Http.esc(agentId))
                .append("\">Capacités</a> › ").append(Http.esc(str(t.get("label")))).append("</p>");
        sb.append("<div class=\"cap-hero\"><span class=\"cap-ic\">").append(Icons.icon(categoryIcon(category)))
                .append("</span><div><div class=\"cap-name\">").append(Http.esc(str(t.get("label"))))
                .append(" <code>").append(Http.esc(str(t.get("type")))).append("</code></div>")
                .append("<p class=\"cap-desc\">").append(Http.esc(str(t.get("summary")))).append("</p>")
                .append("<div class=\"presets\">").append(statusPill(str(t.get("status"))))
                .append(Ui.badge(categoryLabel(category))).append(Ui.badge(kindLabel(str(t.get("kind")))))
                .append("</div></div></div>");

        sb.append("<div class=\"cap-cols\"><div>");
        sb.append("<section class=\"form-section\"><p class=\"fs-h\">Réglages</p>")
                .append("<p class=\"fs-d\">Chaque profil garde ses propres valeurs. Les réglages avancés sont communs à "
                        + "toutes les capacités et gérés par le moteur.</p>");
        sb.append("<div class=\"table-wrap\"><table class=\"ptable\"><thead><tr><th>Réglage</th><th>Valeurs acceptées</th>"
                + "<th>Défaut</th></tr></thead><tbody>");
        for (Map<String, Object> p : params(t)) {
            sb.append("<tr><td><strong>").append(Http.esc(str(p.get("label")))).append("</strong>")
                    .append(Boolean.TRUE.equals(p.get("common")) ? " " + Ui.badge("avancé") : "")
                    .append("<div class=\"form-text\">").append(Http.esc(str(p.get("help"))))
                    .append(" <code>").append(Http.esc(str(p.get("key")))).append("</code></div></td>")
                    .append("<td class=\"n\">").append(Http.esc(valueTypeLabel(str(p.get("valueType")))))
                    .append(str(p.get("bounds")).isEmpty() ? "" : "<br>" + Http.esc(str(p.get("bounds"))))
                    .append("</td><td class=\"n\">").append(Http.esc(defaultText(p))).append("</td></tr>");
        }
        sb.append("</tbody></table></div></section>");

        Map<String, Object> presets = asMap(t.get("presets"));
        if (!presets.isEmpty()) {
            sb.append("<section class=\"form-section\"><p class=\"fs-h\">Préréglages</p>")
                    .append("<p class=\"fs-d\">Valeurs de départ <strong>copiées</strong> dans le profil quand on les "
                            + "choisit : modifier un profil ne change jamais les autres.</p><ul class=\"cap-facts\">");
            presets.forEach((name, values) -> sb.append(fact(name, presetText(t, asMap(values)))));
            sb.append("</ul></section>");
        }
        sb.append("</div><div>");

        sb.append("<section class=\"form-section\"><p class=\"fs-h\">Garanties</p><ul class=\"safe-list\">")
                .append(safe("NONE".equals(str(t.get("worldMutation")))
                        ? "Ne pose ni ne casse aucun bloc." : "Modifie le terrain (" + str(t.get("worldMutation")) + ")."))
                .append(safe("Aucune apparition dans le Hub, un claim ou une zone protégée ; ne vise ni PNJ, ni joueur "
                        + "en créatif ou spectateur."))
                .append(safe(Boolean.TRUE.equals(t.get("repeatable"))
                        ? "Peut figurer plusieurs fois dans un profil." : "Une seule fois par profil."))
                .append(safe("Déclenchée " + triggers(t) + "."))
                .append(safe("IN_DEVELOPMENT".equals(str(t.get("status")))
                        ? "En développement : seulement sur un profil de test (boss, ou tirage naturel désactivé)."
                        : statusLabel(str(t.get("status"))) + "."))
                .append("</ul></section>");
        sb.append("<section class=\"form-section\"><p class=\"fs-h\">Compatibilité</p><p class=\"fs-d\">Effet sur : ")
                .append(Http.esc(effectiveOn(t))).append(". Sur une autre créature, elle est acceptée mais "
                        + "sans effet (un avertissement le signale dans le profil).</p></section>");
        List<String> used = usages(t, profiles);
        sb.append("<section class=\"form-section\"><p class=\"fs-h\">Profils qui l'utilisent · ").append(used.size())
                .append("</p>");
        if (used.isEmpty()) {
            sb.append("<p class=\"fs-d\">Aucun. On l'ajoute depuis un profil de <a href=\"/mobs?agent=")
                    .append(Http.esc(agentId)).append("\">Mobs spéciaux &amp; boss</a> → Modifier → "
                            + "« Ajouter une capacité ».</p>");
        } else {
            sb.append("<ul class=\"cap-facts\">");
            used.forEach(u -> sb.append("<li>").append(Http.esc(u)).append("</li>"));
            sb.append("</ul>");
        }
        sb.append("</section></div></div>");
        return sb.toString();
    }

    private static String safe(String text) {
        return "<li>" + Icons.icon("check") + "<span>" + Http.esc(text) + "</span></li>";
    }

    private static String presetText(Map<String, Object> type, Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> p : params(type)) {
            Object v = values.get(str(p.get("key")));
            if (v != null) {
                parts.add(str(p.get("label")) + " " + number(v));
            }
        }
        return String.join(" · ", parts);
    }

    // ---- Formulaire de profil : cartes + ajout -------------------------------------------------

    /**
     * Section « Capacités » du formulaire de profil. {@code existing} = profil relevé par {@code mob.list}
     * ({@code null} à la création).
     */
    static String formSection(String uid, String agentId, Catalog catalog, Map<String, Object> existing) {
        List<Map<String, Object>> current = existing == null ? List.of()
                : asList(existing.get("abilities")).stream().map(MobAbilityViews::asMap).toList();
        String entityType = existing == null ? "" : str(existing.get("entityType"));
        StringBuilder sb = new StringBuilder("<div class=\"npc-fs ab-section\">");
        sb.append("<p class=\"npc-fs-h\">").append(Icons.icon("ability")).append("Capacités · ")
                .append(current.size()).append(current.size() > 1 ? " configurées" : " configurée").append("</p>");
        sb.append("<p class=\"form-text\">Dans l'ordre où le plugin les examine. Chaque capacité garde ses propres "
                + "valeurs ; une capacité désactivée garde ses réglages sans jamais se déclencher. "
                + "<a href=\"").append(PATH).append("?agent=").append(Http.esc(agentId))
                .append("\">Voir le catalogue des capacités</a></p>");
        sb.append("<input type=\"hidden\" name=\"ab_form\" value=\"1\">");
        for (Object w : asList(existing == null ? null : existing.get("abilityWarnings"))) {
            sb.append(Ui.banner("warn", Http.esc(str(w))));
        }
        sb.append("<div class=\"ab-list\" data-ab-list>");
        for (int i = 0; i < current.size(); i++) {
            Map<String, Object> instance = current.get(i);
            Optional<Map<String, Object>> type = catalog.type(str(instance.get("type")));
            sb.append(type.isPresent() ? existingCard(uid, i, type.get(), instance, entityType)
                    : unknownCard(i, instance));
        }
        sb.append("</div>");
        sb.append(addPicker(uid, catalog, current, existing, current.size()));
        return sb.append("</div>").toString();
    }

    private static String existingCard(String uid, int i, Map<String, Object> type, Map<String, Object> instance,
                                       String entityType) {
        String prefix = "ab_" + i + "_";
        boolean enabled = !Boolean.FALSE.equals(instance.get("enabled"));
        String category = str(type.get("category"));
        StringBuilder sb = new StringBuilder("<div class=\"ab-card").append(enabled ? "" : " off")
                .append("\" data-ab-card>");
        sb.append("<input type=\"hidden\" name=\"").append(prefix).append("type\" value=\"")
                .append(Http.esc(str(type.get("type")))).append("\">");
        sb.append("<div class=\"ab-top\"><span class=\"ab-ord\" data-ab-ord>").append(i + 1).append("</span>")
                .append("<span class=\"cap-ic sm\">").append(Icons.icon(categoryIcon(category))).append("</span>")
                .append("<span class=\"ab-name\">").append(Http.esc(str(type.get("label")))).append("</span>")
                .append(enabled ? statusPill(str(type.get("status"))) : Ui.pill("Désactivée", "neutral", ""));
        sb.append("<div class=\"ab-acts\">")
                .append("<label class=\"switch\"><input type=\"checkbox\" name=\"").append(prefix)
                .append("enabled\" value=\"true\"").append(enabled ? " checked" : "").append("> Active</label>")
                .append("<label class=\"ab-pos\">Ordre <input type=\"number\" min=\"1\" max=\"20\" name=\"")
                .append(prefix).append("pos\" value=\"").append(i + 1).append("\" data-ab-pos></label>")
                .append("<button class=\"btn secondary sm\" type=\"button\" data-ab-move=\"up\" aria-label=\"Monter\" hidden>")
                .append(Icons.icon("up")).append("</button>")
                .append("<button class=\"btn secondary sm\" type=\"button\" data-ab-move=\"down\" aria-label=\"Descendre\" hidden>")
                .append(Icons.icon("down")).append("</button>")
                .append("<label class=\"switch ab-remove\"><input type=\"checkbox\" name=\"").append(prefix)
                .append("remove\" value=\"true\"> Retirer</label>")
                .append("</div>");
        sb.append("<p class=\"ab-sum\">").append(Http.esc(instanceSummary(type, instance)));
        if (!effectiveFor(type, entityType)) {
            sb.append(" <span class=\"ab-warn\">").append(Icons.icon("warning")).append("Sans effet sur un ")
                    .append(Http.esc(entityType)).append(" (effet sur : ").append(Http.esc(effectiveOn(type)))
                    .append(").</span>");
        }
        sb.append("</p></div>");
        sb.append("<details class=\"ab-edit\"><summary>").append(Icons.icon("edit")).append("Modifier les réglages</summary>");
        sb.append(paramFields(uid + "-ab" + i, prefix, type, asMap(instance.get("values"))));
        sb.append("</details></div>");
        return sb.toString();
    }

    /** Capacité inconnue du catalogue (plugin plus récent que le relevé) : gardée telle quelle, non éditable. */
    private static String unknownCard(int i, Map<String, Object> instance) {
        String prefix = "ab_" + i + "_";
        StringBuilder sb = new StringBuilder("<div class=\"ab-card\" data-ab-card>");
        sb.append("<input type=\"hidden\" name=\"").append(prefix).append("type\" value=\"")
                .append(Http.esc(str(instance.get("type")))).append("\">");
        sb.append("<input type=\"hidden\" name=\"").append(prefix).append("pos\" value=\"").append(i + 1)
                .append("\" data-ab-pos>");
        if (!Boolean.FALSE.equals(instance.get("enabled"))) {
            sb.append("<input type=\"hidden\" name=\"").append(prefix).append("enabled\" value=\"true\">");
        }
        asMap(instance.get("values")).forEach((k, v) -> {
            if (!"enabled".equals(k) && v != null) {
                sb.append("<input type=\"hidden\" name=\"").append(prefix).append(Http.esc(k)).append("\" value=\"")
                        .append(Http.esc(number(v))).append("\">");
            }
        });
        sb.append("<div class=\"ab-top\"><span class=\"ab-ord\" data-ab-ord>").append(i + 1).append("</span>")
                .append("<span class=\"ab-name\">").append(Http.esc(str(instance.get("label")))).append("</span>")
                .append("<p class=\"ab-sum\">Absente du catalogue relevé : conservée telle quelle. Cliquer sur "
                        + "« Rafraîchir » pour la modifier.</p></div></div>");
        return sb.toString();
    }

    private static String addPicker(String uid, Catalog catalog, List<Map<String, Object>> current,
                                    Map<String, Object> existing, int count) {
        boolean naturallySpawned = existing != null && "SPECIAL".equals(str(existing.get("category")))
                && Boolean.TRUE.equals(existing.get("enabled")) && asDouble(existing.get("spawnChance")) > 0;
        String entityType = existing == null ? "" : str(existing.get("entityType"));
        StringBuilder sb = new StringBuilder("<details class=\"ab-add\">")
                .append("<summary class=\"btn sm\">").append(Icons.icon("plus")).append("Ajouter une capacité</summary>");
        sb.append("<p class=\"form-text\">Cocher une ou plusieurs capacités : leurs réglages s'ouvrent, déjà remplis avec "
                + "le préréglage « Normal ». ").append(count).append(" sur ").append(catalog.maxPerProfile())
                .append(" au plus par profil.</p>");
        sb.append("<div class=\"pick\">");
        for (Map<String, Object> t : catalog.types()) {
            String type = str(t.get("type"));
            String reason = null;
            boolean present = current.stream().anyMatch(a -> type.equals(str(a.get("type"))));
            if (present && !Boolean.TRUE.equals(t.get("repeatable"))) {
                reason = "Déjà dans ce profil (une seule par profil).";
            } else if ("PLANNED".equals(str(t.get("status")))) {
                reason = "Prévue — pas encore disponible.";
            } else if ("IN_DEVELOPMENT".equals(str(t.get("status"))) && naturallySpawned) {
                reason = "En développement : seulement sur un profil de test (boss, ou tirage naturel désactivé).";
            } else if (count >= catalog.maxPerProfile()) {
                reason = "Plafond atteint : " + catalog.maxPerProfile() + " capacités au plus par profil.";
            }
            String name = "ab_new_" + type;
            String id = uid + "-new-" + type.toLowerCase(Locale.ROOT);
            sb.append("<div class=\"pick-row").append(reason == null ? "" : " disabled").append("\">");
            sb.append("<label class=\"pick-l\" for=\"").append(id).append("\"><input type=\"checkbox\" id=\"").append(id)
                    .append("\" name=\"").append(name).append("\" value=\"true\"").append(reason == null ? "" : " disabled")
                    .append("><span><strong>").append(Http.esc(str(t.get("label")))).append("</strong> ")
                    .append(statusPill(str(t.get("status")))).append("<br><span class=\"why\">")
                    .append(Http.esc(reason != null ? reason : str(t.get("summary"))));
            if (reason == null && !effectiveFor(t, entityType)) {
                sb.append(" Sans effet sur un ").append(Http.esc(entityType)).append(" (effet sur : ")
                        .append(Http.esc(effectiveOn(t))).append(").");
            }
            sb.append("</span></span></label>");
            if (reason == null) {
                Map<String, Object> normal = asMap(asMap(t.get("presets")).get("Normal"));
                sb.append("<div class=\"ab-new-params\">")
                        .append(paramFields(id, name + "_", t, normal)).append("</div>");
            }
            sb.append("</div>");
        }
        return sb.append("</div></details>").toString();
    }

    /** Préréglages (boutons, panel.js) + réglages principaux + réglages avancés repliés. */
    private static String paramFields(String idBase, String prefix, Map<String, Object> type, Map<String, Object> values) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> presets = asMap(type.get("presets"));
        if (!presets.isEmpty()) {
            sb.append("<div class=\"presets ab-presets\" hidden data-ab-presets><span class=\"muted\">Préréglage :</span>");
            presets.forEach((name, preset) -> sb.append("<button type=\"button\" class=\"chip\" data-ab-preset=\"")
                    .append(Http.esc(Json.write(asMap(preset)))).append("\" data-ab-prefix=\"").append(Http.esc(prefix))
                    .append("\">").append(Http.esc(name)).append("</button>"));
            sb.append("</div>");
        }
        StringBuilder main = new StringBuilder();
        StringBuilder advanced = new StringBuilder();
        int advancedCount = 0;
        for (Map<String, Object> p : params(type)) {
            boolean common = Boolean.TRUE.equals(p.get("common"));
            (common ? advanced : main).append(field(idBase, prefix, p, values.get(str(p.get("key")))));
            advancedCount += common ? 1 : 0;
        }
        sb.append("<div class=\"form-grid\">").append(main).append("</div>");
        if (advancedCount > 0) {
            sb.append("<details class=\"mob-fold\"><summary>").append(Icons.icon("wrench")).append("Réglages avancés (")
                    .append(advancedCount).append(")</summary><div class=\"mob-fold-b\"><div class=\"form-grid\">")
                    .append(advanced).append("</div></div></details>");
        }
        return sb.toString();
    }

    private static String field(String idBase, String prefix, Map<String, Object> p, Object value) {
        String key = str(p.get("key"));
        String name = prefix + key;
        String id = idBase + "-" + key;
        String valueType = str(p.get("valueType"));
        StringBuilder sb = new StringBuilder("<div class=\"field\"><label class=\"form-label\" for=\"").append(id)
                .append("\">").append(Http.esc(str(p.get("label"))))
                .append(Boolean.TRUE.equals(p.get("required")) ? " *" : "").append("</label>");
        if ("BOOL".equals(valueType)) {
            String current = value instanceof Boolean b ? String.valueOf(b) : "";
            sb.append("<select class=\"form-select\" id=\"").append(id).append("\" name=\"").append(Http.esc(name)).append("\">")
                    .append(option("", current, "Défaut (" + defaultText(p) + ")"))
                    .append(option("true", current, "Oui")).append(option("false", current, "Non")).append("</select>");
        } else {
            sb.append("<input class=\"form-control\" id=\"").append(id).append("\" type=\"text\" name=\"")
                    .append(Http.esc(name)).append("\" value=\"").append(Http.esc(value == null ? "" : number(value)))
                    .append("\" autocomplete=\"off\"");
            if ("ENTITY_TYPE".equals(valueType)) {
                sb.append(" list=\"dl-mob-entity\"");
            } else {
                sb.append(" inputmode=\"").append("INT".equals(valueType) ? "numeric" : "decimal").append("\"");
            }
            sb.append(">");
        }
        sb.append("<div class=\"form-text\">").append(Http.esc(str(p.get("help"))));
        String bounds = str(p.get("bounds"));
        sb.append("<span class=\"fmeta\"><span><b>Valeurs</b> ").append(Http.esc(valueTypeLabel(valueType)))
                .append(bounds.isEmpty() ? "" : ", " + Http.esc(bounds)).append("</span><span><b>Défaut</b> ")
                .append(Http.esc(defaultText(p))).append("</span></span></div></div>");
        return sb.toString();
    }

    // ---- Petits utilitaires --------------------------------------------------------------------

    private static String option(String value, String current, String label) {
        return "<option value=\"" + Http.esc(value) + "\"" + (value.equals(current) ? " selected" : "") + ">"
                + Http.esc(label) + "</option>";
    }

    /** Valeur telle que relevée ({@code 2.0} reste « 2.0 » : le plugin réécrit ce qu'il a lu). */
    static String number(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static List<Object> asList(Object value) {
        return value instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }
}
