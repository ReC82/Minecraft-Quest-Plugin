package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.claim.ClaimOverview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection JSON de {@code claim.list} (issue #637) : le contrat lu par la page « Claims » et la
 * fiche joueur de PlugAdmin. {@code ownerName = null} = pseudo inconnu du serveur ; {@code tier =
 * null} = forme libre, sans palier.
 */
final class ClaimListDetails {

    private ClaimListDetails() {
    }

    static Map<String, Object> of(ClaimOverview.View view) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("claimsWorld", view.claimsWorld());
        details.put("owners", view.owners());
        List<Map<String, Object>> claims = new ArrayList<>();
        for (ClaimOverview.Entry e : view.claims()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("owner", e.owner().toString());
            row.put("ownerName", e.ownerName());
            row.put("main", e.main());
            row.put("world", e.world());
            row.put("worldLoaded", e.worldLoaded());
            row.put("inClaimsWorld", e.inClaimsWorld());
            row.put("min", e.minX() + ", " + e.minY() + ", " + e.minZ());
            row.put("max", e.maxX() + ", " + e.maxY() + ", " + e.maxZ());
            row.put("reservedMin", e.reservedMinX() + ", " + e.reservedMinZ());
            row.put("reservedMax", e.reservedMaxX() + ", " + e.reservedMaxZ());
            row.put("width", e.width());
            row.put("depth", e.depth());
            row.put("height", e.height());
            row.put("center", e.centerX() + ", " + e.centerZ());
            row.put("tier", e.tier());
            row.put("members", e.members());
            row.put("publicRedstone", e.publicRedstone());
            claims.add(row);
        }
        details.put("claims", claims);
        return details;
    }
}
