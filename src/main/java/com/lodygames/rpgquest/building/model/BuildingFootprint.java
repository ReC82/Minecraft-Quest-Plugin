package com.lodygames.rpgquest.building.model;

/**
 * L'emprise d'un bâtiment dans un monde : deux coins de blocs, inclus (issue #213, lot
 * « placement »).
 *
 * <h2>Pourquoi une emprise calculée avant tout collage</h2>
 *
 * <p>C'est ce qui permet d'annoncer à l'administrateur <em>exactement</em> ce qui va être écrasé,
 * de détecter un chevauchement avec un bâtiment déjà posé, et de refuser une emprise qui sortirait
 * des limites du monde — tout cela <strong>sans toucher un seul bloc</strong>. Le collage n'est
 * alors plus qu'une exécution.</p>
 *
 * <p>Record <strong>pur</strong> : aucun type WorldEdit ni Bukkit. Une emprise est de l'arithmétique
 * sur des entiers, et c'est tout ce qu'elle doit être.</p>
 */
public record BuildingFootprint(String world,
                                int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ) {

    public BuildingFootprint {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("monde obligatoire");
        }
        // Les coins sont normalisés à la construction : un appelant qui les fournit à l'envers
        // obtient une emprise correcte plutôt qu'une emprise vide calculée en silence.
        int lowX = Math.min(minX, maxX);
        int lowY = Math.min(minY, maxY);
        int lowZ = Math.min(minZ, maxZ);
        int highX = Math.max(minX, maxX);
        int highY = Math.max(minY, maxY);
        int highZ = Math.max(minZ, maxZ);
        minX = lowX;
        minY = lowY;
        minZ = lowZ;
        maxX = highX;
        maxY = highY;
        maxZ = highZ;
    }

    /**
     * L'emprise d'une définition posée à une ancre, avec une rotation.
     *
     * <p>Le raisonnement, en une phrase : on exprime les coins du schematic en <em>décalages
     * relatifs à l'ancre</em>, on fait tourner ces décalages, puis on les replace autour de l'ancre
     * du monde. L'ancre reste donc un point fixe de la transformation, ce qui est précisément ce
     * qu'on veut d'une ancre.</p>
     */
    public static BuildingFootprint of(BuildingDefinition definition, String world,
                                       int anchorX, int anchorY, int anchorZ, int degrees) {
        int lowDx = -definition.anchorX();
        int highDx = definition.sizeX() - 1 - definition.anchorX();
        int lowDz = -definition.anchorZ();
        int highDz = definition.sizeZ() - 1 - definition.anchorZ();

        // Les quatre coins horizontaux : à 90° et 270°, ce sont les décalages en Z qui deviennent
        // l'étendue en X. Faire tourner les quatre coins plutôt que « échanger les dimensions »
        // évite d'avoir à traiter séparément le cas où l'ancre n'est pas au centre — et elle ne
        // l'est jamais, puisque c'est la porte.
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
                anchorX + minDx, anchorY - definition.anchorY(), anchorZ + minDz,
                anchorX + maxDx, anchorY + definition.sizeY() - 1 - definition.anchorY(),
                anchorZ + maxDz);
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

    /** Nombre de blocs de l'emprise — ce qu'on annonce avant d'écraser quoi que ce soit. */
    public long blockCount() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(String otherWorld, int x, int y, int z) {
        return world.equals(otherWorld)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * Vrai si les deux emprises partagent au moins un bloc.
     *
     * <p>Deux mondes différents ne se chevauchent jamais, même à coordonnées identiques — c'est
     * l'erreur qu'un test de chevauchement naïf commet, et elle refuserait un placement légitime.
     * </p>
     */
    public boolean overlaps(BuildingFootprint other) {
        return other != null
                && world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /** Libellé compact pour les écrans et les messages : {@code x1..x2 / y1..y2 / z1..z2}. */
    public String label() {
        return minX + ".." + maxX + " / " + minY + ".." + maxY + " / " + minZ + ".." + maxZ;
    }
}
