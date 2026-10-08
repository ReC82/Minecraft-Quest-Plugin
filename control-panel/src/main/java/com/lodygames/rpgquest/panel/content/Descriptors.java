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
