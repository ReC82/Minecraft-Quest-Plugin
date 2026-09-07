package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HubGuideLoaderTest {

    private final HubGuideLoader loader = new HubGuideLoader();

    @Test
    void twoDistinctHubsBothLoad() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("depart.yml", load(guide("hub_depart", "world_hub", "rpgquest:guide")));
        files.put("desert.yml", load(guide("hub_desert", "world_hub_desert", "rpgquest:guide_desert")));

        HubGuideLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertTrue(report.issues().isEmpty(), () -> "issues: " + report.issues());
    }

    @Test
    void duplicateHubIdRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(guide("hub_depart", "world_a", "rpgquest:guide")));
        files.put("b.yml", load(guide("hub_depart", "world_b", "rpgquest:guide")));

        HubGuideLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("hub-id dupliqué")));
    }

    @Test
    void aWorldClaimedByTwoHubsIsRejectedForTheSecond() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", load(guide("hub_a", "world_hub", "rpgquest:guide")));
        files.put("b.yml", load(guide("hub_b", "world_hub", "rpgquest:guide_b")));

        HubGuideLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("hub_a", report.loaded().get(0).hubId());
        assertTrue(report.issues().stream().anyMatch(i -> i.message().contains("world_hub")));
    }

    @Test
    void oneInvalidFileDoesNotBlockTheValidOnes() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("good.yml", load(guide("hub_ok", "world_ok", "rpgquest:guide")));
        files.put("bad.yml", load("welcome: \"pas de hub-id\"\n"));

        HubGuideLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertTrue(report.issues().stream().allMatch(i -> i.file().equals("bad.yml")));
    }

    private String guide(String hubId, String world, String dialogue) {
        return """
                hub-id: %s
                worlds:
                  - %s
                guide-dialogue: %s
                welcome: "Bienvenue à %s"
                referrals:
                  - role: "Quêtes"
                    npc: "le Libraire"
                    note: "Journal des quêtes."
                """.formatted(hubId, world, dialogue, hubId);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
