package com.lodygames.rpgquest.panel.city;

import java.util.List;
import java.util.Locale;

/**
 * Une ville telle que la voit PlugAdmin (issue #178), projetée depuis le relevé {@code city.list}.
 * Les limites sont celles calculées par le plugin, jamais recalculées ici.
 */
public record CityView(String id, String name, String world, double x, double y, double z,
                       int centerX, int centerZ, int distance, boolean active, String createdAt,
                       boolean worldLoaded, int minX, int maxX, int minZ, int maxZ, int width,
                       int discoveries, List<String> overlaps) {

    public CityView {
        overlaps = List.copyOf(overlaps == null ? List.of() : overlaps);
    }

    /** La phrase demandée par le ticket, avec la distance réelle. */
    public String ruleSentence() {
        return "La ville s'étend de " + distance + " blocs dans chaque direction depuis son centre.";
    }

    public String sizeLabel() {
        return width + " × " + width + " blocs";
    }

    public String centerLabel() {
        return "X " + centerX + " · Z " + centerZ + " (Y " + (int) Math.floor(y) + ")";
    }

    public String boundsLabel() {
        return "X " + minX + " … " + maxX + " · Z " + minZ + " … " + maxZ;
    }

    public String createdAtLabel() {
        return createdAt.length() >= 16 ? createdAt.substring(0, 16).replace('T', ' ') + " UTC" : createdAt;
    }

    public String searchText() {
        return (name + " " + id + " " + world).toLowerCase(Locale.ROOT);
    }
}
