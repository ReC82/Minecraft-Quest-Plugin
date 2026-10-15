package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #430 — la copie d'un dialogue « Hors source » n'accepte qu'un contenu intègre et à jour. */
class DialogueServerCopyTest {

    private static final String YAML = """
            # commentaire conservé
            id: rpgquest:brigand
            start: hello
            nodes:
              hello:
                speaker: "Brigand"
                text: "Salut."\r
                choices:
                  - text: "Au revoir"
                    actions:
                      - type: CLOSE
            """;

    private static Map<String, Object> read(String text, String sha) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("present", true);
        d.put("tooLarge", false);
        d.put("sha256", sha);
        d.put("text", text);
        return d;
    }

    private static String sha(String text) {
        return ContentWorkspace.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anIntactReadIsCopiedVerbatim() {
        DialogueServerCopy c = DialogueServerCopy.of(Optional.of(read(YAML, sha(YAML))), false, sha(YAML));
        assertTrue(c.ready());
        assertEquals(YAML, c.text(), "octet pour octet : commentaire et fin de ligne CRLF compris");
        assertEquals(sha(YAML), c.devSha());
        assertTrue(c.editableAfterCopy());
    }

    @Test
    void nothingIsCopiedFromATamperedStaleOrMissingRead() {
        assertEquals(DialogueServerCopy.Status.NOT_READ, DialogueServerCopy.of(Optional.empty(), false, "").status());
        assertEquals(DialogueServerCopy.Status.PENDING, DialogueServerCopy.of(Optional.empty(), true, "").status());
        assertEquals(DialogueServerCopy.Status.CORRUPT,
                DialogueServerCopy.of(Optional.of(read(YAML + " ", sha(YAML))), false, "").status());
        assertEquals(DialogueServerCopy.Status.STALE,
                DialogueServerCopy.of(Optional.of(read(YAML, sha(YAML))), false, sha("autre")).status(),
                "DEV a changé depuis la lecture");
        assertEquals(DialogueServerCopy.Status.PENDING,
                DialogueServerCopy.of(Optional.of(read(YAML, sha(YAML))), true, "").status(),
                "une lecture plus récente arrive");
        Map<String, Object> absent = new LinkedHashMap<>(Map.of("present", false, "sha256", "", "text", ""));
        assertEquals(DialogueServerCopy.Status.ABSENT, DialogueServerCopy.of(Optional.of(absent), false, "").status());
        Map<String, Object> big = read("", sha(YAML));
        big.put("tooLarge", true);
        assertEquals(DialogueServerCopy.Status.TOO_LARGE, DialogueServerCopy.of(Optional.of(big), false, "").status());
        assertEquals("", DialogueServerCopy.of(Optional.of(read(YAML, sha(YAML))), false, sha("x")).text(),
                "aucun texte n'est remis hors READY");
    }

    @Test
    void aFileTheEditorCannotReReadIsCopiedButSaidReadOnly() {
        String odd = "id: rpgquest:x\nstart: a\nnodes: [1, 2]\n";
        DialogueServerCopy c = DialogueServerCopy.of(Optional.of(read(odd, sha(odd))), false, "");
        assertTrue(c.ready());
        assertFalse(c.editableAfterCopy());
        assertFalse(c.editorIssues().isEmpty());
    }
}
