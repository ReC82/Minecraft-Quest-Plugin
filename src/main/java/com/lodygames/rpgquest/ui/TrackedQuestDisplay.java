package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.ArrayList;
import java.util.List;
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
        // Retour joueur 2026-10-04 (issue #157) : gabarit mal formé -- une balise </gray>
        // supplémentaire (sans ouverture correspondante) était rendue littéralement par
        // MiniMessage au lieu d'être un simple formatage, exactement comme rapporté en jeu.
        Component title = MM.deserialize("<gold><quest></gold> <gray>— <step></gray>",
                Placeholder.parsed("quest", quest.title().base()),
                Placeholder.unparsed("step", describeStep(stepView)));
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

    /**
     * Libellé humain de l'étape (issue #157) -- jamais l'id technique (ex. {@code kill_spiders})
     * affiché tel quel. Construit à partir des descriptions déjà humanisées de ses objectifs
     * ({@link ObjectiveProgressView#description()}, voir {@code QuestObjective#describe}) ; ne
     * modifie jamais {@code stepId()} lui-même, qui reste l'identité technique interne.
     */
    private static String describeStep(QuestStepProgressView stepView) {
        List<String> labels = new ArrayList<>();
        for (ObjectiveProgressView objective : stepView.objectives()) {
            if (objective.description() != null && !objective.description().isBlank()) {
                labels.add(objective.description());
            }
        }
        return labels.isEmpty() ? stepView.stepId() : String.join(" · ", labels);
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
