package com.lodygames.rpgquest.building.model;

import java.util.Optional;

/**
 * La zone de passage d'une arche de portail (issue #568, #228-P5) : le volume que le joueur
 * traverse, en coordonnées <strong>locales</strong> du schematic (rotation nulle), bornes incluses.
 *
 * <p>C'est le « contrat d'ancrages » demandé par #228 : posée à une ancre avec une rotation, l'arche
 * dit exactement où se trouve son passage dans le monde, sans que personne ait à le mesurer en jeu.
 * La transformation est la même que celle de {@link BuildingFootprint} — décalages relatifs à
 * l'ancre, tournés par {@link BuildingRotation}, replacés autour de l'ancre du monde — donc une
 * arche et son passage ne peuvent pas diverger après rotation.</p>
 *
 * <p>Record <strong>pur</strong> : ni Bukkit, ni WorldEdit.</p>
 */
public record PortalPassage(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public PortalPassage {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("zone de passage : les bornes min doivent être inférieures ou "
                    + "égales aux bornes max.");
        }
        if (minX < 0 || minY < 0 || minZ < 0) {
            throw new IllegalArgumentException("zone de passage : coordonnées locales négatives.");
        }
    }

    /** Le motif du refus si la zone sort d'un schematic de cette taille, sinon vide. */
    public Optional<String> checkInside(int sizeX, int sizeY, int sizeZ) {
        if (maxX >= sizeX || maxY >= sizeY || maxZ >= sizeZ) {
            return Optional.of("Zone de passage hors du bâtiment : " + label() + " dans " + sizeX + " × " + sizeZ
                    + " × " + sizeY + ".");
        }
        return Optional.empty();
    }

    /**
     * Le passage dans le monde, pour une définition posée à cette ancre avec cette rotation.
     *
     * @param degrees rotation réellement appliquée à la pose ({@code {0, 90, 180, 270}})
     */
    public BuildingFootprint inWorld(BuildingDefinition definition, String world,
                                     int anchorWorldX, int anchorWorldY, int anchorWorldZ, int degrees) {
        int lowDx = minX - definition.anchorX();
        int highDx = maxX - definition.anchorX();
        int lowDz = minZ - definition.anchorZ();
        int highDz = maxZ - definition.anchorZ();
        int[][] corners = {
            BuildingRotation.rotateOffset(lowDx, lowDz, degrees),
            BuildingRotation.rotateOffset(lowDx, highDz, degrees),
            BuildingRotation.rotateOffset(highDx, lowDz, degrees),
            BuildingRotation.rotateOffset(highDx, highDz, degrees),
        };
        int minDx = Integer.MAX_VALUE;
        int maxDx = Integer.MIN_VALUE;
        int minDz = Integer.MAX_VALUE;
        int maxDz = Integer.MIN_VALUE;
        for (int[] corner : corners) {
            minDx = Math.min(minDx, corner[0]);
            maxDx = Math.max(maxDx, corner[0]);
            minDz = Math.min(minDz, corner[1]);
            maxDz = Math.max(maxDz, corner[1]);
        }
        return new BuildingFootprint(world,
                anchorWorldX + minDx, anchorWorldY + minY - definition.anchorY(), anchorWorldZ + minDz,
                anchorWorldX + maxDx, anchorWorldY + maxY - definition.anchorY(), anchorWorldZ + maxDz);
    }

    /** Le passage d'un bâtiment réellement posé. */
    public BuildingFootprint inWorld(BuildingDefinition definition, BuildingPlacement placement) {
        return inWorld(definition, placement.world(), placement.anchorX(), placement.anchorY(),
                placement.anchorZ(), placement.rotationDegrees());
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** {@code x1..x2 / y1..y2 / z1..z2}, comme {@link BuildingFootprint#label()}. */
    public String label() {
        return minX + ".." + maxX + " / " + minY + ".." + maxY + " / " + minZ + ".." + maxZ;
    }
}
