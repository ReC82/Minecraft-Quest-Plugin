package com.lodygames.rpgquest.travel;

import java.util.UUID;
import org.slf4j.Logger;

/**
 * TODO(debug bug TP hub) : instrumentation temporaire, à retirer une fois la cause confirmée — voir
 * {@code WorldPortalTeleportListener}/{@code PortalService}, les deux seuls appelants.
 *
 * <p>Format unique et centralisé pour toutes les lignes {@code [TP-TRACE]} du plugin, quelle que
 * soit la classe d'origine (démarrage volontairement centralisé plutôt que des chaînes ad hoc
 * dispersées dans chaque classe : un seul endroit à retirer plus tard, et un format grep-able
 * garanti identique partout). Chaque champ absent pour un événement donné (ex. {@code inside} pour
 * un {@code channel_start}, qui ne concerne pas une transition de zone) est rendu {@code "-"}
 * plutôt qu'omis, pour que la position des colonnes reste stable dans les logs.</p>
 */
final class TpTraceLogger {

    private TpTraceLogger() {
    }

    static void log(Logger logger, String event, UUID playerId, String playerName, String portalId,
                     String world, int x, int y, int z,
                     Boolean inside, Boolean previousInside,
                     Object grace, Object channel, String from, String destination) {
        logger.info("[TP-TRACE] uuid={} player={} event={} portal={} world={} x={} y={} z={} "
                        + "inside={} previousInside={} grace={} channel={} from={} destination={}",
                playerId, playerName, event, dash(portalId), dash(world), x, y, z,
                dash(inside), dash(previousInside), dash(grace), dash(channel), dash(from), dash(destination));
    }

    /**
     * Diagnostic de latence du passage d'un portail simple (issue #161) : une ligne par
     * téléportation, avec les trois étapes distinguées — évaluation des colonnes candidates,
     * chargement/génération des chunks, téléportation elle-même. Format séparé de {@link #log}
     * (colonnes différentes, durée de vie différente : cette mesure survit à l'instrumentation
     * temporaire {@code [TP-TRACE]} ci-dessus) mais même principe de ligne unique grep-able.
     */
    static void logLatency(Logger logger, UUID playerId, String playerName, String portalId, String world,
                            String strategy, int attempts,
                            long searchMs, long chunkMs, long teleportMs, long totalMs) {
        logger.info("[TP-LATENCY] uuid={} player={} portal={} world={} strategy={} attempts={} "
                        + "search_ms={} chunks_ms={} teleport_ms={} total_ms={}",
                playerId, playerName, dash(portalId), dash(world), dash(strategy), attempts,
                searchMs, chunkMs, teleportMs, totalMs);
    }

    /** Conversion unique nanosecondes → millisecondes (arrondi bas), pour que toutes les lignes aient la même unité. */
    static long toMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String dash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
