package com.lodygames.rpgquest.building.model;

import java.time.Instant;

/**
 * Un fragment du <strong>terrain d'origine</strong> d'un emplacement (issue #234).
 *
 * <h2>Baseline originale ≠ état d'avant la dernière opération</h2>
 *
 * <p>C'est la distinction centrale du ticket. Chaque pose prend déjà une sauvegarde de ce qu'elle
 * écrase ({@link BuildingPlacement#backupSchematic()}), et cette sauvegarde sert à <em>compenser</em>
 * une opération qui échoue en cours de route. Mais après hutte → tour → autre orientation, cette
 * sauvegarde ne contient plus le terrain d'origine : elle contient le bâtiment précédent.</p>
 *
 * <p>La baseline est donc conservée <strong>à part</strong>, et n'est jamais réécrite tant que
 * l'emplacement reste dans une chaîne d'expérimentation. « Restaurer le terrain original » rend
 * l'état d'avant le <em>premier</em> bâtiment, quel que soit le nombre d'essais entre-temps.</p>
 *
 * <h2>Pourquoi plusieurs fragments et non un seul</h2>
 *
 * <p>Une tour de 9 × 14 occupe plus de place qu'une hutte de 7 × 6. Restaurer une baseline prise sur
 * l'emprise de la hutte laisserait des blocs de tour <em>en dehors</em> de cette emprise — et le
 * terrain ne serait pas « d'origine », il serait partiellement d'origine, ce qui est pire qu'un
 * refus parce que ça en a l'air.</p>
 *
 * <p>Un fragment est donc capturé pour chaque emprise que l'on s'apprête à toucher pour la première
 * fois. L'ordre des opérations garantit que <strong>chaque fragment contient réellement du terrain
 * d'origine</strong> : avant toute nouvelle emprise, l'ancienne est d'abord restaurée depuis sa
 * baseline, donc la zone est vierge au moment où on la capture. C'est pour cette raison que l'ordre
 * de restauration des fragments n'a pas d'importance — et qu'on le fixe quand même, du plus ancien
 * au plus récent, pour que deux restaurations successives produisent exactement le même monde.</p>
 *
 * @param id         identifiant de ligne, {@code 0} pour un fragment pas encore écrit
 * @param schematic  nom du fichier de sauvegarde ; l'adaptateur sait quoi en faire, pas le domaine
 */
public record BuildingBaseline(long id,
                               String siteId,
                               String schematic,
                               String world,
                               int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ,
                               String capturedBy,
                               Instant capturedAt) {

    public BuildingBaseline {
        capturedBy = capturedBy == null ? "" : capturedBy;
    }

    /** Fragment pas encore persisté, pour l'emprise donnée. */
    public static BuildingBaseline of(String siteId, String schematic, BuildingFootprint footprint,
                                      String capturedBy, Instant capturedAt) {
        return new BuildingBaseline(0L, siteId, schematic, footprint.world(),
                footprint.minX(), footprint.minY(), footprint.minZ(),
                footprint.maxX(), footprint.maxY(), footprint.maxZ(),
                capturedBy, capturedAt);
    }

    public BuildingFootprint footprint() {
        return new BuildingFootprint(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Vrai si cette emprise est entièrement contenue dans ce fragment. */
    public boolean covers(BuildingFootprint other) {
        return other != null
                && world.equals(other.world())
                && minX <= other.minX() && other.maxX() <= maxX
                && minY <= other.minY() && other.maxY() <= maxY
                && minZ <= other.minZ() && other.maxZ() <= maxZ;
    }
}
