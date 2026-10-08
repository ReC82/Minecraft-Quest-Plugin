package com.lodygames.rpgquest.panel.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Configuration d'un fournisseur d'IA (issue #146), telle qu'elle vit <strong>côté serveur</strong>.
 *
 * <p><strong>La clé API est dans ce record, et ce record ne doit jamais atteindre le navigateur.</strong>
 * Les pages du panel n'affichent que {@link #masked()} et {@link #fingerprint()} — jamais
 * {@link #apiKey()}. C'est la raison d'être de ces deux méthodes : donner à l'administrateur de quoi
 * reconnaître <em>quelle</em> clé est enregistrée, sans en révéler un seul caractère.</p>
 *
 * @param apiKey         clé en clair, lue depuis la base locale du panel (SQLite en mode 600, hors
 *                       dépôt Git) ; chaîne vide = aucune clé enregistrée
 * @param baseUrl        surcharge de l'URL de base, vide = valeur par défaut du fournisseur
 * @param model          modèle à utiliser, vide = valeur par défaut du fournisseur
 * @param maxOutputTokens plafond de sortie, garde-fou de budget demandé par le ticket
 * @param timeoutSeconds délai maximal d'un appel
 */
public record AiProviderSettings(String providerId, boolean enabled, String apiKey, String baseUrl,
                                 String model, int maxOutputTokens, int timeoutSeconds) {

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 90;

    public AiProviderSettings {
        providerId = providerId == null ? "" : providerId.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        model = model == null ? "" : model.trim();
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
    }

    public static AiProviderSettings empty(String providerId) {
        return new AiProviderSettings(providerId, false, "", "", "",
                DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_TIMEOUT_SECONDS);
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /** Utilisable pour un appel réel : activé <strong>et</strong> pourvu d'une clé. */
    public boolean usable() {
        return enabled && hasKey();
    }

    /**
     * Ce que l'écran affiche à la place de la clé : une longueur, et rien d'autre. Aucun caractère
     * de la clé n'en sort — pas même les derniers, contrairement à l'usage courant : une clé
     * d'API est un secret entier, et montrer sa fin ne sert qu'à se rassurer.
     */
    public String masked() {
        return hasKey() ? "•".repeat(Math.min(24, Math.max(8, apiKey.length()))) + " ("
                + apiKey.length() + " caractères)" : "aucune clé enregistrée";
    }

    /**
     * Empreinte courte et non réversible de la clé, pour que l'administrateur puisse vérifier
     * <em>laquelle</em> est en place (après rotation, par exemple) sans qu'elle soit exposée.
     */
    public String fingerprint() {
        if (!hasKey()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Copie sans la clé, pour tout ce qui pourrait être journalisé ou sérialisé par erreur. */
    public AiProviderSettings withoutKey() {
        return new AiProviderSettings(providerId, enabled, "", baseUrl, model, maxOutputTokens,
                timeoutSeconds);
    }

    /**
     * {@code toString} est redéfini <strong>exprès</strong> : le {@code toString} généré d'un record
     * imprime tous ses composants, donc la clé entière, et il suffit d'un journal de debug ou d'un
     * message d'exception pour la faire fuir. Celui-ci n'imprime que l'empreinte.
     */
    @Override
    public String toString() {
        return "AiProviderSettings[" + providerId + ", enabled=" + enabled
                + ", key=" + (hasKey() ? "présente(" + fingerprint() + ")" : "absente")
                + ", model=" + model + ", baseUrl=" + baseUrl
                + ", maxOutputTokens=" + maxOutputTokens + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
