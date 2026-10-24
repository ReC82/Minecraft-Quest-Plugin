package com.lodygames.rpgquest.webapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V1 publique du portail (#56), de bout en bout par HTTP : actualités indépendantes du serveur de jeu,
 * brouillons invisibles, classements par portée (#54), profils limités, aucun pseudo tant que
 * {@code player-data-public=false} (#55), SEO de base.
 */
class PublicPortalV1Test {

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServerBootstrap bootstrap;
    private int port;

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
    }

    private void start(boolean playerDataPublic, String extraProperties) throws Exception {
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "port=0\nrate-limit-per-minute=1000\npublic-base-url=https://portail.example/\n"
                + "site-title=LodyQuests\nplayer-data-public=" + playerDataPublic
                + "\nsnapshot-file=" + slash(tempDir.resolve("snapshot.json"))
                + "\nnews-dir=" + slash(tempDir.resolve("news")) + "\n" + extraProperties);
        WebApiConfig config = WebApiConfigLoader.load(properties, "jeton", null);
        bootstrap = new HttpServerBootstrap(config, new SnapshotStore(config.snapshotFile(),
                config.snapshotMaxAgeSeconds(), Logger.getLogger("portal-v1-test")), null);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void news(String slug, String status, String category, String date) throws Exception {
        Files.createDirectories(tempDir.resolve("news"));
        Files.writeString(tempDir.resolve("news").resolve(slug + ".md"), "title: Titre " + slug + "\nsummary: Résumé "
                + slug + "\ndate: " + date + "\nstatus: " + status + "\ncategory: " + category
                + "\n---\nTexte de **" + slug + "** <b>brut</b>\n", StandardCharsets.UTF_8);
    }

    private void snapshot() throws Exception {
        Map<String, Object> season = new LinkedHashMap<>();
        season.put("id", "season_01");
        season.put("name", "Saison 1");
        season.put("start", "2026-10-01T00:00:00Z");
        season.put("end", "2027-01-01T00:00:00Z");
        season.put("status", "ACTIVE");
        season.put("leaderboards", Map.of("RPG_XP_COMBAT", List.of(Map.of("name", "Bob", "value", 90, "rank", 1))));
        Map<String, Object> event = Map.of("id", "zombie_weekend", "name", "Week-end des zombies",
                "start", "2026-11-07T18:00:00Z", "end", "2026-11-09T22:00:00Z", "status", "SCHEDULED",
                "leaderboards", Map.of("RPG_XP_COMBAT", List.of()));
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("scopes", List.of("lifetime", "season", "event"));
        statistics.put("lifetime", Map.of(
                "RPG_XP_GLOBAL", List.of(Map.of("name", "Alice", "value", 4500, "rank", 1, "uuid", "uuid-secret"),
                        Map.of("name", "Bob", "value", 300, "rank", 2)),
                "QUESTS_COMPLETED", List.of(Map.of("name", "Bob", "value", 7, "rank", 1),
                        Map.of("name", "Alice", "value", 3, "rank", 2))));
        statistics.put("season", season);
        statistics.put("events", List.of(event));
        statistics.put("catalog", Map.of("metrics", List.of(Map.of("id", "ENTITY_KILL", "dated", true,
                "trackedSince", "2026-10-16T08:00:00Z"))));
        Files.writeString(tempDir.resolve("snapshot.json"), Json.write(Map.of(
                "schemaVersion", 2,
                "generatedAt", Instant.now().toString(),
                "server", Map.of("online", true, "playerCount", 2, "maxPlayers", 20),
                "players", List.of("Alice", "Bob"),
                "leaderboards", Map.of(),
                "catalog", List.of(),
                "announcements", List.of(),
                "statistics", statistics,
                "profiles", List.of(
                        Map.of("name", "Alice", "skills", Map.of("GLOBAL", 4500, "MINING", 1200), "questsCompleted", 3,
                                "stats", Map.of("ENTITY_KILL", 1234, "CITY_DISCOVERED", 2),
                                "uuid", "uuid-secret", "email", "alice@example.org"),
                        Map.of("name", "Bob", "skills", Map.of("GLOBAL", 300), "questsCompleted", 7)))),
                StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void newsStayReadableWithoutAnyGameServerAndDraftsStayHidden() throws Exception {
        news("ouverture", "published", "announcement", "2026-10-01T10:00:00Z");
        news("secret", "draft", "news", "2026-10-02T10:00:00Z");
        news("futur", "published", "event", "2099-01-01T10:00:00Z");
        start(false, "");

        HttpResponse<String> home = get("/");
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("Titre ouverture"), "dernière actualité sur l'accueil, sans instantané");
        assertTrue(home.body().contains("État inconnu"), "aucune donnée : état inconnu (#421)");
        assertFalse(home.body().contains("Hors ligne"), "jamais « hors ligne » sans arrêt annoncé (#421)");

        HttpResponse<String> list = get("/actualites");
        assertTrue(list.body().contains("href=\"/actualites/ouverture\""));
        for (String hidden : List.of("Titre secret", "Titre futur")) {
            assertFalse(list.body().contains(hidden), hidden);
            assertFalse(home.body().contains(hidden), hidden);
        }

        HttpResponse<String> article = get("/actualites/ouverture");
        assertEquals(200, article.statusCode());
        assertTrue(article.body().contains("<strong>ouverture</strong>"));
        assertTrue(article.body().contains("&lt;b&gt;brut&lt;/b&gt;"), "aucun HTML du fichier n'est interprété");
        assertTrue(article.body().contains("<link rel=\"canonical\" href=\"https://portail.example/actualites/ouverture\">"));
        assertFalse(article.body().contains("État du serveur de jeu inconnu"),
                "une actualité n'affiche pas le bandeau du serveur de jeu");

        assertEquals(404, get("/actualites/secret").statusCode());
        assertEquals(404, get("/actualites/futur").statusCode());
        assertEquals(404, get("/actualites/..%2Fweb-api.properties").statusCode());
        assertEquals(404, get("/actualites?categorie=inconnue").statusCode());
        assertEquals(404, get("/actualites?page=2").statusCode());
        assertEquals(404, get("/actualites?page=abc").statusCode());
        assertTrue(get("/actualites?categorie=announcement").body().contains("Titre ouverture"));
        assertFalse(get("/actualites?categorie=maintenance").body().contains("Titre ouverture"));
    }

    @Test
    void newsArePaginated() throws Exception {
        for (int i = 10; i < 22; i++) {
            news("article-" + i, "published", "news", "2026-09-" + i + "T10:00:00Z");
        }
        start(false, "");

        String first = get("/actualites").body();
        String second = get("/actualites?page=2").body();
        assertTrue(first.contains("Titre article-21") && !first.contains("Titre article-11"));
        assertTrue(second.contains("Titre article-11") && second.contains("Titre article-10"));
        assertTrue(first.contains("href=\"/actualites?page=2\""));
    }

    @Test
    void noPseudonymAnywhereWhilePlayerDataIsPrivate() throws Exception {
        snapshot();
        news("ouverture", "published", "announcement", "2026-10-01T10:00:00Z");
        start(false, "");

        for (String path : List.of("/", "/classements", "/leaderboards", "/joueurs", "/joueurs/Alice",
                "/classements?periode=season_01", "/sitemap.xml", "/infos", "/actualites", "/evenements",
                "/progression", "/demo/classements", "/demo/joueurs", "/demo/evenements")) {
            HttpResponse<String> response = get(path);
            for (String name : List.of("Alice", "Bob")) {
                assertFalse(response.body().contains(name), path + " publie " + name);
            }
        }
        String home = get("/").body();
        assertTrue(home.contains("Saison 1"), "noms de périodes (sans joueur) affichés");
        assertTrue(home.contains("ouvriront quand"));
    }

    @Test
    void leaderboardsShowEachScopeWithRanksAndNothingPrivate() throws Exception {
        snapshot();
        start(true, "");

        String lifetime = get("/classements").body();
        assertTrue(lifetime.contains("XP globale") && lifetime.contains("Quêtes terminées"));
        assertTrue(lifetime.indexOf("XP globale") < lifetime.indexOf("Quêtes terminées"));
        assertTrue(lifetime.contains("href=\"/classements?periode=season_01\""));
        assertTrue(lifetime.contains("4 500 XP") || lifetime.contains("4 500 XP"), "nombres lisibles");
        assertTrue(lifetime.contains("Données relevées sur le serveur le"));

        String season = get("/classements?periode=season_01").body();
        assertTrue(season.contains("En cours") && season.contains("XP Combat") && season.contains("Bob"));
        assertFalse(season.contains("Alice"), "la saison n'affiche que ses propres classements");
        assertTrue(get("/classements?periode=zombie_weekend").body().contains("Pas encore commencé"));
        assertEquals(404, get("/classements?periode=inconnue").statusCode());
        assertEquals(404, get("/classements?periode=%3Cscript%3E").statusCode());

        for (String path : List.of("/", "/classements", "/joueurs", "/joueurs/Alice")) {
            String body = get(path).body();
            assertFalse(body.contains("uuid-secret") || body.contains("alice@example.org"), path);
        }
    }

    @Test
    void aProfileShowsStatsAndRanksAndThePlayerSearchRedirects() throws Exception {
        snapshot();
        start(true, "");

        String alice = get("/joueurs/alice").body();
        assertTrue(alice.contains("XP globale : ") && alice.contains("<strong>Minage</strong>"));
        assertTrue(alice.contains("Quêtes terminées<br><span class=\"note\">2<sup>e</sup>"), alice);
        assertTrue(alice.contains("<strong>Monstres vaincus</strong><span>1\u202f234 monstres<br><span class=\"note\">"
                + "depuis le 16 octobre 2026</span>"), alice);
        assertTrue(alice.contains("<strong>Villes découvertes</strong><span>2 villes</span>"), alice);
        assertFalse(get("/joueurs/Bob").body().contains(">Statistiques<"), "aucune section sans valeur");
        HttpResponse<String> search = get("/joueurs?pseudo=Bob");
        assertEquals(303, search.statusCode());
        assertEquals("/joueurs/Bob", search.headers().firstValue("Location").orElse(""));
        assertEquals(404, get("/joueurs/Carol").statusCode());
        assertEquals(200, get("/joueurs?pseudo=%3Cx%3E").statusCode(), "pseudo invalide : la liste, pas une redirection");
    }

    @Test
    void homeShowsPreviewStatusAndJoinInformation() throws Exception {
        snapshot();
        start(true, "server-address=play.example.org\nminecraft-version=1.21.4\n");

        String home = get("/").body();
        assertTrue(home.contains("En ligne") && home.contains("2 joueur(s) connecté(s)"));
        assertTrue(home.contains("Saison 1") && home.contains("Week-end des zombies"));
        assertTrue(home.contains("href=\"/joueurs/Alice\""), "aperçu des classements");
        String infos = get("/infos").body();
        assertTrue(infos.contains("play.example.org") && infos.contains("1.21.4") && infos.contains("id=\"rejoindre\""));
    }

    @Test
    void robotsAndSitemapListOnlyStablePublicPages() throws Exception {
        snapshot();
        news("ouverture", "published", "announcement", "2026-10-01T10:00:00Z");
        news("secret", "draft", "news", "2026-10-02T10:00:00Z");
        start(true, "");

        String robots = get("/robots.txt").body();
        assertTrue(robots.contains("Disallow: /compte") && robots.contains("Sitemap: https://portail.example/sitemap.xml"));
        String sitemap = get("/sitemap.xml").body();
        assertTrue(sitemap.contains("<loc>https://portail.example/actualites/ouverture</loc>"));
        assertFalse(sitemap.contains("secret"));
        assertFalse(sitemap.contains("/joueurs/"), "aucun profil poussé aux moteurs de recherche");
        assertTrue(get("/health").body().contains("\"news\":1"));
    }

    @Test
    void everyPublicPageIsMobileReadyAndAccessible() throws Exception {
        start(false, "");

        for (String path : List.of("/", "/actualites", "/classements", "/joueurs", "/infos", "/status",
                "/confidentialite", "/compte", "/evenements", "/progression", "/demo/classements", "/demo/joueurs",
                "/demo/joueurs/Demo_Aube", "/demo/evenements")) {
            String body = get(path).body();
            assertTrue(body.contains("name=\"viewport\""), path);
            assertTrue(body.contains("href=\"#contenu\"") && body.contains("<main id=\"contenu\">"), path);
            assertFalse(body.contains("<script"), path + " : aucun script");
        }
        assertTrue(get("/actualites").body().contains("href=\"/actualites\" aria-current=\"page\""));
    }

    @Test
    void iconSpriteIsServedLocallyWithLongCacheAndPagesUseIt() throws Exception {
        start(true, "");
        snapshot();

        String home = get("/").body();
        String version = com.lodygames.rpgquest.webapi.site.PortalIcons.VERSION;
        assertTrue(home.contains("/assets/lq-icons.svg?v=" + version + "#lq-trophy"), "menu illustré (#428)");
        assertTrue(home.contains("NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."));
        assertFalse(home.contains("<use href=\"http"), "aucune icône distante");
        assertTrue(get("/classements").body().contains("class=\"p1 pos1\""), "podium or / argent / bronze");

        HttpResponse<String> sprite = get("/assets/lq-icons.svg?v=" + version);
        assertEquals(200, sprite.statusCode());
        assertTrue(sprite.headers().firstValue("Content-Type").orElse("").startsWith("image/svg+xml"));
        assertEquals("public, max-age=31536000, immutable", sprite.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(sprite.body().contains("<symbol id=\"lq-unknown\""));
        assertEquals("public, max-age=3600", get("/assets/lq-icons.svg").headers().firstValue("Cache-Control").orElse(""));

        String etag = sprite.headers().firstValue("ETag").orElseThrow();
        HttpResponse<String> cached = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/assets/lq-icons.svg?v=" + version)).header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, cached.statusCode());
        assertEquals(404, get("/assets/../snapshot.json").statusCode());
        assertEquals(404, get("/assets/autre.svg").statusCode());
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/assets/lq-icons.svg")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }
}
