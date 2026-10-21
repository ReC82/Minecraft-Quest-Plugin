package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lodygames.rpgquest.panel.building.BuildingDesignRunStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issue #655 — la page d'un projet s'ouvre sur la version RETENUE par la dernière conception
 * automatique, pas sur la dernière version refusée empilée après elle.
 */
class WorkshopDefaultDraft655Test {

    private static final Instant T0 = Instant.parse("2026-10-20T20:54:00Z");

    private static BuildingPlanDraft draft(String id, int seconds) {
        return new BuildingPlanDraft(id, "rpgquest-dev", "", "id: x", "sha", 1,
                BuildingPlanDraft.Origin.AI_CORRECTION, "", "", "", "", "", "", "", "", 12, 12, 10, false,
                "owner", T0.plusSeconds(seconds), "owner", T0.plusSeconds(seconds), "p");
    }

    private static Optional<BuildingDesignRunStore.Run> run(BuildingDesignRunStore.Status status, String fin,
                                                            int finishedAt) {
        return Optional.of(new BuildingDesignRunStore.Run("r", "rpgquest-dev", "p", "openai", "EXIGEANT", 12, 12,
                0, 0, status, "", fin, List.of(), "owner", T0, T0.plusSeconds(finishedAt), false, false));
    }

    /** L'ordre de la base : la plus récente d'abord (f8540b38 … 35ce1e1a … 7de85014). */
    private final List<BuildingPlanDraft> drafts = List.of(draft("f8540b38", 269), draft("ee1d8ac7", 131),
            draft("35ce1e1a", 104), draft("7de85014", 22));

    @Test
    void theRetainedVersionOfAFinishedRunIsShownRatherThanTheLastRefusedOne() {
        assertEquals("35ce1e1a", WorkshopPages.defaultDraft(drafts,
                run(BuildingDesignRunStore.Status.SUCCEEDED, "35ce1e1a", 284)).id());
    }

    @Test
    void theLatestVersionWinsWhileRunningWithoutResultOrAfterNewerWork() {
        assertEquals("f8540b38", WorkshopPages.defaultDraft(drafts,
                run(BuildingDesignRunStore.Status.RUNNING, "", 284)).id());
        assertEquals("f8540b38", WorkshopPages.defaultDraft(drafts,
                run(BuildingDesignRunStore.Status.STOPPED, "", 284)).id());
        assertEquals("f8540b38", WorkshopPages.defaultDraft(drafts,
                run(BuildingDesignRunStore.Status.SUCCEEDED, "35ce1e1a", 200)).id(),
                "une version créée après la fin de la session est le travail en cours");
        assertEquals("f8540b38", WorkshopPages.defaultDraft(drafts, Optional.empty()).id());
        assertNull(WorkshopPages.defaultDraft(List.of(), Optional.empty()));
    }
}
