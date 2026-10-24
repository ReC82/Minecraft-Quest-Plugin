package com.lodygames.rpgquest.permission;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #321 (avec #35) — garde architecturale : un nœud de bypass gameplay ne se lit jamais
 * directement. Les nœuds {@code rpgquest.bypass.*} sont {@code default: false}, mais l'ombrelle
 * {@link RpgPermissions#LEGACY_ADMIN_WORLD} qui les implique est {@code default: op} : un
 * {@code hasPermission} direct sur un bypass rendrait à nouveau l'OP « invisible » aux règles du
 * monde des claims, comme avant le correctif. Le seul chemin autorisé est {@link
 * AdminBypassMode#allows} / {@link AdminBypassMode#allowsExact}, qui exige une session
 * {@code /rpgadmin bypass on} ouverte explicitement.
 */
class BypassOnlyThroughAdminModeTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Set<String> ALLOWED = Set.of("RpgPermissions.java", "AdminBypassMode.java");
    private static final Pattern DIRECT_BYPASS_READ = Pattern.compile(
            "hasBypass\\(|hasPermission\\(\\s*(RpgPermissions\\.)?BYPASS_|hasPermission\\(\\s*\"rpgquest\\.bypass\\."
                    + "|hasPermission\\(\\s*(ClaimWorldAccessGuard\\.)?BYPASS_PERMISSION");

    @Test
    void noGameplayCodeReadsABypassNodeOutsideTheExplicitAdminMode() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (ALLOWED.contains(file.getFileName().toString())) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("//")) {
                        continue; // Javadoc et commentaires : seule la lecture effective compte.
                    }
                    if (DIRECT_BYPASS_READ.matcher(line).find()) {
                        offenders.add(SOURCES.relativize(file) + ":" + (i + 1) + " → " + line);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "lecture directe d'un nœud de bypass (passer par AdminBypassMode.allows) : " + offenders);
    }
}
