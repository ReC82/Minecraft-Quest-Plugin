package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #81 — un seul résolveur du nom affiché d'un monde ; l'identifiant technique ne change jamais. */
class WorldNamesTest {

    private static Map<String, Object> world(String name, String alias, List<String> roles) {
        return Map.of("name", name, "alias", alias, "roles", roles);
    }

    private static final Map<String, Object> RELEVE = Map.of(
            "mainWorld", "world",
            "roles", Map.of("hub", "world_hub", "wild", "terres", "claims", "claims"),
            "worlds", List.of(world("world", "", List.of()), world("world_hub", "Village des Lody", List.of("HUB")),
                    world("terres", "", List.of("WILD")), world("claims", "  ", List.of("CLAIMS")),
                    world("lody_flat", "", List.of()), world("world_nether", "world_nether", List.of())));

    @Test
    void aliasChosenByTheAdministratorWins() {
        WorldNames names = WorldNames.of(RELEVE);
        assertEquals("Village des Lody", names.name("world_hub"));
        assertEquals("Village des Lody (world_hub)", names.label("world_hub"), "identifiant toujours visible");
        assertTrue(names.hasCustomName("WORLD_HUB"));
    }

    @Test
    void withoutAliasTheRoleGivesHubClaimsWild() {
        WorldNames names = WorldNames.of(RELEVE);
        assertEquals("Wild", names.name("terres"), "défaut par rôle, même si l'identifiant n'est pas « wild »");
        assertEquals("Claims", names.name("claims"), "alias blanc ignoré");
        assertEquals("Monde principal", names.name("world"));
        assertEquals("lody_flat", names.name("lody_flat"), "monde sans rôle ni alias : son identifiant");
        assertEquals("lody_flat", names.label("lody_flat"), "pas de répétition entre parenthèses");
        assertFalse(names.hasCustomName("terres"));
        assertEquals("Nether", names.name("world_nether"), "alias = identifiant (relevé DEV 21/10) : défaut");
        assertFalse(names.hasCustomName("world_nether"));
        assertEquals("Village des Lody", names.ofRole("HUB"));
        assertEquals("Wild", names.ofRole("WILD"));
    }

    @Test
    void withoutAnySurveyTheUsualNamesApply() {
        WorldNames names = WorldNames.of(null);
        assertEquals("Hub", names.name("world_hub"));
        assertEquals("Claims", names.name("claims"));
        assertEquals("Wild", names.name("wild"));
        assertEquals("Nether", names.name("world_nether"));
        assertEquals("Hub (world_hub)", names.label("world_hub"));
        assertEquals("Hub", names.ofRole("HUB"));
        assertEquals("", names.label(null));
    }
}
