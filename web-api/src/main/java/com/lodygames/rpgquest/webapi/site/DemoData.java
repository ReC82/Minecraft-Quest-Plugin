package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.account.LinkedProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Données de démonstration du portail (#56, lot refonte) : des joueurs <strong>fictifs</strong>, tous
 * préfixés {@code Demo_}, pour montrer classements, podiums, fiches joueur et événements tant que les
 * vrais profils ne sont pas publiés ({@code player-data-public=false}, décision #55).
 *
 * <p>Rien ici ne vient du serveur de jeu, rien n'est écrit sur disque, et ces valeurs ne sont jamais
 * mélangées aux vraies : seules les pages {@code /demo/…} les lisent, avec un bandeau explicite et
 * {@code noindex}. Les classements sont calculés depuis les profils, pour rester cohérents entre eux ;
 * les dates des périodes suivent l'horloge pour que l'aperçu ne devienne jamais « terminé ».</p>
 */
public final class DemoData {

    static final String PREFIX = "Demo_";
    static final String BANNER = "<div class=\"demo-banner\" role=\"note\">" + PortalIcons.icon("shield", 32)
            + "<p><strong>Aperçu avec des joueurs fictifs.</strong> Ces pseudos, scores et événements sont des "
            + "données de démonstration inventées pour présenter le site : ce ne sont pas de vrais joueurs ni de "
            + "vrais résultats. Les vrais classements ouvriront après validation des règles de confidentialité.</p></div>";

    private static final List<String> TRACKS = List.of("COMBAT", "MINING", "FARMING", "FISHING", "EXPLORATION");

    /** Pseudo, XP combat, minage, agriculture, pêche, exploration, quêtes terminées. */
    private static final Object[][] PLAYERS = {
            {"Demo_Aube", 4200, 2600, 900, 1500, 1800, 24},
            {"Demo_Brasier", 6100, 1200, 300, 400, 900, 15},
            {"Demo_Cendre", 1800, 5200, 700, 200, 1100, 19},
            {"Demo_Dune", 900, 1400, 3800, 2600, 600, 21},
            {"Demo_Elfe", 2300, 800, 1200, 600, 4100, 30},
            {"Demo_Flocon", 1200, 1900, 600, 3900, 500, 11},
            {"Demo_Givre", 3100, 2200, 400, 300, 1300, 15},
            {"Demo_Hibou", 600, 700, 2100, 1200, 2400, 9},
            {"Demo_Iris", 1500, 1100, 1600, 900, 700, 12},
            {"Demo_Jade", 400, 300, 500, 1800, 300, 6},
            {"Demo_Kiwi", 250, 600, 200, 150, 400, 4},
            {"Demo_Lune", 120, 90, 300, 80, 200, 2},
    };

    private DemoData() {
    }

    /** État complet au format de l'instantané v2 (#54), daté de {@code now}. */
    public static Map<String, Object> state(Instant now) {
        List<Map<String, Object>> profiles = profiles();
        Map<String, Object> lifetime = new LinkedHashMap<>();
        lifetime.put("RPG_XP_GLOBAL", board(profiles, profile -> skill(profile, "GLOBAL")));
        lifetime.put("QUESTS_COMPLETED", board(profiles, profile -> ((Number) profile.get("questsCompleted")).longValue()));
        for (String track : TRACKS) {
            lifetime.put("RPG_XP_" + track, board(profiles, profile -> skill(profile, track)));
        }

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("scopes", List.of("lifetime", "season", "event"));
        statistics.put("lifetime", lifetime);
        statistics.put("season", season(profiles, now));
        statistics.put("events", events(profiles, now));
        statistics.put("boards", boards(profiles, now));
        statistics.put("catalog", catalog(now));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schemaVersion", 2);
        state.put("generatedAt", now.toString());
        state.put("statistics", statistics);
        state.put("profiles", profiles);
        return state;
    }

    /**
     * Progression personnelle fictive de {@code Demo_Aube} (#53), pour l'aperçu {@code /demo/compte} :
     * rangs calculés sur les mêmes joueurs fictifs que les classements de démonstration. Niveaux selon
     * la courbe par défaut du plugin (100 XP, ×1,15 par niveau).
     */
    public static LinkedProgress personalProgress(Instant now) {
        List<Map<String, Object>> profiles = profiles();
        Map<String, Object> me = profiles.get(0);
        String name = (String) me.get("name");
        List<LinkedProgress.Skill> skills = new ArrayList<>();
        for (String track : java.util.stream.Stream.concat(java.util.stream.Stream.of("GLOBAL"), TRACKS.stream()).toList()) {
            long xp = skill(me, track);
            skills.add(new LinkedProgress.Skill(track, xp, demoLevel(xp)));
        }
        List<LinkedProgress.Board> boards = List.of(
                demoBoard("xp_total", "Aventuriers les plus expérimentés", "aventure", "RPG_XP_GLOBAL", "lifetime", "lifetime",
                        standing(profiles, name, profile -> skill(profile, "GLOBAL"))),
                demoBoard("combat_semaine", "Combattants de la semaine", "combat", "RPG_XP_COMBAT", "weekly", "demo-semaine",
                        standing(profiles, name, profile -> skill(profile, "COMBAT") / 7)),
                demoBoard("exploration_saison", "Explorateurs de la saison", "exploration", "RPG_XP_EXPLORATION", "season",
                        "demo_saison", standing(profiles, name, profile -> skill(profile, "EXPLORATION") / 4)),
                demoBoard("peche_jour", "Pêcheurs du jour", "recolte", "FISH_CAUGHT", "daily", "demo-jour",
                        new LinkedProgress.Standing(0, 0, 0)));
        return new LinkedProgress(now.minus(Duration.ofMinutes(7)), skills,
                standing(profiles, name, profile -> ((Number) profile.get("questsCompleted")).longValue()), boards);
    }

    private static LinkedProgress.Board demoBoard(String id, String title, String category, String metric, String period,
                                                  String window, LinkedProgress.Standing standing) {
        return new LinkedProgress.Board(id, title, category, null, metric, period, window, "ACTIVE", standing);
    }

    private static LinkedProgress.Standing standing(List<Map<String, Object>> profiles, String name,
                                                    ToLongFunction<Map<String, Object>> value) {
        List<Map<String, Object>> rows = board(profiles, value);
        for (Map<String, Object> row : rows) {
            if (row.get("name").equals(name)) {
                return new LinkedProgress.Standing(((Number) row.get("value")).longValue(),
                        ((Number) row.get("rank")).intValue(), rows.size());
            }
        }
        return new LinkedProgress.Standing(0, 0, 0);
    }

    private static int demoLevel(long xp) {
        int level = 1;
        double cost = 100;
        long remaining = xp;
        while (level < 100 && remaining >= Math.round(cost)) {
            remaining -= Math.round(cost);
            cost *= 1.15;
            level++;
        }
        return level;
    }

    private static List<Map<String, Object>> profiles() {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (Object[] row : PLAYERS) {
            Map<String, Object> skills = new LinkedHashMap<>();
            long sum = 0;
            for (int i = 0; i < TRACKS.size(); i++) {
                int xp = (Integer) row[i + 1];
                skills.put(TRACKS.get(i), xp);
                sum += xp;
            }
            int quests = (Integer) row[6];
            // GLOBAL reçoit la moitié de chaque piste et l'XP des quêtes (docs/PROGRESSION.md).
            skills.put("GLOBAL", sum / 2 + quests * 50L);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", row[0]);
            profile.put("skills", skills);
            profile.put("questsCompleted", quests);
            profiles.add(profile);
        }
        return profiles;
    }

    private static long skill(Map<String, Object> profile, String track) {
        return ((Number) ((Map<?, ?>) profile.get("skills")).get(track)).longValue();
    }

    /** Classement trié, rangs ex aequo comme le plugin (« 1, 2, 2, 4 »). */
    private static List<Map<String, Object>> board(List<Map<String, Object>> profiles,
                                                   ToLongFunction<Map<String, Object>> value) {
        List<Map<String, Object>> sorted = profiles.stream()
                .sorted(Comparator.comparingLong(value).reversed()).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            long current = value.applyAsLong(sorted.get(i));
            if (current != previous) {
                rank = i + 1;
                previous = current;
            }
            rows.add(Map.of("name", sorted.get(i).get("name"), "value", current, "rank", rank));
        }
        return rows;
    }

    private static Map<String, Object> season(List<Map<String, Object>> profiles, Instant now) {
        Map<String, Object> season = period("demo_saison", "Saison des Lanternes (démo)", now.minus(Duration.ofDays(20)),
                now.plus(Duration.ofDays(40)), "ACTIVE");
        season.put("leaderboards", Map.of(
                "RPG_XP_GLOBAL", board(profiles, profile -> skill(profile, "GLOBAL") / 3),
                "RPG_XP_EXPLORATION", board(profiles, profile -> skill(profile, "EXPLORATION") / 4)));
        return season;
    }

    private static List<Map<String, Object>> events(List<Map<String, Object>> profiles, Instant now) {
        Map<String, Object> hunt = period("demo_squelettes", "Nuit des squelettes (démo)", now.minus(Duration.ofHours(30)),
                now.plus(Duration.ofHours(42)), "ACTIVE");
        hunt.put("ranking", "HIGHEST_TOTAL");
        hunt.put("filter", Map.of("entity", List.of("minecraft:skeleton", "minecraft:stray")));
        hunt.put("rewards", List.of(Map.of("kind", "RANK", "value", 1, "label", "Titre « Chasseur d'os » (démo)"),
                Map.of("kind", "RANK", "value", 3, "label", "10 pièces (démo)")));
        hunt.put("leaderboards", Map.of("ENTITY_KILL", board(profiles, profile -> skill(profile, "COMBAT") / 60)));

        Map<String, Object> harvest = period("demo_moisson", "Grande moisson commune (démo)", now.minus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(4)), "ACTIVE");
        harvest.put("ranking", "COMMUNITY_GOAL");
        harvest.put("community", Map.of("goal", 5000, "total", 3240));
        harvest.put("rewards", List.of(Map.of("kind", "COMMUNITY", "label", "Festin au Hub pour tout le monde (démo)")));
        harvest.put("leaderboards", Map.of("RPG_XP_FARMING", board(profiles, profile -> skill(profile, "FARMING") / 5)));

        Map<String, Object> fishing = period("demo_peche", "Concours de pêche (démo)", now.plus(Duration.ofDays(6)),
                now.plus(Duration.ofDays(8)), "SCHEDULED");
        fishing.put("ranking", "BEST_SINGLE");
        fishing.put("leaderboards", Map.of());

        Map<String, Object> past = period("demo_mineurs", "Fièvre des mineurs (démo)", now.minus(Duration.ofDays(30)),
                now.minus(Duration.ofDays(27)), "ARCHIVED");
        past.put("leaderboards", Map.of("RPG_XP_MINING", board(profiles, profile -> skill(profile, "MINING") / 8)));
        return List.of(hunt, harvest, fishing, past);
    }

    /**
     * Catalogue de classements fictif (#734) : quelques classements de chaque période, valeurs
     * dérivées des profils fictifs (donc cohérentes entre elles), fenêtres calées sur l'horloge.
     */
    private static List<Map<String, Object>> boards(List<Map<String, Object>> profiles, Instant now) {
        Instant weekStart = now.minus(Duration.ofDays(3));
        Instant weekEnd = now.plus(Duration.ofDays(4));
        List<Map<String, Object>> boards = new ArrayList<>();
        boards.add(demoBoard("demo_zombies", "Chasseurs de zombies (démo)", "combat", "skull", "ENTITY_KILL", "weekly",
                Map.of("entity-family", List.of("zombies")), weekStart, weekEnd, now, true,
                board(profiles, profile -> skill(profile, "COMBAT") / 90), board(profiles, profile -> skill(profile, "COMBAT") / 120)));
        boards.add(demoBoard("demo_araignees", "Chasseurs d'araignées (démo)", "combat", "spider", "ENTITY_KILL", "weekly",
                Map.of("entity-family", List.of("spiders")), weekStart, weekEnd, now, false,
                board(profiles, profile -> skill(profile, "COMBAT") / 200), List.of()));
        boards.add(demoBoard("demo_monstres_jour", "Monstres vaincus aujourd'hui (démo)", "combat", null, "ENTITY_KILL",
                "daily", Map.of("entity-family", List.of("hostile", "boss")), now.minus(Duration.ofHours(10)),
                now.plus(Duration.ofHours(14)), now, false, board(profiles, profile -> skill(profile, "COMBAT") / 400),
                board(profiles, profile -> skill(profile, "COMBAT") / 300)));
        boards.add(demoBoard("demo_minerais", "Mineurs du mois (démo)", "recolte", "ore", "BLOCK_BREAK", "monthly",
                Map.of("block-family", List.of("ores")), now.minus(Duration.ofDays(12)), now.plus(Duration.ofDays(18)), now,
                false, board(profiles, profile -> skill(profile, "MINING") / 25), board(profiles, profile -> skill(profile, "MINING") / 30)));
        boards.add(demoBoard("demo_waypoints", "Waypoints découverts (démo)", "exploration", null, "WAYPOINT_DISCOVERED",
                "lifetime", null, null, null, now, true, board(profiles, profile -> skill(profile, "EXPLORATION") / 150), null));
        boards.add(demoBoard("demo_quetes", "Quêtes de la semaine (démo)", "aventure", null, "QUEST_COMPLETION", "weekly",
                null, weekStart, weekEnd, now, false,
                board(profiles, profile -> ((Number) profile.get("questsCompleted")).longValue() / 3), List.of()));
        boards.add(creatureBoard(profiles, now, weekStart, weekEnd));
        return boards;
    }

    /**
     * Classement par type de créature fictif (#734) : quelques types avec des joueurs Demo_*, un mob
     * spécial inventé et un boss rencontré mais personne cette semaine (ligne « déjà vaincus »).
     */
    private static Map<String, Object> creatureBoard(List<Map<String, Object>> profiles, Instant now, Instant start,
                                                     Instant end) {
        Map<String, Object> board = demoBoard("demo_creatures_semaine", "Créatures vaincues cette semaine, par type (démo)",
                "combat", "skull", "ENTITY_KILL", "weekly", null, start, end, now, false, List.of(), null);
        board.remove("leaderboard");
        board.put("split", "entity");
        String since = now.minus(Duration.ofDays(40)).toString();
        board.put("targets", List.of(
                creature("ENDER_DRAGON", "Ender Dragon", "boss", null, since, List.of()),
                creature("special:rpgquest:demo_roi_squelette", "Roi squelette (démo)", "special", "Squelette", since,
                        board(profiles, profile -> skill(profile, "COMBAT") / 2000)),
                creature("CREEPER", "Creeper", "hostile", null, since, board(profiles, profile -> skill(profile, "COMBAT") / 300)),
                creature("SPIDER", "Araignée", "hostile", null, since, board(profiles, profile -> skill(profile, "COMBAT") / 200)),
                creature("SKELETON", "Squelette", "hostile", null, since, board(profiles, profile -> skill(profile, "COMBAT") / 150)),
                creature("ZOMBIE", "Zombie", "hostile", null, since, board(profiles, profile -> skill(profile, "COMBAT") / 90)),
                creature("PIG", "Cochon", "passive", null, since, board(profiles, profile -> skill(profile, "COMBAT") / 500))));
        return board;
    }

    private static Map<String, Object> creature(String key, String label, String category, String base, String since,
                                                List<Map<String, Object>> rows) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("key", key);
        target.put("label", label);
        target.put("category", category);
        if (base != null) {
            target.put("base", base);
        }
        target.put("trackedSince", since);
        target.put("leaderboard", rows.stream().filter(row -> ((Number) row.get("value")).longValue() > 0).toList());
        return target;
    }

    private static Map<String, Object> demoBoard(String id, String name, String category, String icon, String metric,
                                                 String period, Map<String, Object> filter, Instant start, Instant end,
                                                 Instant now, boolean featured, List<Map<String, Object>> rows,
                                                 List<Map<String, Object>> previousRows) {
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("id", id);
        board.put("name", name);
        board.put("category", category);
        if (icon != null) {
            board.put("icon", icon);
        }
        board.put("metric", metric);
        board.put("period", period);
        if (filter != null) {
            board.put("filter", filter);
        }
        board.put("featured", featured);
        board.put("size", 10);
        board.put("status", "ACTIVE");
        board.put("trackedSince", now.minus(Duration.ofDays(40)).toString());
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("key", "demo");
        if (start != null) {
            window.put("start", start.toString());
            window.put("end", end.toString());
        }
        board.put("window", window);
        board.put("leaderboard", rows.stream().filter(row -> ((Number) row.get("value")).longValue() > 0).toList());
        if (previousRows != null && start != null) {
            Duration length = Duration.between(start, end);
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put("key", "demo-previous");
            previous.put("start", start.minus(length).toString());
            previous.put("end", start.toString());
            previous.put("leaderboard", previousRows.stream()
                    .filter(row -> ((Number) row.get("value")).longValue() > 0).toList());
            board.put("previous", previous);
        }
        return board;
    }

    private static Map<String, Object> catalog(Instant now) {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("timezone", "Europe/Brussels");
        catalog.put("metrics", List.of(Map.of("id", "ENTITY_KILL", "dated", true, "trackedSince",
                now.minus(Duration.ofDays(40)).toString())));
        catalog.put("families", Map.of(
                "entity-family", List.of(Map.of("id", "zombies", "label", "Zombies"),
                        Map.of("id", "spiders", "label", "Araignées"), Map.of("id", "hostile", "label", "Monstres hostiles"),
                        Map.of("id", "boss", "label", "Boss")),
                "block-family", List.of(Map.of("id", "ores", "label", "Minerais"))));
        catalog.put("planned", List.of(
                Map.of("id", "PLAY_TIME", "reason", "Temps de jeu et jours actifs : pas encore mesurés."),
                Map.of("id", "DISTANCE_TRAVELED", "reason", "Distance parcourue (marche, nage, vol) : pas encore mesurée.")));
        return catalog;
    }

    private static Map<String, Object> period(String id, String name, Instant start, Instant end, String status) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("id", id);
        period.put("name", name);
        period.put("start", start.toString());
        period.put("end", end.toString());
        period.put("status", status);
        return period;
    }
}
