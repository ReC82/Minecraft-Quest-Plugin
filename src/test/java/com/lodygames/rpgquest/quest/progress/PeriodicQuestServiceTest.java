package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.PeriodicQuestConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository;
import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository.Assignment;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardDue;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.economy.QuestRewardReceipt;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.periodic.PeriodicQuestService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #15 — attribution, démarrage, renouvellement et expiration des quêtes quotidiennes et
 * hebdomadaires, contre le vrai moteur de quêtes et une vraie base SQLite. L'horloge est simulée
 * pour franchir les réinitialisations (UTC, reset 04:00, semaine le lundi).
 */
class PeriodicQuestServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey DAILY_ZOMBIE = new NamespacedKey("rpgquest", "daily_zombie");
    private static final NamespacedKey DAILY_SKELETON = new NamespacedKey("rpgquest", "daily_skeleton");
    private static final NamespacedKey WEEKLY_CREEPER = new NamespacedKey("rpgquest", "weekly_creeper");
    private static final NamespacedKey ORDINARY_SPIDER = new NamespacedKey("rpgquest", "ordinary_spider");
    private static final String DAILY_ANNOUNCEMENT = "✦ Nouvelles quêtes quotidiennes disponibles : ouvre ton journal.";
    private static final String WEEKLY_ANNOUNCEMENT = "✦ Nouvelles quêtes hebdomadaires disponibles : ouvre ton journal.";
    /** Samedi 17/10/2026 12:00 UTC. */
    private static final Instant SATURDAY_NOON = Instant.parse("2026-10-17T12:00:00Z");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private YamlQuestEngine questEngine;
    private DatabaseManager database;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variables;
    private PeriodicQuestAssignmentRepository assignments;
    private QuestProgressEngine engine;
    private PeriodicQuestService service;
    private final AtomicReference<Instant> now = new AtomicReference<>(SATURDAY_NOON);
    private final AtomicReference<PeriodicQuestConfig> config = new AtomicReference<>(
            new PeriodicQuestConfig(true, ZoneId.of("UTC"), LocalTime.of(4, 0), DayOfWeek.MONDAY, 2, 1));

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        writePeriodicQuest(questsDir, DAILY_ZOMBIE, "daily", "ZOMBIE");
        writePeriodicQuest(questsDir, DAILY_SKELETON, "daily", "SKELETON");
        writePeriodicQuest(questsDir, WEEKLY_CREEPER, "weekly", "CREEPER");
        Files.writeString(questsDir.resolve("ordinary_spider.yml"), """
                id: rpgquest:ordinary_spider
                title: "Araignées"
                description: "Description"
                category: hunting
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: SPIDER
                        amount: 2
                """);
        this.plugin = plugin;
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        progressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        variables = new PlayerVariableRepository(database);
        assignments = new PeriodicQuestAssignmentRepository(database);
        QuestMessagesService messages = new QuestMessagesService(plugin);
        messages.start();
        engine = new QuestProgressEngine(plugin, questEngine, progressRepository, variables, messages,
                new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database)),
                new NoopRewardPayer());
        engine.start();
        service = newService();
    }

    private PeriodicQuestService newService() {
        return new PeriodicQuestService(plugin, questEngine, engine, assignments, variables, config::get, now::get,
                Runnable::run, plugin.getSLF4JLogger());
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void aPeriodicTemplateIsNeverAvailableOutsideItsAssignment() throws Exception {
        PlayerMock player = addPlayer();

        assertEquals(AcceptOutcome.Result.NOT_ASSIGNED, await(engine.accept(player, DAILY_ZOMBIE)).result());
        assertEquals(AcceptOutcome.Result.NOT_ASSIGNED, await(engine.accept(player, DAILY_ZOMBIE, true)).result(),
                "même le raccourci admin « force » ne l'attribue pas");
        assertEquals(QuestProgressEngine.Availability.Status.NOT_ASSIGNED,
                await(engine.availability(player.getUniqueId(), DAILY_ZOMBIE, false)).status());
        assertEquals(CompleteOutcome.NOT_ACTIVE, await(engine.forceComplete(player, DAILY_ZOMBIE)),
                "aucune remise forcée hors attribution : pas de récompense hors période");
        assertTrue(progressRepository.findAll(player.getUniqueId()).get().isEmpty());
    }

    @Test
    void openingTheJournalAssignsAndStartsTheQuestsOfThePeriodOnce() throws Exception {
        PlayerMock player = addPlayer();

        await(service.unlockAndRefresh(player));
        await(service.unlockAndRefresh(player));
        await(service.refresh(player));

        assertEquals(Optional.of("1"), await(variables.get(player.getUniqueId(), PeriodicQuestService.UNLOCKED_KEY)));
        List<Assignment> daily = await(assignments.findPeriod(player.getUniqueId(), "daily", "2026-10-17"));
        List<Assignment> weekly = await(assignments.findPeriod(player.getUniqueId(), "weekly", "2026-10-12"));
        assertEquals(2, daily.size(), "deux quotidiennes, jamais quatre après trois ouvertures");
        assertEquals(1, weekly.size());
        assertTrue(daily.stream().allMatch(a -> PeriodicQuestAssignmentRepository.STARTED.equals(a.status())));
        assertEquals(QuestState.ACTIVE, state(player, DAILY_ZOMBIE));
        assertEquals(QuestState.ACTIVE, state(player, DAILY_SKELETON));
        assertEquals(QuestState.ACTIVE, state(player, WEEKLY_CREEPER));
    }

    @Test
    void aQuestCompletedTodayIsNotRestartedBeforeTheReset() throws Exception {
        PlayerMock player = addPlayer();
        await(service.unlockAndRefresh(player));

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.COMPLETED, state(player, DAILY_ZOMBIE));

        now.set(SATURDAY_NOON.plusSeconds(3600 * 15)); // dimanche 03:00 : même jour de jeu
        await(service.refresh(player));
        assertEquals(QuestState.COMPLETED, state(player, DAILY_ZOMBIE), "une seule récompense par jour");
    }

    @Test
    void theResetExpiresUnfinishedQuestsAndRenewsTheDayWithFreshCounters() throws Exception {
        PlayerMock player = addPlayer();
        await(service.unlockAndRefresh(player));
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // zombie terminée samedi
        engine.handleKillEntity(player, EntityType.SKELETON); // squelette 1/2, non terminée

        now.set(Instant.parse("2026-10-18T04:00:00Z")); // dimanche, reset quotidien
        await(service.refresh(player));

        List<Assignment> saturday = await(assignments.findPeriod(player.getUniqueId(), "daily", "2026-10-17"));
        assertTrue(saturday.stream().allMatch(a -> PeriodicQuestAssignmentRepository.CLOSED.equals(a.status())));
        List<Assignment> sunday = await(assignments.findPeriod(player.getUniqueId(), "daily", "2026-10-18"));
        assertEquals(2, sunday.size());
        // Réserve de 2 pour 2 places : les deux reviennent, redémarrées à zéro.
        assertEquals(QuestState.ACTIVE, state(player, DAILY_ZOMBIE));
        assertEquals(QuestState.ACTIVE, state(player, DAILY_SKELETON));

        engine.handleKillEntity(player, EntityType.SKELETON);
        assertEquals(QuestState.ACTIVE, state(player, DAILY_SKELETON),
                "le 1/2 de samedi a expiré avec la quête : un seul kill ne suffit pas dimanche");

        // Redémarrage serveur : les compteurs de samedi ne reviennent pas non plus.
        engine.unloadForPlayer(player.getUniqueId());
        await(engine.loadForPlayer(player.getUniqueId()));
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.ACTIVE, state(player, DAILY_ZOMBIE), "compteur 2/2 de samedi effacé au redémarrage");
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.COMPLETED, state(player, DAILY_ZOMBIE));

        // La semaine n'a pas changé : l'hebdomadaire est intacte.
        assertEquals(PeriodicQuestAssignmentRepository.STARTED,
                await(assignments.findPeriod(player.getUniqueId(), "weekly", "2026-10-12")).get(0).status());
    }

    @Test
    void anUnfinishedQuestFromAnEarlierPeriodIsAbandonedNotRewarded() throws Exception {
        PlayerMock player = addPlayer();
        await(service.unlockAndRefresh(player));
        config.set(new PeriodicQuestConfig(true, ZoneId.of("UTC"), LocalTime.of(4, 0), DayOfWeek.MONDAY, 0, 1));

        now.set(Instant.parse("2026-10-19T05:00:00Z")); // lundi : reset quotidien ET hebdomadaire
        await(service.refresh(player));

        assertEquals(QuestState.ABANDONED, state(player, DAILY_ZOMBIE), "expirée sans récompense");
        assertEquals(QuestState.ABANDONED, state(player, DAILY_SKELETON));
        assertTrue(await(assignments.findPeriod(player.getUniqueId(), "daily", "2026-10-19")).isEmpty(),
                "0 quotidienne configurée : aucune attribution");
        assertEquals(QuestState.ACTIVE, state(player, WEEKLY_CREEPER), "nouvelle semaine, quête redémarrée");
        assertEquals(1, await(assignments.findPeriod(player.getUniqueId(), "weekly", "2026-10-19")).size());
    }

    @Test
    void nothingIsAssignedWhenTheFeatureIsDisabled() throws Exception {
        PlayerMock player = addPlayer();
        config.set(new PeriodicQuestConfig(false, ZoneId.of("UTC"), LocalTime.of(4, 0), DayOfWeek.MONDAY, 2, 1));

        await(service.unlockAndRefresh(player));

        assertTrue(await(assignments.findOpen(player.getUniqueId())).isEmpty());
        assertTrue(await(variables.get(player.getUniqueId(), PeriodicQuestService.UNLOCKED_KEY)).isEmpty());
    }

    @Test
    void theViewReadsTheCurrentPeriodAndItsNextReset() throws Exception {
        PlayerMock player = addPlayer();
        await(service.unlockAndRefresh(player));

        PeriodicQuestService.PeriodView view = await(service.view(player.getUniqueId(), QuestPeriod.DAILY));
        assertEquals("2026-10-17", view.periodKey());
        assertEquals(Instant.parse("2026-10-18T04:00:00Z"), view.nextReset());
        assertEquals(2, view.assignments().size());
    }

    // ---- Annonces de l'attribution automatique (retour propriétaire du 23/10) ----------------

    @Test
    void theAutomaticAssignmentAnnouncesOneChatLinePerCategoryWithoutTitleNorObjectives() throws Exception {
        TitleRecordingPlayer player = addTitleRecordingPlayer();

        await(service.unlockAndRefresh(player));
        server.getScheduler().performOneTick();

        assertEquals(List.of(DAILY_ANNOUNCEMENT, WEEKLY_ANNOUNCEMENT), drainMessages(player),
                "une seule ligne par catégorie, aucun détail d'objectif par quête");
        assertTrue(player.titles.isEmpty(), "aucun titre « Quête commencée » pour une attribution automatique");
        assertEquals(QuestState.ACTIVE, state(player, DAILY_ZOMBIE));
        assertEquals(QuestState.ACTIVE, state(player, WEEKLY_CREEPER));
    }

    @Test
    void reconnectingOrRestartingWithinTheSamePeriodAnnouncesNothing() throws Exception {
        TitleRecordingPlayer player = addTitleRecordingPlayer();
        await(service.unlockAndRefresh(player));
        server.getScheduler().performOneTick();
        drainMessages(player);

        now.set(SATURDAY_NOON.plusSeconds(3600)); // même jour, même semaine
        await(service.refresh(player)); // reconnexion
        await(newService().refresh(player)); // redémarrage serveur : la mémoire est en base
        server.getScheduler().performOneTick();

        assertTrue(drainMessages(player).isEmpty(), "rien de neuf : aucune annonce");
        assertTrue(player.titles.isEmpty());
    }

    @Test
    void aDailyRenewalAnnouncesOnlyTheDailyCategoryOnce() throws Exception {
        TitleRecordingPlayer player = addTitleRecordingPlayer();
        await(service.unlockAndRefresh(player));
        server.getScheduler().performOneTick();
        drainMessages(player);

        now.set(Instant.parse("2026-10-18T04:00:00Z")); // dimanche : reset quotidien seulement
        // Renouvellement en ligne (cadence) et connexion qui se croisent : une seule annonce.
        CompletableFuture<Void> first = service.refresh(player);
        CompletableFuture<Void> second = service.refresh(player);
        await(first);
        await(second);
        server.getScheduler().performOneTick();

        List<String> messages = drainMessages(player);
        assertEquals(List.of(DAILY_ANNOUNCEMENT),
                messages.stream().filter(m -> m.startsWith("✦")).toList(),
                "la semaine n'a pas changé : pas d'annonce hebdomadaire ; une seule quotidienne");
        assertTrue(messages.stream().allMatch(m -> m.startsWith("✦") || m.startsWith("⌛")),
                "seuls l'annonce et l'éventuel message d'expiration : " + messages);
        assertTrue(player.titles.isEmpty());
        assertEquals(Optional.of("2026-10-18"),
                await(variables.get(player.getUniqueId(), PeriodicQuestService.announcedVariable(QuestPeriod.DAILY))));
    }

    @Test
    void theExpiryMessageOfTheEndedPeriodIsKept() throws Exception {
        TitleRecordingPlayer player = addTitleRecordingPlayer();
        await(service.unlockAndRefresh(player));
        server.getScheduler().performOneTick();
        drainMessages(player);

        now.set(Instant.parse("2026-10-18T04:00:00Z"));
        await(service.refresh(player));
        server.getScheduler().performOneTick();

        assertEquals(2, drainMessages(player).stream().filter(m -> m.startsWith("⌛ Quête expirée")).count());
    }

    @Test
    void playersServedBeforeTheMemoryAreNotReannouncedTheirCurrentPeriod() throws Exception {
        TitleRecordingPlayer player = addTitleRecordingPlayer();
        await(service.unlockAndRefresh(player));
        server.getScheduler().performOneTick();
        drainMessages(player);
        // État d'un joueur servi par la version précédente : attributions démarrées, rien mémorisé.
        await(variables.set(player.getUniqueId(), PeriodicQuestService.announcedVariable(QuestPeriod.DAILY), ""));
        await(variables.set(player.getUniqueId(), PeriodicQuestService.announcedVariable(QuestPeriod.WEEKLY), ""));

        await(service.refresh(player));

        assertTrue(drainMessages(player).isEmpty(), "rien n'a été démarré maintenant : rien à annoncer");
        assertEquals(Optional.of("2026-10-17"),
                await(variables.get(player.getUniqueId(), PeriodicQuestService.announcedVariable(QuestPeriod.DAILY))));
    }

    @Test
    void anOrdinaryQuestStillShowsItsStartTitleAndObjectives() throws Exception {
        TitleRecordingPlayer player = addTitleRecordingPlayer();

        assertEquals(AcceptOutcome.Result.ACCEPTED, await(engine.accept(player, ORDINARY_SPIDER)).result());
        server.getScheduler().performOneTick();

        assertEquals(1, player.titles.size(), "titre « Quête commencée » conservé pour une quête ordinaire");
        assertTrue(drainMessages(player).size() >= 2, "en-tête + objectifs toujours annoncés dans le chat");
    }

    // ---- Outils ----------------------------------------------------------------------------

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        await(profileRepository.findOrCreate(player.getUniqueId(), player.getName()));
        return player;
    }

    /** Joueur qui retient les Title Adventure reçus : MockBukkit ne les expose pas. */
    private static final class TitleRecordingPlayer extends PlayerMock {
        private final List<Title> titles = new CopyOnWriteArrayList<>();

        TitleRecordingPlayer(ServerMock server) {
            super(server, "Periodic", UUID.randomUUID());
        }

        @Override
        public void showTitle(Title title) {
            titles.add(title);
        }
    }

    private TitleRecordingPlayer addTitleRecordingPlayer() throws Exception {
        TitleRecordingPlayer player = new TitleRecordingPlayer(server);
        server.addPlayer(player);
        await(profileRepository.findOrCreate(player.getUniqueId(), player.getName()));
        server.getScheduler().performOneTick();
        drainMessages(player); // accueil du plugin à la connexion (Rune de rappel…), hors sujet ici
        return player;
    }

    private static List<String> drainMessages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return messages;
    }

    private QuestState state(PlayerMock player, NamespacedKey questId) throws Exception {
        return await(progressRepository.find(player.getUniqueId(), questId)).orElseThrow().state();
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void writePeriodicQuest(Path dir, NamespacedKey id, String period, String entity) throws Exception {
        Files.writeString(dir.resolve(id.getKey() + ".yml"), """
                id: %s
                title: "Titre"
                description: "Description"
                category: %s
                period: %s
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: %s
                        amount: 2
                rewards:
                  - type: EXPERIENCE
                    amount: 10
                """.formatted(id, period, period, entity));
    }

    private static final class NoopRewardPayer implements QuestRewardPayer {
        @Override
        public CompletableFuture<QuestRewardReceipt> payQuestReward(UUID playerId, String grantId) {
            return CompletableFuture.completedFuture(new QuestRewardReceipt(QuestRewardReceipt.Status.CREDITED, 0L, 0L, 1));
        }

        @Override
        public CompletableFuture<List<QuestRewardDue>> pendingQuestRewards(UUID playerId, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Void> recordQuestRewardFailure(String grantId, String error) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
