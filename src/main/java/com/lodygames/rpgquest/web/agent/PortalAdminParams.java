package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.travel.WorldPortalAdminService.Kind;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Operation;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.ZoneRequest;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.util.Locale;
import java.util.Map;

/**
 * Lecture des paramètres de {@code portal.create|update|enable|disable|delete} (issue #565) :
 * contrat de forme uniquement, les règles métier sont dans {@code WorldPortalAdminService}.
 *
 * <ul>
 *   <li>{@code confirm=true} obligatoire pour toutes ;</li>
 *   <li>{@code id} ; {@code expected} (empreinte lue par la page) sauf pour la création ;</li>
 *   <li>zone : {@code zone_mode=site} + {@code site}, {@code width}, {@code height}, {@code depth},
 *       ou {@code zone_mode=coords} + {@code world} (création) et {@code min_x}…{@code max_z} ;
 *       {@code zone_mode=keep} (ou absent) en modification = zone inchangée ;</li>
 *   <li>création : {@code destination_world}, {@code strategy} ({@code WORLD_SPAWN} par défaut) ;</li>
 *   <li>{@code name} : présent = nouveau nom (vide = nom déduit de la destination) ;</li>
 *   <li>{@code confirm_essential=true} : portail vers le Hub, le Wild ou les Claims.</li>
 * </ul>
 */
final class PortalAdminParams {

    record Parsed(Operation operation, String error) {

        static Parsed fail(String error) {
            return new Parsed(null, error);
        }
    }

    private static final String[] CORNERS = {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z"};

    private PortalAdminParams() {
    }

    static Parsed parse(Kind kind, Map<String, String> p) {
        if (!"true".equals(p.get("confirm"))) {
            return Parsed.fail("Confirmation explicite manquante : aucun portail n'a été modifié.");
        }
        String id = trim(p.get("id")).toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return Parsed.fail("Paramètre « id » manquant.");
        }
        String expected = trim(p.get("expected"));
        if (kind != Kind.CREATE && !expected.matches("[0-9a-f]{24}")) {
            return Parsed.fail("Empreinte « expected » manquante ou mal formée : rafraîchir les portails.");
        }
        String name = p.containsKey("name") ? trim(p.get("name")) : null;
        String mode = trim(p.get("zone_mode")).toLowerCase(Locale.ROOT);
        ZoneRequest zone = null;
        if (kind == Kind.CREATE || kind == Kind.UPDATE) {
            if (mode.equals("site")) {
                Integer w = integer(p.get("width"));
                Integer h = integer(p.get("height"));
                Integer d = integer(p.get("depth"));
                if (w == null || h == null || d == null) {
                    return Parsed.fail("Largeur, hauteur et profondeur doivent être des nombres entiers.");
                }
                zone = ZoneRequest.fromSite(trim(p.get("site")), w, h, d);
            } else if (mode.equals("coords")) {
                int[] c = new int[CORNERS.length];
                for (int i = 0; i < CORNERS.length; i++) {
                    Integer v = integer(p.get(CORNERS[i]));
                    if (v == null) {
                        return Parsed.fail("Coordonnée « " + CORNERS[i] + " » manquante ou invalide.");
                    }
                    c[i] = v;
                }
                Zone z = new Zone(Math.min(c[0], c[3]), Math.min(c[1], c[4]), Math.min(c[2], c[5]),
                        Math.max(c[0], c[3]), Math.max(c[1], c[4]), Math.max(c[2], c[5]));
                zone = ZoneRequest.explicit(trim(p.get("world")), z);
            } else if (kind == Kind.CREATE || !(mode.isEmpty() || mode.equals("keep"))) {
                return Parsed.fail("Zone : choisir « site » (emplacement) ou « coords » (coordonnées).");
            }
        }
        DestinationStrategy strategy = DestinationStrategy.WORLD_SPAWN;
        String rawStrategy = trim(p.get("strategy"));
        if (!rawStrategy.isEmpty()) {
            try {
                strategy = DestinationStrategy.valueOf(rawStrategy.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Parsed.fail("Arrivée « " + rawStrategy + " » inconnue.");
            }
        }
        String actor = trim(p.get("by"));
        return new Parsed(new Operation(kind, id, expected, name, zone, trim(p.get("destination_world")), strategy,
                "true".equals(p.get("confirm_essential")), actor.isEmpty() ? "panel" : actor), null);
    }

    private static Integer integer(String raw) {
        try {
            return Integer.parseInt(trim(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}
