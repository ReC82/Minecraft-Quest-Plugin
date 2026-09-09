package com.lodygames.rpgquest.waypoint.render;

import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * Représentation physique d'un waypoint, <strong>abstraite et versionnée</strong> (issue #124 :
 * « le modèle visuel doit être abstrait/versionné, pas codé en dur dans la logique métier »).
 *
 * <p>La logique métier ({@code WaypointService}) ne connaît que le <em>numéro de version</em>
 * ({@code waypoints.model_version}) et l'ancre persistée (x/y/z + orientation) ; elle demande au
 * {@link WaypointModelRegistry} le modèle correspondant pour poser les blocs, savoir quel bloc est
 * l'interacteur, et quels blocs protéger. Ajouter un modèle v2 (autre structure, ou un schematic)
 * puis re-poser les waypoints existants ne demande donc aucune migration de schéma ni de changement
 * dans la logique de découverte : l'identité du waypoint est indépendante du rendu.</p>
 *
 * <p>Conventions :</p>
 * <ul>
 *   <li><strong>ancre</strong> = colonne de surface, premier bloc d'air au-dessus du sol (le
 *       {@code y} rendu par {@code RandomSafeLocationFinder}) ;</li>
 *   <li><strong>facing</strong> = direction cardinale vers laquelle « regarde » l'interacteur (le
 *       bouton), donc aussi le côté où il est posé ;</li>
 *   <li>tous les {@link BlockOffset} sont relatifs à l'ancre.</li>
 * </ul>
 */
public interface WaypointModel {

    /** Numéro de version stable de ce modèle (>= 1). Stocké tel quel en base. */
    int version();

    /** Pose la structure dans le monde. Ne doit jamais lever si la zone a été jugée constructible. */
    void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing);

    /** Décalage du bloc <strong>interacteur</strong> (le bouton) : seul un clic sur CE bloc découvre. */
    BlockOffset interactor(BlockFace facing);

    /** Tous les blocs constitutifs à protéger (interacteur inclus), en décalages relatifs à l'ancre. */
    Set<BlockOffset> protectedBlocks(BlockFace facing);
}
