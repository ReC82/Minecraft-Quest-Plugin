package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Issue #110 — contrat machine-readable du format {@code lodyquests-content-pack}.
 *
 * <p>Ces tests ne vérifient pas une liste recopiée : ils vérifient que le schéma, les gabarits et la
 * documentation <strong>sont réellement dérivés</strong> de {@link Descriptors}. Un type d'objectif
 * ajouté au moteur sans apparaître dans le contrat fait donc échouer la suite, ce qui est exactement
 * le filet demandé par le ticket (« le schéma doit rester dérivé des capacités réelles du moteur »).
 * {@code EditorDescriptorsTest} verrouille de son côté l'ensemble des descripteurs sur
 * l'{@code ObjectiveType} du moteur : les deux tests mis bout à bout relient le contrat au moteur.</p>
 */
class ContentPackContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() {
        Object parsed = Json.parse(ContentPackSchema.json());
        assertTrue(parsed instanceof Map, "le schéma doit être un objet JSON valide");
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        assertNotNull(o, "noeud de schéma absent");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        assertNotNull(o, "liste de schéma absente");
        return (List<Object>) o;
    }

    private static Map<String, Object> def(String name) {
        return map(map(schema().get("$defs")).get(name));
    }

    // ---- Schéma --------------------------------------------------------------------------------

    @Test
    void theSchemaIsValidJsonAndDeclaresItsDraftAndIdentity() {
        Map<String, Object> schema = schema();

        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema"));
        assertEquals(ContentPackSchema.SCHEMA_ID, schema.get("$id"));
        assertEquals(ContentPackSchema.FORMAT, schema.get("title"));
        assertEquals("object", schema.get("type"));
    }

    /** {@code format} et {@code schemaVersion} sont les seuls champs sur lesquels un import décide. */
    @Test
    void theEnvelopePinsFormatAndVersionAsConstants() {
        Map<String, Object> props = map(schema().get("properties"));

        assertEquals(ContentPackSchema.FORMAT, map(props.get("format")).get("const"));
        assertEquals(Long.valueOf(ContentPackSchema.SCHEMA_VERSION),
                ((Number) map(props.get("schemaVersion")).get("const")).longValue());
        assertEquals(List.of("format", "schemaVersion", "content"), schema().get("required"));
        assertEquals(Boolean.FALSE, schema().get("additionalProperties"));
    }

    /** Une famille inconnue dans {@code content:} est refusée, jamais ignorée en silence. */
    @Test
    void contentDeclaresExactlyTheCanonicalFamiliesAndRefusesOthers() {
        Map<String, Object> content = map(map(schema().get("properties")).get("content"));

        assertEquals(new LinkedHashSet<>(ContentPackSchema.FAMILIES),
                new LinkedHashSet<>(map(content.get("properties")).keySet()));
        assertEquals(Boolean.FALSE, content.get("additionalProperties"));
    }

    @Test
    void objectiveBranchesAreExactlyTheRealObjectiveTypes() {
        Set<String> inSchema = branchKinds(def("objective"));

        Set<String> expected = new LinkedHashSet<>(
                Descriptors.OBJECTIVES.stream().map(Descriptors.Descriptor::kind).toList());
        assertEquals(expected, inSchema,
                "le schéma doit suivre les descripteurs réels, sans type en trop ni type manquant");
    }

    @Test
    void rewardBranchesAreExactlyTheRealRewardTypes() {
        Set<String> expected = new LinkedHashSet<>(
                Descriptors.REWARDS.stream().map(Descriptors.Descriptor::kind).toList());

        assertEquals(expected, branchKinds(def("reward")));
    }

    /**
     * Les champs obligatoires du schéma sont ceux déclarés obligatoires par les descripteurs, et
     * aucun autre champ n'est accepté — sinon une IA pourrait produire un pack « valide » portant une
     * propriété que le moteur ignore silencieusement.
     */
    @Test
    void eachBranchRequiresExactlyTheDescriptorRequiredFieldsAndNothingElse() {
        for (Object raw : list(def("objective").get("oneOf"))) {
            Map<String, Object> branch = map(raw);
            String kind = (String) map(map(branch.get("properties")).get("type")).get("const");
            Descriptors.Descriptor descriptor = Descriptors.objective(kind).orElseThrow();

            List<String> expectedRequired = new ArrayList<>();
            expectedRequired.add("type");
            descriptor.fields().stream().filter(Descriptors.Field::required)
                    .map(Descriptors.Field::name).forEach(expectedRequired::add);
            assertEquals(expectedRequired, branch.get("required"), "champs obligatoires de " + kind);

            Set<String> expectedProps = new LinkedHashSet<>();
            expectedProps.add("type");
            descriptor.fields().forEach(f -> expectedProps.add(f.name()));
            assertEquals(expectedProps, new LinkedHashSet<>(map(branch.get("properties")).keySet()),
                    "champs déclarés de " + kind);
            assertEquals(Boolean.FALSE, branch.get("additionalProperties"), "champ étranger refusé pour " + kind);
        }
    }

    /** Une quantité est un entier strictement positif, comme le moteur l'exige. */
    @Test
    void integerFieldsAreConstrainedToStrictlyPositiveIntegers() {
        Map<String, Object> branch = branch(def("objective"), "KILL_ENTITY");
        Map<String, Object> amount = map(map(branch.get("properties")).get("amount"));

        assertEquals("integer", amount.get("type"));
        assertEquals(0L, ((Number) amount.get("exclusiveMinimum")).longValue());
    }

    /** Issue #185 : un champ liste devient un tableau de chaînes sans doublon, pas une chaîne. */
    @Test
    void listFieldsBecomeUniqueStringArrays() {
        Map<String, Object> branch = branch(def("objective"), "DISCOVER_WAYPOINT");
        Map<String, Object> worlds = map(map(branch.get("properties")).get("worlds"));

        assertEquals("array", worlds.get("type"));
        assertEquals("string", map(worlds.get("items")).get("type"));
        assertEquals(Boolean.TRUE, worlds.get("uniqueItems"));
    }

    @Test
    void questIdsAreConstrainedToTheNamespacedForm() {
        String regex = (String) def("questId").get("pattern");

        assertTrue("rpgquest:tc110_descente".matches(regex), regex);
        assertFalse("RPGQuest:Descente".matches(regex), "les majuscules ne sont pas un id valide");
        assertFalse("quests/descente.yml".matches(regex), "un chemin de fichier n'est pas un id");
        assertFalse("descente".matches(regex), "le namespace est obligatoire");
    }

    @Test
    void aQuestMustCarryAnIdATitleAndAtLeastOneStepWithAtLeastOneObjective() {
        assertEquals(List.of("id", "title", "steps"), def("quest").get("required"));
        assertEquals(1L, ((Number) map(map(def("quest").get("properties")).get("steps")).get("minItems")).longValue());
        assertEquals(1L, ((Number) map(map(def("step").get("properties")).get("objectives")).get("minItems")).longValue());
    }

    /** Les dépendances existent dans le contrat : un import doit pouvoir les confronter au serveur. */
    @Test
    void dependenciesAreDeclarableForEveryReferencedFamily() {
        Map<String, Object> deps = map(map(schema().get("properties")).get("dependencies"));

        assertEquals(Set.of("quests", "npcs", "dialogues", "items"),
                map(deps.get("properties")).keySet());
    }

    /** Les métadonnées restent ouvertes et informatives : un générateur peut y ajouter sa provenance. */
    @Test
    void metadataStaysOpenAndPurelyInformative() {
        Map<String, Object> metadata = map(map(schema().get("properties")).get("metadata"));

        assertEquals(Boolean.TRUE, metadata.get("additionalProperties"));
        assertTrue(map(metadata.get("properties")).containsKey("generator"));
        assertTrue(((String) metadata.get("description")).contains("informatives"));
    }

    // ---- Gabarits et exemples ------------------------------------------------------------------

    @Test
    void theFullTemplateListsEveryRealObjectiveAndRewardType() {
        String template = ContentPackTemplates.template(null);

        for (Descriptors.Descriptor d : Descriptors.OBJECTIVES) {
            assertTrue(template.contains(d.kind()), "objectif absent du gabarit : " + d.kind());
        }
        for (Descriptors.Descriptor d : Descriptors.REWARDS) {
            assertTrue(template.contains(d.kind()), "récompense absente du gabarit : " + d.kind());
        }
    }

    @Test
    void eachFamilyTemplateCarriesTheEnvelopeAndItsOwnSection() {
        for (String family : ContentPackSchema.FAMILIES) {
            String template = ContentPackTemplates.template(family);

            assertTrue(template.contains("format: " + ContentPackSchema.FORMAT), family);
            assertTrue(template.contains("schemaVersion: " + ContentPackSchema.SCHEMA_VERSION), family);
            assertTrue(template.contains("  " + family + ":"), "section « " + family + " » absente");
        }
    }

    /** Une famille inconnue n'échoue pas : elle donne le gabarit complet, qui est un sur-ensemble valide. */
    @Test
    void anUnknownFamilyFallsBackToTheFullTemplate() {
        assertEquals(ContentPackTemplates.template(null), ContentPackTemplates.template("recettes"));
    }

    @Test
    void theMinimalExampleIsAParsableOneObjectivePack() {
        Object parsed = MiniYaml.parse(ContentPackTemplates.minimalExample());

        Map<String, Object> pack = map(parsed);
        assertEquals(ContentPackSchema.FORMAT, pack.get("format"));
        List<Object> quests = list(map(pack.get("content")).get("quests"));
        assertEquals(1, quests.size());
        Map<String, Object> quest = map(quests.get(0));
        assertEquals(1, list(quest.get("steps")).size());
        assertEquals(1, list(map(list(quest.get("steps")).get(0)).get("objectives")).size());
    }

    /**
     * Tout élément DÉFINI par l'exemple complet porte le préfixe {@code tc110_} : il est donc
     * reconnaissable et supprimable après essai, sans risque de confusion avec du contenu de
     * production. Les seules références non préfixées sont des dépendances, et elles sont déclarées.
     */
    @Test
    void everyElementDefinedByTheCompleteExampleIsPrefixedAndItsDependenciesAreDeclared() {
        Map<String, Object> pack = map(MiniYaml.parse(ContentPackTemplates.completeExample()));
        Map<String, Object> content = map(pack.get("content"));

        for (String family : ContentPackSchema.FAMILIES) {
            for (Object raw : list(content.get(family))) {
                String id = String.valueOf(map(raw).get("id"));
                String bare = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                assertTrue(bare.startsWith("tc110_"), family + " : id non préfixé « " + id + " »");
            }
        }

        Map<String, Object> dependencies = map(pack.get("dependencies"));
        assertTrue(list(dependencies.get("quests")).contains("rpgquest:first_steps"),
                "la quête externe utilisée en prérequis doit être déclarée en dépendance");
        assertTrue(list(dependencies.get("npcs")).contains("tc110_mineur"),
                "une dépendance satisfaite par le pack lui-même reste déclarée : l'import doit pouvoir "
                        + "la reconnaître comme telle");
    }

    @Test
    void theCompleteExampleChainsItsQuestsThroughAStory() {
        Map<String, Object> content = map(map(MiniYaml.parse(ContentPackTemplates.completeExample())).get("content"));

        List<Object> stories = list(content.get("stories"));
        assertEquals(1, stories.size());
        List<Object> questIds = list(map(stories.get(0)).get("quests"));
        assertEquals(List.of("rpgquest:tc110_descente", "rpgquest:tc110_remonter"), questIds);

        Set<String> defined = new LinkedHashSet<>();
        for (Object raw : list(content.get("quests"))) {
            defined.add(String.valueOf(map(raw).get("id")));
        }
        assertTrue(defined.containsAll(questIds), "une story ne référence que des quêtes réellement fournies");
    }

    /** Le PNJ et son dialogue partagent le même id : c'est la convention du moteur, pas un hasard. */
    @Test
    void theCompleteExampleFollowsTheDialogueIdConvention() {
        Map<String, Object> content = map(map(MiniYaml.parse(ContentPackTemplates.completeExample())).get("content"));

        Map<String, Object> npc = map(list(content.get("npcs")).get(0));
        Map<String, Object> dialogue = map(list(content.get("dialogues")).get(0));
        assertEquals(npc.get("id"), dialogue.get("id"));
        assertEquals(npc.get("id"), npc.get("dialogue"));
    }

    // ---- Documentation pour IA -----------------------------------------------------------------

    @Test
    void theAiDocumentationDescribesEveryRealTypeWithItsFields() {
        String doc = ContentPackTemplates.aiDocumentation();

        for (Descriptors.Descriptor d : Descriptors.OBJECTIVES) {
            assertTrue(doc.contains("`" + d.kind() + "`"), "objectif absent de la documentation : " + d.kind());
            for (Descriptors.Field f : d.fields()) {
                assertTrue(doc.contains("`" + f.name() + "`"),
                        "champ absent de la documentation : " + d.kind() + "." + f.name());
            }
        }
        for (Descriptors.Descriptor d : Descriptors.REWARDS) {
            assertTrue(doc.contains("`" + d.kind() + "`"), "récompense absente : " + d.kind());
        }
    }

    @Test
    void theAiDocumentationAnnouncesTheRealTypeCounts() {
        String doc = ContentPackTemplates.aiDocumentation();

        assertTrue(doc.contains("## Objectifs disponibles (" + Descriptors.OBJECTIVES.size() + ")"), doc.substring(0, 200));
        assertTrue(doc.contains("## Récompenses disponibles (" + Descriptors.REWARDS.size() + ")"));
    }

    /** Les deux exemples sont embarqués dans la documentation : elle se suffit à elle-même. */
    @Test
    void theAiDocumentationEmbedsBothExamplesAndStatesItsLimits() {
        String doc = ContentPackTemplates.aiDocumentation();

        assertTrue(doc.contains("## Exemple minimal"));
        assertTrue(doc.contains("## Exemple complet multi-éléments"));
        assertTrue(doc.contains("tc110_descente"), "l'exemple complet doit être inclus en entier");
        assertTrue(doc.contains("Limite de cette version du contrat"),
                "la limite sur le vocabulaire de dialogue doit être énoncée, jamais passée sous silence");
        assertTrue(doc.contains("Aucune contrainte d'équilibrage n'est formalisée"),
                "ne jamais inventer de règle d'équilibrage");
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static Set<String> branchKinds(Map<String, Object> def) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Object raw : list(def.get("oneOf"))) {
            kinds.add((String) map(map(map(raw).get("properties")).get("type")).get("const"));
        }
        return kinds;
    }

    private static Map<String, Object> branch(Map<String, Object> def, String kind) {
        for (Object raw : list(def.get("oneOf"))) {
            Map<String, Object> branch = map(raw);
            if (kind.equals(map(map(branch.get("properties")).get("type")).get("const"))) {
                return branch;
            }
        }
        throw new AssertionError("branche absente du schéma : " + kind);
    }
}
