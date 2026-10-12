package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #267 — projets de l'Atelier, et migration additive des brouillons de #256. */
class BuildingProjectStoreTest {

    @TempDir
    Path tmp;

    private static BuildingProjectStore.Fields fields(String brief) {
        return new BuildingProjectStore.Fields("Bibliothèque", brief, "bibliotheque_v2", "WEST", 19, 14);
    }

    @Test
    void aProjectIsCreatedReadAndUpdatedUnderVersionControl() {
        BuildingProjectStore store = new BuildingProjectStore(tmp.resolve("cp.db").toString());
        BuildingProject p = store.create("dev", fields("Deux étages."), "", "owner");

        assertEquals(1, p.version());
        assertEquals(BuildingProjectStore.UpdateOutcome.SAVED, store.update(p.id(), 1, fields("Trois étages."), "owner"));
        assertEquals(BuildingProjectStore.UpdateOutcome.STALE, store.update(p.id(), 1, fields("Autre onglet."), "owner"),
                "la version affichée n'est plus la courante");
        assertEquals("Trois étages.", store.find(p.id()).orElseThrow().brief());
    }

    @Test
    void invalidFieldsAreRefusedNotTruncated() {
        assertTrue(new BuildingProjectStore.Fields("", "x", "abc", "WEST", 19, 14).refusal().isPresent());
        assertTrue(new BuildingProjectStore.Fields("A", "x".repeat(12_001), "abc", "WEST", 19, 14).refusal()
                .orElseThrow().contains("Rien n'a été coupé"));
        assertTrue(new BuildingProjectStore.Fields("A", "x", "Mauvais Id!", "WEST", 19, 14).refusal().isPresent());
        assertEquals("bibliotheque_du_hub", BuildingProject.suggestBuildingId("Bibliothèque du Hub"));
    }

    @Test
    void anImportIsFoundAgainBySite() {
        BuildingProjectStore store = new BuildingProjectStore(tmp.resolve("cp.db").toString());
        BuildingProject p = store.create("dev", fields("x"), "buildsite_0009", "owner");
        assertEquals(p.id(), store.importedFrom("dev", "buildsite_0009").orElseThrow().id());
        assertTrue(store.importedFrom("dev", "buildsite_0001").isEmpty());
    }

    /** Une base créée par #256, sans la colonne project_id : elle s'ouvre, gagne la colonne, ne perd rien. */
    @Test
    void aDatabaseFrom256GainsTheProjectColumnAndKeepsItsDrafts() throws Exception {
        String db = tmp.resolve("old.db").toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE building_plan_draft (id TEXT PRIMARY KEY, agent_id TEXT NOT NULL, "
                    + "site_id TEXT NOT NULL, plan TEXT NOT NULL, plan_sha TEXT NOT NULL, version INTEGER NOT NULL, "
                    + "origin TEXT NOT NULL, parent_id TEXT, source_action_id TEXT, provider_id TEXT, "
                    + "provider_label TEXT, model TEXT, usage TEXT, raw_response TEXT, extraction_note TEXT, "
                    + "max_width INTEGER NOT NULL DEFAULT 19, max_height INTEGER NOT NULL DEFAULT 14, "
                    + "archived INTEGER NOT NULL DEFAULT 0, created_by TEXT, created_at TEXT NOT NULL, "
                    + "updated_by TEXT, updated_at TEXT NOT NULL)");
            s.execute("INSERT INTO building_plan_draft VALUES ('b1820f4d', 'dev', 'buildsite_0009', 'id: x', 'sha', "
                    + "1, 'RECOVERED', '', '7a1ab38d', '', '', '', '', '', '', 19, 14, 0, 'owner', "
                    + "'2026-10-12T10:00:00.000Z', 'owner', '2026-10-12T10:00:00.000Z')");
        }

        BuildingPlanDraftStore drafts = new BuildingPlanDraftStore(db);
        new BuildingPlanDraftStore(db); // une seconde ouverture ne refait rien

        BuildingPlanDraft d = drafts.find("b1820f4d").orElseThrow();
        assertEquals("id: x", d.plan(), "le brouillon est intact");
        assertEquals("", d.projectId());
        assertEquals(java.util.List.of("buildsite_0009"), drafts.sitesWithUnattachedDrafts("dev"));
        assertEquals(1, drafts.attachSiteDrafts("dev", "buildsite_0009", "projet-1"));
        assertEquals(0, drafts.attachSiteDrafts("dev", "buildsite_0009", "projet-2"), "un seul rattachement");
        assertEquals("projet-1", drafts.find("b1820f4d").orElseThrow().projectId());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT site_id FROM building_plan_draft WHERE id = 'b1820f4d'")) {
            assertTrue(rs.next());
            assertEquals("buildsite_0009", rs.getString(1), "l'emplacement d'origine reste noté");
        }
    }
}
