package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import java.util.Optional;

/**
 * La frontière entre RPGQuest et le moteur de schematics (issue #213, lot « placement »).
 *
 * <h2>Pourquoi cette interface existe</h2>
 *
 * <p>WorldEdit est une dépendance d'<strong>infrastructure remplaçable</strong>, jamais une
 * dépendance du domaine. Aucun type WorldEdit ne traverse cette interface : on y parle de plans, de
 * noms de fichiers, d'emprises et de degrés — c'est-à-dire du vocabulaire de RPGQuest. Les modèles
 * {@code BuildingSite}, {@code BuildingDefinition} et {@code BuildingPlacement} n'en connaissent
 * même pas l'existence.</p>
 *
 * <p>Conséquence concrète, et c'est tout l'intérêt : <strong>toute la logique de ce lot est
 * testable sans WorldEdit sur le chemin de classe</strong>. La rotation, l'emprise, le refus d'un
 * site déjà occupé, l'anti-double-clic, l'enchaînement collage → placement enregistré : tout cela
 * se vérifie contre une implémentation de test. Ce qui reste derrière l'interface est mince, et
 * c'est précisément la part qu'aucun test ne peut honnêtement couvrir ici.</p>
 *
 * <h2>Indisponibilité : un refus nommé, jamais un échec de démarrage</h2>
 *
 * <p>Comme le pont Citizens et le pont LuckPerms, l'implémentation réelle se déclare
 * <strong>indisponible avec son motif</strong> si le moteur n'est pas installé ou ne correspond
 * pas. Le plugin démarre normalement ; seule la pose de bâtiments est refusée, en le disant.</p>
 */
public interface SchematicGateway {

    /** Vrai si le moteur est présent et exploitable. */
    boolean available();

    /** Le motif de l'indisponibilité, destiné à l'écran. Vide si {@link #available()}. */
    String unavailableReason();

    /** Vrai si le fichier existe dans le dossier des schematics. */
    boolean has(String fileName);

    /**
     * Écrit un plan dans un fichier {@code .schem}, avec l'écrivain officiel du moteur.
     *
     * <p>Aucun bloc du monde n'est lu ni touché : le plan est construit en mémoire. C'est la
     * méthode qui produit la hutte de test.</p>
     */
    Outcome write(Blueprint blueprint, String fileName);

    /**
     * Les dimensions réelles d'un fichier, pour les confronter à la définition déclarée.
     *
     * <p>Vide si le fichier est absent ou illisible. Une divergence entre le fichier et la
     * définition ne doit pas se découvrir après le collage.</p>
     */
    Optional<Dimensions> inspect(String fileName);

    /**
     * Copie la zone du monde correspondant à l'emprise dans un fichier de sauvegarde.
     *
     * <p>C'est ce qui rend le retour arrière possible : on sauvegarde ce qu'on va écraser,
     * <strong>avant</strong> de l'écraser.</p>
     */
    Outcome capture(BuildingFootprint footprint, String fileName);

    /**
     * Colle un schematic, après rotation, de sorte que l'ancre locale tombe exactement sur l'ancre
     * du monde.
     *
     * <p>L'implémentation doit <strong>vérifier que la région réellement transformée par le moteur
     * coïncide avec l'emprise attendue</strong> et refuser sinon. Le sens de rotation d'une
     * bibliothèque tierce est une convention qu'on ne peut pas déduire par le raisonnement : plutôt
     * que de parier, on compare et on refuse en cas de désaccord.</p>
     */
    Outcome paste(PasteOrder order);

    /**
     * Restaure une sauvegarde à l'emplacement exact d'où elle a été prise.
     *
     * <p>Aucune rotation : une sauvegarde se repose telle quelle, sinon ce n'est pas une
     * restauration.</p>
     */
    Outcome restore(String fileName, BuildingFootprint footprint);

    /** Dimensions d'un fichier, en blocs. */
    record Dimensions(int sizeX, int sizeY, int sizeZ) {

        public String label() {
            return sizeX + " × " + sizeZ + " × " + sizeY;
        }
    }

    /**
     * Un ordre de collage, entièrement exprimé en types RPGQuest.
     *
     * @param expected l'emprise calculée par le domaine — l'implémentation doit s'y conformer ou
     *                 refuser
     */
    record PasteOrder(String schematic,
                      String world,
                      int anchorX, int anchorY, int anchorZ,
                      int localAnchorX, int localAnchorY, int localAnchorZ,
                      int rotationDegrees,
                      BuildingFootprint expected) {
    }

    /** Résultat d'une opération : réussie, ou refusée avec un motif en français. */
    record Outcome(boolean ok, String error) {

        public static Outcome success() {
            return new Outcome(true, "");
        }

        public static Outcome failure(String error) {
            return new Outcome(false, error == null ? "Échec non précisé." : error);
        }
    }
}
