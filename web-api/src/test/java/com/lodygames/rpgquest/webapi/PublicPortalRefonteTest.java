package com.lodygames.rpgquest.webapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.json.Json;
import com.lodygames.rpgquest.webapi.site.DemoData;
import com.lodygames.rpgquest.webapi.site.StatLabels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Refonte du portail public (#56, lot 3 de la mission WebPortal) : pages de démonstration étiquetées et
 * isolées des vraies données, page Événements sans pseudo, filtres par thème, podium, page Progresser.
 */
class PublicPortalRefonteTest {

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

    private void start(boolean playerDataPublic) throws Exception {
        Path properties = tempDir.resolve("web-api.properties");
        Files.writeString(properties, "port=0\nrate-limit-per-minute=1000\npublic-base-url=https://portail.example/\n"
                + "site-title=LodyQuests\nplayer-data-public=" + playerDataPublic
                + "\nsnapshot-file=" + tempDir.resolve("snapshot.json").toString().replace('\\', '/')
                + "\nnews-dir=" + tempDir.resolve("news").toString().replace('\\', '/') + "\n");
        WebApiConfig config = WebApiConfigLoader.load(properties, "jeton", null);
        bootstrap = new HttpServerBootstrap(config, new SnapshotStore(config.snapshotFile(),
                config.snapshotMaxAgeSeconds(), Logger.getLogger("portal-refonte-test")), null);
        bootstrap.start();
        port = bootstrap.boundPort();
    }

    /** Un vrai instantané : « Realname » ne doit JAMAIS apparaître sur une page de démonstration. */
    private void snapshot() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "chasse");
        event.put("name", "Chasse aux araignées");
        event.put("start", now.minus(1, ChronoUnit.DAYS).toString());
        event.put("end", now.plus(2, ChronoUnit.DAYS).toString());
        event.put("status", "ACTIVE");
        event.put("ranking", "COMMUNITY_GOAL");
        event.put("community", Map.of("goal", 1000, "total", 420));
        event.put("leaderboards", Map.of("ENTITY_KILL", List.of(Map.of("name", "Realname", "value", 42, "rank", 1))));
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("scopes", List.of("lifetime", "event"));
        statistics.put("lifetime", Map.of("RPG_XP_GLOBAL", List.of(Map.of("name", "Realname", "value", 900, "rank", 1))));
        statistics.put("events", List.of(event));
        Files.writeString(tempDir.resolve("snapshot.json"), Json.write(Map.of(
                "schemaVersion", 2, "generatedAt", now.toString(),
                "server", Map.of("online", true, "playerCount", 1, "maxPlayers", 20),
                "players", List.of("Realname"), "leaderboards", Map.of(), "catalog", List.of(), "announcements", List.of(),
                "statistics", statistics,
                "profiles", List.of(Map.of("name", "Realname", "skills", Map.of("GLOBAL", 900), "questsCompleted", 1)))),
                StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void demoPagesAreLabelledNotIndexedAndNeverShowRealPlayers() throws Exception {
        snapshot();
        start(true);

        for (String path : List.of("/demo/classements", "/demo/classements?categorie=combat", "/demo/joueurs",
                "/demo/joueurs/Demo_Aube", "/demo/evenements", "/demo/classements?periode=demo_squelettes")) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), path);
            String body = response.body();
            assertTrue(body.contains("class=\"demo-banner\"") && body.contains("joueurs fictifs"), path + " : bandeau");
            assertTrue(body.contains("<meta name=\"robots\" content=\"noindex, nofollow\">"), path + " : noindex");
            assertFalse(body.contains("rel=\"canonical\""), path);
            assertFalse(body.contains("Realname") || body.contains("Chasse aux araignées"), path + " : vraie donnée");
            assertFalse(body.contains("href=\"/joueurs/"), path + " : un lien quitte la démonstration");
        }
        String boards = get("/demo/classements").body();
        assertTrue(boards.contains("href=\"/demo/joueurs/Demo_"), "profils fictifs liés");
        assertTrue(boards.contains("class=\"podium\""));
        assertEquals(404, get("/demo/joueurs/Realname").statusCode(), "un vrai pseudo n'existe pas en démonstration");
        assertEquals(303, get("/demo/joueurs?pseudo=Demo_Kiwi").statusCode());
        assertEquals("/demo/joueurs/Demo_Kiwi", get("/demo/joueurs?pseudo=Demo_Kiwi").headers().firstValue("Location").orElse(""));
    }

    @Test
    void demoStaysAvailableWhilePlayerDataIsPrivateAndPrivatePagesPointToIt() throws Exception {
        snapshot();
        start(false);

        assertTrue(get("/demo/classements").body().contains("Demo_"));
        assertTrue(get("/classements").body().contains("href=\"/demo/classements\""));
        assertTrue(get("/joueurs").body().contains("href=\"/demo/joueurs\""));
        String home = get("/").body();
        assertTrue(home.contains("href=\"/demo/classements\""), "aperçu fictif proposé depuis l'accueil");
        assertFalse(home.contains("Demo_"), "aucun joueur fictif mélangé à l'accueil réel");
    }

    @Test
    void demoProfileShowsTracksMedalsAndBestRank() throws Exception {
        start(false);

        String profile = get("/demo/joueurs/demo_elfe").body();
        assertTrue(profile.contains("class=\"profile-hero\"") && profile.contains("quêtes terminées"));
        assertTrue(profile.contains("Pistes de progression") && profile.contains("Exploration"));
        assertTrue(profile.contains("<span style=\"width:100%\">"), "la piste la plus forte remplit sa barre");
        assertTrue(profile.contains("Médailles de classement") && profile.contains("class=\"rank podium-1\""),
                "Demo_Elfe est 1re aux quêtes terminées");
        assertTrue(profile.contains("Meilleur rang : <strong>1<sup>er</sup>"));
    }

    @Test
    void eventsPageGroupsPeriodsWithoutAnyPseudonym() throws Exception {
        snapshot();
        start(false);

        String events = get("/evenements").body();
        assertTrue(events.contains("Chasse aux araignées") && events.contains("En cours"));
        assertTrue(events.contains("Objectif commun") && events.contains("420 / 1"), "total agrégé, sans pseudo");
        assertFalse(events.contains("Realname"));
        assertFalse(events.contains("Voir le classement"), "pas de lien vers des classements non publiés");
        assertTrue(events.contains("href=\"/evenements\" aria-current=\"page\""));

        String demo = get("/demo/evenements").body();
        assertTrue(demo.contains("Nuit des squelettes (démo)") && demo.contains("À venir") && demo.contains("Terminés"));
        assertTrue(demo.contains("href=\"/demo/classements?periode=demo_squelettes\""));
        assertTrue(demo.contains("Se termine dans") && demo.contains("Commence dans"));
    }

    @Test
    void eventsPageLinksToPublishedBoardsAndSaysWhenThereIsNothing() throws Exception {
        start(true);
        assertTrue(get("/evenements").body().contains("Aucune saison ni aucun événement"));
        assertTrue(get("/evenements").body().contains("href=\"/demo/evenements\""));
        snapshot();
        assertTrue(get("/evenements").body().contains("href=\"/classements?periode=chasse\""));
    }

    @Test
    void themeFilterKeepsOnlyMatchingBoardsAndKeepsThePeriod() throws Exception {
        start(false);

        String all = get("/demo/classements").body();
        assertTrue(all.contains("XP Combat") && all.contains("Quêtes terminées"));
        assertTrue(all.contains("href=\"/demo/classements?categorie=combat\""));
        String combat = get("/demo/classements?categorie=combat").body();
        assertTrue(combat.contains("XP Combat") && !combat.contains("<span>Quêtes terminées"));
        assertTrue(combat.contains("href=\"/demo/classements?periode=demo_saison&amp;categorie=combat\""),
                "changer de période garde le thème");
        assertEquals(200, get("/demo/classements?categorie=%3Cx%3E").statusCode(), "thème inconnu : tout afficher");
        assertTrue(get("/demo/classements?categorie=%3Cx%3E").body().contains("Quêtes terminées"));
    }

    @Test
    void podiumKeepsRankOrderInHtmlAndShowsTies() throws Exception {
        start(false);
        String body = get("/demo/classements?categorie=aventure").body();
        int podium = body.indexOf("class=\"podium\"");
        int first = body.indexOf("pos1", podium);
        int second = body.indexOf("pos2", podium);
        assertTrue(podium > 0 && first < second, "ordre des rangs conservé dans le HTML");
        // Demo_Brasier et Demo_Givre ont 15 quêtes : même rang.
        assertTrue(body.contains("<ol class=\"board\" start=\"4\">"));
    }

    @Test
    void homeAndProgressionGuideANewPlayer() throws Exception {
        start(false);

        String home = get("/").body();
        assertTrue(home.contains("Commence en 3 étapes") && home.contains("class=\"steps\""));
        for (String link : List.of("/evenements", "/classements", "/progression", "/actualites", "/joueurs", "/infos")) {
            assertTrue(home.contains("class=\"tile-link\" href=\"" + link + "\""), link);
        }
        String progression = get("/progression").body();
        assertTrue(progression.contains("Les cinq pistes") && progression.contains("Agriculture"));
        assertEquals(404, get("/progression/autre").statusCode());
        assertEquals(404, get("/evenements/autre").statusCode());
    }

    @Test
    void robotsKeepDemoOutAndSitemapListsNewPages() throws Exception {
        start(false);
        assertTrue(get("/robots.txt").body().contains("Disallow: /demo/"));
        String sitemap = get("/sitemap.xml").body();
        assertTrue(sitemap.contains("https://portail.example/evenements") && sitemap.contains("https://portail.example/progression"));
        assertFalse(sitemap.contains("/demo"));
    }

    /**
     * Bug du 23/10 : la liste, la fiche et les classements fictifs doivent montrer les mêmes valeurs, et l'XP
     * globale suivre une règle affichée (ce n'est pas la somme des pistes).
     */
    @Test
    @SuppressWarnings("unchecked")
    void demoListProfileAndBoardsShowTheSameValues() throws Exception {
        start(false);
        Map<String, Object> state = DemoData.state(Instant.now());
        Map<String, Object> lifetime = (Map<String, Object>) ((Map<String, Object>) state.get("statistics")).get("lifetime");
        String list = get("/demo/joueurs").body();
        String boards = get("/demo/classements").body();
        List<String> tracks = List.of("COMBAT", "MINING", "FARMING", "FISHING", "EXPLORATION");

        for (Map<String, Object> profile : (List<Map<String, Object>>) state.get("profiles")) {
            String name = (String) profile.get("name");
            Map<String, Number> skills = (Map<String, Number>) profile.get("skills");
            long sum = tracks.stream().mapToLong(track -> skills.get(track).longValue()).sum();
            long quests = ((Number) profile.get("questsCompleted")).longValue();
            assertEquals(sum / 2 + quests * 50, skills.get("GLOBAL").longValue(), name + " : règle de l'XP globale");
            String global = StatLabels.number(skills.get("GLOBAL"));

            assertTrue(list.contains(">" + name + "</a><br><span class=\"note\">" + global + " XP globale</span>"),
                    name + " : liste");
            String sheet = get("/demo/joueurs/" + name).body();
            assertTrue(sheet.contains("XP globale : <span class=\"big\">" + global + " XP</span>"), name + " : fiche");
            assertTrue(sheet.contains(StatLabels.GLOBAL_XP_RULE), name + " : règle affichée sur la fiche");
            for (String track : tracks) {
                assertTrue(sheet.contains("<span>" + StatLabels.number(skills.get(track)) + " XP</span>"), name + " " + track);
            }
            long best = Long.MAX_VALUE;
            for (Map.Entry<String, Object> board : lifetime.entrySet()) {
                for (Map<String, Object> row : (List<Map<String, Object>>) board.getValue()) {
                    if (name.equals(row.get("name"))) {
                        long rank = ((Number) row.get("rank")).longValue();
                        best = Math.min(best, rank);
                        assertTrue(sheet.contains(StatLabels.title(board.getKey()) + "<br><span class=\"note\">" + rank + "<sup>"),
                                name + " : rang " + board.getKey());
                    }
                }
            }
            assertTrue(sheet.contains("Meilleur rang : <strong>" + best + "<sup>"), name + " : meilleur rang");
        }
        for (Map<String, Object> row : (List<Map<String, Object>>) lifetime.get("RPG_XP_GLOBAL")) {
            if (((Number) row.get("rank")).intValue() <= 10) {
                assertTrue(boards.contains(">" + row.get("name") + "</a></span><span class=\"value\">"
                        + StatLabels.number(row.get("value")) + " XP</span>"), row.get("name") + " : classement XP globale");
            }
        }
        String brasier = get("/demo/joueurs/Demo_Brasier").body();
        assertTrue(brasier.contains("XP globale : <span class=\"big\">5\u202f200 XP</span>")
                || brasier.contains("XP globale : <span class=\"big\">5\u00a0200 XP</span>"), "valeur signalée");
        assertTrue(brasier.contains("Meilleur rang : <strong>1<sup>er</sup></strong> en XP Combat"));
    }

    @Test
    void demoSearchFindsFictionalPlayersByExactPartialOrSpacedNameWithoutLeavingTheDemo() throws Exception {
        snapshot();
        start(true);

        for (String query : List.of("Demo_Brasier", "demo_brasier", "DEMO_BRASIER", "Brasier", "brasier", "Demo+Brasier",
                "%20brasier%20", "bras")) {
            HttpResponse<String> response = get("/demo/joueurs?pseudo=" + query);
            assertEquals(303, response.statusCode(), query);
            assertEquals("/demo/joueurs/Demo_Brasier", response.headers().firstValue("Location").orElse(""), query);
        }
        HttpResponse<String> several = get("/demo/joueurs?pseudo=demo_");
        assertEquals(200, several.statusCode());
        assertTrue(several.body().contains("12 profils correspondent") && several.body().contains("class=\"demo-banner\""));
        assertFalse(several.body().contains("href=\"/joueurs/"), "la recherche reste dans la démonstration");
        HttpResponse<String> none = get("/demo/joueurs?pseudo=Realname");
        assertEquals(200, none.statusCode());
        assertTrue(none.body().contains("Aucun profil public ne correspond"));
        assertFalse(none.body().contains("href=\"/demo/joueurs/Realname\""), "un vrai pseudo n'existe pas en démonstration");
        assertTrue(get("/demo/joueurs?pseudo=%3Cscript%3E").body().contains("&lt;script&gt;"), "texte cherché échappé");

        HttpResponse<String> live = get("/joueurs?pseudo=realn");
        assertEquals(303, live.statusCode());
        assertEquals("/joueurs/Realname", live.headers().firstValue("Location").orElse(""));
        assertEquals(200, get("/joueurs?pseudo=Demo_Brasier").statusCode(), "aucun joueur fictif parmi les vrais");
    }
}
