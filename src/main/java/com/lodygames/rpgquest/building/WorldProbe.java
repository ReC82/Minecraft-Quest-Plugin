package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingFootprint;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Ce que le service de placement a besoin de savoir du monde réel (issue #213, lot « placement »).
 *
 * <h2>Pourquoi une interface pour trois questions</h2>
 *
 * <p>Les limites de hauteur d'un monde et le contenu d'une zone ne sont connus que de Bukkit. Les
 * demander derrière une interface RPGQuest rend <strong>tout le service de placement testable</strong> :
 * on peut vérifier qu'une emprise dépassant le plafond du monde est refusée, ou qu'une zone pleine
 * déclenche un avertissement, sans démarrer un serveur. Sans cela, ces règles ne seraient
 * vérifiables qu'à la main, en jeu — c'est-à-dire presque jamais.</p>
 */
public interface WorldProbe {

    /** Vrai si le monde existe et est chargé en ce moment. */
    boolean loaded(String world);

    /** Hauteur minimale constructible, ou vide si le monde n'est pas chargé. */
    OptionalInt minHeight(String world);

    /** Hauteur maximale <strong>exclusive</strong>, comme Bukkit la donne. */
    OptionalInt maxHeight(String world);

    /**
     * Nombre de blocs non-air dans l'emprise, ou vide si le calcul n'est pas possible
     * (monde déchargé, chunk absent).
     *
     * <p>Sert uniquement à <strong>avertir</strong> : « cette zone contient déjà 180 blocs ». Jamais
     * à refuser — de l'herbe et du terrain naturel sont parfaitement normaux à l'endroit où l'on
     * veut bâtir, et refuser pour cela empêcherait tout placement.</p>
     */
    OptionalLong countNonAir(BuildingFootprint footprint);
}
