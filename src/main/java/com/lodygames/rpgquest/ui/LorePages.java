package com.lodygames.rpgquest.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Issue #475 (retour du 17/10) — découpe une liste de lignes d'infobulle en pages de hauteur
 * bornée. Le client place une infobulle trop haute au-dessus du curseur sans la borner en haut :
 * son début (titre, début de la description) sortait de la fenêtre (~854×480, 240 px d'interface
 * de haut en échelle automatique). Chaque page devient un objet distinct de la vue détail.
 */
public final class LorePages {

    private LorePages() {
    }

    /** Pages d'au plus {@code maxLines} lignes, dans l'ordre ; aucune page pour une liste vide. */
    public static <T> List<List<T>> split(List<T> lines, int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines doit être >= 1 : " + maxLines);
        }
        List<List<T>> pages = new ArrayList<>();
        for (int from = 0; from < lines.size(); from += maxLines) {
            pages.add(List.copyOf(lines.subList(from, Math.min(lines.size(), from + maxLines))));
        }
        return pages;
    }
}
