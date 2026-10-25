package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #373 : l'éditeur affichait le dialogue du Garde réduit à deux répliques. {@link MiniYaml}
 * ne connaissait pas les scalaires blocs {@code text: >} : il lisait « > » comme texte, puis
 * s'arrêtait en silence à la ligne suivante — tout le reste du fichier disparaissait, sans aucun
 * problème signalé, et l'enregistrement refusait les réponses menant aux répliques « perdues ».
 *
 * <p>L'oracle est SnakeYAML, le lecteur du moteur : pour chaque contenu du dépôt, le panel doit lire
 * exactement la même chose que le serveur.</p>
 */
class MiniYamlFidelityTest {

    private static final Path RESOURCES = Path.of("..", "src", "main", "resources");

    @Test
    void everyContentFileOfTheRepositoryReadsLikeTheEngine() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(RESOURCES), "ressources du plugin introuvables");
        int checked = 0;
        for (String kind : List.of("dialogues", "quests", "stories")) {
            try (Stream<Path> files = Files.list(RESOURCES.resolve(kind))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".yml")).sorted().toList()) {
                    String text = Files.readString(p);
                    assertEquals(normalize(new Yaml().load(text)), normalize(MiniYaml.parse(text)),
                            kind + "/" + p.getFileName());
                    checked++;
                }
            }
        }
        assertTrue(checked >= 10, checked + " fichier(s) vérifié(s)");
    }

    @Test
    void theGuardDialogueIsReadWhole() throws Exception {
        Path guard = RESOURCES.resolve("dialogues/guard.yml");
        Assumptions.assumeTrue(Files.isRegularFile(guard), "guard.yml introuvable");
        DialogueYaml.ReadResult r = DialogueYaml.read(Files.readString(guard));
        assertTrue(r.ok(), r.problems().toString());
        DialogueDraft d = r.draft();

        // Inventaire relevé avec SnakeYAML sur le fichier source (rapport #373).
        assertEquals(23, d.nodes.size(), "répliques");
        assertEquals(45, d.choiceCount(), "réponses");
        int nexts = 0;
        int conditions = 0;
        int actions = 0;
        Set<String> ids = new HashSet<>();
        d.nodes.forEach(n -> ids.add(n.id));
        for (DialogueDraft.Node n : d.nodes) {
            for (DialogueDraft.Choice c : n.choices) {
                conditions += c.conditions.size();
                actions += c.actions.size();
                if (!c.next.isBlank()) {
                    nexts++;
                    assertTrue(ids.contains(c.next), n.id + " → " + c.next + " doit exister");
                }
            }
        }
        assertEquals(35, nexts, "suites");
        assertEquals(18, conditions, "conditions");
        assertEquals(17, actions, "actions");
        assertTrue(!Diagnostic.hasError(DialogueValidator.validate(d)), DialogueValidator.validate(d).toString());

        // Inventaire mis à jour par #778 (offres first_steps / crystal_hunt retirées du Garde), puis
        // #841 (+5 répliques « Où en suis-je ? », +5 réponses conditionnées ACTIVE, +5 « Merci »).
        DialogueDraft.Node offer = d.nodes.stream().filter(n -> n.id.equals("offer_tier1")).findFirst().orElseThrow();
        assertEquals("<white>La quête s'appelle <gold>Gauthier — Palier 1</gold>.</white><newline> <gray>But : obtenir "
                + "le droit de poser ton premier terrain.</gray><newline> <gray>Ce qu'il faudra faire : repousser les "
                + "quatre menaces qui rôdent autour des murs — 5 araignées, 5 zombies, 5 squelettes et 5 creepers. "
                + "Le palier se valide tout seul à la dernière menace repoussée.</gray>", offer.text, "le « > » n'est plus pris pour le texte");
    }

    @Test
    void openFormSaveReloadKeepsTheGuardIntact() throws Exception {
        Path guard = RESOURCES.resolve("dialogues/guard.yml");
        Assumptions.assumeTrue(Files.isRegularFile(guard), "guard.yml introuvable");
        DialogueDraft d = DialogueYaml.read(Files.readString(guard)).draft();
        String canonical = DialogueYaml.write(d);
        String saved = DialogueYaml.write(DialogueForm.parse(DialogueForm.fields(d)).draft());
        assertEquals(canonical, saved, "formulaire non touché → même dialogue");
        assertTrue(DialogueYaml.roundTripProblems(saved).isEmpty(), DialogueYaml.roundTripProblems(saved).toString());
        // Et le moteur relit la version canonique comme la source (aux blancs finaux près, que le
        // panel a toujours normalisés).
        assertEquals(trimmedTexts(new Yaml().load(Files.readString(guard))), trimmedTexts(new Yaml().load(saved)));
    }

    @Test
    void blockScalarsFollowYamlRules() {
        String yaml = """
                a: >
                  un
                  deux

                  trois # pas un commentaire
                b: |
                  x
                    y
                c: >-
                  fin
                d: |+
                  garde

                e: plain
                """;
        Map<?, ?> m = (Map<?, ?>) MiniYaml.parse(yaml);
        assertEquals(normalize(new Yaml().load(yaml)), normalize(m));
        assertEquals("un deux\ntrois # pas un commentaire\n", m.get("a"));
        assertEquals("x\n  y\n", m.get("b"));
        assertEquals("fin", m.get("c"));
        assertEquals("garde\n\n", m.get("d"));
        assertEquals("plain", m.get("e"));
    }

    @Test
    void aBlockScalarInsideAListItemStopsAtTheSiblingKey() {
        String yaml = """
                choices:
                  - text: >
                      Bonjour
                      à toi
                    next: suite
                """;
        assertEquals(normalize(new Yaml().load(yaml)), normalize(MiniYaml.parse(yaml)));
    }

    @Test
    void anUnreadableLineIsAnErrorNotATruncation() {
        String yaml = """
                nodes:
                  a:
                    text: x
                      suite non comprise
                  b:
                    text: y
                """;
        assertThrows(IllegalArgumentException.class, () -> MiniYaml.parse(yaml));
        assertTrue(!DialogueYaml.read("id: x\n" + yaml).ok(), "l'éditeur passe en lecture seule");
    }

    /** Scalaires en texte : MiniYaml ne type que les booléens, SnakeYAML aussi les nombres. */
    private static Object normalize(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(normalize(v)));
            return out;
        }
        return o == null ? null : String.valueOf(o);
    }

    private static Object trimmedTexts(Object o) {
        Object n = normalize(o);
        return trim(n);
    }

    private static Object trim(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), trim(v)));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(trim(v)));
            return out;
        }
        return o == null ? null : String.valueOf(o).strip();
    }
}
