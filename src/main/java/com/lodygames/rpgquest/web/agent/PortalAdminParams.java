package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.dialogue.DialogueConditionYaml;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.DestinationRequest;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Kind;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.Operation;
import com.lodygames.rpgquest.travel.WorldPortalAdminService.ZoneRequest;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.PortalMessages;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.FixedPoint;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.MemoryConfiguration;

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
 *   <li>création : {@code destination_world}, {@code strategy} ({@code WORLD_SPAWN} par défaut) ;
 *       modification : les mêmes avec {@code dest_mode=set} (absent ou {@code keep} = arrivée
 *       inchangée) ;</li>
 *   <li>{@code strategy=FIXED_POINT} : {@code point_x}, {@code point_y}, {@code point_z} obligatoires,
 *       {@code point_yaw}, {@code point_pitch} facultatifs (0) — issue #566 ;</li>
 *   <li>création : {@code return_of} facultatif = portail dont celui-ci est le retour ;</li>
 *   <li>{@code name} : présent = nouveau nom (vide = nom déduit de la destination) ;</li>
 *   <li>{@code confirm_essential=true} : portail vers le Hub, le Wild ou les Claims.</li>
 *   <li>{@code portal.access} (issue #567) : {@code level} (vide = aucun) + {@code level_message},
 *       {@code cooldown} (secondes, vide ou 0 = aucun) + {@code cooldown_message}, et jusqu'à
 *       {@value PortalAccess#MAX_RULES} lignes {@code cond_<i>_type} (vide = ligne ignorée),
 *       {@code cond_<i>_negate}, {@code cond_<i>_quest} / {@code _state} / {@code _material} /
 *       {@code _amount} / {@code _permission} / {@code _key} / {@code _value}, {@code cond_<i>_message}.
 *       Les conditions sont lues par le même code que les dialogues.</li>
 *   <li>{@code portal.messages} (issue #569) : {@code msg_welcome|preparing|success|unavailable|unsafe|refusal},
 *       {@code warning_mode} (DEFAULT, ALWAYS, NEVER), {@code warn_title|text|hint|enter|enter_hide|cancel|cancel_text},
 *       {@code warn_allow_hide} ; vide = texte par défaut.</li>
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
        DestinationRequest destination = null;
        String destMode = trim(p.get("dest_mode")).toLowerCase(Locale.ROOT);
        if (kind == Kind.CREATE || (kind == Kind.UPDATE && destMode.equals("set"))) {
            FixedPoint point = null;
            if (strategy == DestinationStrategy.FIXED_POINT) {
                Double x = decimal(p.get("point_x"));
                Double y = decimal(p.get("point_y"));
                Double z = decimal(p.get("point_z"));
                if (x == null || y == null || z == null) {
                    return Parsed.fail("Point fixe : X, Y et Z doivent être des nombres.");
                }
                Double yaw = trim(p.get("point_yaw")).isEmpty() ? Double.valueOf(0) : decimal(p.get("point_yaw"));
                Double pitch = trim(p.get("point_pitch")).isEmpty() ? Double.valueOf(0) : decimal(p.get("point_pitch"));
                if (yaw == null || pitch == null || pitch < -90 || pitch > 90) {
                    return Parsed.fail("Regard du point fixe invalide : horizontal en degrés, vertical de -90 à 90.");
                }
                point = new FixedPoint(x, y, z, yaw.floatValue(), pitch.floatValue());
            }
            destination = new DestinationRequest(trim(p.get("destination_world")), strategy, point);
        } else if (kind == Kind.UPDATE && !(destMode.isEmpty() || destMode.equals("keep"))) {
            return Parsed.fail("Arrivée : « dest_mode » doit valoir « set » ou « keep ».");
        }
        String returnOf = kind == Kind.CREATE ? trim(p.get("return_of")).toLowerCase(Locale.ROOT) : "";
        if (!returnOf.isEmpty() && !returnOf.matches("[a-z0-9_-]{1,48}")) {
            return Parsed.fail("Portail aller « return_of » invalide.");
        }
        PortalAccess access = null;
        if (kind == Kind.ACCESS) {
            AccessParsed a = access(p);
            if (a.error() != null) {
                return Parsed.fail(a.error());
            }
            access = a.access();
        }
        PortalMessages messages = null;
        if (kind == Kind.MESSAGES) {
            try {
                messages = messages(p);
            } catch (IllegalArgumentException e) {
                return Parsed.fail("Messages : " + e.getMessage());
            }
        }
        String actor = trim(p.get("by"));
        return new Parsed(new Operation(kind, id, expected, name, zone, destination, returnOf,
                "true".equals(p.get("confirm_essential")), actor.isEmpty() ? "panel" : actor, access, messages), null);
    }

    /**
     * Messages et avertissement (issue #569) : tous facultatifs, vide = texte par défaut ;
     * {@code warning_mode} DEFAULT / ALWAYS / NEVER, {@code warn_allow_hide} absent = {@code true}.
     */
    static PortalMessages messages(Map<String, String> p) {
        String rawMode = trim(p.get("warning_mode")).toUpperCase(Locale.ROOT);
        PortalMessages.Mode mode;
        try {
            mode = rawMode.isEmpty() ? PortalMessages.Mode.DEFAULT : PortalMessages.Mode.valueOf(rawMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("avertissement « " + rawMode + " » inconnu (DEFAULT, ALWAYS ou NEVER).");
        }
        String allowHide = trim(p.get("warn_allow_hide"));
        if (!allowHide.isEmpty() && !allowHide.equals("true") && !allowHide.equals("false")) {
            throw new IllegalArgumentException("« warn_allow_hide » doit valoir true ou false.");
        }
        PortalMessages.Warning warning = new PortalMessages.Warning(mode, p.get("warn_title"), p.get("warn_text"),
                p.get("warn_hint"), p.get("warn_enter"), p.get("warn_enter_hide"), p.get("warn_cancel"),
                p.get("warn_cancel_text"), !allowHide.equals("false"));
        return new PortalMessages(p.get("msg_welcome"), p.get("msg_preparing"), p.get("msg_success"),
                p.get("msg_unavailable"), p.get("msg_unsafe"), p.get("msg_refusal"), warning);
    }

    private record AccessParsed(PortalAccess access, String error) {
    }

    private static final String[] CONDITION_KEYS = {"quest", "state", "material", "permission", "key", "value"};

    /** Conditions d'accès (issue #567) : forme seulement, la quête et le type de variable sont revérifiés par le service. */
    static AccessParsed access(Map<String, String> p) {
        Integer level = null;
        if (!trim(p.get("level")).isEmpty()) {
            level = integer(p.get("level"));
            if (level == null || level < 1 || level > PortalAccess.MAX_LEVEL) {
                return new AccessParsed(null, "Niveau minimum : un entier de 1 à " + PortalAccess.MAX_LEVEL
                        + " (vide = aucun).");
            }
        }
        int cooldown = 0;
        if (!trim(p.get("cooldown")).isEmpty()) {
            Integer c = integer(p.get("cooldown"));
            if (c == null || c < 0 || c > PortalAccess.MAX_COOLDOWN_SECONDS) {
                return new AccessParsed(null, "Délai entre deux passages : de 0 à " + PortalAccess.MAX_COOLDOWN_SECONDS
                        + " secondes.");
            }
            cooldown = c;
        }
        List<PortalAccess.Rule> rules = new ArrayList<>();
        for (int i = 0; i < PortalAccess.MAX_RULES; i++) {
            String prefix = "cond_" + i + "_";
            String type = trim(p.get(prefix + "type")).toUpperCase(Locale.ROOT);
            if (type.isEmpty()) {
                continue;
            }
            String where = "Condition " + (i + 1) + " : ";
            MemoryConfiguration section = new MemoryConfiguration();
            section.set("type", type);
            for (String key : CONDITION_KEYS) {
                String value = trim(p.get(prefix + key));
                if (value.length() > 100) {
                    return new AccessParsed(null, where + "« " + key + " » trop long (100 caractères au plus).");
                }
                if (!value.isEmpty()) {
                    section.set(key, value);
                }
            }
            if ("VARIABLE_EQUALS".equals(type) && !section.isSet("value")) {
                section.set("value", "");
            }
            String amount = trim(p.get(prefix + "amount"));
            if (!amount.isEmpty()) {
                Integer n = integer(amount);
                if (n == null) {
                    return new AccessParsed(null, where + "la quantité doit être un nombre entier.");
                }
                section.set("amount", n);
            }
            if (section.isSet("permission") && !section.getString("permission").matches("[A-Za-z0-9_.-]{1,100}")) {
                return new AccessParsed(null, where + "permission invalide (lettres, chiffres, « . », « _ », « - »).");
            }
            if (section.isSet("key") && !section.getString("key").matches("[A-Za-z0-9_.:-]{1,64}")) {
                return new AccessParsed(null, where + "nom de variable invalide (lettres, chiffres, « _ », « . », « : », « - »).");
            }
            section.set("negate", "true".equals(p.get(prefix + "negate")));
            List<String> errors = new ArrayList<>();
            DialogueCondition condition = DialogueConditionYaml.parse(section, "condition " + (i + 1), errors);
            if (condition == null || !errors.isEmpty()) {
                return new AccessParsed(null, where + String.join(" ; ", errors));
            }
            try {
                rules.add(new PortalAccess.Rule(condition, p.get(prefix + "message")));
            } catch (IllegalArgumentException e) {
                return new AccessParsed(null, where + e.getMessage());
            }
        }
        try {
            return new AccessParsed(new PortalAccess(level, p.get("level_message"), rules, cooldown,
                    p.get("cooldown_message")), null);
        } catch (IllegalArgumentException e) {
            return new AccessParsed(null, "Conditions d'accès : " + e.getMessage());
        }
    }

    /** Nombre fini, au plus 30 000 000 en valeur absolue ({@code null} sinon). */
    private static Double decimal(String raw) {
        try {
            double v = Double.parseDouble(trim(raw).replace(',', '.'));
            return Double.isFinite(v) && Math.abs(v) <= 30_000_000 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
