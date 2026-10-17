package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.content.Descriptors;
import com.lodygames.rpgquest.panel.content.DialogueCatalogCoherence;
import com.lodygames.rpgquest.panel.content.DialogueDraft;
import com.lodygames.rpgquest.panel.content.DialogueForm;
import com.lodygames.rpgquest.panel.content.DialogueServerCopy;
import com.lodygames.rpgquest.panel.content.DialogueYaml;
import com.lodygames.rpgquest.panel.http.Http;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dialogues, version simple (issue #332) : la liste, la création, l'éditeur « Le personnage dit… /
 * Le joueur peut répondre… / Ensuite… », l'aperçu et la mise en jeu.
 *
 * <p><strong>Une seule façon de modifier un dialogue.</strong> L'éditeur travaille sur le fichier
 * enregistré par PlugAdmin. « Enregistrer » l'écrit ; « Mettre en jeu » le copie sur le serveur par
 * la publication à chaud existante (#47) — aucun second mécanisme. L'ancien écran, avec les
 * diagnostics et l'édition directe sur le serveur, reste disponible sous « Détails techniques ».</p>
 *
 * <p><strong>Aucune perte.</strong> Tout ce que porte le fichier traverse le formulaire
 * ({@link DialogueForm}) : conditions, effets, ordre, champs que l'éditeur ne sait pas nommer. Un
 * fichier que l'éditeur ne sait pas relire entièrement s'ouvre en lecture seule, avec la raison.</p>
 *
 * <p>Classe de rendu pure : elle reçoit tout ce qu'elle affiche ({@link Ctx}) et n'accède à rien.</p>
 */
public final class DialoguePages {

    /** Un personnage, tel que la liste des PNJ le connaît. */
    public record Npc(String id, String name, String dialogueKey) {
    }

    /** Une quête, par son titre. */
    public record Quest(String id, String title) {
    }

    /** Où en est le dialogue enregistré par rapport au jeu. */
    public enum Live {
        /** Le jeu porte exactement ce qui est enregistré. */
        UP_TO_DATE,
        /** Enregistré, pas encore envoyé (ou modifié depuis). */
        NOT_LIVE,
        /** Mise en jeu demandée, pas encore terminée. */
        PUBLISHING,
        /** La dernière mise en jeu de cette version a échoué. */
        FAILED,
        /** Le serveur a une autre version, modifiée hors de PlugAdmin. */
        CONFLICT,
        /** Seulement sur le serveur : rien à mettre en jeu depuis PlugAdmin. */
        SERVER_ONLY,
        /** État du serveur non relevé. */
        UNKNOWN
    }

    /**
     * @param detail       ce qu'il faut savoir (motif d'un échec), ou vide
     * @param sourceSha    empreinte du fichier enregistré, vide s'il n'existe pas
     * @param expectedDev  empreinte attendue sur le serveur pour une mise en jeu sûre
     * @param when         date du dernier événement connu (mise en jeu), ou {@code null}
     */
    public record LiveState(Live live, String detail, String sourceSha, String expectedDev, Instant when) {
        public static LiveState unknown() {
            return new LiveState(Live.UNKNOWN, "", "", "", null);
        }
    }

    /** Tout ce que les pages affichent, préparé par l'appelant. */
    public record Ctx(String agentId, List<Npc> npcs, List<Quest> quests, Set<String> serverKeys,
                      boolean npcsSurveyed, java.util.function.Function<String, LiveState> live,
                      List<Catalog> catalogs, List<Model> models, boolean catalogsSurveyed) {

        /** Sans catalogues relevés (écrans qui n'en ont pas besoin, versions antérieures à #430). */
        public Ctx(String agentId, List<Npc> npcs, List<Quest> quests, Set<String> serverKeys,
                   boolean npcsSurveyed, java.util.function.Function<String, LiveState> live) {
            this(agentId, npcs, quests, serverKeys, npcsSurveyed, live, List.of(), List.of(), false);
        }

        /** Modèles de PNJ récurrents dont les apparitions disent ce dialogue (npc_id = dialogue). */
        List<Model> models(String key) {
            List<Model> out = new ArrayList<>();
            for (Model m : models) {
                if (key.equals(DialogueYaml.plainId(m.npcId()))) {
                    out.add(m);
                }
            }
            return out;
        }

        /** Catalogues des modèles qui portent ce dialogue, sans doublon, dans l'ordre. */
        List<String> modelCatalogs(String key) {
            List<String> out = new ArrayList<>();
            for (Model m : models(key)) {
                for (String c : m.catalogs()) {
                    if (!out.contains(c)) {
                        out.add(c);
                    }
                }
            }
            return out;
        }

        Catalog catalog(String id) {
            for (Catalog c : catalogs) {
                if (c.id().equals(id)) {
                    return c;
                }
            }
            return null;
        }

        String catalogName(String id) {
            Catalog c = catalog(id);
            return c == null || c.name().isBlank() ? MiniText.prettifyId(id) : MiniText.plain(c.name());
        }

        Map<String, List<String>> catalogQuests() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            catalogs.forEach(c -> out.put(c.id(), c.quests()));
            return out;
        }

        List<Npc> users(String key) {
            List<Npc> out = new ArrayList<>();
            for (Npc n : npcs) {
                if (key.equals(n.dialogueKey())) {
                    out.add(n);
                }
            }
            return out;
        }

        String questTitle(String id) {
            String plain = DialogueYaml.plainId(id);
            for (Quest q : quests) {
                if (DialogueYaml.plainId(q.id()).equals(plain)) {
                    return q.title();
                }
            }
            return MiniText.prettifyId(plain);
        }
    }

    /**
     * Issue #430 — une liste de quêtes (catalogue #369), désignée par son nom.
     *
     * @param quests quêtes de la liste réellement proposables (activées et existantes)
     * @param usedBy modèles de PNJ récurrents qui la portent
     */
    public record Catalog(String id, String name, boolean enabled, List<String> quests, List<String> usedBy) {
    }

    /** Issue #430 — un modèle de PNJ récurrent (#13) : ses apparitions disent le dialogue {@code npcId}. */
    public record Model(String id, String name, String npcId, boolean enabled, List<String> catalogs) {
    }

    /** Ce que la session peut faire. */
    public record Rights(boolean write, boolean publish, boolean delete) {
    }

    /** Un dialogue de la liste : clé, contenu relu (ou {@code null} s'il n'est que sur le serveur). */
    public record Row(String key, DialogueDraft draft, boolean parseOk) {
    }

    /**
     * L'état de l'éditeur.
     *
     * @param readOnly    raison d'une ouverture en lecture seule, ou vide
     * @param notice      message de résultat (geste, enregistrement), ou vide
     * @param noticeTone  {@code ok}, {@code info}, {@code warn} ou {@code err}
     * @param errors      messages par emplacement ({@link #nodeKey} / {@link #replyKey}, ou « dialogue »)
     * @param dirty       le formulaire porte des modifications non enregistrées
     * @param hasComments le fichier contient des notes « # » que l'enregistrement ne garde pas
     * @param serverCopy  issue #430 — pour un dialogue présent seulement sur le serveur : où en est
     *                    sa copie vers la source ; {@code null} sinon
     */
    public record Editing(String slug, DialogueDraft draft, String expectedSha, String readOnly,
                          String notice, String noticeTone, Map<String, List<String>> errors,
                          boolean dirty, boolean hasComments, DialogueServerCopy serverCopy) {

        public Editing(String slug, DialogueDraft draft, String expectedSha, String readOnly,
                       String notice, String noticeTone, Map<String, List<String>> errors,
                       boolean dirty, boolean hasComments) {
            this(slug, draft, expectedSha, readOnly, notice, noticeTone, errors, dirty, hasComments, null);
        }
    }

    private DialoguePages() {
    }

    // =====================================================================================
    //  Liste
    // =====================================================================================

    public static String list(String csrf, Ctx ctx, Rights rights, List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("dialogues", "Dialogues",
                "Ce que disent les personnages, et ce que le joueur peut leur répondre.",
                rights.write() ? Ui.primaryLink("/dialogues/new", "plus", "Créer un dialogue") : ""));

        sb.append("<div class=\"npc-catbar\">")
                .append("<form method=\"post\" action=\"/dialogues/refresh\" data-submit-once>")
                .append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId()))
                .append("<button class=\"btn secondary sm\" type=\"submit\">").append(Icons.icon("refresh"))
                .append("Actualiser</button></form>")
                .append("<a class=\"btn secondary sm ms-auto\" href=\"/dialogues/diagnostic\">")
                .append(Icons.icon("wrench")).append("Détails techniques</a></div>");

        if (rows.isEmpty()) {
            sb.append(Ui.empty("dialogues", "Aucun dialogue pour l'instant. « Créer un dialogue » pour "
                    + "faire parler un personnage."));
            return sb.toString();
        }
        sb.append("<div class=\"npc-searchbar\"><input class=\"form-control npc-search\" type=\"search\" ")
                .append("placeholder=\"Chercher un dialogue ou un personnage…\" aria-label=\"Chercher\" ")
                .append("data-filter-input=\"dialogues\"></div>");
        sb.append("<p class=\"count-note\" data-count-note data-noun=\"dialogue\">").append(rows.size())
                .append(" dialogue(s)</p>");
        sb.append("<ul class=\"dlg-list\">");
        Map<String, Integer> sameTitle = new LinkedHashMap<>();
        for (Row row : rows) {
            sameTitle.merge(title(row.key(), row.draft(), ctx.users(row.key())), 1, Integer::sum);
        }
        for (Row row : rows) {
            sb.append(listRow(ctx, rights, row,
                    sameTitle.getOrDefault(title(row.key(), row.draft(), ctx.users(row.key())), 0) > 1));
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static String listRow(Ctx ctx, Rights rights, Row row, boolean ambiguous) {
        List<Npc> users = ctx.users(row.key());
        String title = title(row.key(), row.draft(), users);
        LiveState live = ctx.live().apply(row.key());
        StringBuilder who = new StringBuilder();
        for (Npc n : users) {
            who.append(who.length() == 0 ? "" : ", ").append(MiniText.plain(n.name()));
        }
        String search = (title + " " + who + " " + row.key()).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder("<li class=\"dlg-row\" data-filter-item=\"dialogues\" data-filter-text=\"")
                .append(Http.esc(search)).append("\">");
        sb.append("<div class=\"dlg-row-main\"><a class=\"dlg-row-title\" href=\"/dialogues/edit/")
                .append(Http.esc(row.key())).append("\">").append(Http.esc(title)).append("</a>");
        if (ambiguous) {
            // Deux dialogues portent le même nom : la référence les distingue.
            sb.append("<span class=\"dlg-row-sum\">réf. ").append(Http.esc(row.key())).append("</span>");
        }
        sb.append("<span class=\"dlg-row-who\">").append(users.isEmpty()
                ? "<span class=\"muted\">Aucun personnage</span>"
                : Icons.icon("npc") + Http.esc(who.toString())).append("</span>");
        if (row.draft() != null) {
            sb.append("<span class=\"dlg-row-sum\">").append(Http.esc(summary(ctx, row.draft()))).append("</span>");
        }
        sb.append("</div>");
        sb.append("<div class=\"dlg-row-side\">").append(liveChip(row.draft() == null ? serverOnly() : live,
                row.draft() != null && !row.parseOk()));
        sb.append("<a class=\"btn secondary sm\" href=\"/dialogues/edit/").append(Http.esc(row.key())).append("\">")
                .append(row.draft() != null && rights.write() ? "Modifier" : "Voir").append("</a></div>");
        return sb.append("</li>").toString();
    }

    private static LiveState serverOnly() {
        return new LiveState(Live.SERVER_ONLY, "", "", "", null);
    }

    /** « 3 répliques · propose « Herbes » ». */
    static String summary(Ctx ctx, DialogueDraft d) {
        List<String> offers = new ArrayList<>();
        for (DialogueDraft.Node n : d.nodes) {
            for (DialogueDraft.Choice c : n.choices) {
                for (Map<String, String> a : c.actions) {
                    String t = null;
                    if ("START_QUEST".equalsIgnoreCase(a.getOrDefault("type", ""))) {
                        t = "« " + MiniText.plain(ctx.questTitle(a.getOrDefault("quest", ""))) + " »";
                    } else if (DialogueForm.CATALOG_ACTION.equalsIgnoreCase(a.getOrDefault("type", ""))) {
                        t = "une quête au hasard dans une liste";
                    }
                    if (t != null && !offers.contains(t)) {
                        offers.add(t);
                    }
                }
            }
        }
        String s = d.nodes.size() + (d.nodes.size() > 1 ? " répliques" : " réplique");
        return offers.isEmpty() ? s : s + " · propose " + String.join(", ", offers);
    }

    /** Nom lisible d'un dialogue : « Dialogue de Lucien ». */
    static String title(String key, DialogueDraft d, List<Npc> users) {
        if (!users.isEmpty()) {
            List<String> names = new ArrayList<>();
            users.forEach(n -> names.add(MiniText.plain(n.name())));
            return "Dialogue " + of(String.join(" et ", names));
        }
        DialogueDraft.Node start = d == null ? null : d.startNode();
        if (start != null && start.speaker != null && !start.speaker.isBlank()) {
            return "Dialogue " + of(MiniText.plain(start.speaker));
        }
        return MiniText.prettifyId(key);
    }

    /** « de Lucien », « d'Elias » : l'élision devant une voyelle ou un h. */
    static String of(String name) {
        String n = name == null ? "" : name.trim();
        return !n.isEmpty() && "aeiouyhéèêëàâäîïôöûüAEIOUYHÉÈÊËÀÂÄÎÏÔÖÛÜ".indexOf(n.charAt(0)) >= 0 ? "d'" + n : "de " + n;
    }

    private static String liveChip(LiveState live, boolean unreadable) {
        if (unreadable) {
            return chip("warn", "À vérifier");
        }
        return switch (live.live()) {
            case UP_TO_DATE -> chip("ok", "À jour en jeu");
            case NOT_LIVE -> chip("warn", "Pas encore en jeu");
            case PUBLISHING -> chip("info", "Mise en jeu en cours…");
            case FAILED -> chip("err", "Mise en jeu échouée");
            case CONFLICT -> chip("err", "Modifié sur le serveur");
            case SERVER_ONLY -> chip("info", "Seulement sur le serveur");
            case UNKNOWN -> chip("neutral", "État en jeu inconnu");
        };
    }

    private static String chip(String tone, String label) {
        return "<span class=\"dlg-chip tone-" + tone + "\">" + Http.esc(label) + "</span>";
    }

    // =====================================================================================
    //  Création
    // =====================================================================================

    /**
     * Formulaire de création : pour quel personnage, et sa première réplique.
     *
     * @param npcPrefill personnage présélectionné (depuis sa fiche), ou vide
     * @param error      message à afficher près du formulaire, ou vide
     */
    public static String create(String csrf, Ctx ctx, String npcPrefill, String name, String firstLine,
                                String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("dialogues", "Créer un dialogue",
                "Choisissez qui parle et ce qu'il dit en premier. Vous ajouterez les réponses ensuite.",
                "<a class=\"btn secondary\" href=\"/dialogues\">" + Icons.icon("back") + "Dialogues</a>"));
        sb.append("<form method=\"post\" action=\"/dialogues/create\" class=\"card dlg-card dlg-create\" data-submit-once>");
        sb.append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId()));
        if (error != null && !error.isBlank()) {
            sb.append("<p class=\"field-error\" role=\"alert\">").append(Icons.icon("warning"))
                    .append(Http.esc(error)).append("</p>");
        }
        sb.append("<label for=\"dlg-new-npc\">Quel personnage parle ?</label>");
        sb.append("<select class=\"form-select\" id=\"dlg-new-npc\" name=\"npc\">");
        sb.append("<option value=\"\">Aucun pour l'instant</option>");
        for (Npc n : sortedByName(ctx.npcs())) {
            sb.append("<option value=\"").append(Http.esc(n.id())).append("\"")
                    .append(n.id().equals(npcPrefill) ? " selected" : "").append(">")
                    .append(Http.esc(MiniText.plain(n.name())))
                    .append(n.dialogueKey().isEmpty() ? "" : " — a déjà un dialogue").append("</option>");
        }
        sb.append("</select>");
        sb.append("<p class=\"form-text\">S'il a déjà un dialogue, le nouveau le remplacera quand vous "
                + "l'aurez mis en jeu.</p>");
        sb.append("<label for=\"dlg-new-name\">Nom du dialogue</label>");
        sb.append("<input class=\"form-control\" id=\"dlg-new-name\" name=\"name\" maxlength=\"48\" ")
                .append("placeholder=\"Par exemple : Lucien au marché\" value=\"").append(Http.esc(nz(name))).append("\">");
        sb.append("<p class=\"form-text\">Facultatif si vous avez choisi un personnage.</p>");
        sb.append("<label for=\"dlg-new-line\">Le personnage dit…</label>");
        sb.append("<textarea class=\"form-control\" id=\"dlg-new-line\" name=\"first_line\" rows=\"2\" maxlength=\"512\" ")
                .append("placeholder=\"Salut !\">").append(Http.esc(nz(firstLine))).append("</textarea>");
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" data-busy-label=\"Création…\">")
                .append(Icons.icon("plus")).append("Créer le dialogue</button>")
                .append("<a class=\"btn secondary\" href=\"/dialogues\">Annuler</a></div>");
        sb.append("</form>");
        return sb.toString();
    }

    private static List<Npc> sortedByName(List<Npc> npcs) {
        List<Npc> out = new ArrayList<>(npcs);
        out.sort((a, b) -> MiniText.plain(a.name()).compareToIgnoreCase(MiniText.plain(b.name())));
        return out;
    }

    // =====================================================================================
    //  Éditeur
    // =====================================================================================

    /** Clé d'erreur d'une réplique (index dans le fichier). */
    public static String nodeKey(int i) {
        return "n" + i;
    }

    /** Clé d'erreur d'une réponse. */
    public static String replyKey(int i, int j) {
        return "n" + i + "c" + j;
    }

    public static String editor(String csrf, Ctx ctx, Rights rights, Editing e) {
        DialogueDraft d = e.draft();
        List<Npc> users = ctx.users(e.slug());
        String title = title(e.slug(), d, users);
        boolean editable = rights.write() && e.readOnly().isEmpty();
        StringBuilder sb = new StringBuilder();
        // Issue #465 : un dialogue enregistré en source peut être réécrit par une IA externe, puis
        // réimporté avec relecture des changements.
        String aiEdit = rights.write() && e.serverCopy() == null && !e.expectedSha().isEmpty()
                ? "<a class=\"btn secondary\" href=\"/dialogues/ai/" + Http.esc(e.slug()) + "\">"
                        + Icons.icon("docs") + "Modifier avec une IA…</a>"
                : "";
        sb.append(Ui.pageHeader("dialogues", title, "",
                "<a class=\"btn secondary\" href=\"/dialogues\">" + Icons.icon("back") + "Dialogues</a>" + aiEdit));

        sb.append(who(csrf, ctx, rights, e.slug(), users));
        sb.append(liveBar(csrf, ctx, rights, e));
        if (e.serverCopy() != null) {
            sb.append(serverCopy(csrf, ctx, rights, e.slug(), e.serverCopy()));
        }

        if (!e.notice().isEmpty()) {
            sb.append(Ui.banner(e.noticeTone(), Http.esc(e.notice())));
        }
        if (!e.readOnly().isEmpty()) {
            sb.append(Ui.banner("warn", Http.esc(e.readOnly())));
        }
        List<String> general = e.errors().getOrDefault("dialogue", List.of());
        for (String msg : general) {
            sb.append("<p class=\"field-error\" role=\"alert\">").append(Icons.icon("warning"))
                    .append(Http.esc(msg)).append("</p>");
        }

        sb.append(coherence(ctx, d, e.slug()));

        sb.append("<form method=\"post\" action=\"/dialogues/save\" class=\"dlg-editor\" novalidate")
                .append(e.dirty() ? " data-dirty=\"1\"" : "").append(" data-dirty-form>");
        sb.append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId()))
                .append(hidden("slug", e.slug())).append(hidden("expected_sha", e.expectedSha()));
        // Entrée = « Enregistrer », jamais un geste d'édition pris au hasard parmi les boutons.
        sb.append("<button type=\"submit\" name=\"_op\" value=\"save\" class=\"default-submit\" tabindex=\"-1\" "
                + "aria-hidden=\"true\"").append(editable ? "" : " disabled").append("></button>");

        Map<String, String> f = DialogueForm.fields(d);
        sb.append(hidden("d_id", f.get("d_id"))).append(hidden("n_count", f.get("n_count")));

        List<DialogueDraft.Node> order = DialogueForm.displayOrder(d);
        Set<String> unreachable = DialogueForm.unreachable(d);
        for (DialogueDraft.Node node : order) {
            int i = d.nodes.indexOf(node);
            sb.append(nodeCard(ctx, d, i, node, f, editable, unreachable.contains(node.id), e.errors()));
        }
        // Une réplique hors de l'ordre d'affichage n'existe pas : displayOrder les couvre toutes.
        if (editable) {
            sb.append("<div class=\"dlg-addnode\"><button class=\"btn secondary\" type=\"submit\" name=\"_op\" ")
                    .append("value=\"addnode\" formnovalidate>").append(Icons.icon("plus"))
                    .append("Ajouter une réplique</button></div>");
        }

        sb.append(dialogueSettings(d, f, editable, e.hasComments()));

        if (editable) {
            sb.append("<div class=\"dlg-savebar\">")
                    .append("<span class=\"dlg-dirty\" data-dirty-note").append(e.dirty() ? "" : " hidden").append(">")
                    .append(Icons.icon("warning")).append("Modifications non enregistrées</span>")
                    .append("<button class=\"btn\" type=\"submit\" name=\"_op\" value=\"save\" data-busy-label=\"Enregistrement…\">")
                    .append(Icons.icon("save")).append("Enregistrer</button>");
            if (rights.publish()) {
                sb.append("<button class=\"btn secondary\" type=\"submit\" name=\"_op\" value=\"save_publish\" ")
                        .append("data-busy-label=\"Enregistrement…\">").append(Icons.icon("deploy"))
                        .append("Enregistrer et mettre en jeu</button>");
            }
            sb.append("</div>");
        }
        sb.append("</form>");

        sb.append(preview(ctx, d));

        if (rights.delete()) {
            sb.append("<p class=\"dlg-delete-link\"><a class=\"btn secondary sm npc-danger\" href=\"/dialogues/delete?slug=")
                    .append(Http.esc(e.slug())).append("\">").append(Icons.icon("trash"))
                    .append("Supprimer ce dialogue…</a></p>");
        }
        sb.append("<p class=\"dlg-tech\"><a href=\"/dialogues/diagnostic\">Détails techniques et diagnostics</a></p>");
        return sb.toString();
    }

    /** « Qui parle ? » : le ou les personnages qui utilisent ce dialogue, et le moyen d'en associer un. */
    private static String who(String csrf, Ctx ctx, Rights rights, String slug, List<Npc> users) {
        StringBuilder sb = new StringBuilder("<div class=\"dlg-who\">");
        List<Model> models = ctx.models(slug);
        if (users.isEmpty() && models.isEmpty()) {
            sb.append("<span class=\"muted\">").append(ctx.npcsSurveyed()
                    ? "Aucun personnage n'utilise ce dialogue." + (ctx.catalogsSurveyed() ? ""
                            : " (Les PNJ récurrents ne sont pas encore relus : « Actualiser » sur Catalogues de quêtes.)")
                    : "Personnages non relus : « Actualiser » sur la liste des dialogues.").append("</span>");
        } else if (users.isEmpty()) {
            sb.append("<span class=\"muted\">Aucun personnage fixe : seulement des PNJ récurrents.</span>");
        }
        for (Model m : models) {
            // #430 : un modèle de rencontre n'est pas un personnage déjà placé ; on dit ce qu'il est.
            sb.append("<span class=\"dlg-model\">").append(Icons.icon("npc")).append("PNJ récurrent : <a href=\"/npcs/encounters\">")
                    .append(Http.esc(m.name().isBlank() ? m.id() : MiniText.plain(m.name()))).append("</a>")
                    .append(m.enabled() ? "" : " <span class=\"muted\">(n'apparaît plus)</span>");
            if (m.catalogs().isEmpty()) {
                sb.append(" <span class=\"muted\">— aucune liste de quêtes</span>");
            } else {
                sb.append(" — liste").append(m.catalogs().size() > 1 ? "s" : "").append(" : ");
                for (int k = 0; k < m.catalogs().size(); k++) {
                    String c = m.catalogs().get(k);
                    sb.append(k == 0 ? "" : ", ").append("<a href=\"").append(Http.esc(catalogUrl(ctx, c))).append("\">")
                            .append(Http.esc(ctx.catalogName(c))).append("</a>");
                }
            }
            sb.append("</span>");
        }
        if (!users.isEmpty()) {
            sb.append("<span>").append(Icons.icon("npc")).append("Personnage : ");
            for (int k = 0; k < users.size(); k++) {
                Npc n = users.get(k);
                sb.append(k == 0 ? "" : ", ").append("<a href=\"/npcs?focus=").append(Http.esc(n.id()))
                        .append("\">").append(Http.esc(MiniText.plain(n.name()))).append("</a>");
            }
            sb.append("</span>");
        }
        if (rights.write() && !ctx.npcs().isEmpty()) {
            sb.append("<details class=\"dlg-link\"><summary>Donner ce dialogue à un personnage</summary>");
            sb.append("<form method=\"post\" action=\"/dialogues/link\" data-submit-once class=\"dlg-inline\">")
                    .append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId())).append(hidden("slug", slug));
            sb.append("<label for=\"dlg-link-npc\" class=\"visually-hidden\">Personnage</label>")
                    .append("<select class=\"form-select\" id=\"dlg-link-npc\" name=\"npc\" required>")
                    .append("<option value=\"\">Choisir un personnage…</option>");
            for (Npc n : sortedByName(ctx.npcs())) {
                if (!slug.equals(n.dialogueKey())) {
                    sb.append("<option value=\"").append(Http.esc(n.id())).append("\">")
                            .append(Http.esc(MiniText.plain(n.name()))).append("</option>");
                }
            }
            sb.append("</select><button class=\"btn secondary\" type=\"submit\">Associer</button></form>");
            sb.append("<p class=\"form-text\">Quand on lui parlera, il dira ce dialogue au lieu de son "
                    + "dialogue actuel.</p></details>");
        }
        return sb.append("</div>").toString();
    }

    /** L'état « en jeu » et le bouton « Mettre en jeu ». Jamais un succès pour une simple demande. */
    private static String liveBar(String csrf, Ctx ctx, Rights rights, Editing e) {
        LiveState live = ctx.live().apply(e.slug());
        String tone;
        String text;
        boolean offerPublish = false;
        boolean offerCheck = false;
        switch (live.live()) {
            case UP_TO_DATE -> {
                tone = "ok";
                text = "À jour en jeu — les joueurs voient la version enregistrée.";
            }
            case NOT_LIVE -> {
                tone = "warn";
                text = "Enregistré — pas encore en jeu.";
                offerPublish = true;
            }
            case PUBLISHING -> {
                tone = "info";
                text = "Mise en jeu en cours… La page se met à jour quand le serveur a répondu.";
            }
            case FAILED -> {
                tone = "err";
                text = "Mise en jeu échouée" + (live.detail().isEmpty() ? "." : " : " + live.detail())
                        + " Corrigez si besoin, puis réessayez.";
                offerPublish = true;
            }
            case CONFLICT -> {
                tone = "err";
                text = "Le serveur a une autre version de ce dialogue, modifiée en dehors de PlugAdmin. "
                        + "Comparez-les dans « Détails techniques » avant de la remplacer.";
            }
            case SERVER_ONLY -> {
                tone = "info";
                text = "Ce dialogue n'existe que sur le serveur : copiez-le dans PlugAdmin pour le modifier.";
            }
            default -> {
                tone = "info";
                text = live.sourceSha().isEmpty() ? "On ne sait pas encore ce que le jeu contient."
                        : "Enregistré — on ne sait pas encore si le jeu a cette version.";
                offerCheck = true;
                offerPublish = !live.sourceSha().isEmpty();
            }
        }
        StringBuilder sb = new StringBuilder("<div class=\"dlg-live tone-").append(tone).append("\" role=\"status\">");
        sb.append("<span>").append(Http.esc(text));
        if (live.when() != null && (live.live() == Live.FAILED || live.live() == Live.UP_TO_DATE)) {
            sb.append(" <span class=\"muted\">(").append(Http.esc(ActionView.relativeTime(live.when(), Instant.now())))
                    .append(")</span>");
        }
        sb.append("</span>");
        if (offerPublish && rights.publish() && !e.dirty()) {
            sb.append("<form method=\"post\" action=\"/dialogues/publish\" data-submit-once>")
                    .append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId()))
                    .append(hidden("slug", e.slug())).append(hidden("expected_sha", live.sourceSha()))
                    .append("<button class=\"btn sm\" type=\"submit\" data-busy-label=\"Envoi…\">")
                    .append(Icons.icon("deploy")).append(live.live() == Live.FAILED ? "Réessayer" : "Mettre en jeu")
                    .append("</button></form>");
        }
        if (offerCheck) {
            sb.append("<form method=\"post\" action=\"/dialogues/refresh\" data-submit-once>")
                    .append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId()))
                    .append(hidden("return", "/dialogues/edit/" + e.slug()))
                    .append("<button class=\"btn secondary sm\" type=\"submit\">Vérifier</button></form>");
        }
        return sb.append("</div>").toString();
    }

    private static String nodeCard(Ctx ctx, DialogueDraft d, int i, DialogueDraft.Node node,
                                   Map<String, String> f, boolean editable, boolean unreachable,
                                   Map<String, List<String>> errors) {
        int number = DialogueForm.number(d, node.id);
        boolean isStart = node.id.equals(d.start);
        StringBuilder sb = new StringBuilder("<section class=\"card dlg-card dlg-node\" id=\"r-")
                .append(Http.esc(node.id)).append("\">");
        sb.append("<h2 class=\"dlg-node-h\">").append(isStart ? "Début de la conversation" : "Réplique " + number);
        if (unreachable) {
            sb.append(" <span class=\"dlg-chip tone-warn\">Aucune réponse n'y mène</span>");
        }
        sb.append("</h2>");
        sb.append(hidden(DialogueForm.node(i, "id"), node.id));
        for (String msg : errors.getOrDefault(nodeKey(i), List.of())) {
            sb.append("<p class=\"field-error\" role=\"alert\">").append(Icons.icon("warning"))
                    .append(Http.esc(msg)).append("</p>");
        }

        String textId = "dlg-n" + i + "-text";
        if (editable) {
            sb.append(StyleField.render(DialogueForm.node(i, "text"), textId, "Le personnage dit…", node.text,
                    false, "", true, false));
        } else {
            sb.append("<p class=\"dlg-says\">").append(MiniText.html(node.text)).append("</p>")
                    .append(hidden(DialogueForm.node(i, "text"), node.text));
        }

        sb.append("<h3 class=\"dlg-replies-h\">Le joueur peut répondre…</h3>");
        sb.append(hidden(DialogueForm.node(i, "c_count"), String.valueOf(node.choices.size())));
        if (node.choices.isEmpty()) {
            sb.append("<p class=\"muted dlg-noreply\">Aucune réponse : la conversation s'arrête après cette réplique.</p>");
        }
        sb.append("<ol class=\"dlg-replies\">");
        for (int j = 0; j < node.choices.size(); j++) {
            sb.append(replyItem(ctx, d, i, j, node.choices.get(j), f, editable, errors));
        }
        sb.append("</ol>");
        if (editable) {
            sb.append("<button class=\"btn secondary sm\" type=\"submit\" name=\"_op\" value=\"addreply:")
                    .append(i).append("\" formnovalidate>").append(Icons.icon("plus")).append("Ajouter une réponse</button>");
        }

        sb.append("<details class=\"dlg-more\"><summary>Réglages de la réplique</summary>");
        sb.append("<label for=\"dlg-n").append(i).append("-speaker\">Nom affiché au-dessus de la réplique</label>")
                .append("<input class=\"form-control\" id=\"dlg-n").append(i).append("-speaker\" name=\"")
                .append(DialogueForm.node(i, "speaker")).append("\" maxlength=\"128\" value=\"")
                .append(Http.esc(node.speaker)).append("\"").append(editable ? "" : " readonly").append(">");
        if (editable && !isStart && d.nodes.size() > 1) {
            sb.append("<button class=\"btn secondary sm npc-danger dlg-rm\" type=\"submit\" name=\"_op\" value=\"delnode:")
                    .append(i).append("\" formnovalidate>").append(Icons.icon("trash")).append("Retirer cette réplique</button>");
        }
        sb.append("</details>");
        return sb.append("</section>").toString();
    }

    private static String replyItem(Ctx ctx, DialogueDraft d, int i, int j, DialogueDraft.Choice c,
                                    Map<String, String> f, boolean editable, Map<String, List<String>> errors) {
        DialogueForm.Reply r = DialogueForm.reply(c);
        String base = "dlg-n" + i + "-c" + j;
        StringBuilder sb = new StringBuilder("<li class=\"dlg-reply\" id=\"r-").append(Http.esc(nodeIdOf(d, i)))
                .append("-c").append(j).append("\">");
        for (String msg : errors.getOrDefault(replyKey(i, j), List.of())) {
            sb.append("<p class=\"field-error\" role=\"alert\">").append(Icons.icon("warning"))
                    .append(Http.esc(msg)).append("</p>");
        }
        for (String k : List.of("qpos", "cpos", "wend", "onext")) {
            sb.append(hidden(DialogueForm.reply(i, j, k), f.get(DialogueForm.reply(i, j, k))));
        }
        String dis = editable ? "" : " disabled";

        sb.append("<label class=\"visually-hidden\" for=\"").append(base).append("-text\">Réponse du joueur</label>");
        sb.append("<input class=\"form-control dlg-reply-text\" id=\"").append(base).append("-text\" name=\"")
                .append(DialogueForm.reply(i, j, "text")).append("\" maxlength=\"512\" placeholder=\"Ce que dit le joueur\" value=\"")
                .append(Http.esc(r.text())).append("\"").append(editable ? "" : " readonly").append(">");

        sb.append("<div class=\"dlg-then\">");
        sb.append("<div><label for=\"").append(base).append("-next\">Ensuite…</label>");
        sb.append("<select class=\"form-select\" id=\"").append(base).append("-next\" name=\"")
                .append(DialogueForm.reply(i, j, "next")).append("\"").append(dis).append(">");
        sb.append(option(DialogueForm.END, r.next(), "Terminer la conversation"));
        boolean found = DialogueForm.END.equals(r.next());
        for (DialogueDraft.Node target : DialogueForm.displayOrder(d)) {
            String label = "Le personnage répond : " + (target.id.equals(d.start) ? "le début"
                    : "réplique " + DialogueForm.number(d, target.id))
                    + excerptSuffix(target.text);
            sb.append(option(target.id, r.next(), label));
            found |= target.id.equals(r.next());
        }
        if (!found) {
            sb.append(option(r.next(), r.next(), "Réplique introuvable — choisissez une suite"));
        }
        if (editable) {
            sb.append(option(DialogueForm.NEW, r.next(), "Une nouvelle réplique…"));
        }
        sb.append("</select></div>");

        sb.append("<div><label for=\"").append(base).append("-qa\">Et aussi…</label>");
        sb.append("<select class=\"form-select\" id=\"").append(base).append("-qa\" name=\"")
                .append(DialogueForm.reply(i, j, "qa")).append("\" data-switch=\"").append(base).append("-q\"")
                .append(dis).append(">");
        sb.append(option("", r.questAction(), "Rien de plus"));
        sb.append(option("START_QUEST", r.questAction(), "Proposer une quête précise"));
        sb.append(option(DialogueForm.CATALOG_ACTION, r.questAction(), "Proposer une quête au hasard dans une liste"));
        sb.append(option("ADVANCE_QUEST", r.questAction(), "Faire avancer une quête"));
        sb.append(option("TURN_IN_QUEST", r.questAction(), "Rendre une quête (donne les récompenses)"));
        sb.append("</select></div></div>");

        sb.append("<div class=\"dlg-quest\" data-switch-for=\"").append(base).append("-q\" data-switch-value=\"START_QUEST ADVANCE_QUEST TURN_IN_QUEST\"")
                .append(r.questAction().isEmpty() ? " hidden" : "").append(">");
        sb.append(questPicker(ctx, base + "-qid", DialogueForm.reply(i, j, "qid"), r.questId(), editable,
                !DialogueForm.QUEST_ACTIONS.contains(r.questAction())));
        sb.append("</div>");
        boolean catalogOn = DialogueForm.CATALOG_ACTION.equals(r.questAction());
        sb.append("<div class=\"dlg-quest\" data-switch-for=\"").append(base).append("-q\" data-switch-value=\"")
                .append(DialogueForm.CATALOG_ACTION).append("\"").append(catalogOn ? "" : " hidden").append(">");
        sb.append(catalogPicker(ctx, d.id, base + "-qcat", DialogueForm.reply(i, j, "qcat"), r.catalogId(), editable,
                !catalogOn));
        sb.append("</div>");

        sb.append(advanced(ctx, d, i, j, r, f, editable));

        if (editable) {
            sb.append("<button class=\"btn secondary sm npc-danger dlg-rm\" type=\"submit\" name=\"_op\" value=\"delreply:")
                    .append(i).append(":").append(j).append("\" formnovalidate>").append(Icons.icon("trash"))
                    .append("Supprimer cette réponse</button>");
        }
        return sb.append("</li>").toString();
    }

    private static String excerptSuffix(String text) {
        String ex = DialogueForm.excerpt(text, 40);
        return ex.isEmpty() ? " (à écrire)" : " — « " + ex + " »";
    }

    /** Une quête choisie par son titre, avec une recherche au-dessus de la liste. */
    private static String questPicker(Ctx ctx, String id, String name, String selected, boolean editable,
                                      boolean disabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("<label for=\"").append(id).append("\">Quelle quête ?</label>");
        if (editable && ctx.quests().size() > 8) {
            sb.append("<input class=\"form-control dlg-qsearch\" type=\"search\" placeholder=\"Chercher une quête…\" ")
                    .append("aria-label=\"Chercher une quête\" data-select-filter=\"").append(id).append("\">");
        }
        sb.append("<select class=\"form-select\" id=\"").append(id).append("\" name=\"").append(name).append("\"")
                .append(disabled ? " disabled" : "").append(editable ? "" : " disabled").append(">");
        sb.append("<option value=\"\">Choisir une quête…</option>");
        boolean found = selected.isEmpty();
        List<Quest> quests = new ArrayList<>(ctx.quests());
        quests.sort((a, b) -> MiniText.plain(a.title()).compareToIgnoreCase(MiniText.plain(b.title())));
        for (Quest q : quests) {
            boolean on = DialogueYaml.plainId(q.id()).equals(DialogueYaml.plainId(selected));
            found |= on;
            sb.append("<option value=\"").append(Http.esc(q.id())).append("\"").append(on ? " selected" : "")
                    .append(">").append(Http.esc(MiniText.plain(q.title()))).append("</option>");
        }
        if (!found) {
            sb.append("<option value=\"").append(Http.esc(selected)).append("\" selected>")
                    .append(Http.esc(ctx.questTitle(selected))).append(" (introuvable)</option>");
        }
        sb.append("</select>");
        if (editable) {
            sb.append("<p class=\"form-text\"><a href=\"/quests/new\" target=\"_blank\" rel=\"noopener\">Créer une quête</a></p>");
        }
        return sb.toString();
    }

    private static String nodeIdOf(DialogueDraft d, int i) {
        return i >= 0 && i < d.nodes.size() ? d.nodes.get(i).id : "";
    }

    /** La fiche d'un catalogue (#414). */
    static String catalogUrl(Ctx ctx, String catalogId) {
        return QuestCatalogPages.PAGE + "?agent=" + java.net.URLEncoder.encode(ctx.agentId(),
                java.nio.charset.StandardCharsets.UTF_8) + "&focus=" + java.net.URLEncoder.encode(catalogId,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** « la liste « X » » ou, sans liste nommée, celles du PNJ récurrent. */
    private static String catalogPhrase(Ctx ctx, String dialogueId, String catalogId) {
        if (!catalogId.isBlank()) {
            return "la liste « " + ctx.catalogName(catalogId) + " »";
        }
        List<String> own = ctx.modelCatalogs(DialogueYaml.plainId(dialogueId));
        if (own.isEmpty()) {
            return "les listes du PNJ récurrent (aucune pour l'instant)";
        }
        List<String> names = new ArrayList<>();
        own.forEach(c -> names.add("« " + ctx.catalogName(c) + " »"));
        return (own.size() > 1 ? "les listes " : "la liste ") + String.join(", ", names);
    }

    /**
     * Issue #430 — « Quelle liste ? » : les catalogues par leur nom. Vide = les listes des modèles
     * de PNJ récurrents qui disent ce dialogue (le défaut du moteur, réutilisable tel quel).
     */
    private static String catalogPicker(Ctx ctx, String dialogueId, String id, String name, String selected,
                                        boolean editable, boolean disabled) {
        String key = DialogueYaml.plainId(dialogueId);
        List<String> own = ctx.modelCatalogs(key);
        StringBuilder sb = new StringBuilder();
        sb.append("<label for=\"").append(id).append("\">Quelle liste ?</label>");
        sb.append("<select class=\"form-select\" id=\"").append(id).append("\" name=\"").append(name).append("\"")
                .append(disabled || !editable ? " disabled" : "").append(">");
        sb.append(option("", selected, own.isEmpty() ? "Les listes de ce PNJ récurrent (aucune pour l'instant)"
                : "Les listes de ce PNJ récurrent : " + String.join(", ", own.stream().map(ctx::catalogName).toList())));
        boolean found = selected.isEmpty();
        List<Catalog> sorted = new ArrayList<>(ctx.catalogs());
        sorted.sort((a, b) -> ctx.catalogName(a.id()).compareToIgnoreCase(ctx.catalogName(b.id())));
        for (Catalog c : sorted) {
            found |= c.id().equals(selected);
            sb.append(option(c.id(), selected, ctx.catalogName(c.id()) + (c.enabled() ? "" : " (désactivée)")));
        }
        if (!found) {
            sb.append(option(selected, selected, MiniText.prettifyId(selected) + " (introuvable)"));
        }
        sb.append("</select>");
        // Les avertissements de la liste choisie : ce qui ferait que le PNJ n'a rien à proposer.
        List<String> warnings = new ArrayList<>();
        List<String> targets = selected.isEmpty() ? own : List.of(selected);
        if (!ctx.catalogsSurveyed()) {
            warnings.add("Les listes de quêtes n'ont pas encore été relues : « Actualiser » sur Catalogues de quêtes.");
        } else if (ctx.catalogs().isEmpty()) {
            warnings.add("Aucune liste de quêtes n'existe encore : créez-en une dans Catalogues de quêtes.");
        } else if (selected.isEmpty() && ctx.models(key).isEmpty()) {
            warnings.add("Aucun PNJ récurrent ne dit ce dialogue : choisissez une liste précise.");
        } else if (selected.isEmpty() && own.isEmpty()) {
            warnings.add("Ce PNJ récurrent n'a aucune liste : choisissez-en une ici, ou associez-en une à ce PNJ.");
        }
        for (String t : targets) {
            Catalog c = ctx.catalog(t);
            if (c == null) {
                if (ctx.catalogsSurveyed()) {
                    warnings.add("La liste « " + MiniText.prettifyId(t) + " » n'existe pas sur le serveur.");
                }
            } else if (!c.enabled()) {
                warnings.add("La liste « " + ctx.catalogName(t) + " » est désactivée : le PNJ dira qu'il n'a rien.");
            } else if (c.quests().isEmpty()) {
                warnings.add("La liste « " + ctx.catalogName(t) + " » ne contient aucune quête proposable.");
            } else if (!selected.isEmpty() && !ctx.models(key).isEmpty()
                    && ctx.models(key).stream().noneMatch(m -> m.catalogs().contains(t))) {
                warnings.add("Cette liste n'est associée à aucun des PNJ récurrents de ce dialogue : elle sera "
                        + "quand même proposée, mais n'apparaîtra pas sur leur fiche.");
            }
        }
        for (String w : warnings) {
            sb.append("<p class=\"form-text dlg-warn\">").append(Icons.icon("warning")).append(Http.esc(w)).append("</p>");
        }
        sb.append("<p class=\"form-text\">Le personnage présente lui-même la quête tirée (titre, but, délai), "
                + "rappelle une tâche en cours ou dit qu'il n'a rien pour le moment. Rien n'est donné avant "
                + "« Commencer (j'accepte) ».");
        if (!selected.isEmpty() && ctx.catalog(selected) != null) {
            sb.append(" <a href=\"").append(Http.esc(catalogUrl(ctx, selected))).append("\">Voir la liste</a>");
        }
        sb.append("</p>");
        return sb.toString();
    }

    /**
     * Issue #430 — ce qui reste lié à une quête précise alors que ce PNJ propose (ou a) une liste.
     * Signalé, jamais corrigé à la place de l'auteur.
     */
    private static String coherence(Ctx ctx, DialogueDraft d, String slug) {
        DialogueCatalogCoherence.Report report = DialogueCatalogCoherence.analyse(d, ctx.modelCatalogs(slug),
                ctx.catalogQuests(), q -> MiniText.plain(ctx.questTitle(q)));
        if (report.empty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<section class=\"card dlg-card dlg-coherence\" id=\"dlg-coherence\">");
        sb.append("<h2 class=\"dlg-node-h\">").append(Icons.icon("warning")).append(report.offersCatalog()
                ? "À vérifier : ces passages parlent d'une quête précise"
                : "Ce PNJ a une liste de quêtes, mais ce dialogue propose des quêtes précises").append("</h2>");
        sb.append("<p class=\"form-text\">").append(report.offersCatalog()
                ? "Ce dialogue propose une quête au hasard dans une liste. Les passages ci-dessous citent une quête "
                        + "de cette liste en particulier : si une autre est tirée, ils deviennent faux (mauvais texte, "
                        + "réponse cachée, quête en double)."
                : "Pour faire passer ce PNJ à sa liste : sur la réponse qui propose la quête, choisissez « Proposer "
                        + "une quête au hasard dans une liste ». Revoyez ensuite les passages ci-dessous, qui citent "
                        + "une quête de la liste en particulier.")
                .append("</p><ul class=\"dlg-coherence-list\">");
        for (DialogueCatalogCoherence.Finding f : report.findings()) {
            int number = DialogueForm.number(d, f.nodeId());
            String where = (number == 1 ? "Début" : "Réplique " + number);
            String anchor = "#r-" + f.nodeId();
            if (f.choice() >= 0) {
                DialogueDraft.Node n = d.nodes.stream().filter(x -> x.id.equals(f.nodeId())).findFirst().orElse(null);
                String text = n == null || f.choice() >= n.choices.size() ? "" : n.choices.get(f.choice()).text;
                where += ", réponse « " + DialogueForm.excerpt(MiniText.plain(text), 40) + " »";
                anchor += "-c" + f.choice();
            }
            sb.append("<li><a href=\"").append(Http.esc(anchor)).append("\">").append(Http.esc(where)).append("</a> : ")
                    .append(Http.esc(f.what())).append(" (« ").append(Http.esc(MiniText.plain(ctx.questTitle(f.quest()))))
                    .append(" »).</li>");
        }
        sb.append("</ul><p class=\"form-text\">Le jeu ne sait pas encore tester « la quête tirée dans la liste » : "
                + "une condition ne peut viser qu'une quête précise. Le plus simple est souvent de laisser la réponse "
                + "« travail » toujours visible (le PNJ dit lui-même s'il n'a rien) et d'écrire des textes qui ne "
                + "nomment pas une quête. Rien n'est modifié tant que vous n'enregistrez pas.</p>");
        return sb.append("</section>").toString();
    }

    /**
     * Issue #430 — « Copier dans PlugAdmin pour modifier » : un dialogue présent seulement sur le
     * serveur, lu par {@code content.dev.read} (#47) et copié octet pour octet dans la source.
     */
    private static String serverCopy(String csrf, Ctx ctx, Rights rights, String slug, DialogueServerCopy copy) {
        StringBuilder sb = new StringBuilder("<section class=\"card dlg-card dlg-import\" id=\"dlg-import\">");
        sb.append("<h2 class=\"dlg-node-h\">").append(Icons.icon("copy")).append("Rendre ce dialogue modifiable</h2>");
        sb.append("<p class=\"form-text\">PlugAdmin n'a pas encore ce dialogue : il vient du serveur. On peut le "
                + "copier ici <strong>tel quel</strong>, sans rien perdre. La copie ne change rien en jeu : le serveur "
                + "garde son fichier tant que vous ne mettez pas une nouvelle version en jeu.</p>");
        if (!rights.write()) {
            sb.append("<p class=\"form-text\">Vous n'avez pas le droit de modifier les dialogues.</p>");
            return sb.append("</section>").toString();
        }
        String readButton = "<form method=\"post\" action=\"/dialogues/import\" data-submit-once>" + hidden("_csrf", csrf)
                + hidden("agent", ctx.agentId()) + hidden("slug", slug) + hidden("_op", "read")
                + "<button class=\"btn secondary sm\" type=\"submit\" data-busy-label=\"Lecture…\">{label}</button></form>";
        switch (copy.status()) {
            case NOT_READ -> sb.append("<p>Étape 1 : lire le fichier sur le serveur.</p>")
                    .append(readButton.replace("{label}", Icons.icon("eye") + "Lire le dialogue sur le serveur"));
            case PENDING -> sb.append("<p role=\"status\">Lecture en cours sur le serveur… Rechargez la page "
                    + "dans un instant.</p>");
            case ABSENT -> sb.append("<p class=\"field-error\">Le serveur n'a plus ce fichier : il a peut-être été "
                    + "retiré. Actualisez la liste des dialogues.</p>")
                    .append(readButton.replace("{label}", "Relire"));
            case TOO_LARGE -> sb.append("<p class=\"field-error\">Ce fichier est trop gros pour être lu depuis "
                    + "PlugAdmin. Il faut le copier à la main dans le dépôt.</p>");
            case CORRUPT -> sb.append("<p class=\"field-error\">Le contenu reçu ne correspond pas à son empreinte : "
                    + "rien n'est copié. Relisez-le.</p>").append(readButton.replace("{label}", "Relire"));
            case STALE -> sb.append("<p class=\"field-error\">Le fichier a changé sur le serveur depuis la lecture : "
                    + "relisez-le avant de le copier.</p>").append(readButton.replace("{label}", "Relire"));
            case READY -> {
                long lines = copy.text().lines().count();
                sb.append("<p>").append(Icons.icon("check")).append("Lu sur le serveur : ").append(lines)
                        .append(lines > 1 ? " lignes" : " ligne").append(", empreinte <code>")
                        .append(Http.esc(copy.shortSha())).append("</code>. La copie sera identique, octet pour octet.</p>");
                if (!copy.editableAfterCopy()) {
                    sb.append("<p class=\"dlg-warn\">").append(Icons.icon("warning"))
                            .append(Http.esc("Attention : l'éditeur simple ne sait pas relire ce dialogue sans perte ("
                                    + String.join(" ; ", copy.editorIssues()) + "). Il sera copié quand même, mais "
                                    + "restera en lecture seule ici : il faudra le modifier à la main."))
                            .append("</p>");
                }
                sb.append("<details class=\"dlg-more\"><summary>Voir le fichier lu</summary><div class=\"codeblock\"><pre>")
                        .append(Http.esc(copy.text())).append("</pre></div></details>");
                sb.append("<form method=\"post\" action=\"/dialogues/import\" data-submit-once>")
                        .append(hidden("_csrf", csrf)).append(hidden("agent", ctx.agentId())).append(hidden("slug", slug))
                        .append(hidden("_op", "import")).append(hidden("expected_dev_sha", copy.devSha()))
                        .append("<div class=\"btnrow\"><button class=\"btn sm\" type=\"submit\" data-busy-label=\"Copie…\">")
                        .append(Icons.icon("save")).append("Copier dans PlugAdmin pour le modifier</button></div></form>");
                sb.append(readButton.replace("{label}", "Relire"));
            }
            default -> {
            }
        }
        return sb.append("</section>").toString();
    }

    /** Conditions et autres effets d'une réponse — repliés, en français. */
    private static String advanced(Ctx ctx, DialogueDraft d, int i, int j, DialogueForm.Reply r,
                                   Map<String, String> f, boolean editable) {
        int count = r.conditions().size() + r.otherEffects().size();
        StringBuilder sb = new StringBuilder("<details class=\"dlg-more\"")
                .append(count > 0 && hasEmptyEntry(r) ? " open" : "").append("><summary>Conditions et autres effets")
                .append(count > 0 ? " (" + count + ")" : "").append("</summary>");

        sb.append("<p class=\"dlg-adv-h\">Cette réponse n'apparaît que si…</p>");
        sb.append(entries(ctx, d, i, j, "cd", r.conditions(), f, editable));
        sb.append("<p class=\"dlg-adv-h\">Il se passe aussi…</p>");
        sb.append(entries(ctx, d, i, j, "fx", r.otherEffects(), f, editable));
        return sb.append("</details>").toString();
    }

    private static boolean hasEmptyEntry(DialogueForm.Reply r) {
        for (List<Map<String, String>> list : List.of(r.conditions(), r.otherEffects())) {
            for (Map<String, String> e : list) {
                for (Map.Entry<String, String> kv : e.entrySet()) {
                    if (!"type".equals(kv.getKey()) && kv.getValue().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String entries(Ctx ctx, DialogueDraft d, int i, int j, String kind,
                                  List<Map<String, String>> list, Map<String, String> f, boolean editable) {
        StringBuilder sb = new StringBuilder();
        sb.append(hidden(DialogueForm.reply(i, j, kind + "_count"), String.valueOf(list.size())));
        if (list.isEmpty()) {
            sb.append("<p class=\"muted dlg-adv-none\">").append("cd".equals(kind) ? "Toujours proposée." : "Rien d'autre.")
                    .append("</p>");
        }
        List<Descriptors.Descriptor> catalog = "cd".equals(kind)
                ? Descriptors.DIALOGUE_CONDITIONS : Descriptors.DIALOGUE_ACTIONS;
        for (int k = 0; k < list.size(); k++) {
            Map<String, String> e = list.get(k);
            String base = "dlg-n" + i + "-c" + j + "-" + kind + k;
            String type = e.getOrDefault("type", "");
            sb.append("<div class=\"dlg-entry\">");
            sb.append(hidden(DialogueForm.entry(i, j, kind, k, "keys"), f.get(DialogueForm.entry(i, j, kind, k, "keys"))));
            sb.append(hidden(DialogueForm.entry(i, j, kind, k, "otype"), type));
            if ("cd".equals(kind) && f.containsKey(DialogueForm.entry(i, j, kind, k, "onegate"))) {
                sb.append(hidden(DialogueForm.entry(i, j, kind, k, "onegate"), f.get(DialogueForm.entry(i, j, kind, k, "onegate"))));
            }
            sb.append("<label for=\"").append(base).append("-type\">").append("cd".equals(kind) ? "Condition" : "Effet")
                    .append("</label>");
            sb.append("<select class=\"form-select\" id=\"").append(base).append("-type\" name=\"")
                    .append(DialogueForm.entry(i, j, kind, k, "v_type")).append("\"").append(editable ? "" : " disabled").append(">");
            boolean known = false;
            for (Descriptors.Descriptor desc : catalog) {
                if ("fx".equals(kind) && ("CLOSE".equals(desc.kind()))) {
                    continue;
                }
                boolean on = desc.kind().equalsIgnoreCase(type);
                known |= on;
                sb.append("<option value=\"").append(desc.kind()).append("\"").append(on ? " selected" : "").append(">")
                        .append(Http.esc(desc.label())).append("</option>");
            }
            if (!known) {
                sb.append("<option value=\"").append(Http.esc(type)).append("\" selected>")
                        .append(Http.esc(type.isEmpty() ? "(sans type)" : type)).append("</option>");
            }
            sb.append("</select>");
            if (!editable) {
                sb.append(hidden(DialogueForm.entry(i, j, kind, k, "v_type"), type));
            }
            sb.append(entryFields(ctx, d, i, j, kind, k, e, catalog, editable));
            if (editable) {
                sb.append("<button class=\"btn secondary sm dlg-rm\" type=\"submit\" name=\"_op\" value=\"del")
                        .append(kind).append(":").append(i).append(":").append(j).append(":").append(k)
                        .append("\" formnovalidate>Retirer</button>");
            }
            sb.append("</div>");
        }
        if (editable) {
            String addId = "dlg-n" + i + "-c" + j + "-" + kind + "-add";
            sb.append("<div class=\"dlg-inline\"><label class=\"visually-hidden\" for=\"").append(addId).append("\">")
                    .append("cd".equals(kind) ? "Type de condition" : "Type d'effet").append("</label>")
                    .append("<select class=\"form-select\" id=\"").append(addId).append("\" name=\"add_")
                    .append(kind).append("_").append(i).append("_").append(j).append("\">");
            for (Descriptors.Descriptor desc : catalog) {
                if ("fx".equals(kind) && "CLOSE".equals(desc.kind())) {
                    continue;
                }
                sb.append("<option value=\"").append(desc.kind()).append("\">").append(Http.esc(desc.label())).append("</option>");
            }
            sb.append("</select><button class=\"btn secondary sm\" type=\"submit\" name=\"_op\" value=\"add")
                    .append(kind).append(":").append(i).append(":").append(j).append("\" formnovalidate>")
                    .append(Icons.icon("plus")).append("Ajouter</button></div>");
        }
        return sb.toString();
    }

    private static String entryFields(Ctx ctx, DialogueDraft d, int i, int j, String kind, int k,
                                      Map<String, String> e, List<Descriptors.Descriptor> catalog, boolean editable) {
        String type = e.getOrDefault("type", "");
        Descriptors.Descriptor desc = catalog.stream().filter(x -> x.kind().equalsIgnoreCase(type)).findFirst().orElse(null);
        List<String> shown = new ArrayList<>();
        StringBuilder sb = new StringBuilder("<div class=\"dlg-entry-fields\">");
        if (desc != null) {
            for (Descriptors.Field field : desc.fields()) {
                shown.add(field.name());
                sb.append(entryField(ctx, d, DialogueForm.entry(i, j, kind, k, "v_" + field.name()),
                        "dlg-n" + i + "-c" + j + "-" + kind + k + "-" + field.name(), field.label(),
                        field.selectSource(), field.type(), e.getOrDefault(field.name(), ""), editable));
            }
        }
        // Champs que l'éditeur ne sait pas nommer : visibles et conservés, jamais retirés en silence.
        for (Map.Entry<String, String> kv : e.entrySet()) {
            String key = kv.getKey();
            if ("type".equals(key) || shown.contains(key) || ("cd".equals(kind) && Descriptors.NEGATE.name().equals(key))) {
                continue;
            }
            sb.append(entryField(ctx, d, DialogueForm.entry(i, j, kind, k, "v_" + key),
                    "dlg-n" + i + "-c" + j + "-" + kind + k + "-" + key, key, null, Descriptors.FieldType.TEXT,
                    kv.getValue(), editable));
        }
        if ("cd".equals(kind)) {
            boolean on = "true".equalsIgnoreCase(e.getOrDefault(Descriptors.NEGATE.name(), ""));
            sb.append("<label class=\"inline\"><input type=\"checkbox\" name=\"")
                    .append(DialogueForm.entry(i, j, kind, k, "v_negate")).append("\" value=\"true\"")
                    .append(on ? " checked" : "").append(editable ? "" : " disabled").append("> Inverser : seulement si ce n'est pas le cas</label>");
            if (!editable && on) {
                sb.append(hidden(DialogueForm.entry(i, j, kind, k, "v_negate"), "true"));
            }
        }
        return sb.append("</div>").toString();
    }

    private static String entryField(Ctx ctx, DialogueDraft d, String name, String id, String label, String source,
                                     Descriptors.FieldType type, String value, boolean editable) {
        StringBuilder sb = new StringBuilder("<div>");
        sb.append("<label for=\"").append(id).append("\">").append(Http.esc(label)).append("</label>");
        if ("quest".equals(source)) {
            sb.append(questSelect(ctx, id, name, value, editable));
        } else if ("questState".equals(source)) {
            sb.append("<select class=\"form-select\" id=\"").append(id).append("\" name=\"").append(name).append("\"")
                    .append(editable ? "" : " disabled").append(">");
            boolean found = value.isEmpty();
            sb.append(option("", value, "Choisir…"));
            for (String[] s : QUEST_STATES) {
                found |= s[0].equalsIgnoreCase(value);
                sb.append(option(s[0], value.toUpperCase(Locale.ROOT), s[1]));
            }
            if (!found) {
                sb.append(option(value, value, value));
            }
            sb.append("</select>");
        } else if ("npc".equals(source)) {
            sb.append("<select class=\"form-select\" id=\"").append(id).append("\" name=\"").append(name).append("\"")
                    .append(editable ? "" : " disabled").append(">");
            sb.append(option("", value, "Ce personnage"));
            boolean found = value.isEmpty();
            for (Npc n : sortedByName(ctx.npcs())) {
                found |= n.id().equals(value);
                sb.append(option(n.id(), value, MiniText.plain(n.name())));
            }
            if (!found) {
                sb.append(option(value, value, value));
            }
            sb.append("</select>");
        } else {
            sb.append("<input class=\"form-control\" id=\"").append(id).append("\" name=\"").append(name).append("\"")
                    .append(type == Descriptors.FieldType.INT ? " type=\"number\" min=\"1\" inputmode=\"numeric\"" : " type=\"text\"")
                    .append(" value=\"").append(Http.esc(value)).append("\"").append(editable ? "" : " readonly").append(">");
        }
        if (!editable && ("quest".equals(source) || "questState".equals(source) || "npc".equals(source))) {
            sb.append(hidden(name, value));
        }
        return sb.append("</div>").toString();
    }

    private static String questSelect(Ctx ctx, String id, String name, String value, boolean editable) {
        StringBuilder sb = new StringBuilder("<select class=\"form-select\" id=\"").append(id).append("\" name=\"")
                .append(name).append("\"").append(editable ? "" : " disabled").append(">");
        sb.append(option("", value, "Choisir une quête…"));
        boolean found = value.isEmpty();
        for (Quest q : ctx.quests()) {
            boolean on = DialogueYaml.plainId(q.id()).equals(DialogueYaml.plainId(value));
            found |= on;
            sb.append("<option value=\"").append(Http.esc(on ? value : q.id())).append("\"").append(on ? " selected" : "")
                    .append(">").append(Http.esc(MiniText.plain(q.title()))).append("</option>");
        }
        if (!found) {
            sb.append(option(value, value, ctx.questTitle(value) + " (introuvable)"));
        }
        return sb.append("</select>").toString();
    }

    private static final List<String[]> QUEST_STATES = List.of(
            new String[] {"NOT_STARTED", "pas encore commencée"},
            new String[] {"ACTIVE", "en cours"},
            new String[] {"READY_TO_TURN_IN", "prête à être rendue"},
            new String[] {"COMPLETED", "terminée"},
            new String[] {"FAILED", "échouée"},
            new String[] {"ABANDONED", "abandonnée"});

    private static String dialogueSettings(DialogueDraft d, Map<String, String> f, boolean editable, boolean hasComments) {
        StringBuilder sb = new StringBuilder("<details class=\"card dlg-card dlg-more dlg-settings\"><summary>Réglages du dialogue</summary>");
        sb.append("<label for=\"dlg-start\">Première réplique</label>");
        sb.append("<select class=\"form-select\" id=\"dlg-start\" name=\"d_start\"").append(editable ? "" : " disabled").append(">");
        for (DialogueDraft.Node n : DialogueForm.displayOrder(d)) {
            sb.append(option(n.id, d.start, (n.id.equals(d.start) ? "Le début actuel" : "Réplique " + DialogueForm.number(d, n.id))
                    + excerptSuffix(n.text)));
        }
        sb.append("</select>");
        if (!editable) {
            sb.append(hidden("d_start", f.get("d_start")));
        }
        sb.append("<p class=\"form-text\">Identifiant : <code>").append(Http.esc(f.get("d_id")))
                .append("</code>. Il ne change pas, pour que les personnages et les quêtes le retrouvent.</p>");
        if (hasComments) {
            sb.append("<p class=\"form-text\">").append(Icons.icon("info"))
                    .append("Ce fichier contient des notes écrites à la main (lignes « # »). "
                            + "L'enregistrement depuis PlugAdmin ne les conserve pas ; tout le reste est gardé.</p>");
        }
        return sb.append("</details>").toString();
    }

    // =====================================================================================
    //  Aperçu
    // =====================================================================================

    /**
     * Aperçu de la conversation, réplique par réplique. Sans JavaScript, toutes les répliques sont
     * affichées et les réponses sont des liens ; avec, une seule à la fois ({@code data-dlg-preview}).
     * Rien n'est exécuté : une quête proposée est montrée, jamais attribuée.
     */
    static String preview(Ctx ctx, DialogueDraft d) {
        StringBuilder sb = new StringBuilder("<section class=\"card dlg-card dlg-preview\" data-dlg-preview>");
        sb.append("<h2 class=\"dlg-node-h\">").append(Icons.icon("eye")).append("Aperçu de la conversation</h2>");
        sb.append("<p class=\"form-text\">Cliquez sur une réponse pour voir la suite. Rien n'est donné ni démarré "
                + "pour de vrai. L'aperçu montre la dernière version enregistrée ou en cours de modification.</p>");
        sb.append("<div class=\"dlg-pv-nav\"><button class=\"btn secondary sm\" type=\"button\" data-dlg-restart hidden>")
                .append("Recommencer</button></div>");
        for (DialogueDraft.Node node : DialogueForm.displayOrder(d)) {
            sb.append("<div class=\"dlg-pv-node\" data-pv-node=\"").append(Http.esc(node.id)).append("\" id=\"pv-")
                    .append(Http.esc(node.id)).append("\">");
            sb.append("<p class=\"dlg-pv-speaker\">").append(Http.esc(MiniText.plain(node.speaker))).append("</p>");
            sb.append("<p class=\"dlg-pv-text\">").append(node.text.isBlank() ? "<span class=\"muted\">(réplique à écrire)</span>"
                    : MiniText.html(node.text)).append("</p>");
            sb.append("<ul class=\"dlg-pv-replies\">");
            for (DialogueDraft.Choice c : node.choices) {
                DialogueForm.Reply r = DialogueForm.reply(c);
                boolean end = DialogueForm.END.equals(r.next());
                sb.append("<li><a class=\"dlg-pv-reply\" href=\"").append(end ? "#pv-end" : "#pv-" + Http.esc(r.next()))
                        .append("\" data-pv-go=\"").append(end ? "" : Http.esc(r.next())).append("\">")
                        .append(c.text.isBlank() ? "(réponse vide)" : MiniText.html(c.text)).append("</a>");
                List<String> notes = new ArrayList<>();
                if (!c.conditions.isEmpty()) {
                    notes.add("visible sous " + c.conditions.size() + " condition(s)");
                }
                for (Map<String, String> a : c.actions) {
                    String t = a.getOrDefault("type", "").toUpperCase(Locale.ROOT);
                    switch (t) {
                        case "START_QUEST" -> notes.add("📜 propose la quête « " + MiniText.plain(ctx.questTitle(a.getOrDefault("quest", ""))) + " »");
                        case DialogueForm.CATALOG_ACTION -> notes.add("🎲 propose une quête au hasard dans "
                                + catalogPhrase(ctx, d.id, a.getOrDefault("catalog", "")));
                        case "ADVANCE_QUEST" -> notes.add("fait avancer « " + MiniText.plain(ctx.questTitle(a.getOrDefault("quest", ""))) + " »");
                        case "TURN_IN_QUEST" -> notes.add("rend « " + MiniText.plain(ctx.questTitle(a.getOrDefault("quest", ""))) + " »");
                        case "CLOSE", "" -> {
                        }
                        default -> notes.add(Descriptors.dialogueAction(t).map(Descriptors.Descriptor::label)
                                .orElse(t).toLowerCase(Locale.ROOT));
                    }
                }
                if (end) {
                    notes.add("fin de la conversation");
                }
                if (!notes.isEmpty()) {
                    sb.append("<span class=\"dlg-pv-fx\">").append(Http.esc(String.join(" · ", notes))).append("</span>");
                }
                sb.append("</li>");
            }
            if (node.choices.isEmpty()) {
                sb.append("<li class=\"muted\">La conversation s'arrête ici.</li>");
            }
            sb.append("</ul></div>");
        }
        sb.append("<div class=\"dlg-pv-node dlg-pv-end\" data-pv-node=\"\" id=\"pv-end\"><p class=\"muted\">")
                .append("Fin de la conversation.</p></div>");
        return sb.append("</section>").toString();
    }

    // =====================================================================================
    //  Petits outils
    // =====================================================================================

    static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + Http.esc(name) + "\" value=\"" + Http.esc(value == null ? "" : value) + "\">";
    }

    private static String option(String value, String current, String label) {
        return "<option value=\"" + Http.esc(value) + "\"" + (value.equals(current) ? " selected" : "") + ">"
                + Http.esc(label) + "</option>";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Messages de validation à placer près du bon champ, en français simple. */
    public static Map<String, List<String>> placeErrors(DialogueDraft d,
                                                        List<com.lodygames.rpgquest.panel.content.Diagnostic> diags) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        java.util.regex.Pattern node = java.util.regex.Pattern.compile("^nodes\\[([^\\]]*)\\](?:\\.choices\\[(\\d+)\\])?(.*)$");
        for (com.lodygames.rpgquest.panel.content.Diagnostic diag : diags) {
            if (diag.level() != com.lodygames.rpgquest.panel.content.Diagnostic.Level.ERROR) {
                continue;
            }
            String field = diag.field() == null ? "" : diag.field();
            java.util.regex.Matcher m = node.matcher(field);
            String key = "dialogue";
            String msg = friendly(diag.message(), field);
            if (m.matches()) {
                int i = indexOf(d, m.group(1));
                if (i >= 0) {
                    key = m.group(2) == null ? nodeKey(i) : replyKey(i, Integer.parseInt(m.group(2)));
                }
            }
            out.computeIfAbsent(key, x -> new ArrayList<>()).add(msg);
        }
        return out;
    }

    private static int indexOf(DialogueDraft d, String id) {
        for (int i = 0; i < d.nodes.size(); i++) {
            if (d.nodes.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Le message du validateur, dit sans jargon. */
    static String friendly(String message, String field) {
        if (field.endsWith(".text") && message.contains("obligatoire")) {
            return "Écrivez ce que dit le personnage.";
        }
        if (field.endsWith(".text") && message.contains("trop long")) {
            return "Cette réplique est trop longue (512 caractères au plus).";
        }
        if (field.endsWith(".speaker")) {
            return message.contains("trop long") ? "Le nom affiché est trop long (128 caractères au plus)."
                    : "Indiquez le nom affiché au-dessus de la réplique (dans « Réglages de la réplique »).";
        }
        if (message.startsWith("Un choix sans texte")) {
            return "Écrivez ce que dit le joueur, ou supprimez cette réponse.";
        }
        if (message.contains("nœud inexistant")) {
            return "Cette réponse mène à une réplique qui n'existe plus : choisissez une autre suite.";
        }
        if (message.contains("obligatoire pour la condition") || message.contains("obligatoire pour la action")) {
            return "Une condition ou un effet n'est pas complet : remplissez tous ses champs, ou retirez-le.";
        }
        if (message.startsWith("Type de")) {
            return "Une condition ou un effet n'est pas reconnu par le jeu : retirez-le ou choisissez un type.";
        }
        if (message.contains("entier positif")) {
            return "Une quantité doit être un nombre entier supérieur à 0.";
        }
        if ("start".equals(field)) {
            return "Choisissez la première réplique du dialogue (dans « Réglages du dialogue »).";
        }
        return message.replace("nœud", "réplique").replace("Nœud", "Réplique").replace("choix", "réponse")
                .replace("locuteur", "nom affiché").replace("Locuteur", "Nom affiché");
    }
}
