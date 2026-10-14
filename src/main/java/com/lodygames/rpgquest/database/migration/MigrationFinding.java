package com.lodygames.rpgquest.database.migration;

/**
 * Constat produit par l'audit ou la validation d'une migration SQLite → MariaDB (issue #42).
 *
 * <p>Un seul constat {@link Severity#CRITICAL} suffit à rendre la migration {@code FAILED} : le
 * succès n'est jamais déduit de la simple absence d'exception SQL.</p>
 *
 * @param severity gravité
 * @param check    famille de contrôle (regroupement du résumé du rapport)
 * @param scope    table, colonne ou étape concernée ({@code source}, {@code cible}, {@code players.uuid}…)
 * @param message  explication lisible, <strong>sans secret</strong>
 */
public record MigrationFinding(Severity severity, Check check, String scope, String message) {

    public enum Severity {
        /** Bloquant : la migration ne peut pas être déclarée réussie. */
        CRITICAL,
        /** À examiner, sans invalider le résultat. */
        WARNING,
        /** Information utile au runbook (transformation appliquée, alignement de séquence…). */
        INFO
    }

    /** Famille de contrôle, reprise dans le résumé du rapport. */
    public enum Check {
        /** Fichier source : présence, journal, version de schéma, empreinte. */
        SOURCE,
        /** {@code PRAGMA integrity_check}. */
        INTEGRITY,
        /** Clés étrangères / orphelins, source et cible. */
        FOREIGN_KEY,
        /** Format des UUID joueurs. */
        UUID,
        /** Compatibilité des valeurs avec les types et tailles de la cible. */
        TYPE,
        /** Cible : connexion, vacuité, schéma. */
        TARGET,
        /** Copie et comparaison des données. */
        DATA,
        /** Séquences {@code AUTOINCREMENT}. */
        SEQUENCE
    }

    public static MigrationFinding critical(Check check, String scope, String message) {
        return new MigrationFinding(Severity.CRITICAL, check, scope, message);
    }

    public static MigrationFinding warning(Check check, String scope, String message) {
        return new MigrationFinding(Severity.WARNING, check, scope, message);
    }

    public static MigrationFinding info(Check check, String scope, String message) {
        return new MigrationFinding(Severity.INFO, check, scope, message);
    }
}
