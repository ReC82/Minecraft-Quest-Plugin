package com.lodygames.rpgquest.quest.progress;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceExtractEvent;

/**
 * Progression de {@code SMELT_ITEM} (issue #141). <strong>Audit du choix d'événement</strong>, qui
 * est tout l'enjeu de ce type d'objectif :
 *
 * <ul>
 *   <li>{@code FurnaceExtractEvent} est le seul événement de cuisson qui porte un
 *       <strong>joueur</strong>. Il est émis quand un joueur retire le résultat du four, et donne
 *       l'objet obtenu <em>et</em> la quantité retirée — l'attribution est donc toujours exacte,
 *       jamais devinée.</li>
 *   <li>{@code FurnaceSmeltEvent}, lui, n'a <strong>aucun</strong> joueur (il décrit la cuisson du
 *       bloc). L'utiliser obligerait à deviner un propriétaire, ce qui créditerait le mauvais joueur
 *       sur un four partagé — exactement ce que le ticket interdit.</li>
 *   <li>Les trois fours vanilla sont acceptés : {@code FURNACE}, {@code BLAST_FURNACE} et
 *       {@code SMOKER}. Le type de bloc est vérifié explicitement plutôt que supposé : ainsi un
 *       futur bloc émettant le même événement ne créditerait pas une quête sans décision.</li>
 *   <li>Un objet obtenu <strong>autrement</strong> (coffre, {@code /give}, craft, troc, ramassage)
 *       n'émet jamais cet événement : aucune progression, ce qui est précisément la garantie
 *       demandée.</li>
 *   <li>Une extraction par un <strong>entonnoir</strong> n'émet pas cet événement non plus (aucun
 *       joueur n'agit) : automatiser un four ne fait donc progresser aucune quête.</li>
 * </ul>
 *
 * <p>Limite assumée : un joueur qui retire le résultat d'un four rempli par quelqu'un d'autre
 * progresse. C'est cohérent avec « il a sorti la cuisson du four », et c'est le seul comportement
 * que l'API publique permette d'attribuer avec certitude.</p>
 */
final class QuestSmeltListener implements Listener {

    private final QuestProgressEngine engine;

    QuestSmeltListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExtract(FurnaceExtractEvent event) {
        if (!isFurnace(event.getBlock().getType())) {
            return;
        }
        int amount = event.getItemAmount();
        if (amount <= 0) {
            return;
        }
        engine.handleSmeltItem(event.getPlayer(), event.getItemType(), amount);
    }

    private static boolean isFurnace(Material type) {
        return type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER;
    }
}
