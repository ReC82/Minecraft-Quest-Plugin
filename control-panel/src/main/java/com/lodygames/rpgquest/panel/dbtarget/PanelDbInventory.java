package com.lodygames.rpgquest.panel.dbtarget;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Inventaire en <strong>lecture seule</strong> de la base actuelle du panel,
 * {@code control-panel.db} (issue #43) : tables et nombre de lignes, pour préparer une éventuelle
 * migration du panel et vérifier d'un coup d'œil qu'aucune table métier RPGQuest n'y vit.
 *
 * <p>Ouverture {@code open_mode=1} (lecture seule SQLite) : l'inventaire ne crée, ne verrouille en
 * écriture ni ne modifie jamais le fichier.</p>
 */
public final class PanelDbInventory {

    /** Une table de la base du panel. {@code rows < 0} : comptage impossible. */
    public record Table(String name, long rows) {
    }

    private PanelDbInventory() {
    }

    /** Tables utilisateur, triées par nom ; liste vide si la base est illisible. */
    public static List<Table> read(String dbPath) {
        List<Table> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath + "?open_mode=1");
             Statement st = c.createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            for (String name : names) {
                tables.add(new Table(name, count(st, name)));
            }
        } catch (SQLException e) {
            return List.of();
        }
        return List.copyOf(tables);
    }

    private static long count(Statement st, String table) {
        // nom issu de sqlite_master, cité en identifiant SQL : jamais une saisie utilisateur
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\"")) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }
}
