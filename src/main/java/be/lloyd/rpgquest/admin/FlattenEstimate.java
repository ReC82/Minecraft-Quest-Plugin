package be.lloyd.rpgquest.admin;

import be.lloyd.rpgquest.config.FlattenShape;

/**
 * Aperçu (calculé sans toucher au monde) d'une opération d'aplatissement :
 * nombre de colonnes réellement concernées par la forme choisie, et une
 * estimation du nombre de blocs qui seront écrits (majorant — l'exécution
 * réelle saute les blocs déjà corrects).
 */
public record FlattenEstimate(FlattenShape shape, int radius, int y, int columnCount, long blockEstimate) {
}
