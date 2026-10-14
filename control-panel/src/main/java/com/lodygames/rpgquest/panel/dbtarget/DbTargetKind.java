package com.lodygames.rpgquest.panel.dbtarget;

/**
 * Les deux bases MariaDB que PlugAdmin sait décrire et tester, chacune dans sa propre table de
 * {@code control-panel.db} et avec son propre usage de chiffrement ({@link SecretBox}) : un mot de
 * passe scellé pour l'une ne s'ouvre jamais pour l'autre.
 *
 * <ul>
 *   <li>{@link #MIGRATION} — base cible d'une future migration des données <strong>RPGQuest</strong>
 *       (issue #42), propriété du plugin ;</li>
 *   <li>{@link #PANEL} — base <strong>propre au panel</strong> (issue #43), qui n'accueillerait que
 *       les données d'administration de PlugAdmin. Enregistrée et testée, jamais utilisée tant que
 *       le propriétaire n'a pas autorisé la migration du panel.</li>
 * </ul>
 */
public enum DbTargetKind {

    MIGRATION("db_migration_target", "db-migration-target-password", "migration"),
    PANEL("panel_db_target", "panel-db-target-password", "panel");

    private final String table;
    private final String secretPurpose;
    private final String auditLabel;

    DbTargetKind(String table, String secretPurpose, String auditLabel) {
        this.table = table;
        this.secretPurpose = secretPurpose;
        this.auditLabel = auditLabel;
    }

    /** Table de {@code control-panel.db} : constante du code, jamais une saisie. */
    public String table() {
        return table;
    }

    /** Libellé d'usage du {@link SecretBox} (dérivation de clé et données associées). */
    public String secretPurpose() {
        return secretPurpose;
    }

    /** Valeur {@code target=…} des entrées d'audit. */
    public String auditLabel() {
        return auditLabel;
    }
}
