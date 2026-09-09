package com.lodygames.rpgquest.panel.diag;

import java.util.Locale;

/**
 * Gravité d'un diagnostic, du point de vue <strong>opérateur</strong> (issue #38).
 *
 * <ul>
 *   <li>{@link #ERROR} — empêche ou casse une fonctionnalité importante ;</li>
 *   <li>{@link #WARNING} — état incohérent ou incomplet, non bloquant immédiatement ;</li>
 *   <li>{@link #INFO} — information utile / situation à surveiller.</li>
 * </ul>
 *
 * <p>Les sévérités techniques des moteurs (chaînes {@code error|warning|info}) sont mappées ici
 * via {@link #of(String)}.</p>
 */
public enum Severity {
    ERROR(0, "Erreur", "alert-danger", "error"),
    WARNING(1, "Avertissement", "alert-warning", "warning"),
    INFO(2, "Information", "alert-info", "info");

    private final int rank;
    private final String label;
    private final String alertClass;
    private final String icon;

    Severity(int rank, String label, String alertClass, String icon) {
        this.rank = rank;
        this.label = label;
        this.alertClass = alertClass;
        this.icon = icon;
    }

    /** Ordre de tri : 0 pour ERROR (remonte en premier). */
    public int rank() {
        return rank;
    }

    /** Libellé humain singulier (« Erreur »). */
    public String label() {
        return label;
    }

    /** Classe Bootstrap d'alerte associée. */
    public String alertClass() {
        return alertClass;
    }

    /** Nom d'icône ({@code Icons}) associé. */
    public String icon() {
        return icon;
    }

    /** Valeur de filtre stable (« err » / « warn » / « info »). */
    public String filterKey() {
        return switch (this) {
            case ERROR -> "err";
            case WARNING -> "warn";
            case INFO -> "info";
        };
    }

    /** Mappe une sévérité technique de moteur ({@code error|warning|info}, casse libre). */
    public static Severity of(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "error", "err", "critical", "severe" -> ERROR;
            case "warning", "warn" -> WARNING;
            default -> INFO;
        };
    }
}
