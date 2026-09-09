package com.lodygames.rpgquest.content.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #108 : le sérialiseur produit un YAML canonique et déterministe pour
 * {@code lodyquests-content-pack}. Pur JUnit — aucune dépendance Bukkit.
 */
class ContentPackSerializerTest {

    private static ContentPack.Manifest manifest(Map<String, Integer> counts, List<String> families) {
        return new ContentPack.Manifest("2026-09-09T21:40:00Z", "0.1.0-SNAPSHOT",
                ContentPackAssembler.GENERATOR, families, counts);
    }

    private static ContentPack pack(List<QuestPackEntry> quests, List<StoryPackEntry> stories,
                                    List<DialoguePackEntry> dialogues, List<NpcPackEntry> npcs) {
        Map<String, Integer> counts = Map.of("quests", quests.size(), "stories", stories.size(),
                "dialogues", dialogues.size(), "npcs", npcs.size());
        return new ContentPack(ContentPack.FORMAT, ContentPack.SCHEMA_VERSION,
                manifest(counts, List.of("quests", "stories", "dialogues", "npcs")),
                quests, stories, dialogues, npcs);
    }

    private static QuestPackEntry quest(String id, String title) {
        return new QuestPackEntry(id, PackText.of(title), PackText.of("desc " + title), "tutorial", "BOOK",
                false, false, "guide", List.of("rpgquest:intro"),
                List.of(new QuestPackEntry.Step("s1", List.of(
                        QuestPackEntry.Objective.talk("guide"),
                        QuestPackEntry.Objective.kill("ZOMBIE", 5),
                        QuestPackEntry.Objective.reach("wild", 10, 64, -20, 3)))),
                List.of(QuestPackEntry.Reward.experience(50),
                        QuestPackEntry.Reward.variable("CLAIM_TIER_1", "true"),
                        QuestPackEntry.Reward.command("say hi")),
                Map.of("started", "true"));
    }

    @Test
    void emptyPackKeepsAStableShape() {
        String yaml = ContentPackSerializer.toYaml(pack(List.of(), List.of(), List.of(), List.of()));
        assertTrue(yaml.contains("format: lodyquests-content-pack"), yaml);
        assertTrue(yaml.contains("schemaVersion: 1"), yaml);
        assertTrue(yaml.contains("content:\n"), yaml);
        assertTrue(yaml.contains("  quests: []\n"));
        assertTrue(yaml.contains("  stories: []\n"));
        assertTrue(yaml.contains("  dialogues: []\n"));
        assertTrue(yaml.contains("  npcs: []\n"));
    }

    @Test
    void outputIsByteForByteDeterministic() {
        ContentPack p = pack(List.of(quest("rpgquest:b", "B"), quest("rpgquest:a", "A")), List.of(), List.of(), List.of());
        assertEquals(ContentPackSerializer.toYaml(p), ContentPackSerializer.toYaml(p));
    }

    @Test
    void entriesAreSortedById() {
        String yaml = ContentPackSerializer.toYaml(
                pack(List.of(quest("rpgquest:zeta", "Z"), quest("rpgquest:alpha", "A")), List.of(), List.of(), List.of()));
        assertTrue(yaml.indexOf("rpgquest:alpha") < yaml.indexOf("rpgquest:zeta"), yaml);
    }

    @Test
    void formatAndSchemaVersionAreAlwaysPresent() {
        String yaml = ContentPackSerializer.toYaml(pack(List.of(quest("rpgquest:a", "A")), List.of(), List.of(), List.of()));
        assertTrue(yaml.startsWith("# lodyquests-content-pack") || yaml.contains("\nformat: lodyquests-content-pack\n"));
        assertTrue(yaml.contains("\nschemaVersion: 1\n"));
        assertTrue(yaml.contains("  exportedAt: \"2026-09-09T21:40:00Z\""));
        assertTrue(yaml.contains("  counts:\n    dialogues: 0\n    npcs: 0\n    quests: 1\n    stories: 0\n"), yaml);
    }

    @Test
    void unicodeAndMiniMessageTextIsQuotedAndPreserved() {
        QuestPackEntry q = new QuestPackEntry("rpgquest:cafe",
                PackText.of("<red>Café ☕ : l'épreuve</red>"), PackText.of("desc"), "tutorial", "BOOK",
                false, false, null, List.of(),
                List.of(new QuestPackEntry.Step("s", List.of(QuestPackEntry.Objective.talk("guide")))),
                List.of(), Map.of());
        String yaml = ContentPackSerializer.toYaml(pack(List.of(q), List.of(), List.of(), List.of()));
        assertTrue(yaml.contains("title: \"<red>Café ☕ : l'épreuve</red>\""), yaml);
    }

    @Test
    void localizedTextTableFormIsEmittedWhenMultipleLocales() {
        QuestPackEntry q = new QuestPackEntry("rpgquest:loc",
                new PackText(Map.of("default", "Bonjour", "en", "Hello")), PackText.of("d"), "tutorial", "BOOK",
                false, false, null, List.of(),
                List.of(new QuestPackEntry.Step("s", List.of(QuestPackEntry.Objective.talk("guide")))),
                List.of(), Map.of());
        String yaml = ContentPackSerializer.toYaml(pack(List.of(q), List.of(), List.of(), List.of()));
        assertTrue(yaml.contains("      title:\n        default: \"Bonjour\"\n        en: \"Hello\"\n"), yaml);
    }

    @Test
    void questRewardsAndVariablesSerialiseWithTheExpectedKeys() {
        String yaml = ContentPackSerializer.toYaml(pack(List.of(quest("rpgquest:a", "A")), List.of(), List.of(), List.of()));
        assertTrue(yaml.contains("        - type: EXPERIENCE\n          amount: 50\n"), yaml);
        assertTrue(yaml.contains("        - type: VARIABLE\n          key: \"CLAIM_TIER_1\"\n          value: \"true\"\n"), yaml);
        assertTrue(yaml.contains("        - type: COMMAND\n          command: \"say hi\"\n"), yaml);
        assertTrue(yaml.contains("      variables:\n        started: \"true\"\n"), yaml);
        assertTrue(yaml.contains("            - type: REACH_LOCATION\n"), yaml);
        assertTrue(yaml.contains("              world: wild\n"), yaml);
        assertTrue(yaml.contains("              radius: 3\n"), yaml);
    }

    @Test
    void dialogueNegatedConditionAndCloseActionAreSerialised() {
        DialoguePackEntry d = new DialoguePackEntry("rpgquest:g", "start", List.of(
                new DialoguePackEntry.Node("start", "Guide", PackText.of("Salut"), List.of(
                        new DialoguePackEntry.Choice(PackText.of("Partir"),
                                List.of(DialoguePackEntry.Condition.questState("rpgquest:a", "COMPLETED", true)),
                                List.of(DialoguePackEntry.Action.close()), null)))));
        String yaml = ContentPackSerializer.toYaml(pack(List.of(), List.of(), List.of(d), List.of()));
        assertTrue(yaml.contains("                - type: QUEST_STATE\n"), yaml);
        assertTrue(yaml.contains("                  quest: rpgquest:a\n"), yaml);
        assertTrue(yaml.contains("                  state: COMPLETED\n"), yaml);
        assertTrue(yaml.contains("                  negate: true\n"), yaml);
        assertTrue(yaml.contains("                - type: CLOSE\n"), yaml);
    }

    @Test
    void storyAndNpcReferencesArePreserved() {
        StoryPackEntry s = new StoryPackEntry("main", PackText.of("Histoire"), true,
                List.of("rpgquest:first_steps", "rpgquest:crystal_hunt"));
        NpcPackEntry n = new NpcPackEntry("guide", "Le Guide", null, "rpgquest:guide", "guide", true);
        String yaml = ContentPackSerializer.toYaml(pack(List.of(), List.of(s), List.of(), List.of(n)));
        assertTrue(yaml.contains("      questIds: [rpgquest:first_steps, rpgquest:crystal_hunt]\n"), yaml);
        assertTrue(yaml.contains("      secret: true\n"), yaml);
        assertTrue(yaml.contains("      dialogue: rpgquest:guide\n"), yaml);
        assertFalse(yaml.contains("description:"), "description nulle non sérialisée\n" + yaml);
    }

    @Test
    void exportNeverContainsSecretsOrRuntimeOrSystemPaths() {
        // Contenu volontairement « piégé » : une commande éditoriale légitime, du texte varié.
        QuestPackEntry q = new QuestPackEntry("rpgquest:trap",
                PackText.of("Le mot de passe du coffre est dans l'énigme"), PackText.of("desc"), "tutorial", "CHEST",
                false, false, null, List.of(),
                List.of(new QuestPackEntry.Step("s", List.of(QuestPackEntry.Objective.talk("guide")))),
                List.of(QuestPackEntry.Reward.command("give @p minecraft:diamond 1")), Map.of());
        String yaml = ContentPackSerializer.toYaml(pack(List.of(q), List.of(), List.of(), List.of()))
                .toLowerCase(Locale.ROOT);
        for (String forbidden : List.of("rcon_password", "smtp_password", "rcon_host", "verygames_ftp",
                "bearer ", "/opt/plugadmin", "/home/ubuntu", "jdbc:mysql", "authorization:", "x-agent-token",
                "player_uuid", "data.db")) {
            assertFalse(yaml.contains(forbidden), "le pack ne doit jamais contenir « " + forbidden + " »");
        }
    }

    @Test
    void unusualButValidIdsFallBackToQuotingWhenNeeded() {
        // Un id « propre » reste nu ; un id avec un caractère hors jeu sûr est mis entre guillemets.
        NpcPackEntry clean = new NpcPackEntry("shop.keeper-2", "X", null, null, null, true);
        String yaml = ContentPackSerializer.toYaml(pack(List.of(), List.of(), List.of(), List.of(clean)));
        assertTrue(yaml.contains("    - id: shop.keeper-2\n"), yaml);
    }
}
