package com.lodygames.rpgquest.panel.content;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Construit le nom de fichier d'un content pack téléchargé (issue #108) — jamais dérivé d'une
 * saisie navigateur : {@code lodyquests-<famille>-<AAAA-MM-JJ>.yaml}. La date est celle du jour
 * (UTC), stable et lisible.
 */
public final class ContentExportName {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Familles connues (miroir de {@code ContentFamily} côté plugin) + {@code all}. */
    private static final Set<String> KNOWN = Set.of("all", "quests", "stories", "dialogues", "npcs");

    private ContentExportName() {
    }

    public static String forFamily(String family, LocalDate day) {
        String f = family == null ? "" : family.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(f)) {
            f = "content";
        }
        LocalDate d = day == null ? LocalDate.now(ZoneOffset.UTC) : day;
        return "lodyquests-" + f + "-" + DAY.format(d) + ".yaml";
    }

    public static String forFamily(String family) {
        return forFamily(family, LocalDate.now(ZoneOffset.UTC));
    }
}
