package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Le « vertical slice » complet demandé (dialogue → quête → combat → récolte
 * → fabrication → remise → récompense) : dialogue → accepte {@code
 * first_steps} → tue des araignées → remise automatique → accepte {@code
 * crystal_hunt} (prérequis satisfait) → tue des araignées → collecte des
 * cristaux → fabrique une lame → parle au garde → remise automatique →
 * reçoit {@code miner_pickaxe} (récompense {@code COMMAND}).
 *
 * <p>Utilise volontairement le plugin <b>réellement</b> démarré via {@code
 * MockBukkit.load(RPGQuestPlugin.class)} plutôt que des définitions
 * synthétiques : ce test exerce les quêtes/objets/recettes exactement tels
 * qu'ils sont embarqués dans le jar, pas une reconstruction simplifiée.</p>
 */
class CrystalHuntIntegrationTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey FIRST_STEPS = new NamespacedKey("rpgquest", "first_steps");
    private static final NamespacedKey CRYSTAL_HUNT = new NamespacedKey("rpgquest", "crystal_hunt");
    private static final NamespacedKey MINER_PICKAXE = new NamespacedKey("rpgquest", "miner_pickaxe");

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private QuestProgressEngine engine;
    private YamlCustomItemRegistry itemRegistry;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        engine = plugin.bootstrap().questProgressEngine();
        itemRegistry = plugin.bootstrap().customItemRegistry();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void fullDialogueQuestCraftingLoopGrantsTheFinalReward() throws Exception {
        PlayerMock player = server.addPlayer();
        // Attend explicitement la fin du chargement asynchrone déclenché par PlayerJoinEvent
        // (QuestProgressConnectionListener -> loadForPlayer) avant d'interagir : sur un vrai
        // client, la latence réseau garantit naturellement cet ordre, pas ici où tout est local.
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 1-2. Accepte la première quête (comme le ferait le dialogue du garde).
        var firstAccept = engine.accept(player, FIRST_STEPS).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, firstAccept.result());

        // 3. Tue des araignées (first_steps en demande 10).
        for (int i = 0; i < 10; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }
        assertEquals(QuestState.COMPLETED, stateOf(player, FIRST_STEPS));

        // Le prérequis est désormais satisfait : crystal_hunt devient accessible.
        var huntAccept = engine.accept(player, CRYSTAL_HUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, huntAccept.result());

        // 3 (suite). Tue les araignées requises par crystal_hunt (5).
        for (int i = 0; i < 5; i++) {
            engine.handleKillEntity(player, EntityType.SPIDER);
        }

        // 4. Récolte des cristaux (2x AMETHYST_SHARD, matériau de base de refined_crystal).
        engine.handleCollectItem(player, Material.AMETHYST_SHARD);
        engine.handleCollectItem(player, Material.AMETHYST_SHARD);

        // 5. Fabrique une lame (forest_blade_recipe produit un DIAMOND_SWORD comme matériau de base).
        engine.handleCraftItem(player, Material.DIAMOND_SWORD);

        // 6-8. Retourne parler au garde : dernier objectif -> remise automatique -> récompense.
        int itemsBefore = countItems(player, MINER_PICKAXE);
        engine.handleTalkToNpc(player, "guard");

        assertEquals(QuestState.COMPLETED, stateOf(player, CRYSTAL_HUNT));
        assertEquals(itemsBefore + 1, countItems(player, MINER_PICKAXE),
                "la récompense COMMAND doit donner exactement un miner_pickaxe");
    }

    private QuestState stateOf(PlayerMock player, NamespacedKey questId) throws Exception {
        return engine.stateOf(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private int countItems(PlayerMock player, NamespacedKey itemId) {
        int count = 0;
        for (var stack : player.getInventory().getContents()) {
            if (stack != null && itemRegistry.identify(stack).map(itemId::equals).orElse(false)) {
                count += stack.getAmount();
            }
        }
        return count;
    }
}
