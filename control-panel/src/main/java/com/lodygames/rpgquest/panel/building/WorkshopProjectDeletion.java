package com.lodygames.rpgquest.panel.building;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Supprimer définitivement un projet de l'Atelier (issue #881) — ce que le PANEL en garde, rien d'autre.
 * Seul un projet déjà mis à la corbeille ({@code archived = 1}) peut l'être : la suppression
 * définitive est un second geste, séparé et confirmé, jamais celui de la fiche.
 *
 * <p>Effacé, dans une seule transaction : la fiche du projet, ses versions de plan (y compris celles
 * retirées de la liste), leurs révisions et leurs liens de vérification, ses jetons de demande, ses
 * conceptions détaillées, ses conceptions automatiques et ses images de référence. Tout ou rien : une
 * erreur au milieu ne laisse pas de demi-projet.</p>
 *
 * <p>Conservé : l'historique global des actions de l'agent (où un plan reste importable, #308), le
 * journal IA (#739), le journal d'audit du panel, et tout ce qui vit sur le serveur — bibliothèque,
 * versions archivées, emplacements, bâtiments posés, sauvegardes de terrain, portails. Les refus
 * (bâtiment posé, publié, usage incertain) sont décidés AVANT par {@link ProjectUsage} ; ici on
 * revérifie seulement, dans la transaction, la version vue et l'absence de conception en cours.</p>
 */
public final class WorkshopProjectDeletion {

    public enum Outcome { DELETED, STALE, NOT_FOUND, BUSY, NOT_IN_TRASH }

    /** Ce qui serait réellement effacé. */
    public record Preview(int versions, int revisions, int designs, int runs, int images, long imageBytes) {
    }

    private final String jdbcUrl;

    public WorkshopProjectDeletion(String dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
    }

    public Preview preview(String projectId) {
        try (Connection c = connect()) {
            Set<String> tables = tables(c);
            return new Preview(
                    count(c, tables, "building_plan_draft", "SELECT COUNT(*) FROM building_plan_draft WHERE project_id = ?", projectId),
                    count(c, tables, "building_plan_draft_revision", "SELECT COUNT(*) FROM building_plan_draft_revision "
                            + "WHERE draft_id IN (SELECT id FROM building_plan_draft WHERE project_id = ?)", projectId),
                    count(c, tables, "building_design_doc", "SELECT COUNT(*) FROM building_design_doc WHERE project_id = ?", projectId),
                    count(c, tables, "building_design_run", "SELECT COUNT(*) FROM building_design_run WHERE project_id = ?", projectId),
                    count(c, tables, "building_project_image", "SELECT COUNT(*) FROM building_project_image WHERE project_id = ?", projectId),
                    count(c, tables, "building_project_image",
                            "SELECT COALESCE(SUM(size), 0) FROM building_project_image WHERE project_id = ?", projectId));
        } catch (SQLException e) {
            throw new IllegalStateException("Lecture de ce que supprimerait le projet impossible (" + projectId + ")", e);
        }
    }

    /**
     * @param expectedVersion la version de la fiche vue par l'administrateur : modifiée depuis, rien
     *                        n'est effacé ({@link Outcome#STALE})
     */
    public Outcome delete(String projectId, int expectedVersion) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                Outcome outcome = deleteIn(c, projectId, expectedVersion);
                if (outcome == Outcome.DELETED) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return outcome;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Suppression du projet impossible (" + projectId + ") : rien n'a été effacé", e);
        }
    }

    private static Outcome deleteIn(Connection c, String projectId, int expectedVersion) throws SQLException {
        Set<String> tables = tables(c);
        if (tables.contains("building_design_run") && count(c, tables, "building_design_run",
                "SELECT COUNT(*) FROM building_design_run WHERE project_id = ? AND status = 'RUNNING'", projectId) > 0) {
            return Outcome.BUSY;
        }
        // La fiche d'abord : c'est elle qui dit « version vue » et qui fait échouer le second clic.
        int removed;
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM building_project WHERE id = ? AND version = ? AND archived = 1")) {
            ps.setString(1, projectId);
            ps.setInt(2, expectedVersion);
            removed = ps.executeUpdate();
        }
        if (removed != 1) {
            return switch (archivedFlag(c, projectId)) {
                case 1 -> Outcome.STALE;
                case 0 -> Outcome.NOT_IN_TRASH;
                default -> Outcome.NOT_FOUND;
            };
        }
        String drafts = "(SELECT id FROM building_plan_draft WHERE project_id = ?)";
        for (String table : new String[] {"building_plan_draft_revision", "building_plan_draft_action",
                "building_plan_request"}) {
            if (tables.contains(table) && tables.contains("building_plan_draft")) {
                update(c, "DELETE FROM " + table + " WHERE draft_id IN " + drafts, projectId);
            }
        }
        for (String table : new String[] {"building_plan_draft", "building_design_doc", "building_design_run",
                "building_project_image"}) {
            if (tables.contains(table)) {
                update(c, "DELETE FROM " + table + " WHERE project_id = ?", projectId);
            }
        }
        return Outcome.DELETED;
    }

    /** 1 = à la corbeille, 0 = dans la liste, -1 = introuvable. */
    private static int archivedFlag(Connection c, String projectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT archived FROM building_project WHERE id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (rs.getInt(1) != 0 ? 1 : 0) : -1;
            }
        }
    }

    private static void update(Connection c, String sql, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.executeUpdate();
        }
    }

    private static int count(Connection c, Set<String> tables, String table, String sql, String arg) throws SQLException {
        if (!tables.contains(table)) {
            return 0;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static Set<String> tables(Connection c) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }
}
