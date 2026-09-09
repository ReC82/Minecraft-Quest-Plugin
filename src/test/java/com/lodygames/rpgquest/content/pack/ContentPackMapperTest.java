package com.lodygames.rpgquest.content.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import com.lodygames.rpgquest.quest.model.VariableReward;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Issue #108 : {@code ContentPackMapper} recopie fidèlement chaque champ déclaratif des modèles
 * RPGQuest dans les DTO du pack. Pur JUnit — les enums Bukkit ({@code Material}, {@code EntityType})
 * et {@code NamespacedKey} n'ont pas besoin d'un serveur.
 */
class ContentPackMapperTest {

    private static NamespacedKey key(String v) {
        return NamespacedKey.fromString(v);
    }

    @Test
    void questMappingCoversEveryObjectiveAndRewardType() {
        QuestDefinition def = new QuestDefinition(
                key("rpgquest:demo"),
                new LocalizedText(Map.of("default", "Démo", "en", "Demo")),
                LocalizedText.of("Une <red>démo</red>"),
                "tutorial",
                Material.IRON_SWORD,
                true,
                true,
                List.of(key("rpgquest:intro")),
                List.of(new QuestStep("s1", List.of(
                        new TalkToNpcObjective("guide"),
                        new KillEntityObjective(EntityType.ZOMBIE, 5),
                        new BreakBlockObjective(Material.STONE, 12),
                        new ReachLocationObjective("wild", 1.0, 64.0, -2.0, 3.5)))),
                List.of((QuestReward) new ExperienceReward(50),
                        new VariableReward("CLAIM_TIER_1", "true"),
                        new CommandReward("say gg")),
                Map.of("k", "v"),
                "guide");

        QuestPackEntry e = ContentPackMapper.toQuestEntry(def);
        assertEquals("rpgquest:demo", e.id());
        assertEquals("Démo", e.title().base());
        assertEquals("Demo", e.title().byLocale().get("en"));
        assertEquals("tutorial", e.category());
        assertEquals("IRON_SWORD", e.icon());
        assertTrue(e.repeatable());
        assertTrue(e.secret());
        assertEquals("guide", e.giver());
        assertEquals(List.of("rpgquest:intro"), e.prerequisites());
        assertEquals(Map.of("k", "v"), e.variables());

        List<QuestPackEntry.Objective> objs = e.steps().get(0).objectives();
        assertEquals("TALK_TO_NPC", objs.get(0).type());
        assertEquals("guide", objs.get(0).npc());
        assertEquals("KILL_ENTITY", objs.get(1).type());
        assertEquals("ZOMBIE", objs.get(1).entity());
        assertEquals(5, objs.get(1).amount());
        assertEquals("BREAK_BLOCK", objs.get(2).type());
        assertEquals("STONE", objs.get(2).material());
        assertEquals("REACH_LOCATION", objs.get(3).type());
        assertEquals("wild", objs.get(3).world());
        assertEquals(3.5, objs.get(3).radius());

        assertEquals("EXPERIENCE", e.rewards().get(0).type());
        assertEquals(50, e.rewards().get(0).amount());
        assertEquals("VARIABLE", e.rewards().get(1).type());
        assertEquals("CLAIM_TIER_1", e.rewards().get(1).key());
        assertEquals("true", e.rewards().get(1).value());
        assertEquals("COMMAND", e.rewards().get(2).type());
        assertEquals("say gg", e.rewards().get(2).command());
    }

    @Test
    void storyMappingKeepsOrderedQuestReferences() {
        StoryDefinition def = new StoryDefinition("main", LocalizedText.of("Histoire"),
                List.of(key("rpgquest:first_steps"), key("rpgquest:crystal_hunt")), true);
        StoryPackEntry e = ContentPackMapper.toStoryEntry(def);
        assertEquals("main", e.id());
        assertTrue(e.secret());
        assertEquals(List.of("rpgquest:first_steps", "rpgquest:crystal_hunt"), e.questIds());
    }

    @Test
    void dialogueMappingUnwrapsNegatedConditionsAndTypesActions() {
        DialogueChoice choice = new DialogueChoice(
                LocalizedText.of("Partir"),
                List.of(new NegatedCondition(new QuestStateCondition(key("rpgquest:a"), QuestState.COMPLETED))),
                List.of(new StartQuestAction(key("rpgquest:a")), new CloseAction()),
                null);
        DialogueDefinition def = new DialogueDefinition(key("rpgquest:g"), "start",
                Map.of("start", new DialogueNode("start", "Guide", LocalizedText.of("Salut"), List.of(choice))));

        DialoguePackEntry e = ContentPackMapper.toDialogueEntry(def);
        assertEquals("rpgquest:g", e.id());
        assertEquals("start", e.start());
        DialoguePackEntry.Choice c = e.nodes().get(0).choices().get(0);
        assertEquals("QUEST_STATE", c.conditions().get(0).type());
        assertEquals("rpgquest:a", c.conditions().get(0).quest());
        assertEquals("COMPLETED", c.conditions().get(0).state());
        assertTrue(c.conditions().get(0).negate());
        assertEquals("START_QUEST", c.actions().get(0).type());
        assertEquals("rpgquest:a", c.actions().get(0).quest());
        assertEquals("CLOSE", c.actions().get(1).type());
        assertNull(c.next());
    }

    @Test
    void npcMappingPreservesNullsForOptionalFields() {
        NpcPackEntry e = ContentPackMapper.toNpcEntry(new NpcDefinition("jo", "Jo", null, null, null, false));
        assertEquals("jo", e.id());
        assertEquals("Jo", e.displayName());
        assertNull(e.description());
        assertNull(e.dialogue());
        assertNull(e.role());
        assertTrue(!e.enabled());

        NpcPackEntry full = ContentPackMapper.toNpcEntry(
                new NpcDefinition("guide", "Le Guide", "PNJ d'accueil", "rpgquest:guide", "guide", true));
        assertEquals("rpgquest:guide", full.dialogue());
        assertEquals("guide", full.role());
        assertEquals("PNJ d'accueil", full.description());
    }
}
