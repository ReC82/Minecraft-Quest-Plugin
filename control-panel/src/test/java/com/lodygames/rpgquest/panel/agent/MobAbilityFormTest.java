package com.lodygames.rpgquest.panel.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #543 — cartes de capacités du profil → paramètre « abilities » de mob.definition.update. */
class MobAbilityFormTest {

    private static Map<String, String> baseForm() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("mob_id", "golem");
        f.put("category", "BOSS");
        f.put("entity_type", "ZOMBIE");
        f.put("display_name", "Golem");
        f.put("spawn_chance", "0");
        f.put("ab_form", "1");
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> abilities(AgentActionCatalog.Validation v) {
        assertNull(v.error(), v.error());
        return (List<Map<String, Object>>) Json.parse(v.params().get("abilities"));
    }

    @Test
    void cardsAreSentInTheChosenOrderWithRemovedOnesDroppedAndNewOnesAppended() {
        Map<String, String> f = baseForm();
        f.put("ab_0_type", "ENRAGED");
        f.put("ab_0_enabled", "true");
        f.put("ab_0_pos", "2");
        f.put("ab_0_health-fraction", "0.3");
        f.put("ab_0_chance", "");
        f.put("ab_1_type", "SUMMON_ON_DAMAGE");
        f.put("ab_1_pos", "1"); // case « Active » décochée : gardée, désactivée
        f.put("ab_1_amount", "2");
        f.put("ab_2_type", "SPLIT_ON_HIT");
        f.put("ab_2_remove", "true");
        f.put("ab_new_STRONGER_EXPLOSION", "true");
        f.put("ab_new_STRONGER_EXPLOSION_radius-multiplier", "1.5");
        f.put("ab_new_EXPLOSIVE_ON_ATTACK_power", "2.0"); // non cochée : ignorée

        List<Map<String, Object>> list = abilities(AgentActionCatalog.validate("mob.definition.update", f));
        assertEquals(List.of("SUMMON_ON_DAMAGE", "ENRAGED", "STRONGER_EXPLOSION"),
                list.stream().map(m -> m.get("type")).toList());
        assertEquals("false", list.get(0).get("enabled"));
        assertEquals("2", list.get(0).get("amount"));
        assertEquals("true", list.get(1).get("enabled"));
        assertFalse(list.get(1).containsKey("chance"), "vide = défaut, non envoyé");
        assertEquals("1.5", list.get(2).get("radius-multiplier"));
        assertFalse(list.get(2).containsKey("pos"));
    }

    @Test
    void anEmptyCardListRemovesEveryAbility() {
        List<Map<String, Object>> list = abilities(AgentActionCatalog.validate("mob.definition.update", baseForm()));
        assertTrue(list.isEmpty());
    }

    @Test
    void duplicatesAndMalformedKeysAreRefusedBeforeSending() {
        Map<String, String> dup = baseForm();
        dup.put("ab_0_type", "ENRAGED");
        dup.put("ab_new_ENRAGED", "true");
        String error = AgentActionCatalog.validate("mob.definition.update", dup).error();
        assertNotNull(error);
        assertTrue(error.contains("en double"), error);

        Map<String, String> bad = baseForm();
        bad.put("ab_0_type", "ENRAGED");
        bad.put("ab_0_health fraction", "0.3");
        assertTrue(AgentActionCatalog.validate("mob.definition.update", bad).error().contains("invalide"));
    }

    @Test
    void withoutTheCardMarkerTheLegacyFlatFieldsStillWork() {
        Map<String, String> f = baseForm();
        f.remove("ab_form");
        f.put("enraged_enabled", "true");
        f.put("enraged_health_fraction", "0.3");
        f.put("enraged_speed_multiplier", "1.3");
        f.put("enraged_damage_multiplier", "1.5");
        AgentActionCatalog.Validation v = AgentActionCatalog.validate("mob.definition.update", f);
        assertNull(v.error(), v.error());
        assertFalse(v.params().containsKey("abilities"));
        assertEquals("0.3", v.params().get("enraged_health_fraction"));
    }
}
