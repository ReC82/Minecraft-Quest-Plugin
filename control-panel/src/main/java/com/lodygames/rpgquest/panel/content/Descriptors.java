package com.lodygames.rpgquest.panel.content;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Descripteurs des <strong>types réels</strong> d'objectifs et de récompenses RPGQuest (issue #46).
 * Chaque descripteur porte : sa clé technique ({@code kind}), un libellé humain, une icône, et la
 * liste de ses champs (nom technique, libellé, type de saisie, aide, source de select éventuelle).
 * Abstraction volontairement légère : un {@code switch} géant spécifique à une quête serait
 * l'anti-pattern ; ajouter un type = ajouter un descripteur.
 *
 * <p>Types calqués sur {@code quest.model.ObjectiveType} (9) et {@code quest.model.RewardType} (5)
 * du moteur RPGQuest — voir {@code QuestDefinitionParser}.</p>
 */
public final class Descriptors {

    private Descriptors() {
    }

    /**
     * {@code LIST} (issue #185) : plusieurs valeurs séparées par des virgules dans le formulaire,
     * écrites en liste YAML en ligne — le modèle du panel reste une simple {@code Map<String,String>},
     * aucun éditeur de liste hétérogène n'est introduit.
     */
    public enum FieldType { TEXT, INT, DOUBLE, SELECT, TEXTAREA, LIST }

    /**
     * @param name         nom technique (clé YAML)
     * @param label        libellé humain
     * @param type         type de saisie
     * @param help         aide contextuelle courte
     * @param selectSource si {@code type == SELECT} : source des options — {@code "entity"},
     *                     {@code "material"}, {@code "npc"}, {@code "quest"}, {@code "world"}
     * @param required     champ obligatoire
     */
    public record Field(String name, String label, FieldType type, String help, String selectSource,
                        boolean required) {
        public static Field text(String n, String l, String h, boolean req) {
            return new Field(n, l, FieldType.TEXT, h, null, req);
        }

        public static Field integer(String n, String l, String h) {
            return new Field(n, l, FieldType.INT, h, null, true);
        }

        public static Field dbl(String n, String l, String h) {
            return new Field(n, l, FieldType.DOUBLE, h, null, true);
        }

        public static Field select(String n, String l, String source, String h, boolean req) {
            return new Field(n, l, FieldType.SELECT, h, source, req);
        }

        /** Liste facultative de valeurs ; {@code source} sert à vérifier chaque entrée (issue #185). */
        public static Field list(String n, String l, String source, String h) {
            return new Field(n, l, FieldType.LIST, h, source, false);
        }
    }

    /** Un descripteur d'objectif ou de récompense. */
    public record Descriptor(String kind, String label, String icon, String hint, List<Field> fields) {
    }

    // ---- Objectifs (calqués sur ObjectiveType) ---------------------------------------------

    private static final Field AMOUNT = Field.integer("amount", "Quantité", "Nombre à atteindre (entier > 0).");
    private static final Field GIVE_AMOUNT =
            Field.integer("amount", "Quantité", "Nombre d'exemplaires à donner (entier > 0).");

    public static final List<Descriptor> OBJECTIVES = List.of(
            new Descriptor("KILL_ENTITY", "Tuer une entité", "target",
                    "Le joueur doit tuer N entités d'un type donné.",
                    List.of(Field.select("entity", "Entité", "entity", "Type d'entité Minecraft (ex. SPIDER, ZOMBIE).", true), AMOUNT)),
            new Descriptor("COLLECT_ITEM", "Collecter un objet", "gift",
                    "Ramasser N exemplaires d'un objet AU SOL (jeter puis marcher dessus — /give ne compte pas).",
                    List.of(Field.select("material", "Objet", "material",
                            "Chercher par nom français (« améthyste ») ou par identifiant "
                                    + "(« AMETHYST_SHARD »).", true), AMOUNT)),
            new Descriptor("SMELT_ITEM", "Cuire un objet", "uptime",
                    "Faire CUIRE N exemplaires d'un objet dans un four, un haut fourneau ou un fumoir. "
                            + "L'objet indiqué est celui qui SORT du four (ex. « Teinture verte » pour un "
                            + "cactus cuit), pas la matière première. Obtenir l'objet autrement (coffre, "
                            + "craft, /give) ou le laisser sortir par un entonnoir ne compte jamais : "
                            + "la progression se fait quand un joueur retire lui-même le résultat.",
                    List.of(Field.select("material", "Objet obtenu après cuisson", "material",
                            "Chercher par nom français (« teinture verte ») ou par identifiant "
                                    + "(« GREEN_DYE »).", true), AMOUNT)),
            new Descriptor("DISCOVER_WAYPOINT", "Découvrir des waypoints", "world",
                    "Découvrir N waypoints DISTINCTS. Seule la première découverte réelle compte : "
                            + "passer à proximité, se téléporter ou recliquer un waypoint déjà connu "
                            + "ne progresse jamais. Deux waypoints du même biome comptent séparément, "
                            + "et renommer ou déplacer un waypoint ne le fait pas compter deux fois.",
                    List.of(AMOUNT,
                            Field.list("worlds", "Mondes (optionnel)", "world",
                                    "Mondes où la découverte compte, séparés par des virgules "
                                            + "(ex. « world_hub, wild »). Laisser vide = tous les mondes ; "
                                            + "la portée retenue est toujours annoncée au joueur."),
                            Field.select("count-mode", "Règle de comptage", "waypointCountMode",
                                    "NEW_ONLY : seules les découvertes faites pendant que l'objectif est "
                                            + "actif comptent (défaut). INCLUDE_EXISTING : les découvertes "
                                            + "déjà acquises comptent aussi. Sur une quête répétable, "
                                            + "NEW_ONLY empêche un nouveau cycle si le joueur a déjà tout "
                                            + "découvert — aucune découverte n'est jamais supprimée pour "
                                            + "rendre la quête rejouable.", false))),
            new Descriptor("CRAFT_ITEM", "Fabriquer un objet", "gift",
                    "Fabriquer N exemplaires d'un objet (table de craft ou grille 2×2).",
                    List.of(Field.select("material", "Objet", "material",
                            "Chercher par nom français (« bâton ») ou par identifiant "
                                    + "(« STICK »).", true), AMOUNT)),
            new Descriptor("BREAK_BLOCK", "Casser des blocs", "edit",
                    "Casser N blocs d'un type donné.",
                    List.of(Field.select("material", "Bloc", "material",
                            "Chercher par nom français (« terre ») ou par identifiant "
                                    + "(« DIRT »).", true), AMOUNT)),
            new Descriptor("PLACE_BLOCK", "Poser des blocs", "plus",
                    "Poser N blocs d'un type donné.",
                    List.of(Field.select("material", "Bloc", "material",
                            "Chercher par nom français (« terre ») ou par identifiant "
                                    + "(« DIRT »).", true), AMOUNT)),
            new Descriptor("DELIVER_ITEM_TO_NPC", "Rapporter des objets à un PNJ", "gift",
                    "Le joueur doit REMETTRE N exemplaires d'un objet au PNJ choisi, dans son dialogue. "
                            + "Collecter ou posséder l'objet ne suffit pas : les objets sont réellement "
                            + "consommés à la remise. Les dépôts partiels comptent et sont conservés "
                            + "(2 cuirs remis sur 4 restent acquis après une mort ou un redémarrage).",
                    List.of(Field.select("npc", "PNJ destinataire", "npc",
                                    "PNJ logique RPGQuest qui reçoit les objets — chercher par nom "
                                            + "(« Garde ») ou par id (« guard »). Un autre PNJ ne peut "
                                            + "jamais accepter la remise.", true),
                            Field.select("material", "Objet à rapporter", "material",
                                    "Chercher par nom français (« cuir ») ou par identifiant "
                                            + "(« LEATHER »). Objets Minecraft standards uniquement.", true),
                            Field.integer("amount", "Quantité à remettre",
                                    "Total à remettre au PNJ (entier > 0). Le joueur peut le déposer en "
                                            + "plusieurs fois."))),
            new Descriptor("TALK_TO_NPC", "Parler à un PNJ", "npc",
                    "Interagir avec un PNJ identifié RPGQuest (id posé via /rpgadmin npc tag).",
                    List.of(Field.select("npc", "PNJ", "npc",
                            "PNJ logique RPGQuest — chercher par nom (« Garde ») ou par id (« guard »).", true))),
            new Descriptor("REACH_LOCATION", "Atteindre une zone", "world",
                    "S'approcher d'un point du monde à moins de « radius » blocs.",
                    List.of(
                            Field.select("world", "Monde", "world", "Nom du monde (ex. wild).", true),
                            Field.dbl("x", "X", "Coordonnée X du centre."),
                            Field.dbl("y", "Y", "Coordonnée Y du centre."),
                            Field.dbl("z", "Z", "Coordonnée Z du centre."),
                            new Field("radius", "Rayon", FieldType.DOUBLE, "Distance d'activation en blocs (> 0 ; 1 par défaut).", null, false))));

    // ---- Récompenses (calquées sur RewardType) -------------------------------------------

    public static final List<Descriptor> REWARDS = List.of(
            new Descriptor("EXPERIENCE", "Expérience", "uptime",
                    "Points d'XP RPGQuest accordés une fois la quête terminée.",
                    List.of(Field.integer("amount", "Points d'expérience", "Nombre de points d'XP RPGQuest (entier > 0)."))),
            new Descriptor("ITEM", "Objet", "gift",
                    "Donne N exemplaires d'un objet vanilla (le moteur ne gère qu'un Material Minecraft ici, "
                            + "pas un objet personnalisé RPGQuest).",
                    List.of(Field.select("material", "Objet", "material",
                            "Chercher par nom français (« épée en diamant ») ou par identifiant "
                                    + "(« DIAMOND_SWORD »). Seuls les objets réellement "
                                    + "livrables de la version installée sont proposés : un bloc "
                                    + "sans forme d'objet (eau, feu…) est refusé avec son motif.",
                            true), GIVE_AMOUNT)),
            new Descriptor("VARIABLE", "Variable / déblocage", "check",
                    "Pose une variable persistante du joueur (ex. CLAIM_TIER_1 = true).",
                    List.of(
                            Field.text("key", "Clé", "Nom de la variable (ex. CLAIM_TIER_1).", true),
                            Field.text("value", "Valeur", "Valeur brute (ex. true).", true))),
            new Descriptor("COMMAND", "Commande console", "admin",
                    "Exécute une commande console à la fin de la quête. Sensible : validée strictement côté serveur au chargement.",
                    List.of(Field.text("command", "Commande", "Commande sans le « / » initial (ex. give %player% diamond 1).", true))),
            new Descriptor("MONEY", "Pièces (monnaie)", "money",
                    "Crédite le portefeuille persistant du joueur à la fin de la quête. Aucun objet n'est "
                            + "donné : la monnaie RPGQuest est un solde, pas un item, et aucun objet "
                            + "d'inventaire n'est jamais compté comme de l'argent. Le crédit est tracé au "
                            + "journal des transactions et ne peut pas avoir lieu deux fois pour la même "
                            + "complétion.",
                    List.of(Field.integer("amount", "Montant en pièces",
                            "Nombre de pièces créditées (entier > 0). Le montant est une décision "
                                    + "d'équilibrage : rien ne le plafonne côté serveur, un montant "
                                    + "inhabituellement élevé est seulement signalé."))));

    // ---- Actions de dialogue (calquées sur dialogue.model.ActionType) -----------------------

    private static final Field QUEST_REF = Field.select("quest", "Quête", "quest",
            "Identifiant de quête, forme « namespace:clé ».", true);
    private static final Field ITEM_AMOUNT = Field.integer("amount", "Quantité",
            "Nombre d'exemplaires (entier > 0).");

    /**
     * Les douze actions réellement exécutables par un choix de dialogue (issue #146, phase 2).
     *
     * <p><strong>Pourquoi ce catalogue existe.</strong> Jusqu'ici ce vocabulaire ne vivait que dans
     * l'énumération {@code ActionType} du plugin et dans son parseur : le Control Panel ne pouvait ni
     * le contraindre dans le schéma de #110, ni le fournir à une IA. Les deux limites étaient
     * documentées comme telles. Le déclarer ici les lève d'un coup, et
     * {@code DialogueDescriptorsTest} verrouille l'ensemble sur le moteur — exactement comme
     * {@code EditorDescriptorsTest} le fait pour les objectifs.</p>
     */
    public static final List<Descriptor> DIALOGUE_ACTIONS = List.of(
            new Descriptor("START_QUEST", "Démarrer une quête", "book",
                    "Accepte la quête pour le joueur, si elle est réellement disponible pour lui "
                            + "(prérequis, état, répétabilité) — l'action ne contourne aucune règle.",
                    List.of(QUEST_REF)),
            new Descriptor("ADVANCE_QUEST", "Faire avancer une quête", "history",
                    "Passe à l'étape suivante de la quête. Sert aux étapes qui se valident en "
                            + "parlant, sans objectif mesurable.",
                    List.of(QUEST_REF)),
            new Descriptor("TURN_IN_QUEST", "Rendre une quête", "check",
                    "Termine la quête et verse ses récompenses. Sans effet si elle n'est pas "
                            + "réellement prête à être rendue.",
                    List.of(QUEST_REF)),
            new Descriptor("GIVE_ITEM", "Donner un objet", "gift",
                    "Donne N exemplaires d'un objet vanilla au joueur.",
                    List.of(Field.select("material", "Objet", "material",
                            "Chercher par nom français ou par identifiant Minecraft.", true),
                            ITEM_AMOUNT)),
            new Descriptor("TAKE_ITEM", "Reprendre un objet", "admin",
                    "Retire N exemplaires d'un objet au joueur. Pour une remise de quête, préférer "
                            + "DELIVER_QUEST_ITEMS, qui gère les dépôts partiels et ne retire jamais "
                            + "plus que le reliquat.",
                    List.of(Field.select("material", "Objet", "material",
                            "Chercher par nom français ou par identifiant Minecraft.", true),
                            ITEM_AMOUNT)),
            new Descriptor("SET_VARIABLE", "Poser une variable", "check",
                    "Écrit une variable persistante du joueur (déblocage, jalon de scénario).",
                    List.of(Field.text("key", "Clé", "Nom de la variable (ex. CLAIM_TIER_1).", true),
                            Field.text("value", "Valeur",
                                    "Valeur brute (ex. true). Vide = chaîne vide.", false))),
            new Descriptor("RUN_SAFE_COMMAND", "Exécuter une commande autorisée", "admin",
                    "Exécute une commande console. Sensible : seules les commandes de la liste "
                            + "blanche du serveur sont acceptées, et le refus a lieu au chargement.",
                    List.of(Field.text("command", "Commande",
                            "Sans le « / » initial. Doit figurer dans la liste blanche du serveur.", true))),
            new Descriptor("OPEN_DIALOGUE", "Ouvrir un autre dialogue", "dialogues",
                    "Enchaîne sur un autre dialogue. Sert à factoriser une branche commune à "
                            + "plusieurs PNJ.",
                    List.of(Field.select("dialogue", "Dialogue", "dialogue",
                            "Identifiant du dialogue à ouvrir.", true))),
            new Descriptor("OPEN_MERCHANT", "Ouvrir un marchand", "money",
                    "Ouvre l'inventaire d'un marchand PNJ.",
                    List.of(Field.select("merchant", "Marchand", "merchant",
                            "Identifiant du marchand.", true))),
            new Descriptor("GIVE_STARTER_KIT", "Remettre le kit de départ", "gift",
                    "Remet le kit de départ correspondant au palier atteint par le joueur "
                            + "(issue #218). Aucun paramètre : le palier et le contenu viennent de la "
                            + "configuration, jamais du dialogue.",
                    List.of()),
            new Descriptor("DELIVER_QUEST_ITEMS", "Recevoir les objets attendus", "gift",
                    "Traite en une fois toutes les remises que ce PNJ attend du joueur, pour toutes "
                            + "ses quêtes actives. Ne retire jamais plus que le reliquat, et les "
                            + "dépôts partiels sont acquis définitivement (issue #123).",
                    List.of(Field.select("npc", "PNJ destinataire", "npc",
                            "Facultatif : vide = le PNJ porteur du dialogue, par la convention "
                                    + "« identifiant de dialogue = identifiant de PNJ ». Laisser vide "
                                    + "rend la branche réutilisable telle quelle.", false))),
            new Descriptor("CLOSE", "Fermer le dialogue", "check",
                    "Ferme la fenêtre. Aucun paramètre.", List.of()));

    // ---- Conditions de dialogue (calquées sur dialogue.model.ConditionType) -----------------

    /**
     * Les huit conditions réellement évaluables sur un choix de dialogue. Un choix dont une
     * condition est fausse n'est pas affiché.
     *
     * <p>{@link #NEGATE} s'applique à <strong>n'importe laquelle</strong> d'entre elles : il n'est
     * donc pas répété dans chaque descripteur, mais documenté une fois.</p>
     */
    public static final List<Descriptor> DIALOGUE_CONDITIONS = List.of(
            new Descriptor("QUEST_STATE", "État d'une quête", "book",
                    "Vraie si la quête est dans l'état indiqué pour ce joueur.",
                    List.of(QUEST_REF, Field.select("state", "État", "questState",
                            "Un des six états du moteur : NOT_STARTED, ACTIVE, READY_TO_TURN_IN, "
                            + "COMPLETED, FAILED ou ABANDONED.", true))),
            new Descriptor("HAS_ITEM", "Possède un objet", "gift",
                    "Vraie si le joueur possède au moins N exemplaires de l'objet.",
                    List.of(Field.select("material", "Objet", "material",
                            "Chercher par nom français ou par identifiant Minecraft.", true),
                            ITEM_AMOUNT)),
            new Descriptor("HAS_PERMISSION", "Possède une permission", "admin",
                    "Vraie si le joueur a la permission indiquée.",
                    List.of(Field.text("permission", "Permission",
                            "Nœud de permission (ex. rpgquest.admin.world).", true))),
            new Descriptor("VARIABLE_EQUALS", "Variable égale à", "check",
                    "Vraie si la variable du joueur vaut exactement cette valeur.",
                    List.of(Field.text("key", "Clé", "Nom de la variable.", true),
                            Field.text("value", "Valeur",
                                    "Valeur attendue. Vide = chaîne vide.", false))),
            new Descriptor("NO_MAIN_CLAIM", "N'a pas de claim principal", "world",
                    "Vraie si le joueur n'a aucun claim principal. Aucun paramètre.", List.of()),
            new Descriptor("HAS_MAIN_CLAIM", "A un claim principal", "world",
                    "Vraie si le joueur a un claim principal. Aucun paramètre.", List.of()),
            new Descriptor("LACKS_CUSTOM_ITEM", "N'a pas un objet personnalisé", "gift",
                    "Vraie si le joueur ne possède pas l'objet personnalisé RPGQuest indiqué. "
                            + "Reconnaît l'objet par son identité réelle, pas par son nom.",
                    List.of(Field.text("item", "Objet personnalisé",
                            "Identifiant de l'objet RPGQuest.", true))),
            new Descriptor("HAS_PENDING_DELIVERY", "Attend encore des objets", "gift",
                    "Vraie si une quête active du joueur demande de remettre des objets à ce PNJ "
                            + "(issue #123). Permet d'afficher la branche de remise seulement quand "
                            + "elle a un sens.",
                    List.of(Field.select("npc", "PNJ", "npc",
                            "Facultatif : vide = le PNJ porteur du dialogue.", false))));

    /**
     * Champ commun à <strong>toutes</strong> les conditions : {@code negate: true} inverse le
     * verdict. Documenté à part parce qu'il n'appartient à aucun type en particulier.
     */
    public static final Field NEGATE = new Field("negate", "Inverser la condition", FieldType.TEXT,
            "« true » inverse le verdict de la condition. Permet d'exprimer « ce PNJ n'attend plus "
                    + "rien » sans créer un type de condition supplémentaire.", null, false);

    public static Optional<Descriptor> dialogueAction(String kind) {
        return find(DIALOGUE_ACTIONS, kind);
    }

    public static Optional<Descriptor> dialogueCondition(String kind) {
        return find(DIALOGUE_CONDITIONS, kind);
    }

    /** Descripteur d'un {@code kind}, qu'il soit objectif ou récompense. */
    public static Optional<Descriptor> any(String kind) {
        return objective(kind).or(() -> reward(kind));
    }

    /**
     * Noms techniques des champs valides pour un {@code kind} (objectif ou récompense), plus la clé
     * {@code kind} elle-même. Sert de liste blanche pour nettoyer une ligne quand l'utilisateur
     * change de type (#46, point 17 : ne jamais conserver une valeur d'un type précédent).
     */
    public static java.util.Set<String> fieldNames(String kind) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        names.add("kind");
        any(kind).ifPresent(d -> d.fields().forEach(f -> names.add(f.name())));
        return names;
    }

    public static Optional<Descriptor> objective(String kind) {
        return find(OBJECTIVES, kind);
    }

    public static Optional<Descriptor> reward(String kind) {
        return find(REWARDS, kind);
    }

    private static Optional<Descriptor> find(List<Descriptor> all, String kind) {
        if (kind == null) {
            return Optional.empty();
        }
        String k = kind.trim().toUpperCase(Locale.ROOT);
        return all.stream().filter(d -> d.kind().equals(k)).findFirst();
    }
}
