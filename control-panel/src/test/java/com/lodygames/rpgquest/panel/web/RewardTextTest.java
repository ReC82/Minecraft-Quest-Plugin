package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Lecture admin des récompenses de quêtes (issue #77 ; structuration #78). */
class RewardTextTest {

    private static Map<String, Object> summary(String kind, Object amount, String target, String value, String command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("amount", amount);
        m.put("target", target);
        m.put("value", value);
        m.put("command", command);
        m.put("raw", "raw-" + kind);
        return m;
    }

    @Test
    void structuredXpRewardIsReadable() {
        RewardText.Reward r = RewardText.fromSummary(summary("EXPERIENCE", 100L, null, null, null));
        assertEquals("+100 XP", r.label());
        assertNull(r.rawDetail());
    }

    @Test
    void structuredItemRewardUsesFrenchNameAndKeepsToken() {
        RewardText.Reward r = RewardText.fromSummary(summary("ITEM", 1L, "DIAMOND_SWORD", null, null));
        assertEquals("Objet : Épée en diamant ×1", r.label());
        assertEquals("DIAMOND_SWORD", r.rawDetail());
    }

    @Test
    void structuredVariableRewardIsSummarised() {
        RewardText.Reward unlock = RewardText.fromSummary(summary("VARIABLE", 0L, "CLAIM_TIER_1", "true", null));
        assertEquals("Débloque : Claim Tier 1", unlock.label());
        assertEquals("variable CLAIM_TIER_1 = true", unlock.rawDetail());
    }

    @Test
    void structuredCommandRewardIsNeverTruncated() {
        String longCmd = "customitem give %player% rpgquest:miner_pickaxe 1 && lp user %player% permission set "
                + "rpgquest.claim.tier2 true && broadcast une très longue phrase de félicitations pour le joueur";
        RewardText.Reward r = RewardText.fromSummary(summary("COMMAND", 0L, null, null, longCmd));
        assertEquals("Objet : Miner Pickaxe ×1", r.label());
        assertEquals(longCmd, r.rawDetail());
        assertFalse(r.rawDetail().contains("…"), "aucune troncature");
    }

    @Test
    void unknownStructuredKindFallsBackToRawParse() {
        Map<String, Object> m = summary("MYSTERY", 0L, null, null, null);
        m.put("raw", "+7 XP");
        assertEquals("+7 XP", RewardText.fromSummary(m).label());
    }

    @Test
    void xpRewardStaysAsIsWithoutTechnicalDetail() {
        RewardText.Reward r = RewardText.parse("+100 XP");
        assertEquals("+100 XP", r.label());
        assertNull(r.rawDetail());
    }

    @Test
    void itemRewardBecomesReadableAndKeepsRawToken() {
        RewardText.Reward r = RewardText.parse("+1x DIAMOND_SWORD");
        assertEquals("Objet : Épée en diamant ×1", r.label());
        assertEquals("DIAMOND_SWORD", r.rawDetail());
    }

    @Test
    void variableRewardIsSummarisedAsUnlockOrValue() {
        RewardText.Reward unlock = RewardText.parse("variable CLAIM_TIER_1 = true");
        assertEquals("Débloque : Claim Tier 1", unlock.label());
        assertEquals("variable CLAIM_TIER_1 = true", unlock.rawDetail());

        RewardText.Reward valued = RewardText.parse("variable reputation = 3");
        assertTrue(valued.label().startsWith("Variable : Reputation"), valued.label());
        assertEquals("variable reputation = 3", valued.rawDetail());
    }

    @Test
    void knownRewardCommandsAreSummarisedButRawKept() {
        RewardText.Reward custom = RewardText.parse(
                "commande console : customitem give %player% rpgquest:miner_pickaxe 2");
        assertEquals("Objet : Miner Pickaxe ×2", custom.label());
        assertEquals("customitem give %player% rpgquest:miner_pickaxe 2", custom.rawDetail());

        RewardText.Reward give = RewardText.parse("commande console : give %player% OAK_PLANKS 16");
        assertEquals("Objet : Planches de chêne ×16", give.label());

        RewardText.Reward xp = RewardText.parse("commande console : xp add %player% 50");
        assertEquals("+50 XP", xp.label());
        assertEquals("xp add %player% 50", xp.rawDetail());
    }

    @Test
    void unknownCommandKeepsFullRawAndNeutralLabel() {
        RewardText.Reward r = RewardText.parse("commande console : lp user %player% permission set foo.bar true");
        assertEquals("Commande de récompense", r.label());
        assertEquals("lp user %player% permission set foo.bar true", r.rawDetail());
    }

    @Test
    void unknownFreeFormRewardIsNeverHiddenAndNeverThrows() {
        RewardText.Reward r = RewardText.parse("Titre special deverrouille");
        assertTrue(r.label().length() > 0);
        assertNull(r.rawDetail());
        assertEquals("—", RewardText.parse(null).label());
        assertEquals("—", RewardText.parse("   ").label());
    }
}
