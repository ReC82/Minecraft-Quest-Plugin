package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.Descriptors;
import com.lodygames.rpgquest.panel.content.Diagnostic;
import com.lodygames.rpgquest.panel.content.QuestDraft;
import com.lodygames.rpgquest.panel.content.QuestValidator;
import com.lodygames.rpgquest.panel.content.QuestYaml;
import com.lodygames.rpgquest.panel.content.RefData;
import com.lodygames.rpgquest.panel.content.StoryDraft;
import com.lodygames.rpgquest.panel.content.StoryValidator;
import com.lodygames.rpgquest.panel.content.StoryYaml;
import com.lodygames.rpgquest.panel.content.TextDiff;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Éditeur guidé de quêtes et de stories (issue #46). Formulaire multi-sections rendu côté serveur,
 * <strong>sans JavaScript</strong> : chaque bouton {@code _action} renvoie l'état complet, le
 * serveur le relit dans un {@link QuestDraft} / {@link StoryDraft}, applique la mutation
 * (ajout / suppression / réordonnancement de ligne) et re-rend. Les listes déroulantes de valeurs
 * réelles (entité, matériau, PNJ, quête, monde) sont des {@code <datalist>} — autocomplétion +
 * saisie libre, conforme CSP.
 *
 * <p>Sépare strictement <em>éditer</em> de <em>enregistrer dans la source</em> : l'écriture passe
 * par {@link ContentWorkspace} (whitelist {@code quests/*.yml} / {@code stories/*.yml}, hash de
 * version, écriture atomique). Aucun déploiement. Si l'espace de travail est en lecture seule, tout
 * fonctionne sauf le bouton d'enregistrement.</p>
 */
public final class ContentEditorPages {

    private static final Pattern STEP_ID_KEY = Pattern.compile("step\\.(\\d+)\\.id");
    private static final Pattern OBJ_KEY = Pattern.compile("obj\\.(\\d+)\\.(\\d+)\\.(.+)");
    private static final Pattern REW_KEY = Pattern.compile("rew\\.(\\d+)\\.(.+)");
    private static final Pattern STORY_Q_KEY = Pattern.compile("q\\.(\\d+)");

    private final ContentWorkspace workspace;

    public ContentEditorPages(ContentWorkspace workspace) {
        this.workspace = workspace;
    }

    public sealed interface Result {
        record Html(String body) implements Result {
        }

        record Redirect(String location) implements Result {
        }
    }

    // ================================================================================
    //  Quêtes
    // ================================================================================

    /** GET {@code /quests/new} ({@code slug == null}) ou {@code /quests/edit/<slug>}. */
    public Result questPage(RefData ref, String slug, boolean saved) {
        QuestDraft draft;
        String expectedSha = "";
        if (slug == null) {
            draft = QuestDraft.blank();
        } else {
            Optional<ContentWorkspace.ContentFile> cf = workspace.read("quests", slug);
            if (cf.isEmpty()) {
                return new Result.Html(missing("quests", slug));
            }
            QuestYaml.ReadResult rr = QuestYaml.read(cf.get().text());
            draft = rr.draft() != null ? rr.draft() : QuestDraft.blank();
            if (draft.id.isBlank()) {
                draft.id = slug;
            }
            expectedSha = cf.get().sha256();
        }
        String note = saved ? Ui.banner("ok", "Quête enregistrée dans la source. "
                + "Le fichier sera validé par le moteur RPGQuest au prochain chargement du serveur.") : "";
        return new Result.Html(note + renderQuest(ref, draft, slug, expectedSha, List.of(), false, null));
    }

    /** POST {@code /quests/save} — porte toutes les actions du formulaire. */
    public Result questPost(RefData ref, Map<String, String> form) {
        QuestDraft draft = parseQuest(form);
        String slug = blankToNull(form.get("slug"));
        String expectedSha = form.getOrDefault("expectedSha", "");
        String action = form.getOrDefault("_action", "refresh");
        applyQuestAction(draft, action);

        RefData refPlus = ref == null ? RefData.empty() : ref;
        boolean wantSave = action.equals("save");
        boolean showChecks = wantSave || action.equals("validate");

        if (wantSave) {
            List<Diagnostic> diags = QuestValidator.validate(draft, refPlus);
            String yaml = QuestYaml.write(draft);
            List<String> rtp = QuestYaml.roundTripProblems(yaml);
            String targetSlug = QuestYaml.plainId(draft.id);
            String blocker = saveBlocker("quests", diags, rtp, targetSlug);
            if (blocker != null) {
                return new Result.Html(Ui.banner("err", blocker)
                        + renderQuest(refPlus, draft, slug, expectedSha, diags, true, rtp));
            }
            ContentWorkspace.WriteResult wr =
                    workspace.write("quests", targetSlug, yaml, slug == null ? "" : expectedSha);
            if (!wr.ok()) {
                return new Result.Html(Ui.banner("err", Http.esc(wr.message()))
                        + renderQuest(refPlus, draft, slug, expectedSha, diags, true, rtp));
            }
            return new Result.Redirect("/quests/edit/" + targetSlug + "?saved=1");
        }

        List<Diagnostic> diags = showChecks ? QuestValidator.validate(draft, refPlus) : List.of();
        List<String> rtp = showChecks ? QuestYaml.roundTripProblems(QuestYaml.write(draft)) : null;
        return new Result.Html(renderQuest(refPlus, draft, slug, expectedSha, diags, showChecks, rtp));
    }

    private void applyQuestAction(QuestDraft d, String action) {
        String[] p = action.split(":");
        switch (p[0]) {
            case "add_step" -> {
                QuestDraft.Step s = new QuestDraft.Step("step_" + (d.steps.size() + 1));
                s.objectives.add(blankRow("KILL_ENTITY"));
                d.steps.add(s);
            }
            case "del_step" -> {
                int i = intAt(p, 1);
                if (i >= 0 && i < d.steps.size()) {
                    d.steps.remove(i);
                }
            }
            case "add_obj" -> {
                int si = intAt(p, 1);
                if (si >= 0 && si < d.steps.size()) {
                    d.steps.get(si).objectives.add(blankRow("KILL_ENTITY"));
                }
            }
            case "del_obj" -> {
                int si = intAt(p, 1);
                int oi = intAt(p, 2);
                if (si >= 0 && si < d.steps.size() && oi >= 0 && oi < d.steps.get(si).objectives.size()) {
                    d.steps.get(si).objectives.remove(oi);
                }
            }
            case "mv_obj" -> move(si(d, intAt(p, 1)), intAt(p, 2), p.length > 3 ? p[3] : "");
            case "add_reward" -> d.rewards.add(blankRow("EXPERIENCE"));
            case "del_reward" -> {
                int ri = intAt(p, 1);
                if (ri >= 0 && ri < d.rewards.size()) {
                    d.rewards.remove(ri);
                }
            }
            case "mv_reward" -> move(d.rewards, intAt(p, 1), p.length > 2 ? p[2] : "");
            default -> {
                // refresh / validate / save : aucune mutation structurelle
            }
        }
    }

    private static List<Map<String, String>> si(QuestDraft d, int si) {
        return si >= 0 && si < d.steps.size() ? d.steps.get(si).objectives : new ArrayList<>();
    }

    // ---- parsing du formulaire quête ---------------------------------------------------

    private QuestDraft parseQuest(Map<String, String> f) {
        QuestDraft d = new QuestDraft();
        d.id = f.getOrDefault("id", "").trim();
        d.title = f.getOrDefault("title", "").trim();
        d.description = f.getOrDefault("description", "").trim();
        d.category = f.getOrDefault("category", "").trim();
        d.icon = f.getOrDefault("icon", "").trim();
        d.repeatable = isOn(f.get("repeatable"));
        d.secret = isOn(f.get("secret"));
        d.giver = f.getOrDefault("giver", "").trim();
        for (String line : lines(f.get("prereq"))) {
            d.prerequisites.add(line);
        }
        for (String line : lines(f.get("vars"))) {
            int eq = indexOfAny(line, "=:");
            if (eq > 0) {
                d.variables.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }

        // Étapes : indices découverts via "step.<si>.id"
        TreeSet<Integer> stepIdx = new TreeSet<>();
        for (String k : f.keySet()) {
            Matcher m = STEP_ID_KEY.matcher(k);
            if (m.matches()) {
                stepIdx.add(Integer.parseInt(m.group(1)));
            }
        }
        for (int si : stepIdx) {
            QuestDraft.Step step = new QuestDraft.Step(f.getOrDefault("step." + si + ".id", "").trim());
            TreeSet<Integer> objIdx = new TreeSet<>();
            Map<Integer, Map<String, String>> objs = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : f.entrySet()) {
                Matcher m = OBJ_KEY.matcher(e.getKey());
                if (m.matches() && Integer.parseInt(m.group(1)) == si) {
                    int oi = Integer.parseInt(m.group(2));
                    objIdx.add(oi);
                    objs.computeIfAbsent(oi, x -> new LinkedHashMap<>()).put(m.group(3), e.getValue());
                }
            }
            for (int oi : objIdx) {
                step.objectives.add(normaliseRow(objs.get(oi)));
            }
            d.steps.add(step);
        }

        // Récompenses : indices via "rew.<ri>.*"
        TreeSet<Integer> rewIdx = new TreeSet<>();
        Map<Integer, Map<String, String>> rews = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : f.entrySet()) {
            Matcher m = REW_KEY.matcher(e.getKey());
            if (m.matches()) {
                int ri = Integer.parseInt(m.group(1));
                rewIdx.add(ri);
                rews.computeIfAbsent(ri, x -> new LinkedHashMap<>()).put(m.group(2), e.getValue());
            }
        }
        for (int ri : rewIdx) {
            d.rewards.add(normaliseRow(rews.get(ri)));
        }
        return d;
    }

    /** Force une clé {@code kind} en tête, valeur en MAJUSCULES. */
    private static Map<String, String> normaliseRow(Map<String, String> raw) {
        Map<String, String> row = new LinkedHashMap<>();
        String kind = raw.getOrDefault("kind", "").trim().toUpperCase(Locale.ROOT);
        row.put("kind", kind.isBlank() ? "KILL_ENTITY" : kind);
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (!e.getKey().equals("kind")) {
                row.put(e.getKey(), e.getValue().trim());
            }
        }
        return row;
    }

    // ---- rendu du formulaire quête ---------------------------------------------------

    private String renderQuest(RefData ref, QuestDraft d, String slug, String expectedSha,
                               List<Diagnostic> diags, boolean showChecks, List<String> rtp) {
        boolean editing = slug != null;
        boolean writable = workspace.writable("quests");
        StringBuilder sb = new StringBuilder();

        sb.append(Ui.pageHeader("quests", editing ? "Modifier une quête" : "Créer une quête",
                editing ? "Quête « " + Http.esc(slug) + " » — " + Http.esc(d.title)
                        : "Renseigner les sections ci-dessous ; « Vérifier » liste les anomalies avant l'enregistrement.",
                "<a class=\"btn secondary\" href=\"/quests\">" + Icons.icon("back") + "Retour au catalogue</a>"));

        if (!writable) {
            sb.append(readOnlyBanner("quests"));
        }

        sb.append("<form method=\"post\" action=\"/quests/save\" class=\"editor\">%CSRF%");
        sb.append(hidden("slug", slug == null ? "" : slug));
        sb.append(hidden("expectedSha", expectedSha == null ? "" : expectedSha));

        // -- Général --
        sb.append(sectionOpen("book", "Général", "Identité de la quête. L'identifiant canonique sert de nom de fichier."));
        sb.append("<div class=\"form-grid\">");
        sb.append(text("id", "Identifiant canonique", "Minuscules, chiffres, « _ - / ». « rpgquest: » est ajouté automatiquement.",
                QuestYaml.plainId(d.id), true, editing));
        sb.append(text("category", "Catégorie", "Ex. tutorial, combat, crafting.", d.category, true, false));
        sb.append(text("title", "Titre affiché", "Peut contenir du MiniMessage (ex. <gold>…</gold>).", d.title, true, false, "full"));
        sb.append(textarea("description", "Description", "Texte présenté au joueur. MiniMessage accepté.", d.description, "full"));
        sb.append(text("icon", "Icône", "Matériau Minecraft servant d'icône (BOOK par défaut).", d.icon, false, false));
        sb.append(text("giver", "PNJ donneur", "Identifiant logique du PNJ qui remet la quête (optionnel).", d.giver, false, false, null, "dl-npc"));
        sb.append("<div class=\"full\">");
        sb.append(checkbox("repeatable", "Quête répétable", d.repeatable));
        sb.append(checkbox("secret", "Quête secrète (masquée tant que non déclenchée)", d.secret));
        sb.append("</div></div>");
        sb.append(sectionClose());

        // -- Prérequis --
        sb.append(sectionOpen("lock", "Prérequis",
                "Quêtes à terminer avant celle-ci — un identifiant par ligne (namespace optionnel)."));
        sb.append(textarea("prereq", "Quêtes prérequises", "Ex. first_steps", String.join("\n", d.prerequisites), "full"));
        if (!ref.quests().isEmpty()) {
            sb.append("<p class=\"field-help\">Connues : ").append(Http.esc(preview(ref.quests(), 12))).append("</p>");
        }
        sb.append(sectionClose());

        // -- Objectifs --
        sb.append(sectionOpen("target", "Objectifs",
                "Une quête est une suite d'étapes ordonnées ; chaque étape a un ou plusieurs objectifs."));
        for (int si = 0; si < d.steps.size(); si++) {
            sb.append(renderStep(ref, d.steps.get(si), si, d.steps.size()));
        }
        sb.append("<div class=\"btnrow\"><button class=\"btn sm\" name=\"_action\" value=\"add_step\">")
                .append(Icons.icon("plus")).append("Ajouter une étape</button></div>");
        sb.append(sectionClose());

        // -- Récompenses --
        sb.append(sectionOpen("gift", "Récompenses", "Attribuées une fois la quête entièrement terminée."));
        sb.append("<div class=\"rowlist\">");
        for (int ri = 0; ri < d.rewards.size(); ri++) {
            sb.append(renderRow(ref, "rew", d.rewards.get(ri), -1, ri, d.rewards.size(),
                    Descriptors.REWARDS, "reward"));
        }
        sb.append("</div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn sm\" name=\"_action\" value=\"add_reward\">")
                .append(Icons.icon("plus")).append("Ajouter une récompense</button></div>");
        sb.append(sectionClose());

        // -- Variables --
        sb.append(sectionOpen("edit", "Variables",
                "Variables persistantes posées à la fin de la quête — une par ligne, « CLÉ = valeur »."));
        sb.append(textarea("vars", "Variables", "Ex. CLAIM_TIER_1 = true", varsText(d.variables), "full"));
        sb.append(sectionClose());

        // -- Validation / aperçu --
        sb.append(sectionOpen("check", "Validation & aperçu", "« Vérifier » ne modifie rien : il liste les anomalies et montre le fichier généré."));
        sb.append("<div class=\"btnrow\">");
        sb.append("<button class=\"btn secondary\" name=\"_action\" value=\"refresh\">")
                .append(Icons.icon("filter")).append("Actualiser le formulaire</button>");
        sb.append("<button class=\"btn secondary\" name=\"_action\" value=\"validate\">")
                .append(Icons.icon("check")).append("Vérifier</button>");
        if (writable) {
            sb.append("<button class=\"btn\" name=\"_action\" value=\"save\">")
                    .append(Icons.icon("save")).append("Enregistrer dans la source</button>");
        } else {
            sb.append("<button class=\"btn\" name=\"_action\" value=\"save\" disabled>")
                    .append(Icons.icon("save")).append("Enregistrer dans la source</button>");
        }
        sb.append("</div>");

        if (showChecks) {
            sb.append(renderChecks(diags, rtp));
            String yaml = QuestYaml.write(d);
            sb.append(renderPreview("quests", slug, yaml));
        }
        sb.append(sectionClose());

        sb.append(sharedDatalists(ref));
        sb.append("</form>");
        return sb.toString();
    }

    private String renderStep(RefData ref, QuestDraft.Step step, int si, int total) {
        StringBuilder sb = new StringBuilder("<div class=\"rowitem\">");
        sb.append("<div class=\"rowitem-h\"><span class=\"grip\">").append(Icons.icon("chevron")).append("</span>");
        sb.append("<span>Étape ").append(si + 1).append("</span>");
        sb.append("<span class=\"rowitem-actions\">");
        sb.append(iconBtn("del_step:" + si, "trash", "Supprimer l'étape"));
        sb.append("</span></div>");
        sb.append("<div class=\"form-grid\">");
        sb.append(text("step." + si + ".id", "Identifiant d'étape", "Minuscules, chiffres, « _ - ».", step.id, true, false));
        sb.append("</div>");
        sb.append("<div class=\"rowlist\">");
        for (int oi = 0; oi < step.objectives.size(); oi++) {
            sb.append(renderRow(ref, "obj." + si, step.objectives.get(oi), si, oi, step.objectives.size(),
                    Descriptors.OBJECTIVES, "objective"));
        }
        sb.append("</div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn sm\" name=\"_action\" value=\"add_obj:").append(si).append("\">")
                .append(Icons.icon("plus")).append("Ajouter un objectif</button></div>");
        return sb.append("</div>").toString();
    }

    /**
     * Rendu générique d'une ligne objectif/récompense.
     *
     * @param prefix {@code "obj.<si>"} ou {@code "rew"}
     * @param si     index d'étape (objectif) ou {@code -1} (récompense)
     */
    private String renderRow(RefData ref, String prefix, Map<String, String> row, int si, int idx, int total,
                             List<Descriptors.Descriptor> catalog, String kindKind) {
        String kind = row.getOrDefault("kind", catalog.get(0).kind());
        Optional<Descriptors.Descriptor> desc = catalog.stream().filter(x -> x.kind().equalsIgnoreCase(kind)).findFirst();
        String mvBase = kindKind.equals("objective") ? "mv_obj:" + si + ":" + idx : "mv_reward:" + idx;
        String delAction = kindKind.equals("objective") ? "del_obj:" + si + ":" + idx : "del_reward:" + idx;
        String fieldBase = prefix + "." + idx;

        StringBuilder sb = new StringBuilder("<div class=\"rowitem\">");
        sb.append("<div class=\"rowitem-h\"><span class=\"grip\">").append(Icons.icon("chevron")).append("</span>");
        sb.append("<select name=\"").append(fieldBase).append(".kind\" aria-label=\"Type\">");
        for (Descriptors.Descriptor c : catalog) {
            sb.append("<option value=\"").append(c.kind()).append("\"")
                    .append(c.kind().equalsIgnoreCase(kind) ? " selected" : "").append(">")
                    .append(Http.esc(c.label())).append("</option>");
        }
        sb.append("</select>");
        sb.append("<span class=\"rowitem-actions\">");
        if (idx > 0) {
            sb.append(iconBtn(mvBase + ":up", "up", "Monter"));
        }
        if (idx < total - 1) {
            sb.append(iconBtn(mvBase + ":down", "down", "Descendre"));
        }
        sb.append(iconBtn(delAction, "trash", "Supprimer"));
        sb.append("</span></div>");

        desc.ifPresent(dsc -> {
            if (dsc.hint() != null && !dsc.hint().isBlank()) {
                sb.append("<p class=\"field-help\">").append(Http.esc(dsc.hint())).append("</p>");
            }
            sb.append("<div class=\"form-grid\">");
            for (Descriptors.Field fld : dsc.fields()) {
                String name = fieldBase + "." + fld.name();
                String val = row.getOrDefault(fld.name(), "");
                switch (fld.type()) {
                    case INT, DOUBLE -> sb.append(number(name, fld.label(), fld.help(), val, fld.required(),
                            fld.type() == Descriptors.FieldType.INT));
                    case SELECT -> sb.append(text(name, fld.label(), fld.help(), val, fld.required(), false,
                            null, datalistId(fld.selectSource())));
                    default -> sb.append(text(name, fld.label(), fld.help(), val, fld.required(), false));
                }
            }
            sb.append("</div>");
        });
        if (desc.isEmpty()) {
            sb.append("<p class=\"field-help\">Type inconnu — choisir un type valide puis « Actualiser le formulaire ».</p>");
        }
        return sb.append("</div>").toString();
    }

    // ================================================================================
    //  Stories
    // ================================================================================

    public Result storyPage(RefData ref, String slug, boolean saved) {
        StoryDraft draft;
        String expectedSha = "";
        if (slug == null) {
            draft = StoryDraft.blank();
        } else {
            Optional<ContentWorkspace.ContentFile> cf = workspace.read("stories", slug);
            if (cf.isEmpty()) {
                return new Result.Html(missing("stories", slug));
            }
            StoryYaml.ReadResult rr = StoryYaml.read(cf.get().text());
            draft = rr.draft() != null ? rr.draft() : StoryDraft.blank();
            if (draft.id.isBlank()) {
                draft.id = slug;
            }
            expectedSha = cf.get().sha256();
        }
        String note = saved ? Ui.banner("ok", "Story enregistrée dans la source.") : "";
        return new Result.Html(note + renderStory(ref, draft, slug, expectedSha, List.of(), false, null));
    }

    public Result storyPost(RefData ref, Map<String, String> form) {
        StoryDraft draft = parseStory(form);
        String slug = blankToNull(form.get("slug"));
        String expectedSha = form.getOrDefault("expectedSha", "");
        String action = form.getOrDefault("_action", "refresh");
        applyStoryAction(draft, action);

        RefData refPlus = ref == null ? RefData.empty() : ref;
        boolean wantSave = action.equals("save");
        boolean showChecks = wantSave || action.equals("validate");

        if (wantSave) {
            List<Diagnostic> diags = StoryValidator.validate(draft, refPlus);
            String yaml = StoryYaml.write(draft);
            List<String> rtp = StoryYaml.roundTripProblems(yaml);
            String targetSlug = StoryYaml.plainId(draft.id);
            String blocker = saveBlocker("stories", diags, rtp, targetSlug);
            if (blocker != null) {
                return new Result.Html(Ui.banner("err", blocker)
                        + renderStory(refPlus, draft, slug, expectedSha, diags, true, rtp));
            }
            ContentWorkspace.WriteResult wr =
                    workspace.write("stories", targetSlug, yaml, slug == null ? "" : expectedSha);
            if (!wr.ok()) {
                return new Result.Html(Ui.banner("err", Http.esc(wr.message()))
                        + renderStory(refPlus, draft, slug, expectedSha, diags, true, rtp));
            }
            return new Result.Redirect("/stories/edit/" + targetSlug + "?saved=1");
        }

        List<Diagnostic> diags = showChecks ? StoryValidator.validate(draft, refPlus) : List.of();
        List<String> rtp = showChecks ? StoryYaml.roundTripProblems(StoryYaml.write(draft)) : null;
        return new Result.Html(renderStory(refPlus, draft, slug, expectedSha, diags, showChecks, rtp));
    }

    private void applyStoryAction(StoryDraft d, String action) {
        String[] p = action.split(":");
        switch (p[0]) {
            case "add_q" -> d.questIds.add("");
            case "del_q" -> {
                int i = intAt(p, 1);
                if (i >= 0 && i < d.questIds.size()) {
                    d.questIds.remove(i);
                }
            }
            case "mv_q" -> {
                int i = intAt(p, 1);
                String dir = p.length > 2 ? p[2] : "";
                int j = dir.equals("up") ? i - 1 : i + 1;
                if (i >= 0 && i < d.questIds.size() && j >= 0 && j < d.questIds.size()) {
                    java.util.Collections.swap(d.questIds, i, j);
                }
            }
            default -> {
            }
        }
    }

    private StoryDraft parseStory(Map<String, String> f) {
        StoryDraft d = new StoryDraft();
        d.id = f.getOrDefault("id", "").trim();
        d.name = f.getOrDefault("name", "").trim();
        d.secret = isOn(f.get("secret"));
        TreeSet<Integer> idx = new TreeSet<>();
        for (String k : f.keySet()) {
            Matcher m = STORY_Q_KEY.matcher(k);
            if (m.matches()) {
                idx.add(Integer.parseInt(m.group(1)));
            }
        }
        for (int i : idx) {
            d.questIds.add(f.getOrDefault("q." + i, "").trim());
        }
        return d;
    }

    private String renderStory(RefData ref, StoryDraft d, String slug, String expectedSha,
                               List<Diagnostic> diags, boolean showChecks, List<String> rtp) {
        boolean editing = slug != null;
        boolean writable = workspace.writable("stories");
        StringBuilder sb = new StringBuilder();

        sb.append(Ui.pageHeader("stories", editing ? "Modifier une story" : "Créer une story",
                editing ? "Story « " + Http.esc(slug) + " » — " + Http.esc(d.name)
                        : "Une story est un enchaînement ordonné de quêtes existantes. Elle ne relance jamais rien toute seule.",
                "<a class=\"btn secondary\" href=\"/stories\">" + Icons.icon("back") + "Retour au catalogue</a>"));

        if (!writable) {
            sb.append(readOnlyBanner("stories"));
        }

        sb.append("<form method=\"post\" action=\"/stories/save\" class=\"editor\">%CSRF%");
        sb.append(hidden("slug", slug == null ? "" : slug));
        sb.append(hidden("expectedSha", expectedSha == null ? "" : expectedSha));

        sb.append(sectionOpen("book", "Général", "Identité de la story. L'identifiant sert de nom de fichier."));
        sb.append("<div class=\"form-grid\">");
        sb.append(text("id", "Identifiant", "Minuscules, chiffres, « _ - ».", StoryYaml.plainId(d.id), true, editing));
        sb.append(text("name", "Nom affiché", "Titre lisible de la chaîne.", d.name, true, false));
        sb.append("<div class=\"full\">").append(checkbox("secret", "Story secrète", d.secret)).append("</div>");
        sb.append("</div>");
        sb.append(sectionClose());

        sb.append(sectionOpen("target", "Chaîne de quêtes",
                "Ordre d'enchaînement présenté au joueur. Utiliser les flèches pour réordonner."));
        sb.append("<div class=\"rowlist\">");
        for (int i = 0; i < d.questIds.size(); i++) {
            sb.append("<div class=\"rowitem\"><div class=\"rowitem-h\"><span class=\"grip\">")
                    .append(Icons.icon("chevron")).append("</span><span>").append(i + 1).append(".</span>");
            sb.append("<span class=\"rowitem-actions\">");
            if (i > 0) {
                sb.append(iconBtn("mv_q:" + i + ":up", "up", "Monter"));
            }
            if (i < d.questIds.size() - 1) {
                sb.append(iconBtn("mv_q:" + i + ":down", "down", "Descendre"));
            }
            sb.append(iconBtn("del_q:" + i, "trash", "Retirer"));
            sb.append("</span></div><div class=\"form-grid\">");
            sb.append(text("q." + i, "Quête", "Identifiant de quête (namespace optionnel).", d.questIds.get(i),
                    true, false, null, "dl-quest"));
            sb.append("</div></div>");
        }
        sb.append("</div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn sm\" name=\"_action\" value=\"add_q\">")
                .append(Icons.icon("plus")).append("Ajouter une quête</button></div>");
        if (!ref.quests().isEmpty()) {
            sb.append("<p class=\"field-help\">Connues : ").append(Http.esc(preview(ref.quests(), 12))).append("</p>");
        }
        sb.append(sectionClose());

        sb.append(sectionOpen("check", "Validation & aperçu", "« Vérifier » liste les anomalies et montre le fichier généré."));
        sb.append("<div class=\"btnrow\">");
        sb.append("<button class=\"btn secondary\" name=\"_action\" value=\"validate\">")
                .append(Icons.icon("check")).append("Vérifier</button>");
        sb.append("<button class=\"btn\" name=\"_action\" value=\"save\"").append(writable ? "" : " disabled").append(">")
                .append(Icons.icon("save")).append("Enregistrer dans la source</button>");
        sb.append("</div>");
        if (showChecks) {
            sb.append(renderChecks(diags, rtp));
            sb.append(renderPreview("stories", slug, StoryYaml.write(d)));
        }
        sb.append(sectionClose());

        sb.append(sharedDatalists(ref));
        sb.append("</form>");
        return sb.toString();
    }

    // ================================================================================
    //  Fragments partagés
    // ================================================================================

    private String renderChecks(List<Diagnostic> diags, List<String> rtp) {
        StringBuilder sb = new StringBuilder();
        if (rtp != null && !rtp.isEmpty()) {
            StringBuilder b = new StringBuilder("<strong>Le fichier généré ne se relit pas fidèlement — "
                    + "enregistrement bloqué :</strong><ul>");
            for (String r : rtp) {
                b.append("<li>").append(Http.esc(r)).append("</li>");
            }
            sb.append(Ui.banner("err", b.append("</ul>").toString()));
        }
        long errs = diags.stream().filter(x -> x.level() == Diagnostic.Level.ERROR).count();
        long warns = diags.stream().filter(x -> x.level() == Diagnostic.Level.WARNING).count();
        if (diags.isEmpty()) {
            sb.append(Ui.banner("ok", "Aucune anomalie détectée par la validation du panneau. "
                    + "Le moteur RPGQuest reste l'autorité finale au chargement du serveur."));
        } else {
            String head = errs > 0
                    ? "<strong>" + errs + " erreur(s)</strong> — à corriger avant l'enregistrement."
                    : (warns > 0 ? "<strong>" + warns + " avertissement(s)</strong> — enregistrement possible après vérification."
                    : "Informations de validation.");
            String type = errs > 0 ? "err" : (warns > 0 ? "warn" : "info");
            StringBuilder b = new StringBuilder(head).append("<ul class=\"diag-list\">");
            for (Diagnostic d : diags) {
                b.append("<li>").append(Ui.severity(d.level().name())).append(" ");
                if (!d.field().isBlank()) {
                    b.append("<code class=\"tid\">").append(Http.esc(d.field())).append("</code> ");
                }
                b.append(Http.esc(d.message())).append("</li>");
            }
            sb.append(Ui.banner(type, b.append("</ul>").toString()));
        }
        return sb.toString();
    }

    private String renderPreview(String kind, String slug, String yaml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"section-title\">").append(Icons.icon("book")).append("Fichier généré</p>");
        Optional<ContentWorkspace.ContentFile> current = slug == null ? Optional.empty() : workspace.read(kind, slug);
        if (current.isPresent() && !TextDiff.identical(current.get().text(), yaml)) {
            sb.append("<p class=\"field-help\">Différences avec la version actuelle de <code>")
                    .append(Http.esc(current.get().repoPath())).append("</code> :</p>");
            sb.append("<div class=\"codeblock diff\"><pre>");
            for (TextDiff.Line l : TextDiff.diff(current.get().text(), yaml)) {
                String cls = l.kind() == '+' ? "di-add" : (l.kind() == '-' ? "di-del" : "di-ctx");
                sb.append("<span class=\"").append(cls).append("\">").append(l.kind()).append(' ')
                        .append(Http.esc(l.text())).append("</span>\n");
            }
            sb.append("</pre></div>");
        } else if (current.isPresent()) {
            sb.append("<p class=\"field-help\">Identique à la version actuelle de la source.</p>");
        }
        sb.append("<div class=\"codeblock\"><pre>").append(Http.esc(yaml)).append("</pre></div>");
        sb.append("<p class=\"field-help\">Aperçu en lecture seule — le YAML est un diagnostic, pas un champ éditable.</p>");
        return sb.toString();
    }

    private String saveBlocker(String kind, List<Diagnostic> diags, List<String> rtp, String slug) {
        if (!workspace.writable(kind)) {
            return "Espace de travail en lecture seule : enregistrement impossible. " + readOnlyHint(kind);
        }
        if (slug == null || slug.isBlank() || !slug.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            return "Identifiant invalide « " + Http.esc(slug) + " » : impossible d'en déduire un nom de fichier sûr.";
        }
        if (rtp != null && !rtp.isEmpty()) {
            return "Le fichier généré ne se relit pas fidèlement — enregistrement bloqué (voir ci-dessous).";
        }
        if (Diagnostic.hasError(diags)) {
            return "La validation signale des erreurs — corriger avant d'enregistrer.";
        }
        return null;
    }

    private String readOnlyBanner(String kind) {
        return Ui.banner("warn", "<strong>Éditeur en lecture seule.</strong> " + readOnlyHint(kind)
                + " La validation, l'aperçu et le diff restent disponibles.");
    }

    private String readOnlyHint(String kind) {
        return "Le service PlugAdmin n'a pas les droits d'écriture sur <code>src/main/resources/" + kind
                + "/</code>. Le propriétaire du dépôt peut les accorder (ex. "
                + "<code>setfacl -m u:plugadmin:rwx src/main/resources/quests src/main/resources/stories</code>), "
                + "puis relancer le service.";
    }

    private String missing(String kind, String slug) {
        return Ui.banner("err", "Contenu introuvable : <code>" + Http.esc(kind) + "/" + Http.esc(slug)
                + ".yml</code> n'existe pas dans l'espace de travail.");
    }

    // ---- primitives de formulaire --------------------------------------------------

    private static String sectionOpen(String icon, String title, String desc) {
        return "<div class=\"form-section\"><p class=\"fs-h\">" + Icons.icon(icon) + Http.esc(title) + "</p>"
                + (desc == null || desc.isBlank() ? "" : "<p class=\"fs-d\">" + Http.esc(desc) + "</p>");
    }

    private static String sectionClose() {
        return "</div>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value) + "\">";
    }

    private static String text(String name, String label, String help, String value, boolean required, boolean readOnly) {
        return text(name, label, help, value, required, readOnly, null, null);
    }

    private static String text(String name, String label, String help, String value, boolean required,
                               boolean readOnly, String extraClass) {
        return text(name, label, help, value, required, readOnly, extraClass, null);
    }

    private static String text(String name, String label, String help, String value, boolean required,
                               boolean readOnly, String extraClass, String datalist) {
        String id = "f-" + name.replace('.', '-');
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"field").append(extraClass != null ? " " + extraClass : "").append("\">");
        sb.append("<label for=\"").append(id).append("\">").append(Http.esc(label))
                .append(required ? " <span aria-hidden=\"true\">*</span>" : "").append("</label>");
        sb.append("<input type=\"text\" id=\"").append(id).append("\" name=\"").append(Http.esc(name)).append("\" value=\"")
                .append(Http.esc(value)).append("\"");
        if (datalist != null) {
            sb.append(" list=\"").append(datalist).append("\" autocomplete=\"off\"");
        }
        if (readOnly) {
            sb.append(" readonly");
        }
        if (required) {
            sb.append(" required");
        }
        sb.append(">");
        if (help != null && !help.isBlank()) {
            sb.append("<p class=\"field-help\">").append(Http.esc(help)).append("</p>");
        }
        return sb.append("</div>").toString();
    }

    private static String number(String name, String label, String help, String value, boolean required, boolean integer) {
        String id = "f-" + name.replace('.', '-');
        return "<div class=\"field\"><label for=\"" + id + "\">" + Http.esc(label)
                + (required ? " <span aria-hidden=\"true\">*</span>" : "") + "</label>"
                + "<input type=\"number\" id=\"" + id + "\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value)
                + "\"" + (integer ? " step=\"1\"" : " step=\"any\"") + (required ? " required" : "") + ">"
                + (help == null || help.isBlank() ? "" : "<p class=\"field-help\">" + Http.esc(help) + "</p>")
                + "</div>";
    }

    private static String textarea(String name, String label, String help, String value, String extraClass) {
        String id = "f-" + name.replace('.', '-');
        return "<div class=\"field" + (extraClass != null ? " " + extraClass : "") + "\">"
                + "<label for=\"" + id + "\">" + Http.esc(label) + "</label>"
                + "<textarea id=\"" + id + "\" name=\"" + Http.esc(name) + "\">" + Http.esc(value) + "</textarea>"
                + (help == null || help.isBlank() ? "" : "<p class=\"field-help\">" + Http.esc(help) + "</p>")
                + "</div>";
    }

    private static String checkbox(String name, String label, boolean checked) {
        return "<label class=\"inline\"><input type=\"checkbox\" name=\"" + Http.esc(name) + "\" value=\"on\""
                + (checked ? " checked" : "") + "> " + Http.esc(label) + "</label>";
    }

    private static String iconBtn(String action, String icon, String label) {
        return "<button class=\"iconbtn\" name=\"_action\" value=\"" + Http.esc(action) + "\" aria-label=\""
                + Http.esc(label) + "\" title=\"" + Http.esc(label) + "\">" + Icons.icon(icon) + "</button>";
    }

    private static String datalist(String id, List<String> options) {
        StringBuilder sb = new StringBuilder("<datalist id=\"").append(id).append("\">");
        for (String o : options) {
            sb.append("<option value=\"").append(Http.esc(o)).append("\">");
        }
        return sb.append("</datalist>").toString();
    }

    private static String datalistId(String source) {
        return switch (source == null ? "" : source) {
            case "entity" -> "dl-entity";
            case "material" -> "dl-material";
            case "npc" -> "dl-npc";
            case "quest" -> "dl-quest";
            case "world" -> "dl-world";
            default -> null;
        };
    }

    /** Datalists communes à émettre une fois par page d'éditeur (dans le {@code <form>}). */
    public static String sharedDatalists(RefData ref) {
        RefData r = ref == null ? RefData.empty() : ref;
        return datalist("dl-entity", RefData.ENTITIES)
                + datalist("dl-material", RefData.MATERIALS)
                + datalist("dl-npc", r.npcs())
                + datalist("dl-quest", r.quests())
                + datalist("dl-world", r.worlds());
    }

    private static Map<String, String> blankRow(String kind) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kind", kind);
        return m;
    }

    private static <T> void move(List<T> list, int i, String dir) {
        int j = dir.equals("up") ? i - 1 : i + 1;
        if (i >= 0 && i < list.size() && j >= 0 && j < list.size()) {
            java.util.Collections.swap(list, i, j);
        }
    }

    private static int intAt(String[] parts, int i) {
        try {
            return i < parts.length ? Integer.parseInt(parts[i]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isOn(String v) {
        return "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v);
    }

    private static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String l : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String t = l.trim();
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (char c : chars.toCharArray()) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    private static String varsText(Map<String, String> vars) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : vars.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return sb.toString().strip();
    }

    private static String preview(List<String> values, int max) {
        List<String> shown = values.size() > max ? values.subList(0, max) : values;
        return String.join(", ", shown) + (values.size() > max ? "…" : "");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
