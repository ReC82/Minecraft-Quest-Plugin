package com.lodygames.rpgquest.webapi.site;

/**
 * Métadonnées d'une page publique (#56).
 *
 * @param path        chemin canonique ({@code /actualites/mon-article}) ; vide = pas de lien canonique
 * @param description résumé pour les moteurs de recherche et les partages (≤ 160 caractères conseillés)
 * @param section     entrée de menu active ({@link #HOME}, {@link #NEWS}…) ; vide = aucune
 */
public record PageMeta(String path, String description, String section) {

    public static final String HOME = "/";
    public static final String NEWS = "/actualites";
    public static final String BOARDS = "/classements";
    public static final String PLAYERS = "/joueurs";
    public static final String INFO = "/infos";
    public static final String ACCOUNT = "/compte";

    public static PageMeta none() {
        return new PageMeta("", "", "");
    }
}
