package com.lodygames.rpgquest.feedback;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * Les grands titres de quête (issue #817).
 *
 * <p>Un Title n'a qu'un emplacement : deux quêtes terminées par la même bûche affichaient deux
 * « Quête terminée », et la seconde effaçait la première avant qu'elle soit lisible. La règle :</p>
 *
 * <ul>
 *   <li>une quête terminée est <strong>prioritaire</strong> : affichée tout de suite si l'écran est
 *       libre, sinon <strong>mise en file</strong> et affichée à la fin de la précédente ; au plus
 *       {@value #MAX_QUEUED} en attente, l'attente reste donc bornée (quelques secondes). Au-delà, le
 *       titre est abandonné, jamais l'annonce : le bilan de chaque quête part aussi dans le chat ;</li>
 *   <li>une quête commencée a la priorité la plus basse : son titre n'est pas montré pendant
 *       qu'une fin de quête est affichée ou attendue (ses objectifs partent quand même dans le
 *       chat), et une fin de quête le remplace aussitôt.</li>
 * </ul>
 *
 * <p>À utiliser depuis le thread principal.</p>
 */
public final class QuestTitleQueue {

    /** Nombre maximal de titres de fin de quête en attente derrière celui affiché. */
    public static final int MAX_QUEUED = 3;

    /** Ce qu'il advient d'un titre proposé. */
    public enum Decision { SHOWN, QUEUED, DROPPED }

    private final LongSupplier clock;
    private final BiConsumer<Runnable, Long> later;
    private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();

    /**
     * @param later planifie une tâche sur le thread principal après le délai donné en millisecondes
     */
    public QuestTitleQueue(LongSupplier clock, BiConsumer<Runnable, Long> later) {
        this.clock = clock;
        this.later = later;
    }

    private static final class Slot {
        private long busyUntil;
        private final Deque<Title> queue = new ArrayDeque<>();
    }

    /** Titre de quête terminée : affiché, mis en file ou abandonné (le chat garde le bilan). */
    public Decision completed(Player player, Title title) {
        UUID playerId = player.getUniqueId();
        long now = clock.getAsLong();
        slots.entrySet().removeIf(entry -> entry.getValue().queue.isEmpty() && now >= entry.getValue().busyUntil);
        Slot slot = slots.computeIfAbsent(playerId, id -> new Slot());
        if (slot.queue.isEmpty() && now >= slot.busyUntil) {
            show(player, slot, title, now);
            return Decision.SHOWN;
        }
        if (slot.queue.size() >= MAX_QUEUED) {
            return Decision.DROPPED;
        }
        slot.queue.addLast(title);
        if (slot.queue.size() == 1) {
            scheduleNext(player, slot.busyUntil - now);
        }
        return Decision.QUEUED;
    }

    /** Titre de quête commencée : seulement si aucune fin de quête n'occupe ou n'attend l'écran. */
    public Decision started(Player player, Title title) {
        Slot slot = slots.get(player.getUniqueId());
        if (slot != null && (!slot.queue.isEmpty() || clock.getAsLong() < slot.busyUntil)) {
            return Decision.DROPPED;
        }
        player.showTitle(title);
        return Decision.SHOWN;
    }

    private void show(Player player, Slot slot, Title title, long now) {
        player.showTitle(title);
        slot.busyUntil = now + displayMillis(title);
    }

    private void scheduleNext(Player player, long delayMillis) {
        later.accept(() -> showNext(player), Math.max(1L, delayMillis));
    }

    void showNext(Player player) {
        Slot slot = slots.get(player.getUniqueId());
        if (slot == null || slot.queue.isEmpty()) {
            return;
        }
        Title next = slot.queue.pollFirst();
        if (!player.isOnline()) {
            slots.remove(player.getUniqueId(), slot);
            return;
        }
        show(player, slot, next, clock.getAsLong());
        if (!slot.queue.isEmpty()) {
            scheduleNext(player, slot.busyUntil - clock.getAsLong());
        }
    }

    /** Durée de lecture d'un titre : apparition + affichage (le fondu de sortie peut être recouvert). */
    static long displayMillis(Title title) {
        Title.Times times = title.times();
        if (times == null) {
            return Duration.ofMillis(500).plusMillis(3_500).toMillis(); // valeurs par défaut du client
        }
        return times.fadeIn().plus(times.stay()).toMillis();
    }
}
