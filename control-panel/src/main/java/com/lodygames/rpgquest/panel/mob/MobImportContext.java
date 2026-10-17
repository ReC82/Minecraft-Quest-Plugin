package com.lodygames.rpgquest.panel.mob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Issue #214 — ce que PlugAdmin sait du serveur pour préparer un profil de mob : les modèles du
 * moteur installé ({@code mob.catalogs}), les catalogues réels, les profils existants
 * ({@code mob.list}) et les récompenses ({@code reward.catalog.status}). Tout vient des derniers
 * relevés ; rien n'est inventé quand un relevé manque.
 *
 * @param templates  modèle par clé ({@code special}, {@code boss-quest}, {@code boss-community}) ; vide
 *                   si le plugin installé ne les fournit pas encore ou si les catalogues ne sont pas relevés
 * @param biomes     clés de biome en minuscules
 * @param rewards    clé → libellé, tables comprises ({@code table:<id>})
 */
public record MobImportContext(boolean catalogsLoaded, Map<String, String> templates, List<String> worlds,
                               String wildWorld, List<String> biomes, List<String> entityTypes,
                               boolean rewardsLoaded, Map<String, String> rewards, List<Profile> profiles) {

    /** Les trois sortes de profil, dans l'ordre d'affichage. */
    public static final List<String> KINDS = List.of("special", "boss-quest", "boss-community");

    /** Un profil existant tel que relevé. {@code yaml} vide = plugin antérieur à #214. */
    public record Profile(String id, String displayName, String category, String bossMode, String yaml) {
    }

    public MobImportContext {
        templates = Map.copyOf(templates);
        worlds = List.copyOf(worlds);
        biomes = List.copyOf(biomes);
        entityTypes = List.copyOf(entityTypes);
        rewards = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
        profiles = List.copyOf(profiles);
    }

    public static String kindLabel(String kind) {
        return switch (kind) {
            case "special" -> "Mob spécial (tirage aléatoire du Wild)";
            case "boss-quest" -> "Boss de quête";
            case "boss-community" -> "Boss communautaire";
            default -> kind;
        };
    }

    public static boolean isKind(String kind) {
        return kind != null && KINDS.contains(kind);
    }

    public Optional<Profile> profile(String id) {
        String wanted = plainId(id);
        return profiles.stream().filter(p -> plainId(p.id()).equals(wanted)).findFirst();
    }

    /** Identifiants pris, sans espace de noms. */
    public List<String> takenIds() {
        List<String> out = new ArrayList<>();
        profiles.forEach(p -> out.add(plainId(p.id())));
        return out;
    }

    static String plainId(String id) {
        String s = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("rpgquest:") ? s.substring("rpgquest:".length()) : s;
    }

    /** Relevés bruts (détails d'action) → contexte. */
    @SuppressWarnings("unchecked")
    public static MobImportContext of(Map<String, Object> catalogs, Map<String, Object> mobList,
                                      Map<String, Object> rewardStatus) {
        Map<String, String> templates = new LinkedHashMap<>();
        if (catalogs != null && catalogs.get("templates") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (isKind(String.valueOf(k)) && v != null) {
                    templates.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        List<String> biomes = strings(catalogs, "biomes").stream().map(b -> b.toLowerCase(Locale.ROOT)).toList();
        Map<String, String> rewards = new LinkedHashMap<>();
        if (rewardStatus != null) {
            for (Object o : list(rewardStatus.get("rewards"))) {
                if (o instanceof Map<?, ?> r && r.get("key") != null) {
                    rewards.put(String.valueOf(r.get("key")), r.get("label") == null ? "" : String.valueOf(r.get("label")));
                }
            }
            for (Object o : list(rewardStatus.get("tables"))) {
                if (o instanceof Map<?, ?> t && t.get("id") != null) {
                    rewards.put("table:" + t.get("id"), "tirage « " + (t.get("label") == null ? t.get("id") : t.get("label")) + " »");
                }
            }
        }
        List<Profile> profiles = new ArrayList<>();
        if (mobList != null) {
            for (Object o : list(mobList.get("profiles"))) {
                if (o instanceof Map<?, ?> p && p.get("id") != null) {
                    Map<String, Object> row = (Map<String, Object>) p;
                    profiles.add(new Profile(str(row.get("id")), str(row.get("displayName")), str(row.get("category")),
                            str(row.get("bossMode")), str(row.get("yaml"))));
                }
            }
        }
        return new MobImportContext(catalogs != null, templates, strings(catalogs, "worlds"),
                catalogs == null ? "" : str(catalogs.get("wildWorld")), biomes, strings(catalogs, "entityTypes"),
                rewardStatus != null, rewards, profiles);
    }

    private static List<String> strings(Map<String, Object> map, String key) {
        List<String> out = new ArrayList<>();
        if (map != null) {
            for (Object o : list(map.get(key))) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
