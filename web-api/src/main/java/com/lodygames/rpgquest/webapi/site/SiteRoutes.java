package com.lodygames.rpgquest.webapi.site;

/**
 * Adresses des pages de classements et de profils, réelles ou de démonstration (#56).
 *
 * <p>Les pages de démonstration ({@code /demo/…}) réutilisent exactement le rendu des vraies pages,
 * mais avec les joueurs fictifs de {@link DemoData} : les liens internes doivent donc rester dans
 * l'espace de démonstration, et chaque page porter le bandeau « données de démonstration ».</p>
 *
 * @param boards  chemin de la page des classements
 * @param players chemin de la liste des profils
 * @param events  chemin de la page des événements
 * @param demo    {@code true} : joueurs fictifs, page non indexée et bandeau obligatoire
 */
public record SiteRoutes(String boards, String players, String events, boolean demo) {

    public static final SiteRoutes LIVE = new SiteRoutes(PageMeta.BOARDS, PageMeta.PLAYERS, PageMeta.EVENTS, false);
    public static final SiteRoutes DEMO = new SiteRoutes("/demo/classements", "/demo/joueurs", "/demo/evenements", true);

    /** Bandeau de démonstration en tête de page ; vide pour les vraies pages. */
    String banner() {
        return demo ? DemoData.BANNER : "";
    }
}
