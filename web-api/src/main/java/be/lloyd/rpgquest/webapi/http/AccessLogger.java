package be.lloyd.rpgquest.webapi.http;

import java.util.logging.Logger;

/**
 * Journal d'accès (mission point 6). N'écrit jamais l'en-tête
 * {@code Authorization} ni aucun jeton — seuls méthode, chemin, IP, statut et
 * durée sont journalisés (mission, test manuel "aucun secret n'apparaît...
 * dans les logs").
 */
public final class AccessLogger {

    private final Logger logger;

    public AccessLogger(Logger logger) {
        this.logger = logger;
    }

    public void log(String method, String path, String remoteIp, int status, long durationMillis) {
        logger.info(() -> method + " " + path + " ip=" + remoteIp + " status=" + status + " " + durationMillis + "ms");
    }
}
