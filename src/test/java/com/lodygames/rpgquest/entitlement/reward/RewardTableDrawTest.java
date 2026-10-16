package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #177 — sémantique des tirages, déterminisme et clés des gains. */
class RewardTableDrawTest {

    private static RewardTable.Entry entry(String id, int chance) {
        return new RewardTable.Entry(id, "reward_" + id, chance);
    }

    private static RewardTable table(RewardTable.Mode mode, RewardTable.Entry... entries) {
        return new RewardTable("loot", "Butin", mode, true, List.of(entries));
    }

    @Test
    void theSameSeedAlwaysGivesTheSameResult() {
        RewardTable table = table(RewardTable.Mode.INDEPENDENT, entry("a", 3000), entry("b", 5000), entry("c", 100));
        for (int i = 0; i < 200; i++) {
            String seed = "quest:" + UUID.randomUUID();
            assertEquals(RewardTableDraw.draw(table, seed), RewardTableDraw.draw(table, seed));
        }
    }

    @Test
    void singleModeNeverGivesMoreThanOneAndKeepsTheRealChances() {
        RewardTable table = table(RewardTable.Mode.SINGLE, entry("a", 2500), entry("b", 2500));
        int a = 0;
        int b = 0;
        int none = 0;
        for (int i = 0; i < 20_000; i++) {
            List<RewardTable.Entry> won = RewardTableDraw.draw(table, "seed-" + i);
            assertTrue(won.size() <= 1);
            if (won.isEmpty()) {
                none++;
            } else if (won.get(0).id().equals("a")) {
                a++;
            } else {
                b++;
            }
        }
        // 25 % / 25 % / 50 % aucun gain — jamais renormalisé en 50 / 50.
        assertEquals(5000, a, 400);
        assertEquals(5000, b, 400);
        assertEquals(10_000, none, 500);
    }

    @Test
    void independentModeCanGiveSeveralOrNothing() {
        RewardTable table = table(RewardTable.Mode.INDEPENDENT, entry("a", 5000), entry("b", 5000));
        boolean several = false;
        boolean nothing = false;
        int a = 0;
        for (int i = 0; i < 4000; i++) {
            List<RewardTable.Entry> won = RewardTableDraw.draw(table, "s" + i);
            several |= won.size() == 2;
            nothing |= won.isEmpty();
            a += won.stream().anyMatch(e -> e.id().equals("a")) ? 1 : 0;
        }
        assertTrue(several);
        assertTrue(nothing);
        assertEquals(2000, a, 200);
    }

    @Test
    void certainAndDisabledTables() {
        assertEquals(1, RewardTableDraw.draw(table(RewardTable.Mode.SINGLE, entry("a", RewardTable.FULL)), "x").size());
        RewardTable off = new RewardTable("loot", "Butin", RewardTable.Mode.SINGLE, false,
                List.of(entry("a", RewardTable.FULL)));
        assertTrue(RewardTableDraw.draw(off, "x").isEmpty());
    }

    @Test
    void winsBecomeIdempotentDeclarationsThatFitTheKeyColumn() {
        UUID player = UUID.randomUUID();
        RewardTable table = new RewardTable("a".repeat(32), "Butin", RewardTable.Mode.SINGLE, true,
                List.of(new RewardTable.Entry("e".repeat(24), "gem", RewardTable.FULL)));
        String base = "quest:" + UUID.randomUUID();
        List<RewardTableDraw.Win> wins = RewardTableDraw.wins(table, base, player, RewardSource.QUEST,
                "rpgquest:q", key -> "Gemme");
        assertEquals(1, wins.size());
        RewardEntitlement.Declaration declaration = wins.get(0).declaration();
        assertEquals(base + ":" + table.id() + "." + "e".repeat(24) + ":" + player, declaration.idempotencyKey());
        assertTrue(declaration.idempotencyKey().length() <= RewardEntitlement.MAX_KEY_LENGTH);
        assertEquals("gem", declaration.rewardKey());
        assertEquals("Gemme — table " + table.id() + " v" + table.version() + " (100 %)", declaration.label());
        assertEquals(wins, RewardTableDraw.wins(table, base, player, RewardSource.QUEST, "rpgquest:q", key -> "Gemme"));
    }

    @Test
    void versionChangesWithTheContentThatDecidesTheDraw() {
        RewardTable a = table(RewardTable.Mode.SINGLE, entry("a", 1000));
        assertEquals(a.version(), new RewardTable("loot", "Autre nom", RewardTable.Mode.SINGLE, true,
                List.of(entry("a", 1000))).version());
        assertTrue(!a.version().equals(table(RewardTable.Mode.SINGLE, entry("a", 1001)).version()));
        assertTrue(!a.version().equals(table(RewardTable.Mode.INDEPENDENT, entry("a", 1000)).version()));
    }

    @Test
    void percentAndExplanation() {
        assertEquals("12,5 %", RewardTable.percent(1250));
        assertEquals("12,05 %", RewardTable.percent(1205));
        assertEquals("100 %", RewardTable.percent(10_000));
        assertEquals("0,01 %", RewardTable.percent(1));
        assertTrue(RewardTableDraw.explain(table(RewardTable.Mode.SINGLE, entry("a", 2500), entry("b", 1250)))
                .contains("aucun gain : 62,5 %"));
        String independent = RewardTableDraw.explain(table(RewardTable.Mode.INDEPENDENT, entry("a", 5000),
                entry("b", 5000)));
        assertTrue(independent.contains("Aucun gain : 25,00 %"), independent);
        assertTrue(independent.contains("1,00"), independent);
    }
}
