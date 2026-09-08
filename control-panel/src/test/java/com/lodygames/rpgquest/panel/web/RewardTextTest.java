package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Lecture admin des récompenses de quêtes (issue #77). */
class RewardTextTest {

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
