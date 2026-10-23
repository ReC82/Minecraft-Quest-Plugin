package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.docs.DocLibrary;
import com.lodygames.rpgquest.panel.docs.DocPage;
import com.lodygames.rpgquest.panel.docs.DocSearchIndex;
import com.lodygames.rpgquest.panel.docs.Markdown;
import com.lodygames.rpgquest.panel.http.Http;
import java.util.List;
import java.util.Map;

/**
 * Rendu HTML du centre de documentation (issue #49) : page d'accueil (recherche + catégories +
 * raccourcis), résultats de recherche, et fiche unique (Markdown rendu, fil d'Ariane, sommaire,
 * commandes copiables). #780 : sommaire en tête de fiche (colonne fixe sur ordinateur, bloc
 * dépliable au-dessus du texte sur téléphone) construit sur les ancres réellement rendues,
 * fiches liées, fiche précédente / suivante, et résultats de recherche menant à la bonne section. Rendu chaîne, sans état — testable directement. Le contenu vient de
 * {@link DocLibrary} (fichiers Markdown versionnés, chargés en mémoire) ; aucun chemin du
 * navigateur n'est jamais ouvert : les fiches sont adressées par un {@code slug} interne résolu
 * ici.
 */
public final class DocsPages {

    private final DocLibrary library;
    private final DocSearchIndex index;

    /** Raccourcis « Comment faire ? » de la page d'accueil : (libellé, requête, icône). */
    private static final List<String[]> SHORTCUTS = List.of(
            new String[] {"Taguer un PNJ", "tag npc", "npc"},
            new String[] {"Mettre un skin de PNJ", "skin", "npc"},
            new String[] {"Reset d'un joueur", "reset joueur", "players"},
            new String[] {"Compléter une quête", "quest complete", "quests"},
            new String[] {"Rollback / déploiement", "rollback", "back"},
            new String[] {"Déployer sur VeryGames", "verygames deploiement", "server"},
            new String[] {"Tester les Claims", "claim test", "world"},
            new String[] {"Tester le Wild", "wild", "world"});

    private static String catIcon(String category) {
        String c = category == null ? "" : category.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("pnj") || c.contains("citizens")) return "npc";
        if (c.contains("joueur")) return "players";
        if (c.contains("quête") || c.contains("quete") || c.contains("stor")) return "quests";
        if (c.contains("claim")) return "world";
        if (c.contains("wild")) return "world";
        if (c.contains("verygames")) return "server";
        if (c.contains("déploiement") || c.contains("deploiement") || c.contains("rollback")) return "back";
        if (c.contains("admin")) return "admin";
        return "book";
    }

    public DocsPages(DocLibrary library) {
        this.library = library;
        this.index = DocSearchIndex.build(library);
    }

    // ---- Accueil + recherche ---------------------------------------------------------------

    /** {@code /docs} : accueil si {@code q} vide, sinon résultats de recherche. */
    public String home(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.strip();
        StringBuilder sb = new StringBuilder();
        sb.append(Ui.pageHeader("docs", "Documentation",
                "Wiki d'administration privé — commandes, procédures et fiches opérationnelles. "
                        + "Source de vérité : les fichiers Markdown versionnés dans le dépôt.", ""));
        sb.append(searchForm(q, true));

        if (library.isEmpty()) {
            sb.append(Ui.empty("book", "Aucune fiche chargée."));
            return sb.toString();
        }
        if (!q.isEmpty()) {
            return sb.append(searchResults(q)).toString();
        }

        sb.append(Ui.sectionTitle("target", "Comment faire ?")).append("<div class=\"doc-shortcuts\">");
        for (String[] s : SHORTCUTS) {
            sb.append("<a class=\"doc-chip\" href=\"/docs?q=").append(Http.esc(urlEncode(s[1]))).append("\">")
                    .append(Icons.icon(s.length > 2 ? s[2] : "search"))
                    .append(Http.esc(s[0])).append("</a>");
        }
        sb.append("</div>");

        sb.append(Ui.sectionTitle("book", "Catégories")).append("<div class=\"doc-cats\">");
        for (Map.Entry<String, List<DocPage>> e : library.byCategory().entrySet()) {
            sb.append("<article class=\"doc-cat\"><h3>").append(Icons.icon(catIcon(e.getKey())))
                    .append(Http.esc(e.getKey())).append("</h3><ul>");
            for (DocPage p : e.getValue()) {
                sb.append("<li><a href=\"/docs/").append(Http.esc(p.slug())).append("\">")
                        .append(Http.esc(p.title())).append("</a></li>");
            }
            sb.append("</ul></article>");
        }
        sb.append("</div>");
        sb.append(assetScript());
        return sb.toString();
    }

    private String searchResults(String q) {
        List<DocSearchIndex.Hit> hits = index.search(q);
        StringBuilder sb = new StringBuilder();
        sb.append("<p class=\"doc-crumbs\"><a href=\"/docs\">Documentation</a> <span>›</span> Recherche</p>");
        if (hits.isEmpty()) {
            sb.append("<div class=\"empty\">").append(Icons.icon("search"))
                    .append("Aucune fiche ne correspond à « ").append(Http.esc(q))
                    .append(" ». Essaie un mot plus court (ex. <code>npc</code>, <code>skin</code>, "
                            + "<code>rollback</code>).</div>");
            return sb.append(assetScript()).toString();
        }
        sb.append("<p class=\"count-note\">").append(hits.size()).append(" résultat(s) pour « ")
                .append(Http.esc(q)).append(" »</p><div class=\"doc-hits\">");
        for (DocSearchIndex.Hit hit : hits) {
            DocPage p = hit.page();
            sb.append("<a class=\"doc-hit\" href=\"").append(Http.esc(hit.href())).append("\">")
                    .append("<span class=\"doc-hit-cat\">").append(Http.esc(p.category())).append("</span>")
                    .append("<span class=\"doc-hit-title\">").append(Http.esc(p.title())).append("</span>");
            if (!hit.sectionTitle().isEmpty()) {
                sb.append("<span class=\"doc-hit-sec\">Section : ").append(Http.esc(hit.sectionTitle())).append("</span>");
            }
            sb.append("<span class=\"doc-hit-snip\">").append(Http.esc(hit.snippet())).append("</span>")
                    .append("</a>");
        }
        sb.append("</div>");
        return sb.append(assetScript()).toString();
    }

    // ---- Fiche unique --------------------------------------------------------------------

    /** {@code /docs/<slug>} : rend la fiche, ou une page 404 (jamais une erreur 500) si slug inconnu. */
    public String page(String slug, String rawQuery) {
        return library.bySlug(slug).map(p -> renderPage(p, rawQuery)).orElseGet(this::notFound);
    }

    /** {@code true} si le slug correspond à une fiche existante (pour choisir le code HTTP). */
    public boolean exists(String slug) {
        return library.bySlug(slug).isPresent();
    }

    private String renderPage(DocPage p, String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.strip();
        StringBuilder sb = new StringBuilder();
        sb.append(searchForm(q, false));
        sb.append("<p class=\"doc-crumbs\"><a href=\"/docs\">Documentation</a> <span>›</span> ")
                .append(Http.esc(p.category())).append(" <span>›</span> ").append(Http.esc(p.title()))
                .append("</p>");

        List<Markdown.Heading> toc = tableOfContents(p.markdown());
        List<DocPage> related = library.related(p);
        sb.append("<div class=\"doc-layout\">");

        // Sommaire (ordinateur) : colonne fixe, titres de niveau 2/3 avec les ancres rendues.
        sb.append("<nav class=\"doc-toc\" aria-label=\"Sommaire de la fiche\"><p class=\"doc-toc-h\">Sur cette fiche</p>");
        if (toc.isEmpty()) {
            sb.append("<p class=\"muted\">—</p>");
        } else {
            sb.append("<ul>");
            for (Markdown.Heading t : toc) {
                sb.append("<li class=\"lvl").append(t.level()).append("\"><a href=\"#").append(Http.esc(t.id()))
                        .append("\">").append(Http.esc(t.text())).append("</a></li>");
            }
            sb.append("</ul>");
        }
        if (!related.isEmpty()) {
            sb.append("<p class=\"doc-toc-h\">Fiches liées</p><ul>");
            for (DocPage r : related) {
                sb.append("<li><a href=\"/docs/").append(Http.esc(r.slug())).append("\">")
                        .append(Http.esc(r.title())).append("</a></li>");
            }
            sb.append("</ul>");
        }
        if (!p.tags().isEmpty()) {
            sb.append("<p class=\"doc-toc-h\">Tags</p><p class=\"doc-tags\">");
            for (String tag : p.tags()) {
                sb.append(Ui.badge(tag));
            }
            sb.append("</p>");
        }
        sb.append("</nav>");

        // Corps
        sb.append("<article class=\"doc-body\">");
        sb.append("<h1>").append(Http.esc(p.title())).append("</h1>");
        sb.append(mobileToc(toc));
        sb.append(Markdown.render(p.markdown()));
        sb.append(relatedBlock(related));
        sb.append(pager(p));
        sb.append("<p class=\"doc-source muted\">Source : <code>").append(Http.esc(p.sourcePath()))
                .append("</code> — modifiable via Git (pull request).</p>");
        sb.append("</article>");

        sb.append("</div>");
        return sb.append(assetScript()).toString();
    }

    /** Sommaire au-dessus du texte, pour les petits écrans (masqué sur ordinateur) : grandes sections. */
    private static String mobileToc(List<Markdown.Heading> toc) {
        List<Markdown.Heading> top = toc.stream().filter(t -> t.level() == 2).toList();
        if (top.size() < 2) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<details class=\"doc-toc-m\" open><summary>Sommaire (")
                .append(top.size()).append(" parties)</summary><ol>");
        for (Markdown.Heading t : top) {
            sb.append("<li><a href=\"#").append(Http.esc(t.id())).append("\">").append(Http.esc(t.text()))
                    .append("</a></li>");
        }
        return sb.append("</ol></details>").toString();
    }

    private static String relatedBlock(List<DocPage> related) {
        if (related.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<nav class=\"doc-related\" aria-label=\"Fiches liées\">"
                + "<p class=\"doc-toc-h\">Fiches liées</p><ul>");
        for (DocPage r : related) {
            sb.append("<li><a href=\"/docs/").append(Http.esc(r.slug())).append("\">").append(Http.esc(r.title()))
                    .append("</a> <span class=\"muted\">").append(Http.esc(r.category())).append("</span></li>");
        }
        return sb.append("</ul></nav>").toString();
    }

    /** Fiche précédente / suivante, dans l'ordre de la page d'accueil (catégorie, ordre, titre). */
    private String pager(DocPage p) {
        var prev = library.previous(p);
        var next = library.next(p);
        if (prev.isEmpty() && next.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<nav class=\"doc-pager\" aria-label=\"Fiche précédente et suivante\">");
        prev.ifPresentOrElse(d -> sb.append("<a class=\"doc-pager-prev\" rel=\"prev\" href=\"/docs/")
                .append(Http.esc(d.slug())).append("\"><span>← Fiche précédente</span>").append(Http.esc(d.title()))
                .append("</a>"), () -> sb.append("<span></span>"));
        next.ifPresent(d -> sb.append("<a class=\"doc-pager-next\" rel=\"next\" href=\"/docs/")
                .append(Http.esc(d.slug())).append("\"><span>Fiche suivante →</span>").append(Http.esc(d.title()))
                .append("</a>"));
        return sb.append("</nav>").toString();
    }

    private String notFound() {
        return "<h1>Fiche introuvable</h1>"
                + "<p class=\"sub\">Aucune fiche de documentation ne porte cet identifiant.</p>"
                + "<p><a href=\"/docs\">← Retour au centre de documentation</a></p>";
    }

    // ---- fragments --------------------------------------------------------------------

    /** {@code autofocus} seulement sur l'accueil : sur une fiche, il ouvrirait le clavier du téléphone. */
    private static String searchForm(String q, boolean autofocus) {
        return "<form class=\"doc-search\" method=\"get\" action=\"/docs\">"
                + "<input type=\"search\" name=\"q\" value=\"" + Http.esc(q) + "\"" + (autofocus ? " autofocus" : "") + " "
                + "placeholder=\"Rechercher : tag npc, skin, reset joueur, rollback, claim, wild…\" "
                + "aria-label=\"Rechercher dans la documentation\">"
                + "<button class=\"btn\" type=\"submit\">Rechercher</button></form>";
    }

    /**
     * Le script progressif ({@code /assets/panel.js}, bouton « Copier ») est désormais chargé
     * globalement par {@link Layout} — plus rien à ajouter au niveau de la page.
     */
    private static String assetScript() {
        return "";
    }

    /**
     * Sommaire : titres de niveau 2 et 3 <strong>tels que rendus</strong> par {@link Markdown}
     * (mêmes ancres, apostrophes et doublons compris — #780 : l'ancien calcul séparé donnait
     * {@code l-apercu} quand la page rendait {@code lapercu}).
     */
    static List<Markdown.Heading> tableOfContents(String markdown) {
        return Markdown.headings(markdown).stream().filter(h -> h.level() == 2 || h.level() == 3).toList();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
