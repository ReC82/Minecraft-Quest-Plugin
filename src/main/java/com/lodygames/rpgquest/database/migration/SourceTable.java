package com.lodygames.rpgquest.database.migration;

import java.util.List;

/**
 * Structure d'une table de la base SQLite source, telle que lue dans {@code sqlite_master} et les
 * {@code PRAGMA} (issue #42). Aucun nom de table n'est codé en dur dans l'outil : la migration
 * suit le schéma <strong>réel</strong> de la base.
 *
 * @param name        nom de la table
 * @param columns     colonnes, dans l'ordre de déclaration
 * @param primaryKey  colonnes de la clé primaire, dans l'ordre de la clé (vide : aucune)
 * @param foreignKeys clés étrangères déclarées
 * @param uniqueKeys  ensembles de colonnes uniques (index {@code UNIQUE}, contraintes) hors clé primaire
 * @param autoIncrement vrai si la table possède une colonne {@code INTEGER PRIMARY KEY AUTOINCREMENT}
 */
public record SourceTable(
        String name,
        List<Column> columns,
        List<String> primaryKey,
        List<ForeignKey> foreignKeys,
        List<List<String>> uniqueKeys,
        boolean autoIncrement) {

    public SourceTable {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        foreignKeys = List.copyOf(foreignKeys);
        uniqueKeys = List.copyOf(uniqueKeys);
    }

    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }

    /** @param declaredType type déclaré en SQLite ({@code TEXT}, {@code INTEGER}, {@code REAL}, {@code BLOB}) */
    public record Column(String name, String declaredType, boolean notNull) {

        /** Affinité SQLite déduite du type déclaré (règles de la documentation SQLite §3.1). */
        public String affinity() {
            String type = declaredType == null ? "" : declaredType.toUpperCase(java.util.Locale.ROOT);
            if (type.contains("INT")) {
                return "INTEGER";
            }
            if (type.contains("CHAR") || type.contains("CLOB") || type.contains("TEXT")) {
                return "TEXT";
            }
            if (type.isEmpty() || type.contains("BLOB")) {
                return "BLOB";
            }
            if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")) {
                return "REAL";
            }
            return "NUMERIC";
        }
    }

    /** Clé étrangère {@code columns → parentTable(parentColumns)}. */
    public record ForeignKey(List<String> columns, String parentTable, List<String> parentColumns) {
        public ForeignKey {
            columns = List.copyOf(columns);
            parentColumns = List.copyOf(parentColumns);
        }
    }
}
