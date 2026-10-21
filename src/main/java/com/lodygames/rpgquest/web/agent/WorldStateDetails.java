package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.world.create.WorldCreationAuditLog;
import com.lodygames.rpgquest.world.state.WorldStatePlanner;
import com.lodygames.rpgquest.world.state.WorldStateService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection JSON des actions {@code world.state.options}, {@code world.load}, {@code world.unload},
 * {@code world.import} et {@code world.autoload} (issue #563) : le contrat lu par PlugAdmin.
 */
final class WorldStateDetails {

    private WorldStateDetails() {
    }

    static Map<String, Object> options(WorldStateService.Options o) {
        WorldStatePlanner.Context c = o.context();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("multiverse", c.multiverseAvailable());
        d.put("multiverseReason", c.multiverseReason() == null ? "" : c.multiverseReason());
        d.put("mainWorld", c.mainWorld());
        List<Map<String, Object>> worlds = new ArrayList<>();
        o.worlds().forEach((name, verdicts) -> {
            WorldStatePlanner.Known k = c.known().get(name.toLowerCase(java.util.Locale.ROOT));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("loaded", c.players().containsKey(name));
            m.put("players", c.players().getOrDefault(name, 0));
            m.put("multiverseKnown", k != null);
            m.put("autoLoad", k != null && k.autoLoad());
            m.put("essential", !WorldStatePlanner.essentialReasons(name, c).isEmpty());
            Map<String, Object> ops = new LinkedHashMap<>();
            verdicts.forEach((op, plan) -> ops.put(op.name(), plan(plan)));
            m.put("operations", ops);
            worlds.add(m);
        });
        d.put("worlds", worlds);
        List<Map<String, Object>> folders = new ArrayList<>();
        for (WorldStatePlanner.Folder f : o.importable()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("dimension", f.dimension());
            m.put("plan", plan(WorldStatePlanner.plan(new WorldStatePlanner.Request(WorldStatePlanner.Operation.IMPORT,
                    f.name(), f.dimension(), true), c)));
            folders.add(m);
        }
        d.put("importable", folders);
        d.put("history", history(o.history()));
        return d;
    }

    static Map<String, Object> plan(WorldStatePlanner.Plan p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("operation", p.operation().name());
        d.put("world", p.world());
        d.put("ok", p.ok());
        d.put("autoLoad", p.autoLoad());
        d.put("environment", p.environment());
        d.put("summary", p.summary());
        d.put("errors", p.errors());
        d.put("warnings", p.warnings());
        return d;
    }

    static Map<String, Object> outcome(WorldStateService.Outcome o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("world", o.world());
        d.put("operation", o.operation().name());
        d.put("result", o.result().name());
        d.put("message", o.message());
        d.put("replayed", o.replayed());
        List<Map<String, Object>> checks = new ArrayList<>();
        for (WorldStateService.Check k : o.checks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", k.label());
            m.put("expected", k.expected());
            m.put("actual", k.actual());
            m.put("ok", k.ok());
            checks.add(m);
        }
        d.put("checks", checks);
        d.put("notes", o.notes());
        d.put("history", o.history());
        if (o.plan() != null) {
            d.put("plan", plan(o.plan()));
        }
        return d;
    }

    private static List<Map<String, Object>> history(List<WorldCreationAuditLog.Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldCreationAuditLog.Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at());
            m.put("actor", e.actor());
            m.put("world", e.world());
            m.put("operation", e.checks().stream().filter(l -> l.startsWith("OPÉRATION ")).findFirst()
                    .map(l -> l.substring("OPÉRATION ".length())).orElse(""));
            m.put("result", e.result());
            m.put("message", e.message());
            out.add(m);
        }
        return out;
    }
}
