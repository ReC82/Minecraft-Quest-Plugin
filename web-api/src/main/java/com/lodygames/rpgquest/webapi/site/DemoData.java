package com.lodygames.rpgquest.webapi.site;

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

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schemaVersion", 2);
        state.put("generatedAt", now.toString());
        state.put("statistics", statistics);
        state.put("profiles", profiles);
        return state;
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
