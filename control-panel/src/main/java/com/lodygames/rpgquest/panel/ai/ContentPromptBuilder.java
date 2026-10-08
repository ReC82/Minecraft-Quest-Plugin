package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.content.ContentPackTemplates;
import com.lodygames.rpgquest.panel.content.RefData;
import java.util.List;

/**
 * Assemble le prompt d'une demande de contenu — quête, dialogue ou story (issue #146).
 *
 * <p><strong>L'administrateur n'écrit que son intention.</strong> Tout le reste est ajouté
 * automatiquement : les règles de LodyQuests, le contrat de contenu généré pour #110, les types
 * d'objectifs et de récompenses <em>réellement</em> supportés, les références réellement disponibles
 * sur le serveur, et l'instruction de format stricte. C'est la différence entre « coller un contrat
 * dans ChatGPT » et un atelier intégré : personne n'a à se souvenir de ce qu'il faut joindre.</p>
 *
 * <p><strong>Rien n'est recopié ici.</strong> La documentation vient de
 * {@link ContentPackTemplates#aiDocumentation()} et le schéma de {@link ContentPackSchema#json()},
 * tous deux dérivés des descripteurs du moteur. Un type d'objectif ajouté au moteur arrive donc
 * dans le prompt sans qu'une ligne soit touchée — et un type qui n'existe pas ne peut pas y
 * apparaître. Depuis la phase 2, cela vaut aussi pour les actions et les conditions de dialogue.</p>
 *
 * <p>Les trois familles partagent tout ce qui est coûteux à maintenir — contrat, schéma, références
 * réelles, consigne de format, demande de correction — et ne divergent que par leurs consignes
 * propres, portées par {@link Kind}. Une quatrième famille n'aurait qu'une entrée d'énumération et
 * un record de demande à ajouter.</p>
 */
public final class ContentPromptBuilder {

    private ContentPromptBuilder() {
    }

    /**
     * La famille de contenu demandée. Elle porte les consignes qui <strong>changent</strong> d'une
     * famille à l'autre : tout le reste du prompt est commun.
     */
    public enum Kind {
        QUEST("quête", "quests", """
                7. Produis UNE seule quête, dans la section « quests ». Ne remplis les autres
                   sections que si la demande le réclame explicitement.
                """),
        DIALOGUE("dialogue", "dialogues", """
                7. Produis UN seul dialogue, dans la section « dialogues ». N'y ajoute pas de quête :
                   si le dialogue en démarre une, elle doit déjà exister (utilise la liste des
                   quêtes disponibles) ou être déclarée dans « dependencies ».
                8. L'identifiant du dialogue EST l'identifiant du PNJ qui le porte. C'est une
                   convention du moteur, pas un choix éditorial.
                9. N'invente JAMAIS un type d'action ni de condition. Seuls ceux listés dans le
                   contrat existent, avec exactement les champs qui y sont décrits.
                10. Un choix dont une condition est fausse N'EST PAS AFFICHÉ au joueur. Pour une
                    branche visible mais refusée, laisse-la sans condition et explique le refus dans
                    le nœud suivant : c'est presque toujours ce que veut l'auteur.
                11. Tout nœud cité par un « next » doit exister, et le nœud « start » aussi. Prévois
                    toujours une sortie : un choix sans « next », ou une action CLOSE.
                """),
        STORY("story", "stories", """
                7. Produis UNE seule story, dans la section « stories ». Une story est un
                   ENCHAÎNEMENT ORDONNÉ DE QUÊTES EXISTANTES : elle ne définit aucun objectif, aucune
                   récompense, aucune étape.
                8. N'invente JAMAIS une quête. N'utilise que les quêtes listées comme disponibles, et
                   dans un ordre de progression cohérent. Si la demande en exige une qui n'existe
                   pas, ne l'invente pas : déclare-la dans « dependencies » et dis-le.
                9. Ne crée AUCUNE section « quests ». Si tu penses qu'il manque une quête, c'est une
                   dépendance manquante, pas une quête à écrire.
                """);

        private final String label;
        private final String section;
        private final String rules;

        Kind(String label, String section, String rules) {
            this.label = label;
            this.section = section;
            this.rules = rules;
        }

        public String label() {
            return label;
        }

        /** Section du content pack que la réponse doit remplir. */
        public String section() {
            return section;
        }

        public static Kind of(String raw) {
            for (Kind k : values()) {
                if (k.name().equalsIgnoreCase(raw)) {
                    return k;
                }
            }
            return QUEST;
        }
    }

    /**
     * La demande humaine, telle que le formulaire la collecte. Tous les champs sont facultatifs sauf
     * {@link #intent()} : c'est le seul dont l'absence rend la demande vide de sens.
     *
     * @param intent       ce que la quête doit raconter / faire faire au joueur
     * @param title        titre exact souhaité, éventuellement stylé (MiniMessage) ; vide = libre
     * @param questId      identifiant souhaité ; vide = laissé à l'IA
     * @param category     catégorie éditoriale
     * @param giver        PNJ donneur souhaité
     * @param difficulty   libellé d'intention de difficulté, pas une mécanique
     * @param duration     durée visée, pour calibrer les quantités
     * @param stepCount    nombre d'étapes souhaité, 0 = laissé à l'IA
     * @param repeatable   quête répétable
     * @param rewardIntent intention de récompense en langage naturel
     * @param constraints  contraintes libres
     */
    public record QuestRequest(String intent, String title, String questId, String category,
                               String giver, String difficulty, String duration, int stepCount,
                               boolean repeatable, String rewardIntent, String constraints) {

        public QuestRequest {
            intent = trim(intent);
            title = trim(title);
            questId = trim(questId);
            category = trim(category);
            giver = trim(giver);
            difficulty = trim(difficulty);
            duration = trim(duration);
            rewardIntent = trim(rewardIntent);
            constraints = trim(constraints);
            stepCount = Math.max(0, Math.min(10, stepCount));
        }

        public boolean valid() {
            return !intent.isEmpty();
        }
    }

    /**
     * Demande de dialogue. {@link #intent()} seul est nécessaire ; {@link #dialogueId()} est très
     * vivement recommandé, puisque c'est l'id du PNJ et qu'il détermine quel PNJ portera le
     * dialogue — l'IA ne peut pas le deviner.
     *
     * @param intent     ce que la conversation doit raconter / permettre
     * @param dialogueId id du PNJ porteur, qui est aussi l'id du dialogue
     * @param speaker    nom affiché du locuteur, éventuellement stylé
     * @param tone       intention de ton, pas une mécanique
     * @param nodeCount  nombre de nœuds souhaité, 0 = laissé à l'IA
     * @param quest      quête que la conversation doit proposer ou valider ; vide = aucune
     * @param constraints contraintes libres
     */
    public record DialogueRequest(String intent, String dialogueId, String speaker, String tone,
                                  int nodeCount, String quest, String constraints) {

        public DialogueRequest {
            intent = trim(intent);
            dialogueId = trim(dialogueId);
            speaker = trim(speaker);
            tone = trim(tone);
            quest = trim(quest);
            constraints = trim(constraints);
            nodeCount = Math.max(0, Math.min(12, nodeCount));
        }

        public boolean valid() {
            return !intent.isEmpty();
        }
    }

    /**
     * Demande de story. Une story n'étant qu'un ordre de quêtes existantes, {@link #quests()} est la
     * matière première : sans elle l'IA devrait deviner, et une story qui cite une quête inexistante
     * est refusée à l'import.
     *
     * @param intent  le fil narratif de l'enchaînement
     * @param storyId identifiant souhaité ; vide = laissé à l'IA
     * @param title   titre exact souhaité, éventuellement stylé ; vide = libre
     * @param quests  quêtes à enchaîner, telles que saisies (une par ligne ou séparées par virgules)
     * @param constraints contraintes libres
     */
    public record StoryRequest(String intent, String storyId, String title, String quests,
                               String constraints) {

        public StoryRequest {
            intent = trim(intent);
            storyId = trim(storyId);
            title = trim(title);
            quests = trim(quests);
            constraints = trim(constraints);
        }

        public boolean valid() {
            return !intent.isEmpty();
        }

        /** Les quêtes citées, quelle que soit la façon dont l'administrateur les a séparées. */
        public List<String> questList() {
            if (quests.isEmpty()) {
                return List.of();
            }
            return java.util.Arrays.stream(quests.split("[,;\n\r]+"))
                    .map(String::trim).filter(q -> !q.isEmpty()).toList();
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Nombre maximal de références listées par famille — un prompt n'est pas un export. */
    private static final int MAX_REFS = 60;

    /**
     * Les règles et le contrat de format. Elles sont séparées de la demande parce que les trois
     * fournisseurs savent distinguer les instructions du système de celles de l'utilisateur, et que
     * mélanger les deux rend l'IA bien plus enclin à « négocier » le format.
     */
    public static String systemPrompt(Kind kind) {
        return """
                Tu produis du contenu pour LodyQuests, un plugin de quêtes Minecraft, au format
                « content pack » décrit ci-dessous.

                RÈGLES ABSOLUES

                1. Ta réponse est UNIQUEMENT un document YAML. Aucune phrase avant, aucune phrase
                   après, aucun bloc de code, aucune explication.
                2. N'invente JAMAIS un type d'objectif, de récompense, d'action ou de condition.
                   Seuls ceux listés dans le contrat existent. Un type plausible mais absent de la
                   liste fera échouer l'import.
                3. N'invente JAMAIS une référence (PNJ, quête, monde, dialogue). N'utilise que les
                   références listées comme disponibles. Si la demande en exige une qui n'existe pas,
                   ne la mets pas et déclare-la dans « dependencies ».
                4. Les identifiants sont définitifs et servent de nom de fichier : minuscules,
                   chiffres et « _ » uniquement. Un identifiant de quête a la forme
                   « namespace:clé ».
                5. Les textes affichés au joueur sont en FRANÇAIS. Tu peux utiliser MiniMessage pour
                   la couleur (par exemple <gold>…</gold>), sobrement ; du texte brut est accepté.
                6. Respecte les quantités demandées. À défaut d'indication, reste modeste. Rien ne
                   plafonne une récompense côté moteur, c'est donc à toi de rester raisonnable.
                """
                + kind.rules
                + """

                Le document sera validé par les validateurs réels du serveur avant tout
                enregistrement, et un administrateur devra le confirmer. Une erreur de ta part ne
                casse rien, mais fait perdre un aller-retour : applique le contrat à la lettre.
                """;
    }

    /** La demande, le contrat, les références. Dans cet ordre : l'IA lit mieux la consigne en tête. */
    public static String userPrompt(QuestRequest request, RefData refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CE QUE JE VEUX\n\n").append(request.intent()).append("\n\n");

        sb.append("# CONSIGNES PRÉCISES\n\n");
        line(sb, "Identifiant de quête imposé", request.questId());
        line(sb, "Titre exact à utiliser (reprends-le tel quel, balises comprises)", request.title());
        line(sb, "Catégorie", request.category());
        line(sb, "PNJ donneur (champ « giver »)", request.giver());
        line(sb, "Difficulté visée", request.difficulty());
        line(sb, "Durée visée", request.duration());
        if (request.stepCount() > 0) {
            line(sb, "Nombre d'étapes", String.valueOf(request.stepCount()));
        }
        line(sb, "Quête répétable", request.repeatable() ? "oui" : "non");
        line(sb, "Récompense souhaitée", request.rewardIntent());
        line(sb, "Contraintes supplémentaires", request.constraints());
        sb.append('\n');

        appendContractAndFormat(sb, Kind.QUEST, refs);
        return sb.toString();
    }

    /**
     * Demande de dialogue. Les références jointes sont les mêmes : ce sont les PNJ et les quêtes
     * réels qui empêchent l'IA d'inventer un donneur ou une quête à démarrer.
     */
    public static String userPrompt(DialogueRequest request, RefData refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CE QUE JE VEUX\n\n").append(request.intent()).append("\n\n");

        sb.append("# CONSIGNES PRÉCISES\n\n");
        line(sb, "PNJ porteur du dialogue — c'est AUSSI l'id du dialogue", request.dialogueId());
        line(sb, "Nom affiché du locuteur (reprends-le tel quel, balises comprises)",
                request.speaker());
        line(sb, "Ton de la conversation", request.tone());
        if (request.nodeCount() > 0) {
            line(sb, "Nombre de nœuds souhaité", String.valueOf(request.nodeCount()));
        }
        line(sb, "Quête que la conversation doit proposer ou valider (elle existe déjà)",
                request.quest());
        line(sb, "Contraintes supplémentaires", request.constraints());
        sb.append('\n');

        appendContractAndFormat(sb, Kind.DIALOGUE, refs);
        return sb.toString();
    }

    /**
     * Demande de story. Les quêtes citées sont répétées telles quelles, parce qu'elles sont la
     * matière première de l'enchaînement — et rappelées comme devant exister.
     */
    public static String userPrompt(StoryRequest request, RefData refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CE QUE JE VEUX\n\n").append(request.intent()).append("\n\n");

        sb.append("# CONSIGNES PRÉCISES\n\n");
        line(sb, "Identifiant de story imposé", request.storyId());
        line(sb, "Titre exact à utiliser (reprends-le tel quel, balises comprises)", request.title());
        line(sb, "Contraintes supplémentaires", request.constraints());
        List<String> quests = request.questList();
        if (!quests.isEmpty()) {
            sb.append("- Quêtes à enchaîner, dans cet ordre si cet ordre est cohérent :\n");
            for (String q : quests) {
                sb.append("  - ").append(q).append('\n');
            }
            sb.append("  Chacune doit figurer parmi les quêtes disponibles listées plus bas. "
                    + "N'en ajoute aucune autre.\n");
        } else {
            sb.append("- Aucune quête imposée : choisis-les parmi les quêtes disponibles listées "
                    + "plus bas, et UNIQUEMENT parmi elles.\n");
        }
        sb.append('\n');

        appendContractAndFormat(sb, Kind.STORY, refs);
        return sb.toString();
    }

    /**
     * La partie commune aux trois familles : références réelles, contrat de #110, schéma officiel,
     * consigne de format. Elle est volumineuse et c'est voulu — c'est exactement ce que personne ne
     * veut avoir à coller à la main, et ce qui fait qu'un type ajouté au moteur arrive dans le
     * prompt sans qu'on y pense.
     */
    private static void appendContractAndFormat(StringBuilder sb, Kind kind, RefData refs) {
        sb.append(references(refs));

        sb.append("\n# CONTRAT DE CONTENU (fais-y autorité)\n\n");
        sb.append(ContentPackTemplates.aiDocumentation());

        sb.append("\n# SCHÉMA JSON OFFICIEL (pour lever toute ambiguïté)\n\n```json\n");
        sb.append(ContentPackSchema.json());
        sb.append("```\n\n");

        sb.append("# FORMAT DE TA RÉPONSE\n\n");
        sb.append("Réponds par le document YAML seul. Il doit commencer exactement par la ligne\n");
        sb.append("« format: lodyquests-content-pack ». Aucun texte autour. Le contenu demandé va\n");
        sb.append("dans la section « ").append(kind.section()).append(" », et nulle part ailleurs.\n");
    }

    /**
     * Demande de correction après un échec de validation : on renvoie à l'IA <strong>sa propre
     * sortie</strong> et les diagnostics réels, plutôt que de lui redemander à partir de zéro.
     * C'est ce qui rend le bouton « demander une correction » utile — l'IA voit ce qui n'allait pas.
     */
    public static String correctionPrompt(Kind kind, String previousYaml, List<String> problems,
                                          RefData refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # CORRECTION DEMANDÉE

                Le document que tu as produit a été refusé par les validateurs réels du serveur.
                Voici les problèmes constatés, puis ton document. Corrige-les tous et renvoie le
                document complet corrigé — toujours du YAML seul, sans explication.

                ## Problèmes constatés

                """);
        for (String p : problems) {
            sb.append("- ").append(p).append('\n');
        }
        sb.append("\n## Ton document précédent\n\n```yaml\n").append(previousYaml).append("\n```\n\n");
        sb.append("Le contenu demandé reste un ").append(kind.label())
                .append(", dans la section « ").append(kind.section()).append(" ».\n\n");
        sb.append(references(refs));
        sb.append("\n# RAPPEL DU CONTRAT\n\n").append(ContentPackTemplates.aiDocumentation());
        return sb.toString();
    }

    /**
     * Les références <strong>réellement</strong> disponibles, lues sur le dernier relevé du serveur.
     * C'est la mesure la plus efficace contre les références inventées, que le ticket demande
     * explicitement de limiter.
     */
    private static String references(RefData refs) {
        RefData r = refs == null ? RefData.empty() : refs;
        StringBuilder sb = new StringBuilder("# RÉFÉRENCES DISPONIBLES\n\n");
        sb.append("N'utilise QUE ces valeurs. Toute autre sera signalée comme inconnue et l'import "
                + "sera bloqué.\n\n");
        appendRefs(sb, "PNJ (pour « giver » et pour l'objectif TALK_TO_NPC)", r.npcs(), r.npcsKnown());
        appendRefs(sb, "Quêtes existantes (pour « prerequisites »)", r.quests(), r.questsKnown());
        appendRefs(sb, "Mondes (pour REACH_LOCATION et DISCOVER_WAYPOINT)", r.worlds(), r.worldsKnown());
        if (r.itemCatalog().known()) {
            sb.append("- **Objets / blocs** : le serveur en expose ")
                    .append(r.itemCatalog().items().size())
                    .append(". Utilise des identifiants Minecraft standard en majuscules "
                            + "(STONE, IRON_INGOT, LEATHER…) ; ils seront vérifiés.\n");
        } else {
            sb.append("- **Objets / blocs** : aucun relevé disponible. Utilise des identifiants "
                    + "Minecraft standard et courants, ils seront vérifiés au serveur.\n");
        }
        return sb.append('\n').toString();
    }

    private static void appendRefs(StringBuilder sb, String label, List<String> values, boolean known) {
        sb.append("- **").append(label).append("** : ");
        if (!known) {
            sb.append("aucun relevé disponible — n'en invente aucun, et déclare dans "
                    + "« dependencies » ce dont la quête a besoin.\n");
            return;
        }
        if (values.isEmpty()) {
            sb.append("aucun n'existe. N'en utilise pas.\n");
            return;
        }
        List<String> shown = values.size() > MAX_REFS ? values.subList(0, MAX_REFS) : values;
        sb.append(String.join(", ", shown));
        if (values.size() > shown.size()) {
            sb.append(" … (").append(values.size() - shown.size()).append(" autres non listées ; "
                    + "n'utilise que celles ci-dessus)");
        }
        sb.append('\n');
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(" : ").append(value).append('\n');
        }
    }
}
