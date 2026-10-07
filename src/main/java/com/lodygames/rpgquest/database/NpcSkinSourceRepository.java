package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Source de skin d'un PNJ Citizens, telle que RPGQuest l'a appliquée (migration V27).
 *
 * <p><strong>Pourquoi cette table existe.</strong> Un PNJ Citizens de type {@code PLAYER} sans
 * skin explicite tire son apparence de son <em>nom</em>. Renommer un tel PNJ change donc aussi son
 * apparence — jamais ce qu'un administrateur demande quand il corrige un nom. Pour reconduire
 * l'apparence au travers d'un renommage, il faut savoir quel skin s'appliquait. Citizens le sait,
 * mais dans des métadonnées dont les clés ne font pas partie de l'artefact {@code citizensapi}
 * auquel ce projet se limite : s'y coupler serait fragile et se casserait en silence à la première
 * évolution interne de Citizens. On mémorise donc <em>notre</em> source, la seule que nous
 * maîtrisons, et on la réapplique par la commande structurée officielle.</p>
 *
 * <p>Clé = {@code NPC#getUniqueId()}, la même que {@code npc_citizens_bindings} : stable entre
 * redémarrages, contrairement à l'id numérique. Pure JDBC — aucun type Citizens ni Bukkit ici.</p>
 */
public final class NpcSkinSourceRepository {

    /** D'où vient l'apparence d'un PNJ. */
    public enum Kind {
        /** Lien MineSkin explicitement appliqué depuis le panel. */
        URL,
        /**
         * Nom dont l'apparence était dérivée avant un renommage. Reconduire ce nom restitue
         * <strong>exactement</strong> ce que le joueur voyait déjà : ce n'est pas un skin neuf.
         */
        NAME
    }

    /** Ce que RPGQuest a appliqué, et comment le réappliquer à l'identique. */
    public record SkinSource(Kind kind, String value) {
    }

    private static final String UPSERT = """
            INSERT INTO npc_citizens_skins (citizens_uuid, source_kind, source_value, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(citizens_uuid) DO UPDATE SET source_kind = excluded.source_kind,
                source_value = excluded.source_value, updated_at = excluded.updated_at
            """;
    private static final String FIND =
            "SELECT source_kind, source_value FROM npc_citizens_skins WHERE citizens_uuid = ?";
    private static final String DELETE = "DELETE FROM npc_citizens_skins WHERE citizens_uuid = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public NpcSkinSourceRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    /** Enregistre (ou remplace) la source d'apparence d'un PNJ Citizens. */
    public CompletableFuture<Void> save(UUID citizensUuid, Kind kind, String value) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT))) {
                statement.setString(1, citizensUuid.toString());
                statement.setString(2, kind.name());
                statement.setString(3, value);
                statement.setString(4, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Source connue pour ce PNJ, ou vide si RPGQuest n'en a jamais appliqué — auquel cas son
     * apparence vient du nom, et c'est à l'appelant d'en tenir compte.
     */
    public CompletableFuture<Optional<SkinSource>> find(UUID citizensUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(FIND))) {
                statement.setString(1, citizensUuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<SkinSource>empty();
                    }
                    Kind kind;
                    try {
                        kind = Kind.valueOf(rs.getString("source_kind"));
                    } catch (IllegalArgumentException unknown) {
                        // Ligne écrite par une version ultérieure : on préfère ignorer plutôt que
                        // réappliquer quelque chose qu'on ne comprend pas.
                        return Optional.<SkinSource>empty();
                    }
                    return Optional.of(new SkinSource(kind, rs.getString("source_value")));
                }
            }
        });
    }

    /** Oublie la source d'un PNJ (déliaison / suppression) — jamais appelé par un renommage. */
    public CompletableFuture<Void> delete(UUID citizensUuid) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(DELETE))) {
                statement.setString(1, citizensUuid.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }
}
