package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.WorldPortalAdminService.Kind;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortalAdminParamsTest {

    private static final String FP = "0123456789abcdef01234567";

    @Test
    void everyOperationNeedsAnExplicitConfirmation() {
        assertNotNull(PortalAdminParams.parse(Kind.DISABLE, Map.of("id", "x", "expected", FP)).error());
    }

    @Test
    void createBySiteReadsTheDimensions() {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "Arche", "name", "Arche",
                "zone_mode", "site", "site", "buildsite_0007", "width", "3", "height", "4", "depth", "1"));
        m.putAll(Map.of("destination_world", "wild", "strategy", "random_safe", "by", "lody"));
        var p = PortalAdminParams.parse(Kind.CREATE, m);

        assertNull(p.error());
        assertEquals("arche", p.operation().id());
        assertEquals("buildsite_0007", p.operation().zone().site());
        assertEquals(3, p.operation().zone().width());
        assertEquals(DestinationStrategy.RANDOM_SAFE, p.operation().destination().strategy());
        assertEquals("lody", p.operation().actor());
    }

    @Test
    void createFromAPlacedArchNeedsOnlyTheSiteAndIsRefusedOnUpdate() {
        var p = PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "porte", "zone_mode", "building",
                "site", "buildsite_0008", "destination_world", "wild"));

        assertNull(p.error());
        assertTrue(p.operation().zone().fromBuilding());
        assertEquals("buildsite_0008", p.operation().zone().site());
        assertEquals(0, p.operation().zone().width(), "aucune dimension saisie : le passage de l'arche fait foi");
        assertNotNull(PortalAdminParams.parse(Kind.UPDATE, Map.of("confirm", "true", "id", "porte", "expected", FP,
                "zone_mode", "building", "site", "buildsite_0008")).error(), "un portail existant se lie (« Lier »)");
    }

    @Test
    void coordinatesAreNormalisedMinMax() {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "arche", "zone_mode", "coords",
                "world", "world_hub", "min_x", "5", "min_y", "70", "min_z", "-3"));
        m.putAll(Map.of("max_x", "1", "max_y", "66", "max_z", "-3", "destination_world", "wild"));

        var p = PortalAdminParams.parse(Kind.CREATE, m);

        assertNull(p.error());
        assertEquals(new Zone(1, 66, -3, 5, 70, -3), p.operation().zone().explicit());
    }

    @Test
    void anUpdateWithoutZoneModeKeepsTheZoneAndWithoutNameKeepsTheName() {
        var p = PortalAdminParams.parse(Kind.UPDATE, Map.of("confirm", "true", "id", "hub_to_wild", "expected", FP,
                "confirm_essential", "true"));

        assertNull(p.error());
        assertNull(p.operation().zone());
        assertNull(p.operation().name());
        assertNull(p.operation().destination(), "sans dest_mode=set : arrivée inchangée");
        assertTrue(p.operation().confirmEssential());
    }

    @Test
    void aFixedPointArrivalReadsPositionAndLook() {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "hub_to_ville", "expected", FP,
                "dest_mode", "set", "destination_world", "ville", "strategy", "fixed_point"));
        m.putAll(Map.of("point_x", "100,5", "point_y", "64", "point_z", "-20.5", "point_yaw", "450"));

        var p = PortalAdminParams.parse(Kind.UPDATE, m);

        assertNull(p.error());
        var d = p.operation().destination();
        assertEquals("ville", d.world());
        assertEquals(DestinationStrategy.FIXED_POINT, d.strategy());
        assertEquals(new WorldPortalDefinition.FixedPoint(100.5, 64, -20.5, 90f, 0f), d.point(), "450° = 90°");
    }

    @Test
    void aFixedPointWithoutCoordinatesOrWithABadLookIsRejected() {
        Map<String, String> base = Map.of("confirm", "true", "id", "x", "zone_mode", "coords", "world", "w",
                "min_x", "0", "min_y", "0", "min_z", "0", "max_x", "0", "max_y", "0", "max_z", "0");
        Map<String, String> noPoint = new HashMap<>(base);
        noPoint.putAll(Map.of("destination_world", "ville", "strategy", "FIXED_POINT", "point_x", "1"));
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, noPoint).error());
        Map<String, String> badPitch = new HashMap<>(noPoint);
        badPitch.putAll(Map.of("point_y", "64", "point_z", "1", "point_pitch", "120"));
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, badPitch).error());
    }

    @Test
    void aReturnPortalNamesItsOutboundPortal() {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "ville_to_hub", "zone_mode", "coords",
                "world", "ville", "min_x", "0", "min_y", "64", "min_z", "0", "max_x", "1", "max_y", "65"));
        m.putAll(Map.of("max_z", "0", "destination_world", "world_hub", "return_of", "Hub_To_Ville"));

        var p = PortalAdminParams.parse(Kind.CREATE, m);

        assertNull(p.error());
        assertEquals("hub_to_ville", p.operation().returnOf());
    }

    @Test
    void malformedInputsAreRejected() {
        assertNotNull(PortalAdminParams.parse(Kind.ENABLE, Map.of("confirm", "true", "id", "x", "expected", "zz")).error());
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "x")).error());
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "x", "zone_mode", "site",
                "width", "trois", "height", "4", "depth", "1")).error());
        assertNotNull(PortalAdminParams.parse(Kind.CREATE, Map.of("confirm", "true", "id", "x", "zone_mode", "site",
                "width", "3", "height", "4", "depth", "1", "strategy", "teleport_anywhere")).error());
    }

    // ---- Conditions d'accès (issue #567) ---------------------------------------------------------

    private static Map<String, String> access(String... kv) {
        Map<String, String> m = new HashMap<>(Map.of("confirm", "true", "id", "arche", "expected", FP));
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void accessReadsLevelCooldownAndConditionRowsWithTheDialogueParser() {
        var p = PortalAdminParams.parse(Kind.ACCESS, access("level", "5", "level_message", "<red>Niveau 5.</red>",
                "cooldown", "30", "cond_0_type", "quest_state", "cond_0_quest", "crystal_hunt", "cond_0_state",
                "completed", "cond_0_message", "<red>Termine la chasse.</red>", "cond_1_type", "",
                "cond_2_type", "HAS_ITEM", "cond_2_material", "diamond", "cond_2_amount", "2",
                "cond_3_type", "HAS_MAIN_CLAIM", "cond_3_negate", "true"));

        assertNull(p.error());
        var a = p.operation().access();
        assertEquals(5, a.requiredLevel());
        assertEquals("<red>Niveau 5.</red>", a.levelMessage());
        assertEquals(30, a.cooldownSeconds());
        assertEquals(3, a.rules().size(), "ligne sans type ignorée");
        assertEquals(new com.lodygames.rpgquest.dialogue.model.QuestStateCondition(
                new org.bukkit.NamespacedKey("rpgquest", "crystal_hunt"),
                com.lodygames.rpgquest.quest.model.QuestState.COMPLETED), a.rules().get(0).condition());
        assertEquals("<red>Termine la chasse.</red>", a.rules().get(0).message());
        assertEquals(new com.lodygames.rpgquest.dialogue.model.HasItemCondition(org.bukkit.Material.DIAMOND, 2),
                a.rules().get(1).condition());
        assertEquals(new com.lodygames.rpgquest.dialogue.model.NegatedCondition(
                new com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition()), a.rules().get(2).condition());
    }

    @Test
    void anEmptyAccessFormRemovesEveryCondition() {
        var p = PortalAdminParams.parse(Kind.ACCESS, access("level", "", "cooldown", "0"));

        assertNull(p.error());
        assertTrue(p.operation().access().isEmpty());
    }

    @Test
    void accessRejectsUnknownOrUnofferedTypesAndBadValues() {
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("cond_0_type", "HAS_INVENTORY_SPACE")).error(),
                "type existant mais non proposé aux portails");
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("cond_0_type", "FREE_TEXT")).error());
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("cond_0_type", "HAS_ITEM",
                "cond_0_material", "pas_un_bloc", "cond_0_amount", "1")).error());
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("cond_0_type", "QUEST_STATE",
                "cond_0_quest", "q")).error(), "état obligatoire");
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("cond_0_type", "HAS_PERMISSION",
                "cond_0_permission", "a b")).error());
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("level", "0")).error());
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("cooldown", "-1")).error());
        assertNotNull(PortalAdminParams.parse(Kind.ACCESS, access("level_message", "x".repeat(201))).error());
    }

    // ---- portal.messages (issue #569) ------------------------------------------------

    @Test
    void messagesReadEveryTextAndTheWarningOptions() {
        var p = PortalAdminParams.parse(Kind.MESSAGES, access("msg_welcome", "<green>Salut.</green>",
                "msg_refusal", "<red>Non.</red>", "warning_mode", "always", "warn_title", "<gold>Attention</gold>",
                "warn_allow_hide", "false"));

        assertNull(p.error());
        var m = p.operation().messages();
        assertEquals("<green>Salut.</green>", m.welcome());
        assertEquals("<red>Non.</red>", m.refusal());
        assertEquals("", m.success(), "absent = défaut");
        assertEquals(com.lodygames.rpgquest.travel.model.PortalMessages.Mode.ALWAYS, m.warning().mode());
        assertEquals("<gold>Attention</gold>", m.warning().title());
        assertFalse(m.warning().allowHide());
    }

    @Test
    void anEmptyMessagesFormRestoresEveryDefault() {
        var p = PortalAdminParams.parse(Kind.MESSAGES, access("msg_welcome", "  ", "warning_mode", ""));

        assertNull(p.error());
        assertTrue(p.operation().messages().isEmpty());
    }

    @Test
    void messagesRejectAnUnknownModeOrTooLongTexts() {
        assertNotNull(PortalAdminParams.parse(Kind.MESSAGES, access("warning_mode", "PARFOIS")).error());
        assertNotNull(PortalAdminParams.parse(Kind.MESSAGES, access("warn_allow_hide", "oui")).error());
        assertNotNull(PortalAdminParams.parse(Kind.MESSAGES, access("msg_success", "x".repeat(201))).error());
        assertNull(PortalAdminParams.parse(Kind.MESSAGES, access("warn_text", "x".repeat(500))).error(),
                "le texte de l'avertissement peut être plus long");
        assertNotNull(PortalAdminParams.parse(Kind.MESSAGES, access("warn_text", "x".repeat(501))).error());
    }
}
