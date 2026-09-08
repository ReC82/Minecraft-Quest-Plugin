package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou round-trip #46 (B12) : le YAML émis par {@link QuestYaml} / {@link StoryYaml} doit se
 * relire à l'identique avec le mini-lecteur, faute de quoi l'éditeur refuse d'écrire dans la source.
 */
class ContentYamlRoundTripTest {

    private static Map<String, String> obj(String kind, String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kind", kind);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void minimalQuestRoundTrips() {
        QuestDraft q = QuestDraft.blank();
        q.id = "test_quest";
        q.title = "Une quête d'essai";
        q.description = "Description d'essai.";
        q.steps.get(0).objectives.get(0).put("entity", "SPIDER");
        q.steps.get(0).objectives.get(0).put("amount", "5");

        String yaml = QuestYaml.write(q);
        assertTrue(QuestYaml.roundTripProblems(yaml).isEmpty(), () -> "problèmes: " + QuestYaml.roundTripProblems(yaml)
                + "\n---\n" + yaml);

        QuestYaml.ReadResult r = QuestYaml.read(yaml);
        assertEquals("test_quest", r.draft().id);
        assertEquals("Une quête d'essai", r.draft().title);
        assertEquals("SPIDER", r.draft().steps.get(0).objectives.get(0).get("entity"));
        assertEquals("5", r.draft().steps.get(0).objectives.get(0).get("amount"));
    }

    @Test
    void richQuestRoundTrips() {
        QuestDraft q = new QuestDraft();
        q.id = "rpgquest:crystal_hunt_v2";
        q.title = "<gold>La chasse aux cristaux</gold>";
        q.description = "<gray>Ramener des crocs.</gray>";
        q.category = "crafting";
        q.giver = "guard";
        q.icon = "AMETHYST_SHARD";
        q.repeatable = false;
        q.prerequisites.add("first_steps");

        QuestDraft.Step s1 = new QuestDraft.Step("hunt_spiders");
        s1.objectives.add(obj("KILL_ENTITY", "entity", "SPIDER", "amount", "5"));
        QuestDraft.Step s2 = new QuestDraft.Step("gather");
        s2.objectives.add(obj("COLLECT_ITEM", "material", "AMETHYST_SHARD", "amount", "2"));
        s2.objectives.add(obj("TALK_TO_NPC", "npc", "guard"));
        q.steps.add(s1);
        q.steps.add(s2);

        q.rewards.add(obj("EXPERIENCE", "amount", "100"));
        q.rewards.add(obj("COMMAND", "command", "customitem give %player% rpgquest:miner_pickaxe 1"));
        q.rewards.add(obj("VARIABLE", "key", "CLAIM_TIER_1", "value", "true"));
        q.variables.put("crystal_hunt_started", "true");

        String yaml = QuestYaml.write(q);
        assertTrue(QuestYaml.roundTripProblems(yaml).isEmpty(),
                () -> "problèmes: " + QuestYaml.roundTripProblems(yaml) + "\n---\n" + yaml);

        QuestYaml.ReadResult r = QuestYaml.read(yaml);
        assertEquals(2, r.draft().steps.size());
        assertEquals(2, r.draft().steps.get(1).objectives.size());
        assertEquals(3, r.draft().rewards.size());
        assertEquals("true", r.draft().variables.get("crystal_hunt_started"));
        assertEquals("first_steps", r.draft().prerequisites.get(0));
    }

    @Test
    void storyRoundTrips() {
        StoryDraft s = new StoryDraft();
        s.id = "main_story_v2";
        s.name = "Histoire principale";
        s.questIds.add("premiers_pas");
        s.questIds.add("rpgquest:first_steps");
        s.questIds.add("crystal_hunt");

        String yaml = StoryYaml.write(s);
        assertTrue(StoryYaml.roundTripProblems(yaml).isEmpty(),
                () -> "problèmes: " + StoryYaml.roundTripProblems(yaml) + "\n---\n" + yaml);

        StoryYaml.ReadResult r = StoryYaml.read(yaml);
        assertEquals("main_story_v2", r.draft().id);
        assertEquals("Histoire principale", r.draft().name);
        assertEquals(3, r.draft().questIds.size());
        assertEquals("first_steps", r.draft().questIds.get(1));
    }

    @Test
    void validatorFlagsMissingTitleAndUnknownRefs() {
        QuestDraft q = QuestDraft.blank();
        q.id = "x";
        // titre / description manquants
        RefData ref = new RefData(java.util.List.of("first_steps"), java.util.List.of("guard"),
                java.util.List.of("world"), true, true, true);
        q.prerequisites.add("does_not_exist");
        var diags = QuestValidator.validate(q, ref);
        assertTrue(Diagnostic.hasError(diags));
        assertTrue(diags.stream().anyMatch(d -> d.field().equals("title") && d.level() == Diagnostic.Level.ERROR));
        assertTrue(diags.stream().anyMatch(d -> d.message().contains("does_not_exist")));
    }
}
