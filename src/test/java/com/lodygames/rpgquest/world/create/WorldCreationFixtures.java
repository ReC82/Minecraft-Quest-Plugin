package com.lodygames.rpgquest.world.create;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Faux serveur pour la création de monde (issue #562) : rien n'est jamais créé sur disque, la
 * « création » ajoute seulement un nom à une liste en mémoire.
 */
final class WorldCreationFixtures {

    private WorldCreationFixtures() {
    }

    static final Set<String> BLOCKS = Set.of("minecraft:bedrock", "minecraft:dirt", "minecraft:grass_block",
            "minecraft:snow_block", "minecraft:sandstone", "minecraft:sand", "minecraft:air", "minecraft:stone");
    static final Set<String> BIOMES = Set.of("minecraft:plains", "minecraft:snowy_plains", "minecraft:desert",
            "minecraft:the_void");

    static WorldCreationPlanner.Context context() {
        return new WorldCreationPlanner.Context(true, null, "world", Set.of("world", "world_nether", "world_hub",
                "wild", "claims"), Set.of("world_hub", "claims", "ancien"), Set.of("world", "world_hub", "wild",
                "claims", "ancien", "Terres_Perdues", "plugins"), Set.of("world_hub", "wild", "claims", "world"),
                BLOCKS::contains, BIOMES::contains);
    }

    static WorldCreationRequest normal(String name) {
        return new WorldCreationRequest(name, "NORMAL", "NORMAL", "", true, "", List.of(), "");
    }

    /** Faux serveur : enregistre les appels, rend la relecture programmée. */
    static final class FakeGateway implements WorldCreationGateway {
        final List<String> created = new ArrayList<>();
        final Set<String> worlds = new LinkedHashSet<>();
        String failure;
        boolean keepSpawn;
        String rereadType;
        WorldCreationPlanner.Context base = WorldCreationFixtures.context();
        Runnable duringCreate = () -> { };

        @Override
        public WorldCreationPlanner.Context context(Set<String> folders) {
            Set<String> loaded = new LinkedHashSet<>(base.loaded());
            loaded.addAll(worlds);
            Set<String> allFolders = new LinkedHashSet<>(base.folders());
            allFolders.addAll(folders);
            return new WorldCreationPlanner.Context(base.multiverseAvailable(), base.multiverseReason(),
                    base.mainWorld(), loaded, base.multiverseKnown(), allFolders, base.essential(), base.blockValid(),
                    base.biomeValid());
        }

        @Override
        public List<String> biomes() {
            return List.copyOf(BIOMES);
        }

        @Override
        public List<String> blocks() {
            return List.copyOf(BLOCKS);
        }

        @Override
        public String create(WorldCreationPlanner.Plan plan) {
            created.add(plan.world());
            duringCreate.run();
            if (failure != null) {
                return failure;
            }
            worlds.add(plan.world());
            lastPlan = plan;
            return null;
        }

        WorldCreationPlanner.Plan lastPlan;

        @Override
        public List<String> configureCreated(String world) {
            return List.of();
        }

        @Override
        public Optional<Created> describe(String world) {
            if (!worlds.contains(world) || lastPlan == null) {
                return Optional.empty();
            }
            WorldCreationPlanner.Plan p = lastPlan;
            return Optional.of(new Created(world, p.dimension().name(),
                    rereadType != null ? rereadType : p.terrain().name(), p.seed() == null ? 42L : p.seed(),
                    p.structures(), p.generatorSettings(), keepSpawn, true, true));
        }
    }
}
