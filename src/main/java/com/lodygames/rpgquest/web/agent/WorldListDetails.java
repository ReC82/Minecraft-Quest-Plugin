package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.world.WorldOverview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection JSON de {@code world.list} (issue #560) : le contrat lu par la page « Mondes » de
 * PlugAdmin. Une valeur {@code null} signifie « non lisible » (affichée « inconnu »).
 */
final class WorldListDetails {

    private WorldListDetails() {
    }

    static Map<String, Object> of(WorldOverview.View view) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mainWorld", view.mainWorld());
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("hub", view.roles().hub());
        roles.put("wild", view.roles().wild());
        roles.put("claims", view.roles().claims());
        details.put("roles", roles);
        Map<String, Object> mv = new LinkedHashMap<>();
        mv.put("available", view.multiverse().available());
        mv.put("version", view.multiverse().version());
        mv.put("reason", view.multiverse().reason());
        details.put("multiverse", mv);
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (WorldOverview.Entry e : view.worlds()) {
            worlds.add(entry(e));
        }
        details.put("worlds", worlds);
        details.put("onlinePlayers", view.onlinePlayers());
        details.put("issues", issues(view.issues()));
        return details;
    }

    private static Map<String, Object> entry(WorldOverview.Entry e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", e.name());
        row.put("alias", e.alias());
        row.put("loaded", e.loaded());
        row.put("manager", e.manager().name());
        row.put("main", e.main());
        row.put("essential", e.essential());
        row.put("roles", e.roles());
        row.put("multiverseKnown", e.multiverseKnown());
        row.put("rpgquestManaged", e.rpgquestManaged());
        row.put("environment", e.environment());
        row.put("worldType", e.worldType());
        // Graine en texte : un long dépasse la précision d'un nombre JSON côté navigateur.
        row.put("seed", e.seed() == null ? null : String.valueOf(e.seed()));
        row.put("structures", e.structures());
        row.put("players", e.players());
        row.put("difficulty", e.difficulty());
        row.put("pvp", e.pvp());
        row.put("hardcore", e.hardcore());
        row.put("spawn", e.spawn());
        row.put("generator", e.generator());
        row.put("generatorSettings", e.generatorSettings());
        row.put("autoLoad", e.autoLoad());
        WorldOverview.Links l = e.links();
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("portalsFrom", portals(l.portalsFrom()));
        links.put("portalsTo", portals(l.portalsTo()));
        links.put("sites", places(l.sites()));
        links.put("waypoints", l.waypoints());
        links.put("autoWaypoints", l.autoWaypoints());
        List<Map<String, Object>> mobs = new ArrayList<>();
        for (WorldOverview.MobLink m : l.mobs()) {
            Map<String, Object> mob = new LinkedHashMap<>();
            mob.put("id", m.id());
            mob.put("name", m.name());
            mobs.add(mob);
        }
        links.put("mobs", mobs);
        links.put("npcSpots", places(l.npcSpots()));
        links.put("cities", places(l.cities()));
        row.put("links", links);
        row.put("issues", issues(e.issues()));
        return row;
    }

    private static List<Map<String, Object>> portals(List<WorldOverview.PortalLink> portals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldOverview.PortalLink p : portals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("world", p.world());
            row.put("destinationWorld", p.destinationWorld());
            row.put("enabled", p.enabled());
            row.put("strategy", p.strategy());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> places(List<WorldOverview.Place> places) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldOverview.Place p : places) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("name", p.name());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> issues(List<WorldOverview.Issue> issues) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldOverview.Issue i : issues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", i.code());
            row.put("severity", i.severity());
            row.put("message", i.message());
            out.add(row);
        }
        return out;
    }
}
