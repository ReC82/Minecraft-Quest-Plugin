package com.lodygames.rpgquest.claim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"removal", "deprecation"})
// removal : EntityDamageByEntityEvent(Entity, Entity, DamageCause, double), même dépréciation déjà
// documentée pour ZoneProtectionListenerTest/WeaponBehaviorListenerTest.
// deprecation : PlayerInteractEvent#isCancelled() est dépréciée côté API (sémantique ambiguë
// bloc/objet, voir docs/ARCHITECTURE.md section item.behavior) mais reste la seule façon de lire
// l'état d'annulation d'un événement construit à la main dans un test ; le code de production
// (ClaimProtectionListener) ne l'appelle jamais, seul ignoreCancelled = true est utilisé.
class ClaimProtectionListenerTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    /** Issue #35 : mode bypass explicite, fermé par défaut. */
    private final com.lodygames.rpgquest.permission.AdminBypassMode bypass =
            new com.lodygames.rpgquest.permission.AdminBypassMode(() -> com.lodygames.rpgquest.settings.GameSettings.DEFAULTS);
    private RPGQuestPlugin plugin;
    private World world;
    private DatabaseManager database;
    private ProgressionService progressionService;
    private ClaimService claimService;
    private NpcIdentityService npcIdentityService;
    private ClaimProtectionListener listener;
    private PlayerMock owner;
    private PlayerMock member;
    private PlayerMock stranger;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        ClaimRepository claimRepository = new ClaimRepository(database);

        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones-unused"), plugin.getSLF4JLogger());
        // Pas d'appel à start() : aucun exemple bundlé nécessaire, un ZoneRegistry vide suffit ici.
        zoneRegistry.reload();
        YamlPortalRegistry portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals-unused"), plugin.getSLF4JLogger());
        portalRegistry.reload();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionRepository progressionRepository = new ProgressionRepository(database);
        progressionService = new ProgressionService(
                plugin, progressionRepository, () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();

        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();
        npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        listener = new ClaimProtectionListener(claimService, npcIdentityService, bypass);

        owner = addPlayer();
        member = addPlayer();
        stranger = addPlayer();

        variableRepository.set(owner.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Claim couvrant -10..10 sur x/z dans "world", "member" en confiance.
        claimService.create(owner, "home", new Location(world, -10, 0, -10), new Location(world, 10, 255, 10))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        claimService.trust(owner.getUniqueId(), "home", member.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
    }

    @AfterEach
    void tearDown() {
        progressionService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    // ---- Frontière / membre autorisé-non autorisé ------------------------------------------

    @Test
    void blockBreakByAStrangerInsideTheClaimIsCancelled() {
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, stranger);

        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakOnTheBorderIsCancelled() {
        Block block = world.getBlockAt(10, 64, 10); // borne max, incluse
        BlockBreakEvent event = new BlockBreakEvent(block, stranger);

        listener.onBreak(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void blockBreakJustOutsideTheBorderIsAllowed() {
        Block block = world.getBlockAt(11, 64, 11);
        BlockBreakEvent event = new BlockBreakEvent(block, stranger);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void blockBreakByTheOwnerIsAllowed() {
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, owner);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void blockBreakByATrustedMemberIsAllowed() {
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, member);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void blockBreakByABypassingAdminIsAllowed() {
        stranger.setOp(true); // rpgquest.admin.world est "default: op"
        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent withoutSession = new BlockBreakEvent(block, stranger);
        listener.onBreak(withoutSession);
        assertTrue(withoutSession.isCancelled(), "issue #35 : un OP sans session respecte les claims d'autrui");

        bypass.enable(stranger);
        BlockBreakEvent event = new BlockBreakEvent(block, stranger);

        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void blockPlaceByAStrangerIsCancelled() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.AIR);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), block.getRelative(0, -1, 0),
                new ItemStack(Material.STONE), stranger, true, EquipmentSlot.HAND);

        listener.onPlace(event);

        assertTrue(event.isCancelled());
    }

    // ---- Conteneurs / redstone configurable -------------------------------------------------

    @Test
    void containerAccessByAStrangerIsCancelled() {
        Block chest = world.getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);
        PlayerInteractEvent event = new PlayerInteractEvent(
                stranger, Action.RIGHT_CLICK_BLOCK, null, chest, null, EquipmentSlot.HAND);

        listener.onInteract(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void buttonUseByAStrangerIsCancelledWhenRedstoneIsNotPublic() {
        Block button = world.getBlockAt(0, 64, 0);
        button.setType(Material.OAK_BUTTON);
        PlayerInteractEvent event = new PlayerInteractEvent(
                stranger, Action.RIGHT_CLICK_BLOCK, null, button, null, EquipmentSlot.HAND);

        listener.onInteract(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void buttonUseByAStrangerIsAllowedWhenRedstoneIsMadePublic() throws Exception {
        claimService.setAllowPublicRedstone(owner.getUniqueId(), "home", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        Block button = world.getBlockAt(0, 64, 0);
        button.setType(Material.OAK_BUTTON);
        PlayerInteractEvent event = new PlayerInteractEvent(
                stranger, Action.RIGHT_CLICK_BLOCK, null, button, null, EquipmentSlot.HAND);

        listener.onInteract(event);

        assertFalse(event.isCancelled());
    }

    // ---- Animaux --------------------------------------------------------------------------

    @Test
    void animalDamageByAStrangerInsideTheClaimIsCancelled() {
        Cow cow = world.spawn(new Location(world, 0.5, 64, 0.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                stranger, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void animalDamageByTheOwnerIsAllowed() {
        Cow cow = world.spawn(new Location(world, 0.5, 64, 0.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                owner, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void animalDamageOutsideTheClaimIsAllowed() {
        Cow cow = world.spawn(new Location(world, 1000.5, 64, 1000.5), Cow.class);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                stranger, cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
    }

    // ---- Issue #531 : PHYSICAL (dalles, terre labourée) ------------------------------------

    private PlayerInteractEvent stepOn(PlayerMock player, Material type) {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(type);
        return new PlayerInteractEvent(player, Action.PHYSICAL, null, block, BlockFace.SELF, null);
    }

    @Test
    void pressurePlateStepByAStrangerIsCancelledEvenWithoutAHand() {
        PlayerInteractEvent event = stepOn(stranger, Material.STONE_PRESSURE_PLATE);

        listener.onInteract(event);

        assertTrue(event.isCancelled(), "PHYSICAL n'a pas de main : il doit être traité avant le filtre de main");
    }

    @Test
    void pressurePlateStepByAStrangerIsAllowedWhenRedstoneIsMadePublic() throws Exception {
        claimService.setAllowPublicRedstone(owner.getUniqueId(), "home", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        PlayerInteractEvent event = stepOn(stranger, Material.STONE_PRESSURE_PLATE);

        listener.onInteract(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void farmlandTramplingByAStrangerIsCancelledEvenWhenRedstoneIsPublic() throws Exception {
        claimService.setAllowPublicRedstone(owner.getUniqueId(), "home", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        PlayerInteractEvent event = stepOn(stranger, Material.FARMLAND);

        listener.onInteract(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void farmlandTramplingByAMemberIsAllowed() {
        PlayerInteractEvent event = stepOn(member, Material.FARMLAND);

        listener.onInteract(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void farmlandTramplingOutsideTheClaimIsAllowed() {
        Block block = world.getBlockAt(1000, 64, 1000);
        block.setType(Material.FARMLAND);
        PlayerInteractEvent event = new PlayerInteractEvent(stranger, Action.PHYSICAL, null, block, BlockFace.SELF, null);

        listener.onInteract(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void farmlandTramplingByAMountRiddenByAStrangerIsCancelled() {
        Pig pig = world.spawn(new Location(world, 0.5, 65, 0.5), Pig.class);
        pig.addPassenger(stranger);
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.FARMLAND);
        EntityInteractEvent event = new EntityInteractEvent(pig, block);

        listener.onEntityStep(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void farmlandTramplingByAMountRiddenByTheOwnerOrRiderlessIsAllowed() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.FARMLAND);
        Pig riderless = world.spawn(new Location(world, 0.5, 65, 0.5), Pig.class);
        EntityInteractEvent riderlessEvent = new EntityInteractEvent(riderless, block);
        listener.onEntityStep(riderlessEvent);
        assertFalse(riderlessEvent.isCancelled(), "aucun joueur à qui imputer le piétinement");

        Pig ridden = world.spawn(new Location(world, 0.5, 65, 0.5), Pig.class);
        ridden.addPassenger(owner);
        EntityInteractEvent ownerEvent = new EntityInteractEvent(ridden, block);
        listener.onEntityStep(ownerEvent);
        assertFalse(ownerEvent.isCancelled());
    }

    // ---- Issue #531 : seaux ------------------------------------------------------------------

    @Test
    void lavaBucketEmptiedByAStrangerInsideTheClaimIsCancelled() {
        Block target = world.getBlockAt(0, 64, 0);
        target.setType(Material.AIR);
        PlayerBucketEmptyEvent event = new PlayerBucketEmptyEvent(stranger, target, target.getRelative(BlockFace.DOWN),
                BlockFace.UP, Material.LAVA_BUCKET, new ItemStack(Material.LAVA_BUCKET), EquipmentSlot.HAND);

        listener.onBucketEmpty(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void waterBucketFilledByAStrangerInsideTheClaimIsCancelledButAllowedForAMember() {
        Block water = world.getBlockAt(0, 64, 0);
        water.setType(Material.WATER);
        PlayerBucketFillEvent strangerEvent = new PlayerBucketFillEvent(stranger, water, water,
                BlockFace.UP, Material.BUCKET, new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND);
        listener.onBucketFill(strangerEvent);
        assertTrue(strangerEvent.isCancelled());

        PlayerBucketFillEvent memberEvent = new PlayerBucketFillEvent(member, water, water,
                BlockFace.UP, Material.BUCKET, new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND);
        listener.onBucketFill(memberEvent);
        assertFalse(memberEvent.isCancelled());
    }

    // ---- Issue #531 : interactions avec les entités -------------------------------------------

    @Test
    void animalInteractionByAStrangerIsCancelledOnBothHands() {
        Cow cow = world.spawn(new Location(world, 0.5, 64, 0.5), Cow.class);
        PlayerInteractEntityEvent mainHand = new PlayerInteractEntityEvent(stranger, cow, EquipmentSlot.HAND);
        PlayerInteractEntityEvent offHand = new PlayerInteractEntityEvent(stranger, cow, EquipmentSlot.OFF_HAND);

        listener.onInteractEntity(mainHand);
        listener.onInteractEntity(offHand);

        assertTrue(mainHand.isCancelled(), "traite, tonte, nourriture, laisse... refusées à un non-membre");
        assertTrue(offHand.isCancelled(), "la main secondaire ne doit pas permettre la même action");
    }

    @Test
    void animalInteractionByAMemberOrOutsideTheClaimIsAllowed() {
        Cow inside = world.spawn(new Location(world, 0.5, 64, 0.5), Cow.class);
        PlayerInteractEntityEvent memberEvent = new PlayerInteractEntityEvent(member, inside, EquipmentSlot.HAND);
        listener.onInteractEntity(memberEvent);
        assertFalse(memberEvent.isCancelled());

        Cow outside = world.spawn(new Location(world, 1000.5, 64, 1000.5), Cow.class);
        PlayerInteractEntityEvent outsideEvent = new PlayerInteractEntityEvent(stranger, outside, EquipmentSlot.HAND);
        listener.onInteractEntity(outsideEvent);
        assertFalse(outsideEvent.isCancelled());
    }

    @Test
    void rpgQuestNpcInsideTheClaimStaysUsableByAStranger() throws Exception {
        Villager npc = world.spawn(new Location(world, 0.5, 64, 0.5), Villager.class);
        npcIdentityService.tag(npc, "claim_guard").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(stranger, npc, EquipmentSlot.HAND);

        listener.onInteractEntity(event);

        assertFalse(event.isCancelled(), "dialogues et quêtes des PNJ RPGQuest restent accessibles");
    }

    @Test
    void plainVillagerTradeByAStrangerIsCancelled() {
        Villager villager = world.spawn(new Location(world, 0.5, 64, 0.5), Villager.class);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(stranger, villager, EquipmentSlot.HAND);

        listener.onInteractEntity(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void entityInteractionByABypassingAdminIsAllowed() {
        stranger.setOp(true);
        bypass.enable(stranger);
        Cow cow = world.spawn(new Location(world, 0.5, 64, 0.5), Cow.class);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(stranger, cow, EquipmentSlot.HAND);

        listener.onInteractEntity(event);

        assertFalse(event.isCancelled());
    }

    // ---- Explosions (externe/interne) ------------------------------------------------------

    @Test
    void explosionInsideTheClaimClearsTheBlockList() {
        var creeper = world.spawn(new Location(world, 0.5, 64, 0.5), org.bukkit.entity.Creeper.class);
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertTrue(event.blockList().isEmpty(), "aucune destruction de bloc dans un claim, même par une explosion externe (creeper)");
    }

    @Test
    void explosionOutsideTheClaimKeepsTheBlockList() {
        var creeper = world.spawn(new Location(world, 1000.5, 64, 1000.5), org.bukkit.entity.Creeper.class);
        Block block = world.getBlockAt(1000, 64, 1000);
        block.setType(Material.STONE);
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, block.getLocation(),
                new java.util.ArrayList<>(java.util.List.of(block)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onEntityExplode(event);

        assertFalse(event.blockList().isEmpty());
    }

    @Test
    void blockExplodeInsideTheClaimClearsTheBlockList() {
        Block tnt = world.getBlockAt(0, 64, 0);
        tnt.setType(Material.STONE);
        BlockExplodeEvent event = new BlockExplodeEvent(tnt, tnt.getState(),
                new java.util.ArrayList<>(java.util.List.of(tnt)), 0f, org.bukkit.ExplosionResult.DESTROY);

        listener.onBlockExplode(event);

        assertTrue(event.blockList().isEmpty());
    }

    // ---- Pistons ----------------------------------------------------------------------------

    @Test
    void pistonPushingAcrossTheClaimBorderIsCancelled() {
        Block piston = world.getBlockAt(9, 64, 0); // à l'intérieur, juste avant la frontière (x=10)
        Block moved = world.getBlockAt(10, 64, 0); // reste dans le claim (x=10 inclus)
        Block destination = world.getBlockAt(11, 64, 0); // sort du claim
        moved.setType(Material.STONE);
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(
                piston, java.util.List.of(moved), org.bukkit.block.BlockFace.EAST);

        listener.onPistonExtend(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void pistonEntirelyInsideTheClaimIsAllowed() {
        Block piston = world.getBlockAt(-9, 64, 0);
        Block moved = world.getBlockAt(-8, 64, 0);
        moved.setType(Material.STONE);
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(
                piston, java.util.List.of(moved), org.bukkit.block.BlockFace.EAST);

        listener.onPistonExtend(event);

        assertFalse(event.isCancelled());
    }

    // ---- Monde absent -----------------------------------------------------------------------

    @Test
    void eventsOnAWorldWithNoClaimsNeverThrow() {
        World otherWorld = server.addSimpleWorld("other_world");
        PlayerMock somebody = server.addPlayer();
        Block block = otherWorld.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, somebody);

        assertDoesNotThrow(() -> listener.onBreak(event));
        assertFalse(event.isCancelled());
    }

    // ---- Suppression --------------------------------------------------------------------------

    @Test
    void afterDeletionTheClaimNoLongerProtectsAnything() throws Exception {
        claimService.delete(owner.getUniqueId(), "home").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        Block block = world.getBlockAt(0, 64, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, stranger);

        listener.onBreak(event);

        assertFalse(event.isCancelled(), "plus aucune protection après suppression du claim");
    }
}
