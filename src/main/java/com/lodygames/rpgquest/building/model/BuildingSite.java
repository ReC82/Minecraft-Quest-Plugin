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
 * <h2>Deux textes, deux usages (issue #94)</h2>
 *
 * <p>{@code description} est une <strong>note d'administrateur</strong> : une ligne ou deux, lue
 * dans une liste, pour se rappeler à quoi sert cet emplacement. {@code aiBrief} est le
 * <strong>brief architectural</strong> donné au générateur : plusieurs paragraphes décrivant
 * matériaux, étages, pièces et contraintes.</p>
 *
 * <p>Les confondre était un vrai défaut d'usage : une note de liste ne peut pas s'étendre à
 * plusieurs milliers de caractères sans rendre la liste illisible, et un brief ne peut pas tenir en
 * 500 caractères sans perdre ce qui le rend utile. Les deux vivent donc séparément, avec leur
 * propre borne et leur propre place à l'écran.</p>
 *
 * @param id          identité stable, jamais recalculée ({@code buildsite_0001})
 * @param name        libellé humain, modifiable
 * @param description note courte de l'administrateur ; vide par défaut
 * @param aiBrief     brief architectural destiné à la génération ; vide par défaut
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
public record BuildingSite(String id, String name, String description, String aiBrief, String world,
                           int x, int y, int z, Facing facing, SiteStatus status,
                           String createdBy, Instant createdAt) {

    /** Libellé donné à un emplacement tout juste créé, avant tout renommage. */
    public static final String DEFAULT_NAME = "Nouvel emplacement";

    /** Longueur maximale acceptée pour un nom, côté serveur comme côté panel. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Longueur maximale acceptée pour une description — une note, pas un brief. */
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /**
     * Longueur maximale acceptée pour un brief architectural (issue #94).
     *
     * <p>Borne de <strong>sécurité</strong>, pas de confort : elle existe pour qu'un collage
     * accidentel de plusieurs pages ne gonfle ni la base, ni le relevé de l'agent, ni le prompt
     * envoyé au fournisseur d'IA. 6 000 caractères représentent une à deux pages de brief détaillé
     * — très au-delà des quatre briefs réellement écrits à ce jour (385 à 490 caractères), et bien
     * au-delà du brief de référence de la Bibliothèque du Hub (479).</p>
     */
    public static final int MAX_AI_BRIEF_LENGTH = 6000;

    public BuildingSite {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
        description = description == null ? "" : description.trim();
        aiBrief = aiBrief == null ? "" : aiBrief.trim();
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
        return new BuildingSite(id, name, "", "", world, anchor.x(), anchor.y(), anchor.z(),
                facing, SiteStatus.EMPTY, createdBy, createdAt);
    }

    /** Même emplacement, nom remplacé. */
    public BuildingSite withName(String newName) {
        return new BuildingSite(id, newName, description, aiBrief, world, x, y, z, facing, status,
                createdBy, createdAt);
    }

    /** Même emplacement, description remplacée. */
    public BuildingSite withDescription(String newDescription) {
        return new BuildingSite(id, name, newDescription, aiBrief, world, x, y, z, facing, status,
                createdBy, createdAt);
    }

    /** Même emplacement, brief architectural remplacé (issue #94). */
    public BuildingSite withAiBrief(String newBrief) {
        return new BuildingSite(id, name, description, newBrief, world, x, y, z, facing, status,
                createdBy, createdAt);
    }

    /** Même emplacement, orientation corrigée — la position ne bouge pas. */
    public BuildingSite withFacing(Facing newFacing) {
        return new BuildingSite(id, name, description, aiBrief, world, x, y, z, newFacing, status,
                createdBy, createdAt);
    }

    /**
     * Même emplacement, état changé (lot « placement » de #213).
     *
     * <p>L'état suit le fait, il ne le décide pas : il passe à {@code OCCUPIED} parce qu'un bâtiment
     * a été collé, et revient à {@code EMPTY} parce que la zone a été restaurée.</p>
     */
    public BuildingSite withStatus(SiteStatus newStatus) {
        return new BuildingSite(id, name, description, aiBrief, world, x, y, z, facing, newStatus,
                createdBy, createdAt);
    }

    /** Un brief architectural est-il renseigné ? C'est la précondition d'une génération. */
    public boolean hasAiBrief() {
        return !aiBrief.isEmpty();
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
