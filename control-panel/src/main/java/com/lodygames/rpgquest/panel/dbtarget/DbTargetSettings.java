package com.lodygames.rpgquest.panel.dbtarget;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Paramètres de connexion à la base MariaDB <strong>cible</strong> d'une future migration
 * (issue #42), tels que l'administrateur les a enregistrés dans PlugAdmin.
 *
 * <p>Ce record ne contient <strong>jamais</strong> le mot de passe : seulement le fait qu'il est
 * configuré ({@link #passwordConfigured}) ou illisible ({@link #passwordUnreadable}, secret maître
 * changé). Le mot de passe déchiffré ne sort de {@link DbTargetStore} que vers le testeur de
 * connexion.</p>
 *
 * <p>Ces paramètres décrivent une cible <em>testée</em>, pas le backend du serveur : le backend
 * réellement actif vient du heartbeat du plugin.</p>
 */
public record DbTargetSettings(
        String host,
        int port,
        String database,
        String username,
        String sslMode,
        boolean passwordConfigured,
        boolean passwordUnreadable,
        String updatedAt,
        String updatedBy,
        String lastTestAt,
        boolean lastTestOk,
        String lastTestSummary) {

    public static final Set<String> SSL_MODES = Set.of("disable", "trust", "verify-ca", "verify-full");

    /** Nom d'hôte DNS ou adresse IP : rien qui puisse modifier l'URL JDBC ({@code / ? & = ;}). */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:]+\\]");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_$-]{1,64}");

    public static DbTargetSettings empty() {
        return new DbTargetSettings("", 3306, "", "", "disable", false, false, "", "", "", false, "");
    }

    public boolean configured() {
        return !host.isEmpty() && !database.isEmpty() && !username.isEmpty();
    }

    /**
     * Valide une saisie ; renvoie le message d'erreur, ou {@code null} si tout est correct. Les
     * valeurs ne sont jamais recopiées dans le message (pas de réinjection HTML).
     */
    public static String validate(String host, String port, String database, String username, String sslMode) {
        if (host == null || !HOST.matcher(host).matches()) {
            return "Hôte invalide : nom DNS ou adresse IP, sans schéma, port ni chemin.";
        }
        try {
            int value = Integer.parseInt(port == null ? "" : port.trim());
            if (value < 1 || value > 65535) {
                return "Port hors plage (1 à 65535).";
            }
        } catch (NumberFormatException e) {
            return "Port invalide.";
        }
        if (database == null || !NAME.matcher(database).matches()) {
            return "Nom de base invalide (lettres, chiffres, _ $ -, 64 caractères au plus).";
        }
        if (username == null || !NAME.matcher(username).matches()) {
            return "Compte invalide (lettres, chiffres, _ $ -, 64 caractères au plus).";
        }
        if (sslMode == null || !SSL_MODES.contains(sslMode.toLowerCase(Locale.ROOT))) {
            return "Mode TLS inconnu.";
        }
        return null;
    }

    @Override
    public String toString() {
        // jamais de secret, et pas d'hôte dans les journaux : seulement la base et l'état
        return "DbTargetSettings[database=" + database + ", ssl=" + sslMode
                + ", password=" + (passwordConfigured ? "configuré" : "absent") + "]";
    }
}
