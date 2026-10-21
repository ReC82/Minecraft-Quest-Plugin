package com.lodygames.rpgquest.world.state;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Critère de l'issue #563 : aucune suppression de fichier de monde n'est possible depuis ce
 * chantier. Le code des mondes n'appelle ni suppression, ni retrait, ni régénération, ni clonage.
 */
class NoWorldDeletionTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "deleteWorld|removeWorld|regenWorld|cloneWorld|Files\\.delete|deleteIfExists|deleteRecursively|\\.delete\\(\\)");

    @Test
    void worldCodeNeverDeletesAnything() throws IOException {
        Path root = Path.of("src/main/java/com/lodygames/rpgquest/world");
        List<String> hits;
        try (Stream<Path> files = Files.walk(root)) {
            hits = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    return Files.readAllLines(p).stream().filter(l -> FORBIDDEN.matcher(l).find())
                            .map(l -> p.getFileName() + ": " + l.strip());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
        assertTrue(Files.isDirectory(root));
        assertTrue(hits.isEmpty(), "suppression trouvée : " + hits);
    }
}
