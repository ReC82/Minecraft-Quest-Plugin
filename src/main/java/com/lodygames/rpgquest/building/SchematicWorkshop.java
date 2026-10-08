package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.Blueprint;
import java.util.logging.Logger;

/**
 * Produit les fichiers {@code .schem} des bâtiments dont RPGQuest possède le plan (issue #213, lot
 * « placement »).
 *
 * <h2>Pourquoi générer plutôt qu'importer</h2>
 *
 * <p>La consigne de ce lot était explicite : <strong>aucun fichier externe</strong>. La hutte de
 * test est donc décrite par {@link TestHutBlueprint}, c'est-à-dire par du code — relisible,
 * testable, et qui ne peut pas mentir sur ses dimensions puisque la définition les lit sur le plan.
 * Le {@code .schem} n'est qu'un artefact dérivé, écrit par l'écrivain officiel du moteur.</p>
 *
 * <p>Un blob gzip de quelques kilo-octets déposé dans le dépôt aurait eu l'air plus simple, mais
 * personne n'aurait pu le relire en revue, ni vérifier qu'il mesure bien 7 × 5 × 6.</p>
 *
 * <h2>On n'écrase jamais un fichier existant</h2>
 *
 * <p>{@link #ensureTestHut()} ne fait rien si le fichier est déjà là. C'est la règle du projet pour
 * tout contenu déposé au démarrage, et elle a une raison concrète : un administrateur peut avoir
 * retouché la hutte dans WorldEdit et réexporté par-dessus. La régénération est un geste explicite,
 * jamais un effet de bord d'un redémarrage.</p>
 */
public final class SchematicWorkshop {

    private final SchematicGateway gateway;
    private final Logger logger;

    public SchematicWorkshop(SchematicGateway gateway, Logger logger) {
        this.gateway = gateway;
        this.logger = logger;
    }

    /**
     * Écrit la hutte de test si elle manque.
     *
     * @return vrai si le fichier existe désormais, faux si rien n'a pu être fait
     */
    public boolean ensureTestHut() {
        if (gateway.has(TestHutBlueprint.SCHEMATIC)) {
            return true;
        }
        if (!gateway.available()) {
            logger.info("[building] hutte de test non générée : " + gateway.unavailableReason());
            return false;
        }
        return regenerateTestHut().ok();
    }

    /**
     * Réécrit la hutte de test, <strong>même si le fichier existe</strong>.
     *
     * <p>Geste explicite, déclenché par {@code /rpgadmin building generate}. Déterministe : le même
     * plan produit le même contenu, donc régénérer sans avoir touché au code ne change rien.</p>
     */
    public SchematicGateway.Outcome regenerateTestHut() {
        Blueprint blueprint = TestHutBlueprint.blueprint();
        SchematicGateway.Outcome outcome = gateway.write(blueprint, TestHutBlueprint.SCHEMATIC);
        if (outcome.ok()) {
            logger.info("[building] " + TestHutBlueprint.SCHEMATIC + " écrit : "
                    + blueprint.sizeX() + " × " + blueprint.sizeZ() + " × " + blueprint.sizeY()
                    + ", " + blueprint.solidCount() + " blocs de matière.");
        } else {
            logger.warning("[building] écriture de " + TestHutBlueprint.SCHEMATIC
                    + " impossible : " + outcome.error());
        }
        return outcome;
    }
}
