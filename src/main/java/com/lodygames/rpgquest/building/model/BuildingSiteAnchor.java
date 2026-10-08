package com.lodygames.rpgquest.building.model;

/**
 * La règle d'ancrage : du bloc cliqué à la position enregistrée (issue #213).
 *
 * <h2>La règle, en une phrase</h2>
 *
 * <p>L'ancre est le bloc <strong>adjacent à la face cliquée</strong>, c'est-à-dire l'espace libre
 * contre lequel on vient de cliquer.</p>
 *
 * <p>Cliquer le dessus d'un bloc d'herbe en {@code y=66} enregistre donc {@code y=67} : la case où
 * l'on se tiendrait, et où reposera le premier niveau du bâtiment. C'est ce que le ticket appelle
 * « l'ancre au niveau du sol ».</p>
 *
 * <h2>Pourquoi pas le bloc cliqué lui-même</h2>
 *
 * <p>Parce qu'un bâtiment ne s'enfonce pas d'un bloc dans le terrain. Si l'ancre était le bloc
 * cliqué, tout placement futur devrait ajouter {@code +1} en Y — un décalage implicite, que chaque
 * appelant appliquerait de son côté, et qu'un seul oublierait. Le ticket le dit d'ailleurs
 * explicitement : l'ancre doit être « indépendante d'un offset interne implicite ». Autant la
 * résoudre ici, une fois, et enregistrer une position directement utilisable.</p>
 *
 * <h2>Ce que la règle donne sur les autres faces</h2>
 *
 * <p>La même règle s'applique partout, sans cas particulier : cliquer la face nord d'un mur ancre
 * un bloc au nord de ce mur, cliquer le dessous d'un plafond ancre un bloc en dessous. C'est
 * cohérent — on désigne toujours l'espace libre devant soi — même si le parcours normal est un clic
 * sur le dessus du sol. Un clic sans face exploitable ({@link ClickedFace#SELF}) n'applique aucun
 * décalage.</p>
 *
 * <p><strong>Y n'est pas borné ici.</strong> Les limites réelles d'un monde ne sont pas connues de
 * cette classe (elles dépendent du monde chargé) : c'est l'appelant côté Bukkit qui refuse une
 * position hors limites, parce que c'est lui qui a le monde sous la main.</p>
 *
 * @param x bloc d'ancrage en X
 * @param y bloc d'ancrage en Y
 * @param z bloc d'ancrage en Z
 */
public record BuildingSiteAnchor(int x, int y, int z) {

    /**
     * Applique la règle d'ancrage.
     *
     * @param clickedX bloc réellement cliqué, en X
     * @param clickedY bloc réellement cliqué, en Y
     * @param clickedZ bloc réellement cliqué, en Z
     * @param face     face cliquée de ce bloc
     */
    public static BuildingSiteAnchor resolve(int clickedX, int clickedY, int clickedZ,
                                             ClickedFace face) {
        ClickedFace f = face == null ? ClickedFace.SELF : face;
        return new BuildingSiteAnchor(clickedX + f.modX(), clickedY + f.modY(), clickedZ + f.modZ());
    }
}
