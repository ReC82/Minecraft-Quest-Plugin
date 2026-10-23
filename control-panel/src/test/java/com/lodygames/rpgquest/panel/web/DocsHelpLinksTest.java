package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.docs.DocLibrary;
import com.lodygames.rpgquest.panel.docs.Markdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #780 : les liens d'aide placés dans les messages et les pages de PlugAdmin (erreurs de
 * dialogue #728/#730/#731, diagnostics serveur et PNJ, DiagnosticHelp) mènent à une section qui
 * existe vraiment dans le centre de documentation.
 */
class DocsHelpLinksTest {

    private static final Pattern DOC_LITERAL = Pattern.compile("\"(/docs/[a-z0-9-]+(?:#[a-z0-9-]+)?)\"");

    private final DocLibrary lib = DocLibrary.load();

    @Test
    void dialogueEditorHelpLinksReachTheirSection() {
        assertNull(lib.brokenLinkReason(null, DialoguePages.END_HELP_HREF), DialoguePages.END_HELP_HREF);
        assertNull(lib.brokenLinkReason(null, DialoguePages.LINE_BREAK_HELP_HREF), DialoguePages.LINE_BREAK_HELP_HREF);
        assertEquals("/docs/dialogues#aller-a-la-ligne-dans-une-replique", DialoguePages.LINE_BREAK_HELP_HREF);
        assertNull(lib.brokenLinkReason(null, DialoguePages.GUIDE_HREF));
        String conflict = "/docs/dialogues-depannage#" + Markdown.slug(DialoguePages.CONFLICT_HELP_TITLE);
        assertNull(lib.brokenLinkReason(null, conflict), conflict);
    }

    @Test
    void everyDiagnosticHelpEntryReachesItsSection() {
        for (DiagnosticHelp.Entry e : DiagnosticHelp.all().values()) {
            assertNull(lib.brokenLinkReason(null, e.docHref()), e.code() + " → " + e.docHref());
        }
    }

    @Test
    void everyHardCodedHelpLinkInTheSourcesResolves() throws IOException {
        List<String> broken = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = DOC_LITERAL.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    seen++;
                    String reason = lib.brokenLinkReason(null, m.group(1));
                    if (reason != null) {
                        broken.add(f.getFileName() + " : " + m.group(1) + " — " + reason);
                    }
                }
            }
        }
        assertTrue(seen >= 10, "liens d'aide trouvés dans les sources : " + seen);
        assertEquals(List.of(), broken);
    }
}
