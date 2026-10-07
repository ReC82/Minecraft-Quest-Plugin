package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #123 — remise réelle d'objets à un PNJ, avec dépôts partiels persistants. Couvre les cas
 * imposés par le ticket : dépôt 0, partiel, exact, supérieur au besoin, plusieurs piles, plusieurs
 * objectifs, plusieurs matériaux en une interaction, mauvais PNJ, double tentative et appel
 * concurrent, objectif déjà terminé, mort, reconnexion et redémarrage.
 *
 * <p>Tout passe par le vrai {@link QuestProgressEngine} et une vraie base SQLite : la persistance
 * n'est jamais simulée, puisque c'est précisément la garantie fonctionnelle du ticket.</p>
 */
class QuestItemDeliveryTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey LEATHER_QUEST = new NamespacedKey("rpgquest", "leather_quest");
    private static final NamespacedKey SECOND_QUEST = new NamespacedKey("rpgquest", "second_quest");
    private static final String STEP = "deliver_step";
    private static final String NPC = "guard";
    private static final String OTHER_NPC = "libraire";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private YamlQuestEngine questEngine;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private QuestMessagesService messagesService;
    private NpcIdentityService npcIdentityService;
    private QuestProgressEngine engine;
    private Path questsDir;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        writeDeliverQuest("leather_quest.yml", LEATHER_QUEST, NPC, Map.of(Material.LEATHER, 4), false);

        progressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);
        messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));

        engine = newEngine();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    // ---- Outils --------------------------------------------------------

    /**
     * Quête à une étape dont chaque entrée est un objectif de remise vers {@code npc}, dans l'ordre
     * d'itération de {@code items}. {@code withExtraObjective} ajoute un objectif {@code
     * KILL_ENTITY} non lié à la remise : l'étape ne peut alors pas se terminer par les seules
     * remises, ce qui permet d'observer l'état « tout est déjà remis » sans que la quête ne se
     * termine et ne quitte les quêtes actives.
     */
    private void writeDeliverQuest(String fileName, NamespacedKey id, String npc, Map<Material, Integer> items,
                                    boolean withExtraObjective) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("title: \"Titre\"\ndescription: \"Description\"\ncategory: test\n");
        yaml.append("steps:\n  - id: ").append(STEP).append("\n    objectives:\n");
        items.forEach((material, amount) -> yaml
                .append("      - type: DELIVER_ITEM_TO_NPC\n")
                .append("        npc: ").append(npc).append('\n')
                .append("        material: ").append(material.name()).append('\n')
                .append("        amount: ").append(amount).append('\n'));
        if (withExtraObjective) {
            yaml.append("      - type: KILL_ENTITY\n        entity: ZOMBIE\n        amount: 1\n");
        }
        Files.writeString(questsDir.resolve(fileName), yaml.toString());
    }

    /** Recharge les définitions dans un moteur NEUF — simule aussi un redémarrage du serveur. */
    private QuestProgressEngine newEngine() {
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        QuestProgressEngine created = new QuestProgressEngine(plugin, questEngine, progressRepository,
                variableRepository, messagesService, npcIdentityService, QuestRewardPayer.unavailable());
        created.start();
        return created;
    }

    private void restartEngine() {
        engine.stop();
        engine = newEngine();
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        player.getInventory().clear();
        return player;
    }

    private PlayerMock playerWithActiveQuest(NamespacedKey questId) throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private int countIn(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private int storedCounter(PlayerMock player, NamespacedKey questId, int objectiveIndex) throws Exception {
        Map<Integer, Integer> counters = progressRepository
                .findObjectiveProgress(player.getUniqueId(), questId, STEP)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return counters.getOrDefault(objectiveIndex, 0);
    }

    /** Attend que la valeur persistée atteigne {@code expected} : l'écriture est asynchrone. */
    private void awaitStoredCounter(PlayerMock player, NamespacedKey questId, int objectiveIndex, int expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        int actual = storedCounter(player, questId, objectiveIndex);
        while (actual != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(15);
            actual = storedCounter(player, questId, objectiveIndex);
        }
        assertEquals(expected, actual, "progression de remise jamais persistée à la valeur attendue");
    }

    private Map<Material, DeliveryLine> byMaterial(DeliveryOutcome outcome) {
        Map<Material, DeliveryLine> map = new HashMap<>();
        outcome.lines().forEach(line -> map.put(line.material(), line));
        return map;
    }

    private List<String> drainMessages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            out.add(next);
        }
        return out;
    }

    // ---- Posséder ne suffit jamais -------------------------------------

    @Test
    void holdingOrCollectingTheItemsNeverCompletesTheObjective() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 64));

        // Ni ramassage, ni fabrication ne doivent faire avancer une remise : c'est exactement ce
        // qui distingue DELIVER_ITEM_TO_NPC de COLLECT_ITEM.
        engine.handleCollectItem(player, Material.LEATHER);
        engine.handleCraftItem(player, Material.LEATHER);

        assertEquals(0, engine.pendingDeliveries(player.getUniqueId(), NPC).get(0).delivered());
        assertEquals(QuestState.ACTIVE, engine.stateOf(player.getUniqueId(), LEATHER_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(64, countIn(player, Material.LEATHER), "rien ne doit avoir été consommé");
    }

    // ---- Dépôt 0 / rien d'utile ----------------------------------------

    @Test
    void deliveringWithAnEmptyInventoryChangesNothing() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        drainMessages(player);

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(DeliveryOutcome.Status.NOTHING_USEFUL, outcome.status());
        assertEquals(0, outcome.lines().get(0).delivered());
        assertFalse(outcome.allComplete());
        assertTrue(drainMessages(player).stream().anyMatch(m -> m.contains("rien d'utile")));
        assertEquals(0, storedCounter(player, LEATHER_QUEST, 0), "aucune écriture pour une remise vide");
    }

    // ---- Dépôt partiel, puis le reliquat -------------------------------

    @Test
    void aPartialDeliveryProgressesImmediatelyAndConsumesExactlyWhatWasGiven() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));
        drainMessages(player);

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertEquals(2, outcome.lines().get(0).delivered());
        assertEquals(2, outcome.lines().get(0).justNow());
        assertEquals(2, outcome.lines().get(0).remaining());
        assertFalse(outcome.allComplete());
        assertEquals(0, countIn(player, Material.LEATHER), "les 2 cuirs remis sont consommés");
        awaitStoredCounter(player, LEATHER_QUEST, 0, 2);

        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Matériaux remis")), () -> "obtenu : " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("manque encore")), () -> "obtenu : " + messages);
    }

    @Test
    void theRemainderCanBeDeliveredLaterAndCompletesTheQuest() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));
        engine.deliverTo(player, NPC);
        awaitStoredCounter(player, LEATHER_QUEST, 0, 2);
        drainMessages(player);

        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));
        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertTrue(outcome.allComplete());
        assertEquals(4, outcome.lines().get(0).delivered());
        assertTrue(drainMessages(player).stream().anyMatch(m -> m.contains("remis tout ce qui était demandé")));
        assertEquals(QuestState.COMPLETED, engine.stateOf(player.getUniqueId(), LEATHER_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    // ---- Quantité exacte et quantité supérieure au besoin --------------

    @Test
    void anExactDeliveryCompletesTheObjectiveInOneGo() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 4));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertTrue(outcome.allComplete());
        assertEquals(4, outcome.lines().get(0).justNow());
        assertEquals(0, countIn(player, Material.LEATHER));
    }

    @Test
    void deliveringMoreThanNeededNeverConsumesTheSurplus() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 64));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(4, outcome.lines().get(0).delivered(), "jamais plus que la quantité demandée");
        assertEquals(60, countIn(player, Material.LEATHER), "le surplus reste au joueur");
        awaitStoredCounter(player, LEATHER_QUEST, 0, 4);
    }

    @Test
    void anAlreadyCompletedDeliveryNeverConsumesAnythingMore() throws Exception {
        // Objectif de remise + un objectif de chasse : l'étape reste en cours, donc la quête reste
        // active alors que toutes ses remises sont déjà satisfaites.
        writeDeliverQuest("second_quest.yml", SECOND_QUEST, NPC, Map.of(Material.LEATHER, 4), true);
        restartEngine();
        PlayerMock player = playerWithActiveQuest(SECOND_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 4));
        engine.deliverTo(player, NPC);
        drainMessages(player);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 10));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(DeliveryOutcome.Status.ALREADY_COMPLETE, outcome.status());
        assertTrue(outcome.allComplete());
        assertEquals(10, countIn(player, Material.LEATHER), "rien de plus n'est consommé");
        assertTrue(drainMessages(player).stream().anyMatch(m -> m.contains("déjà remis")));
    }

    // ---- Plusieurs piles -----------------------------------------------

    @Test
    void itemsSpreadOverSeveralStacksAreAddedUp() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().setItem(0, new ItemStack(Material.LEATHER, 1));
        player.getInventory().setItem(5, new ItemStack(Material.LEATHER, 1));
        player.getInventory().setItem(9, new ItemStack(Material.LEATHER, 2));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertTrue(outcome.allComplete(), "1 + 1 + 2 = 4 : remise complète malgré trois piles");
        assertEquals(0, countIn(player, Material.LEATHER));
    }

    @Test
    void onlyTheNeededPartIsTakenAcrossSeveralStacks() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().setItem(0, new ItemStack(Material.LEATHER, 3));
        player.getInventory().setItem(1, new ItemStack(Material.LEATHER, 3));

        engine.deliverTo(player, NPC);

        assertEquals(2, countIn(player, Material.LEATHER), "6 possédés, 4 demandés : 2 doivent rester");
    }

    // ---- Plusieurs objectifs et plusieurs matériaux en une interaction --

    @Test
    void oneSingleInteractionDeliversEveryUsefulMaterialAtOnce() throws Exception {
        LinkedHashMap<Material, Integer> items = new LinkedHashMap<>();
        items.put(Material.STICK, 1);
        items.put(Material.COBBLESTONE, 2);
        items.put(Material.LEATHER, 4);
        items.put(Material.WHEAT_SEEDS, 3);
        writeDeliverQuest("second_quest.yml", SECOND_QUEST, NPC, items, false);
        restartEngine();

        PlayerMock player = playerWithActiveQuest(SECOND_QUEST);
        player.getInventory().addItem(new ItemStack(Material.STICK, 1));
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 1));
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));
        player.getInventory().addItem(new ItemStack(Material.WHEAT_SEEDS, 3));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(DeliveryOutcome.Status.DELIVERED, outcome.status());
        assertFalse(outcome.allComplete(), "il manque encore 1 pierre et 2 cuirs");
        assertEquals(4, outcome.justDelivered().size(), "les quatre matériaux en UNE interaction");
        Map<Material, DeliveryLine> lines = byMaterial(outcome);
        assertEquals(1, lines.get(Material.STICK).delivered());
        assertEquals(1, lines.get(Material.COBBLESTONE).delivered());
        assertEquals(2, lines.get(Material.LEATHER).delivered());
        assertEquals(3, lines.get(Material.WHEAT_SEEDS).delivered());
        assertEquals(1, lines.get(Material.COBBLESTONE).remaining());
        assertEquals(2, lines.get(Material.LEATHER).remaining());
        assertEquals(0, countIn(player, Material.LEATHER), "tout l'utile a été consommé");
        assertEquals(2, outcome.stillMissing().size());

        // Le reliquat exact complète les deux compteurs restants en une seule remise de plus.
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 1));
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));
        assertTrue(engine.deliverTo(player, NPC).allComplete());
    }

    @Test
    void twoQuestsAskingTheSameMaterialShareWhatThePlayerHasWithoutDuplicating() throws Exception {
        writeDeliverQuest("second_quest.yml", SECOND_QUEST, NPC, Map.of(Material.LEATHER, 3), false);
        restartEngine();

        PlayerMock player = addPlayer();
        engine.accept(player, LEATHER_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, SECOND_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 5));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(5, outcome.lines().stream().mapToInt(DeliveryLine::justNow).sum(),
                "les 5 cuirs sont répartis entre les deux quêtes, jamais comptés deux fois");
        assertEquals(0, countIn(player, Material.LEATHER));
    }

    // ---- Mauvais PNJ ----------------------------------------------------

    @Test
    void theWrongNpcCanNeverAcceptADelivery() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 4));
        drainMessages(player);

        DeliveryOutcome outcome = engine.deliverTo(player, OTHER_NPC);

        assertEquals(DeliveryOutcome.Status.NO_OBJECTIVE, outcome.status());
        assertTrue(outcome.lines().isEmpty());
        assertEquals(4, countIn(player, Material.LEATHER), "aucun objet retiré par le mauvais PNJ");
        assertEquals(0, storedCounter(player, LEATHER_QUEST, 0));
        assertTrue(engine.pendingDeliveries(player.getUniqueId(), OTHER_NPC).isEmpty());
        assertFalse(engine.hasPendingDelivery(player.getUniqueId(), OTHER_NPC));
    }

    @Test
    void anUnknownOrMissingNpcIdNeverDeliversAnything() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 4));

        assertEquals(DeliveryOutcome.Status.NO_OBJECTIVE, engine.deliverTo(player, null).status());
        assertEquals(DeliveryOutcome.Status.NO_OBJECTIVE, engine.deliverTo(player, "  ").status());
        assertEquals(DeliveryOutcome.Status.NO_OBJECTIVE, engine.deliverTo(player, "inconnu").status());
        assertEquals(4, countIn(player, Material.LEATHER));
    }

    @Test
    void aPlayerWithoutTheQuestNeverDeliversAnything() throws Exception {
        PlayerMock player = addPlayer();
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 4));

        assertEquals(DeliveryOutcome.Status.NO_OBJECTIVE, engine.deliverTo(player, NPC).status());
        assertEquals(4, countIn(player, Material.LEATHER));
    }

    // ---- Double clic / concurrence --------------------------------------

    @Test
    void aReentrantDeliveryIsRefusedWithoutTakingAnything() throws Exception {
        // Remise + chasse : l'étape ne se termine pas, donc la quête reste active pendant toute
        // l'opération et le seul refus possible est bien la garde anti-concurrence.
        writeDeliverQuest("second_quest.yml", SECOND_QUEST, NPC, Map.of(Material.LEATHER, 4), true);
        restartEngine();
        PlayerMock player = playerWithActiveQuest(SECOND_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 64));

        // Appel RÉENTRANT — le cas réellement dangereux : une seconde remise déclenchée pendant le
        // traitement de la première (clic répété traité dans le même tick, cascade d'événements).
        List<DeliveryOutcome> reentrant = new ArrayList<>();
        engine.onProgressChanged(playerId -> {
            if (reentrant.isEmpty()) {
                reentrant.add(engine.deliverTo(player, NPC));
            }
        });

        engine.deliverTo(player, NPC);

        assertEquals(1, reentrant.size(), "l'observateur de progression doit bien avoir été appelé");
        assertEquals(DeliveryOutcome.Status.BUSY, reentrant.get(0).status(),
                "un appel concurrent est refusé, jamais exécuté une seconde fois");
        assertEquals(60, countIn(player, Material.LEATHER), "exactement 4 cuirs retirés en tout");
        awaitStoredCounter(player, SECOND_QUEST, 0, 4);
    }

    @Test
    void clickingTwiceInARowNeverDoublesTheProgressBeyondWhatWasGiven() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));

        engine.deliverTo(player, NPC);
        DeliveryOutcome second = engine.deliverTo(player, NPC);

        assertEquals(DeliveryOutcome.Status.NOTHING_USEFUL, second.status(),
                "le second clic ne trouve plus rien : l'utile a déjà quitté l'inventaire");
        assertEquals(2, engine.pendingDeliveries(player.getUniqueId(), NPC).get(0).delivered(),
                "jamais 4 à partir de 2 cuirs réellement remis");
        awaitStoredCounter(player, LEATHER_QUEST, 0, 2);
    }

    // ---- Mort / reconnexion / redémarrage -------------------------------

    @Test
    void progressSurvivesDeathAndReconnection() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 2));
        engine.deliverTo(player, NPC);
        awaitStoredCounter(player, LEATHER_QUEST, 0, 2);

        // Mort réelle (événement diffusé à tous les écouteurs du plugin), puis perte d'inventaire.
        server.getPluginManager().callEvent(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0,
                Component.text("mort"), false));
        player.getInventory().clear();
        // Déconnexion puis reconnexion : l'état en mémoire est purgé, puis relu depuis la base.
        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<DeliveryLine> lines = engine.pendingDeliveries(player.getUniqueId(), NPC);
        assertEquals(1, lines.size());
        assertEquals(2, lines.get(0).delivered(), "les 2 cuirs remis restent acquis après la mort");
        assertEquals(2, lines.get(0).remaining());
    }

    @Test
    void progressSurvivesAServerRestartAndTheRemainderStillCompletesIt() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 3));
        engine.deliverTo(player, NPC);
        awaitStoredCounter(player, LEATHER_QUEST, 0, 3);

        // Redémarrage : moteur entièrement neuf, rien en mémoire, mêmes fichiers et même base.
        restartEngine();
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(3, engine.pendingDeliveries(player.getUniqueId(), NPC).get(0).delivered());

        player.getInventory().addItem(new ItemStack(Material.LEATHER, 1));
        assertTrue(engine.deliverTo(player, NPC).allComplete());
    }

    // ---- Objets personnalisés jamais consommés --------------------------

    @Test
    void aCustomRpgquestItemIsNeverConsumedByADelivery() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        ItemStack custom = new ItemStack(Material.LEATHER, 1);
        var meta = custom.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey("rpgquest", "custom_item_id"),
                PersistentDataType.STRING, "rpgquest:relique");
        custom.setItemMeta(meta);
        player.getInventory().setItem(0, custom);
        player.getInventory().setItem(1, new ItemStack(Material.LEATHER, 2));

        DeliveryOutcome outcome = engine.deliverTo(player, NPC);

        assertEquals(2, outcome.lines().get(0).justNow(), "seul le cuir ordinaire est remis");
        ItemStack kept = player.getInventory().getItem(0);
        assertNotNull(kept, "l'objet personnalisé doit rester en place");
        assertEquals(Material.LEATHER, kept.getType());
        assertEquals(1, kept.getAmount());
        assertTrue(kept.getItemMeta().getPersistentDataContainer().has(
                        new NamespacedKey("rpgquest", "custom_item_id"), PersistentDataType.STRING),
                "c'est bien l'exemplaire personnalisé qui a été épargné");
    }

    // ---- Vue de l'état --------------------------------------------------

    @Test
    void theStatusViewReportsDeliveredAndRemainingWithoutChangingAnything() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);
        player.getInventory().addItem(new ItemStack(Material.LEATHER, 1));
        engine.deliverTo(player, NPC);

        List<DeliveryLine> lines = engine.pendingDeliveries(player.getUniqueId(), NPC);
        assertEquals(1, lines.get(0).delivered());
        assertEquals(3, lines.get(0).remaining());
        assertFalse(lines.get(0).complete());
        assertEquals(0, lines.get(0).justNow(), "une simple consultation n'est pas une remise");
        assertTrue(engine.hasPendingDelivery(player.getUniqueId(), NPC));

        String rendered = DeliveryStatusText.render(lines);
        assertTrue(rendered.contains("1/4"), () -> "état lisible attendu, obtenu : " + rendered);
        assertTrue(rendered.contains("<lang:"), "le nom de l'objet doit être traduit côté client");
    }

    /**
     * Le nom d'objet est injecté comme une balise MiniMessage {@code <lang:…>} : si cette balise
     * n'était pas reconnue, le joueur lirait littéralement « <lang:item.minecraft.leather> ». Ce
     * test désérialise réellement le texte et exige un composant traduisible dans l'arbre.
     */
    @Test
    void theRenderedStatusProducesARealTranslatableComponentNotLiteralText() throws Exception {
        PlayerMock player = playerWithActiveQuest(LEATHER_QUEST);

        Component rendered = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                .deserialize(DeliveryStatusText.render(engine.pendingDeliveries(player.getUniqueId(), NPC)));

        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(rendered);
        assertFalse(plain.contains("<lang:"), () -> "balise non interprétée, le joueur la verrait : " + plain);
        assertTrue(containsTranslatable(rendered), "le nom de l'objet doit être un composant traduisible");
        assertTrue(plain.contains("0/4"), () -> "compteur attendu, obtenu : " + plain);
    }

    private static boolean containsTranslatable(Component component) {
        if (component instanceof net.kyori.adventure.text.TranslatableComponent) {
            return true;
        }
        return component.children().stream().anyMatch(QuestItemDeliveryTest::containsTranslatable);
    }

    @Test
    void theStatusOfANpcThatExpectsNothingIsExplicit() throws Exception {
        PlayerMock player = addPlayer();

        assertEquals(DeliveryStatusText.NOTHING_EXPECTED,
                DeliveryStatusText.render(engine.pendingDeliveries(player.getUniqueId(), NPC)));
    }
}
