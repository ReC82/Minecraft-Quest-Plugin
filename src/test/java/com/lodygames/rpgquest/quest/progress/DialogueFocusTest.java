package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** Issue #636 — cycle de vie du focus d'un dialogue PNJ, horloge maîtrisée. */
class DialogueFocusTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final DialogueFocus focus = new DialogueFocus(now::get);
    private final UUID player = UUID.randomUUID();
    private final DialogueFocus.Deferred completion =
            new DialogueFocus.Deferred("Les toiles du sous-bois", List.of(Component.text("+ 10 XP")));

    @Test
    void withoutDialogueNothingIsDeferred() {
        assertFalse(focus.defer(player, completion), "hors dialogue : annonce normale, titre compris");
        assertEquals(List.of(), focus.end(player));
    }

    @Test
    void aCompletionDuringADialogueIsNoticedOnceInTheWindowAndReleasedOnceAtTheEnd() {
        focus.expect(player);
        assertTrue(focus.defer(player, completion));

        assertEquals(List.of("Les toiles du sous-bois"), focus.takeNotices(player));
        assertEquals(List.of(), focus.takeNotices(player), "une seule ligne dans la fenêtre");

        assertEquals(List.of(completion), focus.end(player));
        assertEquals(List.of(), focus.end(player), "jamais annoncé deux fois");
        assertFalse(focus.isFocused(player));
    }

    @Test
    void anExpectedDialogueThatNeverOpensReleasesItsAnnouncementsAfterTheShortHold() {
        focus.expect(player);
        focus.defer(player, completion);

        now.addAndGet(DialogueFocus.EXPECTED_HOLD.toMillis() - 1);
        assertEquals(List.of(), focus.expireIfDue(player));
        now.addAndGet(1);
        assertEquals(List.of(completion), focus.expireIfDue(player));
    }

    @Test
    void eachShownNodeRenewsTheHoldUsedWhenEscapeIsNotObservable() {
        focus.shown(player);
        focus.defer(player, completion);
        now.addAndGet(DialogueFocus.SHOWN_HOLD.toMillis() - 1);
        focus.shown(player);
        now.addAndGet(DialogueFocus.SHOWN_HOLD.toMillis() - 1);
        assertTrue(focus.isFocused(player));
        assertEquals(List.of(), focus.expireIfDue(player));
        now.addAndGet(1);
        assertEquals(List.of(completion), focus.expireIfDue(player));
    }

    @Test
    void movingOnlyEndsTheFocusOnceTheWindowIsShownAndTheMomentumIsOver() {
        focus.expect(player);
        assertFalse(focus.endsOnMove(player), "fenêtre pas encore affichée : élan du clic");
        focus.shown(player);
        assertFalse(focus.endsOnMove(player));
        now.addAndGet(DialogueFocus.MOVE_GRACE.toMillis());
        assertTrue(focus.endsOnMove(player));
    }

    @Test
    void aDisconnectionDropsEverythingAndNothingIsReplayed() {
        focus.shown(player);
        focus.defer(player, completion);
        focus.discard(player);

        assertFalse(focus.hasPending(player));
        assertEquals(List.of(), focus.takeNotices(player));
        assertEquals(List.of(), focus.end(player));
    }
}
