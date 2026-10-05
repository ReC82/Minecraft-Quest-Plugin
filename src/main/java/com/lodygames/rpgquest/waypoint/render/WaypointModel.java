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

    /**
     * Pose la structure dans le monde. Ne doit jamais lever si la zone a été jugée constructible.
     *
     * @param displayName nom canonique affiché (issue #167 : panneaux latéraux) -- jamais
     *                     régénéré ici, toujours celui déjà persisté par l'appelant.
     */
    void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing, String displayName);

    /** Décalage du bloc <strong>interacteur</strong> (le bouton) : seul un clic sur CE bloc découvre. */
    BlockOffset interactor(BlockFace facing);

    /** Tous les blocs constitutifs à protéger (interacteur inclus), en décalages relatifs à l'ancre. */
    Set<BlockOffset> protectedBlocks(BlockFace facing);

    /**
     * Issue #191 : matériau <strong>attendu</strong> à chaque décalage constitutif, pour pouvoir
     * diagnostiquer une structure abîmée (bloc manquant ou remplacé) et la restaurer <em>sur place</em>
     * sans dupliquer la logique de {@link #place} ni déplacer le waypoint.
     *
     * <p>Ne décrit que les blocs dont l'absence casse la structure — le sol de soutien, les blocs
     * d'air de dégagement et tout détail cosmétique restent hors de ce contrat. Le texte des
     * panneaux n'est pas comparé ici : {@code WaypointService#upgradeSigns} le rétablit déjà de
     * façon idempotente.</p>
     */
    java.util.Map<BlockOffset, org.bukkit.Material> expectedBlocks(BlockFace facing);
}
