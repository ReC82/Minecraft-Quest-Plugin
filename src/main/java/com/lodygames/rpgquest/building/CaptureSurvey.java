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
 * <p>Il sert à <strong>dire avant de faire</strong> : combien de conteneurs seront capturés vides,
 * quelles entités (cadres, tableaux, supports d'armure…) ne seront pas dans le fichier. Un bâtiment
 * décoré dont la moitié du mobilier est faite d'entités ne doit pas être annoncé « capturé tel
 * quel ».</p>
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

    public int entityCount() {
        return entities.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Un bloc à contenu (coffre, tonneau, four, pupitre, juke-box…), en coordonnées du monde.
     *
     * @param filled vrai s'il contenait quelque chose au moment du relevé
     */
    public record ContainerSpot(int x, int y, int z, String type, boolean filled) {
    }
}
