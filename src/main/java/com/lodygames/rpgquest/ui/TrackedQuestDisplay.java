package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/**
 * Bossbar (Adventure, native, pas de scoreboard legacy) affichant la quête
 * suivie et la progression de son étape courante. Optionnelle : contrôlée
 * par {@code config.yml} → {@code journal.tracker-enabled}. Purement
 * cosmétique — désactivée, le suivi lui-même (persisté en base) continue de
 * fonctionner, seul l'affichage disparaît.
 */
final class TrackedQuestDisplay {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final boolean enabled;
    private final Map<UUID, BossBar> activeBars = new ConcurrentHashMap<>();

    TrackedQuestDisplay(boolean enabled) {
        this.enabled = enabled;
    }

    void update(Player player, QuestDefinition quest, QuestStepProgressView stepView) {
        if (!enabled) {
            return;
        }
        Component title = MM.deserialize("<gold><quest></gold> <gray>— <step></gray></gray>",
                Placeholder.parsed("quest", quest.title().base()),
                Placeholder.unparsed("step", stepView.stepId()));
        float progress = progressOf(stepView);

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

    private float progressOf(QuestStepProgressView stepView) {
        int current = 0;
        int total = 0;
        for (ObjectiveProgressView objective : stepView.objectives()) {
            current += objective.current();
            total += objective.total();
        }
        if (total <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, current / (float) total));
    }
}
