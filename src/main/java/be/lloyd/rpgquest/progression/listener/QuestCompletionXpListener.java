package be.lloyd.rpgquest.progression.listener;

import be.lloyd.rpgquest.config.ProgressionConfig;
import be.lloyd.rpgquest.progression.ProgressionService;
import be.lloyd.rpgquest.progression.model.SkillType;
import be.lloyd.rpgquest.quest.model.QuestState;
import be.lloyd.rpgquest.quest.progress.QuestProgressEngine;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Accorde une XP RPG (piste {@code GLOBAL}) une seule fois par quête
 * terminée, en plus de l'éventuelle récompense {@code experience} vanilla de
 * la quête (mission point 10 : les deux systèmes restent indépendants). Ne
 * modifie rien dans le package {@code quest} : branché sur {@link
 * QuestProgressEngine#onProgressChanged}, qui notifie après <b>tout</b>
 * changement de progression (pas seulement une fin de quête) — {@link
 * #onProgressChanged} reparcourt donc l'ensemble des états à chaque appel et
 * laisse la déduplication de {@code ProgressionService#awardXp} (id
 * d'événement = id de quête) garantir qu'une quête déjà récompensée ne l'est
 * plus jamais, même rappelée plusieurs fois.
 */
public final class QuestCompletionXpListener {

    private final ProgressionService progression;
    private final QuestProgressEngine questProgressEngine;
    private final Supplier<ProgressionConfig> config;
    private final Logger logger;

    public QuestCompletionXpListener(ProgressionService progression, QuestProgressEngine questProgressEngine,
                                      Supplier<ProgressionConfig> config, Logger logger) {
        this.progression = progression;
        this.questProgressEngine = questProgressEngine;
        this.config = config;
        this.logger = logger;
    }

    public void onProgressChanged(UUID playerId) {
        int questCompletionXp = config.get().questCompletionXp();
        if (questCompletionXp <= 0) {
            return;
        }
        questProgressEngine.allStates(playerId).thenAccept(states -> {
            for (var entry : states.entrySet()) {
                if (entry.getValue() == QuestState.COMPLETED) {
                    NamespacedKey questId = entry.getKey();
                    progression.awardXp(playerId, SkillType.GLOBAL, questCompletionXp,
                            "quest_completion", "quest_complete:" + questId);
                }
            }
        }).exceptionally(error -> {
            logger.error("Échec de la vérification des quêtes terminées pour l'XP de progression ({}).", playerId, error);
            return null;
        });
    }
}
