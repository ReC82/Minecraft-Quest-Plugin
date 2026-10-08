package com.lodygames.rpgquest.building.model;

import java.time.Instant;

/**
 * Un emplacement de construction : un point d'ancrage nommé dans un monde (issue #213).
 *
 * <p>C'est le socle du futur système de bâtiments. Un emplacement ne contient <strong>aucun</strong>
 * bâtiment, aucune dimension, aucune emprise et aucun schematic — ce lot n'en sait rien et ne
 * prétend rien à leur sujet. Il dit seulement : « ici, orienté comme ça, on posera quelque chose ».
 *
 * <h2>L'identité est l'{@code id}, jamais la position ni le nom</h2>
 *
 * <p>{@code id} est attribué une fois, séquentiellement ({@code buildsite_0001}), et ne change
 * plus : c'est lui qu'un futur {@code BuildingPlacement} citera. Le {@code name} est un libellé
 * humain, modifiable à volonté depuis le Control Panel, et la position peut être corrigée sans que
 * l'identité bouge. Confondre les trois — comme un id dérivé des coordonnées — rendrait toute
 * correction destructrice de références.</p>
 *
 * <h2>Coordonnées entières</h2>
 *
 * <p>Un bâtiment se pose sur la grille de blocs : l'ancre est un bloc, pas une position flottante.
 * Les centres de village ({@code village_centers}) stockent des {@code REAL} parce qu'ils sont des
 * destinations de téléportation, où un demi-bloc compte ; ici un {@code REAL} n'exprimerait qu'une
 * précision inutilisable.</p>
 *
 * @param id          identité stable, jamais recalculée ({@code buildsite_0001})
 * @param name        libellé humain, modifiable
 * @param description note libre de l'administrateur ; vide par défaut
 * @param world       nom du monde Minecraft
 * @param x           ancre en X (bloc)
 * @param y           ancre en Y (bloc)
 * @param z           ancre en Z (bloc)
 * @param facing      orientation cardinale retenue à la création, corrigeable ensuite
 * @param status      état de l'emplacement ({@link SiteStatus#EMPTY} tant qu'aucun bâtiment n'existe)
 * @param createdBy   pseudo de l'administrateur créateur, ou vide si l'identité n'était pas
 *                    disponible — jamais inventé
 * @param createdAt   instant de création
 */
public record BuildingSite(String id, String name, String description, String world,
                           int x, int y, int z, Facing facing, SiteStatus status,
                           String createdBy, Instant createdAt) {

    /** Libellé donné à un emplacement tout juste créé, avant tout renommage. */
    public static final String DEFAULT_NAME = "Nouvel emplacement";

    /** Longueur maximale acceptée pour un nom, côté serveur comme côté panel. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Longueur maximale acceptée pour une description. */
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    public BuildingSite {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
        description = description == null ? "" : description.trim();
        world = world == null ? "" : world.trim();
        facing = facing == null ? Facing.NORTH : facing;
        status = status == null ? SiteStatus.EMPTY : status;
        createdBy = createdBy == null ? "" : createdBy.trim();
    }

    /**
     * Un emplacement neuf, portant le nom que le joueur a saisi (issue #227) et sans description.
     *
     * <p>Un nom vide retombe sur {@link #DEFAULT_NAME} par le constructeur canonique — mais ce cas
     * ne devrait plus se produire depuis #227 : le nom est exigé à la confirmation, et c'est
     * justement ce qui fait qu'un clic de travers ne laisse rien derrière lui.</p>
     */
    public static BuildingSite created(String id, String world, BuildingSiteAnchor anchor,
                                       Facing facing, String name, String createdBy,
                                       Instant createdAt) {
        return new BuildingSite(id, name, "", world, anchor.x(), anchor.y(), anchor.z(),
                facing, SiteStatus.EMPTY, createdBy, createdAt);
    }

    /** Même emplacement, nom remplacé. */
    public BuildingSite withName(String newName) {
        return new BuildingSite(id, newName, description, world, x, y, z, facing, status,
                createdBy, createdAt);
    }

    /** Même emplacement, description remplacée. */
    public BuildingSite withDescription(String newDescription) {
        return new BuildingSite(id, name, newDescription, world, x, y, z, facing, status,
                createdBy, createdAt);
    }

    /** Même emplacement, orientation corrigée — la position ne bouge pas. */
    public BuildingSite withFacing(Facing newFacing) {
        return new BuildingSite(id, name, description, world, x, y, z, newFacing, status,
                createdBy, createdAt);
    }

    /** Position lisible, dans l'ordre où un administrateur la lit en jeu. */
    public String positionLabel() {
        return x + " / " + y + " / " + z;
    }

    /** Deux emplacements occupent-ils exactement le même bloc du même monde ? */
    public boolean samePositionAs(String otherWorld, int otherX, int otherY, int otherZ) {
        return world.equals(otherWorld) && x == otherX && y == otherY && z == otherZ;
    }
}
