package com.lodygames.rpgquest.story;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository.StoryProgressRecord;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.AcceptOutcome;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import com.lodygames.rpgquest.story.model.StoryState;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.slf4j.Logger;

/**
 * Orchestre la progression Story de bout en bout : démarrage/consultation/réinitialisation
 * (admin/debug, comme avant) <strong>et</strong>, depuis cette étape, la progression automatique —
 * une Story {@code ACTIVE} avance toute seule au fil des quêtes qu'elle référence, sans aucune
 * commande ni interaction PNJ entre deux quêtes (voir {@link #onQuestProgressChanged}).
 *
 * <p><strong>Changement de conception par rapport à l'étape précédente</strong> : {@code
 * StoryService} n'est plus indépendant du moteur de quête — c'est précisément l'objet de cette
 * étape (connecter les deux). {@code questIds()} d'une {@link StoryDefinition} reste une simple
 * liste de références jamais validée au <em>chargement</em> (voir {@code StoryDefinitionParser}),
 * mais est désormais résolue à l'<em>exécution</em> contre {@link QuestProgressEngine}/{@link
 * YamlQuestEngine}.</p>
 *
 * <p><strong>Cache mémoire des stories {@code ACTIVE}</strong> — même conception que {@code
 * QuestProgressEngine#activeByPlayer} : chargé à la connexion ({@link #loadForPlayer}), vidé à la
 * déconnexion ({@link #unloadForPlayer}), et c'est cette structure (pas la base) qui sert de
 * verrou anti-double-avance — voir {@link #advanceStory}.</p>
 */
public final class StoryService implements PluginService {

    private final RPGQuestPlugin plugin;
    private final StoryRegistry registry;
    private final StoryProgressRepository progressRepository;
    private final PlayerProfileRepository profileRepository;
    private final QuestProgressEngine questProgressEngine;
    private final YamlQuestEngine questEngine;
    private final QuestMessagesService messagesService;
    private final Logger logger;

    private final Map<UUID, Map<String, ActiveStoryProgress>> activeByPlayer = new ConcurrentHashMap<>();

    public StoryService(RPGQuestPlugin plugin, StoryRegistry registry, StoryProgressRepository progressRepository,
                         PlayerProfileRepository profileRepository, QuestProgressEngine questProgressEngine,
                         YamlQuestEngine questEngine, QuestMessagesService messagesService, Logger logger) {
        this.plugin = plugin;
        this.registry = registry;
        this.progressRepository = progressRepository;
        this.profileRepository = profileRepository;
        this.questProgressEngine = questProgressEngine;
        this.questEngine = questEngine;
        this.messagesService = messagesService;
        this.logger = logger;
    }

    @Override
    public void start() {
        // Rien à démarrer : le chargement des définitions est StoryRegistry (service séparé), la
        // progression par joueur est chargée à la connexion (voir loadForPlayer).
    }

    @Override
    public void stop() {
        activeByPlayer.clear();
    }

    /** Écouteur de connexion/déconnexion à enregistrer sans condition — même patron que {@code QuestProgressEngine#connectionListener}. */
    public Listener connectionListener() {
        return new StoryConnectionListener(this);
    }

    public List<StoryDefinition> stories() {
        return registry.stories();
    }

    public record StoryInfo(StoryDefinition story, StoryState state, int currentIndex) {
    }

    /** Un {@link StoryInfo} par story connue du registre, dans l'ordre chargé — {@code NOT_STARTED} si aucune ligne. */
    public CompletableFuture<List<StoryInfo>> info(UUID playerId) {
        return progressRepository.findAll(playerId).thenApply(records -> registry.stories().stream()
                .map(story -> {
                    StoryProgressRecord record = records.get(story.id());
                    return record == null
                            ? new StoryInfo(story, StoryState.NOT_STARTED, 0)
                            : new StoryInfo(story, record.state(), record.currentIndex());
                })
                .toList());
    }

    // ---- Cycle de vie joueur (mêmes responsabilités que QuestProgressEngine#loadForPlayer) -------

    /**
     * Recharge les stories {@code ACTIVE} du joueur depuis la base, puis vérifie/rattrape l'état de
     * la quête courante de chacune (auto-guérison après redémarrage/reconnexion — mission point 3) :
     * quête jamais acceptée → démarrée ; déjà terminée (crash entre la complétion et l'avancement
     * Story) → la story avance immédiatement ; déjà active → rien à faire.
     */
    public void loadForPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        progressRepository.findAll(playerId).thenAccept(records -> {
            Map<String, ActiveStoryProgress> map = new ConcurrentHashMap<>();
            for (var entry : records.entrySet()) {
                String storyId = entry.getKey();
                StoryProgressRecord record = entry.getValue();
                if (record.state() != StoryState.ACTIVE) {
                    continue;
                }
                registry.find(storyId).ifPresent(story -> {
                    int index = Math.min(Math.max(record.currentIndex(), 0), story.questIds().size() - 1);
                    map.put(storyId, new ActiveStoryProgress(storyId, index));
                });
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                activeByPlayer.put(playerId, map);
                if (player.isOnline()) {
                    ensureCurrentQuestsAreOnTrack(player);
                }
            });
        }).exceptionally(error -> {
            logger.error("Impossible de charger la progression Story de {}", playerId, error);
            return null;
        });
    }

    public void unloadForPlayer(UUID playerId) {
        activeByPlayer.remove(playerId);
    }

    private void ensureCurrentQuestsAreOnTrack(Player player) {
        Map<String, ActiveStoryProgress> active = activeByPlayer.get(player.getUniqueId());
        if (active == null || active.isEmpty()) {
            return;
        }
        for (ActiveStoryProgress progress : List.copyOf(active.values())) {
            evaluateStory(player, progress);
        }
    }

    public enum StartOutcome {
        STARTED, ALREADY_ACTIVE, ALREADY_COMPLETED, UNKNOWN_STORY
    }

    /**
     * Démarre {@code storyId} pour {@code playerId} — {@code playerName} sert uniquement à
     * créer/rafraîchir le profil joueur ({@code player_profiles}, contrainte de clé étrangère de
     * {@code story_progress}) si la cible est hors ligne et n'a pas encore de profil.
     *
     * <p>Toujours persisté immédiatement, même hors ligne — mais le premier quête n'est
     * effectivement démarrée que si la cible est <strong>actuellement en ligne</strong> (le moteur
     * de quête n'a besoin/n'accepte qu'un {@code Player} connecté). Pour une cible hors ligne, la
     * première quête démarre automatiquement à sa prochaine connexion (voir {@link
     * #loadForPlayer}) — jamais besoin d'une commande joueur (mission : UX joueur prioritaire).</p>
     */
    public CompletableFuture<StartOutcome> start(UUID playerId, String playerName, String storyId) {
        Optional<StoryDefinition> storyOpt = registry.find(storyId);
        if (storyOpt.isEmpty()) {
            return CompletableFuture.completedFuture(StartOutcome.UNKNOWN_STORY);
        }
        StoryDefinition story = storyOpt.get();
        return progressRepository.find(playerId, storyId).thenCompose(existing -> {
            if (existing.isPresent() && existing.get().state() == StoryState.ACTIVE) {
                return CompletableFuture.completedFuture(StartOutcome.ALREADY_ACTIVE);
            }
            if (existing.isPresent() && existing.get().state() == StoryState.COMPLETED) {
                return CompletableFuture.completedFuture(StartOutcome.ALREADY_COMPLETED);
            }

            return profileRepository.findOrCreate(playerId, playerName)
                    .thenCompose(profile -> progressRepository.upsertProgress(playerId, storyId, StoryState.ACTIVE, 0))
                    .thenApply(v -> {
                        logger.info("Story « {} » démarrée pour {} ({}).", storyId, playerName, playerId);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            activeByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                                    .put(storyId, new ActiveStoryProgress(storyId, 0));
                            Player online = plugin.getServer().getPlayer(playerId);
                            if (online != null) {
                                showStoryStarted(online, story);
                                startCurrentQuest(online, story, story.questIds().get(0));
                            }
                        });
                        return StartOutcome.STARTED;
                    });
        });
    }

    // ---- Progression automatique --------------------------------------------------------------

    /**
     * Branché sur {@link QuestProgressEngine#onProgressChanged} (voir le bootstrap) — notifié après
     * <strong>toute</strong> mutation de progression de quête d'un joueur, pas seulement une fin.
     * Toujours ré-évalué en repassant sur le thread principal (voir {@link #evaluateStory}) : {@code
     * onProgressChanged} peut être invoqué depuis un thread autre que principal (complétion d'un
     * {@code CompletableFuture} interne à {@code QuestProgressEngine}), et toute interaction avec un
     * {@code Player} (messages, {@code accept}) doit rester sur le thread principal.
     */
    public void onQuestProgressChanged(UUID playerId) {
        Map<String, ActiveStoryProgress> active = activeByPlayer.get(playerId);
        if (active == null || active.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return; // déconnecté entre-temps.
            }
            for (ActiveStoryProgress progress : List.copyOf(active.values())) {
                evaluateStory(player, progress);
            }
        });
    }

    private void evaluateStory(Player player, ActiveStoryProgress progress) {
        Optional<StoryDefinition> storyOpt = registry.find(progress.storyId());
        if (storyOpt.isEmpty()) {
            return; // story supprimée/déchargée entre-temps.
        }
        StoryDefinition story = storyOpt.get();
        NamespacedKey currentQuestId = story.questIds().get(progress.currentIndex());
        questProgressEngine.stateOf(player.getUniqueId(), currentQuestId).thenAccept(state -> {
            if (state != QuestState.COMPLETED) {
                return; // rien à faire : pas encore terminée (ou pas encore acceptée, voir startCurrentQuest).
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> advanceStory(player, story, progress, currentQuestId));
        }).exceptionally(error -> {
            logger.error("Échec de la vérification de progression Story « {} » pour {}", story.id(), player.getUniqueId(), error);
            return null;
        });
    }

    /**
     * Fait avancer {@code progress} d'une position — idempotent par construction (mission point 3) :
     * la <strong>seule</strong> garde nécessaire est de vérifier, au moment de s'exécuter sur le
     * thread principal, que {@code progress} pointe toujours vers {@code completedQuestId} — si un
     * appel concurrent a déjà fait avancer l'index entre-temps (deux notifications pour la même
     * complétion, ou l'auto-guérison de {@link #loadForPlayer} qui arrive en même temps), cette
     * vérification échoue silencieusement et rien n'est refait deux fois. Aucune récompense n'est
     * distribuée ici : {@link QuestProgressEngine#accept} ne distribue jamais de récompense (elles ne
     * le sont qu'à la remise, déjà protégée par sa propre garde), donc rien à dédupliquer côté Story.
     */
    private void advanceStory(Player player, StoryDefinition story, ActiveStoryProgress progress, NamespacedKey completedQuestId) {
        if (progress.currentIndex() >= story.questIds().size()
                || !story.questIds().get(progress.currentIndex()).equals(completedQuestId)) {
            return; // déjà avancée par un appel concurrent : rien à refaire.
        }

        int nextIndex = progress.currentIndex() + 1;
        progress.setCurrentIndex(nextIndex); // synchrone, avant tout async : verrouille la suite.
        UUID playerId = player.getUniqueId();

        if (nextIndex >= story.questIds().size()) {
            Map<String, ActiveStoryProgress> active = activeByPlayer.get(playerId);
            if (active != null) {
                active.remove(story.id());
            }
            progressRepository.upsertProgress(playerId, story.id(), StoryState.COMPLETED, nextIndex).exceptionally(error -> {
                logger.error("Impossible de persister la complétion de la story « {} » pour {}", story.id(), playerId, error);
                return null;
            });
            logger.info("Story « {} » terminée pour {}.", story.id(), playerId);
            showStoryCompleted(player, story);
            return;
        }

        progressRepository.upsertProgress(playerId, story.id(), StoryState.ACTIVE, nextIndex).exceptionally(error -> {
            logger.error("Impossible de persister l'avancement de la story « {} » pour {}", story.id(), playerId, error);
            return null;
        });
        NamespacedKey nextQuestId = story.questIds().get(nextIndex);
        showNextObjective(player, nextQuestId);
        startCurrentQuest(player, story, nextQuestId);
    }

    /**
     * Démarre {@code questId} pour {@code player} via le moteur de quête normal ({@link
     * QuestProgressEngine#accept}), qui est déjà idempotent (renvoie {@code ALREADY_ACTIVE}
     * proprement si déjà en cours, ne redonne jamais de récompense). Une quête déjà {@code
     * COMPLETED} et {@code repeatable: false} au moment où la story l'atteint (contenu mal
     * configuré, ou déjà terminée hors story) ne bloque jamais silencieusement toute la story :
     * {@link #evaluateStory} verra {@code stateOf == COMPLETED} au prochain événement de progression
     * et avancera quand même — seul un journal d'avertissement signale l'anomalie.
     */
    private void startCurrentQuest(Player player, StoryDefinition story, NamespacedKey questId) {
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            logger.warn("Story « {} » référence une quête inconnue « {} » — quête ignorée, la story ne peut pas progresser dessus.",
                    story.id(), questId);
            return;
        }
        questProgressEngine.accept(player, questId).thenAccept(outcome -> {
            if (outcome.result() != AcceptOutcome.Result.ACCEPTED
                    && outcome.result() != AcceptOutcome.Result.ALREADY_ACTIVE) {
                logger.warn("Story « {} » : impossible de démarrer automatiquement « {} » pour {} ({}).",
                        story.id(), questId, player.getUniqueId(), outcome.result());
            }
        });
    }

    // ---- Réinitialisation (admin/debug) --------------------------------------------------------

    public enum ResetOutcome {
        RESET_ONE, RESET_ALL, UNKNOWN_STORY
    }

    /**
     * {@code storyIdOrAll = "all"} (insensible à la casse) supprime toute progression Story du
     * joueur ; sinon cible une seule story, dont l'id doit exister dans le registre (évite de
     * supprimer silencieusement une ligne à cause d'une faute de frappe). Ne touche jamais à
     * {@code quest_progress}, à l'inventaire ni à l'économie — voir {@link StoryProgressRepository}
     * et, pour un reset qui remet <em>aussi</em> les quêtes associées dans un état rejouable, {@link
     * #resetWithQuests}.
     */
    public CompletableFuture<ResetOutcome> reset(UUID playerId, String storyIdOrAll) {
        if ("all".equals(storyIdOrAll.toLowerCase(Locale.ROOT))) {
            forgetActiveStories(playerId, null);
            return progressRepository.deleteAllForPlayer(playerId).thenApply(v -> {
                logger.info("Toute la progression Story de {} a été réinitialisée.", playerId);
                return ResetOutcome.RESET_ALL;
            });
        }
        if (registry.find(storyIdOrAll).isEmpty()) {
            return CompletableFuture.completedFuture(ResetOutcome.UNKNOWN_STORY);
        }
        forgetActiveStories(playerId, storyIdOrAll);
        return progressRepository.deleteStory(playerId, storyIdOrAll).thenApply(v -> {
            logger.info("Story « {} » réinitialisée pour {}.", storyIdOrAll, playerId);
            return ResetOutcome.RESET_ONE;
        });
    }

    public enum ResetWithQuestsOutcome {
        RESET, UNKNOWN_STORY
    }

    /**
     * Outil ADMIN/DEBUG ciblé (mission point 5) : remet {@code storyId} à zéro <strong>et</strong>
     * réinitialise, une par une, toutes les quêtes qu'elle référence — via {@link
     * QuestProgressEngine#resetQuest}, qui garantit déjà de ne jamais toucher aux autres quêtes du
     * joueur. Ne devine jamais qu'un reset Story doit tout remettre à zéro : {@link #reset} seul ne
     * touche <strong>que</strong> {@code story_progress}, jamais {@code quest_progress} — cette
     * méthode séparée est le seul chemin qui touche aussi aux quêtes, et seulement à celles listées
     * par cette story précisément.
     */
    public CompletableFuture<ResetWithQuestsOutcome> resetWithQuests(UUID playerId, String storyId) {
        Optional<StoryDefinition> storyOpt = registry.find(storyId);
        if (storyOpt.isEmpty()) {
            return CompletableFuture.completedFuture(ResetWithQuestsOutcome.UNKNOWN_STORY);
        }
        StoryDefinition story = storyOpt.get();
        forgetActiveStories(playerId, storyId);

        List<CompletableFuture<Void>> questResets = story.questIds().stream()
                .map(questId -> questProgressEngine.resetQuest(playerId, questId))
                .toList();
        return CompletableFuture.allOf(questResets.toArray(CompletableFuture[]::new))
                .thenCompose(v -> progressRepository.deleteStory(playerId, storyId))
                .thenApply(v -> {
                    logger.info("Story « {} » ET ses {} quête(s) associée(s) réinitialisées pour {}.",
                            storyId, story.questIds().size(), playerId);
                    return ResetWithQuestsOutcome.RESET;
                });
    }

    private void forgetActiveStories(UUID playerId, String storyIdOrNullForAll) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Map<String, ActiveStoryProgress> active = activeByPlayer.get(playerId);
            if (active == null) {
                return;
            }
            if (storyIdOrNullForAll == null) {
                active.clear();
            } else {
                active.remove(storyIdOrNullForAll);
            }
        });
    }

    // ---- Feedback joueur (chat, jamais Title/Subtitle — voir docs/storylines.md) ----------------
    //
    // Volontairement le chat, pas un Title comme les quêtes : QuestProgressEngine#accept affiche déjà
    // son propre Title "Quête commencée" à chaque démarrage de quête (Story ou non) ; empiler un
    // second Title juste avant/après créerait une course d'affichage (le second écrase toujours le
    // premier, sans garantie fiable de l'ordre entre deux CompletableFuture distincts). Le chat
    // n'entre jamais en conflit et garde un historique consultable des étapes de la story.

    private void showStoryStarted(Player player, StoryDefinition story) {
        player.sendMessage(messagesService.current().format(
                "story.started", Placeholder.parsed("story", story.name().base())));
    }

    private void showNextObjective(Player player, NamespacedKey questId) {
        String title = questEngine.find(questId).map(q -> q.title().base()).orElse(questId.toString());
        player.sendMessage(messagesService.current().format(
                "story.next-objective", Placeholder.parsed("quest", title)));
    }

    private void showStoryCompleted(Player player, StoryDefinition story) {
        player.sendMessage(messagesService.current().format(
                "story.completed", Placeholder.parsed("story", story.name().base())));
    }
}
