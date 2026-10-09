package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
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
     * Issue #123 : la branche de remise livrée avec le Garde doit rester <strong>générique</strong> —
     * aucune quête, aucun matériau, aucun PNJ nommé. C'est ce qui permet de la recopier telle quelle
     * dans le dialogue d'un autre PNJ, et c'est ce qu'un passage par le Control Panel pourrait
     * casser par inadvertance.
     */
    @Test
    void theGuardDeliveryBranchIsGenericAndNeverNamesAQuestOrAMaterial() {
        DialogueLoadReport report = loader.load(Map.of("guard.yml", read("/dialogues/guard.yml")));
        assertTrue(report.issues().isEmpty(), () -> "guard.yml doit rester valide : " + report.issues());

        DialogueDefinition guard = report.loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "guard")))
                .findFirst().orElseThrow();

        // L'entrée dans la branche n'est proposée que s'il reste quelque chose à remettre.
        var entry = guard.nodes().get(guard.startNodeId()).choices().stream()
                .filter(c -> "delivery".equals(c.next()))
                .toList();
        assertEquals(1, entry.size(), "une seule entrée vers la branche de remise");
        assertEquals(1, entry.get(0).conditions().size());
        assertTrue(entry.get(0).conditions().get(0)
                        instanceof com.lodygames.rpgquest.dialogue.model.PendingDeliveryCondition pending
                        && pending.npcId() == null,
                "la condition doit être HAS_PENDING_DELIVERY sans PNJ nommé (déduit du dialogue)");

        // La remise elle-même : une seule action, sans PNJ nommé.
        var deliverChoices = guard.nodes().values().stream()
                .flatMap(node -> node.choices().stream())
                .filter(choice -> choice.actions().stream()
                        .anyMatch(a -> a instanceof com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction))
                .toList();
        assertTrue(deliverChoices.size() >= 1, "au moins un choix doit déclencher la remise");
        for (var choice : deliverChoices) {
            assertEquals(1, choice.actions().size(), "la remise ne doit rien faire d'autre");
            var action = (com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction) choice.actions().get(0);
            assertNull(action.npcId(), "aucun PNJ ne doit être codé en dur dans la donnée livrée");
        }

        // Le nœud d'état affiche la progression réelle, jamais un texte figé.
        assertTrue(guard.nodes().get("delivery").text().base().contains("%delivery_status%"));
        assertTrue(guard.nodes().get("delivery_after").text().base().contains("%delivery_status%"));
        assertTrue(guard.nodes().containsKey("delivery_done"), "un nœud de fin doit exister");
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

    // ================================================================================
    //  Issue #235 — onboarding : plus aucune mutation de gameplay derrière un libellé vague
    // ================================================================================

    /**
     * La règle que #235 institue, vérifiée sur le <strong>Guide</strong> : un choix qui démarre une
     * quête doit l'annoncer, et nommer la quête.
     *
     * <p>C'est le défaut exact rapporté en jeu : « Très bien, j'y vais. » démarrait « Premiers pas »
     * sans le dire. Le test porte sur la règle et non sur ce choix précis, pour qu'un futur nœud du
     * Guide ne puisse pas réintroduire le même piège.</p>
     *
     * <p><strong>Pourquoi le Guide seulement.</strong> Écrit d'abord pour tous les dialogues livrés,
     * ce test a immédiatement révélé <em>trois</em> choix du Garde qui démarrent une quête sans le
     * dire (« J'ai entendu dire que tu avais besoin d'aide… » → {@code crystal_hunt}, « Je veux
     * prouver ma valeur… » → {@code guard_tier1}, « Je veux agrandir mon terrain… » →
     * {@code guard_tier2}). C'est le même défaut, mais dans du contenu qui appartient à d'autres
     * tickets ; #235 porte sur le parcours du Guide. Le constat est consigné dans le rapport de
     * session plutôt que corrigé en effet de bord — et ce test est prêt à être élargi à
     * {@link #BUNDLED} le jour où ces libellés seront revus.</p>
     */
    @Test
    void noGuideChoiceStartsAQuestWithoutAnnouncingIt() {
        DialogueDefinition guide = guide();

        for (DialogueNode node : guide.nodes().values()) {
            for (DialogueChoice choice : node.choices()) {
                if (choice.actions().stream().noneMatch(a -> a instanceof StartQuestAction)) {
                    continue;
                }
                String label = choice.text().base();
                assertTrue(label.startsWith("Commencer la quête : "),
                        "le choix « " + label + " » (" + node.id() + ") démarre une quête : son "
                                + "libellé doit l'annoncer ET la nommer");
            }
        }
    }

    /** L'ancien libellé générique ne doit pas revenir, où que ce soit. */
    @Test
    void theAmbiguousGoAheadLabelIsGone() {
        DialogueDefinition guide = guide();

        boolean present = guide.nodes().values().stream()
                .flatMap(n -> n.choices().stream())
                .anyMatch(c -> c.text().base().contains("j'y vais"));

        assertFalse(present, "« Très bien, j'y vais. » démarrait une quête sans le dire (#235)");
    }

    /**
     * Le parcours d'introduction : une question, une explication, <em>puis</em> le démarrage nommé.
     *
     * <p>Deux gestes et non un : le joueur lit ce qu'on attend de lui avant d'accepter.</p>
     */
    @Test
    void theIntroductionExplainsBeforeStartingAndNamesTheQuest() {
        DialogueDefinition guide = guide();

        DialogueChoice entry = guide.nodes().get("greeting").choices().stream()
                .filter(c -> "intro_quest".equals(c.next()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("le Guide doit mener à un nœud d'explication"));
        assertTrue(entry.actions().stream().noneMatch(a -> a instanceof StartQuestAction),
                "le choix qui ouvre l'explication ne doit RIEN démarrer");

        DialogueNode intro = guide.nodes().get("intro_quest");
        assertNotNull(intro, "nœud « intro_quest » attendu");
        assertTrue(intro.text().base().contains("Premiers pas"),
                "l'explication doit nommer la quête avant de la proposer");

        DialogueChoice start = intro.choices().stream()
                .filter(c -> c.actions().stream().anyMatch(a -> a instanceof StartQuestAction))
                .findFirst()
                .orElseThrow(() -> new AssertionError("le nœud d'explication doit proposer de démarrer"));
        assertEquals("Commencer la quête : Premiers pas", start.text().base());
        assertEquals(new NamespacedKey("rpgquest", "premiers_pas"),
                ((StartQuestAction) start.actions().get(0)).questId());
    }

    /**
     * La quête de palier 2 est <strong>atteignable</strong>.
     *
     * <p>Avant #235 elle n'était citée que dans {@code config.yml} : aucun dialogue ne la démarrait,
     * donc la progression de kit annoncée par #218 était injouable. Ce test est le garde-fou de ce
     * défaut précis.</p>
     */
    @Test
    void theGuideCanStartTheKitUpgradeQuest() {
        DialogueDefinition guide = guide();

        boolean offered = guide.nodes().values().stream()
                .flatMap(n -> n.choices().stream())
                .flatMap(c -> c.actions().stream())
                .anyMatch(a -> a instanceof StartQuestAction start
                        && start.questId().equals(new NamespacedKey("rpgquest", "kit_tier2")));

        assertTrue(offered, "rpgquest:kit_tier2 doit être proposée par son PNJ donneur (giver: guide)");
    }

    /**
     * Le palier est affiché par des marqueurs, pas recopié.
     *
     * <p>Si quelqu'un écrivait « Palier 1 — Nouveau venu » en dur ici, le texte mentirait dès que la
     * configuration changerait. Le test exige donc les trois marqueurs <em>et</em> l'absence de
     * quantité codée en dur dans le nœud.</p>
     */
    @Test
    void theKitProgressNodeDerivesEverythingFromPlaceholders() {
        DialogueNode node = guide().nodes().get("kit_progress");

        assertNotNull(node, "nœud « kit_progress » attendu (#235)");
        String text = node.text().base();
        assertTrue(text.contains("%kit_tier_current%"), "le palier actuel doit être un marqueur");
        assertTrue(text.contains("%kit_tier_next%"), "le palier suivant doit être un marqueur");
        assertTrue(text.contains("%kit_upgrade_requirements%"), "les matériaux doivent être un marqueur");
        assertFalse(text.contains("COBBLESTONE") || text.contains("WHEAT_SEEDS"),
                "aucun matériau ne doit être écrit en dur dans le dialogue");
    }

    /** L'option de progression du kit est joignable depuis l'accueil, sans condition. */
    @Test
    void theKitProgressNodeIsReachableFromTheGreeting() {
        DialogueChoice choice = guide().nodes().get("greeting").choices().stream()
                .filter(c -> "kit_progress".equals(c.next()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("« Comment améliorer mon kit ? » attendu à l'accueil"));

        assertTrue(choice.conditions().isEmpty(),
                "connaître son palier ne doit dépendre d'aucune condition — le nœud s'adapte lui-même");
    }

    private DialogueDefinition guide() {
        return loader.load(Map.of("guide.yml", read("/dialogues/guide.yml"))).loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "guide")))
                .findFirst()
                .orElseThrow();
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
