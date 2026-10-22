package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Icônes pixel art LodyQuests dans PlugAdmin (#428, phase 2b) : <strong>le même sprite et la même
 * table</strong> que le portail public ({@code web-api/src/main/resources/portal}), copiés par Gradle
 * sous {@code classpath:/assets/lq/} et servis en local par {@code /assets/lq/lq-icons.svg}.
 *
 * <p>Résolution d'un material / d'une entité Paper : nom exact, puis suffixe, puis l'icône de repli
 * fournie par l'appelant (l'icône du type d'objectif ou de récompense), et en dernier recours
 * {@value #FALLBACK}. Jamais d'image cassée. Les icônes sont décoratives ({@code aria-hidden}) :
 * le libellé voisin porte toujours le sens. Les Bootstrap Icons ({@link Icons}) restent l'icônographie
 * de l'interface (menus, boutons) ; ces tuiles illustrent le <em>contenu de jeu</em>.</p>
 */
public final class PixelIcons {

    static final String SPRITE = "lq/lq-icons.svg";
    static final String FALLBACK = "unknown";
    private static final Pattern SYMBOL_ID = Pattern.compile("<symbol id=\"lq-([a-z0-9-]+)\"");

    private static final Set<String> NAMES = names(read(SPRITE));
    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    private static final Map<String, String> SUFFIX = new LinkedHashMap<>();

    static {
        Map<String, Object> root = Json.parseObject(new String(read("lq/material-map.json"), StandardCharsets.UTF_8));
        copy(root.get("exact"), EXACT);
        copy(root.get("suffix"), SUFFIX);
    }

    private PixelIcons() {
    }

    static Set<String> names() {
        return NAMES;
    }

    /** {@code name} s'il existe dans le sprite, sinon l'icône de repli. */
    static String known(String name) {
        return name != null && NAMES.contains(name) ? name : FALLBACK;
    }

    /** Material ou entité ({@code DIAMOND_SWORD}, {@code minecraft:spider}…) : exact, suffixe, sinon {@code fallback}. */
    public static String forMaterial(String material, String fallback) {
        if (material == null || material.isBlank()) {
            return known(fallback);
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
        return known(fallback);
    }

    /** Icône d'un objectif structuré ({@code kind} du payload {@code quest.list}) et de sa cible. */
    public static String forObjective(String kind, String target) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "KILL_ENTITY" -> forMaterial(target, "sword");
            case "COLLECT_ITEM", "DELIVER_ITEM_TO_NPC" -> forMaterial(target, "chest");
            case "CRAFT_ITEM", "SMELT_ITEM", "PLACE_BLOCK" -> forMaterial(target, "log");
            case "BREAK_BLOCK" -> forMaterial(target, "pickaxe");
            case "TALK_TO_NPC" -> known("talk");
            case "REACH_LOCATION", "DISCOVER_WAYPOINT" -> known("waypoint");
            case "DISCOVER_CITY" -> known("map");
            case "KILL_QUEST_BOSS" -> known("boss");
            default -> known("scroll");
        };
    }

    /** Icône d'une récompense structurée ({@code kind} du payload {@code quest.list}) et de sa cible. */
    public static String forReward(String kind, String target) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "EXPERIENCE", "XP" -> known("xp");
            case "MONEY" -> known("coin");
            case "ITEM" -> forMaterial(target, "chest");
            case "HEART" -> known("heart");
            case "TABLE" -> known("chest");
            case "COMMAND" -> known("scroll");
            default -> known("trophy");
        };
    }

    /** Tuile 24 px autour d'une icône 16 px, à placer devant un libellé. */
    public static String tile(String name) {
        return "<span class=\"lq-tile\"><svg class=\"lq-ico\" width=\"16\" height=\"16\" aria-hidden=\"true\""
                + " focusable=\"false\"><use href=\"" + Assets.v(SPRITE) + "#lq-" + known(name) + "\"/></svg></span>";
    }

    private static void copy(Object raw, Map<String, String> target) {
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> target.put(String.valueOf(key), String.valueOf(value)));
        }
    }

    private static byte[] read(String rel) {
        try (InputStream in = PixelIcons.class.getResourceAsStream("/assets/" + rel)) {
            if (in == null) {
                throw new IllegalStateException("ressource absente du classpath : /assets/" + rel);
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
}
