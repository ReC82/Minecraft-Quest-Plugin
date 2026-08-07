package be.lloyd.rpgquest.webapi.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authentification serveur-à-serveur (mission étape 21, point 5) : jeton
 * partagé transmis en {@code Authorization: Bearer <token>}. Comparaison en
 * temps constant ({@link MessageDigest#isEqual}) pour ne pas faciliter une
 * attaque par mesure de temps. Un jeton attendu absent/vide ferme l'accès
 * (fail-closed) plutôt que d'ouvrir l'API par défaut.
 */
public final class AuthFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedToken;

    public AuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    public boolean isAuthorized(String authorizationHeader) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return false;
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String provided = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
    }
}
