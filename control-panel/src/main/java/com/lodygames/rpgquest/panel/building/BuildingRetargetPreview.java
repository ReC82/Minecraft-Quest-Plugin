package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * L'aperçu d'une réorientation ou d'un remplacement, tel que le panel le lit (issue #234).
 *
 * <p>Résultat d'un {@code building.placement.retarget.preview}, qui <strong>n'écrit rien</strong> :
 * ni bloc, ni ligne en base. C'est ce que l'administrateur compare avant de confirmer — l'ancienne
 * emprise et la nouvelle, côte à côte.</p>
 *
 * <p>Le {@code token} est la pièce de sûreté : il résume l'emplacement, le bâtiment posé et la
 * définition visée. Renvoyé avec la confirmation, il fait <strong>refuser</strong> une décision
 * prise sur un aperçu périmé — site modifié, bâtiment remplacé entre-temps, définition rechargée.</p>
 *
 * @param targetNonAirBlocks blocs non-air déjà présents dans la nouvelle emprise, ou {@code -1} si
 *                           le comptage n'a pas pu être fait. {@code -1} n'est pas {@code 0} :
 *                           les confondre ferait annoncer « zone libre » sans l'avoir vérifié
 */
public record BuildingRetargetPreview(boolean applicable, String operation,
                                      String siteId, String siteFacing,
                                      String currentBuildingId, String currentBuildingName,
                                      int currentRotation, String currentFootprint,
                                      long currentBlockCount,
                                      String targetBuildingId, String targetBuildingName,
                                      int targetRotation, String targetFootprint,
                                      long targetBlockCount, String targetSize,
                                      long targetNonAirBlocks, boolean overlapping,
                                      String restoreSource, boolean restorable,
                                      List<String> refusals, List<String> warnings,
                                      String token, String requestedFacing, boolean available) {

    public BuildingRetargetPreview {
        refusals = List.copyOf(refusals == null ? List.of() : refusals);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /** Aucun aperçu encore calculé. Volontairement distinct d'un aperçu refusé. */
    public static BuildingRetargetPreview unavailable() {
        return new BuildingRetargetPreview(false, "", "", "", "", "", 0, "", 0L,
                "", "", 0, "", 0L, "", -1L, false, "", false, List.of(), List.of(), "", "", false);
    }

    public static BuildingRetargetPreview from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        return new BuildingRetargetPreview(
                bool(details.get("applicable")), str(details.get("operation")),
                str(details.get("site_id")), str(details.get("site_facing")),
                str(details.get("current_building_id")), str(details.get("current_building_name")),
                intOr(details.get("current_rotation")), str(details.get("current_footprint")),
                longOr(details.get("current_block_count")),
                str(details.get("target_building_id")), str(details.get("target_building_name")),
                intOr(details.get("target_rotation")), str(details.get("target_footprint")),
                longOr(details.get("target_block_count")), str(details.get("target_size")),
                details.get("target_non_air") == null ? -1L : longOr(details.get("target_non_air")),
                bool(details.get("overlapping")),
                str(details.get("restore_source")), bool(details.get("restorable")),
                strings(details.get("refusals")), strings(details.get("warnings")),
                str(details.get("token")), str(details.get("requested_facing")), true);
    }

    /** Vrai si cet aperçu porte bien sur cet emplacement : un aperçu ne vaut que pour le sien. */
    public boolean concerns(String otherSiteId) {
        return available && siteId != null && !siteId.isEmpty() && siteId.equals(otherSiteId);
    }

    /** Vrai si c'est un remplacement et non une simple réorientation. */
    public boolean replacement() {
        return "REPLACE".equals(operation);
    }

    public String operationLabel() {
        return replacement() ? "Remplacement" : "Réorientation";
    }

    public String firstRefusal() {
        return refusals.isEmpty() ? "" : refusals.get(0);
    }

    /**
     * Le nombre de blocs non-air, en clair.
     *
     * <p>« inconnu » et « 0 » sont deux réponses différentes, et l'écran doit les distinguer :
     * afficher « 0 » quand on n'a pas pu compter annoncerait une zone libre sans l'avoir regardée.</p>
     */
    public String nonAirLabel() {
        return targetNonAirBlocks < 0 ? "inconnu (monde ou chunk déchargé)"
                : String.valueOf(targetNonAirBlocks);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(str(value));
    }

    private static int intOr(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(str(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longOr(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(str(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = str(item);
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        }
        return out;
    }
}
