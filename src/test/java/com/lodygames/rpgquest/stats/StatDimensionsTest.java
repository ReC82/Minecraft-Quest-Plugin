package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.stats.StatDimensions.EntityCategory;
import com.lodygames.rpgquest.stats.StatDimensions.Kind;
import java.util.List;
import java.util.Set;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/**
 * Issue #734 — catalogue des cibles généré depuis Paper 1.21.11 : chaque nom de
 * {@code stats/families.yml} existe, chaque type d'entité a une catégorie, et ce qui n'est pas du
 * PvE (joueur, projectile, décor) est exclu avec une raison.
 */
class StatDimensionsTest {

    private final StatDimensions dimensions = StatDimensions.bundled();

    @Test
    void everyNameOfTheBundledFamiliesExistsInPaper() {
        assertEquals(List.of(), dimensions.unknownNames());
    }

    @Test
    void theFirstWaveFamiliesHoldTheirVariants() {
        assertTrue(members(Kind.ENTITY, "zombies").containsAll(Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER")));
        assertTrue(members(Kind.ENTITY, "skeletons").containsAll(Set.of("SKELETON", "STRAY", "BOGGED", "PARCHED")));
        assertEquals(Set.of("SPIDER", "CAVE_SPIDER"), members(Kind.ENTITY, "spiders"));
        assertEquals(Set.of("CREEPER"), members(Kind.ENTITY, "creepers"));
        assertTrue(members(Kind.BLOCK, "ores").containsAll(Set.of("IRON_ORE", "DEEPSLATE_DIAMOND_ORE",
                "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS")), "suffixe _ORE et membres explicites");
        assertTrue(members(Kind.BLOCK, "logs").containsAll(Set.of("OAK_LOG", "CHERRY_WOOD", "CRIMSON_STEM")));
        assertFalse(members(Kind.BLOCK, "ores").stream().anyMatch(name -> name.startsWith("LEGACY_")));
    }

    @Test
    void entityCategoriesComeFromThePaperClasses() {
        assertEquals(EntityCategory.HOSTILE, StatDimensions.category(EntityType.ZOMBIE));
        assertEquals(EntityCategory.HOSTILE, StatDimensions.category(EntityType.CREEPER));
        assertEquals(EntityCategory.HOSTILE, StatDimensions.category(EntityType.SLIME));
        assertEquals(EntityCategory.HOSTILE, StatDimensions.category(EntityType.BREEZE));
        assertEquals(EntityCategory.BOSS, StatDimensions.category(EntityType.ENDER_DRAGON));
        assertEquals(EntityCategory.BOSS, StatDimensions.category(EntityType.WITHER));
        assertEquals(EntityCategory.BOSS, StatDimensions.category(EntityType.WARDEN));
        assertEquals(EntityCategory.BOSS, StatDimensions.category(EntityType.ELDER_GUARDIAN));
        assertEquals(EntityCategory.PASSIVE, StatDimensions.category(EntityType.COW));
        assertEquals(EntityCategory.PASSIVE, StatDimensions.category(EntityType.VILLAGER));
        assertEquals(EntityCategory.EXCLUDED, StatDimensions.category(EntityType.PLAYER), "le PvP n'est pas du PvE");
        assertEquals(EntityCategory.EXCLUDED, StatDimensions.category(EntityType.ARMOR_STAND));
        assertEquals(EntityCategory.EXCLUDED, StatDimensions.category(EntityType.ARROW));
    }

    @Test
    void everyEntityTypeIsCatalogedAndEveryExclusionHasAReason() {
        assertEquals(EntityType.values().length, dimensions.entities().size());
        for (StatDimensions.EntityEntry entry : dimensions.entities()) {
            assertEquals(entry.countable(), entry.reason() == null, entry.type());
        }
        Set<String> hostile = members(Kind.ENTITY, "hostile");
        Set<String> passive = members(Kind.ENTITY, "passive");
        assertTrue(hostile.stream().noneMatch(passive::contains));
        assertFalse(hostile.contains("PLAYER"));
        assertTrue(members(Kind.ENTITY, "boss").contains("WITHER"));
    }

    private Set<String> members(Kind kind, String id) {
        return dimensions.family(kind, id).orElseThrow().members();
    }

    @Test
    void everyCountableCreatureHasAFrenchName() {
        List<String> missing = dimensions.entities().stream().filter(StatDimensions.EntityEntry::countable)
                .map(StatDimensions.EntityEntry::type)
                .filter(type -> !dimensions.labelledEntities().contains(type)).toList();
        assertEquals(List.of(), missing, "nom français manquant dans entity-labels de families.yml");
        assertEquals("Zombie momifié", dimensions.entityLabel("HUSK"));
        assertEquals("Mob inconnu", dimensions.entityLabel("MOB_INCONNU"), "repli lisible, jamais masqué");
        assertEquals(EntityCategory.HOSTILE, dimensions.categoryOf("ZOMBIE"));
        assertEquals(EntityCategory.EXCLUDED, dimensions.categoryOf("MOB_INCONNU"));
    }
}
