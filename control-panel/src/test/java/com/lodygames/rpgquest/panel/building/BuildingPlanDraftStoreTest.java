package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.Check;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore.SaveOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #256 — le stockage des brouillons de plan : rien ne s'écrase, une version périmée ne
 * s'enregistre pas, un jeton payant ne sert qu'une fois, et tout survit à un redémarrage.
 */
class BuildingPlanDraftStoreTest {

    private static final String PLAN = "id: bibliotheque_du_hub\nname: \"Bibliothèque\"\nsteps: []\n";

    @TempDir
    Path tmp;

    private BuildingPlanDraftStore open() {
        return new BuildingPlanDraftStore(tmp.resolve("cp.db").toString());
    }

    private static BuildingPlanDraftStore.NewDraft proposal(String plan) {
        return new BuildingPlanDraftStore.NewDraft("dev", "buildsite_0009", plan,
                BuildingPlanDraft.Origin.AI_PROPOSAL, "", "", "openai", "OpenAI", "gpt-4o",
                "1200 + 900 jetons", "```yaml\n" + plan + "```", "", 19, 14, "owner");
    }

    @Test
    void aCreatedDraftStartsAtVersionOneWithItsFirstRevision() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft draft = store.create(proposal(PLAN));

        assertEquals(1, draft.version());
        assertEquals(PLAN, draft.plan());
        assertEquals("bibliotheque_du_hub", draft.buildingId());
        assertEquals(BuildingPlanDraftStore.sha256(PLAN), draft.planSha());
        assertEquals(1, store.revisions(draft.id()).size());
        assertEquals(List.of(draft.id()),
                store.forSite("dev", "buildsite_0009").stream().map(BuildingPlanDraft::id).toList());
    }

    /** La reprise après redémarrage du panel : une nouvelle instance relit tout. */
    @Test
    void everythingSurvivesAReopenOfTheDatabase() {
        BuildingPlanDraftStore first = open();
        BuildingPlanDraft draft = first.create(proposal(PLAN));
        first.save(draft.id(), 1, PLAN + "# v2\n", "owner");
        first.link(draft.id(), "action-1", Check.AUDIT, 2);
        first.reserve("token-1", "owner");
        first.fulfil("token-1", draft.id());

        BuildingPlanDraftStore restarted = open();
        BuildingPlanDraft again = restarted.find(draft.id()).orElseThrow();
        assertEquals(2, again.version());
        assertEquals(PLAN + "# v2\n", again.plan());
        assertEquals("gpt-4o", again.model(), "les métadonnées de l'appel payé sont conservées");
        assertEquals(2, restarted.revisions(draft.id()).size());
        assertEquals(2, restarted.links(draft.id(), Check.AUDIT).get(0).version());
        assertEquals(draft.id(), restarted.reserve("token-1", "owner").draftId(),
                "un jeton consommé avant le redémarrage le reste après");
    }

    @Test
    void aSaveOnTheCurrentVersionCreatesANewRevisionAndKeepsTheOldOne() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft draft = store.create(proposal(PLAN));

        var saved = store.save(draft.id(), 1, PLAN + "# retouché\n", "owner");

        assertEquals(SaveOutcome.SAVED, saved.outcome());
        assertEquals(2, saved.draft().orElseThrow().version());
        List<BuildingPlanDraftStore.Revision> revisions = store.revisions(draft.id());
        assertEquals(2, revisions.size());
        assertEquals(PLAN, revisions.get(1).plan(), "la version 1 n'est pas écrasée");
    }

    /** Deux onglets : le second, qui affichait la version 1, ne peut pas écraser la version 2. */
    @Test
    void aSaveOnAStaleVersionIsRefusedAndWritesNothing() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft draft = store.create(proposal(PLAN));
        store.save(draft.id(), 1, PLAN + "# onglet A\n", "owner");

        var late = store.save(draft.id(), 1, PLAN + "# onglet B\n", "owner");

        assertEquals(SaveOutcome.STALE, late.outcome());
        BuildingPlanDraft now = store.find(draft.id()).orElseThrow();
        assertEquals(2, now.version());
        assertTrue(now.plan().endsWith("# onglet A\n"), "la version la plus récente reste en place");
        assertEquals(2, store.revisions(draft.id()).size(), "aucune révision fantôme");
    }

    @Test
    void anIdenticalPlanIsNotANewVersion() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft draft = store.create(proposal(PLAN));

        assertEquals(SaveOutcome.UNCHANGED, store.save(draft.id(), 1, PLAN, "owner").outcome());
        assertEquals(1, store.find(draft.id()).orElseThrow().version());
    }

    /** Le double clic sur un bouton payant : la seconde soumission ne réserve rien. */
    @Test
    void aRequestTokenIsReservedOnlyOnce() {
        BuildingPlanDraftStore store = open();

        assertTrue(store.reserve("t", "owner").fresh());
        var second = store.reserve("t", "owner");
        assertFalse(second.fresh(), "le jeton est déjà pris");
        assertEquals("", second.draftId(), "l'appel est encore en cours : aucun brouillon à montrer");

        store.fulfil("t", "draft-42");
        assertEquals("draft-42", store.reserve("t", "owner").draftId(),
                "une resoumission après l'appel mène au brouillon produit");
        assertFalse(store.reserve("", "owner").fresh(), "sans jeton, jamais d'appel");
    }

    @Test
    void anActionBelongsToASingleDraft() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft a = store.create(proposal(PLAN));
        BuildingPlanDraft b = store.create(proposal(PLAN));

        store.link(a.id(), "action-1", Check.AUDIT, 1);
        store.link(b.id(), "action-1", Check.AUDIT, 1);

        assertEquals(a.id(), store.linkOf("action-1").orElseThrow().draftId());
        assertTrue(store.links(b.id(), Check.AUDIT).isEmpty());
    }

    @Test
    void archivingHidesWithoutDeleting() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft draft = store.create(proposal(PLAN));

        assertTrue(store.archive(draft.id(), "owner"));

        assertTrue(store.forSite("dev", "buildsite_0009").isEmpty());
        assertTrue(store.find(draft.id()).orElseThrow().archived(), "toujours en base");
        assertEquals(SaveOutcome.NOT_FOUND, store.save(draft.id(), 1, "x", "owner").outcome());
    }

    @Test
    void aRecoveredDraftIsFoundByItsSourceAction() {
        BuildingPlanDraftStore store = open();
        BuildingPlanDraft draft = store.create(new BuildingPlanDraftStore.NewDraft("dev",
                "buildsite_0009", PLAN, BuildingPlanDraft.Origin.RECOVERED, "", "7a1ab38d",
                "", "", "", "", "", "", 19, 14, "owner"));

        assertEquals(draft.id(), store.recoveredFrom("7a1ab38d").orElseThrow().id());
        assertTrue(store.recoveredFrom("autre").isEmpty());
    }
}
