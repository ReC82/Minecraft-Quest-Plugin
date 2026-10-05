package com.lodygames.rpgquest.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.ops.ServerLogBuffer;
import com.lodygames.rpgquest.ops.ServerOpsService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #210 — OP/DEOP, renvoi au Hub, expulsion et whitelist.
 *
 * <p>Ce qui compte ici : chaque opération <strong>relit l'état réel</strong> après écriture, est
 * <strong>idempotente</strong>, et refuse clairement ce qui est impossible (joueur hors ligne,
 * UUID jamais vu, destination non résolue) plutôt que de renvoyer un faux succès.</p>
 *
 * <p>OP Minecraft est strictement distinct du rôle PlugAdmin, des droits de construction et du
 * bypass de gameplay : aucune de ces opérations n'y touche, et c'est testé.</p>
 */
class PlayerAdminActionsTest {

    private static final long TIMEOUT_SECONDS = 5;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private BukkitAgentActions actions;
    private World hubWorld;
    private Optional<Location> hubTarget;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hubWorld = server.addSimpleWorld("world_hub");
        server.addSimpleWorld("claims");
        hubTarget = Optional.of(new Location(hubWorld, 100.5, 70, -200.5));

        actions = new BukkitAgentActions(plugin, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                () -> "wild",
                new ServerOpsService(plugin, new ServerLogBuffer(50), Optional::empty),
                null,
                () -> hubTarget,
                null, null);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Ces opérations s'exécutent sur le <strong>thread principal</strong> (elles touchent des
     * entités Bukkit). MockBukkit ne déroule les tâches du thread principal que si l'on pompe des
     * ticks : sans cela le futur n'est jamais complété, et le test mesurerait le harnais plutôt que
     * le code. En production l'ordonnanceur tourne de lui-même.
     */
    private <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        for (int i = 0; i < 200 && !future.isDone(); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(5);
        }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private AgentActions.MutationResult op(PlayerMock player, boolean op) throws Exception {
        return await(actions.setOperator(player.getUniqueId(), player.getName(), op));
    }

    // ---- OP / DEOP --------------------------------------------------------------------------

    @Test
    void grantingOpIsAppliedAndRereadFromTheServer() throws Exception {
        PlayerMock player = server.addPlayer();
        assertFalse(player.isOp(), "précondition : le joueur n'est pas OP");

        AgentActions.MutationResult result = op(player, true);

        assertTrue(result.ok(), result.message());
        assertEquals("OPPED", result.code());
        assertTrue(player.isOp(), "l'état réel du serveur a changé");
    }

    @Test
    void revokingOpIsAppliedAndRereadFromTheServer() throws Exception {
        PlayerMock player = server.addPlayer();
        player.setOp(true);

        AgentActions.MutationResult result = op(player, false);

        assertTrue(result.ok(), result.message());
        assertEquals("DEOPPED", result.code());
        assertFalse(player.isOp());
    }

    @Test
    void grantingOpTwiceIsIdempotentAndSaysSo() throws Exception {
        PlayerMock player = server.addPlayer();
        op(player, true);

        AgentActions.MutationResult again = op(player, true);

        // Un rejeu (double-clic, retry réseau) ne doit ni échouer ni prétendre avoir agi.
        assertTrue(again.ok());
        assertEquals("ALREADY_OP", again.code());
        assertTrue(player.isOp());
    }

    @Test
    void revokingOpOnANonOpIsIdempotent() throws Exception {
        PlayerMock player = server.addPlayer();

        AgentActions.MutationResult result = op(player, false);

        assertTrue(result.ok());
        assertEquals("NOT_OP", result.code());
    }

    @Test
    void theResultStatesThatOnlyMinecraftOpChanged() throws Exception {
        PlayerMock player = server.addPlayer();

        AgentActions.MutationResult result = op(player, true);

        // Le ticket insiste : ne pas confondre OP Minecraft, rôle PlugAdmin, droit builder et
        // bypass gameplay. Le résultat le dit à l'administrateur.
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("rôle PlugAdmin")),
                () -> result.effects().toString());
    }

    @Test
    void anUuidNeverSeenByTheServerIsRefusedRatherThanElevated() throws Exception {
        UUID stranger = UUID.randomUUID();

        AgentActions.MutationResult result = await(actions.setOperator(stranger, "Inconnu", true));

        // Accorder OP à un compte jamais vu, sur la seule foi d'une saisie, serait une élévation
        // à l'aveugle.
        assertFalse(result.ok(), result.message());
        assertEquals("UNKNOWN_PLAYER", result.code());
    }

    /**
     * Joueur <strong>hors ligne</strong> : l'écriture ne prend pas effet sous MockBukkit (mesuré —
     * {@code isOp()} relu reste {@code false} après {@code setOp(true)} sur un joueur déconnecté).
     * Sur un vrai serveur, Bukkit écrit dans {@code ops.json} et l'élévation hors ligne fonctionne.
     *
     * <p>Ce test n'affirme donc pas un comportement que le harnais ne sait pas reproduire : il
     * vérifie la garantie qui compte dans les deux cas — <strong>jamais de faux succès</strong>. Le
     * service relit l'état réel et, s'il n'a pas changé, renvoie {@code NOT_APPLIED} avec la valeur
     * relue. C'est exactement ce qui protège l'administrateur d'un « OP accordé » mensonger.</p>
     *
     * <p>L'élévation hors ligne réelle reste donc une vérification manuelle (TC #210).</p>
     */
    @Test
    void anOfflinePlayerNeverGetsAFalseSuccess() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID id = player.getUniqueId();
        String name = player.getName();
        player.disconnect();

        AgentActions.MutationResult result = await(actions.setOperator(id, name, true));

        boolean actuallyOp = server.getOfflinePlayer(id).isOp();
        if (result.ok()) {
            assertTrue(actuallyOp, "un succès annoncé doit correspondre à l'état réel relu");
        } else {
            assertEquals("NOT_APPLIED", result.code(), result.message());
            assertFalse(actuallyOp);
            assertTrue(result.message().contains("relu"), result.message());
        }
    }

    // ---- Renvoi au Hub ----------------------------------------------------------------------

    @Test
    void sendingAnOnlinePlayerToTheHubMovesThemAndPreservesEverything() throws Exception {
        PlayerMock player = server.addPlayer();
        player.setLocation(new Location(server.getWorld("claims"), -20.5, 101, 44.8));
        player.getInventory().addItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER, 1));
        int inventoryBefore = countItems(player);

        AgentActions.MutationResult result = await(actions.sendToHub(player.getUniqueId(), player.getName()));

        assertTrue(result.ok(), result.message());
        assertEquals("SENT", result.code());
        assertEquals("world_hub", player.getWorld().getName(), "le joueur a réellement bougé");
        assertEquals(inventoryBefore, countItems(player), "inventaire intact — aucun reset");
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("Inventaire")),
                () -> result.effects().toString());
    }

    @Test
    void sendingAnOfflinePlayerToTheHubIsRefusedWithoutPretending() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID id = player.getUniqueId();
        player.disconnect();

        AgentActions.MutationResult result =
                await(actions.sendToHub(id, "Absent"));

        assertFalse(result.ok());
        assertEquals("OFFLINE", result.code());
    }

    @Test
    void anUnresolvableHubDestinationIsRefusedRatherThanTeleportingNowhere() throws Exception {
        PlayerMock player = server.addPlayer();
        player.setLocation(new Location(server.getWorld("claims"), 0, 70, 0));
        hubTarget = Optional.empty();

        AgentActions.MutationResult result = await(actions.sendToHub(player.getUniqueId(), player.getName()));

        assertFalse(result.ok());
        assertEquals("NO_DESTINATION", result.code());
        assertEquals("claims", player.getWorld().getName(), "le joueur n'a pas bougé");
    }

    private static int countItems(PlayerMock player) {
        int total = 0;
        for (org.bukkit.inventory.ItemStack stack : player.getInventory().getContents()) {
            if (stack != null) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    // ---- Expulsion ---------------------------------------------------------------------------

    @Test
    void kickingAnOnlinePlayerShowsTheReason() throws Exception {
        PlayerMock player = server.addPlayer();

        AgentActions.MutationResult result = await(actions.kickPlayer(player.getUniqueId(), player.getName(), "Maintenance imminente"));

        assertTrue(result.ok(), result.message());
        assertEquals("KICKED", result.code());
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("Maintenance imminente")),
                () -> result.effects().toString());
    }

    @Test
    void kickingAnOfflinePlayerIsRefused() throws Exception {
        PlayerMock player = server.addPlayer();
        UUID id = player.getUniqueId();
        player.disconnect();

        AgentActions.MutationResult result =
                await(actions.kickPlayer(id, "Absent", "raison"));

        assertFalse(result.ok());
        assertEquals("OFFLINE", result.code());
    }

    @Test
    void anEmptyKickReasonFallsBackToAReadableDefault() throws Exception {
        PlayerMock player = server.addPlayer();

        AgentActions.MutationResult result = await(actions.kickPlayer(player.getUniqueId(), player.getName(), "   "));

        assertTrue(result.ok());
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("administrateur")),
                () -> result.effects().toString());
    }

    // ---- Whitelist ---------------------------------------------------------------------------

    @Test
    void addingToTheWhitelistIsAppliedAndRereadFromTheServer() throws Exception {
        PlayerMock player = server.addPlayer();

        AgentActions.MutationResult result = await(actions.setWhitelisted(player.getUniqueId(), player.getName(), true));

        assertTrue(result.ok(), result.message());
        assertEquals("WHITELISTED", result.code());
        assertTrue(server.getOfflinePlayer(player.getUniqueId()).isWhitelisted());
    }

    @Test
    void removingFromTheWhitelistIsAppliedAndIdempotent() throws Exception {
        PlayerMock player = server.addPlayer();
        await(actions.setWhitelisted(player.getUniqueId(), player.getName(), true));

        AgentActions.MutationResult removed = await(actions.setWhitelisted(player.getUniqueId(), player.getName(), false));
        AgentActions.MutationResult again = await(actions.setWhitelisted(player.getUniqueId(), player.getName(), false));

        assertEquals("UNWHITELISTED", removed.code());
        assertEquals("NOT_WHITELISTED", again.code(), "un rejeu ne prétend pas avoir agi");
    }

    @Test
    void theResultWarnsWhenTheWhitelistIsNotEvenEnforced() throws Exception {
        PlayerMock player = server.addPlayer();
        server.setWhitelist(false);

        AgentActions.MutationResult result = await(actions.setWhitelisted(player.getUniqueId(), player.getName(), true));

        // Ajouter un joueur à une whitelist désactivée ne protège rien : le dire évite une fausse
        // impression de sécurité.
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("DÉSACTIVÉE")),
                () -> result.effects().toString());
    }

    @Test
    void theResultConfirmsWhenTheWhitelistIsEnforced() throws Exception {
        PlayerMock player = server.addPlayer();
        server.setWhitelist(true);

        AgentActions.MutationResult result = await(actions.setWhitelisted(player.getUniqueId(), player.getName(), true));

        assertTrue(result.effects().stream().anyMatch(e -> e.contains("active")),
                () -> result.effects().toString());
    }
}
