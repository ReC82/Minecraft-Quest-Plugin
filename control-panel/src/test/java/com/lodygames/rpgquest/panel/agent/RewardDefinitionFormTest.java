package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #474 — formulaires PlugAdmin du catalogue des récompenses → paramètres agent. */
class RewardDefinitionFormTest {

    private static Map<String, String> form(String... kv) {
        Map<String, String> form = new HashMap<>(Map.of("create", "true", "label", "Épée du Héros !",
                "enabled", "true"));
        for (int i = 0; i < kv.length; i += 2) {
            form.put(kv[i], kv[i + 1]);
        }
        return form;
    }

    private static AgentActionCatalog.Validation save(Map<String, String> form) {
        return AgentActionCatalog.validate("reward.definition.save", form);
    }

    @Test
    void aNewRewardGetsItsKeyFromTheNameAndNormalisedItems() {
        AgentActionCatalog.Validation v = save(form(
                "item_id_0", "diamond_sword", "item_amount_0", "", "item_ench_0", "Sharpness 3, minecraft:unbreaking:2",
                "item_id_2", "POTION", "item_amount_2", "2", "item_potion_2", "minecraft:strength",
                "item_id_3", "rpgquest:return_stone"));
        assertTrue(v.valid(), v.error());
        assertEquals("epee_du_heros", v.params().get("reward_key"));
        assertEquals("DIAMOND_SWORD@1+ench=sharpness:3,unbreaking:2;POTION@2+potion=strength;rpgquest:return_stone@1",
                v.params().get("items"));
        assertEquals("true", v.params().get("create"));
        assertEquals("true", v.params().get("enabled"));
        assertNull(v.params().get("perk_key"));
    }

    @Test
    void editingKeepsTheKeyAndSendsTheVersion() {
        AgentActionCatalog.Validation v = save(form("create", "false", "reward_key", "gem.rare",
                "expected_version", "1a2b3c4d", "enabled", "", "item_id_0", "EMERALD", "item_amount_0", "3",
                "perk_key", "title", "perk_tier", "GOLD"));
        assertTrue(v.valid(), v.error());
        assertEquals("gem.rare", v.params().get("reward_key"), "une clé existante avec un point reste éditable");
        assertEquals("1a2b3c4d", v.params().get("expected_version"));
        assertEquals("false", v.params().get("enabled"));
        assertEquals("title", v.params().get("perk_key"));
        assertEquals("GOLD", v.params().get("perk_tier"));
    }

    @Test
    void badInputsAreRefusedWithAReadableReason() {
        assertTrue(save(form()).error().contains("au moins un objet"));
        assertTrue(save(form("item_id_0", "DIAMOND", "item_amount_0", "65")).error().contains("1 et 64"));
        assertTrue(save(form("item_id_0", "pas un objet")).error().contains("Choisir dans la liste"));
        assertTrue(save(form("item_id_0", "DIAMOND_SWORD", "item_ench_0", "sharpness 11")).error().contains("niveau 1 à 10"));
        assertFalse(save(form("item_id_0", "DIAMOND_SWORD", "item_ench_0", "sharpness 2, sharpness 3")).valid());
        assertTrue(save(form("item_ench_1", "sharpness 1")).error().contains("Objet 2"));
        assertTrue(save(form("item_id_0", "DIAMOND", "perk_key", "title")).error().contains("ET le palier"));
        assertTrue(save(form("item_id_0", "DIAMOND", "reward_key", "off")).error().contains("oui/non"));
        assertTrue(save(form("item_id_0", "DIAMOND", "reward_key", "gem.rare")).error().contains("Identifiant"),
                "pas de point dans une nouvelle clé");
        assertTrue(save(form("label", "", "item_id_0", "DIAMOND")).error().contains("Nom"));
    }

    @Test
    void duplicateToggleInitAndGlobalSwitch() {
        AgentActionCatalog.Validation dup = AgentActionCatalog.validate("reward.definition.duplicate",
                Map.of("reward_key", "gem", "new_key", "gem_v2", "new_label", ""));
        assertTrue(dup.valid(), dup.error());
        assertEquals("gem_v2", dup.params().get("new_key"));
        assertFalse(AgentActionCatalog.validate("reward.definition.duplicate",
                Map.of("reward_key", "gem", "new_key", "yes")).valid());

        AgentActionCatalog.Validation toggle = AgentActionCatalog.validate("reward.definition.toggle",
                Map.of("reward_key", "gem", "enabled", "false"));
        assertEquals("false", toggle.params().get("enabled"));

        assertFalse(AgentActionCatalog.validate("reward.catalog.init", Map.of()).valid(), "confirmation obligatoire");
        assertTrue(AgentActionCatalog.validate("reward.catalog.init", Map.of("confirm", "yes")).valid());
        assertFalse(AgentActionCatalog.validate("reward.catalog.toggle", Map.of("enabled", "true")).valid());
        AgentActionCatalog.Validation on = AgentActionCatalog.validate("reward.catalog.toggle",
                Map.of("enabled", "true", "confirm", "yes"));
        assertEquals("true", on.params().get("enabled"));
    }
}
