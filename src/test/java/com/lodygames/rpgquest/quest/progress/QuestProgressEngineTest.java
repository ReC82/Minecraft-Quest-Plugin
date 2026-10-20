package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.economy.QuestRewardDue;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.economy.QuestRewardReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class QuestProgressEngineTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey KILL_QUEST = new NamespacedKey("rpgquest", "kill_quest");
    private static final NamespacedKey KILL_QUEST_TWO = new NamespacedKey("rpgquest", "kill_quest_two");
    private static final NamespacedKey BREAK_QUEST = new NamespacedKey("rpgquest", "break_quest");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private YamlQuestEngine questEngine;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;
    private RecordingRewardPayer rewardPayer;
    private Path questsDir;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        writeKillQuest(KILL_QUEST, "kill_quest.yml", 2, false, null);

        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        progressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        rewardPayer = new RecordingRewardPayer();
        engine = new QuestProgressEngine(plugin, questEngine, progressRepository, variableRepository, messagesService,
                npcIdentityService, rewardPayer);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    // ---- Issue #12 : disponibilite, source UNIQUE des regles de refus ---------------------

    @Test
    void availabilityIsAvailableForAFreshQuest() throws Exception {
        PlayerMock player = addPlayer();

        QuestProgressEngine.Availability availability =
                engine.availability(player.getUniqueId(), KILL_QUEST, false)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(availability.available());
        assertEquals(QuestProgressEngine.Availability.Status.AVAILABLE, availability.status());
        assertTrue(availability.missingPrerequisites().isEmpty());
    }

    @Test
    void availabilityIsNotAvailableOnceTheQuestIsActive() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        QuestProgressEngine.Availability availability =
                engine.availability(player.getUniqueId(), KILL_QUEST, false)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(availability.available(), "une quete deja acceptee n'est plus « nouvelle »");
        assertEquals(QuestProgressEngine.Availability.Status.ALREADY_ACTIVE, availability.status());
    }

    @Test
    void availabilityReportsAnUnknownQuestInsteadOfPretendingItIsAvailable() throws Exception {
        PlayerMock player = addPlayer();

        QuestProgressEngine.Availability availability = engine
                .availability(player.getUniqueId(), new NamespacedKey("rpgquest", "tc12_inexistante"), false)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(availability.available());
        assertEquals(QuestProgressEngine.Availability.Status.UNKNOWN, availability.status());
    }

    @Test
    void availabilityNamesTheMissingPrerequisites() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "tc12_locked.yml", 1, false, KILL_QUEST.toString());
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();

        QuestProgressEngine.Availability availability =
                engine.availability(player.getUniqueId(), KILL_QUEST_TWO, false)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(availability.available(), "une quete verrouillee ne doit JAMAIS etre signalee");
        assertEquals(QuestProgressEngine.Availability.Status.MISSING_PREREQUISITES, availability.status());
        assertEquals(List.of(KILL_QUEST), availability.missingPrerequisites());
    }

    @Test
    void availabilityDoesNotCreateAnyProgressForThePlayer() throws Exception {
        PlayerMock player = addPlayer();

        engine.availability(player.getUniqueId(), KILL_QUEST, false).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Demander la disponibilite est une LECTURE : elle ne doit rien ecrire, sinon le signal
        // visuel creerait de la progression en regardant simplement un PNJ.
        assertEquals(QuestState.NOT_STARTED,
                engine.stateOf(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void availabilityIgnorePrerequisitesSkipsOnlyThePrerequisiteCheck() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "tc12_locked_force.yml", 1, false, KILL_QUEST.toString());
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();

        assertTrue(engine.availability(player.getUniqueId(), KILL_QUEST_TWO, true)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).available());

        // L'etat de la quete reste decisif, meme en ignorant les prerequis.
        engine.accept(player, KILL_QUEST_TWO, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestProgressEngine.Availability.Status.ALREADY_ACTIVE,
                engine.availability(player.getUniqueId(), KILL_QUEST_TWO, true)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
    }

    @Test
    void acceptProgressAndTurnInCycle() throws Exception {
        PlayerMock player = addPlayer();

        AcceptOutcome accept = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, accept.result());

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST));

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(record.isPresent());
        assertEquals(QuestState.COMPLETED, record.get().state());
    }

    @Test
    void reconnectionDuringAStepPreservesCounters() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2

        // Déconnexion puis reconnexion : le compteur doit être rechargé depuis la base, pas remis à zéro.
        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 2/2 -> devrait suffire si le compteur a été conservé

        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(record.isPresent());
        assertEquals(QuestState.COMPLETED, record.get().state(),
                "un seul kill après reconnexion doit suffire : le compteur (1/2) doit avoir survécu à la reconnexion");
    }

    @Test
    void twoIdenticalObjectivesInTwoQuestsProgressIndependently() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "kill_quest_two.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // KILL_QUEST a besoin de 2 ; KILL_QUEST_TWO n'en a besoin que d'1

        var stateOne = progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var stateTwo = progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, stateOne.orElseThrow().state());
        assertEquals(QuestState.COMPLETED, stateTwo.orElseThrow().state());
    }

    /**
     * Reproduit exactement le scénario signalé sur VeryGames (Story de test, quête BREAK_BLOCK cassée
     * dans le monde {@code wild}) : {@code BREAK_BLOCK} est documenté comme global — aucun champ
     * {@code world} sur l'objectif, doit compter dans n'importe quel monde (voir
     * {@code docs/RPGQUEST_BIBLE.md}, section 3). Contrairement aux autres tests de ce fichier, qui
     * appellent {@code engine.handleBreakBlock(...)} directement, celui-ci passe par un vrai {@link
     * BlockBreakEvent} construit sur un second monde nommé {@code wild} (jamais le monde par défaut
     * du serveur de test) et par le vrai {@link QuestBlockBreakListener} enregistré par {@code
     * engine.start()} — exercice fidèle du chemin réellement emprunté en jeu, pas seulement de la
     * logique interne de {@code QuestProgressEngine}.
     */
    @Test
    void breakBlockObjectiveProgressesFromABlockBreakEventFiredInAnyNamedWorldIncludingWild() throws Exception {
        writeBreakBlockQuest(BREAK_QUEST, "break_quest.yml", Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        World wild = server.addSimpleWorld("wild");
        PlayerMock player = addPlayer();
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.ACTIVE, activeState(player, BREAK_QUEST));

        breakDirtInWild(player, wild);
        assertEquals(QuestState.ACTIVE, activeState(player, BREAK_QUEST), "1/3 : toujours active, pas encore terminée");

        breakDirtInWild(player, wild);
        breakDirtInWild(player, wild);

        var record = progressRepository.find(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(record.isPresent());
        assertEquals(QuestState.COMPLETED, record.get().state(), "3/3 dans wild doit terminer la quête, exactement comme dans n'importe quel monde");
    }

    private void breakDirtInWild(PlayerMock player, World wild) {
        Block block = wild.getBlockAt(0, 64, 0);
        block.setType(Material.DIRT);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
    }

    /**
     * TODO(debug bug BREAK_BLOCK wild) : couvre l'instrumentation temporaire {@code
     * QuestProgressEngine#traceBreakBlockChain}/{@code QuestTraceLogger} — strictement le risque
     * qu'elle introduit (une lecture supplémentaire de {@code activeByPlayer} avant chaque cassage,
     * mélangeant potentiellement plusieurs types d'objectifs) sans jamais muter d'état. Reprend le
     * scénario déjà couvert ci-dessus (BREAK_BLOCK progresse normalement) en y ajoutant une seconde
     * quête active d'un type différent (KILL_ENTITY) pour vérifier que le passage en revue des
     * quêtes actives par l'instrumentation ne plante jamais sur un objectif non-BREAK_BLOCK et
     * n'altère en rien la progression réelle.
     */
    @Test
    void traceInstrumentationNeverAltersProgressionWithMixedActiveObjectiveTypes() throws Exception {
        writeBreakBlockQuest(BREAK_QUEST, "break_quest.yml", Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // quête active non-BREAK_BLOCK, en parallèle
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertDoesNotThrow(() -> {
            engine.handleBreakBlock(player, Material.DIRT);
            engine.handleBreakBlock(player, Material.DIRT);
            engine.handleBreakBlock(player, Material.DIRT);
        });

        var breakRecord = progressRepository.find(player.getUniqueId(), BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, breakRecord.orElseThrow().state(),
                "l'instrumentation ne doit jamais empêcher/retarder la progression réelle");
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST),
                "la quête KILL_ENTITY en parallèle ne doit jamais être affectée par le passage en revue fait pour BREAK_BLOCK");
    }

    /**
     * TODO(debug bug BREAK_BLOCK wild) : couvre la branche « candidats vides » de {@code
     * traceBreakBlockChain} (le joueur a une quête BREAK_BLOCK active, mais casse un matériau qui ne
     * correspond à aucun objectif chargé) — doit rester un no-op silencieux, jamais une exception,
     * jamais une fausse progression.
     */
    @Test
    void traceInstrumentationDoesNotThrowWhenTheBrokenMaterialMatchesNoLoadedObjective() throws Exception {
        writeBreakBlockQuest(BREAK_QUEST, "break_quest.yml", Material.DIRT, 3);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, BREAK_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertDoesNotThrow(() -> engine.handleBreakBlock(player, Material.STONE)); // aucune quête n'attend STONE

        assertEquals(QuestState.ACTIVE, activeState(player, BREAK_QUEST));
        var counters = progressRepository.findObjectiveProgress(player.getUniqueId(), BREAK_QUEST, "break_step")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(counters.getOrDefault(0, 0) == 0, "casser un matériau non chargé ne doit jamais faire progresser un objectif BREAK_BLOCK existant");
    }

    @Test
    void irrelevantEventDoesNothing() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.SKELETON); // la quête attend ZOMBIE
        engine.handleBreakBlock(player, org.bukkit.Material.STONE); // aucun objectif BREAK_BLOCK chargé

        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST));
    }

    @Test
    void abandonThenAcceptAgainRestartsCleanly() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2

        AbandonOutcome abandon = engine.abandon(player, KILL_QUEST);
        assertEquals(AbandonOutcome.ABANDONED, abandon);
        assertEquals(AbandonOutcome.NOTHING_TO_ABANDON, engine.abandon(player, KILL_QUEST));

        AcceptOutcome reaccept = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, reaccept.result());

        // Un seul kill après la ré-acceptation ne doit pas suffire : le compteur est reparti de zéro.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST));
    }

    @Test
    void nonRepeatableQuestAlreadyCompletedCannotBeAcceptedAgain() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "not_repeatable.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, record.orElseThrow().state());

        AcceptOutcome secondAccept = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.NOT_REPEATABLE, secondAccept.result());
    }

    // ---- Issue #13 : délai de ré-acceptation des quêtes renouvelables ---------------------

    @Test
    void repeatableQuestWithCooldownIsRefusedUntilTheDelayHasElapsed() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "cooldown.yml", 1, true, null, "20h");
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, record.orElseThrow().state());

        QuestProgressEngine.Availability availability =
                engine.availability(player.getUniqueId(), KILL_QUEST_TWO, false).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestProgressEngine.Availability.Status.ON_COOLDOWN, availability.status());

        AcceptOutcome again = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ON_COOLDOWN, again.result());
        assertTrue(again.retryAfter().compareTo(java.time.Duration.ofHours(19)) > 0, "≈ 20 h restantes");
        assertTrue(again.retryAfter().compareTo(java.time.Duration.ofHours(20)) <= 0);
        // Refusé = rien d'écrit : la quête reste terminée, aucune progression recréée.
        assertEquals(QuestState.COMPLETED, progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().state());
    }

    @Test
    void remainingCooldownsListsOnlyCompletedQuestsStillRecharging() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "cooldown.yml", 1, true, null, "20h");
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        assertTrue(engine.remainingCooldowns(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(engine.remainingCooldowns(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "une quête en cours n'est pas en recharge");
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        Map<NamespacedKey, java.time.Duration> cooldowns =
                engine.remainingCooldowns(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(java.util.Set.of(KILL_QUEST_TWO), cooldowns.keySet());
        assertTrue(cooldowns.get(KILL_QUEST_TWO).compareTo(java.time.Duration.ofHours(19)) > 0);
        assertTrue(engine.remainingCooldowns(addPlayer().getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "la recharge d'un joueur ne touche pas les autres");
    }

    @Test
    void repeatableQuestWithoutCooldownCanBeTakenAgainImmediately() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "repeatable.yml", 1, true, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.COMPLETED, progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().state());

        AcceptOutcome again = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, again.result());
    }

    @Test
    void remainingCooldownIsZeroOnceElapsedOrWithoutDelay() {
        QuestDefinition withDelay = questEngine.find(KILL_QUEST).orElseThrow();
        withDelay = new QuestDefinition(withDelay.id(), withDelay.title(), withDelay.description(), withDelay.category(),
                withDelay.icon(), true, false, List.of(), withDelay.steps(), withDelay.rewards(), Map.of(), null,
                java.time.Duration.ofHours(2));
        java.time.Instant completed = java.time.Instant.parse("2026-10-14T10:00:00Z");

        assertEquals(java.time.Duration.ofMinutes(30), QuestProgressEngine.remainingCooldown(
                withDelay, completed, completed.plus(java.time.Duration.ofMinutes(90))));
        assertEquals(java.time.Duration.ZERO, QuestProgressEngine.remainingCooldown(
                withDelay, completed, completed.plus(java.time.Duration.ofHours(2))));
        assertEquals(java.time.Duration.ZERO, QuestProgressEngine.remainingCooldown(withDelay, null, completed));
        assertEquals(java.time.Duration.ZERO, QuestProgressEngine.remainingCooldown(
                questEngine.find(KILL_QUEST).orElseThrow(), completed, completed));
    }

    @Test
    void preventsDoubleTurnIn() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int expBefore = player.getTotalExperience();

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // complète la quête, octroie la récompense

        int expAfterFirstCompletion = player.getTotalExperience();
        assertTrue(expAfterFirstCompletion > expBefore, "l'expérience doit avoir été accordée une fois");

        // D'autres événements après la fin ne doivent plus rien faire : la quête n'est plus active en mémoire.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        assertEquals(expAfterFirstCompletion, player.getTotalExperience(),
                "l'expérience ne doit pas être accordée deux fois");

        // La voie admin (bypass) doit elle aussi refuser une double remise.
        assertEquals(CompleteOutcome.ALREADY_COMPLETED,
                engine.forceComplete(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(expAfterFirstCompletion, player.getTotalExperience());
    }

    @Test
    void objectiveProgressIsShownViaActionBarNotChat() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // amount=2

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2

        String actionBar = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar());
        assertTrue(actionBar.contains("1/2"), () -> "actionbar attendu avec 1/2, obtenu : " + actionBar);
        // Issue #71 : nom traduit côté client (clé vanilla), jamais l'identifiant « ZOMBIE ».
        assertTrue(actionBar.contains("Zombie"), () -> "le nom de la cible doit apparaître : " + actionBar);
        assertFalse(actionBar.contains("ZOMBIE"), () -> "aucun identifiant technique : " + actionBar);
        assertNull(player.nextMessage(), "aucun message de progression d'objectif ne doit apparaître dans le chat");
    }

    @Test
    void objectiveProgressActionBarUpdatesOnEachIncrement() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // amount=2

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2
        player.nextActionBar();

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 2/2 : complète l'objectif (et la quête)
        String actionBar = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar());
        assertTrue(actionBar.contains("2/2"), () -> "actionbar attendu avec 2/2, obtenu : " + actionBar);
    }

    @Test
    void irrelevantEventNeverTriggersAnActionBar() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.SKELETON); // la quête attend ZOMBIE

        assertNull(player.nextActionBar(), "aucune progression réelle : aucune actionbar ne doit être envoyée");
    }

    @Test
    void advanceStepShowsObjectiveProgressForEachForciblyCompletedObjective() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // amount=2

        boolean advanced = engine.advanceStep(player, KILL_QUEST);

        assertTrue(advanced);
        String actionBar = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar());
        assertTrue(actionBar.contains("2/2"), () -> "avancement forcé : doit refléter le compteur final, obtenu : " + actionBar);
    }

    /**
     * Issue #235 — accepter une quête annonce <strong>ce qu'il faut faire</strong>.
     *
     * <p>Ce test remplace {@code acceptingAQuestNeverSendsAChatMessage}, qui affirmait le contraire.
     * Deux raisons de l'avoir remplacé plutôt que gardé :</p>
     * <ul>
     *   <li>le <strong>contrat a changé</strong> : un Title qui disparaît en deux secondes et ne dit
     *       que le nom de la quête laissait le joueur sans savoir quoi faire — c'est le retour
     *       utilisateur qui a ouvert #235 ;</li>
     *   <li>l'ancien test ne prouvait <strong>rien</strong> : la notification est planifiée sur le
     *       thread principal, et il ne tickait pas le scheduler. Il aurait passé quel que soit le
     *       comportement. D'où le {@code performTicks} explicite ici.</li>
     * </ul>
     */
    @Test
    void acceptingAQuestAnnouncesItsObjectivesInChat() throws Exception {
        PlayerMock player = quietPlayer();

        AcceptOutcome outcome = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        assertEquals(AcceptOutcome.Result.ACCEPTED, outcome.result());
        String header = player.nextMessage();
        assertNotNull(header, "le démarrage doit annoncer la quête et ses objectifs");
        assertTrue(header.contains("Titre"), () -> "le nom de la quête est attendu : " + header);
        assertTrue(header.contains("journal de quêtes"), () -> "où retrouver l'objectif est attendu : " + header);
        // Issue #467 : jamais une commande dans un message joueur.
        assertFalse(header.contains("/quest"), () -> "aucun renvoi à une commande : " + header);

        String objective = player.nextMessage();
        assertNotNull(objective, "le premier objectif doit être lisible immédiatement");
        // Issue #238 : une CLÉ de traduction, pas l'identifiant brut. C'est ce qui permet au client
        // d'afficher « Zombie » en français — l'ancien message affichait « ZOMBIE », et
        // « WHEAT_SEEDS » pour les graines de blé, au milieu d'une interface française.
        assertTrue(objective.contains("entity.minecraft.zombie"),
                () -> "l'objectif doit porter la clé de traduction Minecraft : " + objective);
        assertFalse(objective.contains("ZOMBIE"),
                () -> "l'identifiant brut ne doit plus apparaître : " + objective);
        assertTrue(objective.contains("2"), () -> "la quantité demandée est attendue : " + objective);

        assertNull(player.nextMessage(), "cette quête n'a qu'un objectif : pas de ligne de plus");
    }

    /** Une quête à plusieurs objectifs les annonce tous — un seul suffirait à égarer le joueur. */
    @Test
    void everyObjectiveOfTheFirstStepIsAnnounced() throws Exception {
        NamespacedKey twoObjectives = new NamespacedKey("rpgquest", "two_objectives");
        Files.writeString(questsDir.resolve("two_objectives.yml"), """
                id: rpgquest:two_objectives
                title: "Double"
                description: "Deux choses à faire."
                category: test
                icon: STONE
                steps:
                  - id: s1
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 2
                      - type: COLLECT_ITEM
                        material: DIRT
                        amount: 3
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = quietPlayer();

        engine.accept(player, twoObjectives).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        player.nextMessage(); // en-tête
        String first = player.nextMessage();
        String second = player.nextMessage();
        assertNotNull(second, "les deux objectifs doivent être annoncés");
        assertTrue(first.contains("entity.minecraft.zombie"), first);
        assertTrue(second.contains("block.minecraft.dirt"), second);
        assertNull(player.nextMessage());
    }

    /**
     * Issue #238 — le cas exact rapporté en jeu : « WHEAT_SEEDS » affiché dans une interface
     * française.
     *
     * <p>Les graines de blé sont le pire exemple : l'identifiant porte un {@code _}, donc même un
     * joueur anglophone lisait « WHEAT_SEEDS » au lieu de « Wheat Seeds ». La clé de traduction
     * officielle de Minecraft règle les deux cas d'un coup, sans aucune table française maison.</p>
     */
    @Test
    void announcedObjectivesCarryMinecraftTranslationKeysNotRawIdentifiers() throws Exception {
        NamespacedKey seeds = new NamespacedKey("rpgquest", "seeds_quest");
        Files.writeString(questsDir.resolve("seeds_quest.yml"), """
                id: rpgquest:seeds_quest
                title: "Graines"
                description: "Ramasser des graines."
                category: test
                icon: WHEAT_SEEDS
                steps:
                  - id: s1
                    objectives:
                      - type: COLLECT_ITEM
                        material: WHEAT_SEEDS
                        amount: 4
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = quietPlayer();

        engine.accept(player, seeds).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        player.nextMessage(); // en-tête
        String objective = player.nextMessage();
        assertNotNull(objective, "l'objectif doit être annoncé");
        assertTrue(objective.contains("wheat_seeds"),
                () -> "la clé de traduction des graines est attendue : " + objective);
        assertFalse(objective.contains("WHEAT_SEEDS"),
                () -> "l'identifiant brut ne doit plus apparaître : " + objective);
        assertTrue(objective.contains("4"), () -> "la quantité est attendue : " + objective);
    }

    // ---- Issue #70 : chaque transition d'étape annonce la prochaine action ----------------------

    @Test
    void advancingToTheNextStepAnnouncesItsObjectivesInChat() throws Exception {
        NamespacedKey twoSteps = new NamespacedKey("rpgquest", "two_steps_announce");
        writeTwoStepKillQuest(twoSteps, "two_steps_announce.yml");
        engine.reloadQuestDefinitions();
        PlayerMock player = quietPlayer();
        engine.accept(player, twoSteps).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        while (player.nextMessage() != null) {
            // annonce de démarrage, déjà couverte plus haut
        }

        engine.handleKillEntity(player, EntityType.ZOMBIE); // termine step_one
        server.getScheduler().performTicks(2);

        String header = player.nextMessage();
        assertNotNull(header, "le passage d'étape doit dire quoi faire ensuite");
        assertTrue(header.contains("Étape suivante"), () -> "en-tête d'étape attendu : " + header);
        assertTrue(header.contains("Titre"), () -> "le nom de la quête est attendu : " + header);
        String objective = player.nextMessage();
        assertNotNull(objective, "l'objectif de la nouvelle étape doit être annoncé");
        assertTrue(objective.contains("entity.minecraft.skeleton"),
                () -> "l'objectif de step_two (et non step_one) est attendu : " + objective);
        assertTrue(objective.contains("1"), () -> "la quantité est attendue : " + objective);
        assertNull(player.nextMessage());
    }

    @Test
    void talkToNpcObjectiveNamesTheNpcAndShowsNoQuantity() throws Exception {
        NamespacedKey talk = new NamespacedKey("rpgquest", "talk_announce");
        Files.writeString(questsDir.resolve("talk_announce.yml"), """
                id: rpgquest:talk_announce
                title: "Parlote"
                description: "Aller voir le garde."
                category: test
                icon: STONE
                steps:
                  - id: s1
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                  - id: s2
                    objectives:
                      - type: TALK_TO_NPC
                        npc: guard
                """);
        engine.reloadQuestDefinitions();
        engine.setNpcNameResolver(id -> "guard".equals(id) ? Optional.of("Garde") : Optional.empty());
        PlayerMock player = quietPlayer();
        engine.accept(player, talk).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        while (player.nextMessage() != null) {
            // annonce de démarrage
        }

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        server.getScheduler().performTicks(2);

        player.nextMessage(); // en-tête
        String objective = player.nextMessage();
        assertNotNull(objective);
        assertTrue(objective.contains("Parler à Garde"), () -> "le PNJ cible doit être nommé : " + objective);
        assertFalse(objective.contains("guard"), () -> "l'identifiant logique ne doit pas apparaître : " + objective);
        assertFalse(objective.contains("x1"), () -> "pas de quantité pour un objectif binaire : " + objective);
    }

    @Test
    void finishingTheLastStepDoesNotAnnounceANextStep() throws Exception {
        PlayerMock player = quietPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // une seule étape
        server.getScheduler().performTicks(2);
        while (player.nextMessage() != null) {
            // annonce de démarrage
        }

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        server.getScheduler().performTicks(2);

        for (String message = player.nextMessage(); message != null; message = player.nextMessage()) {
            String m = message;
            assertFalse(m.contains("Étape suivante"), () -> "aucune étape après la dernière : " + m);
        }
    }

    @Test
    void completingAQuestSendsAChatSummaryOfRewardsActuallyGranted() throws Exception {
        // writeKillQuest() donne une seule récompense : 10 XP (voir la méthode utilitaire en bas de
        // fichier). Le résumé chat doit refléter exactement ça, jamais une récompense inventée
        // (monnaie, objet...) que cette quête ne donne pas.
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        player.nextActionBar();
        engine.handleKillEntity(player, EntityType.ZOMBIE); // complète la quête

        String header = player.nextMessage();
        assertTrue(header.contains("Titre"), () -> "le résumé doit citer le nom de la quête terminée : " + header);

        String rewardLine = player.nextMessage();
        assertTrue(rewardLine.contains("10 XP"), () -> "doit afficher l'XP réellement accordée : " + rewardLine);

        assertNull(player.nextMessage(), "aucune ligne supplémentaire : cette quête ne donne ni objet ni récompense spéciale");
    }

    @Test
    void rewardSummaryListsAnItemAndACommandRewardWithoutInventingDetails() throws Exception {
        NamespacedKey questId = new NamespacedKey("rpgquest", "mixed_rewards");
        Files.writeString(questsDir.resolve("mixed_rewards.yml"), """
                id: rpgquest:mixed_rewards
                title: "Titre Mixte"
                description: "Description"
                category: test
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: ITEM
                    material: IRON_INGOT
                    amount: 3
                  - type: COMMAND
                    command: "help"
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        player.nextMessage(); // en-tête du résumé
        String itemLine = player.nextMessage();
        assertTrue(itemLine.contains("3x IRON_INGOT"), () -> "doit afficher l'objet réellement donné : " + itemLine);
        String specialLine = player.nextMessage();
        assertTrue(specialLine.toLowerCase(java.util.Locale.ROOT).contains("spéciale"),
                () -> "une récompense COMMAND ne peut pas être décrite précisément (commande arbitraire) : " + specialLine);
        assertNull(player.nextMessage());
    }

    @Test
    void questWithNoRewardsSendsNoChatSummary() throws Exception {
        NamespacedKey questId = new NamespacedKey("rpgquest", "no_rewards");
        Files.writeString(questsDir.resolve("no_rewards.yml"), """
                id: rpgquest:no_rewards
                title: "Sans récompense"
                description: "Description"
                category: test
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        assertNull(player.nextMessage(),
                "une quête sans récompense ne doit jamais afficher de résumé (pas de récompense fictive)");
    }

    @Test
    void acceptRejectsWhenPrerequisiteNotCompleted() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "with_prereq.yml", 1, false, KILL_QUEST.toString());
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        AcceptOutcome outcome = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AcceptOutcome.Result.MISSING_PREREQUISITES, outcome.result());
        assertEquals(List.of(KILL_QUEST), outcome.missingPrerequisites());
    }

    @Test
    void acceptWithIgnorePrerequisitesBypassesTheMissingPrerequisiteCheck() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "with_prereq_forced.yml", 1, false, KILL_QUEST.toString());
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        AcceptOutcome outcome = engine.accept(player, KILL_QUEST_TWO, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AcceptOutcome.Result.ACCEPTED, outcome.result(),
                "ignorePrerequisites=true doit sauter la seule vérification des prérequis");
        assertEquals(QuestState.ACTIVE, engine.stateOf(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void acceptWithIgnorePrerequisitesStillRefusesAnAlreadyActiveQuest() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "already_active_forced.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        AcceptOutcome again = engine.accept(player, KILL_QUEST_TWO, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AcceptOutcome.Result.ALREADY_ACTIVE, again.result(),
                "ignorePrerequisites ne relâche QUE le contrôle des prérequis, jamais la garde anti-doublon");
    }

    @Test
    void resetQuestAllowsRestartEvenWhenNotRepeatable() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "not_repeatable_reset.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // complète et remet la quête

        var completed = progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, completed.orElseThrow().state());

        engine.resetQuest(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "l'état persisté doit avoir disparu, pas juste être remis à NOT_STARTED");
        assertTrue(progressRepository.findObjectiveProgress(player.getUniqueId(), KILL_QUEST_TWO, "kill_step")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        AcceptOutcome reaccept = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, reaccept.result(),
                "repeatable=false ne doit plus bloquer l'acceptation après un reset admin");
    }

    @Test
    void resetQuestClearsInMemoryCacheAndDoesNotTouchOtherQuests() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "kill_quest_two_reset.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2 sur KILL_QUEST, complète KILL_QUEST_TWO (besoin de 1)

        engine.resetQuest(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        assertEquals(QuestState.COMPLETED, progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().state(),
                "resetQuest ne doit affecter que la quête ciblée");

        // La progression en mémoire de KILL_QUEST doit aussi avoir disparu : un kill supplémentaire ne la fait pas avancer.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertTrue(progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void objectiveEventBeforeAcceptingTheQuestIsIgnored() throws Exception {
        PlayerMock player = addPlayer();

        // Aucun accept() : l'index connaît l'objectif (le fichier de quête est chargé), mais le joueur
        // n'a aucune progression en mémoire. L'événement ne doit ni lever d'exception ni créer d'état.
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        assertTrue(progressRepository.findAll(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "aucune progression ne doit être créée par un événement reçu avant l'acceptation");
    }

    @Test
    void objectiveOnALaterStepIsIgnoredUntilItsOwnStepIsActive() throws Exception {
        writeTwoStepKillQuest(KILL_QUEST_TWO, "two_step_kill_quest.yml");
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // L'étape courante attend un ZOMBIE ; SKELETON appartient à l'étape suivante (pas encore active).
        engine.handleKillEntity(player, EntityType.SKELETON);
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST_TWO));
        assertEquals("step_one",
                progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().currentStepId(),
                "un événement de la 2e étape ne doit pas faire progresser la quête tant que la 1re étape n'est pas finie");

        // Compléter la 1re étape doit maintenant faire passer la quête à la 2e étape, sans la terminer.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST_TWO));
        assertEquals("step_two",
                progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().currentStepId());

        // Le SKELETON compte maintenant que l'étape 2 est active.
        engine.handleKillEntity(player, EntityType.SKELETON);
        assertEquals(QuestState.COMPLETED,
                progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().state());
    }

    @Test
    void resetAllQuestsClearsEveryQuestForPlayer() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "kill_quest_two_reset_all.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        engine.resetAllQuests(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(progressRepository.findAll(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        AcceptOutcome reaccept = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, reaccept.result());
    }

    /** Ajoute un joueur MockBukkit et crée son profil dans la base de test (requis par la FK de quest_progress). */
    /**
     * Un joueur dont la file de messages est <strong>réellement</strong> vide.
     *
     * <p>Le plugin complet tourne dans ces tests, et la Rune de rappel de départ est remise par une
     * tâche planifiée : vider la file sans avoir d'abord tické le scheduler ne sert à rien, le
     * message arrive ensuite. On tick donc, puis on vide — sinon un test de notification lirait le
     * message d'un autre écouteur et échouerait pour une raison sans rapport.</p>
     */
    private PlayerMock quietPlayer() throws Exception {
        PlayerMock player = addPlayer();
        server.getScheduler().performTicks(5);
        while (player.nextMessage() != null) {
            // volontairement vide
        }
        return player;
    }

    // ---- Issue #318 : récompenses d'objets et inventaire plein — jamais au sol, jamais perdues ----

    private static final NamespacedKey ITEM_QUEST = new NamespacedKey("rpgquest", "item_reward");

    private void writeItemRewardQuest() throws Exception {
        Files.writeString(questsDir.resolve("item_reward.yml"), """
                id: rpgquest:item_reward
                title: "Titre Objet"
                description: "Description"
                category: test
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: EXPERIENCE
                    amount: 10
                  - type: ITEM
                    material: IRON_INGOT
                    amount: 3
                """);
        engine.reloadQuestDefinitions();
    }

    /** Toutes les cases, armure et seconde main comprises : MockBukkit, contrairement à Paper, y range le surplus. */
    private static void fillInventory(PlayerMock player) {
        ItemStack[] full = new ItemStack[player.getInventory().getContents().length];
        java.util.Arrays.fill(full, new ItemStack(Material.DIRT, 64));
        player.getInventory().setContents(full);
    }

    private static void drainMessages(PlayerMock player) {
        while (player.nextMessage() != null) {
            // vide la file
        }
    }

    @Test
    void aQuestCompletedWithAFullInventoryWaitsAndGrantsNothingThenCompletesOnceThereIsRoom() throws Exception {
        writeItemRewardQuest();
        PlayerMock player = addPlayer();
        engine.accept(player, ITEM_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        fillInventory(player);
        drainMessages(player);
        int expBefore = player.getTotalExperience();

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        assertEquals(QuestState.READY_TO_TURN_IN, activeState(player, ITEM_QUEST), "en attente de place, persistée");
        assertEquals(expBefore, player.getTotalExperience(), "aucune récompense partielle");
        assertFalse(player.getInventory().contains(Material.IRON_INGOT));
        assertTrue(player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).isEmpty(), "rien au sol");
        String warning = player.nextMessage();
        assertTrue(warning != null && warning.contains("Inventaire plein"), () -> "message : " + warning);

        engine.retryAwaitingTurnIns(player);
        assertEquals(QuestState.READY_TO_TURN_IN, activeState(player, ITEM_QUEST), "toujours pas de place");
        assertNull(player.nextMessage(), "une reprise automatique qui échoue ne répète pas le message");

        player.getInventory().setItem(0, null);
        engine.retryAwaitingTurnIns(player);
        engine.retryAwaitingTurnIns(player);

        assertEquals(QuestState.COMPLETED, activeState(player, ITEM_QUEST));
        assertEquals(3, player.getInventory().all(Material.IRON_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum(),
                "remise une seule fois malgré deux reprises");
        assertTrue(player.getTotalExperience() > expBefore);
    }

    @Test
    void anAwaitingQuestIsCompletedAfterAReconnection() throws Exception {
        writeItemRewardQuest();
        PlayerMock player = addPlayer();
        engine.accept(player, ITEM_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        fillInventory(player);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.READY_TO_TURN_IN, activeState(player, ITEM_QUEST));

        engine.unloadForPlayer(player.getUniqueId());
        // Deux cases : le plugin chargé peut y glisser la Rune de rappel du kit de départ entre-temps.
        player.getInventory().setItem(0, null);
        player.getInventory().setItem(1, null);
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        assertEquals(QuestState.COMPLETED, activeState(player, ITEM_QUEST));
        assertTrue(player.getInventory().contains(Material.IRON_INGOT, 3));
    }

    @Test
    void aRequestedTurnInWithAFullInventoryIsRefusedAndLeavesTheQuestUntouched() throws Exception {
        writeItemRewardQuest();
        PlayerMock player = addPlayer();
        engine.accept(player, ITEM_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        fillInventory(player);
        drainMessages(player);

        assertEquals(CompleteOutcome.INVENTORY_FULL, engine.forceComplete(player, ITEM_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(QuestState.ACTIVE, activeState(player, ITEM_QUEST), "la quête reste en cours, à retenter");
        String warning = player.nextMessage();
        assertTrue(warning != null && warning.contains("réessaie"), () -> "message : " + warning);

        player.getInventory().setItem(0, null);
        assertEquals(CompleteOutcome.COMPLETED, engine.forceComplete(player, ITEM_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(player.getInventory().contains(Material.IRON_INGOT, 3));
    }

    @Test
    void aRefusedTurnInOfANeverStartedQuestLeavesNoFakeProgress() throws Exception {
        writeItemRewardQuest();
        PlayerMock player = addPlayer();
        fillInventory(player);

        var future = engine.forceComplete(player, ITEM_QUEST);
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1); // la remise passe par le thread principal
            Thread.sleep(10);
        }

        assertEquals(CompleteOutcome.INVENTORY_FULL, future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(engine.activeStepView(player.getUniqueId(), ITEM_QUEST).isEmpty(), "aucune progression fictive en mémoire");
        assertTrue(progressRepository.find(player.getUniqueId(), ITEM_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    // ---- Issue #13, TC-306 : étape déjà pleine rechargée, plus jamais close ------------------

    private static final NamespacedKey SPIDER_QUEST = new NamespacedKey("rpgquest", "tc306_spiders");

    /** Copie de quests/encounter_bandit_spiders.yml (chasse puis retour au PNJ récurrent). */
    private void writeSpiderQuest() throws Exception {
        Files.writeString(questsDir.resolve("tc306_spiders.yml"), """
                id: rpgquest:tc306_spiders
                title: "Les toiles du sous-bois"
                description: "Six araignées puis retour au Brigand."
                category: encounter
                giver: encounter_bandit
                repeatable: true
                cooldown: 20h
                steps:
                  - id: clear_spiders
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 6
                  - id: report_to_bandit
                    objectives:
                      - type: TALK_TO_NPC
                        npc: encounter_bandit
                rewards:
                  - type: EXPERIENCE
                    amount: 25
                  - type: ITEM
                    material: ARROW
                    amount: 16
                """);
        engine.reloadQuestDefinitions();
    }

    /**
     * État réel de LoDyMcFly sur DEV (20/10) : quête reprise avant le correctif de startQuest, donc
     * compteurs de l'exécution précédente restés en base — chasse 6/6 ET retour 1/1 — avec l'état
     * ACTIVE à l'étape de chasse. Rechargée, l'étape restait pleine sans jamais se clore.
     */
    private PlayerMock playerStuckAtFullSpiderStep() throws Exception {
        writeSpiderQuest();
        PlayerMock player = addPlayer();
        UUID id = player.getUniqueId();
        progressRepository.upsertState(id, SPIDER_QUEST, QuestState.ACTIVE, "clear_spiders").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressRepository.setObjectiveProgress(id, SPIDER_QUEST, "clear_spiders", 0, 6).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressRepository.setObjectiveProgress(id, SPIDER_QUEST, "report_to_bandit", 0, 1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.unloadForPlayer(id);
        engine.loadForPlayer(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        return player;
    }

    private int arrows(PlayerMock player) {
        return player.getInventory().all(Material.ARROW).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    @Test
    void aReloadedFullHuntStepAdvancesToTheReturnStepWithoutPayingAnything() throws Exception {
        PlayerMock player = playerStuckAtFullSpiderStep();
        UUID id = player.getUniqueId();

        var record = progressRepository.find(id, SPIDER_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(QuestState.ACTIVE, record.state());
        assertEquals("report_to_bandit", record.currentStepId(), "la chasse 6/6 doit ouvrir l'étape de retour");
        assertTrue(progressRepository.findObjectiveProgress(id, SPIDER_QUEST, "report_to_bandit")
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "le « 1/1 » résiduel de l'exécution précédente ne doit pas rendre la quête tout seul");
        assertEquals(0, arrows(player), "passer d'étape n'accorde rien : la remise attend le PNJ");
    }

    @Test
    void talkingToTheEncounterNpcThenCompletesTheQuestAndPaysOnce() throws Exception {
        PlayerMock player = playerStuckAtFullSpiderStep();
        int expBefore = player.getTotalExperience();

        engine.handleTalkToNpc(player, "encounter_bandit");
        engine.handleTalkToNpc(player, "encounter_bandit"); // double clic
        server.getScheduler().performTicks(2);

        assertEquals(QuestState.COMPLETED, activeState(player, SPIDER_QUEST));
        assertEquals(16, arrows(player), "16 flèches, une seule fois");
        assertTrue(player.getTotalExperience() > expBefore);

        // Reconnexion / redémarrage : rien n'est rejoué.
        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);
        engine.handleTalkToNpc(player, "encounter_bandit");
        assertEquals(QuestState.COMPLETED, activeState(player, SPIDER_QUEST));
        assertEquals(16, arrows(player));
        assertEquals(QuestProgressEngine.Availability.Status.ON_COOLDOWN,
                engine.availability(player.getUniqueId(), SPIDER_QUEST, false)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status(),
                "le délai de 20 h part de cette complétion");
    }

    @Test
    void aReloadedFullLastStepIsNeverPaidAtLoginButClosesOnThePlayersNextAction() throws Exception {
        writeItemRewardQuest();
        PlayerMock player = addPlayer();
        UUID id = player.getUniqueId();
        progressRepository.upsertState(id, ITEM_QUEST, QuestState.ACTIVE, "kill_step").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressRepository.setObjectiveProgress(id, ITEM_QUEST, "kill_step", 0, 1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.unloadForPlayer(id);
        engine.loadForPlayer(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        assertEquals(QuestState.ACTIVE, activeState(player, ITEM_QUEST), "aucune remise sans action du joueur");
        assertFalse(player.getInventory().contains(Material.IRON_INGOT));

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        assertEquals(QuestState.COMPLETED, activeState(player, ITEM_QUEST));
        assertEquals(3, player.getInventory().all(Material.IRON_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum(),
                "remise unique malgré deux actions");
    }

    @Test
    void advancingAStepClearsTheStaleCountersOfTheNextStep() throws Exception {
        writeSpiderQuest();
        PlayerMock player = addPlayer();
        UUID id = player.getUniqueId();
        engine.accept(player, SPIDER_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressRepository.setObjectiveProgress(id, SPIDER_QUEST, "report_to_bandit", 0, 1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        for (int i = 0; i < 6; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        engine.unloadForPlayer(id);
        engine.loadForPlayer(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(2);

        assertEquals(QuestState.ACTIVE, activeState(player, SPIDER_QUEST), "le retour au PNJ reste à faire");
        assertEquals(0, arrows(player));
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private QuestState activeState(PlayerMock player, NamespacedKey questId) throws Exception {
        return progressRepository.find(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow()
                .state();
    }

    private void writeKillQuest(NamespacedKey id, String fileName, int amount, boolean repeatable, String prerequisite) throws Exception {
        writeKillQuest(id, fileName, amount, repeatable, prerequisite, null);
    }

    private void writeKillQuest(NamespacedKey id, String fileName, int amount, boolean repeatable, String prerequisite,
                                String cooldown) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("title: \"Titre\"\n");
        yaml.append("description: \"Description\"\n");
        yaml.append("category: test\n");
        yaml.append("repeatable: ").append(repeatable).append('\n');
        if (cooldown != null) {
            yaml.append("cooldown: ").append(cooldown).append('\n');
        }
        if (prerequisite != null) {
            yaml.append("prerequisites:\n  - ").append(prerequisite).append('\n');
        }
        yaml.append("""
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: %d

                rewards:
                  - type: EXPERIENCE
                    amount: 10
                """.formatted(amount));
        Files.writeString(questsDir.resolve(fileName), yaml.toString());
    }

    private void writeBreakBlockQuest(NamespacedKey id, String fileName, Material material, int amount) throws Exception {
        String yaml = """
                id: %s
                title: "Titre"
                description: "Description"
                category: test
                repeatable: true

                steps:
                  - id: break_step
                    objectives:
                      - type: BREAK_BLOCK
                        material: %s
                        amount: %d

                rewards:
                  - type: EXPERIENCE
                    amount: 5
                """.formatted(id, material, amount);
        Files.writeString(questsDir.resolve(fileName), yaml);
    }

    /** Quête à 2 étapes (step_one : 1 ZOMBIE, step_two : 1 SKELETON) pour tester la garde d'étape active. */
    private void writeTwoStepKillQuest(NamespacedKey id, String fileName) throws Exception {
        String yaml = """
                id: %s
                title: "Titre"
                description: "Description"
                category: test
                repeatable: false
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                  - id: step_two
                    objectives:
                      - type: KILL_ENTITY
                        entity: SKELETON
                        amount: 1

                rewards:
                  - type: EXPERIENCE
                    amount: 10
                """.formatted(id);
        Files.writeString(questsDir.resolve(fileName), yaml);
    }

    // ---- Récompenses monétaires (issue #16) ---------------------------------------------------
    //
    // Ces tests verrouillent le comportement du MOTEUR : combien de demandes de paiement il émet,
    // avec quelle identité, et ce qu'il annonce selon la réponse. L'idempotence et la survie des
    // dettes vivent en SQL et sont vérifiées dans WalletRepositoryTest.

    @Test
    void aMoneyRewardIsRequestedOnceWithTheIdentityRecordedInTheDatabase() throws Exception {
        NamespacedKey questId = writeMoneyQuest("money_quest.yml", "rpgquest:money_quest", 250, false);
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitCalls(1);

        assertEquals(1, rewardPayer.calls.size(), "une complétion = une seule demande de paiement");
        RecordingRewardPayer.Call call = rewardPayer.calls.get(0);
        assertEquals(player.getUniqueId(), call.playerId());
        // L'identité vient de la dette ÉCRITE EN BASE, pas d'un identifiant jeté en mémoire :
        // c'est elle qu'une reprise après redémarrage réutilisera.
        assertFalse(call.grantId().isBlank());
        assertTrue(call.grantId().endsWith("#0"), () -> call.grantId());
    }

    @Test
    void twoMoneyRewardsOnOneCompletionAreRequestedWithTwoDistinctIdentities() throws Exception {
        NamespacedKey questId = new NamespacedKey("rpgquest", "two_money");
        Files.writeString(questsDir.resolve("two_money.yml"), """
                id: rpgquest:two_money
                title: "Deux récompenses"
                description: "Description"
                category: test
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: MONEY
                    amount: 100
                  - type: MONEY
                    amount: 30
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitCalls(2);

        // Avec une identité partagée, la seconde serait avalée comme « déjà payée » et le joueur
        // ne recevrait que la première — défaut mesuré du premier lot.
        assertEquals(2, rewardPayer.calls.size());
        assertFalse(rewardPayer.calls.get(0).grantId().equals(rewardPayer.calls.get(1).grantId()));
    }

    @Test
    void theSuccessMessageArrivesOnlyAfterTheCreditIsConfirmedAndCarriesTheNewBalance() throws Exception {
        NamespacedKey questId = writeMoneyQuest("money_quest.yml", "rpgquest:money_quest", 250, false);
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rewardPayer.answer = new QuestRewardReceipt(QuestRewardReceipt.Status.CREDITED, 250L, 1250L, 1);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        String message = awaitMessageContaining(player, "250");
        assertTrue(message.contains("Titre Monnaie"), () -> "le message doit citer la quête : " + message);
        assertTrue(message.contains("1250"), () -> "le nouveau solde doit venir du reçu : " + message);
    }

    @Test
    void aFailedCreditIsSaidHonestlyAndNeverPresentedAsAGain() throws Exception {
        NamespacedKey questId = writeMoneyQuest("money_quest.yml", "rpgquest:money_quest", 250, false);
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rewardPayer.answer = QuestRewardReceipt.failed();

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        String message = awaitMessageContaining(player, "pas pu être créditée");
        assertFalse(message.contains("250"), () -> "un échec ne doit jamais citer un montant gagné : " + message);
    }

    @Test
    void aFailedCreditIsRecordedSoRetriesCanBeBounded() throws Exception {
        NamespacedKey questId = writeMoneyQuest("money_quest.yml", "rpgquest:money_quest", 250, false);
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rewardPayer.answer = QuestRewardReceipt.failed();

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitMessageContaining(player, "pas pu être créditée");

        // Sans compteur, la reprise automatique tournerait indéfiniment sur une cause qu'elle ne
        // peut pas corriger.
        assertFalse(rewardPayer.failures.isEmpty(), "l'échec doit être enregistré sur la dette");
    }

    @Test
    void anAlreadyCreditedOccasionSaysNothingToThePlayer() throws Exception {
        NamespacedKey questId = writeMoneyQuest("money_quest.yml", "rpgquest:money_quest", 250, false);
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rewardPayer.answer = new QuestRewardReceipt(QuestRewardReceipt.Status.ALREADY_CREDITED, 250L, 250L, 1);

        // Vider ce que d'autres services ont pu dire à la connexion : ce test ne parle que d'argent.
        while (player.nextMessage() != null) {
            // on jette
        }

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        server.getScheduler().performTicks(20);

        String message;
        while ((message = player.nextMessage()) != null) {
            assertFalse(message.contains("250"),
                    () -> "une occasion déjà payée ne doit annoncer aucun gain : ce serait un gain fantôme");
            assertFalse(message.contains("pas pu être créditée"),
                    "déjà payé n'est pas un échec : le dire comme tel inquiéterait pour rien");
        }
    }

    @Test
    void aMoneyRewardAddsNoLineToTheSynchronousRewardSummary() throws Exception {
        NamespacedKey questId = new NamespacedKey("rpgquest", "xp_and_money");
        Files.writeString(questsDir.resolve("xp_and_money.yml"), """
                id: rpgquest:xp_and_money
                title: "Titre Mixte"
                description: "Description"
                category: test
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: EXPERIENCE
                    amount: 10
                  - type: MONEY
                    amount: 500
                """);
        engine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rewardPayer.answer = new QuestRewardReceipt(QuestRewardReceipt.Status.CREDITED, 500L, 500L, 1);

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        player.nextMessage(); // en-tête du résumé
        String xpLine = player.nextMessage();
        assertTrue(xpLine.contains("10 XP"), () -> xpLine);
        assertNull(player.nextMessage(), "le résumé immédiat ne peut pas encore parler du crédit");

        // …et le message monétaire arrive bien ensuite, séparément.
        assertTrue(awaitMessageContaining(player, "500").contains("500"));
    }

    @Test
    void eachCompletionOfARepeatableQuestIsANewOccasion() throws Exception {
        NamespacedKey questId = writeMoneyQuest("money_repeat.yml", "rpgquest:money_repeat", 50, true);
        PlayerMock player = addPlayer();

        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitCalls(1);
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        awaitCalls(2);

        assertEquals(2, rewardPayer.calls.size(), "une quête répétable se paie à chaque complétion");
        assertFalse(rewardPayer.calls.get(0).grantId().equals(rewardPayer.calls.get(1).grantId()),
                "deux complétions distinctes doivent porter deux identités distinctes, sinon la seconde "
                        + "serait refusée comme un doublon et le joueur ne serait jamais repayé");
    }

    @Test
    void aQuestWithoutAMoneyRewardNeverAsksTheEconomyForAnything() throws Exception {
        // writeKillQuest ne donne que de l'XP : toucher au portefeuille ici serait un bug.
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        server.getScheduler().performTicks(20);

        assertTrue(rewardPayer.calls.isEmpty());
    }

    @Test
    void aPendingRewardIsRetriedWhenThePlayerLoadsAgain() throws Exception {
        // Reprise : la dette est lue au chargement du joueur, une seule fois, et payée avec son
        // identité INITIALE.
        PlayerMock player = addPlayer();
        rewardPayer.pending.add(new QuestRewardDue("token-orphelin#0", "rpgquest:money_quest", 1, 0, 70L, 0, null));

        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitCalls(1);

        assertEquals("token-orphelin#0", rewardPayer.calls.get(0).grantId(),
                "une reprise doit réutiliser l'identité initiale, jamais en créer une nouvelle");
    }

    @Test
    void aRewardThatFailedTooManyTimesIsLeftToThePanelInsteadOfBeingRetriedForever() throws Exception {
        PlayerMock player = addPlayer();
        rewardPayer.pending.add(new QuestRewardDue("token-cassé#0", "rpgquest:money_quest", 1, 0, 70L,
                5, "SQLException: disk I/O error"));

        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(20);

        assertTrue(rewardPayer.calls.isEmpty(),
                "au-delà de la borne d'essais, s'acharner ne corrigerait pas la cause");
    }

    @Test
    void aRecoveryNeverReplaysTheNonMonetaryRewards() throws Exception {
        // La reprise ne touche qu'aux lignes de dette : pas d'XP re-donnée, pas de résumé rejoué.
        PlayerMock player = addPlayer();
        int xpBefore = player.getTotalExperience();
        rewardPayer.pending.add(new QuestRewardDue("token-orphelin#0", "rpgquest:money_quest", 1, 0, 70L, 0, null));
        rewardPayer.answer = new QuestRewardReceipt(QuestRewardReceipt.Status.CREDITED, 70L, 70L, 1);

        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitCalls(1);
        server.getScheduler().performTicks(20);

        assertEquals(xpBefore, player.getTotalExperience(), "aucune récompense non monétaire rejouée");
    }

    /**
     * Pompe les ticks jusqu'à voir passer le message attendu. Le crédit est asynchrone et son
     * message est posté sur le thread principal : attendre un nombre FIXE de ticks rendrait le test
     * vert au calme et rouge sous charge.
     */
    private String awaitMessageContaining(PlayerMock player, String needle) {
        for (int i = 0; i < 200; i++) {
            String message;
            while ((message = player.nextMessage()) != null) {
                if (message.contains(needle)) {
                    return message;
                }
            }
            server.getScheduler().performTicks(2);
            sleepBriefly();
        }
        throw new AssertionError("aucun message contenant « " + needle + " » après 200 tours de boucle");
    }

    /** Attend qu'au moins {@code expected} demandes de paiement soient parvenues au payeur. */
    private void awaitCalls(int expected) {
        for (int i = 0; i < 200 && rewardPayer.calls.size() < expected; i++) {
            server.getScheduler().performTicks(2);
            sleepBriefly();
        }
        assertTrue(rewardPayer.calls.size() >= expected,
                () -> "attendu au moins " + expected + " demandes, vu " + rewardPayer.calls.size());
    }

    /**
     * {@code performTicks} ne consomme AUCUN temps d'horloge : sans ce court sommeil, une boucle
     * d'attente peut s'épuiser entièrement avant la fin d'une écriture asynchrone en base.
     */
    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NamespacedKey writeMoneyQuest(String fileName, String id, int amount, boolean repeatable) throws Exception {
        Files.writeString(questsDir.resolve(fileName), """
                id: %s
                title: "Titre Monnaie"
                description: "Description"
                category: test
                repeatable: %s
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: MONEY
                    amount: %d
                """.formatted(id, repeatable, amount));
        engine.reloadQuestDefinitions();
        return NamespacedKey.fromString(id);
    }

    /** Payeur enregistreur : retient ce qui lui a été demandé et répond ce qu'on lui dit de répondre. */
    private static final class RecordingRewardPayer implements QuestRewardPayer {

        record Call(java.util.UUID playerId, String grantId) {
        }

        private final List<Call> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<QuestRewardDue> pending = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile QuestRewardReceipt answer =
                new QuestRewardReceipt(QuestRewardReceipt.Status.CREDITED, 0L, 0L, 1);

        @Override
        public java.util.concurrent.CompletableFuture<QuestRewardReceipt> payQuestReward(
                java.util.UUID playerId, String grantId) {
            calls.add(new Call(playerId, grantId));
            return java.util.concurrent.CompletableFuture.completedFuture(answer);
        }

        @Override
        public java.util.concurrent.CompletableFuture<List<QuestRewardDue>> pendingQuestRewards(
                java.util.UUID playerId, int limit) {
            return java.util.concurrent.CompletableFuture.completedFuture(List.copyOf(pending));
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> recordQuestRewardFailure(String grantId, String error) {
            failures.add(grantId + ": " + error);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
}
