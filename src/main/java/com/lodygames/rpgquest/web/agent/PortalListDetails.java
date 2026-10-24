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
        details.put("worlds", view.worlds());
        List<Map<String, Object>> history = new ArrayList<>();
        for (var h : view.history()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", h.at());
            row.put("actor", h.actor());
            row.put("operation", h.operation());
            row.put("portal", h.portal());
            row.put("before", h.before());
            row.put("after", h.after());
            history.add(row);
        }
        details.put("history", history);
        // #565 : arches de portail posées et passage déduit, pour créer un brouillon depuis l'arche.
        List<Map<String, Object>> arches = new ArrayList<>();
        for (PortalOverview.Arch a : view.arches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("site", a.site());
            row.put("siteName", a.siteName());
            row.put("world", a.world());
            row.put("building", a.building());
            row.put("name", a.name());
            row.put("rotation", a.rotation());
            if (a.zone() != null) {
                row.put("minX", a.zone().minX());
                row.put("minY", a.zone().minY());
                row.put("minZ", a.zone().minZ());
                row.put("maxX", a.zone().maxX());
                row.put("maxY", a.zone().maxY());
                row.put("maxZ", a.zone().maxZ());
            }
            row.put("refusal", a.refusal());
            row.put("usedBy", a.usedBy());
            arches.add(row);
        }
        details.put("arches", arches);
        return details;
    }

    private static Map<String, Object> messages(com.lodygames.rpgquest.travel.model.PortalMessages m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("welcome", m.welcome());
        out.put("preparing", m.preparing());
        out.put("success", m.success());
        out.put("unavailable", m.unavailable());
        out.put("unsafe", m.unsafe());
        out.put("refusal", m.refusal());
        var w = m.warning();
        out.put("warningMode", w.mode().name());
        out.put("title", w.title());
        out.put("text", w.text());
        out.put("hint", w.hint());
        out.put("enter", w.enter());
        out.put("enterHide", w.enterHide());
        out.put("cancel", w.cancel());
        out.put("cancelText", w.cancelText());
        out.put("allowHide", w.allowHide());
        out.put("custom", !m.isEmpty());
        return out;
    }

    /** Textes affichés quand un champ est vide : avertissement (Wild ou générique) et messages d'avant #569. */
    private static Map<String, Object> messageDefaults(com.lodygames.rpgquest.travel.WildEntryWarningService.Texts t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", t.title());
        out.put("text", t.text());
        out.put("hint", t.hint());
        out.put("enter", t.enter());
        out.put("enterHide", t.enterHide());
        out.put("cancel", t.cancel());
        out.put("cancelText", t.cancelText());
        out.put("preparing", t.preparing());
        out.put("success", com.lodygames.rpgquest.travel.WorldPortalTeleportListener.SUCCESS);
        out.put("unavailable", com.lodygames.rpgquest.travel.WorldPortalTeleportListener.WORLD_UNAVAILABLE);
        out.put("unsafe", com.lodygames.rpgquest.travel.WorldPortalTeleportListener.FIXED_POINT_UNSAFE);
        return out;
    }

    private static Map<String, Object> access(com.lodygames.rpgquest.travel.model.PortalAccess access) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", access.requiredLevel() == null ? "" : String.valueOf(access.requiredLevel()));
        out.put("levelMessage", access.levelMessage());
        out.put("cooldown", access.cooldownSeconds());
        out.put("cooldownMessage", access.cooldownMessage());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (var r : access.rules()) {
            Map<String, Object> rule = new LinkedHashMap<>(
                    com.lodygames.rpgquest.dialogue.DialogueConditionYaml.toMap(r.condition()));
            rule.put("message", r.message());
            rule.put("label", com.lodygames.rpgquest.travel.model.PortalAccess.describe(r.condition()));
            rules.add(rule);
        }
        out.put("rules", rules);
        return out;
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
        PortalOverview.Admin a = e.admin();
        row.put("state", a.state());
        row.put("storedName", a.storedName());
        row.put("site", a.site());
        row.put("siteName", a.siteName());
        row.put("width", a.width());
        row.put("height", a.height());
        row.put("depth", a.depth());
        row.put("essential", a.essential());
        row.put("fingerprint", a.fingerprint());
        row.put("editable", a.editable());
        // #567 : conditions d'accès brutes, pour préremplir le formulaire de la fiche.
        if (!a.access().isEmpty()) {
            row.put("access", access(a.access()));
        }
        // #569 : messages bruts (vide = défaut), textes par défaut et avertissement effectif.
        if (a.warningDefaults() != null) {
            row.put("messages", messages(a.messages()));
            row.put("messageDefaults", messageDefaults(a.warningDefaults()));
            row.put("warns", a.warns());
        }
        // #566 : point fixe (valeurs brutes pour préremplir la fiche) et aller-retour.
        if (a.point() != null) {
            row.put("pointX", a.point().x());
            row.put("pointY", a.point().y());
            row.put("pointZ", a.point().z());
            row.put("pointYaw", a.point().yaw());
            row.put("pointPitch", a.point().pitch());
        }
        // #568 : construction posée sur l'emplacement et état du lien (portail entre mondes seulement).
        if (e.kind() == PortalOverview.Kind.WORLD_PORTAL) {
            PortalOverview.Construction c = a.construction();
            Map<String, Object> construction = new LinkedHashMap<>();
            construction.put("linked", c.linked());
            construction.put("status", c.status());
            construction.put("message", c.message());
            construction.put("placed", c.placed());
            construction.put("placedName", c.placedName());
            construction.put("rotation", c.rotation());
            construction.put("linkable", c.linkable());
            construction.put("zone", c.zone());
            construction.put("refusal", c.refusal());
            row.put("construction", construction);
        }
        row.put("returnOf", a.returnOf());
        row.put("returnedBy", a.returnedBy());
        row.put("minX", e.minX());
        row.put("minY", e.minY());
        row.put("minZ", e.minZ());
        row.put("maxX", e.maxX());
        row.put("maxY", e.maxY());
        row.put("maxZ", e.maxZ());
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
