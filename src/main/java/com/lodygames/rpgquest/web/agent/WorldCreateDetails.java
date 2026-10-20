package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.world.create.WorldCreationAuditLog;
import com.lodygames.rpgquest.world.create.WorldCreationPlanner;
import com.lodygames.rpgquest.world.create.WorldCreationRequest;
import com.lodygames.rpgquest.world.create.WorldCreationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projection JSON des actions {@code world.create.*} (issue #562) : le contrat lu par la page
 * « Créer un monde » de PlugAdmin.
 */
final class WorldCreateDetails {

    private WorldCreateDetails() {
    }

    /** Options : Multiverse, noms déjà pris, préréglages, biomes et blocs du registre. */
    static Map<String, Object> options(WorldCreationService.Options o) {
        WorldCreationPlanner.Context c = o.context();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("multiverse", c.multiverseAvailable());
        d.put("multiverseReason", c.multiverseReason() == null ? "" : c.multiverseReason());
        d.put("mainWorld", c.mainWorld());
        d.put("loaded", List.copyOf(c.loaded()));
        d.put("multiverseKnown", List.copyOf(c.multiverseKnown()));
        d.put("folders", List.copyOf(c.folders()));
        d.put("essential", List.copyOf(c.essential()));
        List<Map<String, Object>> presets = new ArrayList<>();
        for (WorldCreationPlanner.FlatPreset p : WorldCreationPlanner.FlatPreset.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.name());
            m.put("label", p.label());
            m.put("biome", p.biome());
            m.put("layers", layers(p.layers()));
            presets.add(m);
        }
        d.put("presets", presets);
        d.put("biomes", o.biomes());
        d.put("blocks", o.blocks());
        d.put("history", history(o.history()));
        return d;
    }

    static Map<String, Object> plan(WorldCreationPlanner.Plan p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("world", p.world());
        d.put("ok", p.ok());
        d.put("dimension", p.dimension().name());
        d.put("terrain", p.terrain().name());
        d.put("seed", p.seed() == null ? "" : String.valueOf(p.seed()));
        d.put("seedLabel", p.seedLabel());
        d.put("structures", p.structures());
        d.put("preset", p.preset() == null ? "" : p.preset().name());
        d.put("layers", layers(p.layers()));
        d.put("biome", p.biome() == null ? "" : p.biome());
        d.put("generatorSettings", p.generatorSettings() == null ? "" : p.generatorSettings());
        d.put("summary", p.summary());
        d.put("warnings", p.warnings());
        d.put("errors", p.errors());
        d.put("fingerprint", p.fingerprint());
        return d;
    }

    static Map<String, Object> creation(WorldCreationService.Creation c) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("world", c.world());
        d.put("result", c.result().name());
        d.put("message", c.message());
        d.put("replayed", c.replayed());
        List<Map<String, Object>> checks = new ArrayList<>();
        for (WorldCreationService.Check k : c.checks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", k.label());
            m.put("expected", k.expected());
            m.put("actual", k.actual());
            m.put("ok", k.ok());
            checks.add(m);
        }
        d.put("checks", checks);
        d.put("notes", c.notes());
        d.put("history", c.history());
        if (c.plan() != null) {
            d.put("plan", plan(c.plan()));
        }
        return d;
    }

    private static List<Map<String, Object>> layers(List<WorldCreationRequest.Layer> layers) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldCreationRequest.Layer l : layers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("block", l.block());
            m.put("height", l.height());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> history(List<WorldCreationAuditLog.Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorldCreationAuditLog.Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.at());
            m.put("actor", e.actor());
            m.put("world", e.world());
            m.put("result", e.result());
            m.put("message", e.message());
            out.add(m);
        }
        return out;
    }
}
