package com.lodygames.rpgquest.panel.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hachage de mot de passe PBKDF2-HMAC-SHA256, sans dépendance externe. Format encodé, inspiré de
 * Django : {@code pbkdf2_sha256$<iterations>$<base64 salt>$<base64 hash>}.
 *
 * <ul>
 *   <li>{@link #hash(String)} — génère un sel aléatoire (16 octets) et un dérivé de 32 octets.</li>
 *   <li>{@link #verify(String, String)} — comparaison en temps constant.</li>
 * </ul>
 *
 * Utilisé pour l'unique compte owner de la V1 : le hash vit dans {@code RPGQUEST_PANEL_OWNER_HASH}
 * (environnement), jamais le mot de passe en clair, jamais dans le dépôt.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int DEFAULT_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = pbkdf2(password.toCharArray(), salt, DEFAULT_ITERATIONS);
        return PREFIX + "$" + DEFAULT_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    public boolean verify(String password, String encoded) {
        if (encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (RuntimeException e) {
            return false;
        }
        byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 indisponible", e);
        }
    }
}
