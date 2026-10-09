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
 * <p>Depuis #234 il y a <strong>deux</strong> plans, et la même technique sert aux deux : la tour de
 * garde passe par {@link #ensure} comme la hutte. Le ticket l'exigeait explicitement — pas de
 * seconde technique de génération, la frontière WorldEdit reste
 * {@code domaine → SchematicGateway → WorldEditSchematicGateway}.</p>
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
     * Écrit un schematic s'il manque. Ne touche <strong>jamais</strong> un fichier existant.
     *
     * @return vrai si le fichier existe désormais, faux si rien n'a pu être fait
     */
    public boolean ensure(String fileName, Blueprint blueprint) {
        if (gateway.has(fileName)) {
            return true;
        }
        if (!gateway.available()) {
            logger.info("[building] " + fileName + " non généré : " + gateway.unavailableReason());
            return false;
        }
        return regenerate(fileName, blueprint).ok();
    }

    /**
     * Réécrit un schematic, <strong>même si le fichier existe</strong>.
     *
     * <p>Geste explicite, déclenché par {@code /rpgadmin building generate}. Déterministe : le même
     * plan produit le même contenu, donc régénérer sans avoir touché au code ne change rien — et
     * c'est ce qui permet à l'empreinte du fichier de servir de version (issue #234).</p>
     */
    public SchematicGateway.Outcome regenerate(String fileName, Blueprint blueprint) {
        SchematicGateway.Outcome outcome = gateway.write(blueprint, fileName);
        if (outcome.ok()) {
            logger.info("[building] " + fileName + " écrit : "
                    + blueprint.sizeX() + " × " + blueprint.sizeZ() + " × " + blueprint.sizeY()
                    + ", " + blueprint.solidCount() + " blocs de matière.");
        } else {
            logger.warning("[building] écriture de " + fileName + " impossible : "
                    + outcome.error());
        }
        return outcome;
    }

    /** Écrit la hutte de test si elle manque. */
    public boolean ensureTestHut() {
        return ensure(TestHutBlueprint.SCHEMATIC, TestHutBlueprint.blueprint());
    }

    /** Réécrit la hutte de test, même si le fichier existe. */
    public SchematicGateway.Outcome regenerateTestHut() {
        return regenerate(TestHutBlueprint.SCHEMATIC, TestHutBlueprint.blueprint());
    }

    /** Écrit la tour de garde de test si elle manque (issue #234). */
    public boolean ensureTestWatchtower() {
        return ensure(TestWatchtowerBlueprint.SCHEMATIC, TestWatchtowerBlueprint.blueprint());
    }

    /** Réécrit la tour de garde de test, même si le fichier existe (issue #234). */
    public SchematicGateway.Outcome regenerateTestWatchtower() {
        return regenerate(TestWatchtowerBlueprint.SCHEMATIC,
                TestWatchtowerBlueprint.blueprint());
    }

    /**
     * Écrit tous les schematics dont RPGQuest possède le plan et qui manquent.
     *
     * <p>Appelé au démarrage. Renvoie le nombre de fichiers désormais disponibles, pour que le
     * démarrage puisse le dire au lieu de le supposer.</p>
     */
    public int ensureAll() {
        int available = 0;
        if (ensureTestHut()) {
            available++;
        }
        if (ensureTestWatchtower()) {
            available++;
        }
        return available;
    }
}
