package com.lodygames.rpgquest.webapi.site;

import java.text.NumberFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Libellés français des métriques (#54), des statuts de période et des dates affichées (#56). */
public final class StatLabels {

    /** Fuseau d'affichage des dates : celui de la communauté (Bruxelles). */
    static final ZoneId ZONE = ZoneId.of("Europe/Brussels");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH).withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH'h'mm", Locale.FRENCH).withZone(ZONE);

    /** Ordre d'affichage : les plus parlantes d'abord, les autres dans l'ordre de l'instantané. */
    static final List<String> PREFERRED = List.of("RPG_XP_GLOBAL", "QUESTS_COMPLETED", "ENTITY_KILL", "FISH_CAUGHT",
            "RPG_XP_COMBAT", "RPG_XP_MINING", "RPG_XP_FARMING", "RPG_XP_FISHING", "RPG_XP_EXPLORATION");

    /** Thèmes des classements (#56) : identifiant d'adresse → libellé, icône. Ordre = ordre des filtres. */
    static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("aventure", new String[] {"Aventure", "scroll"});
        CATEGORIES.put("combat", new String[] {"Combat", "sword"});
        CATEGORIES.put("recolte", new String[] {"Récolte", "pickaxe"});
        CATEGORIES.put("exploration", new String[] {"Exploration", "map"});
        CATEGORIES.put("communaute", new String[] {"Communauté", "heart"});
        CATEGORIES.put("autres", new String[] {"Autres", "trophy"});
    }

    private StatLabels() {
    }

    /** Thème d'une métrique (#54) ; « autres » pour une métrique inconnue, jamais d'erreur. */
    static String category(String metric) {
        return switch (metric) {
            case "RPG_XP_GLOBAL", "QUESTS_COMPLETED", "QUEST_COMPLETION", "STORY_COMPLETED" -> "aventure";
            case "RPG_XP_COMBAT", "ENTITY_KILL" -> "combat";
            case "RPG_XP_MINING", "RPG_XP_FARMING", "RPG_XP_FISHING", "FISH_CAUGHT", "BLOCK_BREAK" -> "recolte";
            case "RPG_XP_EXPLORATION", "WAYPOINT_DISCOVERED", "CITY_DISCOVERED", "WAYSTONE_DISCOVERED" -> "exploration";
            default -> "autres";
        };
    }

    public static String title(String metric) {
        if (metric.equals("QUESTS_COMPLETED")) {
            return "Quêtes terminées";
        }
        if (metric.equals("ENTITY_KILL")) {
            return "Monstres vaincus";
        }
        if (metric.equals("FISH_CAUGHT")) {
            return "Prises de pêche";
        }
        String catalogTitle = switch (metric) {
            case "BLOCK_BREAK" -> "Blocs cassés";
            case "QUEST_COMPLETION" -> "Quêtes terminées (par période)";
            case "WAYPOINT_DISCOVERED" -> "Waypoints découverts";
            case "CITY_DISCOVERED" -> "Villes découvertes";
            case "WAYSTONE_DISCOVERED" -> "Pierres de voyage découvertes";
            case "STORY_COMPLETED" -> "Histoires terminées";
            default -> null;
        };
        if (catalogTitle != null) {
            return catalogTitle;
        }
        if (metric.equals("RPG_XP_GLOBAL")) {
            return "XP globale";
        }
        if (metric.startsWith("RPG_XP_")) {
            return "XP " + label(metric.substring("RPG_XP_".length()));
        }
        return metric;
    }

    public static String unit(String metric) {
        return switch (metric) {
            case "QUESTS_COMPLETED", "QUEST_COMPLETION" -> "quêtes";
            case "ENTITY_KILL" -> "monstres";
            case "FISH_CAUGHT" -> "prises";
            case "BLOCK_BREAK" -> "blocs";
            case "WAYPOINT_DISCOVERED" -> "waypoints";
            case "CITY_DISCOVERED" -> "villes";
            case "WAYSTONE_DISCOVERED" -> "pierres";
            case "STORY_COMPLETED" -> "histoires";
            default -> "XP";
        };
    }

    /**
     * Règle affichée partout où l'XP globale côtoie les pistes (#56) : la piste {@code GLOBAL} est une piste à part
     * entière, alimentée par un miroir des autres pistes et par les quêtes (docs/PROGRESSION.md) — ce n'est pas
     * leur somme.
     */
    public static final String GLOBAL_XP_RULE = "L'XP globale n'est pas la somme des pistes : chaque piste y verse "
            + "une part de son XP (la moitié par défaut) et chaque quête terminée y ajoute sa récompense.";

    /** Libellé français d'une piste de progression ; la clé brute si elle est inconnue. */
    public static String label(String skill) {
        return switch (skill) {
            case "GLOBAL" -> "Global";
            case "COMBAT" -> "Combat";
            case "MINING" -> "Minage";
            case "FARMING" -> "Agriculture";
            case "FISHING" -> "Pêche";
            case "EXPLORATION" -> "Exploration";
            default -> skill;
        };
    }

    public static String number(Object value) {
        if (value instanceof Number number) {
            return NumberFormat.getIntegerInstance(Locale.FRANCE).format(number.longValue());
        }
        return "";
    }

    /** Statut d'une saison ou d'un événement (#54), en mots simples. */
    public static String status(String status) {
        return switch (status == null ? "" : status) {
            case "SCHEDULED" -> "À venir";
            case "ACTIVE" -> "En cours";
            case "ENDED" -> "Terminé";
            case "ARCHIVED" -> "Terminé (résultats définitifs)";
            case "CANCELLED" -> "Annulé";
            default -> "";
        };
    }

    /**
     * Périodes du catalogue de classements (#734) : nom de la période dans l'instantané → identifiant
     * d'adresse, libellé du filtre. Ordre = ordre des filtres.
     */
    static final Map<String, String[]> BOARD_PERIODS = new LinkedHashMap<>();

    static {
        BOARD_PERIODS.put("daily", new String[] {"jour", "Aujourd'hui"});
        BOARD_PERIODS.put("weekly", new String[] {"semaine", "Cette semaine"});
        BOARD_PERIODS.put("monthly", new String[] {"mois", "Ce mois-ci"});
        BOARD_PERIODS.put("season", new String[] {"saison", "Saison"});
        BOARD_PERIODS.put("event", new String[] {"evenement", "Événement"});
        BOARD_PERIODS.put("custom", new String[] {"dates", "Dates spéciales"});
        BOARD_PERIODS.put("lifetime", new String[] {"toujours", "Depuis toujours"});
    }

    /** Fenêtre précédente d'un classement de calendrier : « Hier », « La semaine dernière »… */
    static String previousLabel(String period) {
        return switch (period == null ? "" : period) {
            case "daily" -> "Hier";
            case "weekly" -> "La semaine dernière";
            case "monthly" -> "Le mois dernier";
            default -> "Période précédente";
        };
    }

    /**
     * Bornes lisibles d'une fenêtre {@code [start, end)} : « du 19 octobre 2026 au 25 octobre 2026 »
     * (la fin affichée est le dernier jour compris) ; un seul jour s'écrit « le 23 octobre 2026 ».
     */
    static String window(String start, String end) {
        try {
            var first = Instant.parse(start).atZone(ZONE).toLocalDate();
            var last = Instant.parse(end).minusSeconds(1).atZone(ZONE).toLocalDate();
            DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
            return first.equals(last) ? "le " + day.format(first) : "du " + day.format(first) + " au " + day.format(last);
        } catch (DateTimeException | NullPointerException e) {
            return "";
        }
    }

    /** Règle de classement d'un événement (#57), expliquée simplement. */
    public static String ranking(String ranking) {
        return switch (ranking == null ? "" : ranking) {
            case "LOWEST_TOTAL" -> "La plus petite valeur gagne.";
            case "BEST_SINGLE" -> "Seul ton meilleur résultat compte.";
            case "COMMUNITY_GOAL" -> "Objectif commun : chaque participant fait avancer le total de tous.";
            case "HIGHEST_TOTAL" -> "Le plus grand total gagne.";
            default -> "";
        };
    }

    /** Critère de filtre d'un événement (#57) : « entity » → « Monstres concernés ». */
    public static String filter(String key) {
        return switch (key) {
            case "entity" -> "Monstres concernés";
            case "special-mob" -> "Monstres spéciaux concernés";
            case "item" -> "Prises concernées";
            case "entity-family" -> "Familles de monstres";
            case "block" -> "Blocs concernés";
            case "block-family" -> "Familles de blocs";
            case "quest" -> "Quêtes concernées";
            default -> key;
        };
    }

    /**
     * Compte à rebours (#57) jusqu'à {@code isoInstant} : « 2 j 4 h », « 3 h 12 min », « 5 min ».
     * Calculé à l'affichage (le CSP interdit le JavaScript en ligne) ; vide si la date est passée ou illisible.
     */
    public static String remaining(String isoInstant, Instant now) {
        Instant target;
        try {
            target = Instant.parse(isoInstant);
        } catch (DateTimeException | NullPointerException e) {
            return "";
        }
        long minutes = java.time.Duration.between(now, target).toMinutes();
        if (!target.isAfter(now)) {
            return "";
        }
        if (minutes < 1) {
            return "moins d'une minute";
        }
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        if (days > 0) {
            return days + " j " + hours + " h";
        }
        return hours > 0 ? hours + " h " + minutes % 60 + " min" : minutes + " min";
    }

    /** Valeur de filtre lisible : {@code ZOMBIE_VILLAGER} → « zombie villager ». */
    public static String constant(String value) {
        int colon = value.indexOf(':');
        String bare = colon >= 0 ? value.substring(colon + 1) : value;
        return bare.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    public static String date(String isoInstant) {
        return format(isoInstant, DATE);
    }

    public static String dateTime(String isoInstant) {
        return format(isoInstant, DATE_TIME);
    }

    public static String dateTime(Instant instant) {
        return DATE_TIME.format(instant);
    }

    private static String format(String isoInstant, DateTimeFormatter formatter) {
        if (isoInstant == null) {
            return "";
        }
        try {
            return formatter.format(Instant.parse(isoInstant));
        } catch (DateTimeException e) {
            return "";
        }
    }
}
