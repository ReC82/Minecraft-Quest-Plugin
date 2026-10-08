package com.lodygames.rpgquest.panel.content;

import java.util.List;

/**
 * Gabarits téléchargeables, exemples et documentation compacte du format
 * {@code lodyquests-content-pack} (issue #110).
 *
 * <p><strong>Tout est généré à partir de {@link Descriptors}</strong>, la même source que le
 * formulaire, l'écriture YAML et la validation du Control Panel — donc aucun catalogue de types,
 * de champs ou d'aides n'est recopié ici. Un type d'objectif ou de récompense ajouté au moteur
 * apparaît dans le gabarit et la documentation sans intervention ; un type retiré en disparaît.
 * C'est l'exigence de l'issue #110 (« dérivé des capacités réelles du moteur ») et la raison pour
 * laquelle ces fichiers ne sont pas maintenus à la main.</p>
 *
 * <p>Les identifiants des exemples sont préfixés {@code tc110_} : ils sont reconnaissables, donc
 * faciles à retrouver et à supprimer après un essai, et ne peuvent pas être confondus avec du
 * contenu de production.</p>
 */
public final class ContentPackTemplates {

    private static final String EXAMPLE_PREFIX = "tc110_";

    private ContentPackTemplates() {
    }

    // ---- Gabarits ------------------------------------------------------------------------------

    /**
     * Gabarit d'une famille : l'enveloppe minimale plus <strong>un</strong> élément à compléter.
     * Le but est qu'un humain comme une IA puisse partir de ce fichier sans lire le schéma.
     *
     * @param family une valeur de {@link ContentPackSchema#FAMILIES} ; toute autre valeur donne le
     *               gabarit du pack complet
     */
    public static String template(String family) {
        String f = family == null ? "" : family.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (f) {
            case "quests" -> envelope("quests") + questTemplateBody() + objectiveReference() + rewardReference();
            case "stories" -> envelope("stories") + storyTemplateBody();
            case "dialogues" -> envelope("dialogues") + dialogueTemplateBody()
                    + dialogueActionReference() + dialogueConditionReference();
            case "npcs" -> envelope("npcs") + npcTemplateBody();
            default -> fullTemplate();
        };
    }

    private static String fullTemplate() {
        StringBuilder sb = new StringBuilder(header(
                "Gabarit de pack COMPLET : les quatre familles, à compléter ou à vider.",
                "Une famille vide peut simplement être supprimée."));
        sb.append("format: ").append(ContentPackSchema.FORMAT).append('\n');
        sb.append("schemaVersion: ").append(ContentPackSchema.SCHEMA_VERSION).append('\n');
        sb.append(metadataBlock());
        sb.append("content:\n");
        sb.append(questTemplateBody());
        sb.append(storyTemplateBody());
        sb.append(dialogueTemplateBody());
        sb.append(npcTemplateBody());
        sb.append(objectiveReference());
        sb.append(rewardReference());
        sb.append(dialogueActionReference());
        sb.append(dialogueConditionReference());
        return sb.toString();
    }

    private static String envelope(String family) {
        StringBuilder sb = new StringBuilder(header(
                "Gabarit de pack — famille « " + family + " ».",
                "Les autres familles peuvent être ajoutées sous « content: » quand c'est utile."));
        sb.append("format: ").append(ContentPackSchema.FORMAT).append('\n');
        sb.append("schemaVersion: ").append(ContentPackSchema.SCHEMA_VERSION).append('\n');
        sb.append(metadataBlock());
        sb.append("content:\n");
        return sb.toString();
    }

    private static String header(String line1, String line2) {
        return "# " + line1 + "\n"
                + "# " + line2 + "\n"
                + "#\n"
                + "# Ce fichier est GÉNÉRÉ depuis les descripteurs réels du moteur : il ne contient aucun\n"
                + "# champ inventé, et il suit automatiquement l'évolution des types supportés.\n"
                + "# Un pack ne contient jamais de donnée joueur, de progression ni de secret.\n"
                + "#\n";
    }

    private static String metadataBlock() {
        return """

                # Informatif uniquement : jamais utilisé pour décider quoi que ce soit à l'import.
                metadata:
                  title: "À remplir"
                  author: "À remplir"
                  generator: "À remplir"
                  notes: ""

                # Dépendances externes attendues (facultatif). L'import distingue « satisfaite par le pack »,
                # « déjà présente sur le serveur » et « manquante » — il ne devine jamais.
                dependencies:
                  quests: []
                  npcs: []
                  dialogues: []
                  items: []

                """;
    }

    private static String questTemplateBody() {
        StringBuilder sb = new StringBuilder("""
                  quests:
                    - id: namespace:ma_quete          # minuscules, chiffres et « _ » ; jamais un chemin
                      title: "<gold>Mon titre</gold>" # MiniMessage accepté, texte brut aussi
                      description: "Ce que le joueur doit faire."
                      category: exemple
                      icon: PAPER                     # Material Minecraft
                      repeatable: false
                      secret: false
                      giver: mon_pnj                  # id de PNJ logique, facultatif
                      prerequisites: []               # ids de quêtes à terminer avant
                      steps:
                        - id: premiere_etape
                          objectives:
                """);
        // Un objectif et une récompense d'exemple, générés depuis le PREMIER descripteur de chaque
        // catalogue : le gabarit est donc immédiatement VALIDE, à adapter plutôt qu'à compléter.
        Descriptors.Descriptor first = Descriptors.OBJECTIVES.get(0);
        sb.append("            - type: ").append(first.kind()).append("          # à adapter — voir la liste en bas\n");
        for (Descriptors.Field f : first.fields()) {
            sb.append("              ").append(f.name()).append(": ").append(exampleValue(f)).append('\n');
        }
        sb.append("""
                      rewards:
                """);
        Descriptors.Descriptor firstReward = Descriptors.REWARDS.get(0);
        sb.append("        - type: ").append(firstReward.kind()).append("            # à adapter — voir la liste en bas\n");
        for (Descriptors.Field f : firstReward.fields()) {
            sb.append("          ").append(f.name()).append(": ").append(exampleValue(f)).append('\n');
        }
        sb.append("""
                      variables: {}

                """);
        return sb.toString();
    }

    private static String storyTemplateBody() {
        return """
                  stories:
                    - id: ma_story
                      name: "Nom de la story"
                      secret: false
                      quests:                         # dans l'ordre de progression
                        - namespace:ma_quete

                """;
    }

    private static String dialogueTemplateBody() {
        return """
                  dialogues:
                    # Convention du moteur : l'id du dialogue EST l'id du PNJ qui le porte.
                    - id: mon_pnj
                      start: accueil
                      # « nodes » est une MAP : la clé est l'id du nœud, jamais une liste.
                      nodes:
                        accueil:
                          speaker: "Mon PNJ"
                          text: "Bonjour."
                          choices:
                            # Un choix conditionnel n'est PAS affiché si sa condition est fausse.
                            # « negate: true » inverse n'importe quelle condition.
                            - text: "Parle-moi de la mine."
                              conditions:
                                - type: QUEST_STATE
                                  quest: namespace:ma_quete
                                  state: NOT_STARTED
                              actions:
                                - type: START_QUEST
                                  quest: namespace:ma_quete
                                - type: CLOSE
                            # Prévoir toujours une sortie : un choix sans « next », ou CLOSE.
                            - text: "Au revoir."
                              actions:
                                - type: CLOSE

                """;
    }

    private static String npcTemplateBody() {
        return """
                  npcs:
                    - id: mon_pnj
                      displayName: "Mon PNJ"
                      description: "Note éditoriale, jamais affichée en jeu."
                      dialogue: mon_pnj
                      role: guide
                      enabled: true

                """;
    }


    // ---- Référence générée : tous les types réellement supportés --------------------------------

    private static String objectiveReference() {
        return reference("OBJECTIFS SUPPORTÉS", Descriptors.OBJECTIVES);
    }

    private static String rewardReference() {
        return reference("RÉCOMPENSES SUPPORTÉES", Descriptors.REWARDS);
    }

    private static String dialogueActionReference() {
        return reference("ACTIONS DE DIALOGUE SUPPORTÉES", Descriptors.DIALOGUE_ACTIONS);
    }

    /**
     * Les conditions, plus la mention de {@code negate} : c'est le seul champ commun à toutes, et
     * il n'apparaîtrait donc dans aucun descripteur.
     */
    private static String dialogueConditionReference() {
        return reference("CONDITIONS DE DIALOGUE SUPPORTÉES", Descriptors.DIALOGUE_CONDITIONS)
                + "# Toute condition accepte « " + Descriptors.NEGATE.name()
                + ": true », qui inverse son verdict.\n#\n";
    }

    private static String reference(String title, List<Descriptors.Descriptor> catalog) {
        StringBuilder sb = new StringBuilder("# ---- ").append(title).append(" (")
                .append(catalog.size()).append(") ").append("-".repeat(Math.max(0, 40 - title.length())))
                .append("\n#\n");
        for (Descriptors.Descriptor d : catalog) {
            sb.append("# ").append(d.kind()).append(" — ").append(d.label()).append('\n');
            for (String line : wrap(d.hint(), 92)) {
                sb.append("#     ").append(line).append('\n');
            }
            for (Descriptors.Field f : d.fields()) {
                sb.append("#   • ").append(f.name()).append(" (").append(yamlType(f))
                        .append(f.required() ? ", obligatoire" : ", facultatif").append(")\n");
            }
            sb.append("#\n");
        }
        return sb.toString();
    }

    private static String yamlType(Descriptors.Field f) {
        return switch (f.type()) {
            case INT -> "entier > 0";
            case DOUBLE -> "nombre";
            case LIST -> "liste";
            default -> "texte";
        };
    }

    // ---- Exemples ------------------------------------------------------------------------------

    /** Le plus petit pack valide possible : une quête, une étape, un objectif. */
    public static String minimalExample() {
        Descriptors.Descriptor first = Descriptors.OBJECTIVES.get(0);
        StringBuilder sb = new StringBuilder("""
                # Exemple MINIMAL (issue #110) : le plus petit pack valide.
                # Ids préfixés « tc110_ » pour être reconnaissables et supprimables après essai.
                """);
        sb.append("format: ").append(ContentPackSchema.FORMAT).append('\n');
        sb.append("schemaVersion: ").append(ContentPackSchema.SCHEMA_VERSION).append('\n');
        sb.append("content:\n  quests:\n");
        sb.append("    - id: rpgquest:").append(EXAMPLE_PREFIX).append("minimal\n");
        sb.append("      title: \"Exemple minimal\"\n");
        sb.append("      description: \"Un seul objectif.\"\n");
        // La catégorie est obligatoire pour le validateur réel : sans elle, cet exemple — annoncé
        // comme le plus petit pack VALIDE — était refusé à l'import. Vérifié par un test d'import.
        sb.append("      category: ").append(EXAMPLE_PREFIX).append("exemple\n");
        sb.append("      steps:\n        - id: etape_unique\n          objectives:\n");
        sb.append("            - type: ").append(first.kind()).append('\n');
        for (Descriptors.Field f : first.fields()) {
            sb.append("              ").append(f.name()).append(": ").append(exampleValue(f)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Exemple COMPLET multi-éléments : deux quêtes enchaînées par une story, un PNJ et son dialogue,
     * des dépendances déclarées et des métadonnées de provenance. C'est la forme que l'on attend
     * d'une IA à qui l'on demande une mini-campagne.
     */
    public static String completeExample() {
        return """
                # Exemple COMPLET multi-éléments (issue #110) — « Les mines oubliées ».
                #
                # Tous les ids sont préfixés « tc110_ » : reconnaissables, donc faciles à retrouver et à
                # supprimer après un essai, et impossibles à confondre avec du contenu de production.
                #
                # Ce pack illustre : deux quêtes enchaînées par une story, un PNJ et son dialogue (dont
                # l'id est celui du PNJ, convention du moteur), des dépendances déclarées et des
                # métadonnées de provenance. Les valeurs d'équilibrage sont volontairement modestes :
                # aucun plafond n'est imposé par le moteur, c'est une décision éditoriale.
                format: lodyquests-content-pack
                schemaVersion: 1

                metadata:
                  title: "Les mines oubliées"
                  author: "generated-with-ai"
                  generator: "exemple de référence #110"
                  notes: "prototype — supprimer après essai"

                dependencies:
                  # Déjà présente sur le serveur de référence : l'import doit le constater, pas le supposer.
                  quests:
                    - rpgquest:first_steps
                  # Fournis par le pack lui-même : l'import doit les reconnaître comme satisfaites.
                  npcs:
                    - tc110_mineur
                  dialogues:
                    - tc110_mineur
                  items: []

                content:
                  quests:
                    - id: rpgquest:tc110_descente
                      title: "<gold>La descente</gold>"
                      description: "<gray>Le vieux mineur veut savoir ce qui se cache au fond du puits.</gray>"
                      category: tc110
                      icon: LANTERN
                      repeatable: false
                      secret: false
                      giver: tc110_mineur
                      prerequisites:
                        - rpgquest:first_steps
                      steps:
                        - id: parler_au_mineur
                          objectives:
                            - type: TALK_TO_NPC
                              npc: tc110_mineur
                        - id: creuser
                          objectives:
                            - type: BREAK_BLOCK
                              material: STONE
                              amount: 20
                      rewards:
                        - type: EXPERIENCE
                          amount: 25

                    - id: rpgquest:tc110_remonter
                      title: "<gold>Remonter la trouvaille</gold>"
                      description: "<gray>Rapporte au mineur de quoi prouver ta découverte.</gray>"
                      category: tc110
                      icon: RAW_IRON
                      repeatable: false
                      giver: tc110_mineur
                      prerequisites:
                        - rpgquest:tc110_descente
                      steps:
                        - id: rapporter
                          objectives:
                            - type: DELIVER_ITEM_TO_NPC
                              npc: tc110_mineur
                              material: RAW_IRON
                              amount: 3
                      rewards:
                        - type: EXPERIENCE
                          amount: 40
                        - type: MONEY
                          amount: 15

                  stories:
                    - id: tc110_mines_oubliees
                      name: "Les mines oubliées"
                      secret: false
                      quests:
                        - rpgquest:tc110_descente
                        - rpgquest:tc110_remonter

                  npcs:
                    - id: tc110_mineur
                      displayName: "Vieux mineur"
                      description: "PNJ d'exemple du pack #110 — à supprimer après essai."
                      dialogue: tc110_mineur
                      role: guide
                      enabled: true

                  dialogues:
                    - id: tc110_mineur
                      start: accueil
                      # « nodes » est une MAP indexée par id de nœud, comme l'écrit l'export du
                      # plugin. Une liste de nœuds serait refusée à l'import.
                      nodes:
                        accueil:
                          speaker: "Vieux mineur"
                          text: "Tu viens pour le puits, pas vrai ?"
                          choices:
                            # Un choix sans condition est toujours affiché.
                            - text: "Raconte-moi."
                              next: histoire
                            # Celui-ci n'apparaît QUE si la quête n'est pas encore commencée : un
                            # choix dont une condition est fausse n'est pas montré au joueur.
                            - text: "J'y vais tout de suite."
                              conditions:
                                - type: QUEST_STATE
                                  quest: rpgquest:tc110_descente
                                  state: NOT_STARTED
                              actions:
                                - type: START_QUEST
                                  quest: rpgquest:tc110_descente
                                - type: CLOSE
                            # « negate » inverse n'importe quelle condition : ici, seulement pour
                            # qui n'a PAS encore terminé la descente.
                            - text: "Qu'est-ce que tu dirais d'un coup de main ?"
                              conditions:
                                - type: QUEST_STATE
                                  quest: rpgquest:tc110_descente
                                  state: COMPLETED
                                  negate: true
                              next: histoire
                            - text: "Une autre fois."
                              actions:
                                - type: CLOSE
                        histoire:
                          speaker: "Vieux mineur"
                          text: "Personne n'en est remonté depuis des années."
                          choices:
                            - text: "J'y vais."
                              actions:
                                - type: START_QUEST
                                  quest: rpgquest:tc110_descente
                                - type: CLOSE
                            # Dernier nœud atteignable : toujours prévoir une sortie.
                            - text: "Laisse-moi réfléchir."
                              actions:
                                - type: CLOSE
                """;
    }

    private static String exampleValue(Descriptors.Field f) {
        return switch (f.type()) {
            case INT -> "3";
            case DOUBLE -> "1.0";
            case LIST -> "[]";
            case SELECT -> "entity".equals(f.selectSource()) ? "ZOMBIE"
                    : "material".equals(f.selectSource()) || "icon".equals(f.selectSource()) ? "STONE"
                    : "npc".equals(f.selectSource()) ? "guide"
                    : "world".equals(f.selectSource()) ? "world_hub" : "exemple";
            default -> "exemple";
        };
    }

    // ---- Documentation compacte pour une IA ----------------------------------------------------

    /**
     * Documentation compacte, pensée pour être collée telle quelle dans un prompt : règles
     * d'identifiants, types d'objectifs et de récompenses réellement disponibles avec leurs
     * paramètres, règles de stories, références autorisées, conventions de dialogue, exemple minimal
     * et exemple complet. Générée depuis les descripteurs : elle ne peut pas décrire un type qui
     * n'existe pas, ni oublier un type qui existe.
     */
    public static String aiDocumentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Contrat de contenu LodyQuests — ").append(ContentPackSchema.FORMAT)
                .append(" v").append(ContentPackSchema.SCHEMA_VERSION).append("\n\n");
        sb.append("""
                Ce document est **généré** depuis les descripteurs réels du moteur : il ne décrit que des
                propriétés réellement supportées. Il peut être collé tel quel dans un prompt pour demander
                un pack importable.

                ## Enveloppe

                ```yaml
                format: lodyquests-content-pack
                schemaVersion: 1
                metadata: { }      # facultatif, purement informatif
                dependencies: { }  # facultatif
                content:
                  quests: []
                  stories: []
                  dialogues: []
                  npcs: []
                ```

                `format` et `schemaVersion` sont les **seuls** champs sur lesquels un import s'appuie pour
                décider. Les métadonnées documentent la provenance et n'influent jamais sur le gameplay.

                ## Règles d'identifiants

                - Quête : `namespace:clé`, minuscules, chiffres et `_` (ex. `rpgquest:tc110_descente`).
                  Jamais un chemin de fichier, jamais un titre.
                - PNJ, dialogue, story, étape, objet : identifiant logique simple, minuscules, chiffres
                  et `_`. Stable et indépendant du nom affiché.
                - Un identifiant est **définitif** : le renommer crée un autre élément, il ne déplace rien.

                ## Textes

                Tout texte affiché accepte **MiniMessage** (ex. `<gold>Titre</gold>`). Le texte brut est
                accepté et reste lisible. N'inventez pas de balise : seules les balises MiniMessage
                standard sont interprétées.

                """);

        sb.append("## Objectifs disponibles (").append(Descriptors.OBJECTIVES.size()).append(")\n\n");
        appendCatalog(sb, Descriptors.OBJECTIVES);

        sb.append("## Récompenses disponibles (").append(Descriptors.REWARDS.size()).append(")\n\n");
        appendCatalog(sb, Descriptors.REWARDS);

        sb.append("""
                ## Règles de stories

                Une story est un **enchaînement ordonné de quêtes existantes**. Elle ne définit jamais
                d'objectif par elle-même, et ses `quests` doivent exister : soit dans le même pack, soit
                déjà sur le serveur. L'ordre de la liste est l'ordre de progression.

                ## Références autorisées

                - `giver` d'une quête → un id de PNJ logique.
                - `prerequisites` d'une quête → des ids de quêtes.
                - `npc` d'un objectif → un id de PNJ logique.
                - `dialogue` d'un PNJ → un id de dialogue.
                - `quests` d'une story → des ids de quêtes.

                Une référence vers un élément absent du pack **et** absent du serveur est une dépendance
                manquante : déclarez-la dans `dependencies` plutôt que d'inventer l'élément.

                ## Conventions de dialogue

                - L'id d'un dialogue **est** l'id du PNJ qui le porte.
                - `nodes` est une **map** indexée par identifiant de nœud (`accueil:`, `histoire:`…),
                  **pas une liste**. L'identifiant est la clé et n'est jamais répété à l'intérieur du
                  nœud. C'est la forme que le moteur lit ; une liste est refusée à l'import.
                - Un dialogue a un nœud de départ (`start`) et des nœuds nommés ; chaque choix peut porter
                  des conditions d'affichage, des actions, et un `next`.
                - Un choix sans `next` termine la conversation ; `CLOSE` la ferme explicitement.
                - Un choix dont une seule condition est fausse **n'est pas affiché** au joueur. Pour une
                  branche visible mais refusée, affichez-la sans condition et expliquez le refus dans le
                  nœud suivant.
                - `negate: true` sur n'importe quelle condition inverse son verdict. C'est le seul champ
                  commun à toutes les conditions.
                - Les actions d'un choix sont exécutées **dans l'ordre de la liste**.

                """);

        sb.append("### Actions de dialogue disponibles (")
                .append(Descriptors.DIALOGUE_ACTIONS.size()).append(")\n\n");
        appendCatalog(sb, Descriptors.DIALOGUE_ACTIONS, "####");

        sb.append("### Conditions de dialogue disponibles (")
                .append(Descriptors.DIALOGUE_CONDITIONS.size()).append(")\n\n");
        appendCatalog(sb, Descriptors.DIALOGUE_CONDITIONS, "####");

        sb.append("""
                ## Équilibrage

                Aucune contrainte d'équilibrage n'est formalisée par le moteur : rien ne plafonne une
                récompense. Les valeurs de l'exemple complet (25 et 40 XP, 15 pièces) sont des ordres de
                grandeur modestes, pas une règle.

                ## Exemple minimal

                ```yaml
                """);
        sb.append(minimalExample()).append("```\n\n## Exemple complet multi-éléments\n\n```yaml\n");
        sb.append(completeExample()).append("```\n");
        return sb.toString();
    }

    private static void appendCatalog(StringBuilder sb, List<Descriptors.Descriptor> catalog) {
        appendCatalog(sb, catalog, "###");
    }

    private static void appendCatalog(StringBuilder sb, List<Descriptors.Descriptor> catalog,
                                      String heading) {
        for (Descriptors.Descriptor d : catalog) {
            sb.append(heading).append(" `").append(d.kind()).append("` — ").append(d.label()).append("\n\n");
            sb.append(d.hint()).append("\n\n");
            for (Descriptors.Field f : d.fields()) {
                sb.append("- `").append(f.name()).append("` (").append(yamlType(f))
                        .append(f.required() ? ", **obligatoire**" : ", facultatif").append(") — ")
                        .append(f.help() == null ? "" : f.help()).append('\n');
            }
            sb.append('\n');
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
