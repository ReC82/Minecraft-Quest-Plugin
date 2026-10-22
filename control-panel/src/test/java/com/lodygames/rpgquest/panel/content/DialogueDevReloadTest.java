package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #732 — lecture « Recharger depuis DEV » et ce que le jeu utilise, sans rien deviner. */
class DialogueDevReloadTest {

    private static final String DEV = """
            id: rpgquest:musty
            start: start
            nodes:
              start:
                speaker: "Musty"
                text: "Salut !"
                choices:
                  - text: "Au revoir"
                    actions:
                      - type: CLOSE
            """;

    private static String sha(String text) {
        return ContentWorkspace.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<Map<String, Object>> read(boolean present, String text, String sha) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("present", present);
        d.put("tooLarge", false);
        d.put("sha256", sha);
        d.put("text", text);
        return Optional.of(d);
    }

    @Test
    void eachReadOutcomeHasItsOwnStatusAndOnlyAnIntactCurrentReadCanReplace() {
        assertEquals(DialogueDevReload.Status.PENDING, DialogueDevReload.of(true, null, Optional.empty(), "").status());
        DialogueDevReload failed = DialogueDevReload.of(false, "agent arrêté", Optional.empty(), "");
        assertEquals(DialogueDevReload.Status.FAILED, failed.status());
        assertEquals("agent arrêté", failed.message());
        assertEquals(DialogueDevReload.Status.ABSENT, DialogueDevReload.of(false, null, read(false, "", ""), "").status());
        assertEquals(DialogueDevReload.Status.CORRUPT,
                DialogueDevReload.of(false, null, read(true, DEV, sha("autre")), "").status());
        assertEquals(DialogueDevReload.Status.STALE,
                DialogueDevReload.of(false, null, read(true, DEV, sha(DEV)), sha("plus récent")).status());

        DialogueDevReload ok = DialogueDevReload.of(false, null, read(true, DEV, sha(DEV)), sha(DEV));
        assertTrue(ok.ready());
        assertEquals(DEV, ok.replacement().orElseThrow());
        assertEquals(1, ok.lineCount());
        assertTrue(ok.editorIssues().isEmpty());
        assertFalse(failed.replacement().isPresent());
    }

    @Test
    void inGameIsConfirmedComparedOrUnknownNeverGuessed() {
        DialogueDraft same = DialogueYaml.read(DEV).draft();
        DialogueDraft older = DialogueYaml.read(DEV.replace("Salut !", "Bonjour")).draft();
        assertEquals(DialogueDevReload.InGame.SAME,
                DialogueDevReload.inGame(DEV, sha(DEV), sha(DEV), Optional.empty(), false), "confirmé par la mise en jeu");
        assertEquals(DialogueDevReload.InGame.UNKNOWN,
                DialogueDevReload.inGame(DEV, sha(DEV), "", Optional.of(older), false), "relevé trop ancien");
        assertEquals(DialogueDevReload.InGame.SAME,
                DialogueDevReload.inGame(DEV, sha(DEV), "", Optional.of(same), true));
        assertEquals(DialogueDevReload.InGame.OLDER,
                DialogueDevReload.inGame(DEV, sha(DEV), "", Optional.of(older), true));
        assertEquals(DialogueDevReload.InGame.NOT_LOADED,
                DialogueDevReload.inGame(DEV, sha(DEV), "", Optional.empty(), true));
    }
}
