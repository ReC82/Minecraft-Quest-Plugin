package com.lodygames.rpgquest.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Nœuds de dialogue <strong>réellement présentés</strong> à un joueur (issue #12, table
 * {@code dialogue_node_reads}).
 *
 * <p><strong>Sémantique voulue.</strong> Une ligne signifie « ce joueur a vu ce nœud ». L'absence
 * de ligne vaut « jamais lu » : rien n'est pré-rempli, et <strong>ouvrir un PNJ ne marque pas
 * toutes ses branches</strong> — seul le nœud effectivement affiché est enregistré. Une branche
 * nouvellement accessible reste donc signalée jusqu'à ce qu'elle soit lue pour de vrai.</p>
 *
 * <p>L'identité est l'<strong>UUID</strong> : un changement de pseudo ne perd pas la lecture, et
 * l'état survit à une reconnexion comme à un redémarrage. L'écriture est idempotente (clé primaire
 * composite), donc relire deux fois le même nœud n'ajoute rien.</p>
 *
 * <p>JDBC pur, aucun type Bukkit/Paper — comme les autres dépôts du projet.</p>
 */
public final class DialogueReadRepository {

    private static final String SELECT_FOR_DIALOGUE =
            "SELECT node_id FROM dialogue_node_reads WHERE player_uuid = ? AND dialogue_id = ?";
    private static final String SELECT_ALL_FOR_PLAYER =
            "SELECT dialogue_id, node_id FROM dialogue_node_reads WHERE player_uuid = ?";
    private static final String UPSERT = """
            INSERT INTO dialogue_node_reads (player_uuid, dialogue_id, node_id, read_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (player_uuid, dialogue_id, node_id) DO NOTHING
            """;
    private static final String DELETE_ALL_FOR_PLAYER =
            "DELETE FROM dialogue_node_reads WHERE player_uuid = ?";

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public DialogueReadRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    /** Un nœud lu, identifié par son dialogue et son id de nœud. */
    public record ReadNode(String dialogueId, String nodeId) {
    }

    /** Nœuds déjà lus par ce joueur pour ce dialogue. Jamais {@code null}. */
    public CompletableFuture<Set<String>> readNodes(UUID playerId, String dialogueId) {
        return database.execute(connection -> {
            Set<String> nodes = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_FOR_DIALOGUE)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, dialogueId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        nodes.add(resultSet.getString("node_id"));
                    }
                }
            }
            return nodes;
        });
    }

    /**
     * Tous les nœuds lus par un joueur, tous dialogues confondus. Lu <strong>une fois</strong> à la
     * connexion pour alimenter le cache en mémoire : le service de signal n'interroge jamais la
     * base dans sa boucle d'affichage.
     */
    public CompletableFuture<Set<ReadNode>> allForPlayer(UUID playerId) {
        return database.execute(connection -> {
            Set<ReadNode> nodes = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        nodes.add(new ReadNode(resultSet.getString("dialogue_id"),
                                resultSet.getString("node_id")));
                    }
                }
            }
            return nodes;
        });
    }

    /**
     * Enregistre qu'un nœud a été présenté. Idempotent : un nœud déjà lu n'est pas réécrit, donc
     * {@code read_at} garde la date de la <strong>première</strong> lecture.
     */
    public CompletableFuture<Void> markRead(UUID playerId, String dialogueId, String nodeId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(UPSERT))) {
                statement.setString(1, playerId.toString());
                statement.setString(2, dialogueId);
                statement.setString(3, nodeId);
                statement.setString(4, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Supprime la lecture d'un joueur — <strong>uniquement</strong> depuis le reset admin
     * « nouveau joueur ». La suppression d'un contenu ne passe jamais par ici : l'état de lecture
     * n'est pas une progression de quête, mais il reste une donnée du joueur.
     */
    public CompletableFuture<Integer> deleteAllForPlayer(UUID playerId) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_FOR_PLAYER)) {
                statement.setString(1, playerId.toString());
                return statement.executeUpdate();
            }
        });
    }
}
