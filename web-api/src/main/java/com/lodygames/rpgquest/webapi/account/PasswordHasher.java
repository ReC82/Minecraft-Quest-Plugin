package com.lodygames.rpgquest.webapi.account;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hachage des mots de passe (issue #53) : PBKDF2-HMAC-SHA256 du JDK, sel aléatoire de 16 octets,
 * {@value #DEFAULT_ITERATIONS} itérations (recommandation OWASP), aucune dépendance externe.
 *
 * <p>Format stocké, auto-descriptif pour pouvoir relever le coût plus tard sans casser les comptes :
 * {@code pbkdf2-sha256$<itérations>$<sel base64>$<hash base64>}. La vérification compare en temps
 * constant.</p>
 */
public final class PasswordHasher {

    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final String SCHEME = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** Coût réduit réservé aux tests. */
    public PasswordHasher(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations doit être positif");
        }
        this.iterations = iterations;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return SCHEME + "$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(derived);
    }

    public boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, storedIterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 indisponible dans ce JDK", e);
        } finally {
            spec.clearPassword();
        }
    }
}
