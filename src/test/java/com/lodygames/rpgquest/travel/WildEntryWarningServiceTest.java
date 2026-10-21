package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import com.lodygames.rpgquest.travel.model.PortalMessages;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #161 (qui couvre la partie B de #26) : avertissement de danger générique avant l'entrée
 * dans le Wild, confirmation explicite, option persistante « ne plus afficher », retour visible de
 * préparation, et une seule demande en vol par joueur.
 *
 * <p>La présentation est injectée ({@link CapturingPresenter}) pour pouvoir « cliquer » les boutons
 * sans client Minecraft ; un test dédié vérifie en plus le rendu réel du repli chat. Comme ailleurs
 * dans ce dépôt, {@code MockBukkit.load(RPGQuestPlugin.class)} démarre le vrai plugin : les
 * assertions cherchent toujours le message attendu <em>parmi</em> ceux reçus, jamais le premier.</p>
 */
class WildEntryWarningServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    private static final WorldPortalDefinition TO_WILD = new WorldPortalDefinition(
            "hub_to_wild", "world_hub", 0, 0, 0, 4, 4, 4, "wild", true);
    private static final WorldPortalDefinition TO_CLAIMS = new WorldPortalDefinition(
            "hub_to_claims", "world_hub", 0, 0, 0, 4, 4, 4, "claims", true);

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerVariableRepository variableRepository;
    private PlayerProfileRepository profileRepository;
    private CapturingPresenter presenter;
    private WildEntryWarningService guard;

    private final AtomicInteger teleportNowCalls = new AtomicInteger();
    private final AtomicReference<String> lastTeleportPortal = new AtomicReference<>();

    /** Mémorise le dernier avertissement affiché et expose ses boutons, pour les « cliquer ». */
    private static final class CapturingPresenter implements WildEntryPromptPresenter {

        private final AtomicInteger presentations = new AtomicInteger();
        private volatile WildEntryPrompt last;

        @Override
        public void present(Player player, WildEntryPrompt prompt) {
            last = prompt;
            presentations.incrementAndGet();
        }

        void click(int buttonIndex) {
            last.buttons().get(buttonIndex).action().run();
        }

        String plainBody() {
            return last.body().stream()
                    .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                    .reduce("", (a, b) -> a + "\n" + b);
        }

        List<String> plainLabels() {
            return last.buttons().stream()
                    .map(button -> PlainTextComponentSerializer.plainText().serialize(button.label()))
                    .toList();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world_hub");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        variableRepository = new PlayerVariableRepository(database);
        profileRepository = new PlayerProfileRepository(database);

        presenter = new CapturingPresenter();
        guard = newGuard(presenter);
    }

    private WildEntryWarningService newGuard(WildEntryPromptPresenter presenter) {
        return new WildEntryWarningService(plugin, variableRepository, () -> "wild",
                (player, portal) -> {
                    lastTeleportPortal.set(portal.id());
                    teleportNowCalls.incrementAndGet();
                },
                presenter);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    /**
     * Le profil est créé avant toute écriture : {@code player_variables} porte une clé étrangère
     * vers {@code player_profiles} (voir {@code database.SchemaMigrator}), exactement comme en jeu
     * où {@code PlayerConnectionListener} crée le profil à la connexion.
     */
    private PlayerMock player() throws Exception {
        PlayerMock p = server.addPlayer();
        profileRepository.findOrCreate(p.getUniqueId(), p.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        p.teleport(new Location(server.getWorld("world_hub"), 2, 2, 2));
        return p;
    }

    private void pumpUntil(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(15);
        }
        assertTrue(condition.getAsBoolean(), () -> "condition jamais atteinte : " + what);
    }

    /** Laisse passer assez de ticks pour qu'une téléportation différée parasite se révèle. */
    private void pumpQuietly() throws Exception {
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(5);
        }
    }

    private List<String> drainMessages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            out.add(next);
        }
        return out;
    }

    /** Préférence par portail (issue #569) de {@code hub_to_wild}. */
    private String hiddenPreference(UUID playerId) throws Exception {
        return variable(playerId, WildEntryWarningService.hiddenKey("hub_to_wild"));
    }

    private String variable(UUID playerId, String key) throws Exception {
        Optional<String> value = variableRepository.get(playerId, key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return value.orElse(null);
    }

    // ---- Portée du garde ------------------------------------------------

    @Test
    void portalsThatDoNotLeadToTheWildAreNeverAffected() throws Exception {
        PlayerMock player = player();

        assertTrue(guard.allowEntry(player, TO_CLAIMS), "un portail hors Wild passe sans contrôle");

        pumpQuietly();
        assertEquals(0, presenter.presentations.get(), "aucun avertissement pour un autre portail");
        assertEquals(0, teleportNowCalls.get(), "ce portail-là est téléporté par le listener, pas par le garde");
    }

    // ---- Première entrée ------------------------------------------------

    @Test
    void theFirstEntryWarnsWithoutInspectingTheInventoryAndNeverTeleports() throws Exception {
        PlayerMock player = player();
        player.getInventory().clear(); // inventaire vide : l'avertissement ne dépend d'aucun objet.

        assertFalse(guard.allowEntry(player, TO_WILD), "aucune téléportation avant confirmation");
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");

        String body = presenter.plainBody();
        assertTrue(body.contains("zone dangereuse"), () -> "danger annoncé, obtenu : " + body);
        assertTrue(body.contains("PvP y est autorisé"), () -> "PvP annoncé, obtenu : " + body);
        assertTrue(body.contains("perdre les objets de votre inventaire"), () -> "perte d'objets annoncée : " + body);
        assertTrue(body.contains("Voulez-vous continuer ?"), () -> "question de confirmation : " + body);
        assertTrue(body.contains("Le Garde peut vous renseigner sur les conditions actuelles du Wild."),
                () -> "renvoi vers le Garde (issue #24) attendu, obtenu : " + body);

        assertEquals(List.of("Entrer dans le Wild", "Entrer et ne plus afficher cet avertissement", "Annuler"),
                presenter.plainLabels(), "les trois actions explicites attendues");

        pumpQuietly();
        assertEquals(0, teleportNowCalls.get(), "toujours aucune téléportation sans confirmation");
    }

    @Test
    void theChatFallbackShowsTheWarningAndItsThreeActions() throws Exception {
        PlayerMock player = player();
        WildEntryWarningService chatGuard = newGuard(new ChatWildEntryPromptPresenter());
        drainMessages(player);

        assertFalse(chatGuard.allowEntry(player, TO_WILD));

        List<String> messages = new ArrayList<>();
        pumpUntil(() -> {
            messages.addAll(drainMessages(player));
            return messages.stream().anyMatch(m -> m.contains("zone dangereuse"))
                    && messages.stream().anyMatch(m -> m.contains("Entrer dans le Wild") && m.contains("Annuler"));
        }, "avertissement et ses trois actions reçus dans le chat");
    }

    // ---- Annulation -----------------------------------------------------

    @Test
    void cancellingKeepsThePlayerAtTheHub() throws Exception {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");
        drainMessages(player);

        presenter.click(2);

        pumpQuietly();
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Vous restez au Hub")),
                () -> "accusé d'annulation attendu, obtenu : " + messages);
        assertEquals(0, teleportNowCalls.get(), "une annulation ne téléporte jamais");
        assertNull(hiddenPreference(player.getUniqueId()), "une annulation ne mémorise jamais de préférence");
    }

    @Test
    void closingTheMenuWithoutChoosingKeepsThePlayerAtTheHub() throws Exception {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");
        drainMessages(player);

        // Fermer la fenêtre = n'exécuter aucune action : rien d'autre à simuler.
        pumpQuietly();

        assertEquals(0, teleportNowCalls.get(), "fermer le menu ne téléporte jamais");
        assertNull(hiddenPreference(player.getUniqueId()), "fermer le menu ne mémorise jamais de préférence");
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().noneMatch(m -> m.contains("Recherche d'un point d'arrivée")),
                () -> "aucune préparation lancée, obtenu : " + messages);
        assertFalse(guard.hasRequestInFlight(player.getUniqueId()),
                "le joueur ne doit jamais rester bloqué après une fermeture silencieuse");
    }

    // ---- Confirmation ---------------------------------------------------

    @Test
    void confirmingSendsThePreparationFeedbackImmediatelyThenTeleports() throws Exception {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");
        drainMessages(player);

        presenter.click(0);

        // Avant tout tick : le retour de préparation est déjà parti.
        List<String> immediate = drainMessages(player);
        assertTrue(immediate.stream().anyMatch(m -> m.contains("Recherche d'un point d'arrivée sûr")),
                () -> "retour de préparation immédiat attendu, obtenu : " + immediate);

        pumpUntil(() -> teleportNowCalls.get() == 1, "téléportation lancée après confirmation");
        assertEquals("hub_to_wild", lastTeleportPortal.get(), "le portail d'origine est conservé (RANDOM_SAFE inclus)");
        assertNull(hiddenPreference(player.getUniqueId()),
                "« Entrer » seul ne doit jamais masquer l'avertissement pour la suite");
        pumpQuietly();
        assertEquals(1, teleportNowCalls.get(), "exactement une téléportation");
    }

    @Test
    void neverShowAgainIsStoredOnlyWithARealDeparture() throws Exception {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");

        presenter.click(1);

        pumpUntil(() -> teleportNowCalls.get() == 1, "départ confirmé");
        pumpUntil(() -> {
            try {
                return "true".equals(hiddenPreference(player.getUniqueId()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "préférence « ne plus afficher » persistée");
        assertNull(variable(player.getUniqueId(), WildEntryWarningService.WARNING_HIDDEN_KEY),
                "l'ancienne clé n'est plus écrite : la préférence est par portail (#569)");
    }

    @Test
    void aHiddenWarningTeleportsDirectlyButStillShowsThePreparation() throws Exception {
        PlayerMock player = player();
        variableRepository.set(player.getUniqueId(), WildEntryWarningService.WARNING_HIDDEN_KEY, "true")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        drainMessages(player);

        assertFalse(guard.allowEntry(player, TO_WILD), "le départ reste piloté par le garde");
        pumpUntil(() -> teleportNowCalls.get() == 1, "départ direct sans avertissement");

        assertEquals(0, presenter.presentations.get(), "avertissement masqué : aucune fenêtre");
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Recherche d'un point d'arrivée sûr")),
                () -> "le retour de préparation reste visible même sans avertissement, obtenu : " + messages);
    }

    /** La préférence vit en base : un nouveau service (≈ redémarrage du serveur) la relit. */
    @Test
    void theStoredPreferenceSurvivesARestart() throws Exception {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");
        presenter.click(1);
        pumpUntil(() -> teleportNowCalls.get() == 1, "départ confirmé");

        CapturingPresenter afterRestart = new CapturingPresenter();
        WildEntryWarningService reloaded = newGuard(afterRestart);
        teleportNowCalls.set(0);

        assertFalse(reloaded.allowEntry(player, TO_WILD));
        pumpUntil(() -> teleportNowCalls.get() == 1, "départ direct après « redémarrage »");
        assertEquals(0, afterRestart.presentations.get(), "l'avertissement reste masqué après redémarrage");
    }

    // ---- Robustesse -----------------------------------------------------

    @Test
    void onlyOneRequestIsInFlightPerPlayer() throws Exception {
        PlayerMock player = player();

        assertFalse(guard.allowEntry(player, TO_WILD));
        assertFalse(guard.allowEntry(player, TO_WILD), "deuxième pas dans la zone : refusé sans relecture");
        assertFalse(guard.allowEntry(player, TO_WILD));

        pumpUntil(() -> presenter.presentations.get() >= 1, "avertissement affiché");
        pumpQuietly();
        assertEquals(1, presenter.presentations.get(), "un seul avertissement malgré trois entrées rapprochées");
    }

    @Test
    void repeatedConfirmationsNeverStartTwoDepartures() throws Exception {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");

        presenter.click(0);
        presenter.click(0); // double-clic rapide, avant le tick de départ.

        pumpUntil(() -> teleportNowCalls.get() >= 1, "départ lancé");
        pumpQuietly();
        assertEquals(1, teleportNowCalls.get(), "une seule téléportation malgré le double-clic");
    }

    @Test
    void aPlayerWhoDisconnectsIsNeverTeleportedLater() throws Exception {
        PlayerMock player = player();
        UUID playerId = player.getUniqueId();
        guard.allowEntry(player, TO_WILD);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");

        presenter.click(0);
        assertNotNull(server.getPlayer(playerId), "le joueur est encore là au moment de la confirmation");
        player.disconnect();

        pumpQuietly();
        assertEquals(0, teleportNowCalls.get(), "aucune téléportation tardive pour un joueur déconnecté");
        assertFalse(guard.hasRequestInFlight(playerId), "le jeton est relâché après une déconnexion");
    }

    // ---- Avertissement par portail (issue #569) ----------------------------

    private static WorldPortalDefinition withWarning(String id, String destination, PortalMessages.Warning warning) {
        return new WorldPortalDefinition(id, "world_hub", 0, 0, 0, 4, 4, 4, destination, false, null, null, null, "",
                PortalAccess.NONE, PortalMessages.NONE.withWarning(warning));
    }

    private static PortalMessages.Warning on(String title, String text, boolean allowHide) {
        return new PortalMessages.Warning(PortalMessages.Mode.ALWAYS, title, text, "", "<green>Y aller</green>", "",
                "", "<gray>Tu restes là.</gray>", allowHide);
    }

    @Test
    void hubToWildKeepsExactlyTheTextsItHadBefore() {
        assertEquals(WildEntryWarningService.WILD_DEFAULTS, WildEntryWarningService.texts(TO_WILD, "wild"),
                "sans personnalisation, hub_to_wild garde ses textes d'avant #569");
        assertTrue(WildEntryWarningService.appliesTo(TO_WILD, "wild"));
        assertFalse(WildEntryWarningService.appliesTo(TO_CLAIMS, "wild"));
    }

    @Test
    void aCustomWarningUsesThePortalTextsAndFallsBackToDefaults() throws Exception {
        WorldPortalDefinition toVillage = withWarning("hub_to_village", "village",
                on("<gold>Village</gold>", "<white>Les loups rôdent.</white>", true));
        PlayerMock player = player();

        assertFalse(guard.allowEntry(player, toVillage), "un portail averti n'est jamais traversé directement");
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché");

        assertTrue(presenter.plainBody().contains("Les loups rôdent."), presenter::plainBody);
        assertEquals(List.of("Y aller", "Entrer et ne plus afficher cet avertissement", "Annuler"),
                presenter.plainLabels(), "libellés vides = libellés par défaut");
        drainMessages(player);
        presenter.click(2);
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Tu restes là.")), () -> "obtenu : " + messages);
        assertEquals(0, teleportNowCalls.get());
    }

    @Test
    void warningOffLetsEvenAWildPortalThrough() throws Exception {
        WorldPortalDefinition silent = withWarning("hub_to_wild_b", "wild", new PortalMessages.Warning(
                PortalMessages.Mode.NEVER, "", "", "", "", "", "", "", true));
        PlayerMock player = player();

        assertTrue(guard.allowEntry(player, silent), "avertissement retiré : passage direct");
        assertEquals(0, presenter.presentations.get());
    }

    @Test
    void withoutNeverShowAgainTheWarningAlwaysShowsAndThePreferenceIsKept() throws Exception {
        WorldPortalDefinition strict = withWarning("hub_to_wild", "wild", on("", "", false));
        PlayerMock player = player();
        variableRepository.set(player.getUniqueId(), WildEntryWarningService.WARNING_HIDDEN_KEY, "true")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(guard.allowEntry(player, strict));
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement affiché malgré la préférence");
        assertEquals(2, presenter.plainLabels().size(), () -> "« ne plus afficher » absent : " + presenter.plainLabels());
        assertEquals("true", variable(player.getUniqueId(), WildEntryWarningService.WARNING_HIDDEN_KEY),
                "aucune préférence de joueur n'est effacée");
    }

    @Test
    void neverShowAgainIsRememberedPerPortal() throws Exception {
        WorldPortalDefinition a = withWarning("hub_to_a", "world_a", on("", "", true));
        WorldPortalDefinition b = withWarning("hub_to_b", "world_b", on("", "", true));
        PlayerMock player = player();

        guard.allowEntry(player, a);
        pumpUntil(() -> presenter.presentations.get() == 1, "avertissement de A");
        presenter.click(1);
        pumpUntil(() -> {
            try {
                return "true".equals(variable(player.getUniqueId(), WildEntryWarningService.hiddenKey("hub_to_a")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "préférence de A persistée");
        pumpUntil(() -> teleportNowCalls.get() == 1, "départ par A");

        guard.allowEntry(player, b);
        pumpUntil(() -> presenter.presentations.get() == 2, "B avertit toujours");

        guard.allowEntry(player, a);
        pumpUntil(() -> teleportNowCalls.get() == 2, "A ne réaverti plus");
        assertEquals(2, presenter.presentations.get());
        assertEquals("PORTAL_WARNING_HIDDEN_HUB_TO_A", WildEntryWarningService.hiddenKey("hub_to_a"));
    }

    @Test
    void theOldWildPreferenceIsNotAppliedToOtherDestinations() throws Exception {
        WorldPortalDefinition toVillage = withWarning("hub_to_village", "village", on("", "", true));
        PlayerMock player = player();
        variableRepository.set(player.getUniqueId(), WildEntryWarningService.WARNING_HIDDEN_KEY, "true")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        guard.allowEntry(player, toVillage);
        pumpUntil(() -> presenter.presentations.get() == 1, "l'ancienne clé ne vaut que pour le Wild");
    }
}
