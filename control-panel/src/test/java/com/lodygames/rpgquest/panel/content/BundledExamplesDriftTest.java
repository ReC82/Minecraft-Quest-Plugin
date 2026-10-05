package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Garde-fou contre la dérive des listes d'exemples embarqués (issue #194).
 *
 * <p><strong>Le problème que ce test résout.</strong> Le Control Panel doit avertir qu'un contenu
 * fait partie des exemples embarqués dans le JAR, parce que le serveur les <em>recrée au
 * démarrage</em> : supprimer le fichier ne suffit pas, il réapparaît. Mais le panel ne peut pas
 * dépendre du plugin (choix d'isolation des modules), il porte donc une <strong>copie</strong> de
 * ces listes. Une copie dérive toujours — à moins qu'un test ne la confronte à l'original.</p>
 *
 * <p>Ce test lit les <strong>fichiers Java réels du plugin</strong> dans le dépôt et compare. Il
 * est donc un test de cohérence du dépôt, pas un test unitaire : il ne s'exécute que si les
 * sources du plugin sont présentes à côté (cas normal d'un checkout complet), et se contente de
 * s'abstenir sinon plutôt que d'échouer pour la mauvaise raison.</p>
 */
class BundledExamplesDriftTest {

    /** {@code {"a.yml", "b.yml"}} dans une déclaration {@code BUNDLED_EXAMPLES}. */
    private static final Pattern DECLARATION = Pattern.compile(
            "BUNDLED_EXAMPLES\\s*=\\s*\\{?\\s*(?:\\{)?([^;]*?)\\}\\s*;", Pattern.DOTALL);
    private static final Pattern FILE_NAME = Pattern.compile("\"([^\"]+)\\.ya?ml\"");

    @Test
    @DisplayName("La copie des quêtes embarquées correspond à YamlQuestEngine")
    void bundledQuestsMatchThePlugin() throws IOException {
        List<String> fromPlugin = declaredExamples(
                "src/main/java/com/lodygames/rpgquest/quest/YamlQuestEngine.java");
        Assumptions.assumeFalse(fromPlugin.isEmpty(),
                "sources du plugin absentes : test de cohérence non applicable ici");

        assertEquals(fromPlugin, ContentDeletionAnalyzer.BUNDLED_QUESTS,
                "La liste du panel a dérivé de YamlQuestEngine#BUNDLED_EXAMPLES. Sans correction, "
                        + "le panel cesserait d'avertir qu'une quête supprimée sera recréée au "
                        + "prochain démarrage du serveur.");
    }

    @Test
    @DisplayName("La copie des stories embarquées correspond à StoryRegistry")
    void bundledStoriesMatchThePlugin() throws IOException {
        List<String> fromPlugin = declaredExamples(
                "src/main/java/com/lodygames/rpgquest/story/StoryRegistry.java");
        Assumptions.assumeFalse(fromPlugin.isEmpty(),
                "sources du plugin absentes : test de cohérence non applicable ici");

        assertEquals(fromPlugin, ContentDeletionAnalyzer.BUNDLED_STORIES,
                "La liste du panel a dérivé de StoryRegistry#BUNDLED_EXAMPLES.");
    }

    @Test
    @DisplayName("Les listes du panel ne sont pas vides : une liste vide désactiverait l'avertissement")
    void listsAreNotSilentlyEmpty() {
        assertTrue(ContentDeletionAnalyzer.BUNDLED_QUESTS.size() >= 1);
        assertTrue(ContentDeletionAnalyzer.BUNDLED_STORIES.size() >= 1);
    }

    /**
     * Extrait les noms de fichiers d'une déclaration {@code BUNDLED_EXAMPLES}, sans extension,
     * dans l'ordre du code source.
     *
     * @return liste vide si le fichier n'est pas trouvable depuis le répertoire de travail du test
     */
    private static List<String> declaredExamples(String relativePath) throws IOException {
        Path file = locate(relativePath);
        if (file == null) {
            return List.of();
        }
        Matcher declaration = DECLARATION.matcher(Files.readString(file));
        if (!declaration.find()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Matcher names2 = FILE_NAME.matcher(declaration.group(1));
        while (names2.find()) {
            names.add(names2.group(1));
        }
        return names;
    }

    /** Le test tourne depuis {@code control-panel/} : la racine du dépôt est au-dessus. */
    private static Path locate(String relativePath) {
        Path here = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && here != null; depth++) {
            Path candidate = here.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            here = here.getParent();
        }
        return null;
    }
}
