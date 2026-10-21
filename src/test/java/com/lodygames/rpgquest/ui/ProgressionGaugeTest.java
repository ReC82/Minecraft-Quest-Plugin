package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.progression.model.ProgressionCurve;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Issue #183 (retour du 20/10) : jauges de progression du journal, sur la courbe réelle. */
class ProgressionGaugeTest {

    /** 100 XP pour passer niveau 2, 200 pour le 3, 400 pour le 4 (niveau max 4). */
    private static final ProgressionCurve CURVE = new ProgressionCurve(100L, 2.0, 4);

    @Test
    void aPlayerWithoutProgressionIsLevelOneAtZeroPercent() {
        ProgressionGauge gauge = ProgressionGauge.of(CURVE, SkillType.GLOBAL, 0L);

        assertEquals(1, gauge.level());
        assertEquals(0, gauge.percent());
        assertEquals(0, gauge.filledSegments());
        assertEquals(100L, gauge.xpForNextLevel());
        assertTrue(text(gauge.lore()).contains("XP : 0 / 100 pour le niveau 2"), () -> text(gauge.lore()));
    }

    @Test
    void anIntermediateGaugeShowsCurrentAndRequiredXpOfTheLevel() {
        // 100 (niveau 2 atteint) + 90 dans le niveau 2 → 90 / 200 = 45 %.
        ProgressionGauge gauge = ProgressionGauge.of(CURVE, SkillType.MINING, 190L);

        assertEquals(2, gauge.level());
        assertEquals(90L, gauge.xpIntoLevel());
        assertEquals(200L, gauge.xpForNextLevel());
        assertEquals(45, gauge.percent());
        assertEquals(4, gauge.filledSegments());
        String lore = text(gauge.lore());
        assertTrue(lore.contains("Niveau 2 / 4"), lore);
        assertTrue(lore.contains("45 %"), lore);
        assertTrue(lore.contains("XP : 90 / 200 pour le niveau 3"), lore);
        assertTrue(lore.contains("XP totale : 190"), lore);
    }

    @Test
    void anAlmostFullLevelNeverClaimsOneHundredPercent() {
        ProgressionGauge gauge = ProgressionGauge.of(CURVE, SkillType.COMBAT, 299L); // 199 / 200

        assertEquals(2, gauge.level());
        assertEquals(99, gauge.percent());
        assertEquals(9, gauge.filledSegments());
    }

    @Test
    void reachingTheNextLevelExactlyRestartsTheGaugeAtZero() {
        ProgressionGauge gauge = ProgressionGauge.of(CURVE, SkillType.COMBAT, 300L);

        assertEquals(3, gauge.level());
        assertEquals(0, gauge.percent());
    }

    @Test
    void theMaxLevelShowsAFullGaugeAndNoNextLevel() {
        ProgressionGauge gauge = ProgressionGauge.of(CURVE, SkillType.GLOBAL, CURVE.maxTotalXp());

        assertTrue(gauge.isMaxLevel());
        assertEquals(4, gauge.level());
        assertEquals(100, gauge.percent());
        assertEquals(ProgressionGauge.BAR_SEGMENTS, gauge.filledSegments());
        String lore = text(gauge.lore());
        assertTrue(lore.contains("Niveau maximal atteint"), lore);
        assertFalse(lore.contains("pour le niveau 5"), lore);
    }

    @Test
    void theBarAlwaysHasTheSameWidthAndNoTechnicalIdentifier() {
        for (long xp : new long[] {0L, 50L, 190L, 299L, CURVE.maxTotalXp()}) {
            ProgressionGauge gauge = ProgressionGauge.of(CURVE, SkillType.EXPLORATION, xp);
            String bar = PlainTextComponentSerializer.plainText().serialize(
                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(gauge.bar()));
            assertEquals(ProgressionGauge.BAR_SEGMENTS, bar.length(), bar);
            String all = text(List.of(gauge.title())) + text(gauge.lore());
            assertFalse(all.contains("EXPLORATION"), all);
            assertTrue(all.contains("Exploration"), all);
        }
    }

    private static String text(List<Component> lines) {
        StringBuilder out = new StringBuilder();
        for (Component line : lines) {
            out.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return out.toString();
    }
}
