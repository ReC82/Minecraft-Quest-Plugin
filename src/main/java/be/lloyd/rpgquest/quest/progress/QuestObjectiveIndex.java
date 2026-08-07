package be.lloyd.rpgquest.quest.progress;

import be.lloyd.rpgquest.quest.model.BreakBlockObjective;
import be.lloyd.rpgquest.quest.model.CollectItemObjective;
import be.lloyd.rpgquest.quest.model.CraftItemObjective;
import be.lloyd.rpgquest.quest.model.KillEntityObjective;
import be.lloyd.rpgquest.quest.model.ObjectiveType;
import be.lloyd.rpgquest.quest.model.PlaceBlockObjective;
import be.lloyd.rpgquest.quest.model.QuestDefinition;
import be.lloyd.rpgquest.quest.model.QuestObjective;
import be.lloyd.rpgquest.quest.model.QuestStep;
import be.lloyd.rpgquest.quest.model.ReachLocationObjective;
import be.lloyd.rpgquest.quest.model.TalkToNpcObjective;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Index construit une seule fois par ensemble de quêtes chargé (pas par
 * événement, pas par joueur) : pour un événement donné (matériau cassé,
 * entité tuée, ...), retourne directement les {@link ObjectiveRef} qui
 * pourraient correspondre, sans balayer toutes les quêtes chargées.
 * {@code REACH_LOCATION} est indexé par nom de monde (filtre grossier) ; la
 * vérification de distance reste à faire par l'appelant sur les quelques
 * candidats retournés.
 */
public final class QuestObjectiveIndex {

    private final Map<Material, List<ObjectiveRef>> breakBlock = new HashMap<>();
    private final Map<Material, List<ObjectiveRef>> placeBlock = new HashMap<>();
    private final Map<EntityType, List<ObjectiveRef>> killEntity = new HashMap<>();
    private final Map<Material, List<ObjectiveRef>> collectItem = new HashMap<>();
    private final Map<Material, List<ObjectiveRef>> craftItem = new HashMap<>();
    private final Map<String, List<ObjectiveRef>> talkToNpc = new HashMap<>();
    private final Map<String, List<ObjectiveRef>> reachLocationByWorld = new HashMap<>();

    public QuestObjectiveIndex(List<QuestDefinition> quests) {
        for (QuestDefinition quest : quests) {
            List<QuestStep> steps = quest.steps();
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                QuestStep step = steps.get(stepIndex);
                List<QuestObjective> objectives = step.objectives();
                for (int i = 0; i < objectives.size(); i++) {
                    index(new ObjectiveRef(quest.id(), stepIndex, step.id(), i, objectives.get(i)));
                }
            }
        }
    }

    private void index(ObjectiveRef ref) {
        switch (ref.objective()) {
            case BreakBlockObjective o -> add(breakBlock, o.material(), ref);
            case PlaceBlockObjective o -> add(placeBlock, o.material(), ref);
            case KillEntityObjective o -> add(killEntity, o.entity(), ref);
            case CollectItemObjective o -> add(collectItem, o.material(), ref);
            case CraftItemObjective o -> add(craftItem, o.material(), ref);
            case TalkToNpcObjective o -> add(talkToNpc, o.npcId(), ref);
            case ReachLocationObjective o -> add(reachLocationByWorld, o.world(), ref);
        }
    }

    private <K> void add(Map<K, List<ObjectiveRef>> map, K key, ObjectiveRef ref) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(ref);
    }

    public List<ObjectiveRef> breakBlock(Material material) {
        return breakBlock.getOrDefault(material, List.of());
    }

    public List<ObjectiveRef> placeBlock(Material material) {
        return placeBlock.getOrDefault(material, List.of());
    }

    public List<ObjectiveRef> killEntity(EntityType entityType) {
        return killEntity.getOrDefault(entityType, List.of());
    }

    public List<ObjectiveRef> collectItem(Material material) {
        return collectItem.getOrDefault(material, List.of());
    }

    public List<ObjectiveRef> craftItem(Material material) {
        return craftItem.getOrDefault(material, List.of());
    }

    public List<ObjectiveRef> talkToNpc(String npcId) {
        return talkToNpc.getOrDefault(npcId, List.of());
    }

    public List<ObjectiveRef> reachLocation(String world) {
        return reachLocationByWorld.getOrDefault(world, List.of());
    }

    public boolean isEmpty(ObjectiveType type) {
        return switch (type) {
            case BREAK_BLOCK -> breakBlock.isEmpty();
            case PLACE_BLOCK -> placeBlock.isEmpty();
            case KILL_ENTITY -> killEntity.isEmpty();
            case COLLECT_ITEM -> collectItem.isEmpty();
            case CRAFT_ITEM -> craftItem.isEmpty();
            case TALK_TO_NPC -> talkToNpc.isEmpty();
            case REACH_LOCATION -> reachLocationByWorld.isEmpty();
        };
    }
}
