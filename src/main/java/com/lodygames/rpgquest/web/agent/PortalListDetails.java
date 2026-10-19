package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.travel.PortalOverview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection JSON de {@code portal.list} (issue #564) : le contrat lu par la page « Portails » de
 * PlugAdmin. {@code destinationLoaded = null} signifie « monde d'arrivée inconnu ».
 */
final class PortalListDetails {

    private PortalListDetails() {
    }

    static Map<String, Object> of(PortalOverview.View view) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("hub", view.roles().hub());
        roles.put("wild", view.roles().wild());
        roles.put("claims", view.roles().claims());
        details.put("roles", roles);
        List<Map<String, Object>> portals = new ArrayList<>();
        for (PortalOverview.Entry e : view.portals()) {
            portals.add(entry(e));
        }
        details.put("portals", portals);
        details.put("issues", issues(view.issues()));
        return details;
    }

    private static Map<String, Object> entry(PortalOverview.Entry e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.id());
        row.put("name", e.name());
        row.put("kind", e.kind().name());
        row.put("enabled", e.enabled());
        row.put("file", e.file());
        row.put("world", e.world());
        row.put("worldLoaded", e.worldLoaded());
        row.put("worldRole", e.worldRole());
        row.put("min", e.minX() + ", " + e.minY() + ", " + e.minZ());
        row.put("max", e.maxX() + ", " + e.maxY() + ", " + e.maxZ());
        row.put("size", e.size());
        row.put("destinationWorld", e.destinationWorld());
        row.put("destinationLoaded", e.destinationLoaded());
        row.put("destinationRole", e.destinationRole());
        row.put("destinationId", e.destinationId());
        row.put("destinationPoint", e.destinationPoint());
        row.put("strategy", e.strategy());
        row.put("arrival", e.arrival());
        row.put("trigger", e.trigger());
        List<Map<String, Object>> guards = new ArrayList<>();
        for (PortalOverview.Guard g : e.guards()) {
            Map<String, Object> guard = new LinkedHashMap<>();
            guard.put("code", g.code());
            guard.put("title", g.title());
            guard.put("rules", g.rules());
            List<Map<String, Object>> texts = new ArrayList<>();
            for (PortalOverview.Text t : g.texts()) {
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("label", t.label());
                text.put("value", t.value());
                texts.add(text);
            }
            guard.put("texts", texts);
            guards.add(guard);
        }
        row.put("guards", guards);
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (PortalOverview.Condition c : e.conditions()) {
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("label", c.label());
            condition.put("value", c.value());
            conditions.add(condition);
        }
        row.put("conditions", conditions);
        row.put("issues", issues(e.issues()));
        return row;
    }

    private static List<Map<String, Object>> issues(List<PortalOverview.Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PortalOverview.Issue i : issues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", i.code());
            row.put("severity", i.severity());
            row.put("message", i.message());
            out.add(row);
        }
        return out;
    }
}
