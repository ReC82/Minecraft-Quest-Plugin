package com.lodygames.rpgquest.npc.hint;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.NpcHintConfig;
import com.lodygames.rpgquest.database.DialogueReadRepository;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Signal visuel discret au-dessus d'un PNJ (issue #12) : <strong>propre à chaque joueur</strong>,
 * pour une quête réellement disponible ou un dialogue accessible jamais lu.
 *
 * <h2>Ce qui est garanti, et comment</h2>
 *
 * <p><strong>Spécifique au joueur.</strong> Les particules partent par
 * {@code Player#spawnParticle} : seul le destinataire les voit. Deux joueurs devant le même PNJ
 * voient donc des états différents, et le PNJ lui-même n'est jamais modifié — ni son nom, ni son
 * équipement, ni rien de global.</p>
 *
 * <p><strong>Aucune règle dupliquée.</strong> La disponibilité d'une quête vient de
 * {@link QuestProgressEngine#availability}, qui est exactement ce que {@code accept()} utilise
 * pour refuser. Les conditions de dialogue viennent de
 * {@code DialogueSessionEngine#reachableNodes}, qui réutilise l'évaluateur de conditions du moteur
 * de dialogue. Une quête verrouillée, active ou terminée non répétable ne peut donc pas être
 * annoncée comme nouvelle.</p>
 *
 * <h2>Coût maîtrisé</h2>
 *
 * <p>Trois mécanismes, parce qu'un signal au-dessus de PNJ est le genre de fonctionnalité qui
 * dégrade un serveur si on la code naïvement :</p>
 * <ol>
 *   <li><strong>Jamais de boucle par tick.</strong> Une passe d'affichage toutes
 *       {@code period-ticks} (1 s par défaut), et <strong>par joueur connecté</strong> — jamais un
 *       balayage de tous les PNJ du monde.</li>
 *   <li><strong>Jamais de PNJ distant.</strong> Les candidats viennent de
 *       {@code Player#getNearbyEntities} dans le rayon configuré : un PNJ hors rayon, dans un
 *       autre monde ou non chargé n'est ni calculé ni affiché. Aucun chunk n'est chargé pour
 *       l'occasion.</li>
 *   <li><strong>Calcul découplé de l'affichage.</strong> L'état d'un joueur est recalculé au plus
 *       une fois par {@code refresh-seconds}, en asynchrone, et l'affichage se contente de lire ce
 *       cache. Le cache est invalidé immédiatement quand quelque chose de pertinent change
 *       (acceptation de quête, progression, lecture d'un nœud), pour que le signal suive sans
 *       attendre.</li>
 * </ol>
 *
 * <h2>Hors périmètre de cette première version</h2>
 *
 * <p>« Quête prête à être rendue » n'est <strong>pas</strong> signalé, et ce n'est pas un oubli :
 * {@code QuestState.READY_TO_TURN_IN} existe dans l'énumération mais n'est jamais un état
 * observable — {@code QuestProgressEngine#turnIn} le pose puis le remplace par {@code COMPLETED}
 * dans la même méthode, et seul {@code COMPLETED} est persisté. Le signaler supposerait d'inventer
 * une mécanique de remise, ce que le ticket interdit explicitement.</p>
 */
public final class NpcHintService implements com.lodygames.rpgquest.bootstrap.PluginService {

    /** Nature du signal. L'ordre de l'énumération est l'ordre de priorité. */
    public enum Hint {
        /** Une quête est réellement disponible auprès de ce PNJ. */
        QUEST,
        /** Un dialogue accessible de ce PNJ comporte un nœud jamais lu. */
        DIALOGUE
    }

    private final RPGQuestPlugin plugin;
    private final NpcHintConfig config;
    private final NpcIdentityService npcIdentityService;
    private final YamlQuestEngine questEngine;
    private final QuestProgressEngine questProgressEngine;
    private final YamlDialogueEngine dialogueEngine;
    private final DialogueReadRepository readRepository;
    private final ReachabilityProvider reachability;
    private final Logger logger;

    /** Dernier état calculé par joueur. Lu par la passe d'affichage, jamais recalculé dedans. */
    private final Map<UUID, PlayerHints> hints = new ConcurrentHashMap<>();
    /** Nœuds déjà lus, en mémoire, par joueur — évite toute requête SQL dans la boucle. */
    private final Map<UUID, Set<String>> readNodes = new ConcurrentHashMap<>();

    private BukkitTask task;

    /**
     * Fournit les nœuds atteignables d'un dialogue pour un joueur. Abstrait pour que le service
     * soit testable sans moteur de dialogue réel ni serveur.
     */
    public interface ReachabilityProvider {
        CompletableFuture<Set<String>> reachableNodes(Player player, DialogueDefinition dialogue);
    }

    public NpcHintService(RPGQuestPlugin plugin, NpcHintConfig config,
                          NpcIdentityService npcIdentityService, YamlQuestEngine questEngine,
                          QuestProgressEngine questProgressEngine, YamlDialogueEngine dialogueEngine,
                          DialogueReadRepository readRepository, ReachabilityProvider reachability,
                          Logger logger) {
        this.plugin = plugin;
        this.config = config.bounded();
        this.npcIdentityService = npcIdentityService;
        this.questEngine = questEngine;
        this.questProgressEngine = questProgressEngine;
        this.dialogueEngine = dialogueEngine;
        this.readRepository = readRepository;
        this.reachability = reachability;
        this.logger = logger;
    }

    /** État calculé d'un joueur : un signal par PNJ, plus un verrou anti-recalcul concurrent. */
    private static final class PlayerHints {
        final Map<String, Hint> byNpcId = new ConcurrentHashMap<>();
        final AtomicBoolean computing = new AtomicBoolean(false);
        volatile long computedAtMillis;
    }

    public NpcHintConfig config() {
        return config;
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            logger.info("Signal visuel sur les PNJ désactivé (npc-hints.enabled = false).");
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::displayPass,
                config.periodTicks(), config.periodTicks());
        logger.info("Signal visuel sur les PNJ actif : passe toutes les {} ticks, rayon {} blocs, "
                        + "recalcul au plus toutes les {} s.",
                config.periodTicks(), config.radius(), config.refreshSeconds());
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        hints.clear();
        readNodes.clear();
    }

    // ---- Cycle de vie du joueur ------------------------------------------------------------

    /**
     * À la connexion : charge l'état de lecture une seule fois. Tant qu'il n'est pas chargé, aucun
     * signal de dialogue n'est émis pour ce joueur — mieux vaut ne rien montrer qu'annoncer « non
     * lu » un contenu déjà lu.
     */
    public void onJoin(Player player) {
        if (!config.enabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        readRepository.allForPlayer(playerId).thenAccept(nodes -> {
            Set<String> keys = ConcurrentHashMap.newKeySet();
            nodes.forEach(n -> keys.add(readKey(n.dialogueId(), n.nodeId())));
            readNodes.put(playerId, keys);
            invalidate(playerId);
        }).exceptionally(error -> {
            logger.warn("Lecture des dialogues déjà lus impossible pour {} : aucun signal de "
                    + "dialogue ne sera affiché pour cette session.", playerId, error);
            return null;
        });
    }

    /** À la déconnexion : tout est oublié côté mémoire. La base garde la lecture. */
    public void onQuit(UUID playerId) {
        hints.remove(playerId);
        readNodes.remove(playerId);
    }

    /** Changement de monde : l'état calculé ne vaut plus rien, les PNJ proches ont changé. */
    public void onWorldChange(UUID playerId) {
        invalidate(playerId);
    }

    /**
     * Un nœud vient d'être <strong>réellement affiché</strong> : on le note, en mémoire pour le
     * signal immédiat et en base pour la persistance. Branché sur le moteur de dialogue.
     */
    public void onNodePresented(Player player, String dialogueId, String nodeId) {
        if (!config.enabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        readNodes.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                .add(readKey(dialogueId, nodeId));
        invalidate(playerId);
        readRepository.markRead(playerId, dialogueId, nodeId).exceptionally(error -> {
            logger.warn("Lecture du nœud {} du dialogue {} non persistée pour {} : le signal "
                    + "pourra réapparaître après reconnexion.", nodeId, dialogueId, playerId, error);
            return null;
        });
    }

    /** Quelque chose a changé pour ce joueur (quête acceptée, progression, prérequis acquis). */
    public void invalidate(UUID playerId) {
        PlayerHints state = hints.get(playerId);
        if (state != null) {
            state.computedAtMillis = 0L;
        }
    }

    // ---- Passe d'affichage -----------------------------------------------------------------

    /**
     * Une passe : pour chaque joueur connecté, afficher le signal des PNJ proches depuis le cache,
     * et demander un recalcul si le cache est périmé. Rien de bloquant ici.
     */
    void displayPass() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            PlayerHints state = hints.computeIfAbsent(playerId, k -> new PlayerHints());

            List<NearbyNpc> nearby = nearbyNpcs(player);
            if (stale(state)) {
                scheduleRecompute(player, state, nearby);
            }
            if (state.byNpcId.isEmpty()) {
                continue;
            }
            for (NearbyNpc npc : nearby) {
                Hint hint = state.byNpcId.get(npc.npcId());
                if (hint != null) {
                    show(player, npc.entity(), hint);
                }
            }
        }
    }

    private boolean stale(PlayerHints state) {
        return System.currentTimeMillis() - state.computedAtMillis
                >= config.refreshSeconds() * 1000L;
    }

    /** PNJ RPGQuest proches : même monde, dans le rayon, et réellement visibles si demandé. */
    private List<NearbyNpc> nearbyNpcs(Player player) {
        List<NearbyNpc> found = new ArrayList<>();
        double r = config.radius();
        for (Entity entity : player.getNearbyEntities(r, r, r)) {
            if (!entity.isValid()) {
                continue;
            }
            Optional<String> npcId = npcIdentityService.currentId(entity);
            if (npcId.isEmpty()) {
                continue;
            }
            if (config.requireLineOfSight() && !player.hasLineOfSight(entity)) {
                continue;
            }
            found.add(new NearbyNpc(npcId.get(), entity));
        }
        return found;
    }

    /** Un PNJ proche : son identité logique RPGQuest et l'entité qui le porte. */
    private record NearbyNpc(String npcId, Entity entity) {
    }

    private void show(Player player, Entity npc, Hint hint) {
        Location at = npc.getLocation().clone().add(0, npc.getHeight() + config.heightOffset(), 0);
        // spawnParticle sur le JOUEUR : personne d'autre ne voit ce signal.
        player.spawnParticle(hint == Hint.QUEST ? config.questParticle() : config.dialogueParticle(),
                at, config.count(), 0.08, 0.04, 0.08, 0.0);
    }

    // ---- Recalcul ---------------------------------------------------------------------------

    /**
     * Recalcule l'état du joueur pour les PNJ proches, en asynchrone, et un seul recalcul à la
     * fois : la passe d'affichage suivante ne doit pas en empiler un deuxième.
     */
    private void scheduleRecompute(Player player, PlayerHints state, List<NearbyNpc> nearby) {
        if (!state.computing.compareAndSet(false, true)) {
            return;
        }
        // Les identités sont copiées : la suite s'exécute hors du thread principal, et on ne doit
        // jamais toucher à des entités Bukkit depuis là.
        Set<String> npcIds = new HashSet<>();
        nearby.forEach(n -> npcIds.add(n.npcId()));

        compute(player, npcIds)
                .thenAccept(computed -> {
                    state.byNpcId.keySet().retainAll(computed.keySet());
                    state.byNpcId.putAll(computed);
                    state.computedAtMillis = System.currentTimeMillis();
                })
                .exceptionally(error -> {
                    logger.warn("Calcul du signal visuel impossible pour {} : signal inchangé.",
                            player.getUniqueId(), error);
                    // On retarde la prochaine tentative, pour ne pas boucler sur une erreur.
                    state.computedAtMillis = System.currentTimeMillis();
                    return null;
                })
                .whenComplete((v, e) -> state.computing.set(false));
    }

    /** État de chaque PNJ demandé. La quête prime sur le dialogue si les deux s'appliquent. */
    CompletableFuture<Map<String, Hint>> compute(Player player, Set<String> npcIds) {
        if (npcIds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, Hint> result = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> work = new ArrayList<>();

        for (String npcId : npcIds) {
            work.add(questHint(player, npcId).thenCompose(hasQuest -> {
                if (hasQuest) {
                    result.put(npcId, Hint.QUEST);
                    // Priorité quête : inutile d'évaluer le dialogue, et une passe de moins.
                    return CompletableFuture.completedFuture(null);
                }
                return dialogueHint(player, npcId).thenAccept(hasDialogue -> {
                    if (hasDialogue) {
                        result.put(npcId, Hint.DIALOGUE);
                    }
                });
            }));
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))
                .thenApply(v -> Map.copyOf(result));
    }

    /** Une quête dont ce PNJ est le donneur est-elle réellement disponible pour ce joueur ? */
    private CompletableFuture<Boolean> questHint(Player player, String npcId) {
        List<QuestDefinition> given = questEngine.quests().stream()
                .filter(q -> npcId.equalsIgnoreCase(q.giver()))
                .toList();
        if (given.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        List<CompletableFuture<QuestProgressEngine.Availability>> checks = given.stream()
                .map(q -> questProgressEngine.availability(player.getUniqueId(), q.id(), false))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(v -> checks.stream().map(CompletableFuture::join)
                        .anyMatch(QuestProgressEngine.Availability::available));
    }

    /** Ce PNJ a-t-il un dialogue dont un nœud atteignable n'a jamais été lu ? */
    private CompletableFuture<Boolean> dialogueHint(Player player, String npcId) {
        Set<String> read = readNodes.get(player.getUniqueId());
        if (read == null) {
            // État de lecture pas encore chargé : ne rien annoncer plutôt que de se tromper.
            return CompletableFuture.completedFuture(false);
        }
        Optional<DialogueDefinition> dialogue = dialogueEngine.find(dialogueKeyFor(npcId));
        if (dialogue.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        DialogueDefinition definition = dialogue.get();
        String dialogueId = definition.id().toString();
        return reachability.reachableNodes(player, definition)
                .thenApply(nodes -> nodes.stream()
                        .anyMatch(nodeId -> !read.contains(readKey(dialogueId, nodeId))));
    }

    /**
     * Convention du projet (bible § 4) : cliquer un PNJ identifié {@code X} ouvre
     * {@code rpgquest:X}. Le signal suit exactement la même règle — sinon il annoncerait un
     * dialogue que le clic n'ouvre pas.
     */
    private org.bukkit.NamespacedKey dialogueKeyFor(String npcId) {
        return new org.bukkit.NamespacedKey("rpgquest", npcId.toLowerCase(java.util.Locale.ROOT));
    }

    private static String readKey(String dialogueId, String nodeId) {
        return dialogueId + "#" + nodeId;
    }
}
