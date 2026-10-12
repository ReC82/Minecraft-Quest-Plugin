package com.lodygames.rpgquest.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Les « pierres tombales » des contenus supprimés depuis le Control Panel (issue #248).
 *
 * <p><strong>Le problème qu'elles règlent.</strong> Certains fichiers de contenu sont livrés dans
 * le JAR et <em>recréés au démarrage</em> s'ils manquent ({@code BUNDLED_EXAMPLES}). Supprimer un
 * tel fichier sur le serveur ne durait donc que jusqu'au prochain redémarrage, et rendre la
 * suppression durable exigeait de reconstruire et redéployer le plugin. Une pierre tombale dit au
 * démarrage : « ce fichier a été supprimé volontairement, ne le recrée pas ».</p>
 *
 * <p>Elles vivent <strong>hors</strong> des dossiers de contenu ({@code content-tombstones/<type>/
 * <fichier>}), pour ne jamais être relues comme des définitions. Elles n'empêchent rien d'autre :
 * republier ou recréer le fichier depuis le panel fonctionne normalement, puisque l'amorçage ne
 * concerne que les fichiers <em>absents</em>. Pour qu'un exemple du JAR soit à nouveau amorcé,
 * il suffit de supprimer sa pierre tombale.</p>
 */
public final class ContentTombstones {

    private final Path root;

    public ContentTombstones(Path root) {
        this.root = root;
    }

    /** Racine conventionnelle, à côté des dossiers de contenu du plugin. */
    public static ContentTombstones besideContent(Path contentDirectory) {
        return new ContentTombstones(contentDirectory.toAbsolutePath().getParent()
                .resolve("content-tombstones"));
    }

    public boolean isDeleted(String kind, String fileName) {
        return Files.exists(path(kind, fileName));
    }

    /** Inscrit la suppression. Lève si l'écriture échoue : l'appelant décide quoi en dire. */
    public Path record(String kind, String fileName, String declaredId) throws IOException {
        Path file = path(kind, fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "id: " + declaredId + "\ndeleted_at: " + Instant.now() + "\n",
                StandardCharsets.UTF_8);
        return file;
    }

    private Path path(String kind, String fileName) {
        return root.resolve(kind).resolve(Path.of(fileName).getFileName().toString());
    }
}
