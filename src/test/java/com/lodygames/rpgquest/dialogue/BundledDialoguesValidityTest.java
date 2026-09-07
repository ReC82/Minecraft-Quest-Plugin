package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Filet de sécurité pour les dialogues livrés dans le jar ({@code src/main/resources/dialogues/}) :
 * ils sont copiés/édités à la main par un administrateur mais doivent rester valides tels quels.
 * Couvre en particulier le Guide « centre d'aide » (issue #11).
 */
class BundledDialoguesValidityTest {

    private static final List<String> BUNDLED =
            List.of("guard.yml", "guide.yml", "jo.yml", "libraire.yml", "merchant.yml");

    private final DialogueLoader loader = new DialogueLoader(List.of("give", "xp", "customitem", "claim"));

    @Test
    void everyBundledDialogueLoadsWithoutIssue() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        for (String name : BUNDLED) {
            files.put(name, read("/dialogues/" + name));
        }

        DialogueLoadReport report = loader.load(files);

        assertTrue(report.issues().isEmpty(), () -> "problèmes de chargement : " + report.issues());
        assertEquals(BUNDLED.size(), report.loaded().size());
    }

    @Test
    void theGuideExposesAStructuredHelpMenu() {
        DialogueLoadReport report = loader.load(Map.of("guide.yml", read("/dialogues/guide.yml")));

        DialogueDefinition guide = report.loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "guide")))
                .findFirst()
                .orElseThrow();

        assertTrue(guide.nodes().containsKey("help_menu"), "le Guide doit avoir un nœud de menu d'aide");
        // Chaque sujet du menu renvoie vers un nœud d'aide dédié qui, lui, ramène au menu.
        long topicNodes = guide.nodes().keySet().stream().filter(id -> id.startsWith("help_")).count();
        assertTrue(topicNodes >= 6, "au moins six sujets d'aide attendus, trouvés : " + topicNodes);
    }

    private ConfigurationSection read(String resource) {
        try (InputStream in = BundledDialoguesValidityTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Ressource introuvable : " + resource);
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
