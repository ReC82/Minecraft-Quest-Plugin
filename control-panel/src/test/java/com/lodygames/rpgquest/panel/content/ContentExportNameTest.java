package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Issue #108 : nom de fichier de content pack — explicite, type + date, jamais dérivé de saisie libre. */
class ContentExportNameTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    @Test
    void buildsTypeAndDateFilename() {
        assertEquals("lodyquests-quests-2026-09-09.yaml", ContentExportName.forFamily("quests", DAY));
        assertEquals("lodyquests-all-2026-09-09.yaml", ContentExportName.forFamily("all", DAY));
    }

    @Test
    void sanitisesUnexpectedFamilyInput() {
        assertEquals("lodyquests-content-2026-09-09.yaml", ContentExportName.forFamily("", DAY));
        assertEquals("lodyquests-content-2026-09-09.yaml", ContentExportName.forFamily("../etc/passwd", DAY));
        assertEquals("lodyquests-quests-2026-09-09.yaml", ContentExportName.forFamily("QUESTS", DAY));
    }
}
