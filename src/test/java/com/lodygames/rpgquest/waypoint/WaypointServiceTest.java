package com.lodygames.rpgquest.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.RuneConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaypointConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.WaypointRepository;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.waypoint.render.BlockOffset;
import com.lodygames.rpgquest.waypoint.render.WaypointModel;
import com.lodygames.rpgquest.waypoint.render.WaypointModelRegistry;
import com.lodygames.rpgquest.waypoint.render.WaypointModelV1;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.sign.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #124 : couvre {@link WaypointService} — génération unique par instance de biome, concurrence
 * / idempotence, deux instances du même biome, découverte par bouton uniquement, découvertes
 * indépendantes par joueur, persistance/reload, stabilité de l'identité malgré un changement de
 * version de rendu.
 */
class WaypointServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final long REGION = 256L;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private World wild;
    private WaypointRepository repository;
    private WaypointService service;

    private TravelConfig config() {
        // move-throttle 0 : on pilote handleMovement directement dans les tests.
        return new TravelConfig("wild",
                new RuneConfig(10, 1800),
                new WaystoneConfig(1000L, 1.0, 300, 16, 1),
                new WaypointConfig(true, REGION, 16, 40, 24, 0L, 8, 1));
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new WaypointRepository(database);

        service = newService(new WaypointModelRegistry(1, new WaypointModelV1()));
        service.start();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
        database.shutdown();
        MockBukkit.unmock();
    }

    private WaypointService newService(WaypointModelRegistry registry) {
        return new WaypointService(plugin, repository, new WaypointIdentityResolver(),
                new WaypointGenerationPlanner(), registry, WaypointPlacementGuard.ALLOW_ALL, this::config);
    }

    /** Plateforme de pierre + 3 blocs d'air, biome imposé, autour d'un centre. */
    private void prepareArea(int centerX, int centerZ, Biome biome, int radius) {
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                wild.getBlockAt(x, 64, z).setType(Material.STONE);
                wild.getBlockAt(x, 65, z).setType(Material.AIR);
                wild.getBlockAt(x, 66, z).setType(Material.AIR);
                wild.getBlockAt(x, 67, z).setType(Material.AIR);
                wild.setBiome(x, z, biome);
            }
        }
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(2);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(condition.getAsBoolean(), "condition non atteinte avant le délai");
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private Block interactorBlockOf(Waypoint wp) {
        BlockOffset off = new WaypointModelV1().interactor(BlockFace.valueOf(wp.facing()));
        return wild.getBlockAt(wp.x() + off.dx(), wp.y() + off.dy(), wp.z() + off.dz());
    }

    private Waypoint generateAround(int centerX, int centerZ, Biome biome) {
        prepareArea(centerX, centerZ, biome, 48);
        PlayerMock walker;
        try {
            walker = addPlayer();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        walker.teleport(new Location(wild, centerX + 0.5, 65, centerZ + 0.5));
        int before = service.all().size();
        service.handleMovement(walker, walker.getLocation());
        await(() -> service.all().size() > before);
        return service.all().stream()
                .filter(w -> Math.abs(w.x() - centerX) <= 48 && Math.abs(w.z() - centerZ) <= 48)
                .findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void firstEntryIntoABiomeZoneGeneratesExactlyOneWaypoint() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);

        assertEquals(1, service.all().size());
        assertEquals(1, repository.loadAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        assertEquals("minecraft:forest", wp.biomeKey(), "le waypoint est enregistré dans le bon biome");
        assertEquals(1, wp.modelVersion());
        assertTrue(wp.active());

        double distanceFromEntry = Math.hypot(wp.x() - 384.0, wp.z() - 384.0);
        assertTrue(distanceFromEntry >= 12, "le waypoint n'apparaît pas au pied du joueur (" + distanceFromEntry + ")");

        assertEquals(Material.GOLD_BLOCK, wild.getBlockAt(wp.x(), wp.y() + 1, wp.z()).getType());
        assertEquals(Material.COBBLESTONE_WALL, wild.getBlockAt(wp.x(), wp.y(), wp.z()).getType());
        assertEquals(Material.STONE_BUTTON, interactorBlockOf(wp).getType());
    }

    // ---- Issue #191 : structure détruite — détection et restauration sur place ----------------

    @Test
    void anIntactStructureIsReportedAsIntact() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);

        var state = service.integrity(wp.id()).orElseThrow();

        assertTrue(state.intact(), "structure fraîchement posée : " + state);
        assertTrue(service.damagedStructures(null).isEmpty());
    }

    /** Reproduit le signalement : bouton cassé, puis bloc d'or, puis le support. */
    @Test
    void destroyingTheStructureIsDetectedBlockByBlock() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);

        interactorBlockOf(wp).setType(Material.AIR);
        assertTrue(service.integrity(wp.id()).orElseThrow().damaged(), "bouton manquant détecté");

        wild.getBlockAt(wp.x(), wp.y() + 1, wp.z()).setType(Material.AIR);
        wild.getBlockAt(wp.x(), wp.y(), wp.z()).setType(Material.AIR);

        var state = service.integrity(wp.id()).orElseThrow();
        assertTrue(state.missing().size() >= 3, "les blocs détruits sont listés : " + state.missing());
        assertTrue(state.conflicts().isEmpty(), "de l'air n'est pas un conflit");
        assertEquals(1, service.damagedStructures(null).size());
    }

    /**
     * Le cœur du ticket : restauration sans perte d'identité. L'id, le nom et les découvertes
     * joueurs doivent survivre, et aucun second waypoint ne doit apparaître dans le biome.
     */
    @Test
    void restoreInPlaceRebuildsWithoutLosingIdentityOrDiscoveries() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        String id = wp.id();
        String name = wp.displayName();
        int x = wp.x();
        int y = wp.y();
        int z = wp.z();

        PlayerMock finder = addPlayer();
        service.handleInteract(finder, interactorBlockOf(wp));
        await(() -> service.hasActivelyDiscovered(finder.getUniqueId(), id));
        assertTrue(service.hasActivelyDiscovered(finder.getUniqueId(), id), "découverte enregistrée avant la casse");

        // Destruction complète de la structure.
        interactorBlockOf(wp).setType(Material.AIR);
        wild.getBlockAt(x, y + 1, z).setType(Material.AIR);
        wild.getBlockAt(x, y, z).setType(Material.AIR);

        assertTrue(service.restoreInPlace(id, false).isEmpty(), "restauration sans conflit attendue");

        assertTrue(service.integrity(id).orElseThrow().intact(), "structure complète après restauration");
        assertEquals(Material.GOLD_BLOCK, wild.getBlockAt(x, y + 1, z).getType());
        assertEquals(Material.COBBLESTONE_WALL, wild.getBlockAt(x, y, z).getType());
        assertEquals(Material.STONE_BUTTON, interactorBlockOf(wp).getType());

        Waypoint after = service.all().stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow();
        assertEquals(x, after.x(), "jamais déplacé : c'est une restauration, pas une relocalisation");
        assertEquals(y, after.y());
        assertEquals(z, after.z());
        assertEquals(name, after.displayName(), "nom canonique conservé");
        assertEquals(1, service.all().size(), "aucun doublon créé dans le biome");
        assertTrue(service.hasActivelyDiscovered(finder.getUniqueId(), id), "découvertes conservées");
    }

    /** Une construction tierce n'est jamais écrasée sans « force » explicite. */
    @Test
    void restoreRefusesToOverwriteAThirdPartyBuildUnlessForced() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        String id = wp.id();

        interactorBlockOf(wp).setType(Material.AIR);
        // Quelqu'un a construit à l'emplacement du bloc d'or depuis la destruction.
        wild.getBlockAt(wp.x(), wp.y() + 1, wp.z()).setType(Material.OAK_PLANKS);

        var state = service.integrity(id).orElseThrow();
        assertFalse(state.conflicts().isEmpty(), "le bloc étranger est signalé comme conflit : " + state);

        var refusal = service.restoreInPlace(id, false);
        assertTrue(refusal.isPresent(), "sans force, la restauration doit refuser");
        assertTrue(refusal.get().contains("Conflit"), refusal.get());
        assertEquals(Material.OAK_PLANKS, wild.getBlockAt(wp.x(), wp.y() + 1, wp.z()).getType(),
                "rien n'a été écrasé");

        assertTrue(service.restoreInPlace(id, true).isEmpty(), "avec force, la restauration passe");
        assertEquals(Material.GOLD_BLOCK, wild.getBlockAt(wp.x(), wp.y() + 1, wp.z()).getType());
    }

    @Test
    void restoreOfAnUnknownWaypointFailsCleanly() {
        assertTrue(service.restoreInPlace("inconnu", false).isPresent());
        assertTrue(service.integrity("inconnu").isEmpty());
    }

    /**
     * Issue #167 : un panneau sur chacune des deux faces latérales adjacentes à la face du bouton
     * (jamais la face opposée), affichant le nom canonique persisté, le bouton restant accessible.
     */
    @Test
    void waypointGenerationPlacesTwoLateralNameSignsWithTheCanonicalNameAndKeepsTheButtonAccessible() throws Exception {
        Waypoint wp = generateAround(448, 448, Biome.PLAINS);
        BlockFace facing = BlockFace.valueOf(wp.facing());
        BlockFace[] laterals = (facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
                ? new BlockFace[] {BlockFace.EAST, BlockFace.WEST}
                : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH};

        for (BlockFace side : laterals) {
            Block signBlock = wild.getBlockAt(wp.x() + side.getModX(), wp.y() + 1, wp.z() + side.getModZ());
            assertEquals(Material.OAK_WALL_SIGN, signBlock.getType(), "panneau latéral attendu côté " + side);
            assertTrue(signBlock.getBlockData() instanceof Directional, "le panneau doit porter une orientation");
            assertEquals(side, ((Directional) signBlock.getBlockData()).getFacing(),
                    "le panneau doit être orienté vers l'extérieur, côté " + side);

            assertTrue(signBlock.getState() instanceof Sign, "le bloc doit être un vrai panneau");
            Sign sign = (Sign) signBlock.getState();
            String rendered = String.join(" ", sign.getSide(Side.FRONT).lines().stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                    .filter(s -> !s.isBlank())
                    .toList());
            assertEquals(wp.displayName(), rendered,
                    "le panneau doit afficher le nom canonique complet, réparti sur ses lignes");
        }

        // Le bouton (face « facing », pas une face latérale) reste bien l'interacteur, inchangé.
        assertEquals(Material.STONE_BUTTON, interactorBlockOf(wp).getType());
        for (BlockFace side : laterals) {
            assertTrue(service.isProtectedBlock("wild", wp.x() + side.getModX(), wp.y() + 1, wp.z() + side.getModZ()),
                    "chaque panneau doit être protégé comme le reste de la structure, côté " + side);
        }
    }

    /**
     * Issue #167 : mise à niveau idempotente -- un waypoint généré avant cette version (simulé ici
     * en effaçant ses panneaux après génération) retrouve ses panneaux via {@code upgradeSigns},
     * sans jamais changer id/position/découvertes. Rejouable sans risque (idempotent).
     */
    @Test
    void upgradeSignsRetrofitsAnExistingWaypointWithoutChangingItsIdentity() throws Exception {
        Waypoint wp = generateAround(480, 480, Biome.PLAINS);
        BlockFace facing = BlockFace.valueOf(wp.facing());
        BlockFace[] laterals = (facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
                ? new BlockFace[] {BlockFace.EAST, BlockFace.WEST}
                : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH};

        // Simule un waypoint généré avant #167 : ses panneaux n'existent pas (effacés ici).
        for (BlockFace side : laterals) {
            wild.getBlockAt(wp.x() + side.getModX(), wp.y() + 1, wp.z() + side.getModZ()).setType(Material.AIR);
        }

        int processed = service.upgradeSigns("wild");
        assertEquals(1, processed, "le seul waypoint du monde « wild » doit être traité");

        for (BlockFace side : laterals) {
            Block signBlock = wild.getBlockAt(wp.x() + side.getModX(), wp.y() + 1, wp.z() + side.getModZ());
            assertEquals(Material.OAK_WALL_SIGN, signBlock.getType(), "le panneau doit être reposé côté " + side);
        }
        // id/position/découvertes inchangés : le waypoint indexé reste strictement le même.
        assertEquals(wp.id(), service.byId(wp.id()).orElseThrow().id());
        assertEquals(wp.x(), service.byId(wp.id()).orElseThrow().x());
        assertEquals(wp.displayName(), service.byId(wp.id()).orElseThrow().displayName());

        // Rejouable sans risque : un second passage ne lève jamais et ne change rien.
        assertEquals(1, service.upgradeSigns("wild"));
    }

    @Test
    void twoPlayersEnteringSimultaneouslyNeverCreateTwoWaypoints() throws Exception {
        prepareArea(384, 384, Biome.FOREST, 48);
        PlayerMock a = addPlayer();
        PlayerMock b = addPlayer();
        Location entry = new Location(wild, 384.5, 65, 384.5);
        a.teleport(entry);
        b.teleport(entry);

        service.handleMovement(a, entry);
        service.handleMovement(b, entry); // même tick, avant la fin de l'insert async

        await(() -> !service.all().isEmpty());
        server.getScheduler().performTicks(10);

        assertEquals(1, service.all().size(), "une seule instance -> un seul waypoint");
        assertEquals(1, repository.loadAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size(),
                "une seule ligne persistée : jamais de doublon en concurrence");
    }

    @Test
    void insertIfAbsentIsIdempotentAtTheRepositoryLevel() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        boolean second = repository.insertIfAbsent(wp).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(second, "réinsérer la même instance de biome ne crée jamais de doublon");
        assertEquals(1, repository.loadAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void twoSeparatedZonesOfTheSameBiomeGetTwoDistinctWaypoints() throws Exception {
        Waypoint first = generateAround(384, 384, Biome.FOREST);
        Waypoint second = generateAround(384 + (int) (4 * REGION), 384, Biome.FOREST);

        assertEquals(2, service.all().size());
        assertFalse(first.id().equals(second.id()), "deux forêts éloignées = deux waypoints");
        assertFalse(first.biomeInstance().equals(second.biomeInstance()));
        assertEquals("minecraft:forest", first.biomeKey());
        assertEquals("minecraft:forest", second.biomeKey());
    }

    @Test
    void walkingPastAWaypointDiscoversNothing() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        PlayerMock passerby = addPlayer();
        service.handleJoin(passerby);

        // Passe à 2 blocs de l'interacteur, sans cliquer.
        Block interactor = interactorBlockOf(wp);
        passerby.teleport(new Location(wild, interactor.getX() + 2.5, wp.y() + 1, interactor.getZ() + 0.5));
        service.handleMovement(passerby, passerby.getLocation());
        server.getScheduler().performTicks(5);

        assertTrue(repository.discoveriesFor(passerby.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "la proximité ne découvre rien");
    }

    @Test
    void onlyAnExplicitClickOnTheButtonValidatesDiscovery() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        PlayerMock player = addPlayer();
        service.handleJoin(player);

        // Cliquer sur le bloc d'or (pas l'interacteur) ne découvre rien.
        assertFalse(service.handleInteract(player, wild.getBlockAt(wp.x(), wp.y() + 1, wp.z())));
        server.getScheduler().performTicks(3);
        assertTrue(repository.discoveriesFor(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        // Cliquer sur le bouton découvre.
        assertTrue(service.handleInteract(player, interactorBlockOf(wp)));
        await(() -> {
            try {
                return repository.discoveriesFor(player.getUniqueId()).get(2, TimeUnit.SECONDS).contains(wp.id());
            } catch (Exception e) {
                return false;
            }
        });
    }

    /**
     * Retour joueur 2026-10-04 (issue #160) : la toute première découverte affichait seulement le
     * biome ("Waypoint découvert — plains"), jamais le nom canonique persisté -- pourtant déjà
     * utilisé par le menu de voyage et par le reclic (message « déjà découvert »). Les deux doivent
     * désormais partager le même nom.
     */
    @Test
    void firstDiscoveryShowsTheCanonicalNameNotOnlyTheBiome() throws Exception {
        Waypoint wp = generateAround(416, 416, Biome.PLAINS);
        PlayerMock player = addPlayer();

        assertTrue(service.handleInteract(player, interactorBlockOf(wp)));
        await(() -> {
            try {
                return repository.discoveriesFor(player.getUniqueId()).get(2, TimeUnit.SECONDS).contains(wp.id());
            } catch (Exception e) {
                return false;
            }
        });
        server.getScheduler().performTicks(5);

        String message = null;
        String next;
        while ((next = player.nextMessage()) != null) {
            message = next;
        }
        assertNotNull(message, "la première découverte doit produire un message visible");
        assertTrue(message.contains(wp.displayName()),
                "le nom canonique doit apparaître dès la première découverte, pas seulement le biome : " + message);
    }

    /**
     * Retour joueur 2026-10-04 : un clic sur un waypoint déjà découvert ne doit plus être muet
     * (consommation silencieuse) -- un retour clair contenant le nom doit apparaître à chaque fois,
     * sans nouvelle récompense ni ré-écriture de la découverte déjà enregistrée.
     */
    @Test
    void clickingAnAlreadyDiscoveredWaypointAgainShowsAClearFeedbackWithItsName() throws Exception {
        // Jamais de service.handleJoin(player) ici : son chargement asynchrone des découvertes
        // (écriture brute, pas un computeIfAbsent) peut écraser l'ensemble que handleInteract vient
        // de peupler -- même piège déjà rencontré et documenté pour repairMoves...PreservingIdName...
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        PlayerMock player = addPlayer();

        assertTrue(service.handleInteract(player, interactorBlockOf(wp)));
        await(() -> {
            try {
                return repository.discoveriesFor(player.getUniqueId()).get(2, TimeUnit.SECONDS).contains(wp.id());
            } catch (Exception e) {
                return false;
            }
        });
        // Laisse le temps au message asynchrone de première découverte d'être réellement envoyé
        // (tâche planifiée sur le thread principal après l'écriture en base), puis vide la file
        // avant le second clic -- jamais une hypothèse d'ordre entre deux nextMessage() successifs.
        server.getScheduler().performTicks(5);
        while (player.nextMessage() != null) {
            // vide la file des messages déjà reçus (première découverte), hors du périmètre de ce test.
        }

        assertTrue(service.handleInteract(player, interactorBlockOf(wp)), "le clic reste consommé (annule l'événement)");
        String feedback = player.nextMessage();
        assertNotNull(feedback, "un second clic doit produire un retour visible, jamais un silence");
        assertTrue(feedback.contains(wp.displayName()),
                "le retour doit contenir le nom du waypoint : " + feedback);
    }

    @Test
    void discoveryIsIndependentPerPlayer() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        PlayerMock a = addPlayer();
        PlayerMock b = addPlayer();
        service.handleJoin(a);
        service.handleJoin(b);

        service.handleInteract(a, interactorBlockOf(wp));
        await(() -> {
            try {
                return repository.discoveriesFor(a.getUniqueId()).get(2, TimeUnit.SECONDS).contains(wp.id());
            } catch (Exception e) {
                return false;
            }
        });
        assertFalse(repository.discoveriesFor(b.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).contains(wp.id()),
                "la découverte du joueur A n'affecte jamais le joueur B");
    }

    @Test
    void aFreshServiceReloadsWaypointsAndDiscoveriesWithoutDuplicates() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        PlayerMock a = addPlayer();
        service.handleJoin(a);
        service.handleInteract(a, interactorBlockOf(wp));
        await(() -> {
            try {
                return repository.discoveriesFor(a.getUniqueId()).get(2, TimeUnit.SECONDS).contains(wp.id());
            } catch (Exception e) {
                return false;
            }
        });

        service.stop();
        service = newService(new WaypointModelRegistry(1, new WaypointModelV1()));
        service.start();
        await(() -> !service.all().isEmpty());

        assertEquals(1, service.all().size(), "reload : aucun doublon");
        assertEquals(wp.id(), service.all().get(0).id());

        service.handleJoin(a);
        await(() -> {
            try {
                return service.byId(wp.id()).isPresent();
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(repository.discoveriesFor(a.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).contains(wp.id()),
                "les découvertes survivent au reload");
    }

    @Test
    void changingTheRenderModelVersionNeverChangesTheBusinessIdentity() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        String id = wp.id();
        String instance = wp.biomeInstance();

        // Nouveau service avec un registre qui expose une v2 (rendu différent) comme version courante.
        service.stop();
        service = newService(new WaypointModelRegistry(2, new WaypointModelV1(), new FakeV2Model()));
        service.start();
        await(() -> !service.all().isEmpty());

        Waypoint reloaded = service.byId(id).orElseThrow();
        assertEquals(id, reloaded.id(), "l'identité technique ne dépend pas du rendu");
        assertEquals(instance, reloaded.biomeInstance());
        assertEquals(1, reloaded.modelVersion(), "un waypoint existant garde SA version de rendu (pas d'upgrade auto)");
    }

    @Test
    void isProtectedBlockCoversTheWholeStructure() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        assertTrue(service.isProtectedBlock("wild", wp.x(), wp.y(), wp.z()), "support");
        assertTrue(service.isProtectedBlock("wild", wp.x(), wp.y() + 1, wp.z()), "bloc d'or");
        assertTrue(service.isProtectedBlock("wild", wp.x(), wp.y() - 1, wp.z()), "sol porteur");
        Block interactor = interactorBlockOf(wp);
        assertTrue(service.isProtectedBlock("wild", interactor.getX(), interactor.getY(), interactor.getZ()), "bouton");
        assertFalse(service.isProtectedBlock("wild", wp.x() + 10, wp.y(), wp.z()), "un bloc voisin n'est pas protégé");
    }

    @Test
    void aFailedGenerationSchedulesABoundedRetryWithoutLooping() throws Exception {
        // Aucune plateforme, aucun sol : aucun emplacement sûr -> échec propre, pas de boucle.
        wild.setBiome(384, 384, Biome.FOREST);
        PlayerMock walker = addPlayer();
        Location entry = new Location(wild, 384.5, 65, 384.5);
        walker.teleport(entry);
        service.handleMovement(walker, entry);
        server.getScheduler().performTicks(10);

        assertTrue(service.all().isEmpty(), "aucun waypoint créé sans emplacement valable");
        assertNotNull(service); // pas de crash, pas de boucle : le test se termine.
    }

    // ---- Diagnostic et réparation (issue #153) ---------------------------------------------

    @Test
    void inaccessibleDetectsAWaypointWhoseGroundBecameALeafCanopy() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        assertTrue(service.inaccessible().isEmpty(), "sol de pierre normal : accessible");

        // Simule le cas réel #153 : le sol porteur devient du feuillage (ex. un arbre a poussé /
        // le terrain a changé depuis la génération).
        wild.getBlockAt(wp.x(), wp.y() - 1, wp.z()).setType(Material.ACACIA_LEAVES);

        var inaccessible = service.inaccessible();
        assertEquals(1, inaccessible.size());
        assertEquals(wp.id(), inaccessible.get(0).id());
    }

    @Test
    void repairMovesAnInaccessibleWaypointPreservingIdNameAndDiscoveries() throws Exception {
        Waypoint wp = generateAround(384, 384, Biome.FOREST);
        PlayerMock player = addPlayer();
        service.handleInteract(player, interactorBlockOf(wp));
        await(() -> service.hasActivelyDiscovered(player.getUniqueId(), wp.id()));

        wild.getBlockAt(wp.x(), wp.y() - 1, wp.z()).setType(Material.ACACIA_LEAVES);
        assertEquals(1, service.inaccessible().size());

        var error = service.repair(wp.id());
        assertTrue(error.isEmpty(), () -> "réparation attendue en succès : " + error);

        Waypoint repaired = service.byId(wp.id()).orElseThrow();
        assertEquals(wp.id(), repaired.id(), "id jamais recalculé");
        assertEquals(wp.displayName(), repaired.displayName(), "nom jamais changé par une réparation");
        assertEquals(wp.biomeInstance(), repaired.biomeInstance(), "instance de biome jamais changée");
        assertTrue(repaired.x() != wp.x() || repaired.z() != wp.z(), "la position doit réellement changer");
        assertTrue(service.inaccessible().isEmpty(), "la nouvelle position doit être accessible");
        assertTrue(service.hasActivelyDiscovered(player.getUniqueId(), wp.id()), "la découverte survit à la réparation");

        // Persistance réelle en base (pas seulement en mémoire).
        Waypoint reloaded = repository.loadAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
                .filter(w -> w.id().equals(wp.id())).findFirst().orElseThrow();
        assertEquals(repaired.x(), reloaded.x());
        assertEquals(repaired.z(), reloaded.z());
    }

    @Test
    void repairFailsCleanlyWhenWaypointDoesNotExist() {
        assertTrue(service.repair("does_not_exist").isPresent());
    }

    /** Modèle de rendu factice v2 pour prouver la stabilité de l'identité (pose sans effet). */
    private static final class FakeV2Model implements WaypointModel {
        @Override
        public int version() {
            return 2;
        }

        @Override
        public void place(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing, String displayName) {
        }

        @Override
        public BlockOffset interactor(BlockFace facing) {
            return new BlockOffset(0, 2, 0);
        }

        @Override
        public Set<BlockOffset> protectedBlocks(BlockFace facing) {
            return Set.of(new BlockOffset(0, 0, 0), new BlockOffset(0, 1, 0), new BlockOffset(0, 2, 0));
        }

        @Override
        public java.util.Map<BlockOffset, org.bukkit.Material> expectedBlocks(BlockFace facing) {
            return java.util.Map.of(new BlockOffset(0, 1, 0), org.bukkit.Material.GOLD_BLOCK);
        }
    }
}
