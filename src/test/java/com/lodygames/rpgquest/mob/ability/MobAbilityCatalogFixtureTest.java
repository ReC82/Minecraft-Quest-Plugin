package com.lodygames.rpgquest.mob.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.web.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Issue #543 — les tests de PlugAdmin s'appuient sur une copie du catalogue des capacités
 * ({@code control-panel/src/test/resources/fixtures/mob-ability-catalog.json}) ; elle doit rester
 * identique à ce que publie le plugin. Régénérer : {@code RPGQUEST_REGEN_FIXTURES=1}.
 */
class MobAbilityCatalogFixtureTest {

    private static final Path FIXTURE = Path.of("control-panel/src/test/resources/fixtures/mob-ability-catalog.json");

    @Test
    void panelFixtureMatchesThePublishedCatalog() throws Exception {
        String actual = Json.write(MobAbilityExchange.catalogDetails()) + "\n";
        if ("1".equals(System.getenv("RPGQUEST_REGEN_FIXTURES"))) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, actual);
        }
        assertEquals(Files.readString(FIXTURE), actual,
                "catalogue modifié : régénérer la copie du panel (RPGQUEST_REGEN_FIXTURES=1)");
    }
}
