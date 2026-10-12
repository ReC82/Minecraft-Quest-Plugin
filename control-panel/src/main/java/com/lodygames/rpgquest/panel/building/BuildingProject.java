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
 * @param sourceSiteId emplacement dont le brief a été importé, vide sinon — la trace de la migration
 * @param version      version de la fiche, pour qu'une modification faite ailleurs ne soit pas écrasée
 */
public record BuildingProject(String id, String agentId, String name, String brief, String buildingId,
                              String front, int maxWidth, int maxHeight, String sourceSiteId,
                              int version, boolean archived, String createdBy, Instant createdAt,
                              String updatedBy, Instant updatedAt) {

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
        sourceSiteId = sourceSiteId == null ? "" : sourceSiteId;
        createdBy = createdBy == null ? "" : createdBy;
        updatedBy = updatedBy == null ? "" : updatedBy;
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
