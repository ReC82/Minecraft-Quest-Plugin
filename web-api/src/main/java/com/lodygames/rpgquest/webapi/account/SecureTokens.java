package com.lodygames.rpgquest.webapi.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Secrets aléatoires du portail (issue #53) : jetons de session et CSRF, codes de liaison.
 *
 * <p>Seule l'empreinte SHA-256 d'un jeton de session ou d'un code de liaison est enregistrée : une
 * copie de {@code accounts.db} ne permet ni d'ouvrir une session ni de lier un compte.</p>
 */
public final class SecureTokens {

    /** Sans 0/O, 1/I/L : un code se recopie sans ambiguïté depuis l'écran vers le chat Minecraft. */
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {
    }

    /** 32 octets aléatoires en base64url (256 bits). */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String newLinkCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Forme canonique d'un code saisi : majuscules, sans espaces ni tirets ; vide si impossible. */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        String code = raw.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (code.length() != CODE_LENGTH) {
            return "";
        }
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) {
                return "";
            }
        }
        return code;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
