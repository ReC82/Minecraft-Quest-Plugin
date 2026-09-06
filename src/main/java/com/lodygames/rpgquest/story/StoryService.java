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
import com.lodygames.rpgquest.quest.progress.CompleteOutcome;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import com.lodygames.rpgquest.story.model.StoryState;
import java.util.ArrayList;
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

    /**
     * Lignes brutes de {@code story_progress} d'un joueur (lecture pure, aucune écriture). Contrairement
     * à {@link #info}, ne filtre pas sur le registre : reflète exactement ce que
     * {@link #reset}{@code (id, "all")} supprimerait — utilisé par le preview du reset admin
     * « nouveau joueur ».
     */
    public CompletableFuture<Map<String, StoryProgressRecord>> progressRecords(UUID playerId) {
        return progressRepository.findAll(playerId);
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

    // ---- Raccourcis d'administration / test (issue #36) ---------------------------------------------
    //
    // Outils DEV pour atteindre rapidement une étape précise d'une story sans rejouer le gameplay des
    // quêtes précédentes. Ne réimplémentent AUCUNE logique métier : ils orchestrent, dans un ordre
    // connu, les mêmes services que la progression normale — QuestProgressEngine#forceComplete (qui
    // porte déjà la garde anti double-récompense), QuestProgressEngine#accept (idempotent) et
    // StoryProgressRepository. La seule différence avec la progression automatique (onQuestProgressChanged)
    // est le déclenchement : ici l'admin pousse une étape à la fois, au lieu d'attendre un événement
    // de jeu. Le chemin réactif (advanceStory) reste inoffensif s'il se déclenche en parallèle : sa
    // garde « progress pointe toujours vers la quête tout juste complétée » échoue puisqu'on a déjà
    // fait avancer l'index de l'objet ActiveStoryProgress partagé.

    public enum StoryAdvanceOutcome {
        /** La quête courante a été complétée ; la story pointe maintenant vers la quête suivante ({@code nextQuestId}). */
        ADVANCED,
        /** La dernière quête a été complétée ; la story est désormais {@code COMPLETED}. */
        STORY_COMPLETED,
        /** La story était déjà {@code COMPLETED} avant l'appel — rien fait. */
        ALREADY_COMPLETED,
        UNKNOWN_STORY,
        /** La story référence, à sa position courante, un id de quête que le moteur de quête ne connaît pas. */
        UNKNOWN_CURRENT_QUEST
    }

    /**
     * @param storyWasStarted               la story était {@code NOT_STARTED} et vient d'être démarrée par cet appel.
     * @param completedQuestId              quête tout juste complétée (null pour {@code UNKNOWN_STORY}/{@code ALREADY_COMPLETED}).
     * @param completedQuestWasAlreadyDone  {@code forceComplete} a renvoyé {@code ALREADY_COMPLETED} (aucune récompense re-créditée).
     * @param nextQuestId                   quête maintenant {@code ACTIVE} à tester manuellement (null si {@code STORY_COMPLETED}).
     * @param stepNumber                    position 1-indexée maintenant courante (== total si {@code STORY_COMPLETED}).
     */
    public record StoryAdvanceReport(StoryAdvanceOutcome outcome, String storyId, boolean storyWasStarted,
                                     NamespacedKey completedQuestId, boolean completedQuestWasAlreadyDone,
                                     NamespacedKey nextQuestId, int stepNumber, int totalSteps) {

        static StoryAdvanceReport unknownStory(String id) {
            return new StoryAdvanceReport(StoryAdvanceOutcome.UNKNOWN_STORY, id, false, null, false, null, 0, 0);
        }

        static StoryAdvanceReport alreadyCompleted(String id, int total) {
            return new StoryAdvanceReport(StoryAdvanceOutcome.ALREADY_COMPLETED, id, false, null, false, null, total, total);
        }

        static StoryAdvanceReport unknownCurrentQuest(String id, NamespacedKey quest, int index, int total) {
            return new StoryAdvanceReport(StoryAdvanceOutcome.UNKNOWN_CURRENT_QUEST, id, false, quest, false, null, index + 1, total);
        }

        static StoryAdvanceReport advanced(String id, NamespacedKey done, boolean wasDone, boolean started,
                                           NamespacedKey next, int newIndex, int total) {
            return new StoryAdvanceReport(StoryAdvanceOutcome.ADVANCED, id, started, done, wasDone, next, newIndex + 1, total);
        }

        static StoryAdvanceReport storyCompleted(String id, NamespacedKey done, boolean wasDone, boolean started, int total) {
            return new StoryAdvanceReport(StoryAdvanceOutcome.STORY_COMPLETED, id, started, done, wasDone, null, total, total);
        }
    }

    /**
     * Fait progresser {@code storyId} d'exactement <strong>une</strong> étape pour {@code player}
     * (qui doit être en ligne : le moteur de quête n'agit que sur un {@code Player} connecté) :
     *
     * <ol>
     *   <li>story {@code NOT_STARTED} → démarrée (profil + ligne {@code story_progress ACTIVE} à
     *       l'index 0), puis on enchaîne ;</li>
     *   <li>{@code forceComplete} de la quête courante de la story — applique ses récompenses
     *       normalement (dont {@code VARIABLE}, ex. {@code CLAIM_TIER_1}), une seule fois (garde de
     *       {@code forceComplete}) ;</li>
     *   <li>l'index de la story avance d'un cran : soit la story devient {@code COMPLETED}, soit la
     *       quête suivante est acceptée automatiquement (via {@link QuestProgressEngine#accept},
     *       idempotent) et devient l'étape à tester.</li>
     * </ol>
     *
     * <p>Le {@link CompletableFuture} ne se résout qu'une fois l'acceptation de la quête suivante
     * réglée — pour que {@link #adminComplete}, qui boucle sur cette méthode, ne parte jamais dans
     * une itération suivante avant que l'état soit stable.</p>
     */
    public CompletableFuture<StoryAdvanceReport> adminAdvance(Player player, String storyId) {
        Optional<StoryDefinition> storyOpt = registry.find(storyId);
        if (storyOpt.isEmpty()) {
            return CompletableFuture.completedFuture(StoryAdvanceReport.unknownStory(storyId));
        }
        StoryDefinition story = storyOpt.get();
        int total = story.questIds().size();
        UUID playerId = player.getUniqueId();
        CompletableFuture<StoryAdvanceReport> result = new CompletableFuture<>();

        progressRepository.find(playerId, storyId).thenAccept(existing -> {
            StoryState state = existing.map(StoryProgressRecord::state).orElse(StoryState.NOT_STARTED);
            if (state == StoryState.COMPLETED) {
                result.complete(StoryAdvanceReport.alreadyCompleted(storyId, total));
                return;
            }
            boolean wasStarted = state == StoryState.NOT_STARTED;
            int startIndex = wasStarted ? 0 : Math.min(Math.max(existing.get().currentIndex(), 0), total - 1);

            CompletableFuture<Void> prep = wasStarted
                    ? profileRepository.findOrCreate(playerId, player.getName())
                            .thenCompose(profile -> progressRepository.upsertProgress(playerId, storyId, StoryState.ACTIVE, 0))
                    : CompletableFuture.completedFuture(null);

            prep.thenRun(() -> runOnMainThread(() -> {
                if (!player.isOnline()) {
                    result.complete(StoryAdvanceReport.unknownStory(storyId)); // cible partie entre-temps : traité comme échec neutre.
                    return;
                }
                Map<String, ActiveStoryProgress> active = activeByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                if (wasStarted) {
                    active.put(storyId, new ActiveStoryProgress(storyId, 0));
                    showStoryStarted(player, story);
                }
                ActiveStoryProgress progress = active.computeIfAbsent(storyId, k -> new ActiveStoryProgress(storyId, startIndex));
                int index = progress.currentIndex();
                NamespacedKey currentQuestId = story.questIds().get(index);

                questProgressEngine.forceComplete(player, currentQuestId).thenAccept(complete -> runOnMainThread(() ->
                        onCurrentQuestForced(player, story, progress, index, currentQuestId, complete, wasStarted, total, result)))
                        .exceptionally(error -> {
                            logger.error("[admin] story advance « {} » : échec de forceComplete({}) pour {}", storyId, currentQuestId, playerId, error);
                            result.completeExceptionally(error);
                            return null;
                        });
            }));
        }).exceptionally(error -> {
            logger.error("[admin] story advance « {} » pour {} : lecture de progression impossible", storyId, playerId, error);
            result.completeExceptionally(error);
            return null;
        });

        return result;
    }

    private void onCurrentQuestForced(Player player, StoryDefinition story, ActiveStoryProgress progress, int index,
                                      NamespacedKey currentQuestId, CompleteOutcome complete, boolean wasStarted,
                                      int total, CompletableFuture<StoryAdvanceReport> result) {
        if (complete == CompleteOutcome.UNKNOWN_QUEST) {
            logger.warn("[admin] story advance « {} » : la quête courante « {} » est inconnue du moteur de quête.",
                    story.id(), currentQuestId);
            result.complete(StoryAdvanceReport.unknownCurrentQuest(story.id(), currentQuestId, index, total));
            return;
        }
        boolean questWasAlreadyDone = complete == CompleteOutcome.ALREADY_COMPLETED;
        int nextIndex = index + 1;
        UUID playerId = player.getUniqueId();

        // Avancer l'objet partagé AVANT toute chose : la garde de advanceStory (chemin réactif) voit
        // alors que progress ne pointe plus vers currentQuestId et s'annule d'elle-même.
        boolean weAdvance = progress.currentIndex() == index;
        if (weAdvance) {
            progress.setCurrentIndex(nextIndex);
        }

        if (nextIndex >= total) {
            if (weAdvance) {
                Map<String, ActiveStoryProgress> active = activeByPlayer.get(playerId);
                if (active != null) {
                    active.remove(story.id());
                }
                progressRepository.upsertProgress(playerId, story.id(), StoryState.COMPLETED, nextIndex).exceptionally(error -> {
                    logger.error("[admin] story advance : impossible de persister la complétion de « {} » pour {}", story.id(), playerId, error);
                    return null;
                });
                logger.info("[admin] story advance « {} » : TERMINÉE pour {} — quête {} complétée{}.",
                        story.id(), playerId, currentQuestId, questWasAlreadyDone ? " (déjà faite)" : "");
                showStoryCompleted(player, story);
            }
            result.complete(StoryAdvanceReport.storyCompleted(story.id(), currentQuestId, questWasAlreadyDone, wasStarted, total));
            return;
        }

        NamespacedKey nextQuestId = story.questIds().get(nextIndex);
        if (!weAdvance) {
            // Le chemin réactif a déjà fait avancer la story (et accepté la quête suivante) : rien à
            // refaire, on rapporte juste l'état déterministe (exactement une étape franchie).
            result.complete(StoryAdvanceReport.advanced(story.id(), currentQuestId, questWasAlreadyDone, wasStarted, nextQuestId, nextIndex, total));
            return;
        }

        progressRepository.upsertProgress(playerId, story.id(), StoryState.ACTIVE, nextIndex).exceptionally(error -> {
            logger.error("[admin] story advance : impossible de persister l'avancement de « {} » pour {}", story.id(), playerId, error);
            return null;
        });
        logger.info("[admin] story advance « {} » : {} → étape {}/{} ({}) pour {} — quête {} complétée{}.",
                story.id(), currentQuestId, nextIndex + 1, total, nextQuestId, playerId,
                currentQuestId, questWasAlreadyDone ? " (déjà faite)" : "");
        showNextObjective(player, nextQuestId);
        questProgressEngine.accept(player, nextQuestId).whenComplete((outcome, error) -> {
            if (error == null && outcome.result() != AcceptOutcome.Result.ACCEPTED
                    && outcome.result() != AcceptOutcome.Result.ALREADY_ACTIVE) {
                logger.warn("[admin] story advance « {} » : acceptation automatique de « {} » → {}.",
                        story.id(), nextQuestId, outcome.result());
            }
            result.complete(StoryAdvanceReport.advanced(story.id(), currentQuestId, questWasAlreadyDone, wasStarted, nextQuestId, nextIndex, total));
        });
    }

    public enum StoryCompleteOutcome {
        COMPLETED, ALREADY_COMPLETED, UNKNOWN_STORY,
        /** Une quête de la chaîne est inconnue du moteur de quête — arrêt propre à cette étape. */
        BLOCKED
    }

    public record StoryCompleteReport(StoryCompleteOutcome outcome, String storyId,
                                      List<NamespacedKey> completedQuests, NamespacedKey blockedOnQuestId) {
    }

    /**
     * Complète {@code storyId} de bout en bout pour {@code player} en enchaînant {@link #adminAdvance}
     * jusqu'à {@code STORY_COMPLETED} (ou {@code ALREADY_COMPLETED}), une quête à la fois et dans
     * l'ordre. Chaque quête voit ses récompenses appliquées une seule fois (garde de {@code
     * forceComplete}). Borné à {@code totalSteps + 1} itérations — jamais de boucle infinie.
     */
    public CompletableFuture<StoryCompleteReport> adminComplete(Player player, String storyId) {
        Optional<StoryDefinition> storyOpt = registry.find(storyId);
        if (storyOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new StoryCompleteReport(StoryCompleteOutcome.UNKNOWN_STORY, storyId, List.of(), null));
        }
        int maxIterations = storyOpt.get().questIds().size() + 1;
        return adminCompleteLoop(player, storyId, new ArrayList<>(), 0, maxIterations);
    }

    private CompletableFuture<StoryCompleteReport> adminCompleteLoop(Player player, String storyId,
                                                                     List<NamespacedKey> completed, int iteration, int max) {
        if (iteration >= max) {
            logger.warn("[admin] story complete « {} » : arrêt de sécurité après {} itérations pour {}.",
                    storyId, iteration, player.getUniqueId());
            return CompletableFuture.completedFuture(
                    new StoryCompleteReport(StoryCompleteOutcome.BLOCKED, storyId, completed, null));
        }
        return adminAdvance(player, storyId).thenCompose(report -> switch (report.outcome()) {
            case UNKNOWN_STORY -> CompletableFuture.completedFuture(
                    new StoryCompleteReport(StoryCompleteOutcome.UNKNOWN_STORY, storyId, completed, null));
            case ALREADY_COMPLETED -> CompletableFuture.completedFuture(
                    new StoryCompleteReport(StoryCompleteOutcome.ALREADY_COMPLETED, storyId, completed, null));
            case UNKNOWN_CURRENT_QUEST -> CompletableFuture.completedFuture(
                    new StoryCompleteReport(StoryCompleteOutcome.BLOCKED, storyId, completed, report.completedQuestId()));
            case ADVANCED -> {
                completed.add(report.completedQuestId());
                yield adminCompleteLoop(player, storyId, completed, iteration + 1, max);
            }
            case STORY_COMPLETED -> {
                completed.add(report.completedQuestId());
                yield CompletableFuture.completedFuture(
                        new StoryCompleteReport(StoryCompleteOutcome.COMPLETED, storyId, completed, null));
            }
        });
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
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
