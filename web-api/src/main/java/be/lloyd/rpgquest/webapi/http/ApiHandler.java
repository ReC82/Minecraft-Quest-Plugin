package be.lloyd.rpgquest.webapi.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Traite une requête déjà validée (rate limit et auth passés) et écrit la réponse ; retourne le statut envoyé, pour le journal. */
@FunctionalInterface
public interface ApiHandler {

    int handle(HttpExchange exchange) throws IOException;
}
