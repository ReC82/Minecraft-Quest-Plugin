package com.lodygames.rpgquest.webapi.site;

import com.lodygames.rpgquest.webapi.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Icônes pixel art LodyQuests (#428) : sprite SVG original servi en local par le portail, et
 * correspondance material / entité / métrique → icône.
 *
 * <p>Le sprite et la table ({@code portal/lq-icons.svg}, {@code portal/material-map.json}) sont
 * générés par {@code docs/design/428-icones/tools}. Toute icône demandée qui n'existe pas dans le
 * sprite retombe sur {@value #FALLBACK} : jamais d'image cassée. Les icônes sont décoratives
 * ({@code aria-hidden}) : le texte voisin porte toujours le sens.</p>
 */
public final class PortalIcons {

    /** Chemin public du sprite ; le paramètre {@code v} change avec son contenu (cache long sans risque). */
    public static final String SPRITE_PATH = "/assets/lq-icons.svg";
    static final String FALLBACK = "unknown";
    private static final Pattern SYMBOL_ID = Pattern.compile("<symbol id=\"lq-([a-z0-9-]+)\"");

    private static final byte[] SPRITE = read("portal/lq-icons.svg");
    /** Empreinte courte du sprite, pour l'adresse versionnée. */
    public static final String VERSION = digest(SPRITE);
    private static final Set<String> NAMES = names(SPRITE);
    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    private static final Map<String, String> SUFFIX = new LinkedHashMap<>();

    /** Métrique de classement (#54) → icône ; une métrique inconnue prend le trophée. */
    private static final Map<String, String> METRICS = Map.ofEntries(
            Map.entry("RPG_XP_GLOBAL", "xp"),
            Map.entry("QUESTS_COMPLETED", "scroll"),
            Map.entry("QUEST_COMPLETION", "scroll"),
            Map.entry("STORY_COMPLETED", "scroll"),
            Map.entry("ENTITY_KILL", "skull"),
            Map.entry("FISH_CAUGHT", "fish"),
            Map.entry("BLOCK_BREAK", "pickaxe"),
            Map.entry("WAYPOINT_DISCOVERED", "waypoint"),
            Map.entry("CITY_DISCOVERED", "map"),
            Map.entry("WAYSTONE_DISCOVERED", "waypoint"),
            Map.entry("RPG_XP_COMBAT", "sword"),
            Map.entry("RPG_XP_MINING", "pickaxe"),
            Map.entry("RPG_XP_FARMING", "bread"),
            Map.entry("RPG_XP_FISHING", "fish"),
            Map.entry("RPG_XP_EXPLORATION", "map"));

    static {
        loadMaterialMap();
    }

    private PortalIcons() {
    }

    static byte[] sprite() {
        return SPRITE.clone();
    }

    static Set<String> names() {
        return NAMES;
    }

    static Map<String, String> exactMaterials() {
        return EXACT;
    }

    static Map<String, String> suffixMaterials() {
        return SUFFIX;
    }

    /** {@code name} s'il existe dans le sprite, sinon l'icône de repli. */
    static String known(String name) {
        return name != null && NAMES.contains(name) ? name : FALLBACK;
    }

    /** Material ou entité Paper ({@code DIAMOND_SWORD}, {@code SPIDER}…) : nom exact, puis suffixe, puis repli. */
    public static String forMaterial(String material) {
        if (material == null || material.isBlank()) {
            return FALLBACK;
        }
        String key = material.trim().toUpperCase(Locale.ROOT);
        int namespace = key.indexOf(':');
        if (namespace >= 0) {
            key = key.substring(namespace + 1);
        }
        String exact = EXACT.get(key);
        if (exact != null) {
            return known(exact);
        }
        for (Map.Entry<String, String> suffix : SUFFIX.entrySet()) {
            if (key.endsWith(suffix.getKey())) {
                return known(suffix.getValue());
            }
        }
        return FALLBACK;
    }

    /** Icône d'une métrique de classement. */
    public static String forMetric(String metric) {
        return known(METRICS.getOrDefault(metric, "trophy"));
    }

    /** Icône seule, à côté d'un texte : 16, 32 ou 48 px (multiples nets de la grille). */
    public static String icon(String name, int size) {
        if (size != 16 && size != 32 && size != 48) {
            throw new IllegalArgumentException("taille d'icône non entière : " + size);
        }
        return "<svg class=\"lq-ico lq-" + size + "\" aria-hidden=\"true\" focusable=\"false\"><use href=\""
                + SPRITE_PATH + "?v=" + VERSION + "#lq-" + known(name) + "\"/></svg>";
    }

    /** Icône dans une tuile contrastée (24 px autour d'une icône 16, 44 px autour d'une icône 32). */
    public static String tile(String name, boolean large) {
        return "<span class=\"lq-tile" + (large ? " lq-tile-l" : "") + "\">" + icon(name, large ? 32 : 16) + "</span>";
    }

    /** Titre de section précédé de sa tuile : {@code <h2 class="lq-h">…</h2>}. */
    public static String heading(String level, String name, String text) {
        return "<" + level + " class=\"lq-h\">" + tile(name, true) + "<span>" + Html.escape(text) + "</span></" + level + ">";
    }

    /** État vide illustré : icône + phrase, sans jamais de valeur inventée. */
    public static String empty(String name, String html) {
        return "<div class=\"lq-empty\">" + tile(name, true) + "<p class=\"note\">" + html + "</p></div>";
    }

    private static void loadMaterialMap() {
        Object parsed = Json.parse(new String(read("portal/material-map.json"), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalStateException("material-map.json : objet attendu");
        }
        copy(root.get("exact"), EXACT);
        copy(root.get("suffix"), SUFFIX);
    }

    private static void copy(Object raw, Map<String, String> target) {
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> target.put(String.valueOf(key), String.valueOf(value)));
        }
    }

    private static byte[] read(String resource) {
        try (InputStream in = PortalIcons.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("ressource absente du JAR : " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> names(byte[] sprite) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SYMBOL_ID.matcher(new String(sprite, StandardCharsets.UTF_8));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return Set.copyOf(names);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
