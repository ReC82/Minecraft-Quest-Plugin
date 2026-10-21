package com.lodygames.rpgquest.quest.progress;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;

/**
 * Issue #636 — « un dialogue PNJ a le focus de ce joueur » : tant que c'est vrai, une annonce de
 * quête n'affiche aucun grand titre par-dessus la fenêtre. Elle est mise en attente, signalée par
 * une ligne courte dans le prochain nœud affiché, et son bilan de récompenses part dans le chat à
 * la fin du dialogue.
 *
 * <p>Logique pure (horloge injectée, aucun appel Bukkit) : le cycle de vie se teste sans serveur.
 * Toutes les méthodes sont appelées depuis le thread principal ; la synchronisation n'est qu'une
 * ceinture de sécurité.</p>
 *
 * <p>Fermer une fenêtre Paper Dialog avec Échap n'envoie rien au serveur : la fin du dialogue
 * n'est donc pas toujours observable. Le focus se termine sur le premier signal fiable — fermeture
 * par un choix, déplacement d'un bloc après la fenêtre ouverte, téléportation, déconnexion — et,
 * à défaut, à l'expiration d'un délai de garde renouvelé à chaque nœud affiché.</p>
 */
public final class DialogueFocus {

    /** Clic sur un PNJ à dialogue : couvre l'ouverture asynchrone de la fenêtre (conditions lues en base). */
    static final Duration EXPECTED_HOLD = Duration.ofSeconds(3);
    /** Fenêtre affichée : délai de garde quand Échap la ferme sans qu'aucun autre signal n'arrive. */
    static final Duration SHOWN_HOLD = Duration.ofSeconds(30);
    /** Élan d'un joueur qui courait au moment du clic : ce déplacement-là ne ferme pas la fenêtre. */
    static final Duration MOVE_GRACE = Duration.ofSeconds(1);

    /** Fin de quête retenue pendant un dialogue, annoncée une seule fois à sa fin. */
    public record Deferred(String questTitle, List<Component> rewardLines) {
        public Deferred {
            rewardLines = List.copyOf(rewardLines);
        }
    }

    private record Focus(long until, long shownAt) {
        boolean shown() {
            return shownAt >= 0;
        }
    }

    private final LongSupplier clockMillis;
    private final Map<UUID, Focus> focus = new HashMap<>();
    private final Map<UUID, List<Deferred>> pending = new HashMap<>();
    private final Map<UUID, List<String>> notices = new HashMap<>();

    public DialogueFocus(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Clic sur un PNJ dont le dialogue va s'ouvrir : appelé AVANT la progression {@code TALK_TO_NPC} du même clic. */
    public synchronized void expect(UUID playerId) {
        Focus current = focus.get(playerId);
        long now = clockMillis.getAsLong();
        long until = Math.max(now + EXPECTED_HOLD.toMillis(), current == null ? 0 : current.until());
        focus.put(playerId, new Focus(until, current == null ? -1 : current.shownAt()));
    }

    /** Un nœud vient d'être affiché : le focus est confirmé et son délai de garde renouvelé. */
    public synchronized void shown(UUID playerId) {
        long now = clockMillis.getAsLong();
        focus.put(playerId, new Focus(now + SHOWN_HOLD.toMillis(), now));
    }

    public synchronized boolean isFocused(UUID playerId) {
        Focus current = focus.get(playerId);
        return current != null && current.until() > clockMillis.getAsLong();
    }

    /**
     * Retient la fin de quête si un dialogue a le focus. {@code false} : aucun dialogue, l'appelant
     * annonce normalement (titre compris).
     */
    public synchronized boolean defer(UUID playerId, Deferred completion) {
        if (!isFocused(playerId)) {
            return false;
        }
        pending.computeIfAbsent(playerId, id -> new ArrayList<>()).add(completion);
        notices.computeIfAbsent(playerId, id -> new ArrayList<>()).add(completion.questTitle());
        return true;
    }

    /** Titres des quêtes terminées pas encore signalées dans la fenêtre ; chacune n'est rendue qu'une fois. */
    public synchronized List<String> takeNotices(UUID playerId) {
        List<String> taken = notices.remove(playerId);
        return taken == null ? List.of() : taken;
    }

    /** Fin observée du dialogue : rend les annonces retenues (à envoyer une fois) et libère le focus. */
    public synchronized List<Deferred> end(UUID playerId) {
        focus.remove(playerId);
        notices.remove(playerId);
        List<Deferred> released = pending.remove(playerId);
        return released == null ? List.of() : released;
    }

    /**
     * Déplacement d'un bloc : la fenêtre n'est plus à l'écran (un joueur devant un dialogue ne se
     * déplace pas). Ignoré tant que la fenêtre n'est pas affichée et pendant l'élan qui suit.
     */
    public synchronized boolean endsOnMove(UUID playerId) {
        Focus current = focus.get(playerId);
        return current != null && current.shown()
                && clockMillis.getAsLong() - current.shownAt() >= MOVE_GRACE.toMillis();
    }

    /** Déconnexion : rien n'est rejoué à la reconnexion (les récompenses restent dans le journal). */
    public synchronized void discard(UUID playerId) {
        focus.remove(playerId);
        pending.remove(playerId);
        notices.remove(playerId);
    }

    public synchronized boolean hasPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    /** Délai de garde écoulé sans fin observée : mêmes annonces que {@link #end}, sinon rien. */
    public synchronized List<Deferred> expireIfDue(UUID playerId) {
        return isFocused(playerId) ? List.of() : end(playerId);
    }
}
