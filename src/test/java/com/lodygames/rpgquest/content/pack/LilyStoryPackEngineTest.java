package com.lodygames.rpgquest.content.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.DialogueLoadReport;
import com.lodygames.rpgquest.dialogue.DialogueLoader;
import com.lodygames.rpgquest.quest.QuestLoadReport;
import com.lodygames.rpgquest.quest.QuestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #211 — le pack d'exemple « Les souvenirs de Lily », servi par PlugAdmin, est lu par les
 * VRAIS chargeurs du moteur : chaque quête et chaque dialogue du fichier se charge sans problème.
 * Le panel valide avec ses propres descripteurs ; ce test ferme la boucle côté jeu (types
 * d'objectifs, conditions {@code QUEST_AVAILABLE} / {@code negate}, actions de remise).
 */
class LilyStoryPackEngineTest {

    private static final Path PACK = Path.of("control-panel", "src", "main", "resources", "content-packs",
            "exemple-pack-lily.json");

    @TempDir
    Path tmp;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content() throws Exception {
        Map<String, Object> pack = new Yaml().load(Files.readString(PACK));
        return (Map<String, Object>) pack.get("content");
    }

    @SuppressWarnings("unchecked")
    @Test
    void everyQuestOfThePackLoadsInTheEngine() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("quests"));
        for (Map<String, Object> quest : (List<Map<String, Object>>) content().get("quests")) {
            String key = String.valueOf(quest.get("id")).replace("rpgquest:", "");
            Files.writeString(dir.resolve(key + ".yml"), new Yaml().dump(quest));
        }

        QuestLoadReport report = new QuestLoader().loadDirectory(dir);

        assertTrue(report.issues().isEmpty(), () -> "problèmes : " + report.issues());
        assertEquals(5, report.loaded().size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void bothDialoguesOfThePackLoadInTheEngine() throws Exception {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        for (Map<String, Object> dialogue : (List<Map<String, Object>>) content().get("dialogues")) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new Yaml().dump(dialogue));
            } catch (InvalidConfigurationException e) {
                throw new AssertionError(e);
            }
            files.put(String.valueOf(dialogue.get("id")).replace("rpgquest:", "") + ".yml", yaml);
        }

        DialogueLoadReport report = new DialogueLoader(List.of()).load(files);

        assertTrue(report.issues().isEmpty(), () -> "problèmes : " + report.issues());
        assertEquals(2, report.loaded().size());
    }
}
