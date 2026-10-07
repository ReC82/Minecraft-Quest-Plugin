package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Filet de sécurité pour les dialogues livrés dans le jar ({@code src/main/resources/dialogues/}) :
 * ils sont copiés/édités à la main par un administrateur mais doivent rester valides tels quels.
 * Couvre en particulier le Guide « centre d'aide » (issue #11).
 */
class BundledDialoguesValidityTest {

    private static final List<String> BUNDLED =
            List.of("guard.yml", "guide.yml", "jo.yml", "libraire.yml", "merchant.yml");

    private final DialogueLoader loader = new DialogueLoader(List.of("give", "xp", "customitem", "claim"));

    @Test
    void everyBundledDialogueLoadsWithoutIssue() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        for (String name : BUNDLED) {
            files.put(name, read("/dialogues/" + name));
        }

        DialogueLoadReport report = loader.load(files);

        assertTrue(report.issues().isEmpty(), () -> "problèmes de chargement : " + report.issues());
        assertEquals(BUNDLED.size(), report.loaded().size());
    }

    @Test
    void theGuideExposesAStructuredHelpMenu() {
        DialogueLoadReport report = loader.load(Map.of("guide.yml", read("/dialogues/guide.yml")));

        DialogueDefinition guide = report.loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "guide")))
                .findFirst()
                .orElseThrow();

        assertTrue(guide.nodes().containsKey("help_menu"), "le Guide doit avoir un nœud de menu d'aide");
        // Chaque sujet du menu renvoie vers un nœud d'aide dédié qui, lui, ramène au menu.
        long topicNodes = guide.nodes().keySet().stream().filter(id -> id.startsWith("help_")).count();
        assertTrue(topicNodes >= 6, "au moins six sujets d'aide attendus, trouvés : " + topicNodes);
    }

    /**
     * Issue #24 : le Garde doit proposer <strong>en permanence</strong> (aucune condition, donc
     * quelle que soit la progression du joueur) de renseigner l'état du Wild, et la réponse doit
     * être rendue dynamiquement — c'est-à-dire porter le marqueur {@code %wild_conditions%}, jamais
     * une météo écrite en dur dans la donnée.
     */
    @Test
    void theGuardAlwaysOffersToReportTheCurrentWildConditions() {
        DialogueLoadReport report = loader.load(Map.of("guard.yml", read("/dialogues/guard.yml")));
        assertTrue(report.issues().isEmpty(), () -> "guard.yml doit rester valide : " + report.issues());

        DialogueDefinition guard = report.loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "guard")))
                .findFirst().orElseThrow();

        var choices = guard.nodes().get(guard.startNodeId()).choices().stream()
                .filter(choice -> choice.text().base().contains("Comment est le Wild"))
                .toList();
        assertEquals(1, choices.size(), "un seul choix « état du Wild » au nœud d'accueil du Garde");
        var choice = choices.get(0);
        assertTrue(choice.conditions().isEmpty(), "ce choix doit être permanent : aucune condition");
        assertTrue(choice.actions().isEmpty(), "demander l'état du Wild ne doit rien exécuter ni rien modifier");

        var answer = guard.nodes().get(choice.next());
        assertTrue(answer != null, "le choix doit mener à un nœud de réponse : " + choice.next());
        assertTrue(answer.text().base().contains("%wild_conditions%"),
                () -> "la réponse doit être dynamique, trouvé : " + answer.text().base());
    }

    /**
     * Récupération du journal de quêtes : le Libraire doit pouvoir en <strong>redonner</strong> un
     * sans jamais en donner deux, et sans toucher à la progression.
     *
     * <p>Les deux garanties tiennent à la <em>donnée</em>, pas au code : la condition
     * {@code LACKS_CUSTOM_ITEM} masque l'option dès qu'un exemplaire est en poche (pas de doublon),
     * et la seule action attachée est un {@code customitem give … 1} (aucune remise à zéro de
     * quête, de story ou de variable). Ce test verrouille ce contrat : éditer {@code libraire.yml}
     * depuis le Control Panel et retirer la condition par inadvertance casserait le test.</p>
     */
    @Test
    void theLibrarianCanHandTheQuestJournalBackWithoutDuplicatingItOrResettingAnything() {
        DialogueLoadReport report = loader.load(Map.of("libraire.yml", read("/dialogues/libraire.yml")));
        assertTrue(report.issues().isEmpty(), () -> "libraire.yml doit rester valide : " + report.issues());

        DialogueDefinition libraire = report.loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "libraire")))
                .findFirst().orElseThrow();

        NamespacedKey journal = new NamespacedKey("rpgquest", "journal_quetes");
        var handouts = libraire.nodes().values().stream()
                .flatMap(node -> node.choices().stream())
                .filter(choice -> choice.actions().stream()
                        .anyMatch(a -> a instanceof com.lodygames.rpgquest.dialogue.model.RunSafeCommandAction cmd
                                && cmd.command().contains(journal.getKey())))
                .toList();

        assertEquals(1, handouts.size(),
                "une seule option doit remettre le journal — plusieurs chemins rouvriraient la porte au doublon");
        var handout = handouts.get(0);

        assertTrue(handout.conditions().stream()
                        .anyMatch(c -> c instanceof com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition lacks
                                && lacks.itemId().equals(journal)),
                "l'option doit être masquée tant que le joueur possède déjà le journal (anti-doublon)");

        for (var action : handout.actions()) {
            assertTrue(action instanceof com.lodygames.rpgquest.dialogue.model.RunSafeCommandAction,
                    "remettre le journal ne doit rien faire d'autre que donner l'objet, trouvé : " + action);
            String command = ((com.lodygames.rpgquest.dialogue.model.RunSafeCommandAction) action).command();
            assertTrue(command.startsWith("customitem give "), "commande inattendue : " + command);
            assertTrue(command.endsWith(" 1"), "un seul exemplaire doit être donné : " + command);
        }
    }

    private ConfigurationSection read(String resource) {
        try (InputStream in = BundledDialoguesValidityTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Ressource introuvable : " + resource);
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
