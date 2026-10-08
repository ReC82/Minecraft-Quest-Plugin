package com.lodygames.rpgquest.panel.content;

import com.lodygames.rpgquest.panel.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrat <strong>machine-readable</strong> du format {@code lodyquests-content-pack} (issue #110) :
 * un JSON Schema, des gabarits téléchargeables, des exemples, et une documentation compacte
 * réutilisable dans un prompt d'IA.
 *
 * <p><strong>Tout est généré</strong>, rien n'est maintenu à la main. Les objectifs et les
 * récompenses sont dérivés de {@link Descriptors}, qui pilote déjà le formulaire, l'écriture YAML,
 * l'aller-retour et la validation du Control Panel — et dont {@code EditorDescriptorsTest} verrouille
 * l'ensemble des types sur l'{@code ObjectiveType} du moteur. Un type d'objectif ajouté au moteur
 * apparaît donc dans le schéma, le gabarit, l'exemple et la documentation sans qu'une seule ligne
 * soit recopiée ici — c'est l'exigence du ticket : « le schéma doit rester dérivé des capacités
 * réelles du moteur et ne pas inventer des propriétés non supportées ».</p>
 *
 * <p><strong>Couverture complète depuis #146 phase 2</strong> : la section {@code dialogues} est
 * désormais contrainte jusqu'au vocabulaire de ses actions et de ses conditions, dérivé de
 * {@link Descriptors#DIALOGUE_ACTIONS} et {@link Descriptors#DIALOGUE_CONDITIONS} — eux-mêmes
 * verrouillés sur les énumérations du moteur par {@code DialogueDescriptorsTest}. C'était la limite
 * explicitement documentée des deux phases précédentes.</p>
 */
public final class ContentPackSchema {

    public static final String FORMAT = "lodyquests-content-pack";
    public static final int SCHEMA_VERSION = 1;
    /** Identifiant public du schéma — versionné par {@link #SCHEMA_VERSION}, jamais par une date. */
    public static final String SCHEMA_ID =
            "https://lodyquests.local/schema/lodyquests-content-pack-v" + SCHEMA_VERSION + ".json";

    /** Familles de contenu du pack, dans l'ordre canonique (miroir de {@code ContentFamily}). */
    public static final List<String> FAMILIES = List.of("quests", "stories", "dialogues", "npcs");

    private ContentPackSchema() {
    }

    // ---- JSON Schema ---------------------------------------------------------------------------

    /** Le schéma officiel, en JSON Schema draft 2020-12, indenté et déterministe. */
    public static String json() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", SCHEMA_ID);
        schema.put("title", FORMAT);
        schema.put("description", "Contenu déclaratif LodyQuests (quêtes, stories, dialogues, PNJ logiques). "
                + "Un pack ne contient jamais de donnée joueur, de progression ni de secret.");
        schema.put("type", "object");
        schema.put("required", List.of("format", "schemaVersion", "content"));
        schema.put("additionalProperties", false);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("format", constString(FORMAT, "Discriminant du format. Toujours cette valeur exacte."));
        props.put("schemaVersion", constInt(SCHEMA_VERSION,
                "Version du contrat. Un import refuse une version qu'il ne connaît pas plutôt que de deviner."));
        props.put("metadata", metadataSchema());
        props.put("dependencies", dependenciesSchema());
        props.put("content", contentSchema());
        schema.put("properties", props);

        schema.put("$defs", defs());
        return Json.writePretty(schema);
    }

    private static Map<String, Object> metadataSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", typed("string", "Titre humain du pack (ex. « Les mines oubliées »)."));
        props.put("author", typed("string", "Auteur ou provenance (ex. « generated-with-ai »)."));
        props.put("generator", typed("string", "Outil ayant produit le pack (ex. « ChatGPT », « rpgquest-plugin-agent »)."));
        props.put("notes", typed("string", "Note libre (ex. « prototype »)."));
        props.put("exportedAt", typed("string", "Instant d'export, ISO-8601 UTC. Informatif."));
        props.put("pluginVersion", typed("string", "Version du plugin au moment de l'export. Informatif."));
        props.put("families", arrayOf(typed("string", null), "Familles réellement incluses. Informatif."));
        props.put("counts", object(null,
                "Nombre d'éléments par famille, clés = noms de familles. Informatif."));

        Map<String, Object> out = object(props,
                "Métadonnées purement informatives : elles documentent la provenance et n'influent "
                        + "jamais sur le gameplay. Un import ne doit dépendre que de « format » et "
                        + "« schemaVersion ».");
        out.put("additionalProperties", true);
        return out;
    }

    private static Map<String, Object> dependenciesSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("quests", arrayOf(ref("questId"), "Ids de quêtes dont ce pack a besoin."));
        props.put("npcs", arrayOf(ref("logicalId"), "Ids de PNJ logiques dont ce pack a besoin."));
        props.put("dialogues", arrayOf(ref("logicalId"), "Ids de dialogues dont ce pack a besoin."));
        props.put("items", arrayOf(ref("logicalId"), "Ids d'objets personnalisés dont ce pack a besoin."));
        return object(props,
                "Dépendances externes déclarées. Une dépendance peut être satisfaite par le pack "
                        + "lui-même, déjà présente sur le serveur, ou manquante — l'import les distingue "
                        + "et ne devine jamais.");
    }

    private static Map<String, Object> contentSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("quests", arrayOf(ref("quest"), "Quêtes du pack."));
        props.put("stories", arrayOf(ref("story"), "Stories du pack."));
        props.put("dialogues", arrayOf(ref("dialogue"), "Dialogues du pack."));
        props.put("npcs", arrayOf(ref("npc"), "PNJ logiques du pack."));
        Map<String, Object> out = object(props,
                "Sections de contenu. Toutes facultatives : un pack peut ne contenir qu'une famille, "
                        + "voire qu'un seul élément.");
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> defs() {
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("questId", pattern("^[a-z0-9_]+:[a-z0-9_]+$",
                "Identifiant de quête, forme « namespace:clé » (minuscules, chiffres, « _ »). "
                        + "Jamais un chemin de fichier, jamais un titre."));
        defs.put("logicalId", pattern("^[a-z0-9_]+$",
                "Identifiant logique (PNJ, dialogue, objet, étape) : minuscules, chiffres, « _ ». "
                        + "Stable et indépendant du nom affiché."));
        defs.put("text", text());
        defs.put("quest", quest());
        defs.put("step", step());
        defs.put("objective", oneOfDescriptors(Descriptors.OBJECTIVES, "type",
                "Objectif d'étape. « type » détermine les autres champs ; aucun champ d'un autre type "
                        + "n'est accepté."));
        defs.put("reward", oneOfDescriptors(Descriptors.REWARDS, "type",
                "Récompense accordée à la complétion de la quête."));
        // Issue #146 phase 2 : le vocabulaire de dialogue est désormais DÉRIVÉ des descripteurs,
        // donc contraint comme les objectifs. C'était la limite explicitement documentée de #110.
        defs.put("dialogueAction", oneOfDescriptors(Descriptors.DIALOGUE_ACTIONS, "type",
                "Action exécutée par un choix de dialogue. « type » détermine les autres champs ; "
                        + "aucun champ d'un autre type n'est accepté."));
        defs.put("dialogueCondition", oneOfDescriptors(Descriptors.DIALOGUE_CONDITIONS, "type",
                "Condition d'affichage d'un choix. « negate: true » inverse le verdict de n'importe "
                        + "laquelle.", Map.of(Descriptors.NEGATE.name(), negateSchema())));
        defs.put("story", story());
        defs.put("npc", npc());
        defs.put("dialogue", dialogue());
        return defs;
    }

    private static Map<String, Object> text() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "string");
        out.put("description", "Texte affiché au joueur. Le formatage se fait en MiniMessage "
                + "(ex. « <gold>Titre</gold> ») ; le texte brut est accepté et reste lisible.");
        return out;
    }

    private static Map<String, Object> quest() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", ref("questId"));
        props.put("title", ref("text"));
        props.put("description", ref("text"));
        props.put("category", typed("string", "Regroupement éditorial libre (ex. « hunting »)."));
        props.put("icon", typed("string", "Material Minecraft servant d'icône dans le journal (ex. « IRON_SWORD »)."));
        props.put("repeatable", typed("boolean", "La quête peut-elle être refaite une fois terminée ?"));
        props.put("secret", typed("boolean", "Masquée dans le journal tant qu'elle n'est pas découverte."));
        props.put("giver", ref("logicalId"));
        props.put("prerequisites", arrayOf(ref("questId"),
                "Quêtes qui doivent être terminées avant de pouvoir accepter celle-ci."));
        props.put("steps", arrayOfMin(ref("step"), 1, "Étapes ordonnées. Au moins une."));
        props.put("rewards", arrayOf(ref("reward"), "Récompenses de fin de quête."));
        props.put("variables", object(null,
                "Variables éditoriales du contenu (clé → valeur, chaînes). Jamais des variables joueur."));

        Map<String, Object> out = object(props, "Une quête.");
        out.put("required", List.of("id", "title", "steps"));
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> step() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", ref("logicalId"));
        props.put("objectives", arrayOfMin(ref("objective"), 1,
                "Objectifs de l'étape. Au moins un ; tous doivent être remplis pour passer à la suivante."));
        Map<String, Object> out = object(props, "Une étape de quête.");
        out.put("required", List.of("id", "objectives"));
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> story() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", ref("logicalId"));
        props.put("name", ref("text"));
        props.put("secret", typed("boolean", "Masquée tant qu'elle n'est pas découverte."));
        props.put("quests", arrayOfMin(ref("questId"), 1,
                "Quêtes de la story, dans l'ordre de progression. Elles doivent exister (dans ce pack "
                        + "ou déjà sur le serveur). C'est la clé que lit réellement le moteur."));
        props.put("questIds", arrayOfMin(ref("questId"), 1,
                "Orthographe héritée des packs exportés jusqu'au 2026-10-08. Toujours acceptée à "
                        + "l'import, mais ne plus l'écrire : préférer « quests »."));
        Map<String, Object> out = object(props,
                "Une story : un enchaînement ordonné de quêtes existantes. Une story ne définit jamais "
                        + "d'objectif par elle-même.");
        out.put("required", List.of("id", "name"));
        // L'une des deux orthographes doit être présente, jamais aucune : une story sans quête ne
        // veut rien dire. « quests » est la clé officielle, « questIds » l'héritage toléré.
        out.put("anyOf", List.of(
                Map.of("required", List.of("quests")),
                Map.of("required", List.of("questIds"))));
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> npc() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", ref("logicalId"));
        props.put("displayName", typed("string", "Nom affiché au-dessus du PNJ."));
        props.put("description", typed("string", "Note éditoriale, jamais affichée en jeu."));
        props.put("dialogue", ref("logicalId"));
        props.put("role", typed("string", "Rôle éditorial (ex. « guide », « marchand »)."));
        props.put("enabled", typed("boolean", "PNJ actif."));
        Map<String, Object> out = object(props,
                "Un PNJ logique : identité éditoriale seulement. Aucune position, aucun monde, aucune "
                        + "dépendance à Citizens — le placement en jeu est une opération d'administration "
                        + "distincte.");
        out.put("required", List.of("id", "displayName"));
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> dialogue() {
        Map<String, Object> choiceProps = new LinkedHashMap<>();
        choiceProps.put("text", ref("text"));
        choiceProps.put("next", typed("string", "Id du nœud suivant, ou absent pour terminer."));
        choiceProps.put("conditions", arrayOf(ref("dialogueCondition"),
                "Conditions d'affichage du choix. Un choix dont une condition est fausse n'est pas "
                        + "montré au joueur."));
        choiceProps.put("actions", arrayOf(ref("dialogueAction"),
                "Actions exécutées quand le choix est pris, dans l'ordre."));
        Map<String, Object> choice = object(choiceProps, "Un choix proposé au joueur.");
        choice.put("required", List.of("text"));

        Map<String, Object> nodeProps = new LinkedHashMap<>();
        nodeProps.put("speaker", typed("string", "Nom affiché du locuteur."));
        nodeProps.put("text", ref("text"));
        nodeProps.put("choices", arrayOf(choice, "Choix du nœud."));
        Map<String, Object> node = object(nodeProps, "Un nœud de dialogue.");
        node.put("required", List.of("speaker", "text"));
        node.put("additionalProperties", false);

        // Les nœuds forment une MAP « id de nœud → nœud », et non une liste. C'est la forme que
        // le moteur lit et que l'export du plugin écrit ; l'id du nœud est donc la clé, et n'est
        // jamais répété à l'intérieur. Une liste serait refusée à l'import.
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("type", "object");
        nodes.put("description", "Nœuds du dialogue, indexés par identifiant de nœud. Au moins un. "
                + "L'identifiant est la CLÉ : ce n'est pas une liste.");
        nodes.put("propertyNames", pattern("^[a-z0-9_][a-z0-9_-]{0,63}$",
                "Identifiant de nœud : minuscules, chiffres, « _ » et « - »."));
        nodes.put("additionalProperties", node);
        nodes.put("minProperties", 1);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", ref("logicalId"));
        props.put("start", ref("logicalId"));
        props.put("nodes", nodes);
        Map<String, Object> out = object(props,
                "Un dialogue. Convention du moteur : l'id d'un dialogue est l'id du PNJ qui le porte.");
        out.put("required", List.of("id", "start", "nodes"));
        out.put("additionalProperties", false);
        return out;
    }

    /**
     * Construit un {@code oneOf} à partir des descripteurs réels : une branche par type, avec ses
     * champs, ses champs obligatoires et son aide. Rien n'est recopié — un type retiré du moteur
     * disparaît d'ici, un type ajouté y apparaît.
     */
    private static Map<String, Object> oneOfDescriptors(List<Descriptors.Descriptor> catalog,
                                                         String discriminator, String description) {
        return oneOfDescriptors(catalog, discriminator, description, Map.of());
    }

    /**
     * @param commonProps propriétés acceptées par <strong>toutes</strong> les branches —
     *                    {@code negate} pour les conditions de dialogue, qui n'appartient à aucun
     *                    type en particulier. Sans cela, {@code additionalProperties: false} le
     *                    refuserait partout. Ce sont des schémas déjà formés, et non des
     *                    {@code Field} : {@code negate} est un <strong>booléen</strong> pour le
     *                    moteur ({@code getBoolean}), alors qu'aucun {@code FieldType} ne décrit un
     *                    booléen — le dériver d'un descripteur l'aurait déclaré « string », et un
     *                    pack écrivant {@code negate: true} aurait été refusé par tout validateur.
     */
    private static Map<String, Object> oneOfDescriptors(List<Descriptors.Descriptor> catalog,
                                                         String discriminator, String description,
                                                         Map<String, Object> commonProps) {
        List<Object> branches = new ArrayList<>();
        for (Descriptors.Descriptor d : catalog) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(discriminator, constString(d.kind(), d.label()));
            List<String> required = new ArrayList<>();
            required.add(discriminator);
            for (Descriptors.Field f : d.fields()) {
                props.put(f.name(), fieldSchema(f));
                if (f.required()) {
                    required.add(f.name());
                }
            }
            props.putAll(commonProps);
            Map<String, Object> branch = object(props, d.hint());
            branch.put("required", required);
            branch.put("additionalProperties", false);
            branches.add(branch);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description", description);
        out.put("oneOf", branches);
        return out;
    }

    /** {@code negate} : un booléen, et le seul champ commun à toutes les conditions. */
    private static Map<String, Object> negateSchema() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "boolean");
        out.put("title", Descriptors.NEGATE.label());
        out.put("description", Descriptors.NEGATE.help());
        return out;
    }

    private static Map<String, Object> fieldSchema(Descriptors.Field f) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (f.type()) {
            case INT -> {
                out.put("type", "integer");
                out.put("exclusiveMinimum", 0);
            }
            case DOUBLE -> out.put("type", "number");
            case LIST -> {
                out.put("type", "array");
                out.put("items", typed("string", null));
                out.put("uniqueItems", true);
            }
            default -> out.put("type", "string");
        }
        out.put("title", f.label());
        if (f.help() != null && !f.help().isBlank()) {
            out.put("description", f.help());
        }
        return out;
    }

    // ---- Petits constructeurs de schéma --------------------------------------------------------

    private static Map<String, Object> constString(String value, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "string");
        out.put("const", value);
        if (description != null) {
            out.put("description", description);
        }
        return out;
    }

    private static Map<String, Object> constInt(int value, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "integer");
        out.put("const", value);
        out.put("description", description);
        return out;
    }

    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        if (description != null) {
            out.put("description", description);
        }
        return out;
    }

    private static Map<String, Object> pattern(String regex, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "string");
        out.put("pattern", regex);
        out.put("description", description);
        return out;
    }

    private static Map<String, Object> object(Map<String, Object> properties, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        if (description != null) {
            out.put("description", description);
        }
        if (properties != null) {
            out.put("properties", properties);
        }
        return out;
    }

    private static Map<String, Object> arrayOf(Object items, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "array");
        if (description != null) {
            out.put("description", description);
        }
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> arrayOfMin(Object items, int min, String description) {
        Map<String, Object> out = arrayOf(items, description);
        out.put("minItems", min);
        return out;
    }

    private static Map<String, Object> ref(String def) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("$ref", "#/$defs/" + def);
        return out;
    }
}
