package com.lodygames.rpgquest.panel.web;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache-busting des assets locaux (fix #93). {@link #v(String)} renvoie
 * {@code /assets/<path>?v=<hash8>} où {@code hash8} est dérivé du contenu réel du fichier
 * embarqué. Un déploiement qui modifie {@code panel.js} ou {@code plugadmin.css} change donc
 * l'URL, ce qui empêche un navigateur de servir l'ancienne version depuis son cache
 * (le handler {@code PanelApp#handleAsset} ignore la query string).
 *
 * <p>Le hash est calculé une seule fois par chemin (le contenu embarqué ne change pas à
 * l'exécution) et mémorisé.</p>
 */
final class Assets {

    private static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();

    private Assets() {
    }

    static String v(String path) {
        String ver = VERSIONS.computeIfAbsent(path, Assets::hash);
        return ver.isEmpty() ? "/assets/" + path : "/assets/" + path + "?v=" + ver;
    }

    private static String hash(String path) {
        try (var in = Assets.class.getResourceAsStream("/assets/" + path)) {
            if (in == null) {
                return "";
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            return "";
        }
    }
}
