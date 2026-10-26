package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #881 — suppression d'un projet de l'Atelier : tout ou rien, version vue, conception en cours. */
class WorkshopProjectDeletionTest {

    @TempDir
    Path tmp;

    private String db() {
        return tmp.resolve("cp.db").toString();
    }

    private BuildingProject project(String buildingId) {
        return new BuildingProjectStore(db()).create("agent", new BuildingProjectStore.Fields("P " + buildingId,
                "brief", buildingId, "WEST", 9, 9, 6), "", "owner");
    }

    private void draft(String projectId) {
        new BuildingPlanDraftStore(db()).create(new BuildingPlanDraftStore.NewDraft("agent", "", "id: x\n",
                BuildingPlanDraft.Origin.RECOVERED, "", "", "", "", "", "", "", "", 9, 9, 6, "owner", projectId));
    }

    @Test
    void deletesOnlyThisProjectAndCountsWhatGoes() {
        BuildingProject kept = project("kept_one");
        BuildingProject gone = project("gone_one");
        draft(kept.id());
        draft(gone.id());
        draft(gone.id());
        new BuildingDesignRunStore(db());
        new BuildingDesignDocStore(db());
        new ReferenceImageStore(db());
        var deletion = new WorkshopProjectDeletion(db());
        var preview = deletion.preview(gone.id());
        assertEquals(2, preview.versions());

        assertEquals(WorkshopProjectDeletion.Outcome.DELETED, deletion.delete(gone.id(), gone.version()));
        assertTrue(new BuildingProjectStore(db()).find(gone.id()).isEmpty());
        assertTrue(new BuildingPlanDraftStore(db()).forProject("agent", gone.id()).isEmpty());
        assertEquals(1, new BuildingPlanDraftStore(db()).forProject("agent", kept.id()).size());
        assertEquals(0, deletion.preview(gone.id()).versions());
        assertEquals(WorkshopProjectDeletion.Outcome.NOT_FOUND, deletion.delete(gone.id(), gone.version()),
                "le second clic n'efface rien de plus");
    }

    @Test
    void aStaleVersionOrARunningDesignDeletesNothing() {
        BuildingProject p = project("busy_one");
        draft(p.id());
        var deletion = new WorkshopProjectDeletion(db());
        assertEquals(WorkshopProjectDeletion.Outcome.STALE, deletion.delete(p.id(), p.version() + 1));
        new BuildingDesignRunStore(db()).start("agent", p.id(), "tok", "openai", "STANDARD", 3, "owner");
        assertEquals(WorkshopProjectDeletion.Outcome.BUSY, deletion.delete(p.id(), p.version()));
        assertTrue(new BuildingProjectStore(db()).find(p.id()).isPresent());
        assertEquals(1, new BuildingPlanDraftStore(db()).forProject("agent", p.id()).size(), "rien d'effacé");
    }

    @Test
    void usageIsNeverInferredFromTheLibraryAlone() {
        CatalogState inLibrary = new CatalogState(CatalogState.State.IN_LIBRARY, 2, Instant.now());
        var published = ProjectUsage.of(true, List.of(), List.of(), inLibrary, "");
        assertEquals(ProjectUsage.Usage.UNUSED, published.usage(), "publié ≠ utilisé");
        assertFalse(published.deletable(), "mais pas supprimable par surprise");

        var placed = ProjectUsage.of(true, List.of("site_a", "site_b"), List.of(), CatalogState.unknown(), "");
        assertEquals(ProjectUsage.Usage.USED, placed.usage());
        assertTrue(placed.blockers().get(0).contains("2 emplacement(s)"));

        var unknownSites = ProjectUsage.of(false, List.of(), List.of(), CatalogState.unknown(), "");
        assertEquals(ProjectUsage.Usage.UNCERTAIN, unknownSites.usage());
        assertFalse(unknownSites.deletable());

        var absent = ProjectUsage.of(true, List.of(), List.of(),
                new CatalogState(CatalogState.State.ABSENT, 1, Instant.now()), "");
        assertEquals(ProjectUsage.Usage.UNCERTAIN, absent.usage(), "disparu sans retrait connu : à vérifier");

        assertTrue(ProjectUsage.of(true, List.of(), List.of(), CatalogState.unknown(), "").deletable());
        assertTrue(ProjectUsage.of(true, List.of(), List.of(),
                new CatalogState(CatalogState.State.PURGED, 1, Instant.now()), "").deletable());
        assertTrue(ProjectUsage.of(true, List.of(), List.of(),
                new CatalogState(CatalogState.State.RETIRED, 1, Instant.now()), "").deletable(),
                "retiré : les versions archivées restent côté serveur, le projet peut partir");
        assertFalse(ProjectUsage.of(true, List.of(), List.of(), CatalogState.unknown(), "Une conception").deletable());
    }
}
