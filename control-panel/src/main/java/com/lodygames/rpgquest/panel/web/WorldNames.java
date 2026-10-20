package com.lodygames.rpgquest.panel.web;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Issue #81 — l'unique résolveur du nom affiché d'un monde dans PlugAdmin (pages PNJ, Mondes,
 * Portails, Voyages). L'identifiant technique du monde n'est jamais modifié : seul le libellé change.
 *
 * <p>Ordre de résolution :</p>
 * <ol>
 *   <li>le nom choisi par l'administrateur, relevé par {@code world.list} ({@code alias} : alias
 *       Multiverse-Core, ou nom RPGQuest pour un monde hors Multiverse) — il se règle dans la fiche du
 *       monde, section Réglages, champ « Nom affiché » ;</li>
 *   <li>à défaut, le rôle LodyQuests du monde : Hub, Claims, Wild ;</li>
 *   <li>à défaut (aucun relevé), les noms usuels : {@code world_hub} → Hub, {@code claims} → Claims,
 *       {@code wild} → Wild, monde principal, Nether, End ;</li>
 *   <li>sinon l'identifiant technique lui-même.</li>
 * </ol>
 */
public final class WorldNames {

    /** Aucun relevé : seuls les noms par défaut s'appliquent. */
    public static final WorldNames DEFAULTS = new WorldNames(Map.of(), Map.of(), "");

    private final Map<String, String> aliases;
    private final Map<String, String> roles;
    private final String mainWorld;

    private WorldNames(Map<String, String> aliases, Map<String, String> roles, String mainWorld) {
        this.aliases = Map.copyOf(aliases);
        this.roles = Map.copyOf(roles);
        this.mainWorld = mainWorld == null ? "" : mainWorld;
    }

    /** Construit le résolveur depuis les détails d'un relevé {@code world.list} ({@code null} = défauts). */
    public static WorldNames of(Map<String, Object> worldList) {
        if (worldList == null) {
            return DEFAULTS;
        }
        Map<String, String> aliases = new HashMap<>();
        Map<String, String> roles = new HashMap<>();
        if (worldList.get("worlds") instanceof List<?> worlds) {
            for (Object o : worlds) {
                if (!(o instanceof Map<?, ?> w) || !(w.get("name") instanceof String name)) {
                    continue;
                }
                if (w.get("alias") instanceof String alias && !alias.isBlank()
                        && !alias.strip().equalsIgnoreCase(name.strip())) {
                    aliases.put(key(name), alias.strip());
                }
                if (w.get("roles") instanceof List<?> r && !r.isEmpty()) {
                    roles.put(key(name), String.valueOf(r.get(0)));
                }
            }
        }
        if (worldList.get("roles") instanceof Map<?, ?> r) {
            putRole(roles, r.get("hub"), "HUB");
            putRole(roles, r.get("claims"), "CLAIMS");
            putRole(roles, r.get("wild"), "WILD");
        }
        String main = worldList.get("mainWorld") instanceof String m ? m : "";
        return new WorldNames(aliases, roles, main);
    }

    /** Nom à montrer à l'administrateur, sans l'identifiant technique. */
    public String name(String world) {
        String w = world == null ? "" : world.trim();
        String alias = aliases.get(key(w));
        if (alias != null) {
            return alias;
        }
        String byRole = roleName(roles.get(key(w)));
        if (byRole != null) {
            return byRole;
        }
        if (!mainWorld.isEmpty() && w.equalsIgnoreCase(mainWorld)) {
            return "Monde principal";
        }
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "world_hub", "hub" -> "Hub";
            case "claims" -> "Claims";
            case "wild" -> "Wild";
            case "world" -> "Monde principal";
            case "world_nether" -> "Nether";
            case "world_the_end" -> "End";
            default -> w;
        };
    }

    /** Nom affiché suivi de l'identifiant technique entre parenthèses quand ils diffèrent. */
    public String label(String world) {
        String w = world == null ? "" : world.trim();
        String name = name(w);
        return name.equals(w) || w.isEmpty() ? w : name + " (" + w + ")";
    }

    /**
     * Nom du monde qui porte ce rôle ({@code HUB}, {@code CLAIMS}, {@code WILD}) : son nom choisi s'il
     * en a un, sinon Hub / Claims / Wild.
     */
    public String ofRole(String role) {
        for (Map.Entry<String, String> e : roles.entrySet()) {
            if (e.getValue().equals(role)) {
                return name(e.getKey());
            }
        }
        String fallback = roleName(role);
        return fallback == null ? role : fallback;
    }

    /** {@code true} si l'administrateur a choisi un nom pour ce monde. */
    public boolean hasCustomName(String world) {
        return aliases.containsKey(key(world == null ? "" : world.trim()));
    }

    private static String roleName(String role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "HUB" -> "Hub";
            case "CLAIMS" -> "Claims";
            case "WILD" -> "Wild";
            default -> null;
        };
    }

    private static void putRole(Map<String, String> roles, Object world, String role) {
        if (world instanceof String w && !w.isBlank()) {
            roles.putIfAbsent(key(w), role);
        }
    }

    private static String key(String world) {
        return world.toLowerCase(Locale.ROOT);
    }
}
