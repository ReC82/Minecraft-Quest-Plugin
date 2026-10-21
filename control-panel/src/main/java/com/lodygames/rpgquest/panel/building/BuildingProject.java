package com.lodygames.rpgquest.panel.building;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Un projet de l'<strong>Atelier de création</strong> (issues #265, #267) : ce qu'on veut construire,
 * indépendamment de tout emplacement.
 *
 * <p>Avant #265, concevoir un bâtiment exigeait d'avoir d'abord posé un repère dans le monde : le
 * brief vivait dans la fiche de l'emplacement, et les brouillons de plan lui étaient rattachés. Or un
 * bâtiment se conçoit une fois et se pose autant de fois qu'on veut. Le projet porte donc la
 * description du bâtiment ; l'emplacement ne garde que la description du lieu.</p>
 *
 * @param buildingId   identifiant sous lequel le bâtiment entrera dans la bibliothèque — choisi par
 *                     l'administrateur, jamais inventé par le modèle (#94 : l'identifiant dérivé du
 *                     nom d'un emplacement avait heurté celui de la V1)
 * @param front        orientation de référence de l'entrée (NORTH/EAST/SOUTH/WEST) ; la pose sur un
 *                     emplacement réoriente le bâtiment, elle n'est qu'un point de départ
 * @param maxWidth     emprise maximale en X (largeur), en blocs
 * @param maxDepth     emprise maximale en Z (longueur / profondeur), en blocs (#305) ; un projet
 *                     antérieur, à limite carrée unique N, est relu X = N, Z = N
 * @param maxHeight    hauteur maximale en Y, en blocs
 * @param sourceSiteId emplacement dont le brief a été importé, vide sinon — la trace de la migration
 * @param version      version de la fiche, pour qu'une modification faite ailleurs ne soit pas écrasée
 */
public record BuildingProject(String id, String agentId, String name, String brief, String buildingId,
                              String front, int maxWidth, int maxDepth, int maxHeight, String sourceSiteId,
                              int version, boolean archived, String createdBy, Instant createdAt,
                              String updatedBy, Instant updatedAt, String kind, int passageWidth,
                              int passageHeight) {

    /** Type d'un projet (#568) : un bâtiment ordinaire, ou une arche de portail. */
    public static final String KIND_BUILDING = "building";
    public static final String KIND_PORTAL = "portal";
    /** Passage par défaut d'une arche : 3 de large, 4 de haut. */
    public static final int DEFAULT_PASSAGE_WIDTH = 3;
    public static final int DEFAULT_PASSAGE_HEIGHT = 4;

    /** Forme antérieure à #568 : un bâtiment ordinaire. */
    public BuildingProject(String id, String agentId, String name, String brief, String buildingId,
                           String front, int maxWidth, int maxDepth, int maxHeight, String sourceSiteId,
                           int version, boolean archived, String createdBy, Instant createdAt,
                           String updatedBy, Instant updatedAt) {
        this(id, agentId, name, brief, buildingId, front, maxWidth, maxDepth, maxHeight, sourceSiteId, version,
                archived, createdBy, createdAt, updatedBy, updatedAt, KIND_BUILDING, 0, 0);
    }

    /** Même règle que le plugin ({@code BuildingPlan#ID}) : refusé ici plutôt qu'à l'audit. */
    public static final Pattern BUILDING_ID = Pattern.compile("[a-z0-9_]{3,48}");

    /**
     * Borne de la description (#292) : 12 000 caractères, la même que le plugin
     * ({@code BuildingSite.MAX_AI_BRIEF_LENGTH}) et la validation d'action. Comptée APRÈS
     * normalisation des fins de ligne : un navigateur envoie « \r\n », son compteur n'en voit qu'un.
     */
    public static final int MAX_BRIEF = 12_000;
    public static final int MAX_NAME = 80;

    public BuildingProject {
        name = name == null ? "" : name;
        brief = brief == null ? "" : brief;
        buildingId = buildingId == null ? "" : buildingId;
        front = normaliseFront(front);
        maxDepth = maxDepth > 0 ? maxDepth : maxWidth;
        sourceSiteId = sourceSiteId == null ? "" : sourceSiteId;
        createdBy = createdBy == null ? "" : createdBy;
        updatedBy = updatedBy == null ? "" : updatedBy;
        kind = normaliseKind(kind);
        if (!KIND_PORTAL.equals(kind)) {
            passageWidth = 0;
            passageHeight = 0;
        }
    }

    public static String normaliseKind(String raw) {
        return KIND_PORTAL.equals(raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) ? KIND_PORTAL : KIND_BUILDING;
    }

    /** Une arche de portail (issue #568). */
    public boolean portal() {
        return KIND_PORTAL.equals(kind);
    }

    /**
     * Le brief réellement envoyé à l'IA : celui de l'administrateur, suivi pour une arche du contrat
     * de portail (#568) — type, passage traversable de la taille choisie, ancre au centre du passage.
     * Le brief enregistré, lui, reste le texte de l'administrateur.
     */
    public String briefForAi() {
        return portal() ? brief + portalContract(front, passageWidth, passageHeight) : brief;
    }

    /** Le contrat d'une arche de portail, tel que le serveur le vérifiera (PortalPassageFinder). */
    public static String portalContract(String front, int width, int height) {
        String f = normaliseFront(front);
        boolean alongX = "NORTH".equals(f) || "SOUTH".equals(f);
        return "\n\nTYPE IMPOSÉ : ARCHE DE PORTAIL\n"
                + "- Déclare « kind: portal ». Ce n'est pas un bâtiment habitable : ni pièces, ni intérieur.\n"
                + "- Le passage traversable fait exactement " + width + " bloc(s) de large (axe "
                + (alongX ? "x" : "z") + ") et " + height + " blocs de haut, et traverse l'arche de part en part "
                + "dans l'axe de la façade d'entrée (front: " + f + "), sur toute la profondeur (size."
                + (alongX ? "z" : "x") + ").\n"
                + "- Déclare « anchor » au centre du passage, sur la première couche d'air où l'on marche "
                + "(juste au-dessus de la dalle du sol s'il y en a une).\n"
                + "- Aucun bloc dans le passage (ni dalle, tapis, torche, rideau ni vitre) : le serveur relève le "
                + "passage sur la grille depuis l'ancre et refuse l'arche s'il n'est pas dégagé.";
    }

    /** Les limites, dans l'ordre de lecture d'un bâtiment : « 13 × 9 × 7 » = X × Z × Y. */
    public String limitsLabel() {
        return limitsLabel(maxWidth, maxDepth, maxHeight);
    }

    /**
     * Libellé commun des trois limites (#305), pour que l'aperçu, la bibliothèque et les fiches
     * disent la même chose : largeur X, longueur Z, hauteur Y.
     */
    public static String limitsLabel(int width, int depth, int height) {
        return width + " de large (X) × " + depth + " de long (Z) × " + height + " de haut (Y)";
    }

    public boolean hasBrief() {
        return !brief.isBlank();
    }

    /** Orientation acceptée, sinon le sud — une entrée côté sud est le cas le plus courant. */
    public static String normaliseFront(String raw) {
        String f = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (f) {
            case "NORTH", "EAST", "SOUTH", "WEST" -> f;
            default -> "SOUTH";
        };
    }

    public String frontLabel() {
        return switch (front) {
            case "NORTH" -> "nord";
            case "EAST" -> "est";
            case "WEST" -> "ouest";
            default -> "sud";
        };
    }

    /** Un identifiant de bibliothèque proposé à partir d'un nom : minuscules, sans accent, « _ ». */
    public static String suggestBuildingId(String name) {
        String base = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replaceAll("[àáâä]", "a").replaceAll("[èéêë]", "e")
                .replaceAll("[ìíîï]", "i").replaceAll("[òóôö]", "o")
                .replaceAll("[ùúûü]", "u").replace('ç', 'c')
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.length() < 3) {
            base = "batiment_" + base;
        }
        return base.length() > 48 ? base.substring(0, 48) : base;
    }
}
