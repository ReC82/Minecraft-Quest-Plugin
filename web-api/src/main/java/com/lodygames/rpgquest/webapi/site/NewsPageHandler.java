package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.http.HttpResponses;
import com.lodygames.rpgquest.webapi.http.QueryParams;
import com.lodygames.rpgquest.webapi.news.NewsArticle;
import com.lodygames.rpgquest.webapi.news.NewsCategory;
import com.lodygames.rpgquest.webapi.news.NewsParser;
import com.lodygames.rpgquest.webapi.news.NewsRepository;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /actualites} (liste paginée, filtre {@code ?categorie=}) et {@code GET /actualites/<slug>} (#56).
 *
 * <p>Ne dépend pas de l'instantané Minecraft : les actualités restent lisibles quand le serveur de jeu
 * est arrêté. Un brouillon, une actualité programmée et un slug inconnu donnent la même page 404.</p>
 */
public final class NewsPageHandler implements ApiHandler {

    static final int PAGE_SIZE = 10;

    private final NewsRepository news;
    private final Site site;

    public NewsPageHandler(NewsRepository news, Site site) {
        this.news = news;
        this.site = site;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String rest = path.length() > "/actualites".length() ? path.substring("/actualites".length()) : "";
        rest = rest.startsWith("/") ? rest.substring(1) : rest;
        if (!rest.isEmpty()) {
            Optional<NewsArticle> article = rest.length() <= 80 && NewsParser.SLUG.matcher(rest).matches()
                    ? news.find(rest) : Optional.empty();
            return article.isPresent() ? article(exchange, article.get()) : notFound(exchange);
        }
        Map<String, String> query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
        Optional<NewsCategory> category = Optional.empty();
        if (query.containsKey("categorie")) {
            category = NewsCategory.fromId(query.get("categorie"));
            if (category.isEmpty()) {
                return notFound(exchange);
            }
        }
        List<NewsArticle> articles = category.map(news::published).orElseGet(news::published);
        int page = SitePaging.page(query);
        int pages = SitePaging.pageCount(articles.size(), PAGE_SIZE);
        if (page > pages) {
            return notFound(exchange);
        }
        String base = category.map(value -> "/actualites?categorie=" + value.id() + "&").orElse("/actualites?");
        String body = filters(category) + list(articles.subList((page - 1) * PAGE_SIZE,
                Math.min(articles.size(), page * PAGE_SIZE))) + SitePaging.pager(base, page, pages);
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta(PageMeta.NEWS,
                "Nouveautés, annonces, maintenances et événements de " + site.title() + ".", PageMeta.NEWS),
                category.map(value -> "Actualités — " + value.label()).orElse("Actualités"), true, body));
        return 200;
    }

    private int article(HttpExchange exchange, NewsArticle article) throws IOException {
        StringBuilder body = new StringBuilder("<article><p class=\"meta\">").append(meta(article)).append("</p>");
        body.append("<p><strong>").append(Html.escape(article.summary())).append("</strong></p>");
        body.append("<div class=\"content\">").append(NewsMarkup.render(article.body())).append("</div>");
        if (!article.tags().isEmpty()) {
            body.append("<p>");
            for (String tag : article.tags()) {
                body.append("<span class=\"tag\">").append(Html.escape(tag)).append("</span>");
            }
            body.append("</p>");
        }
        body.append("</article><p><a href=\"/actualites\">← Toutes les actualités</a></p>");
        HttpResponses.sendHtml(exchange, 200, PageLayout.render(site, new PageMeta("/actualites/" + article.slug(),
                article.summary(), PageMeta.NEWS), article.title(), true, body.toString()));
        return 200;
    }

    private int notFound(HttpExchange exchange) throws IOException {
        HttpResponses.sendHtml(exchange, 404, PageLayout.render(site, new PageMeta("", "", PageMeta.NEWS),
                "Actualité introuvable", true, "<p>Cette actualité n'existe pas ou n'est pas encore publiée.</p>"
                        + "<p><a href=\"/actualites\">Toutes les actualités</a></p>"));
        return 404;
    }

    private static String filters(Optional<NewsCategory> selected) {
        StringBuilder html = new StringBuilder("<nav aria-label=\"Catégories\"><ul class=\"tabs\"><li><a href=\"/actualites\"")
                .append(selected.isEmpty() ? " aria-current=\"page\"" : "").append(">Tout</a></li>");
        for (NewsCategory category : NewsCategory.values()) {
            html.append("<li><a href=\"/actualites?categorie=").append(category.id()).append('"')
                    .append(selected.orElse(null) == category ? " aria-current=\"page\"" : "").append('>')
                    .append(Html.escape(category.label())).append("</a></li>");
        }
        return html.append("</ul></nav>").toString();
    }

    private static String list(List<NewsArticle> articles) {
        if (articles.isEmpty()) {
            return "<p class=\"note\">Aucune actualité publiée pour le moment.</p>";
        }
        StringBuilder html = new StringBuilder();
        for (NewsArticle article : articles) {
            html.append(card(article, "h2"));
        }
        return html.toString();
    }

    /** Carte d'une actualité (liste et accueil) : catégorie, date, titre lié, résumé. */
    static String card(NewsArticle article, String heading) {
        return "<article class=\"card\" style=\"margin-bottom:1rem\"><p class=\"meta\">" + meta(article) + "</p><"
                + heading + "><a href=\"/actualites/" + article.slug() + "\">" + Html.escape(article.title()) + "</a></"
                + heading + "><p>" + Html.escape(article.summary()) + "</p></article>";
    }

    private static String meta(NewsArticle article) {
        return "<span class=\"tag\">" + Html.escape(article.category().label()) + "</span> <time datetime=\""
                + article.publishedAt() + "\">" + Html.escape(StatLabels.dateTime(article.publishedAt())) + "</time>"
                + (article.author().isEmpty() ? "" : " · " + Html.escape(article.author()));
    }
}
