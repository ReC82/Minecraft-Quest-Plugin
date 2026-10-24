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
 * @param dataMaxAgeSeconds durée pendant laquelle les données de jeu d'un instantané complet
 *        (joueurs, classements, profils) restent affichées : l'instantané n'arrive que toutes les
 *        10 à 15 minutes, l'état du serveur se juge, lui, sur le relevé d'état toutes les 30 s
 */
public record SnapshotIngestSettings(String token, int maxBytes, long dataMaxAgeSeconds) {

    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;
    /** Trois instantanés de 15 min manqués + marge : au-delà, les données sont retirées, jamais montrées comme actuelles. */
    public static final long DEFAULT_DATA_MAX_AGE_SECONDS = 3600;

    public SnapshotIngestSettings(String token, int maxBytes) {
        this(token, maxBytes, DEFAULT_DATA_MAX_AGE_SECONDS);
    }

    public static SnapshotIngestSettings disabled() {
        return new SnapshotIngestSettings(null, DEFAULT_MAX_BYTES);
    }

    @Override
    public String toString() {
        return "SnapshotIngestSettings[token=" + (token == null || token.isBlank() ? "absent" : "***")
                + ", maxBytes=" + maxBytes + ", dataMaxAgeSeconds=" + dataMaxAgeSeconds + "]";
    }
}
