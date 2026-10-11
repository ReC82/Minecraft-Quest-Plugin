package com.lodygames.rpgquest.building.plan;

/**
 * Une zone rectangulaire d'un plan, en coordonnées locales, bornes <strong>incluses</strong>
 * (issue #94).
 *
 * <p>L'ordre des deux coins est indifférent : le constructeur normalise. C'est une petite
 * commodité, mais elle supprime une classe entière d'erreurs de génération — un modèle qui écrit
 * {@code from: [10,0,0]} et {@code to: [2,0,0]} décrit la même intention, et la refuser pour un
 * ordre d'écriture serait du formalisme pur.</p>
 *
 * <p>Bornes incluses, et c'est dit ici parce que c'est la convention que le prompt annonce au
 * modèle : {@code from: [0,0,0], to: [0,0,0]} vaut exactement un bloc. Une borne exclusive rendrait
 * tous les murs d'épaisseur nulle quand les deux coins coïncident.</p>
 */
public record PlanBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public PlanBox {
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

    public static PlanBox of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new PlanBox(x1, y1, z1, x2, y2, z2);
    }

    /** Un seul bloc. */
    public static PlanBox at(int x, int y, int z) {
        return new PlanBox(x, y, z, x, y, z);
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

    /** Nombre de cellules couvertes — sert à borner le coût d'une expansion avant de la faire. */
    public long cells() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** La zone tient-elle entièrement dans un volume {@code sx × sy × sz} ancré en 0 ? */
    public boolean within(int sx, int sy, int sz) {
        return minX >= 0 && minY >= 0 && minZ >= 0 && maxX < sx && maxY < sy && maxZ < sz;
    }

    /** Est-ce une face ou une arête de la zone — c'est-à-dire l'enveloppe ? */
    public boolean onShell(int x, int y, int z) {
        return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
    }

    /** Est-ce une paroi verticale (les quatre murs, sans sol ni plafond) ? */
    public boolean onWall(int x, int y, int z) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }

    public String label() {
        return minX + "/" + minY + "/" + minZ + " → " + maxX + "/" + maxY + "/" + maxZ;
    }
}
