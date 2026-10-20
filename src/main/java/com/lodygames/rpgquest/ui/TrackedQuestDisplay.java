package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Bossbar (Adventure, native, pas de scoreboard legacy) affichant la quête
 * suivie et la progression de son étape courante. Optionnelle : contrôlée
 * par {@code config.yml} → {@code journal.tracker-enabled}. Purement
 * cosmétique — désactivée, le suivi lui-même (persisté en base) continue de
 * fonctionner, seul l'affichage disparaît.
 */
final class TrackedQuestDisplay {

    private final boolean enabled;
    private final Map<UUID, BossBar> activeBars = new ConcurrentHashMap<>();

    TrackedQuestDisplay(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Affiche (ou met à jour) la bossbar. Composition déléguée à {@link TrackedQuestSummary}
     * (issue #71) : résumé borné, jamais d'identifiant technique ni de liste d'objectifs concaténée.
     */
    void update(Player player, QuestDefinition quest, QuestStepProgressView stepView,
                List<QuestObjective> objectives, Function<String, Optional<String>> npcNames) {
        if (!enabled) {
            return;
        }
        TrackedQuestSummary.Summary summary = TrackedQuestSummary.compose(quest, stepView, objectives, npcNames);
        Component title = summary.name();
        float progress = summary.progress();

        BossBar bar = activeBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            activeBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    void clear(Player player) {
        BossBar bar = activeBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    void clearAll() {
        activeBars.clear();
    }
}
