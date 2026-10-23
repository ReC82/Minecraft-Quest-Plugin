package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.http.ApiHandler;
import com.lodygames.rpgquest.webapi.news.NewsArticle;
import com.lodygames.rpgquest.webapi.news.NewsRepository;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code GET /robots.txt} et {@code GET /sitemap.xml} (#56).
 *
 * <p>Le sitemap ne liste que les pages publiques stables et les actualités publiées ; jamais de
 * profil de joueur (un joueur peut se masquer à tout moment, son URL ne doit pas être poussée aux
 * moteurs de recherche). {@code /compte} est exclu de l'indexation.</p>
 */
public final class SeoHandler implements ApiHandler {

    private static final List<String> PAGES = List.of(PageMeta.HOME, PageMeta.NEWS, PageMeta.EVENTS, PageMeta.BOARDS, PageMeta.INFO,
            PageMeta.PROGRESSION,
            "/status", "/wiki", "/confidentialite");

    private final NewsRepository news;
    private final Site site;

    public SeoHandler(NewsRepository news, Site site) {
        this.news = news;
        this.site = site;
    }

    @Override
    public int handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/robots.txt")) {
            String sitemap = site.baseUrl().isEmpty() ? "" : "Sitemap: " + site.baseUrl() + "/sitemap.xml\n";
            return send(exchange, "text/plain; charset=utf-8", "User-agent: *\nDisallow: /compte\nDisallow: /api/\nDisallow: /demo/\n" + sitemap);
        }
        if (path.equals("/sitemap.xml")) {
            return send(exchange, "application/xml; charset=utf-8", sitemap());
        }
        return send404(exchange);
    }

    private String sitemap() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String page : PAGES) {
            xml.append("<url><loc>").append(Html.escape(site.baseUrl() + page)).append("</loc></url>\n");
        }
        for (NewsArticle article : news.published()) {
            xml.append("<url><loc>").append(Html.escape(site.baseUrl() + "/actualites/" + article.slug()))
                    .append("</loc><lastmod>").append(article.publishedAt().toString(), 0, 10).append("</lastmod></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    private static int send(HttpExchange exchange, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return 200;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        return 200;
    }

    private static int send404(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, -1);
        return 404;
    }
}
