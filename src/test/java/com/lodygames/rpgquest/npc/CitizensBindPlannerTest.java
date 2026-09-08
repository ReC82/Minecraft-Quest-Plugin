package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.NpcBindingRepository.Binding;
import com.lodygames.rpgquest.npc.CitizensBindPlanner.Action;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Décision de liaison (#81 phase 1) : collisions, idempotence — pur, sans Bukkit. */
class CitizensBindPlannerTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void freeCitizensAndFreeNpcId_isInsert() {
        var plan = CitizensBindPlanner.plan("woodcutter_bob", B, 14, List.of(
                new Binding(A, 6, "guard")));
        assertEquals(Action.INSERT, plan.action());
    }

    @Test
    void exactSameBinding_isNoop() {
        var plan = CitizensBindPlanner.plan("guard", A, 6, List.of(new Binding(A, 6, "guard")));
        assertEquals(Action.NOOP, plan.action());
        assertEquals("NOOP", plan.code());
    }

    @Test
    void citizensAlreadyBoundToAnotherNpc_isRejected() {
        var plan = CitizensBindPlanner.plan("woodcutter_bob", A, 6, List.of(new Binding(A, 6, "guide")));
        assertEquals(Action.REJECT, plan.action());
        assertEquals("CITIZENS_TAKEN", plan.code());
        assertTrue(plan.message().contains("guide"), plan.message());
    }

    @Test
    void npcIdAlreadyBoundToAnotherCitizens_isRejected() {
        var plan = CitizensBindPlanner.plan("guard", B, 14, List.of(new Binding(A, 6, "guard")));
        assertEquals(Action.REJECT, plan.action());
        assertEquals("NPC_ID_TAKEN", plan.code());
        assertTrue(plan.message().contains("#6"), plan.message());
    }

    @Test
    void noExistingBindings_isInsert() {
        assertEquals(Action.INSERT, CitizensBindPlanner.plan("guard", A, 6, List.of()).action());
    }
}
