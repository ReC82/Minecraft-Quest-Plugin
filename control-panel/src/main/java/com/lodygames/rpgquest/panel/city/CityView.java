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
                       int discoveries, List<String> overlaps, Anchors anchors) {

    /**
     * Issue #322 — PNJ central et point d'arrivée, tels que relus par le plugin. Les positions sont
     * {@code null} quand le plugin n'en connaît aucune : le panel n'en invente jamais.
     */
    public record Anchors(String centralNpcId, String npcDisplayName, String npcStatus, String npcSeverity,
                          String npcMessage, String npcWorld, Double npcX, Double npcY, Double npcZ,
                          boolean npcLive, int npcDistance, String arrivalWorld, Double arrivalX,
                          Double arrivalY, Double arrivalZ, Double arrivalYaw, Double arrivalPitch,
                          String arrivalStatus, String arrivalSeverity, String arrivalMessage) {

        /** Relevé d'un plugin antérieur à #322 : aucun PNJ, arrivée au centre. */
        public static Anchors none() {
            return new Anchors("", "", "NONE", "INFO", "", "", null, null, null, false, -1, "", null, null,
                    null, null, null, "CENTER", "INFO", "");
        }

        public boolean hasCentralNpc() {
            return centralNpcId != null && !centralNpcId.isEmpty();
        }

        public boolean hasArrival() {
            return arrivalWorld != null && !arrivalWorld.isEmpty() && arrivalX != null && arrivalZ != null;
        }

        public String npcLabel() {
            if (!hasCentralNpc()) {
                return "Aucun";
            }
            return npcDisplayName == null || npcDisplayName.isEmpty() || npcDisplayName.equals(centralNpcId)
                    ? centralNpcId : npcDisplayName + " (" + centralNpcId + ")";
        }

        /** Position relue du PNJ, ou vide si inconnue. */
        public String npcPositionLabel() {
            if (npcX == null || npcZ == null) {
                return "";
            }
            return npcWorld + " · X " + (int) Math.floor(npcX) + " · Y " + (int) Math.floor(npcY == null ? 0 : npcY)
                    + " · Z " + (int) Math.floor(npcZ) + (npcLive ? "" : " (dernière position enregistrée)");
        }

        public String arrivalLabel() {
            if (!hasArrival()) {
                return "Centre de la ville (par défaut)";
            }
            return arrivalWorld + " · X " + (int) Math.floor(arrivalX) + " · Y "
                    + (int) Math.floor(arrivalY == null ? 0 : arrivalY) + " · Z " + (int) Math.floor(arrivalZ)
                    + " · regard " + Math.round(arrivalYaw == null ? 0 : arrivalYaw) + "°";
        }
    }

    public CityView {
        overlaps = List.copyOf(overlaps == null ? List.of() : overlaps);
        anchors = anchors == null ? Anchors.none() : anchors;
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
        return (name + " " + id + " " + world + " " + anchors.centralNpcId() + " " + anchors.npcDisplayName())
                .toLowerCase(Locale.ROOT);
    }
}
