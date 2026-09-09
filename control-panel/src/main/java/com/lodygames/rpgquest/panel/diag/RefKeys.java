package com.lodygames.rpgquest.panel.diag;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalisation des identifiants de contenu RPGQuest (quêtes, PNJ…) pour les <strong>vérifications
 * de référence</strong> faites côté panel. Un même id peut apparaître avec ou sans le préfixe
 * {@code rpgquest:} : on indexe les deux formes. Partagé par {@code AgentPages} (cartes détaillées)
 * et les {@link DiagnosticProvider} pour garantir des résultats identiques (issue #38, §16).
 */
public final class RefKeys {

    private RefKeys() {
    }

    /** Ensemble d'identifiants normalisés (minuscule, avec ET sans préfixe {@code rpgquest:}). */
    public static Set<String> idKeySet(List<String> ids) {
        Set<String> out = new HashSet<>();
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String low = raw.toLowerCase(Locale.ROOT);
            out.add(low);
            int c = low.indexOf(':');
            out.add(c >= 0 ? low.substring(c + 1) : "rpgquest:" + low);
        }
        return out;
    }

    /** {@code true} si la référence est vide (rien à vérifier) ou présente dans l'ensemble connu. */
    public static boolean known(Set<String> keys, String ref) {
        if (ref == null || ref.isBlank() || "null".equals(ref)) {
            return true;
        }
        String low = ref.toLowerCase(Locale.ROOT);
        if (keys.contains(low)) {
            return true;
        }
        int c = low.indexOf(':');
        return keys.contains(c >= 0 ? low.substring(c + 1) : "rpgquest:" + low);
    }
}
