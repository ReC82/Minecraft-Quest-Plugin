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
    static final List<String> PREFERRED = List.of("RPG_XP_GLOBAL", "QUESTS_COMPLETED", "RPG_XP_COMBAT",
            "RPG_XP_MINING", "RPG_XP_FARMING", "RPG_XP_FISHING", "RPG_XP_EXPLORATION");

    private StatLabels() {
    }

    public static String title(String metric) {
        if (metric.equals("QUESTS_COMPLETED")) {
            return "Quêtes terminées";
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
        return metric.equals("QUESTS_COMPLETED") ? "quêtes" : "XP";
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
            default -> "";
        };
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
