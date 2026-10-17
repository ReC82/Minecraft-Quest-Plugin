package com.lodygames.rpgquest.quest.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Issue #15 — période de renouvellement d'une quête quotidienne ou hebdomadaire (champ YAML
 * optionnel {@code period: daily|weekly}). Une telle quête est un <strong>modèle</strong> : elle
 * n'est jamais proposée par un PNJ, un catalogue ou {@code /quest accept} ; seul le service des
 * quêtes périodiques l'attribue, une fois par période, au joueur qui a ouvert son journal.
 */
public enum QuestPeriod {
    DAILY("daily", "Quêtes quotidiennes"),
    WEEKLY("weekly", "Quêtes hebdomadaires");

    private final String yamlKey;
    private final String label;

    QuestPeriod(String yamlKey, String label) {
        this.yamlKey = yamlKey;
        this.label = label;
    }

    /** Valeur écrite dans le YAML et en base ({@code daily} / {@code weekly}). */
    public String yamlKey() {
        return yamlKey;
    }

    /** Libellé joueur de l'onglet du journal. */
    public String label() {
        return label;
    }

    public static Optional<QuestPeriod> fromYaml(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (QuestPeriod period : values()) {
            if (period.yamlKey.equals(normalized)) {
                return Optional.of(period);
            }
        }
        return Optional.empty();
    }
}
