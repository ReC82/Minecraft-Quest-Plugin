package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Écriture sûre des profils de mob spécial (issue #169, lot 1) : création, refus d'écrasement,
 * modification, round-trip -- même discipline que {@code NpcDefinitionStoreTest}.
 */
class SpecialMobDefinitionStoreTest {

    @TempDir
    Path dir;

    private SpecialMobDefinition def(String key, String name, double spawnChance, MobCategory category, boolean enabled) {
        return new SpecialMobDefinition(new NamespacedKey("rpgquest", key), category, enabled, EntityType.ZOMBIE,
                name, spawnChance, Set.of(), Set.of(), Set.of(), null, null, null, null, null, null, null,
                null, null, List.of(), List.of(), null, null);
    }

    @Test
    void createWritesAFileThatReloadsIdentically() {
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        SpecialMobDefinitionStore.Result r = store.create(def("swamp_king", "Roi des Marais", 1.0, MobCategory.BOSS, true));

        assertTrue(r.ok());
        assertEquals("CREATED", r.code());
        assertEquals("swamp_king.yml", r.file());
        assertTrue(Files.exists(dir.resolve("swamp_king.yml")));
        SpecialMobDefinition reloaded = store.find(new NamespacedKey("rpgquest", "swamp_king")).orElseThrow();
        assertEquals("Roi des Marais", reloaded.displayName());
        assertEquals(MobCategory.BOSS, reloaded.category());
    }

    @Test
    void createRefusesToOverwriteAnExistingId() {
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        assertTrue(store.create(def("guard_zombie", "Zombie Garde", 0.1, MobCategory.SPECIAL, true)).ok());
        SpecialMobDefinitionStore.Result second = store.create(def("guard_zombie", "Autre", 0.1, MobCategory.SPECIAL, true));
        assertFalse(second.ok());
        assertEquals("EXISTS", second.code());
        assertEquals("Zombie Garde",
                store.find(new NamespacedKey("rpgquest", "guard_zombie")).orElseThrow().displayName(),
                "l'original est intact");
    }

    @Test
    void updateReplacesFieldsButFailsWhenAbsent() {
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        assertEquals("NOT_FOUND", store.update(def("ghost", "Ghost", 0.1, MobCategory.SPECIAL, true)).code());

        store.create(def("guard_zombie", "Zombie Garde", 0.1, MobCategory.SPECIAL, true));
        SpecialMobDefinitionStore.Result upd = store.update(def("guard_zombie", "Zombie Garde", 0.1, MobCategory.SPECIAL, false));
        assertTrue(upd.ok());
        assertEquals("UPDATED", upd.code());
        assertFalse(store.find(new NamespacedKey("rpgquest", "guard_zombie")).orElseThrow().enabled(),
                "la modification (ex. bascule enabled) doit être visible après relecture");
    }

    @Test
    void listSurfacesLoadIssues() throws Exception {
        Files.writeString(dir.resolve("broken.yml"), "id: 123\n:::not yaml");
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        assertTrue(store.list().hasIssues());
    }
}
