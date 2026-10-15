package com.lodygames.rpgquest.webapi.site;

import java.text.NumberFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

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

    private StatLabels() {
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
        if (metric.equals("RPG_XP_GLOBAL")) {
            return "Expérience totale";
        }
        if (metric.startsWith("RPG_XP_")) {
            return "XP " + label(metric.substring("RPG_XP_".length()));
        }
        return metric;
    }

    public static String unit(String metric) {
        return switch (metric) {
            case "QUESTS_COMPLETED" -> "quêtes";
            case "ENTITY_KILL" -> "monstres";
            case "FISH_CAUGHT" -> "prises";
            default -> "XP";
        };
    }

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
