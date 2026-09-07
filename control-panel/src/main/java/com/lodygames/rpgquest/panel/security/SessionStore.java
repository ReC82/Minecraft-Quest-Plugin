package com.lodygames.rpgquest.panel.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stockage des sessions en mémoire (V1). Un identifiant aléatoire de 256 bits par session ;
 * expiration absolue ({@code ttl}) et sur inactivité ({@code idle}).
 *
 * <p>Le cookie navigateur ne contient pas l'id nu mais <code>&lt;id&gt;.&lt;hmac&gt;</code> signé
 * avec {@code sessionSecret} : un id volé/forgé sans le secret est rejeté avant même la recherche
 * en mémoire ({@link #resolve}).</p>
 */
public final class SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;
    private final Duration ttl;
    private final Duration idle;

    public SessionStore(String sessionSecret, Duration ttl, Duration idle) {
        this.secret = sessionSecret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.idle = idle;
    }

    public Session create(String username, String role) {
        Instant now = Instant.now();
        Session session = new Session(randomToken(), username, role, randomToken(), now);
        sessions.put(session.id(), session);
        return session;
    }

    /** Valeur à poser dans le cookie : {@code <id>.<hmac base64url>}. */
    public String signedCookieValue(Session session) {
        return session.id() + "." + base64Url(hmac(session.id()));
    }

    /** Résout une session depuis la valeur de cookie signée, en vérifiant la signature et l'expiration. */
    public Optional<Session> resolve(String cookieValue) {
        if (cookieValue == null) {
            return Optional.empty();
        }
        int dot = cookieValue.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String id = cookieValue.substring(0, dot);
        String sig = cookieValue.substring(dot + 1);
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                base64Url(hmac(id)).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        Session session = sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (now.isAfter(session.createdAt().plus(ttl)) || now.isAfter(session.lastSeenAt().plus(idle))) {
            sessions.remove(id);
            return Optional.empty();
        }
        session.touch(now);
        return Optional.of(session);
    }

    public void invalidate(String id) {
        sessions.remove(id);
    }

    public int activeCount() {
        return sessions.size();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64Url(bytes);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC indisponible", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
