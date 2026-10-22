package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #428 phase 2b : PlugAdmin sert le sprite pixel art du portail sans copie manuelle, et chaque
 * material / entité des quêtes livrées a une vraie icône (pas le repli).
 */
class PixelIconsTest {

    private static final Path PORTAL = Path.of("..", "web-api", "src", "main", "resources", "portal");
    private static final Path QUESTS = Path.of("..", "src", "main", "resources", "quests");
    private static final Pattern TOKEN = Pattern.compile("(?m)^\\s*(?:-\\s*)?(?:entity|material|icon):\\s*([A-Za-z_:]+)\\s*$");

    @Test
    void panelServesExactlyThePortalSpriteAndMap() throws IOException {
        for (String file : new String[] {"lq-icons.svg", "material-map.json"}) {
            assertArrayEquals(Files.readAllBytes(PORTAL.resolve(file)), classpath("/assets/lq/" + file),
                    file + " doit être la copie Gradle de web-api/…/portal, jamais une copie divergente");
        }
    }

    @Test
    void spriteHoldsFallbackAndEveryKindIconExists() {
        assertTrue(PixelIcons.names().contains(PixelIcons.FALLBACK));
        assertTrue(PixelIcons.names().size() >= 28, PixelIcons.names().toString());
        for (String kind : new String[] {"KILL_ENTITY", "COLLECT_ITEM", "CRAFT_ITEM", "SMELT_ITEM", "BREAK_BLOCK",
                "PLACE_BLOCK", "TALK_TO_NPC", "DELIVER_ITEM_TO_NPC", "REACH_LOCATION", "DISCOVER_WAYPOINT",
                "DISCOVER_CITY", "KILL_QUEST_BOSS"}) {
            assertNotEquals(PixelIcons.FALLBACK, PixelIcons.forObjective(kind, ""), kind);
        }
        for (String kind : new String[] {"EXPERIENCE", "MONEY", "ITEM", "VARIABLE", "COMMAND", "HEART", "TABLE"}) {
            assertNotEquals(PixelIcons.FALLBACK, PixelIcons.forReward(kind, ""), kind);
        }
    }

    @Test
    void resolutionIsExactThenSuffixThenKindFallback() {
        assertEquals("spider", PixelIcons.forObjective("KILL_ENTITY", "minecraft:cave_spider"));
        assertEquals("sword", PixelIcons.forObjective("KILL_ENTITY", "PHANTOM"), "mob inconnu : icône du type");
        assertEquals("pickaxe", PixelIcons.forObjective("BREAK_BLOCK", "OBSIDIAN"));
        assertEquals("ore", PixelIcons.forObjective("BREAK_BLOCK", "DEEPSLATE_IRON_ORE"));
        assertEquals("shield", PixelIcons.forReward("ITEM", "LEATHER_CHESTPLATE"));
        assertEquals("pickaxe", PixelIcons.forReward("ITEM", "STONE_PICKAXE"), "_PICKAXE avant _AXE");
        assertEquals("coin", PixelIcons.forReward("MONEY", null));
        assertEquals(PixelIcons.FALLBACK, PixelIcons.forMaterial("ELYTRA", null));
        assertTrue(PixelIcons.tile("n-existe-pas").contains("#lq-unknown\""), "repli, jamais d'image cassée");
    }

    @Test
    void everyMaterialOfTheShippedQuestsHasARealIcon() throws IOException {
        Map<String, String> missing = new TreeMap<>();
        int seen = 0;
        try (Stream<Path> files = Files.list(QUESTS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                Matcher m = TOKEN.matcher(Files.readString(file));
                while (m.find()) {
                    seen++;
                    String token = m.group(1);
                    if (PixelIcons.FALLBACK.equals(PixelIcons.forMaterial(token, null))) {
                        missing.put(token, file.getFileName().toString());
                    }
                }
            }
        }
        assertTrue(seen > 20, "le relevé des quêtes livrées doit fonctionner (" + seen + ")");
        assertTrue(missing.isEmpty(), "materials sans icône (compléter web-api/…/portal/material-map.json) : " + missing);
    }

    private static byte[] classpath(String resource) throws IOException {
        try (InputStream in = PixelIconsTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "absent du classpath : " + resource);
            return in.readAllBytes();
        }
    }
}
