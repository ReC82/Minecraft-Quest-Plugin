package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #177 tranche 2 — formulaire PlugAdmin d'une table de récompenses → paramètres agent. */
class RewardTableFormTest {

    private static Map<String, String> form(String... kv) {
        Map<String, String> form = new HashMap<>(Map.of("create", "true", "label", "Butin de l'Ogre !",
                "mode", "single", "enabled", "true"));
        for (int i = 0; i < kv.length; i += 2) {
            form.put(kv[i], kv[i + 1]);
        }
        return form;
    }

    private static AgentActionCatalog.Validation validate(Map<String, String> form) {
        return AgentActionCatalog.validate("reward.table.save", form);
    }

    @Test
    void aNewTableGetsItsIdFromTheNameAndNormalisedEntries() {
        AgentActionCatalog.Validation v = validate(form("entry_reward_0", "gem.rare", "entry_chance_0", "12,5",
                "entry_reward_2", "book", "entry_chance_2", "0.01"));
        assertTrue(v.valid(), v.error());
        assertEquals("butin_de_l_ogre", v.params().get("table_id"));
        assertEquals("gem_rare=gem.rare@12.5;book=book@0.01", v.params().get("entries"));
        assertEquals("true", v.params().get("create"));
        assertEquals("single", v.params().get("mode"));
        assertEquals("true", v.params().get("enabled"));
    }

    @Test
    void existingEntryIdsAreKeptAndNeverReusedByANewLine() {
        AgentActionCatalog.Validation v = validate(form("create", "false", "table_id", "ogre",
                "expected_version", "1a2b3c4d",
                "entry_reward_0", "gem", "entry_chance_0", "10",
                "entry_id_1", "gem", "entry_reward_1", "book", "entry_chance_1", "5"));
        assertTrue(v.valid(), v.error());
        assertEquals("gem_2=gem@10;gem=book@5", v.params().get("entries"));
        assertEquals("1a2b3c4d", v.params().get("expected_version"));
        assertEquals("false", v.params().get("create"));
    }

    @Test
    void clearRefusalsForANonTechnicalUser() {
        assertTrue(validate(form("entry_reward_0", "gem", "entry_chance_0", "60", "entry_reward_1", "book",
                "entry_chance_1", "50")).error().contains("110 %"));
        assertTrue(validate(form()).error().contains("au moins une récompense"));
        assertTrue(validate(form("entry_reward_0", "gem")).error().startsWith("Ligne 1 (gem)"));
        assertTrue(validate(form("entry_chance_0", "5")).error().contains("choisir une récompense"));
        assertTrue(validate(form("entry_reward_0", "gem", "entry_chance_0", "12.345")).error().contains("deux décimales"));
        assertTrue(validate(form("entry_reward_0", "gem", "entry_chance_0", "0")).error().contains("0,01 et 100"));
        assertTrue(validate(form("entry_reward_0", "gem", "entry_chance_0", "100.5")).error().contains("0,01 et 100"));
        assertTrue(validate(form("mode", "", "entry_reward_0", "gem", "entry_chance_0", "5")).error()
                .contains("Choisir comment"));
        assertTrue(validate(form("label", "Off", "entry_reward_0", "gem", "entry_chance_0", "5")).error()
                .contains("oui/non"));
        assertTrue(validate(form("label", "a\nb", "entry_reward_0", "gem", "entry_chance_0", "5")).error()
                .contains("une ligne"));
    }

    @Test
    void independentChancesMayExceedAHundredPercent() {
        AgentActionCatalog.Validation v = validate(form("mode", "independent", "entry_reward_0", "gem",
                "entry_chance_0", "80", "entry_reward_1", "book", "entry_chance_1", "80"));
        assertTrue(v.valid(), v.error());
    }

    @Test
    void duplicateAndToggleAreValidated() {
        Map<String, String> params = new LinkedHashMap<>();
        assertNull(RewardTableForm.fillDuplicate(Map.of("table_id", "ogre", "new_id", "ogre_v2"), params));
        assertEquals(Map.of("table_id", "ogre", "new_id", "ogre_v2"), params);
        assertTrue(RewardTableForm.fillDuplicate(Map.of("table_id", "ogre", "new_id", "ogre"), new HashMap<>())
                .contains("différent"));
        assertTrue(RewardTableForm.fillDuplicate(Map.of("table_id", "ogre", "new_id", "Ogre V2!"), new HashMap<>())
                != null);
        params.clear();
        assertNull(RewardTableForm.fillToggle(Map.of("table_id", "ogre", "enabled", "false"), params));
        assertEquals("false", params.get("enabled"));
    }

    @Test
    void helpers() {
        assertEquals("butin_de_l_ogre", RewardTableForm.slug("  Butin de l'Ogre ! "));
        assertEquals("epee_eclatante", RewardTableForm.slug("Épée éclatante"));
        assertEquals(1250, RewardTableForm.hundredths("12,5"));
        assertEquals(10_000, RewardTableForm.hundredths("100"));
        assertNull(RewardTableForm.hundredths("1000"));
        assertEquals("12,5 %", RewardTableForm.percentText(1250));
        assertEquals("0,1 %", RewardTableForm.percentText(10));
        assertEquals("0 %", RewardTableForm.percentText(0));
    }
}
