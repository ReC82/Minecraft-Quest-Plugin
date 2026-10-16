package com.lodygames.rpgquest.webapi.ingest;

/**
 * Réception de l'instantané envoyé par le serveur Minecraft (#421).
 *
 * <p>{@code token} vient exclusivement de la variable d'environnement
 * {@code RPGQUEST_PORTAL_SNAPSHOT_TOKEN} : il n'ouvre que {@code POST /api/snapshot} et n'est ni le
 * jeton général de l'API, ni celui de {@code /lien}, ni un secret de PlugAdmin. Absent : la route
 * refuse tout (401).</p>
 *
 * @param maxBytes taille maximale d'un instantané reçu (413 au-delà)
 */
public record SnapshotIngestSettings(String token, int maxBytes) {

    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;

    public static SnapshotIngestSettings disabled() {
        return new SnapshotIngestSettings(null, DEFAULT_MAX_BYTES);
    }

    @Override
    public String toString() {
        return "SnapshotIngestSettings[token=" + (token == null || token.isBlank() ? "absent" : "***")
                + ", maxBytes=" + maxBytes + "]";
    }
}
