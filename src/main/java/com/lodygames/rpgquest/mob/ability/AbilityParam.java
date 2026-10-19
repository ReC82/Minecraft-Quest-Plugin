package com.lodygames.rpgquest.mob.ability;

import java.util.Objects;

/**
 * Issue #542 — un paramètre typé et borné d'une capacité (clé YAML plate, décision Q1 de #541).
 *
 * <p>Une seule source pour la lecture ({@link MobAbilityCatalog#parse}), l'écriture
 * ({@link MobAbilityCatalog#render}), le modèle commenté et la liste des clés acceptées à l'import
 * ({@code SpecialMobSchema}) — et demain le formulaire PlugAdmin (#543).</p>
 *
 * @param key          clé YAML
 * @param valueType    type de valeur
 * @param required     absente = erreur ; sinon {@code defaultValue} s'applique
 * @param min          borne basse ({@code null} = aucune)
 * @param minExclusive la borne basse est exclue
 * @param max          borne haute ({@code null} = aucune)
 * @param maxExclusive la borne haute est exclue
 * @param defaultValue valeur d'une clé absente (type Java de {@code valueType}, ou {@code null})
 * @param writeDefault écrite même quand elle vaut le défaut (garde le rendu d'avant #542 à l'identique)
 * @param example      valeur d'exemple du modèle à remplir
 * @param label        libellé court en français (formulaire)
 * @param help         explication d'une ligne en français
 */
public record AbilityParam(String key, ValueType valueType, boolean required, Double min, boolean minExclusive, Double max,
                           boolean maxExclusive, Object defaultValue, boolean writeDefault, String example,
                           String label, String help) {

    /** Type de valeur d'un paramètre ; le type Java lu est indiqué entre parenthèses. */
    public enum ValueType {
        /** Nombre décimal ({@link Double}). */
        DECIMAL,
        /** Entier ({@link Integer}). */
        INT,
        /** Oui/non ({@link Boolean}). */
        BOOL,
        /** Créature vanilla vivante ({@link org.bukkit.entity.EntityType}). */
        ENTITY_TYPE
    }

    public AbilityParam {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(valueType, "valueType");
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("un paramètre obligatoire n'a pas de défaut : " + key);
        }
    }

    /** Obligatoire, sans défaut, toujours écrit. */
    static AbilityParam required(String key, ValueType valueType, Double min, boolean minExclusive, Double max,
                                 boolean maxExclusive, String example, String label, String help) {
        return new AbilityParam(key, valueType, true, min, minExclusive, max, maxExclusive, null, true, example, label, help);
    }

    /** Facultatif ; {@code writeDefault} dit s'il est écrit quand il vaut son défaut. */
    static AbilityParam optional(String key, ValueType valueType, Double min, boolean minExclusive, Double max,
                                 boolean maxExclusive, Object defaultValue, boolean writeDefault, String example,
                                 String label, String help) {
        return new AbilityParam(key, valueType, false, min, minExclusive, max, maxExclusive, defaultValue, writeDefault,
                example, label, help);
    }

    /** Vrai si {@code value} doit apparaître dans le fichier rendu. */
    boolean shouldWrite(Object value) {
        return value != null && (required || writeDefault || !Objects.equals(value, defaultValue));
    }

    /** Message de bornes en français, repris des messages du parseur d'avant #542. */
    String boundsMessage() {
        if (min != null && min == 0 && minExclusive && max != null && max == 1 && maxExclusive) {
            return "doit être strictement compris entre 0 et 1";
        }
        if (min != null && min == 0 && minExclusive && max != null && max == 1) {
            return "doit être compris entre 0 (exclu) et 1";
        }
        if (min != null && min == 0 && minExclusive && max == null) {
            return "doit être strictement positif";
        }
        if (min != null && min == 1 && !minExclusive && max == null && valueType == ValueType.INT) {
            return "doit être strictement positif";
        }
        if (min != null && min == 0 && !minExclusive && max == null) {
            return "ne peut pas être négatif";
        }
        StringBuilder sb = new StringBuilder("doit être");
        if (min != null) {
            sb.append(minExclusive ? " strictement supérieur à " : " supérieur ou égal à ").append(format(min));
        }
        if (max != null) {
            sb.append(min != null ? " et" : "").append(maxExclusive ? " strictement inférieur à " : " inférieur ou égal à ")
                    .append(format(max));
        }
        return sb.toString();
    }

    boolean inBounds(double value) {
        if (min != null && (minExclusive ? value <= min : value < min)) {
            return false;
        }
        return max == null || (maxExclusive ? value < max : value <= max);
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
