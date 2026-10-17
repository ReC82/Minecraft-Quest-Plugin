package com.lodygames.rpgquest.panel.mob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Issue #214 — différences, <strong>par clé</strong>, entre deux profils en forme canonique (même
 * rendu déterministe côté plugin, donc une clé identique s'écrit identiquement). Une clé du profil
 * actuel absente du fichier importé est listée comme retirée : rien ne disparaît sans être dit.
 */
public final class MobProfileDiff {

    /** @param before vide = clé ajoutée ; {@code after} vide = clé retirée */
    public record Change(String key, String before, String after) {
        public boolean added() {
            return before.isEmpty();
        }

        public boolean removed() {
            return after.isEmpty();
        }
    }

    private MobProfileDiff() {
    }

    public static List<Change> changes(String before, String after) {
        Map<String, String> a = blocks(before);
        Map<String, String> b = blocks(after);
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<Change> out = new ArrayList<>();
        for (String key : keys) {
            String x = a.getOrDefault(key, "");
            String y = b.getOrDefault(key, "");
            if (!x.equals(y)) {
                out.add(new Change(key, x, y));
            }
        }
        return out;
    }

    /** Clé de premier niveau → sa valeur (lignes indentées suivantes comprises), commentaires exclus. */
    static Map<String, String> blocks(String yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        String current = null;
        StringBuilder value = new StringBuilder();
        for (String line : (yaml == null ? "" : yaml).replace("\r", "").split("\n")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            boolean top = !Character.isWhitespace(line.charAt(0)) && !line.startsWith("-");
            int colon = line.indexOf(':');
            if (top && colon > 0) {
                if (current != null) {
                    out.put(current, value.toString().strip());
                }
                current = line.substring(0, colon).strip();
                value.setLength(0);
                value.append(line.substring(colon + 1).strip());
            } else if (current != null) {
                value.append('\n').append(line.stripTrailing());
            }
        }
        if (current != null) {
            out.put(current, value.toString().strip());
        }
        return out;
    }
}
