package com.lodygames.rpgquest.webapi;

import java.nio.file.Path;

/**
 * Contenu éditorial du portail public (#56), indépendant du serveur Minecraft.
 *
 * @param newsDir          dossier des actualités ({@code *.md}, une par fichier) ; absent = aucune actualité
 * @param serverAddress    adresse de connexion Minecraft à afficher ; vide = « communiquée bientôt »
 * @param minecraftVersion version Minecraft à afficher ; vide = non affichée
 */
public record SiteSettings(Path newsDir, String serverAddress, String minecraftVersion) {

    public SiteSettings {
        serverAddress = serverAddress == null ? "" : serverAddress.trim();
        minecraftVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
    }

    /** Valeurs par défaut d'un {@code web-api.properties} sans clé éditoriale. */
    public static SiteSettings defaults() {
        return new SiteSettings(Path.of("news"), "", "");
    }
}
