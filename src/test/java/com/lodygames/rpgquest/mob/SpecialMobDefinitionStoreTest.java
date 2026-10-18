package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.BossMode;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;
import org.bukkit.Material;
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
    void aProfileNamedLikeASettingsFileIsRefusedAndTheSettingsAreUntouched() throws Exception {
        // Issue #169 : mobs/community-bosses.yml porte les réglages du scheduler, jamais un profil.
        String settings = "enabled: true\ninterval-minutes: 15\n";
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("community-bosses.yml"), settings);
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);

        SpecialMobDefinitionStore.Result r = store.create(def("community-bosses", "X", 0.1, MobCategory.BOSS, true));

        assertFalse(r.ok());
        assertEquals("INVALID", r.code());
        assertEquals(settings, Files.readString(dir.resolve("community-bosses.yml")));
        assertEquals("NOT_FOUND", store.update(def("spawn-settings", "X", 0.1, MobCategory.SPECIAL, true)).code());
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

    // ---- Issue #461 : listes multivaluées, validation avant écriture, rollback ----------------

    private SpecialMobDefinition diagBoss(Set<String> biomes, boolean enabled, BossMode mode) {
        return new SpecialMobDefinition(new NamespacedKey("rpgquest", "claude_diag_boss"), MobCategory.BOSS, enabled,
                EntityType.WITHER_SKELETON, "<red>Diag Boss</red>", 0.01, Set.of("wild"), biomes, Set.of(),
                200.0, null, null, null, null, null, null, null, null,
                List.of(new EnragedAbility(0.3, 1.5, 2.0), new SummonOnDamageAbility(EntityType.ZOMBIE, 2, 0.5, 30, 4)),
                List.of(new VanillaItemDrop(Material.EMERALD, 100, 1, 3)), 50, null, mode, 3, List.of("boss_token"));
    }

    @Test
    void multiBiomeCommunityUpdateWritesAValidFileAndKeepsDropsAndAbilities() throws Exception {
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        assertTrue(store.create(diagBoss(Set.of("swamp", "plains"), false, BossMode.QUEST)).ok());

        SpecialMobDefinitionStore.Result r = store.update(diagBoss(Set.of("swamp", "plains"), true, BossMode.COMMUNITY));

        assertTrue(r.ok(), r.message());
        assertFalse(store.list().hasIssues(), store.list().issues().toString());
        SpecialMobDefinition reloaded = store.find(new NamespacedKey("rpgquest", "claude_diag_boss")).orElseThrow();
        assertEquals(Set.of("wild"), reloaded.allowedWorlds());
        assertEquals(Set.of("swamp", "plains"), reloaded.allowedBiomes());
        assertEquals(BossMode.COMMUNITY, reloaded.bossMode());
        assertTrue(reloaded.enabled());
        assertEquals(2, reloaded.abilities().size());
        assertEquals(1, reloaded.drops().size());
        assertEquals(List.of("boss_token"), reloaded.participationRewards());
    }

    @Test
    void aValueWithALineBreakIsRefusedBeforeWritingAndTheFileIsUntouched() throws Exception {
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        assertTrue(store.create(diagBoss(Set.of("swamp", "plains"), false, BossMode.QUEST)).ok());
        byte[] before = Files.readAllBytes(dir.resolve("claude_diag_boss.yml"));

        SpecialMobDefinitionStore.Result r = store.update(diagBoss(Set.of("swamp\r\nplains"), true, BossMode.COMMUNITY));

        assertFalse(r.ok());
        assertEquals("INVALID", r.code());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(dir.resolve("claude_diag_boss.yml"))),
                "rien n'est écrit quand la validation échoue");
    }

    @Test
    void aProfileThatDoesNotReloadAfterWritingIsRolledBackByteForByte() throws Exception {
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        assertTrue(store.create(diagBoss(Set.of("swamp"), false, BossMode.QUEST)).ok());
        byte[] before = Files.readAllBytes(dir.resolve("claude_diag_boss.yml"));
        // Un second fichier au même id : le dossier ne recharge plus le profil après écriture.
        Files.writeString(dir.resolve("zz_duplicate.yml"), "id: rpgquest:claude_diag_boss\nentity-type: ZOMBIE\n"
                + "name: \"Doublon\"\nspawn-chance: 0.1\n");

        SpecialMobDefinitionStore.Result r = store.update(diagBoss(Set.of("swamp", "plains"), true, BossMode.COMMUNITY));

        assertFalse(r.ok());
        assertEquals("ROLLED_BACK", r.code());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(dir.resolve("claude_diag_boss.yml"))),
                "l'ancienne version est remise à l'identique");
    }

    @Test
    void theBrokenDevFileIsDetectedAsPresentButUnreadable() throws Exception {
        Files.writeString(dir.resolve("claude_diag_boss.yml"), "id: rpgquest:claude_diag_boss\ncategory: BOSS\n"
                + "entity-type: WITHER_SKELETON\nname: \"Diag\"\nspawn-chance: 0.01\nbiomes:\n  - swamp\r\nplains\nzones: []\n");
        SpecialMobDefinitionStore store = new SpecialMobDefinitionStore(dir);
        NamespacedKey id = new NamespacedKey("rpgquest", "claude_diag_boss");
        assertTrue(store.hasFile(id));
        assertTrue(store.find(id).isEmpty());
        assertTrue(store.list().hasIssues());
    }
}
