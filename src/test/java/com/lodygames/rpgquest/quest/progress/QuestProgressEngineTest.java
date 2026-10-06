package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
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
        assertTrue(actionBar.contains("Tuer ZOMBIE"), () -> "la description de l'objectif doit apparaître : " + actionBar);
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

    @Test
    void acceptingAQuestNeverSendsAChatMessage() throws Exception {
        PlayerMock player = addPlayer();

        AcceptOutcome outcome = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AcceptOutcome.Result.ACCEPTED, outcome.result());
        assertNull(player.nextMessage(), "le démarrage d'une quête doit passer par un Title, jamais le chat");
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
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("title: \"Titre\"\n");
        yaml.append("description: \"Description\"\n");
        yaml.append("category: test\n");
        yaml.append("repeatable: ").append(repeatable).append('\n');
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
