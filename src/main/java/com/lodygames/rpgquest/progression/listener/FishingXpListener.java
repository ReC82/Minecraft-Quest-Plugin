package com.lodygames.rpgquest.progression.listener;

import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;

/** Accorde de l'XP de pêche à chaque prise réelle ({@code State.CAUGHT_FISH}, jamais un simple lancer/ferrage manqué). */
public final class FishingXpListener implements Listener {

    private final ProgressionService progression;
    private final Supplier<ProgressionConfig> config;
    private final AtomicLong sequence = new AtomicLong();

    public FishingXpListener(ProgressionService progression, Supplier<ProgressionConfig> config) {
        this.progression = progression;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        ProgressionConfig current = config.get();
        if (event.isCancelled() || current.fishingCatchXp() <= 0) {
            return;
        }
        if (!current.keepVanillaXp()) {
            event.setExpToDrop(0);
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        Player player = event.getPlayer();
        String eventId = "fishing:" + player.getUniqueId() + ":" + sequence.incrementAndGet();
        progression.awardXp(player.getUniqueId(), SkillType.FISHING, current.fishingCatchXp(), "fishing_catch", eventId);
    }
}
