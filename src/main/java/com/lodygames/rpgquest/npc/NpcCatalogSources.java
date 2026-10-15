package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Extraction des contenus chargés (dialogues, quêtes, définitions de PNJ) vers les types simples
 * consommés par {@link NpcCatalog}. Partagée par le Control Panel ({@code npc.list}) et par
 * {@code /rpgadmin npc tag} (issue #66) : les deux doivent connaître <strong>le même</strong>
 * registre d'ids canoniques, sinon la commande accepterait un id que le panel signale.
 *
 * <p>Lecture en mémoire uniquement — aucun accès disque ni monde, utilisable sur le thread
 * principal (tab-complétion comprise).</p>
 */
public final class NpcCatalogSources {

    private static final String DEFAULT_NAMESPACE = "rpgquest";

    private NpcCatalogSources() {
    }

    /** Dialogues de l'espace {@code rpgquest:} ; les autres espaces ne désignent pas un PNJ. */
    public static List<NpcCatalog.DialogueLink> dialogueLinks(Collection<DialogueDefinition> dialogues) {
        List<NpcCatalog.DialogueLink> links = new ArrayList<>();
        for (DialogueDefinition d : dialogues) {
            if (!DEFAULT_NAMESPACE.equals(d.id().getNamespace())) {
                continue;
            }
            int choiceCount = 0;
            List<String> starts = new ArrayList<>();
            for (DialogueNode node : d.nodes().values()) {
                choiceCount += node.choices().size();
                node.choices().forEach(choice -> choice.actions().stream()
                        .filter(a -> a instanceof StartQuestAction)
                        .map(a -> ((StartQuestAction) a).questId().toString())
                        .forEach(q -> {
                            if (!starts.contains(q)) {
                                starts.add(q);
                            }
                        }));
            }
            links.add(new NpcCatalog.DialogueLink(d.id().getKey(), d.id().toString(),
                    d.nodes().size(), choiceCount, List.copyOf(starts), d.startNode().speaker()));
        }
        return links;
    }

    public static List<NpcCatalog.QuestLink> questLinks(Collection<QuestDefinition> quests) {
        List<NpcCatalog.QuestLink> links = new ArrayList<>();
        for (QuestDefinition q : quests) {
            List<String> talk = new ArrayList<>();
            // Issue #226 : les remises comptent aussi. Un PNJ destinataire d'un
            // DELIVER_ITEM_TO_NPC n'apparaissait dans aucune colonne du catalogue, donc le
            // supprimer rendait la quête infinissable sans qu'aucun écran ne l'ait annoncé.
            List<String> deliver = new ArrayList<>();
            for (QuestStep step : q.steps()) {
                for (QuestObjective objective : step.objectives()) {
                    if (objective instanceof TalkToNpcObjective t && !talk.contains(t.npcId())) {
                        talk.add(t.npcId());
                    }
                    if (objective instanceof DeliverItemToNpcObjective d
                            && !deliver.contains(d.npcId())) {
                        deliver.add(d.npcId());
                    }
                }
            }
            links.add(new NpcCatalog.QuestLink(q.id().toString(), q.giver(), List.copyOf(talk),
                    List.copyOf(deliver)));
        }
        return links;
    }

    public static List<NpcCatalog.LogicalDefinition> definitions(Collection<NpcDefinition> definitions) {
        List<NpcCatalog.LogicalDefinition> defs = new ArrayList<>();
        for (NpcDefinition d : definitions) {
            defs.add(new NpcCatalog.LogicalDefinition(d.id(), d.displayName(), d.description(),
                    d.dialogueId(), d.role(), d.enabled()));
        }
        return defs;
    }

    /**
     * Registre canonique (ids définis, puis ids référencés par les dialogues et les quêtes) tel
     * que le calcule {@link NpcCatalog#build}, sans les liaisons Citizens (sans effet sur ce
     * registre).
     */
    public static List<String> canonicalIds(Collection<NpcDefinition> definitions,
                                            Collection<DialogueDefinition> dialogues,
                                            Collection<QuestDefinition> quests) {
        return NpcCatalog.build(definitions(definitions), dialogueLinks(dialogues), questLinks(quests),
                List.of(), false).canonicalIds();
    }
}
