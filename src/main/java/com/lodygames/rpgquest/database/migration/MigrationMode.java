package com.lodygames.rpgquest.database.migration;

/** Modes d'exécution de l'outil de migration SQLite → MariaDB (issue #42). */
public enum MigrationMode {

    /**
     * Contrôles en <strong>lecture seule</strong> : source (intégrité, clés étrangères, types,
     * UUID) et, si une cible est fournie, joignabilité, vacuité et version de schéma de la cible.
     * Aucune écriture nulle part, pas même le schéma.
     */
    AUDIT("audit (lecture seule)"),

    /**
     * Répétition complète : schéma créé dans la cible, copie et validation dans
     * <strong>une transaction toujours annulée</strong>. Mode par défaut. Sur MariaDB, le DDL du
     * schéma n'est pas transactionnel : les tables vides créées restent.
     */
    DRY_RUN("dry-run (transaction annulée)"),

    /**
     * Migration réelle : la transaction n'est validée que si tous les contrôles sont verts, puis les
     * séquences {@code AUTOINCREMENT} sont alignées. Exige l'option explicite {@code --commit}.
     */
    COMMIT("commit (données conservées si validées)");

    private final String label;

    MigrationMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
