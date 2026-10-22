package com.lodygames.rpgquest.webapi.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Icônes du portail (#428) : sprite complet, correspondances sûres, repli systématique, tailles nettes. */
class PortalIconsTest {

    /** Noms d'icônes écrits en dur dans les pages : {@code icon("x", …)}, {@code heading("h2", "x", …)}, menu. */
    private static final Pattern USED = Pattern.compile(
            "PortalIcons\\.(?:icon|tile|empty)\\(\"([a-z-]+)\"|PortalIcons\\.heading\\(\"h\\d\", \"([a-z-]+)\""
                    + "|new String\\[] \\{PageMeta\\.[A-Z]+, \"[^\"]+\", \"([a-z-]+)\"}|case [A-Z, ]+ -> \"([a-z-]+)\";");

    @Test
    void spriteHoldsTheFallbackAndOnlyOriginalSymbols() {
        assertTrue(PortalIcons.names().contains(PortalIcons.FALLBACK));
        assertTrue(PortalIcons.names().size() >= 28, PortalIcons.names().toString());
        String sprite = new String(PortalIcons.sprite(), StandardCharsets.UTF_8);
        assertTrue(sprite.contains("pixel art original du projet"));
        assertTrue(!sprite.contains("<image") && !sprite.contains("href="), "aucune image ni ressource externe");
        assertEquals(12, PortalIcons.VERSION.length());
    }

    @Test
    void everyMappedIconExistsInTheSprite() {
        PortalIcons.exactMaterials().forEach((material, icon) ->
                assertTrue(PortalIcons.names().contains(icon), material + " -> " + icon));
        PortalIcons.suffixMaterials().forEach((suffix, icon) ->
                assertTrue(PortalIcons.names().contains(icon), suffix + " -> " + icon));
        for (String metric : StatLabels.PREFERRED) {
            assertNotEquals(PortalIcons.FALLBACK, PortalIcons.forMetric(metric), metric);
        }
    }

    @Test
    void everyIconNamedInThePagesExists() throws IOException {
        List<String> used = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/com/lodygames/rpgquest/webapi/site"))) {
            for (Path file : files.toList()) {
                Matcher matcher = USED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    for (int group = 1; group <= matcher.groupCount(); group++) {
                        if (matcher.group(group) != null) {
                            used.add(file.getFileName() + ":" + matcher.group(group));
                        }
                    }
                }
            }
        }
        assertTrue(used.size() > 20, "le relevé des icônes utilisées doit fonctionner : " + used);
        for (String entry : used) {
            String name = entry.substring(entry.indexOf(':') + 1);
            assertTrue(PortalIcons.names().contains(name), "icône absente du sprite : " + entry);
        }
    }

    @Test
    void materialsResolveExactThenSuffixThenFallback() {
        assertEquals("sword", PortalIcons.forMaterial("DIAMOND_SWORD"));
        assertEquals("pie", PortalIcons.forMaterial("PUMPKIN_PIE"));
        assertEquals("bread", PortalIcons.forMaterial("minecraft:bread"));
        assertEquals("flower", PortalIcons.forMaterial("poppy"));
        assertEquals("spider", PortalIcons.forMaterial("CAVE_SPIDER"));
        assertEquals("fish", PortalIcons.forMaterial("COD"));
        assertEquals("ore", PortalIcons.forMaterial("DEEPSLATE_IRON_ORE"));
        assertEquals("log", PortalIcons.forMaterial("OAK_PLANKS"));
        assertEquals(PortalIcons.FALLBACK, PortalIcons.forMaterial("ELYTRA"));
        assertEquals(PortalIcons.FALLBACK, PortalIcons.forMaterial(""));
        assertEquals(PortalIcons.FALLBACK, PortalIcons.forMaterial(null));
        assertEquals("trophy", PortalIcons.forMetric("BLOCK_BREAK"), "métrique inconnue : trophée générique");
    }

    @Test
    void markupIsDecorativeVersionedAndPixelPerfect() {
        String icon = PortalIcons.icon("sword", 32);
        assertTrue(icon.contains("aria-hidden=\"true\"") && icon.contains("focusable=\"false\""), icon);
        assertTrue(icon.contains("href=\"/assets/lq-icons.svg?v=" + PortalIcons.VERSION + "#lq-sword\""), icon);
        assertTrue(PortalIcons.icon("n-existe-pas", 16).contains("#lq-unknown\""), "repli, jamais d'image cassée");
        assertThrows(IllegalArgumentException.class, () -> PortalIcons.icon("sword", 20), "taille non entière");
        assertTrue(PortalIcons.heading("h2", "scroll", "<Titre>").contains("&lt;Titre&gt;"), "texte échappé");
    }
}
