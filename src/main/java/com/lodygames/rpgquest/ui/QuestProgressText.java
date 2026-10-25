package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.npc.NpcNameTokens;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;

/**
 * Rend l'avancement d'une quête en texte MiniMessage, pour le jeton de dialogue
 * {@code %quest_progress:<quête>%} (issue #841) — ce qu'un PNJ répond à « Où en suis-je ? » :
 *
 * <pre>
 * Gauthier — Palier 1
 * • Araignée 3/5
 * • Zombie 5/5
 * </pre>
 *
 * <p>Générique : aucune quête ni aucun PNJ n'est nommé ici. Les lignes sont celles du journal
 * ({@link ObjectiveLabels#counterLine}), lues sur la même vue de progression
 * ({@code QuestProgressEngine#activeStepView}) : le PNJ et le journal affichent toujours les mêmes
 * compteurs, reconnexion et redémarrage compris (les deux relisent la progression persistée).</p>
 *
 * <p>La phrase qui entoure ce bloc (« Voilà où tu en es », ce qui se passe à la fin) vit dans le
 * texte du nœud, donc reste éditable depuis le Control Panel — même convention que
 * {@code %delivery_status%}.</p>
 */
public final class QuestProgressText {

    /** La quête existe, mais n'est pas en cours pour ce joueur (pas commencée, terminée, abandonnée). */
    public static final String NOT_IN_PROGRESS = "<gray>Cette quête n'est pas en cours.</gray>";

    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private QuestProgressText() {
    }

    /**
     * Identifiant de quête écrit dans le jeton : {@code guard_tier1} (espace {@code rpgquest} par
     * défaut, comme les autres contenus) ou {@code rpgquest:guard_tier1}. {@code null} si invalide.
     */
    public static NamespacedKey questKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return NamespacedKey.fromString(raw.contains(":") ? raw : DEFAULT_NAMESPACE + ":" + raw);
    }

    /**
     * @param quest    définition de la quête, {@code null} si inconnue
     * @param view     étape courante et compteurs du joueur, vide si la quête n'est pas en cours
     * @param npcNames noms affichés actuels des PNJ (cibles « parler à », remises, titre)
     * @return le bloc MiniMessage, ou {@code null} pour une quête inconnue (le jeton reste alors
     *         visible tel quel, comme une ville ou un PNJ inconnu — une faute de frappe se voit)
     */
    public static String render(QuestDefinition quest, Optional<QuestStepProgressView> view,
                                Function<String, Optional<String>> npcNames) {
        if (quest == null) {
            return null;
        }
        if (view.isEmpty()) {
            return NOT_IN_PROGRESS;
        }
        StringBuilder out = new StringBuilder(title(quest, npcNames));
        int stepIndex = stepIndex(quest, view.get().stepId());
        if (quest.steps().size() > 1 && stepIndex >= 0) {
            out.append(" <gray>— étape ").append(stepIndex + 1).append('/').append(quest.steps().size())
                    .append("</gray>");
        }
        List<QuestObjective> objectives = stepIndex >= 0 ? quest.steps().get(stepIndex).objectives() : List.of();
        for (int i = 0; i < view.get().objectives().size(); i++) {
            out.append("<newline>").append(MM.serialize(ObjectiveLabels.counterLine(view.get().objectives().get(i),
                    i < objectives.size() ? objectives.get(i) : null, npcNames)));
        }
        return out.toString();
    }

    /** Titre MiniMessage de la quête ({@code %npc_name:<id>%} résolu), son id s'il n'en a pas. */
    private static String title(QuestDefinition quest, Function<String, Optional<String>> npcNames) {
        String raw = quest.title() == null ? null : quest.title().base();
        if (raw == null || raw.isBlank()) {
            return "<gold>" + MM.escapeTags(quest.id().value()) + "</gold>";
        }
        return NpcNameTokens.resolve(raw, npcNames);
    }

    private static int stepIndex(QuestDefinition quest, String stepId) {
        List<QuestStep> steps = quest.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }
}
