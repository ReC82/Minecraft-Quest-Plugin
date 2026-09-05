package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.StoryService.StoryInfo;
import com.lodygames.rpgquest.story.model.StoryState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.slf4j.Logger;

/**
 * Interface compacte du « Journal des quêtes » ({@link RpgItemKeys#JOURNAL_QUETES}) : clic droit
 * avec l'objet envoie dans le chat un résumé bref — aventures (stories) {@code ACTIVE} avec leur
 * progression et leur quête courante, autres quêtes {@code ACTIVE}/{@code READY_TO_TURN_IN} avec
 * leurs objectifs et leur avancement. Volontairement du chat plutôt qu'un inventaire plein écran :
 * l'objet est consultable en déplacement, sans bloquer la vue, et aucune commande {@code /quest}
 * n'est nécessaire (mission « boucle joueur », UX 100 % sans commande).
 *
 * <p><strong>Stories/quêtes secrètes</strong> : une story secrète non encore découverte
 * ({@code secret} et état {@code NOT_STARTED}) n'apparaît jamais — le journal ne liste de toute
 * façon que ce qui est {@code ACTIVE}, mais le filtre est explicite pour rester correct si une
 * story secrète est marquée active par un outil d'admin. Idem pour une quête secrète non commencée.</p>
 */
public final class QuestJournalBookService implements PluginService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlCustomItemRegistry customItemRegistry;
    private final QuestProgressEngine questProgressEngine;
    private final YamlQuestEngine questEngine;
    private final StoryService storyService;
    private final Logger logger;

    public QuestJournalBookService(RPGQuestPlugin plugin, YamlCustomItemRegistry customItemRegistry,
                                    QuestProgressEngine questProgressEngine, YamlQuestEngine questEngine,
                                    StoryService storyService) {
        this.plugin = plugin;
        this.customItemRegistry = customItemRegistry;
        this.questProgressEngine = questProgressEngine;
        this.questEngine = questEngine;
        this.storyService = storyService;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        // Rien à démarrer : l'écouteur fait tout le travail.
    }

    @Override
    public void stop() {
        // Aucune ressource à libérer.
    }

    public Listener listener() {
        return new QuestJournalBookListener(this);
    }

    boolean isJournal(org.bukkit.inventory.ItemStack stack) {
        return stack != null && customItemRegistry.identify(stack).map(RpgItemKeys.JOURNAL_QUETES::equals).orElse(false);
    }

    /** Construit puis envoie le résumé dans le chat du joueur. Toujours réexécuté sur le thread principal. */
    void open(Player player) {
        UUID playerId = player.getUniqueId();
        CompletableFuture<List<StoryInfo>> storiesFuture = storyService.info(playerId);
        CompletableFuture<Map<NamespacedKey, QuestState>> statesFuture = questProgressEngine.allStates(playerId);
        storiesFuture.thenCombine(statesFuture, DigestData::new)
                .thenAccept(data -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        buildDigest(playerId, data).forEach(player::sendMessage);
                    }
                }))
                .exceptionally(error -> {
                    logger.error("Impossible de construire le Journal des quêtes pour {}", playerId, error);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(MM.deserialize("<red>Impossible d'ouvrir le journal pour le moment.</red>"));
                        }
                    });
                    return null;
                });
    }

    record DigestData(List<StoryInfo> stories, Map<NamespacedKey, QuestState> states) {
    }

    /**
     * Résumé sous forme de lignes de chat. Package-private et sans effet de bord (ne lit que l'état
     * en mémoire de {@code questProgressEngine} via {@link QuestProgressEngine#activeStepView}) pour
     * être directement testable.
     */
    List<Component> buildDigest(UUID playerId, DigestData data) {
        List<Component> lines = new ArrayList<>();
        lines.add(MM.deserialize("<gold><bold>▶ Journal des quêtes</bold></gold>"));

        Set<NamespacedKey> storyCurrentQuests = new HashSet<>();
        List<StoryInfo> activeStories = data.stories().stream()
                .filter(info -> info.state() == StoryState.ACTIVE)
                .filter(info -> !(info.story().secret() && info.state() == StoryState.NOT_STARTED))
                .toList();

        if (activeStories.isEmpty()) {
            lines.add(MM.deserialize("<gray>Aucune aventure en cours.</gray>"));
        } else {
            lines.add(MM.deserialize("<yellow>Aventures en cours :</yellow>"));
            for (StoryInfo info : activeStories) {
                int total = info.story().questIds().size();
                int position = Math.min(info.currentIndex() + 1, total);
                NamespacedKey currentQuestId = info.story().questIds().get(Math.min(info.currentIndex(), total - 1));
                storyCurrentQuests.add(currentQuestId);
                String questTitle = questEngine.find(currentQuestId).map(q -> q.title().base()).orElse(currentQuestId.toString());
                lines.add(MM.deserialize(
                        "<white> • <story></white> <gray>(<pos>/<total>)</gray> <dark_gray>—</dark_gray> <aqua><quest></aqua>",
                        Placeholder.parsed("story", info.story().name().base()),
                        Placeholder.unparsed("pos", String.valueOf(position)),
                        Placeholder.unparsed("total", String.valueOf(total)),
                        Placeholder.parsed("quest", questTitle)));
                appendObjectives(lines, playerId, currentQuestId, data.states().getOrDefault(currentQuestId, QuestState.NOT_STARTED));
            }
        }

        List<NamespacedKey> otherActive = new ArrayList<>();
        for (Map.Entry<NamespacedKey, QuestState> entry : data.states().entrySet()) {
            QuestState state = entry.getValue();
            if (state != QuestState.ACTIVE && state != QuestState.READY_TO_TURN_IN) {
                continue;
            }
            if (storyCurrentQuests.contains(entry.getKey())) {
                continue;
            }
            Optional<QuestDefinition> quest = questEngine.find(entry.getKey());
            if (quest.isPresent() && quest.get().secret() && state == QuestState.NOT_STARTED) {
                continue; // défensif : jamais lister une quête secrète non découverte.
            }
            otherActive.add(entry.getKey());
        }

        if (!otherActive.isEmpty()) {
            lines.add(MM.deserialize("<yellow>Autres quêtes actives :</yellow>"));
            for (NamespacedKey questId : otherActive) {
                QuestState state = data.states().getOrDefault(questId, QuestState.ACTIVE);
                String title = questEngine.find(questId).map(q -> q.title().base()).orElse(questId.toString());
                String suffix = state == QuestState.READY_TO_TURN_IN ? " <gold>[Prête à remettre]</gold>" : "";
                lines.add(MM.deserialize("<white> • <quest></white>" + suffix, Placeholder.parsed("quest", title)));
                appendObjectives(lines, playerId, questId, state);
            }
        }

        if (activeStories.isEmpty() && otherActive.isEmpty()) {
            lines.add(MM.deserialize("<gray>Va voir le Guide ou le Libraire pour commencer.</gray>"));
        }
        return lines;
    }

    private void appendObjectives(List<Component> lines, UUID playerId, NamespacedKey questId, QuestState state) {
        if (state == QuestState.READY_TO_TURN_IN) {
            lines.add(MM.deserialize("<green>   ✔ Objectifs remplis — va rendre la quête.</green>"));
            return;
        }
        Optional<QuestStepProgressView> view = questProgressEngine.activeStepView(playerId, questId);
        if (view.isEmpty()) {
            return;
        }
        for (ObjectiveProgressView objective : view.get().objectives()) {
            lines.add(MM.deserialize("<gray>   - <desc> (<current>/<total>)</gray>",
                    Placeholder.unparsed("desc", objective.description()),
                    Placeholder.unparsed("current", String.valueOf(objective.current())),
                    Placeholder.unparsed("total", String.valueOf(objective.total()))));
        }
    }
}
