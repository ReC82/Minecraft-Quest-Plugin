package com.lodygames.rpgquest.webapi.site;

/**
 * Métadonnées d'une page publique (#56).
 *
 * @param path        chemin canonique ({@code /actualites/mon-article}) ; vide = pas de lien canonique
 * @param description résumé pour les moteurs de recherche et les partages (≤ 160 caractères conseillés)
 * @param section     entrée de menu active ({@link #HOME}, {@link #NEWS}…) ; vide = aucune
 * @param noindex     {@code true} : page à ne pas indexer (pages de démonstration)
 */
public record PageMeta(String path, String description, String section, boolean noindex) {

    public static final String HOME = "/";
    public static final String NEWS = "/actualites";
    public static final String EVENTS = "/evenements";
    public static final String BOARDS = "/classements";
    public static final String PLAYERS = "/joueurs";
    public static final String INFO = "/infos";
    public static final String PROGRESSION = "/progression";
    public static final String ACCOUNT = "/compte";

    public PageMeta(String path, String description, String section) {
        this(path, description, section, false);
    }

    public static PageMeta none() {
        return new PageMeta("", "", "");
    }

    /** Page de démonstration : jamais de lien canonique, jamais indexée, entrée de menu {@code section} active. */
    public static PageMeta demo(String description, String section) {
        return new PageMeta("", description, section, true);
    }
}
