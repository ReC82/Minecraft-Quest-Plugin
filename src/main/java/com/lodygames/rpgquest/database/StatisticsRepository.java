package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.stats.EventLifecycle;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Classements de saisons et d'événements (issue #54), en <strong>lecture seule</strong> sur le
 * gameplay.
 *
 * <p>Source de vérité : {@code xp_grants}, le journal que la progression écrit déjà — une ligne par
 * octroi, dédupliquée par (joueur, piste, id d'événement de jeu), horodatée en UTC. La valeur d'un
 * joueur sur une période est la somme de ses octrois dans la fenêtre {@code [début, fin)} : aucun
 * compteur parallèle, deux périodes qui se chevauchent comptent la même action chacune selon sa
 * fenêtre, et un retry déjà dédupliqué par le journal ne compte jamais deux fois.</p>
 *
 * <p>Issue #57 : les métriques d'activité (mob tué, pêche) se lisent de la même manière dans
 * {@code stat_activity_log}, filtrées par cible ; la règle de classement de l'événement choisit
 * somme ou meilleure action, et l'ordre.</p>
 *
 * <p>À la fin d'une période, {@link #archive} fige ses valeurs dans {@code stat_period_results} :
 * le classement d'une saison passée ne bouge plus, même si le journal est ensuite purgé (reset d'un
 * joueur). Les exclusions de modération et la visibilité des profils (#55) s'appliquent à la
 * lecture, jamais à l'écriture : un joueur exclu après coup disparaît aussi des archives.</p>
 */
public final class StatisticsRepository {

    /**
     * Les horodatages du journal sont des {@code Instant#toString()} (fraction de seconde
     * variable). Une borne écrite sans le {@code Z} final se compare correctement à toutes ces
     * formes : « 18:00:00 » &lt; « 18:00:00Z » &lt; « 18:00:00.5Z ».
     */
    private static final DateTimeFormatter BOUND =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DatabaseManager database;
    private final SqlDialect dialect;

    public StatisticsRepository(DatabaseManager database) {
        this.database = database;
        this.dialect = database.dialect();
    }

    /** Une ligne de classement publique : pseudo, valeur et rang (ex-aequo au même rang). */
    public record RankedRow(String name, long value, int rank) {
    }

    /** Classement en cours d'une période, calculé sur sa fenêtre dans le journal. */
    public CompletableFuture<List<RankedRow>> liveTop(StatPeriod period, StatMetric metric, int limit,
                                                      PublicProfilePolicy.Visibility visibility,
                                                      Collection<UUID> excluded, Collection<String> excludedReasons) {
        requireWindowed(metric);
        String sql = "SELECT p.last_name AS name, " + aggregate(period) + " AS total "
                + "FROM " + source(metric) + " g JOIN player_profiles p ON p.uuid = g.player_uuid "
                + "WHERE " + windowFilter(period, metric, excludedReasons)
                + " AND " + PublicProfilePolicy.visible("p", visibility)
                + notIn("p.uuid", excluded.size())
                + " GROUP BY p.uuid, p.last_name HAVING " + aggregate(period) + " > 0"
                + " ORDER BY total " + period.rules().ranking().order().name() + ", p.last_name ASC, p.uuid ASC LIMIT ?";
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bindWindow(statement, 1, period, metric, excludedReasons);
                index = bindUuids(statement, index, excluded);
                statement.setInt(index, limit);
                return ranked(statement);
            }
        });
    }

    /**
     * Total de tous les participants d'un objectif commun (#57), en direct. Les profils privés
     * comptent (aucun pseudo n'est lu), les joueurs exclus non.
     */
    public CompletableFuture<Long> communityTotal(StatPeriod period, StatMetric metric, Collection<UUID> excluded,
                                                  Collection<String> excludedReasons) {
        requireWindowed(metric);
        String sql = "SELECT COALESCE(SUM(g.amount), 0) AS total FROM " + source(metric) + " g WHERE "
                + windowFilter(period, metric, excludedReasons) + notIn("g.player_uuid", excluded.size());
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bindWindow(statement, 1, period, metric, excludedReasons);
                bindUuids(statement, index, excluded);
                return total(statement);
            }
        });
    }

    /** Total figé d'un objectif commun archivé (#57). */
    public CompletableFuture<Long> archivedCommunityTotal(String periodId, StatMetric metric, Collection<UUID> excluded) {
        String sql = "SELECT COALESCE(SUM(r.stat_value), 0) AS total FROM stat_period_results r "
                + "WHERE r.period_id = ? AND r.metric = ?" + notIn("r.player_uuid", excluded.size());
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, periodId);
                statement.setString(2, metric.id());
                bindUuids(statement, 3, excluded);
                return total(statement);
            }
        });
    }

    /**
     * Note une transition de cycle de vie (#57), une seule fois par période et par état : rend
     * {@code true} si elle vient d'être enregistrée. Journal d'audit, jamais lu par le gameplay.
     */
    public CompletableFuture<Boolean> recordTransition(String periodId, EventLifecycle state, Instant now) {
        String sql = dialect.rewrite("INSERT OR IGNORE INTO stat_period_audit (period_id, action, created_at) VALUES (?, ?, ?)");
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, periodId);
                statement.setString(2, state.name());
                statement.setString(3, now.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Transitions enregistrées d'une période, dans l'ordre où elles ont eu lieu. */
    public CompletableFuture<List<String>> auditTrail(String periodId) {
        return database.execute(connection -> {
            List<String> actions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT action FROM stat_period_audit WHERE period_id = ? ORDER BY created_at ASC, action ASC")) {
                statement.setString(1, periodId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        actions.add(resultSet.getString("action"));
                    }
                }
            }
            return actions;
        });
    }

    /**
     * Fige les valeurs d'une période terminée. Idempotent : la ligne d'archive est posée en premier
     * par un {@code INSERT OR IGNORE}, dans la même transaction que les résultats — un second appel
     * (redémarrage, tick concurrent) ne réécrit rien. Rend {@code true} si l'archive vient d'être créée.
     * La valeur figée suit la règle de classement de l'événement (somme ou meilleure action, #57) ;
     * la transition {@code ARCHIVED} est auditée dans la même transaction.
     */
    public CompletableFuture<Boolean> archive(StatPeriod period, Collection<String> excludedReasons, Instant now) {
        for (String metricId : period.metrics()) {
            requireWindowed(StatMetric.find(metricId).orElseThrow());
        }
        String insertArchive = dialect.rewrite("INSERT OR IGNORE INTO stat_period_archives "
                + "(period_id, scope, start_at, end_at, frozen_at) VALUES (?, ?, ?, ?, ?)");
        return database.execute(connection -> inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(insertArchive)) {
                statement.setString(1, period.id());
                statement.setString(2, period.scope().name());
                statement.setString(3, period.start().toString());
                statement.setString(4, period.end().toString());
                statement.setString(5, now.toString());
                if (statement.executeUpdate() == 0) {
                    return false;
                }
            }
            for (String metricId : period.metrics()) {
                StatMetric metric = StatMetric.find(metricId).orElseThrow();
                String sql = "INSERT INTO stat_period_results (period_id, metric, player_uuid, stat_value) "
                        + "SELECT ?, ?, g.player_uuid, " + aggregate(period) + " FROM " + source(metric) + " g WHERE "
                        + windowFilter(period, metric, excludedReasons) + " GROUP BY g.player_uuid HAVING "
                        + aggregate(period) + " > 0";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, period.id());
                    statement.setString(2, metric.id());
                    bindWindow(statement, 3, period, metric, excludedReasons);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(dialect.rewrite(
                    "INSERT OR IGNORE INTO stat_period_audit (period_id, action, created_at) VALUES (?, ?, ?)"))) {
                statement.setString(1, period.id());
                statement.setString(2, EventLifecycle.ARCHIVED.name());
                statement.setString(3, now.toString());
                statement.executeUpdate();
            }
            return true;
        }));
    }

    /** Identifiants des périodes déjà figées. */
    public CompletableFuture<Set<String>> archivedPeriodIds() {
        return database.execute(connection -> {
            Set<String> ids = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT period_id FROM stat_period_archives");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getString("period_id"));
                }
            }
            return ids;
        });
    }

    /** Classement figé d'une période archivée. */
    public CompletableFuture<List<RankedRow>> archivedTop(StatPeriod period, StatMetric metric, int limit,
                                                          PublicProfilePolicy.Visibility visibility,
                                                          Collection<UUID> excluded) {
        String sql = "SELECT p.last_name AS name, r.stat_value AS total "
                + "FROM stat_period_results r JOIN player_profiles p ON p.uuid = r.player_uuid "
                + "WHERE r.period_id = ? AND r.metric = ? AND " + PublicProfilePolicy.visible("p", visibility)
                + notIn("p.uuid", excluded.size())
                + " ORDER BY total " + period.rules().ranking().order().name() + ", p.last_name ASC, p.uuid ASC LIMIT ?";
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, period.id());
                statement.setString(2, metric.id());
                int index = bindUuids(statement, 3, excluded);
                statement.setInt(index, limit);
                return ranked(statement);
            }
        });
    }

    /**
     * Rangs en classement « compétition » : deux valeurs égales partagent le même rang, le suivant
     * saute d'autant (1, 1, 3). L'ordre d'affichage des ex-aequo est fixé par la requête (pseudo,
     * puis UUID) : deux exports successifs donnent toujours la même liste.
     */
    public static List<RankedRow> rank(List<String> names, List<Long> values) {
        List<RankedRow> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int rank = i > 0 && values.get(i).equals(values.get(i - 1)) ? rows.get(i - 1).rank() : i + 1;
            rows.add(new RankedRow(names.get(i), values.get(i), rank));
        }
        return rows;
    }

    /** Fragment SQL « p.uuid NOT IN (?, ?…) », vide sans exclusion. Aucune donnée concaténée. */
    static String notIn(String column, int count) {
        if (count == 0) {
            return "";
        }
        return " AND " + column + " NOT IN (" + String.join(", ", java.util.Collections.nCopies(count, "?")) + ")";
    }

    static int bindUuids(PreparedStatement statement, int index, Collection<UUID> uuids) throws SQLException {
        for (UUID uuid : uuids) {
            statement.setString(index++, uuid.toString());
        }
        return index;
    }

    private static List<RankedRow> ranked(PreparedStatement statement) throws SQLException {
        List<String> names = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
                values.add(resultSet.getLong("total"));
            }
        }
        return rank(names, values);
    }

    private static long total(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong("total") : 0L;
        }
    }

    /** Journal lu pour une métrique : l'XP ({@code xp_grants}) ou l'activité générique (#57). */
    private static String source(StatMetric metric) {
        return metric.xpSkill() != null ? "xp_grants" : "stat_activity_log";
    }

    /**
     * Filtre SQL d'une fenêtre. Les colonnes de filtre viennent du catalogue des métriques
     * ({@link StatMetric#filters()}), jamais du fichier ; seules les valeurs sont des paramètres.
     */
    private static String windowFilter(StatPeriod period, StatMetric metric, Collection<String> excludedReasons) {
        if (metric.xpSkill() == null) {
            StringBuilder sql = new StringBuilder("g.action = ? AND g.created_at >= ? AND g.created_at < ?");
            for (Map.Entry<String, List<String>> filter : filters(period, metric).entrySet()) {
                sql.append(" AND g.").append(filter.getKey()).append(" IN (")
                        .append(String.join(", ", java.util.Collections.nCopies(filter.getValue().size(), "?"))).append(')');
            }
            return sql.toString();
        }
        String reasons = excludedReasons.isEmpty() ? ""
                : " AND (g.reason IS NULL OR g.reason NOT IN ("
                + String.join(", ", java.util.Collections.nCopies(excludedReasons.size(), "?")) + "))";
        return "g.skill = ? AND g.created_at >= ? AND g.created_at < ?" + reasons;
    }

    private static int bindWindow(PreparedStatement statement, int index, StatPeriod period, StatMetric metric,
                                  Collection<String> excludedReasons) throws SQLException {
        statement.setString(index++, metric.xpSkill() != null ? metric.xpSkill().name() : metric.activity());
        statement.setString(index++, BOUND.format(period.start()));
        statement.setString(index++, BOUND.format(period.end()));
        if (metric.xpSkill() == null) {
            for (List<String> values : filters(period, metric).values()) {
                for (String value : values) {
                    statement.setString(index++, value);
                }
            }
            return index;
        }
        for (String reason : excludedReasons) {
            statement.setString(index++, reason);
        }
        return index;
    }

    /** Colonne du journal → valeurs acceptées, dans un ordre stable (même SQL, mêmes paramètres). */
    private static Map<String, List<String>> filters(StatPeriod period, StatMetric metric) {
        Map<String, List<String>> byColumn = new TreeMap<>();
        for (Map.Entry<String, java.util.Set<String>> filter : period.rules().filter().entrySet()) {
            String column = metric.filters().get(filter.getKey());
            if (column != null && !filter.getValue().isEmpty()) {
                byColumn.computeIfAbsent(column, k -> new ArrayList<>()).addAll(filter.getValue());
            }
        }
        byColumn.values().forEach(values -> values.sort(null));
        return byColumn;
    }

    private static String aggregate(StatPeriod period) {
        return (period.rules().ranking().aggregation() == StatMetric.Aggregation.MAX ? "MAX" : "SUM") + "(g.amount)";
    }

    private static void requireWindowed(StatMetric metric) {
        if (!metric.windowed()) {
            throw new IllegalArgumentException("métrique sans journal horodaté, non calculable sur une période : " + metric.id());
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run() throws SQLException;
    }

    private static <T> T inTransaction(Connection connection, SqlAction<T> action) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = action.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }
}
