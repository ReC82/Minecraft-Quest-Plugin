package com.lodygames.rpgquest.dialogue.model;

import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.util.List;

/**
 * {@code next}, s'il est présent, redirige vers un autre nœud du même
 * dialogue une fois les actions exécutées. Si les actions contiennent un
 * {@link CloseAction} ou un {@link OpenDialogueAction}, cette transition
 * prend le pas sur {@code next} (arrêt anticipé du traitement des actions).
 * Sans {@code next} ni action terminale, le dialogue se ferme par défaut —
 * jamais de nœud « bloqué » sans issue.
 */
public record DialogueChoice(
        LocalizedText text,
        List<DialogueCondition> conditions,
        List<DialogueAction> actions,
        String next
) {

    public DialogueChoice {
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }
}
