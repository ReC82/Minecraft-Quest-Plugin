package com.lodygames.rpgquest.building;

import java.util.List;
import java.util.Map;

/**
 * Ce que contient réellement une zone avant de la capturer comme bâtiment (issue #246).
 *
 * <p>Relevé en <strong>lecture seule</strong>, sur le thread principal, sans forcer le chargement
 * d'aucun chunk : si une partie de la zone n'est pas chargée, le relevé le dit ({@link #complete()}
 * faux) et la capture est refusée plutôt que de copier du vide à la place des blocs absents.</p>
 *
 * <p>Il sert à <strong>dire avant de faire</strong> : quels blocs à contenu gardent leur aspect,
 * lesquels perdent les objets qu'ils affichent (issue #903), quelles entités (cadres, tableaux,
 * supports d'armure…) ne seront pas dans le fichier.</p>
 *
 * @param complete          faux si un chunk de la zone n'est pas chargé
 * @param incompleteReason  le motif, lisible, quand {@code complete} est faux
 * @param nonAirBlocks      blocs non-air de la zone
 * @param containers        les blocs dont le contenu ne sera PAS exporté
 * @param entities          entités présentes, par type, qui ne seront pas capturées
 * @param materials         matériaux les plus présents, du plus fréquent au moins fréquent
 */
public record CaptureSurvey(boolean complete,
                            String incompleteReason,
                            long nonAirBlocks,
                            List<ContainerSpot> containers,
                            Map<String, Integer> entities,
                            List<String> materials) {

    public CaptureSurvey {
        incompleteReason = incompleteReason == null ? "" : incompleteReason;
        containers = containers == null ? List.of() : List.copyOf(containers);
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    /** Une zone dont une partie n'est pas chargée : rien n'est compté, et c'est dit. */
    public static CaptureSurvey incomplete(String reason) {
        return new CaptureSurvey(false, reason, 0L, List.of(), Map.of(), List.of());
    }

    /** Conteneurs dont le contenu n'était pas vide : ce sont eux qu'on signale en priorité. */
    public long filledContainers() {
        return containers.stream().filter(ContainerSpot::filled).count();
    }

    /** Blocs dont un élément visible disparaîtra de la variante : exige un acquittement (#903). */
    public long visualLosses() {
        return count(ContentFate.VISUAL_LOST);
    }

    public long count(ContentFate fate) {
        return containers.stream().filter(spot -> spot.fate() == fate).count();
    }

    public int entityCount() {
        return entities.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Ce que devient, dans la variante, ce que le bloc contient (issue #903). La capture copie
     * toujours le bloc avec toutes ses propriétés (orientation, emplacements de livres occupés…) ;
     * seules les <em>données</em> du bloc — objets, livres, textes — sont retirées.
     */
    public enum ContentFate {
        /** Rien dedans : la variante est identique. */
        EMPTY,
        /** Contenu invisible de l'extérieur (coffre, tonneau…) : non copié, rien ne change à l'œil. */
        HIDDEN_DROPPED,
        /**
         * Livres visibles portés par l'état du bloc (bibliothèque sculptée, pupitre) : ils restent
         * affichés comme décor, mais les livres eux-mêmes ne sont pas copiés — on ne peut pas les
         * prendre dans la variante posée.
         */
        VISUAL_KEPT,
        /**
         * Objets affichés portés par les données du bloc (étagère, feu de camp, motifs d'un pot
         * décoré) : ils disparaissent de la variante.
         */
        VISUAL_LOST
    }

    /**
     * Un bloc à contenu (coffre, tonneau, four, pupitre, juke-box…), en coordonnées du monde.
     *
     * @param type  identifiant Minecraft du bloc, sans espace de noms
     * @param fate  ce que devient son contenu dans la variante
     */
    public record ContainerSpot(int x, int y, int z, String type, ContentFate fate) {

        public ContainerSpot {
            fate = fate == null ? ContentFate.EMPTY : fate;
        }

        /** Vrai s'il contenait quelque chose au moment du relevé. */
        public boolean filled() {
            return fate != ContentFate.EMPTY;
        }
    }
}
