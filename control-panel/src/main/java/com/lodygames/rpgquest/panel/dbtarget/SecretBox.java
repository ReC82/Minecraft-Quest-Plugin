package com.lodygames.rpgquest.panel.dbtarget;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement au repos d'un secret du panel (issue #42 : mot de passe de la base MariaDB cible).
 *
 * <p>AES-256-GCM (chiffrement authentifié : un octet modifié en base fait échouer le déchiffrement
 * au lieu de produire un faux mot de passe), IV aléatoire de 96 bits par chiffrement, données
 * associées = l'usage du secret (un chiffré ne peut pas être recopié vers un autre usage).</p>
 *
 * <p>Clé : HMAC-SHA256(secret maître, libellé d'usage). Le secret maître est
 * {@code RPGQUEST_PANEL_SECRET}, déjà obligatoire, fourni par l'environnement et jamais stocké avec
 * la base : une copie de {@code control-panel.db} seule ne révèle pas le mot de passe. Conséquence
 * assumée : changer {@code RPGQUEST_PANEL_SECRET} rend le secret illisible
 * ({@link #open} renvoie vide) — il faut alors le ressaisir, le panel le signale.</p>
 */
public final class SecretBox {

    private static final String FORMAT = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final byte[] associatedData;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param masterSecret secret maître (≥ 16 caractères)
     * @param purpose      libellé d'usage, sert à la dérivation de clé et de données associées
     */
    public SecretBox(String masterSecret, String purpose) {
        if (masterSecret == null || masterSecret.length() < 16) {
            throw new IllegalArgumentException("secret maître absent ou trop court");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            this.key = new SecretKeySpec(mac.doFinal(("plugadmin/secret-box/" + purpose)
                    .getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("dérivation de clé impossible", e);
        }
        this.associatedData = purpose.getBytes(StandardCharsets.UTF_8);
    }

    /** Chiffre {@code plaintext} ; résultat textuel {@code v1:<base64(iv || chiffré || tag)>}. */
    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return FORMAT + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("chiffrement impossible", e);
        }
    }

    /**
     * Déchiffre ; vide si le chiffré est absent, altéré, d'un autre usage ou scellé avec un autre
     * secret maître. Ne lève jamais d'exception portant une partie du secret.
     */
    public Optional<String> open(String sealed) {
        if (sealed == null || !sealed.startsWith(FORMAT)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getDecoder().decode(sealed.substring(FORMAT.length()));
            if (raw.length <= IV_BYTES) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(associatedData);
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
