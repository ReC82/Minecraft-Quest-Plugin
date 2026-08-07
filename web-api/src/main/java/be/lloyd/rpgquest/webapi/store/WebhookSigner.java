package be.lloyd.rpgquest.webapi.store;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature HMAC-SHA256 des webhooks du prestataire de paiement (mission
 * étape 22, point 9 : "signature/authentification entre le site et le
 * serveur" — ici entre le prestataire sandbox et web-api). Comparaison en
 * temps constant, même principe que {@code http.AuthFilter} de l'étape 21.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    public static String sign(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload);
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible.", e);
        }
    }

    public static boolean isValid(String secret, byte[] payload, String providedSignatureHex) {
        if (secret == null || secret.isBlank() || providedSignatureHex == null) {
            return false;
        }
        String expected = sign(secret, payload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), providedSignatureHex.getBytes(StandardCharsets.UTF_8));
    }
}
