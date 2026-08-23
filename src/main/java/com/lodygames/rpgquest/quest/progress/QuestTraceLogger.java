package com.lodygames.rpgquest.quest.progress;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * TODO(debug bug BREAK_BLOCK wild) : instrumentation temporaire, à retirer une fois la cause
 * confirmée — voir {@link QuestProgressEngine#traceBreakBlockChain}, seul appelant. Même conception
 * que {@code travel.TpTraceLogger} de l'investigation précédente : un format centralisé dans une
 * seule petite classe, pour n'avoir qu'un seul endroit à supprimer plus tard.
 */
final class QuestTraceLogger {

    private QuestTraceLogger() {
    }

    static void logBreakBlock(Logger logger, String playerName, UUID playerId, String world, String material,
                               List<String> activeBreakBlockQuests, List<String> candidates,
                               List<String> evaluations, String outcome) {
        logger.info("[QUEST-TRACE] player={} uuid={} world={} material={} active_break_block_quests={} "
                        + "candidates={} evaluations={} outcome={}",
                playerName, playerId, world, material, activeBreakBlockQuests, candidates, evaluations, outcome);
    }
}
