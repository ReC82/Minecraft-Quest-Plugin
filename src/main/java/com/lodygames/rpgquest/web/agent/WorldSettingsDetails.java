package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.world.settings.WorldSettingsAuditLog;
import com.lodygames.rpgquest.world.settings.WorldSettingsPlanner;
import com.lodygames.rpgquest.world.settings.WorldSettingsService;
import com.lodygames.rpgquest.world.settings.WorldSettingsSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection JSON des actions {@code world.settings.*} (issue #561) : le contrat lu par la fiche
 * monde de PlugAdmin. Valeurs canoniques telles que relues par le plugin ; {@code null} = inconnue.
 */
final class WorldSettingsDetails {

    private WorldSettingsDetails() {
    }

    static Map<String, Object> reading(String world, WorldSettingsService.Reading r) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("world", world);
        d.put("loaded", r.snapshot().isPresent());
        r.snapshot().ifPresent(s -> d.put("snapshot", snapshot(s)));
        d.put("fingerprint", r.fingerprint());
        d.put("history", history(r.history()));
        return d;
    }

    static Map<String, Object> preview(String world, WorldSettingsService.Preview p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("world", world);
        d.put("loaded", p.snapshot().isPresent());
        d.put("message", p.message());
        p.snapshot().ifPresent(s -> d.put("snapshot", snapshot(s)));
        p.plan().ifPresent(plan -> {
            d.put("fingerprint", plan.fingerprint());
            d.put("applicable", plan.applicable());
            d.put("refused", plan.refused());
            d.put("warnings", plan.warnings());
            List<Map<String, Object>> changes = new ArrayList<>();
            for (WorldSettingsPlanner.Change c : plan.changes()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", c.key());
                row.put("label", c.label());
                row.put("current", c.current());
                row.put("proposed", c.proposed());
                row.put("writer", c.writer());
                row.put("status", c.status().name());
                row.put("reason", c.reason());
                row.put("effect", c.effect());
                changes.add(row);
            }
            d.put("changes", changes);
        });
        return d;
    }

    static Map<String, Object> application(WorldSettingsService.Application a) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("world", a.world());
        d.put("result", a.result().name());
        d.put("message", a.message());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorldSettingsService.Applied x : a.applied()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", x.key());
            row.put("label", x.label());
            row.put("before", x.before());
            row.put("requested", x.requested());
            row.put("after", x.after());
            row.put("outcome", x.outcome());
            row.put("detail", x.detail());
            rows.add(row);
        }
        d.put("applied", rows);
        d.put("notes", a.notes());
        return d;
    }

    private static Map<String, Object> snapshot(WorldSettingsSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("multiverse", s.multiverse());
        m.put("roles", s.roles());
        m.put("players", s.players());
        m.put("minHeight", s.minHeight());
        m.put("maxHeight", s.maxHeight());
        m.put("values", new LinkedHashMap<>(s.values()));
        m.put("writers", new LinkedHashMap<>(s.writers()));
        m.put("unavailable", new LinkedHashMap<>(s.unavailable()));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (WorldSettingsSnapshot.Rule r : s.rules()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.name());
            row.put("type", r.type());
            row.put("value", r.value());
            com.lodygames.rpgquest.world.settings.WorldSetting.owningRule(r.name())
                    .ifPresent(owner -> row.put("dedicatedTo", owner.key()));
            rules.add(row);
        }
        m.put("rules", rules);
        m.put("notes", s.notes());
        return m;
    }

    private static List<Map<String, Object>> history(List<WorldSettingsAuditLog.Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldSettingsAuditLog.Entry e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", e.at());
            row.put("actor", e.actor());
            row.put("result", e.result());
            List<Map<String, Object>> lines = new ArrayList<>();
            for (WorldSettingsAuditLog.Line l : e.lines()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("key", l.key());
                lm.put("before", l.before());
                lm.put("requested", l.requested());
                lm.put("after", l.after());
                lm.put("outcome", l.outcome());
                lines.add(lm);
            }
            row.put("changes", lines);
            out.add(row);
        }
        return out;
    }
}
