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

    private static String dash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
