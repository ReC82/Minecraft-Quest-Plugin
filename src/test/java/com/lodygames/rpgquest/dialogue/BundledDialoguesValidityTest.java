package com.lodygames.rpgquest.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.GiveStarterKitAction;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.QuestAvailableCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import com.lodygames.rpgquest.player.StarterToolKitService;
import com.lodygames.rpgquest.quest.model.QuestState;
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
            List.of("guard.yml", "guide.yml", "jo.yml", "libraire.yml", "merchant.yml",
                    "encounter_bandit.yml", "encounter_merchant.yml", "encounter_smith.yml", "help.yml",
                    "woodcutter_bob.yml");

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

    /** Issue #17 : le Libraire du Hub ouvre la boutique du village, et rien d'autre sur ce choix. */
    @Test
    void theLibrarianOpensTheVillageShop() {
        DialogueLoadReport report = loader.load(Map.of("libraire.yml", read("/dialogues/libraire.yml")));
        DialogueDefinition libraire = report.loaded().get(0);

        var shopChoices = libraire.nodes().get("greeting").choices().stream()
                .filter(c -> c.actions().stream()
                        .anyMatch(a -> a instanceof com.lodygames.rpgquest.dialogue.model.OpenMerchantAction))
                .toList();

        assertEquals(1, shopChoices.size(), "une seule entrée de boutique sur l'accueil du Libraire");
        assertEquals(List.of(new com.lodygames.rpgquest.dialogue.model.OpenMerchantAction(
                        new NamespacedKey("rpgquest", "village_merchant"))), shopChoices.get(0).actions());
        assertTrue(shopChoices.get(0).conditions().isEmpty(), "la boutique est proposée à tous les joueurs");
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
     * <p><strong>Élargi à tous les dialogues livrés par #238.</strong> Écrit d'abord pour le Guide
     * seul, ce test avait immédiatement révélé des choix du Garde qui démarrent une quête sans le
     * dire — un défaut appartenant à un autre ticket, consigné à l'époque plutôt que corrigé en
     * effet de bord. #238 a revu ces libellés, donc la règle porte désormais sur {@link #BUNDLED}
     * tout entier, comme prévu.</p>
     *
     * <p>Le préfixe attendu est « Commencer » et non « Commencer la quête : » : la largeur réelle
     * d'un bouton Minecraft (150 px, voir {@code PaperDialogRenderer}) ne permet pas de préfixe
     * aussi long devant le nom d'une quête. Ce qui compte est qu'un bouton qui démarre une quête
     * s'annonce comme tel, et que le nœud l'ait nommée juste avant — ce que vérifient les deux
     * tests suivants.</p>
     */
    @Test
    void noBundledChoiceStartsAQuestWithoutAnnouncingIt() {
        for (String name : BUNDLED) {
            for (DialogueDefinition dialogue : loader.load(Map.of(name, read("/dialogues/" + name)))
                    .loaded()) {
                for (DialogueNode node : dialogue.nodes().values()) {
                    for (DialogueChoice choice : node.choices()) {
                        if (choice.actions().stream().noneMatch(a -> a instanceof StartQuestAction)) {
                            continue;
                        }
                        String label = choice.text().base();
                        assertTrue(label.startsWith("Commencer"),
                                "le choix « " + label + " » (" + name + " / " + node.id() + ") "
                                        + "démarre une quête : son libellé doit l'annoncer");
                    }
                }
            }
        }
    }

    /**
     * Issue #238 — aucun libellé de choix ne doit dépasser la largeur réelle d'un bouton.
     *
     * <p>Les choix sont rendus par la Dialog API de Paper, dont les boutons font
     * {@code PaperDialogRenderer.BUTTON_WIDTH} = <strong>150 px</strong>. Au-delà, Minecraft
     * tronque : « Je veux prouver ma valeur pour obtenir un bout de terrain (palier 1) » mesurait
     * <strong>358 px</strong> et s'affichait coupé en jeu, rendant le choix illisible.</p>
     *
     * <p>La mesure se fait en pixels d'avance de la police Minecraft, pas en caractères : « Mes
     * matériaux » et « IIIIIIIIIIII » n'occupent pas la même place. La table est volontairement
     * recopiée ici depuis {@code panel.dialogue.MinecraftTextWidth} — le Control Panel est un
     * module autonome par décision d'architecture (ADR), et il n'existe pas de module partagé où
     * la mettre. Dix-huit lignes de données dupliquées valent mieux qu'une dépendance inversée.</p>
     */
    @Test
    void noBundledChoiceLabelOverflowsAMinecraftButton() {
        for (String name : BUNDLED) {
            for (DialogueDefinition dialogue : loader.load(Map.of(name, read("/dialogues/" + name)))
                    .loaded()) {
                for (DialogueNode node : dialogue.nodes().values()) {
                    for (DialogueChoice choice : node.choices()) {
                        String label = plainText(choice.text().base());
                        int width = buttonPixels(label);
                        assertTrue(width <= 150,
                                "le libellé « " + label + " » (" + name + " / " + node.id() + ") "
                                        + "mesure " + width + " px pour un bouton de 150 px : il "
                                        + "sera tronqué en jeu");
                    }
                }
            }
        }
    }

    /**
     * Issue #238 — le Garde explique chaque quête, la nomme, et ne la démarre qu'ensuite.
     *
     * <p>C'est le défaut rapporté en jeu : « J'accepte la quête » démarrait {@code first_steps}
     * sans dire laquelle, ni pourquoi, ni ce qu'il faudrait faire. Le test porte sur la règle, donc
     * sur <em>toutes</em> les quêtes du Garde — il y en avait sept, pas trois.</p>
     */
    @Test
    void everyGuardQuestIsExplainedAndNamedBeforeStarting() {
        DialogueDefinition guard = guard();
        DialogueNode greeting = guard.nodes().get("greeting");
        assertNotNull(greeting, "nœud « greeting » attendu");

        // Aucun choix de l'accueil ne démarre quoi que ce soit.
        for (DialogueChoice choice : greeting.choices()) {
            assertTrue(choice.actions().stream().noneMatch(a -> a instanceof StartQuestAction),
                    "l'accueil du Garde ne doit RIEN démarrer : « " + choice.text().base() + " »");
        }

        // Chaque quête démarrée vit dans un nœud « offer_… » qui la nomme.
        int offers = 0;
        for (DialogueNode node : guard.nodes().values()) {
            for (DialogueChoice choice : node.choices()) {
                StartQuestAction start = choice.actions().stream()
                        .filter(StartQuestAction.class::isInstance)
                        .map(StartQuestAction.class::cast)
                        .findFirst().orElse(null);
                if (start == null) {
                    continue;
                }
                offers++;
                assertTrue(node.id().startsWith("offer_"),
                        "un démarrage de quête doit vivre dans un nœud d'explication, pas dans « "
                                + node.id() + " »");
                assertTrue(node.text().base().contains("s'appelle"),
                        "le nœud « " + node.id() + " » doit nommer la quête avant de la proposer");
                assertTrue(node.text().base().contains("But :"),
                        "le nœud « " + node.id() + " » doit dire le but de la quête");
                assertTrue(greeting.choices().stream().anyMatch(c -> node.id().equals(c.next())),
                        "le nœud « " + node.id() + " » doit être atteignable depuis l'accueil");
            }
        }
        assertEquals(7, offers, "les sept quêtes du Garde doivent toutes passer par une explication");
    }

    /** Issue #238 — les anciens libellés génériques ou trompeurs ne doivent pas revenir. */
    @Test
    void theGuardOldMisleadingLabelsAreGone() {
        DialogueDefinition guard = guard();
        List<String> labels = guard.nodes().values().stream()
                .flatMap(n -> n.choices().stream())
                .map(c -> c.text().base())
                .toList();

        assertFalse(labels.contains("J'accepte la quête"),
                "« J'accepte la quête » ne disait pas laquelle (#238)");
        assertFalse(labels.stream().anyMatch(l -> l.contains("J'ai entendu dire")),
                "ce libellé démarrait crystal_hunt sans la nommer, et était tronqué en jeu");
        assertFalse(labels.stream().anyMatch(l -> l.contains("prouver ma valeur pour")),
                "ce libellé mesurait 358 px : illisible en jeu");

        // Le Garde ne doit plus décrire crystal_hunt, devenue éditable depuis le panel : il parlait
        // encore de « crocs d'araignée » et de « forger une lame » pour une quête devenue une chasse.
        String all = guard.nodes().values().stream()
                .map(n -> n.text().base())
                .reduce("", (a2, b2) -> a2 + " " + b2);
        assertFalse(all.contains("crocs d'araignée"),
                "le Garde ne doit plus recopier les objectifs de crystal_hunt : ils ont changé");
        assertFalse(all.contains("épée en fer"),
                "le Garde annonçait cette récompense pour TOUTES les quêtes (#238)");
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
        assertEquals("Commencer : Premiers pas", start.text().base());
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
     * Issue #80 : un clic droit sur Bûcheron Bob n'ouvrait rien. Le clic ouvre le dialogue dont l'id
     * est celui du PNJ ; le « giver » de la quête n'est qu'une déclaration. Ce test garde la chaîne
     * entière : giver de la quête = clé du dialogue, qui propose la quête, seulement si elle est
     * acceptable, et explique le prérequis au lieu de se taire.
     */
    @Test
    void theWoodcutterOffersHisQuestFromTheDialogueTheClickOpens() {
        ConfigurationSection quest = read("/quests/woodcutters_request.yml");
        NamespacedKey questId = NamespacedKey.fromString(quest.getString("id"));
        NamespacedKey clickKey = new NamespacedKey("rpgquest", quest.getString("giver"));

        DialogueLoadReport report = loader.load(Map.of("woodcutter_bob.yml", read("/dialogues/woodcutter_bob.yml")));
        assertTrue(report.issues().isEmpty(), () -> "woodcutter_bob.yml doit rester valide : " + report.issues());
        DialogueDefinition bob = report.loaded().getFirst();
        assertEquals(clickKey, bob.id(), "le clic droit sur le giver ouvre rpgquest:<id du PNJ>");

        String offerNode = bob.nodes().entrySet().stream()
                .filter(e -> e.getValue().choices().stream().anyMatch(c -> c.actions().stream()
                        .anyMatch(a -> a instanceof StartQuestAction s && s.questId().equals(questId))))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError(questId + " doit être proposée par Bob"));

        DialogueNode greeting = bob.startNode();
        assertTrue(greeting.choices().stream().anyMatch(c -> offerNode.equals(c.next())
                        && c.conditions().equals(List.of(new QuestAvailableCondition(questId)))),
                "l'accueil mène à l'offre, gardée par QUEST_AVAILABLE");
        NamespacedKey prerequisite = NamespacedKey.fromString(quest.getStringList("prerequisites").getFirst());
        assertTrue(greeting.choices().stream().anyMatch(c -> c.conditions().contains(
                        new NegatedCondition(new QuestStateCondition(prerequisite, QuestState.COMPLETED)))),
                "prérequis non rempli : Bob l'explique au lieu de ne rien proposer");
    }

    /** Issue #218 : chaque palier suivant (3 à 5) est proposé par le Guide, d'après STARTER_KIT_TIER. */
    @Test
    void theGuideOffersEveryNextKitTierFromTheTierVariable() {
        DialogueDefinition guide = guide();
        for (int tier = 3; tier <= 5; tier++) {
            NamespacedKey quest = new NamespacedKey("rpgquest", "kit_tier" + tier);
            String previous = String.valueOf(tier - 1);
            boolean offered = guide.nodes().values().stream()
                    .flatMap(n -> n.choices().stream())
                    .filter(c -> c.actions().stream().anyMatch(a -> a instanceof StartQuestAction start
                            && start.questId().equals(quest)))
                    .anyMatch(c -> c.conditions().toString().contains("STARTER_KIT_TIER")
                            && c.conditions().toString().contains(previous));
            assertTrue(offered, quest + " doit être proposée quand STARTER_KIT_TIER = " + previous);
        }
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

    // ---- Issue #475 : remise en une action, nom du Guide résolu depuis son id -----------------

    /**
     * Plus d'écran intermédiaire : « Donner les matériaux » remet directement. Retour du 17/10 :
     * uniquement dans l'écran « Comment améliorer mon kit ? », jamais à l'accueil.
     */
    @Test
    void theGuideDeliversInOneActionWithoutIntermediateScreens() {
        DialogueDefinition guide = guide();
        DialogueChoice give = guide.nodes().get("kit_progress").choices().stream()
                .filter(c -> c.text().base().equals("Donner les matériaux"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("« Donner les matériaux » attendu dans l'écran du kit"));

        assertTrue(give.actions().stream().anyMatch(a -> a instanceof com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction),
                "le clic remet directement, sans confirmation");
        assertEquals("delivery_result", give.next());
        assertTrue(guide.nodes().get("delivery_result").text().base().contains("%delivery_status%"));
        guide.nodes().values().forEach(node -> node.choices().forEach(choice -> {
            String label = choice.text().base();
            assertFalse(label.equals("J'ai des matériaux") || label.equals("Donner encore")
                    || label.equals("Donner mes matériaux"), () -> "écran redondant restant : " + label);
        }));
    }

    /**
     * Issue #475, retour du 17/10 (captures 131227 / 131357) : accueil LÉGER. Matériaux, compteurs,
     * état de possession et action de remise n'apparaissent qu'après « Comment améliorer mon kit ? ».
     */
    @Test
    void theGuideGreetingIsLightAndTheKitScreenCarriesTheDelivery() {
        DialogueDefinition guide = guide();
        DialogueNode greeting = guide.nodes().get("greeting");
        String greetingText = greeting.text().base();
        for (String marker : List.of("%delivery_hint%", "%delivery_status%", "%delivery_carry%",
                "%kit_upgrade_requirements%")) {
            assertFalse(greetingText.contains(marker), () -> "accueil surchargé par " + marker);
        }
        assertTrue(greeting.choices().stream().noneMatch(c -> c.actions().stream()
                        .anyMatch(a -> a instanceof com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction)),
                "aucune remise depuis l'accueil");

        String kit = guide.nodes().get("kit_progress").text().base();
        assertTrue(kit.contains("%kit_upgrade_requirements%") && kit.contains("%delivery_carry%"),
                "l'écran du kit dit ce qui reste et si le joueur en porte : " + kit);
        assertFalse(kit.contains("%delivery_hint%"), "la liste ne doit pas être répétée deux fois : " + kit);
    }

    /**
     * Issue #475, 3e retour du 17/10 : « Demander mon kit de départ » quand le kit est déjà reçu
     * ouvre une réponse d'Elias (retour ou fermeture) au lieu de fermer avec un message de chat.
     * Les deux variantes du choix s'excluent sur la variable réellement écrite par le service.
     */
    @Test
    void anAlreadyReceivedStarterKitIsExplainedInsideTheDialogue() {
        DialogueDefinition guide = guide();
        List<DialogueChoice> requests = guide.nodes().get("greeting").choices().stream()
                .filter(c -> c.text().base().equals("Demander mon kit de départ"))
                .toList();
        assertEquals(2, requests.size(), "une variante « déjà reçu », une variante « remise »");

        DialogueChoice already = requests.stream()
                .filter(c -> c.actions().stream().noneMatch(a -> a instanceof GiveStarterKitAction))
                .findFirst().orElseThrow();
        DialogueChoice grant = requests.stream()
                .filter(c -> c.actions().stream().anyMatch(a -> a instanceof GiveStarterKitAction))
                .findFirst().orElseThrow();

        VariableEqualsCondition consumed = new VariableEqualsCondition(
                StarterToolKitService.AVAILABLE_KEY, StarterToolKitService.NOT_AVAILABLE);
        assertEquals(List.of(consumed), already.conditions());
        assertEquals(List.of(new NegatedCondition(consumed)), grant.conditions());
        assertTrue(already.actions().isEmpty(), "la variante « déjà reçu » ne donne ni ne ferme rien");

        DialogueNode answer = guide.nodes().get(already.next());
        assertNotNull(answer, "nœud de réponse attendu : " + already.next());
        assertTrue(answer.text().base().contains("déjà reçu ton kit de départ"));
        assertTrue(answer.choices().stream().anyMatch(c -> "greeting".equals(c.next())),
                "retour aux autres questions");
        assertTrue(answer.choices().stream().anyMatch(c -> c.actions().stream().anyMatch(a -> a instanceof CloseAction)),
                "fermeture possible");
        assertTrue(answer.choices().stream().allMatch(c -> c.actions().stream()
                        .noneMatch(a -> a instanceof GiveStarterKitAction)),
                "la réponse ne redonne jamais le kit");
    }

    /** Le Guide renommé (« Elias ») : locuteur et présentation viennent de l'id, jamais « Guide » en dur. */
    @Test
    void theGuideNeverHardCodesItsOwnDisplayName() {
        guide().nodes().values().forEach(node -> {
            assertEquals("%npc_name%", node.speaker(), () -> "locuteur en dur dans " + node.id());
            assertFalse(node.text().base().contains("le Guide") || node.text().base().contains("moi le Guide"),
                    () -> "nom en dur dans " + node.id() + " : " + node.text().base());
        });
        assertTrue(guide().nodes().get("greeting").text().base().contains("Je suis %npc_name%"));
    }

    private DialogueDefinition guard() {
        return loader.load(Map.of("guard.yml", read("/dialogues/guard.yml"))).loaded().stream()
                .filter(d -> d.id().equals(new NamespacedKey("rpgquest", "guard")))
                .findFirst()
                .orElseThrow();
    }

    /** Retire les balises MiniMessage : elles ne s'affichent pas, donc n'occupent aucun pixel. */
    private static String plainText(String raw) {
        return raw.replaceAll("</?[a-z_#:/]+>", "");
    }

    /**
     * Largeur d'avance du texte dans la police Minecraft par défaut, en pixels.
     *
     * <p>Chaque glyphe avance de sa largeur + 1 px d'espacement. La plupart des caractères font
     * donc 6 px ; les étroits sont listés. Volontairement identique à
     * {@code panel.dialogue.MinecraftTextWidth} — voir le javadoc du test qui l'utilise.</p>
     */
    private static int buttonPixels(String text) {
        int total = 0;
        for (char c : text.toCharArray()) {
            total += switch (c) {
                case '!', ',', '.', ':', ';', 'i', '|' -> 2;
                case '\'', 'l', '`' -> 3;
                case 'I', '[', ']', 't' -> 4;
                case ' ' -> 4;
                case '"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}' -> 5;
                default -> 6;
            };
        }
        return total;
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
