package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.DiscoverWaypointObjective;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.ObjectiveType;
import com.lodygames.rpgquest.quest.model.PlaceBlockObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.SmeltItemObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
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
    private final Map<String, List<ObjectiveRef>> deliverToNpc = new HashMap<>();
    private final Map<Material, List<ObjectiveRef>> smeltItem = new HashMap<>();
    /**
     * Issue #185 : aucune clé naturelle ne découpe ces objectifs (une découverte ne porte ni
     * matériau ni entité), et le filtre de mondes est optionnel. La liste reste donc plate — elle
     * n'est parcourue que lors d'une PREMIÈRE découverte, un événement rare, jamais à chaque
     * déplacement.
     */
    private final List<ObjectiveRef> discoverWaypoint = new ArrayList<>();

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
            case DeliverItemToNpcObjective o -> add(deliverToNpc, o.npcId(), ref);
            case SmeltItemObjective o -> add(smeltItem, o.material(), ref);
            case DiscoverWaypointObjective o -> discoverWaypoint.add(ref);
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

    /**
     * Objectifs de remise (issue #123) dont {@code npcId} est le destinataire — dans l'ordre de
     * déclaration par quête puis par étape, ce qui donne au dialogue un récapitulatif stable.
     * Un PNJ qui n'est destinataire de rien renvoie une liste vide : c'est exactement ce qui fait
     * qu'un mauvais PNJ ne peut jamais accepter une remise.
     */
    public List<ObjectiveRef> deliverToNpc(String npcId) {
        return deliverToNpc.getOrDefault(npcId, List.of());
    }

    /** Objectifs de cuisson (issue #141) portant sur l'objet OBTENU après cuisson. */
    public List<ObjectiveRef> smeltItem(Material material) {
        return smeltItem.getOrDefault(material, List.of());
    }

    /** Objectifs de découverte de waypoints (issue #185) — l'appelant applique le filtre de mondes. */
    public List<ObjectiveRef> discoverWaypoint() {
        return List.copyOf(discoverWaypoint);
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
            case DELIVER_ITEM_TO_NPC -> deliverToNpc.isEmpty();
            case SMELT_ITEM -> smeltItem.isEmpty();
            case DISCOVER_WAYPOINT -> discoverWaypoint.isEmpty();
        };
    }
}
