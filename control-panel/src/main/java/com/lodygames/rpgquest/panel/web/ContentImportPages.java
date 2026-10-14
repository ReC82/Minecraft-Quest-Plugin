package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.content.ContentPackImport;
import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.content.ContentPackSummary;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.Diagnostic;
import com.lodygames.rpgquest.panel.content.StoryKit;
import com.lodygames.rpgquest.panel.content.TextDiff;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Map;

/**
 * Page d'import d'un content pack (issue #109).
 *
 * <p>Le pipeline du ticket est rendu <strong>visible</strong> : on dépose un pack, on lit ce que
 * l'analyse en dit élément par élément, on tranche les collisions, on regarde le diff, et seulement
 * alors on confirme. Rien n'est écrit avant le bouton de confirmation, et la page le dit.</p>
 *
 * <p><strong>Sans état serveur</strong> : le pack est reposté dans un champ caché à chaque étape, et
 * l'analyse est <em>refaite</em> à chaque fois. C'est volontaire — une analyse mise en cache
 * deviendrait fausse dès qu'un éditeur enregistre en parallèle, et la confirmation écrirait alors
 * d'après un état périmé. Ici, la confirmation re-valide tout et redétecte les collisions apparues
 * entre-temps.</p>
 */
public final class ContentImportPages {

    private ContentImportPages() {
    }

    /**
     * @param analysis  {@code null} tant qu'aucun pack n'a été soumis
     * @param applied   résultats d'écriture après confirmation, {@code null} sinon
     * @param decisions arbitrages en cours, pour réafficher les boutons dans le bon état
     */
    public static String render(ContentWorkspace workspace, String packYaml,
                               ContentPackImport.Analysis analysis,
                               List<ContentPackImport.WriteOutcome> applied,
                               Map<String, ContentPackImport.Decision> decisions, String error) {
        return render(workspace, packYaml, analysis, applied, decisions, error, List.of());
    }

    /** Même chose, avec les suites de la confirmation (PNJ logiques créés, issue #211). */
    public static String render(ContentWorkspace workspace, String packYaml,
                               ContentPackImport.Analysis analysis,
                               List<ContentPackImport.WriteOutcome> applied,
                               Map<String, ContentPackImport.Decision> decisions, String error,
                               List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("export", "Importer un content pack",
                "Déposer un pack, lire l'analyse, trancher les collisions, voir le diff, puis "
                        + "confirmer. Rien n'est écrit dans la source avant la confirmation.", ""));

        if (error != null && !error.isBlank()) {
            sb.append(Ui.banner("error", Http.esc(error)));
        }
        if (!workspace.configured()) {
            sb.append(Ui.banner("warning",
                    "L'espace de travail du contenu n'est pas configuré : l'import ne pourrait rien "
                            + "enregistrer. Voir la documentation #46."));
            return sb.toString();
        }

        if (applied != null) {
            sb.append(appliedCard(applied));
        }
        for (String note : notes == null ? List.<String>of() : notes) {
            sb.append(Ui.banner("info", Http.esc(note)));
        }
        sb.append(storyKitCard(analysis == null && applied == null));
        sb.append(uploadCard(packYaml, analysis == null));
        if (analysis != null) {
            sb.append(analysisCard(analysis, packYaml, decisions));
        }
        sb.append(aboutCard());
        return sb.toString();
    }

    // ---- Modèle d'histoire (issue #211) ---------------------------------------------------------

    /**
     * Le parcours « télécharger le modèle → le faire compléter par une IA → importer ». Ouvert sur
     * la page vide, replié dès qu'une analyse est affichée pour ne pas la repousser hors écran sur
     * téléphone. Aucun JavaScript : le prompt est dans une zone de texte à sélectionner.
     */
    private static String storyKitCard(boolean open) {
        StringBuilder sb = new StringBuilder("<section class=\"card\"><details")
                .append(open ? " open" : "").append("><summary>")
                .append(Ui.sectionTitle("docs", "Créer une histoire avec une IA externe"))
                .append("</summary>");
        sb.append("<ol class=\"muted\">"
                + "<li><strong>Télécharger</strong> le modèle d'histoire ci-dessous.</li>"
                + "<li><strong>Faire compléter</strong> la fiche narrative par une IA (ChatGPT, Claude…) "
                + "avec le guide, l'exemple et le prompt.</li>"
                + "<li><strong>Déposer</strong> le fichier <code>.json</code> rendu par l'IA dans « 1. Le pack "
                + "à importer ».</li>"
                + "<li><strong>Vérifier</strong> l'analyse et l'aperçu : quêtes, objectifs, récompenses, "
                + "dialogues, personnages et liens.</li>"
                + "<li><strong>Confirmer</strong> l'import en source (une seule confirmation).</li>"
                + "<li><strong>Publier sur DEV</strong> depuis <a href=\"/content/pending\">Changements en "
                + "attente</a>, puis lier les personnages à un PNJ Citizens.</li></ol>");
        sb.append("<p class=\"muted\">Seul un fichier <code>.json</code> est importable : les fiches "
                + "<code>.md</code> sont écrites pour l'auteur et pour l'IA, jamais pour l'import. "
                + "Aucun dépôt ne publie quoi que ce soit.</p>");
        sb.append("<div class=\"btnrow\"><a class=\"btn\" href=\"/content/story-kit\">")
                .append(Icons.icon("export")).append("Télécharger un modèle d'histoire</a></div>");
        sb.append("<p class=\"muted\">Le modèle (.zip) contient tous les fichiers suivants, aussi "
                + "téléchargeables un par un :</p><ul class=\"muted\">");
        for (StoryKit.KitFile f : StoryKit.FILES) {
            sb.append("<li><a href=\"/content/story-kit?file=").append(Http.esc(f.name())).append("\">")
                    .append(Http.esc(f.label())).append("</a> <code>").append(Http.esc(f.name()))
                    .append("</code> — ").append(Http.esc(f.role())).append("</li>");
        }
        sb.append("</ul>");
        sb.append("<div class=\"field full\"><label for=\"f-story-prompt\">Prompt prêt à copier</label>"
                + "<textarea id=\"f-story-prompt\" rows=\"6\" readonly spellcheck=\"false\">")
                .append(Http.esc(StoryKit.prompt())).append("</textarea></div>");
        sb.append("<form method=\"post\" action=\"/content/import\" class=\"actform\">%CSRF%"
                + "<input type=\"hidden\" name=\"pack\" value=\"").append(Http.esc(StoryKit.lilyPack()))
                .append("\"><div class=\"btnrow\"><button class=\"btn secondary\" type=\"submit\">")
                .append(Icons.icon("check")).append("Analyser l'exemple Lily</button></div>"
                        + "<p class=\"field-help\">Lance seulement l'analyse du pack Lily : rien n'est "
                        + "enregistré sans la confirmation de l'étape 3.</p></form>");
        return sb.append("</details></section>").toString();
    }

    // ---- Dépôt ---------------------------------------------------------------------------------

    private static String uploadCard(String packYaml, boolean expanded) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("export", "1. Le pack à importer"));
        sb.append("<p class=\"muted\">Déposer le fichier <code>.json</code> ou <code>.yml</code>, ou coller son "
                + "contenu (YAML ou JSON, même format). Taille maximale : ").append(ContentPackImport.MAX_BYTES / 1024)
                .append(" Kio. L'analyse ne modifie rien — elle lit le pack et le compare à la source.</p>");
        sb.append("<form method=\"post\" action=\"/content/import\" class=\"actform\" "
                + "enctype=\"multipart/form-data\">%CSRF%");
        sb.append("<div class=\"field full\"><label for=\"f-pack-file\">Fichier du pack</label>"
                + "<input id=\"f-pack-file\" type=\"file\" name=\"pack_file\" accept=\".json,.yml,.yaml,"
                + "application/json,text/yaml\"><p class=\"field-help\">Un fichier déposé remplace le texte "
                + "collé ci-dessous.</p></div>");
        sb.append("<div class=\"field full\"><label for=\"f-pack\">Contenu du pack</label>");
        sb.append("<textarea id=\"f-pack\" name=\"pack\" rows=\"").append(expanded ? 14 : 6)
                .append("\" spellcheck=\"false\" placeholder=\"format: ")
                .append(Http.esc(ContentPackSchema.FORMAT)).append("&#10;schemaVersion: ")
                .append(ContentPackSchema.SCHEMA_VERSION).append("&#10;content:&#10;  quests: []\">")
                .append(Http.esc(packYaml == null ? "" : packYaml)).append("</textarea></div>");
        sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\">")
                .append(Icons.icon("check")).append("Analyser</button></div>");
        sb.append("</form></section>");
        return sb.toString();
    }

    // ---- Analyse -------------------------------------------------------------------------------

    private static String analysisCard(ContentPackImport.Analysis a, String packYaml,
                                       Map<String, ContentPackImport.Decision> decisions) {
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("check", "2. Analyse du pack"));

        for (Diagnostic d : a.envelope()) {
            sb.append(Ui.banner(bannerType(d.level()), Http.esc(d.message())));
        }
        if (!a.readable()) {
            sb.append("<p class=\"muted\">Le pack n'a pas pu être parcouru : aucun élément n'a été "
                    + "analysé, et rien n'a été écrit.</p></section>");
            return sb.toString();
        }

        if (!a.metadata().isEmpty()) {
            sb.append("<p class=\"muted\">");
            a.metadata().forEach((k, v) -> sb.append("<strong>").append(Http.esc(k)).append("</strong> ")
                    .append(Http.esc(v)).append(" &middot; "));
            sb.append("<em>informatif : ces métadonnées n'influent sur aucune décision.</em></p>");
        }

        sb.append("<p class=\"npc-summary\">")
                .append(a.elements().size()).append(" élément(s) &middot; ")
                .append(a.writing()).append(" à écrire &middot; ")
                .append(a.conflicts()).append(" collision(s) &middot; ")
                .append(a.invalid()).append(" inexploitable(s)</p>");

        if (!a.dependencies().isEmpty()) {
            sb.append(dependencies(a));
        }
        sb.append(editorialPreview(a, packYaml));

        for (ContentPackImport.Element e : a.elements()) {
            sb.append(element(e, decisions));
        }

        sb.append(confirmForm(a, packYaml, decisions));
        return sb.append("</section>").toString();
    }

    /**
     * Aperçu éditorial (issue #211) : la story dans l'ordre, chaque quête avec son donneur, ses
     * déblocages, ses objectifs et ses récompenses, puis les personnages et ce qu'il reste à faire
     * pour eux. Ce qui manque est dit, jamais remplacé en silence.
     */
    private static String editorialPreview(ContentPackImport.Analysis a, String packYaml) {
        ContentPackSummary.Summary s = ContentPackSummary.of(packYaml);
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details open><summary class=\"muted\"><strong>Aperçu de "
                + "l'histoire</strong> — story, quêtes, objectifs, récompenses, personnages</summary>");
        for (ContentPackSummary.Story story : s.stories()) {
            sb.append("<p><strong>Story « ").append(Http.esc(story.name())).append(" »</strong> <code>")
                    .append(Http.esc(story.id())).append("</code> — ").append(story.questIds().size())
                    .append(" quête(s), dans cet ordre :</p><ol>");
            for (String id : story.questIds()) {
                ContentPackSummary.Quest q = s.quests().get(id);
                sb.append("<li>").append(q == null
                        ? "<code>" + Http.esc(id) + "</code> <span class=\"badge text-bg-warning\">absente du "
                          + "pack</span>"
                        : quest(q)).append("</li>");
            }
            sb.append("</ol>");
        }
        List<ContentPackSummary.Quest> alone = s.standaloneQuests();
        if (!alone.isEmpty()) {
            sb.append("<p><strong>Quêtes hors story</strong></p><ul>");
            alone.forEach(q -> sb.append("<li>").append(quest(q)).append("</li>"));
            sb.append("</ul>");
        }
        if (!s.npcs().isEmpty()) {
            sb.append("<p><strong>Personnages</strong></p><ul>");
            for (ContentPackSummary.Npc n : s.npcs()) {
                sb.append("<li><strong>").append(Http.esc(n.name())).append("</strong> <code>")
                        .append(Http.esc(n.id())).append("</code> — dialogue <code>")
                        .append(Http.esc(n.dialogue().isEmpty() ? "rpgquest:" + n.id() : n.dialogue()))
                        .append("</code>. ").append(Http.esc(npcFate(a, n.id()))).append("</li>");
            }
            sb.append("</ul>");
        }
        if (!s.dialogues().isEmpty()) {
            sb.append("<p><strong>Dialogues</strong></p><ul>");
            for (ContentPackSummary.Dialogue d : s.dialogues()) {
                sb.append("<li><code>").append(Http.esc(d.id())).append("</code> — ").append(d.nodes())
                        .append(" nœud(s)").append(d.startsQuests().isEmpty() ? ""
                                : ", propose : " + Http.esc(String.join(", ", d.startsQuests())))
                        .append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.append("</details>").toString();
    }

    private static String quest(ContentPackSummary.Quest q) {
        StringBuilder sb = new StringBuilder("<strong>").append(Http.esc(q.title())).append("</strong> <code>")
                .append(Http.esc(q.id())).append("</code>");
        if (!q.giver().isEmpty()) {
            sb.append(" — proposée par <code>").append(Http.esc(q.giver())).append("</code>");
        }
        if (!q.prerequisites().isEmpty()) {
            sb.append(" — après ").append(Http.esc(String.join(", ", q.prerequisites())));
        }
        sb.append("<ul class=\"muted\">");
        q.objectives().forEach(o -> sb.append("<li>").append(Http.esc(o)).append("</li>"));
        q.rewards().forEach(r -> sb.append("<li>Récompense — ").append(Http.esc(r)).append("</li>"));
        return sb.append("</ul>").toString();
    }

    /** Ce que l'import fera de ce personnage, et ce qui restera à faire en jeu. */
    private static String npcFate(ContentPackImport.Analysis a, String id) {
        ContentPackImport.Status status = a.elements().stream()
                .filter(e -> "npcs".equals(e.family()) && id.equalsIgnoreCase(e.id()))
                .map(ContentPackImport.Element::status).findFirst().orElse(null);
        if (status == ContentPackImport.Status.SKIPPED) {
            return "Existe déjà sur le serveur : réutilisé tel quel (aucune modification). Son dialogue "
                    + "s'y rattache une fois publié ; vérifier sur la page PNJ qu'il est bien placé.";
        }
        if (status == ContentPackImport.Status.NEW) {
            return "Personnage logique créé à la confirmation, SANS placement : à lier ensuite à un PNJ "
                    + "Citizens depuis la page PNJ (aucune coordonnée n'est importée).";
        }
        return "Inexploitable : voir le détail ci-dessous.";
    }

    private static String dependencies(ContentPackImport.Analysis a) {
        StringBuilder sb = new StringBuilder("<p class=\"muted\">Dépendances déclarées : ");
        for (ContentPackImport.Dependency d : a.dependencies()) {
            sb.append("<span class=\"badge ").append(switch (d.state()) {
                case IN_PACK -> "text-bg-info";
                case ON_SERVER -> "text-bg-secondary";
                case MISSING -> "text-bg-warning";
            }).append("\">").append(Http.esc(d.id())).append(" — ").append(switch (d.state()) {
                case IN_PACK -> "fournie par le pack";
                case ON_SERVER -> "déjà présente";
                case MISSING -> "manquante";
            }).append("</span> ");
        }
        sb.append("</p><p class=\"muted\">Une dépendance manquante n'empêche pas l'import : le "
                + "contenu sera simplement incomplet jusqu'à ce qu'elle existe.</p>");
        return sb.toString();
    }

    private static String element(ContentPackImport.Element e,
                                  Map<String, ContentPackImport.Decision> decisions) {
        StringBuilder sb = new StringBuilder("<div class=\"npc-item\">");
        sb.append("<p class=\"meta-line\"><span class=\"badge ").append(badgeClass(e.status()))
                .append("\">").append(statusLabel(e.status())).append("</span> ")
                .append("<code class=\"tid\">").append(Http.esc(e.family())).append('/')
                .append(Http.esc(e.slug().isEmpty() ? "?" : e.slug())).append("</code> ")
                .append(Ui.id(e.id())).append("</p>");

        if (e.reason() != null && !e.reason().isBlank()) {
            sb.append("<p class=\"muted\">").append(Http.esc(e.reason())).append("</p>");
        }
        for (Diagnostic d : e.diagnostics()) {
            sb.append("<p class=\"muted\"><span class=\"badge ").append(switch (d.level()) {
                case ERROR -> "text-bg-danger";
                case WARNING -> "text-bg-warning";
                case INFO -> "text-bg-secondary";
            }).append("\">").append(d.level().name()).append("</span> ")
                    .append(Http.esc(d.field())).append(" — ").append(Http.esc(d.message()))
                    .append("</p>");
        }

        if (e.status() == ContentPackImport.Status.CONFLICT) {
            sb.append("<div class=\"btnrow\">");
            sb.append(decisionButton(e, ContentPackImport.Decision.REPLACE, "Remplacer l'existant",
                    "btn", decisions));
            sb.append(decisionButton(e, ContentPackImport.Decision.SKIP, "Garder l'existant",
                    "btn secondary", decisions));
            sb.append("</div>");
        }
        if (!e.diff().isEmpty()) {
            sb.append(diff(e.diff()));
        } else if (e.writes() && e.yaml() != null && !"npcs".equals(e.family())) {
            sb.append("<details><summary class=\"muted\">Voir le contenu qui sera enregistré</summary>"
                    + "<div class=\"codeblock\"><pre>").append(Http.esc(e.yaml())).append("</pre></div></details>");
        }
        return sb.append("</div>").toString();
    }

    /**
     * Les arbitrages voyagent dans le formulaire de confirmation ; ces boutons ne font que poser la
     * valeur, dans une soumission qui <strong>ré-analyse</strong>. Aucun d'eux n'écrit quoi que ce
     * soit — la décision n'est appliquée qu'à la confirmation.
     */
    private static String decisionButton(ContentPackImport.Element e, ContentPackImport.Decision d,
                                         String label, String cls,
                                         Map<String, ContentPackImport.Decision> decisions) {
        boolean on = d == decisions.get(e.key());
        return "<button class=\"" + cls + "\" type=\"submit\" form=\"import-decide\" name=\"decision."
                + Http.esc(e.key()) + "\" value=\"" + d.name() + "\"" + (on ? " disabled" : "") + ">"
                + Http.esc(label) + "</button>";
    }

    /**
     * Même balisage et mêmes classes que l'aperçu de l'éditeur guidé ({@code codeblock diff} +
     * {@code di-add}/{@code di-del}/{@code di-ctx}) : un seul style de diff dans tout le panel, et
     * replié par défaut pour que la liste reste lisible sur mobile — l'exigence du ticket est
     * justement de ne pas se limiter à un gros dump YAML.
     */
    private static String diff(List<TextDiff.Line> lines) {
        long changed = lines.stream().filter(l -> l.kind() != ' ').count();
        StringBuilder sb = new StringBuilder("<details><summary class=\"muted\">Voir le diff (")
                .append(changed).append(" ligne(s) changée(s))</summary>");
        sb.append("<div class=\"codeblock diff\"><pre>");
        for (TextDiff.Line l : lines) {
            String cls = l.kind() == '+' ? "di-add" : (l.kind() == '-' ? "di-del" : "di-ctx");
            sb.append("<span class=\"").append(cls).append("\">").append(l.kind()).append(' ')
                    .append(Http.esc(l.text())).append("</span>\n");
        }
        return sb.append("</pre></div></details>").toString();
    }

    private static String confirmForm(ContentPackImport.Analysis a, String packYaml,
                                      Map<String, ContentPackImport.Decision> decisions) {
        StringBuilder sb = new StringBuilder();
        // Un seul formulaire porte le pack et tous les arbitrages déjà rendus. Les boutons de
        // décision s'y rattachent par « form=… », donc un clic conserve tout le reste de l'état.
        sb.append("<form method=\"post\" action=\"/content/import\" id=\"import-decide\" class=\"actform\">%CSRF%");
        sb.append("<input type=\"hidden\" name=\"pack\" value=\"").append(Http.esc(packYaml)).append("\">");
        decisions.forEach((key, d) -> sb.append("<input type=\"hidden\" name=\"decision.")
                .append(Http.esc(key)).append("\" value=\"").append(d.name()).append("\">"));
        sb.append(Ui.sectionTitle("save", "3. Confirmation"));
        if (a.importable()) {
            sb.append("<p class=\"muted\">").append(a.writing())
                    .append(" élément(s) seront enregistrés dans la source. Les contenus "
                            + "« inchangé » et « ignoré » ne sont pas touchés. L'activation sur le "
                            + "serveur reste une opération séparée.</p>");
            sb.append("<div class=\"btnrow\"><button class=\"btn\" type=\"submit\" name=\"confirm\" "
                    + "value=\"true\">").append(Icons.icon("save"))
                    .append("Enregistrer dans la source</button></div>");
        } else {
            sb.append("<p class=\"muted\">").append(Http.esc(whyNot(a)))
                    .append(" Le bouton d'enregistrement reste indisponible : il n'y a aucun moyen "
                            + "de confirmer un import que l'analyse refuse.</p>");
        }
        return sb.append("</form>").toString();
    }

    private static String whyNot(ContentPackImport.Analysis a) {
        if (Diagnostic.hasError(a.envelope())) {
            return "Le pack lui-même est refusé.";
        }
        if (a.invalid() > 0) {
            return a.invalid() + " élément(s) sont inexploitables : corriger le pack puis relancer "
                    + "l'analyse.";
        }
        if (a.conflicts() > 0) {
            return a.conflicts() + " collision(s) attendent une décision explicite.";
        }
        return "Aucun élément à écrire : tout est déjà identique, ou volontairement ignoré.";
    }

    // ---- Résultat ------------------------------------------------------------------------------

    private static String appliedCard(List<ContentPackImport.WriteOutcome> applied) {
        long ok = applied.stream().filter(o -> o.result() != null && o.result().ok()).count();
        long failed = applied.stream().filter(o -> o.result() != null && !o.result().ok()).count();
        StringBuilder sb = new StringBuilder("<section class=\"card\">");
        sb.append(Ui.sectionTitle("save", "Import enregistré"));
        sb.append(Ui.banner(failed == 0 ? "success" : "warning",
                ok + " élément(s) enregistré(s) dans la source"
                        + (failed == 0 ? "." : ", " + failed + " en échec — détail ci-dessous.")
                        + " L'activation sur le serveur Minecraft reste une opération séparée."));
        sb.append(Ui.tableOpen("Élément", "Résultat", "Fichier"));
        for (ContentPackImport.WriteOutcome o : applied) {
            if (o.result() == null) {
                continue;
            }
            sb.append("<tr><td><code>").append(Http.esc(o.element().key())).append("</code></td><td>")
                    .append(o.result().ok() ? "<span class=\"badge text-bg-success\">écrit</span>"
                            : "<span class=\"badge text-bg-danger\">" + Http.esc(o.result().code())
                              + "</span> " + Http.esc(o.result().message()))
                    .append("</td><td class=\"muted\">")
                    .append(Http.esc(o.result().repoPath() == null ? "" : o.result().repoPath()))
                    .append("</td></tr>");
        }
        sb.append(Ui.tableClose());
        return sb.append("</section>").toString();
    }

    // ---- À propos ------------------------------------------------------------------------------

    private static String aboutCard() {
        return "<section class=\"card\">"
                + Ui.sectionTitle("docs", "Ce que l'import fait, et ne fait pas")
                + "<ul class=\"muted\">"
                + "<li><strong>Rien n'est écrit à l'analyse.</strong> Le pack est lu, validé et "
                + "comparé à la source ; le disque n'est touché qu'à la confirmation.</li>"
                + "<li><strong>Aucun écrasement silencieux.</strong> Un identifiant déjà présent "
                + "devient une collision qui bloque l'import jusqu'à ce que vous choisissiez de "
                + "remplacer ou de garder l'existant.</li>"
                + "<li><strong>Aucun chemin ne vient du fichier.</strong> La destination est "
                + "calculée depuis la famille et l'identifiant ; un pack ne peut pas écrire "
                + "ailleurs.</li>"
                + "<li><strong>Le contenu est ré-émis</strong> dans sa forme canonique par les "
                + "mêmes écrivains que les éditeurs — ce n'est jamais le fichier brut qui est "
                + "copié, et ce qui est enregistré est relisible par le serveur.</li>"
                + "<li><strong>Familles supportées</strong> : quêtes, stories et dialogues sont "
                + "écrits dans la source ; un PNJ logique inconnu du serveur y est créé après "
                + "l'écriture, sans placement ni liaison Citizens. Un PNJ déjà connu n'est pas "
                + "modifié.</li>"
                + "<li><strong>L'import n'active rien.</strong> Il écrit dans la source ; le "
                + "déploiement vers le serveur Minecraft reste une opération distincte.</li>"
                + "</ul>"
                + "<p class=\"muted\">Format, schéma et gabarits : voir "
                + "<a href=\"/content/export\">Export de contenu</a> et la fiche "
                + "<a href=\"/docs\">Content packs</a>.</p>"
                + "</section>";
    }

    // ---- Libellés ------------------------------------------------------------------------------

    private static String bannerType(Diagnostic.Level level) {
        return switch (level) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "info";
        };
    }

    private static String badgeClass(ContentPackImport.Status status) {
        return switch (status) {
            case NEW -> "text-bg-success";
            case MODIFIED -> "text-bg-info";
            case UNCHANGED -> "text-bg-secondary";
            case CONFLICT -> "text-bg-warning";
            case SKIPPED -> "text-bg-light text-dark";
            case INVALID -> "text-bg-danger";
        };
    }

    private static String statusLabel(ContentPackImport.Status status) {
        return switch (status) {
            case NEW -> "nouveau";
            case MODIFIED -> "modifié";
            case UNCHANGED -> "inchangé";
            case CONFLICT -> "conflit";
            case SKIPPED -> "ignoré";
            case INVALID -> "inexploitable";
        };
    }
}
