package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Issue #263 — ce que le prompt ENSEIGNE au modèle, confronté à ce que le moteur ACCEPTE.
 *
 * <p>Le prompt vit dans le Control Panel ({@code BuildingPromptBuilder}), le moteur dans le plugin :
 * aucun des deux modules ne dépend de l'autre, et c'est volontaire. Mais le défaut constaté en réel
 * venait exactement de cette séparation : le prompt décrivait l'échelle par « facade, from, to (le bas
 * et le haut) », le modèle a écrit {@code from: [1, 1]}, et le moteur l'a refusée. Rien ne confrontait
 * les deux textes.</p>
 *
 * <p>Ce test lit donc le <strong>source réel</strong> du prompt dans le dépôt (même principe que
 * {@code BundledExamplesDriftTest} côté panel) et fait analyser par le vrai {@code BuildingPlanYaml}
 * l'exemple complet et chaque exemple minimal. Il s'abstient, au lieu d'échouer pour la mauvaise
 * raison, si le source du panel n'est pas à côté.</p>
 */
class PromptPlanConsistencyTest {

    private static final Path PROMPT_SOURCE = Path.of(
            "control-panel/src/main/java/com/lodygames/rpgquest/panel/ai/BuildingPromptBuilder.java");
    private static final Path PARSER_SOURCE = Path.of(
            "src/main/java/com/lodygames/rpgquest/building/plan/BuildingPlanYaml.java");

    private static List<String> prompt;

    @BeforeAll
    static void readPrompt() throws IOException {
        Assumptions.assumeTrue(Files.exists(PROMPT_SOURCE),
                "source du Control Panel absent : test de cohérence non applicable ici");
        String source = Files.readString(PROMPT_SOURCE);
        int start = source.indexOf("return \"\"\"", source.indexOf("static String systemPrompt()"));
        // Le texte s'arrête au """ fermant, suivi de « ; » ou d'une concaténation (#289 : le tableau
        // des capacités du moteur est ajouté au prompt système).
        int end = source.indexOf("\"\"\"", source.indexOf('\n', start) + 1);
        assertTrue(start > 0 && end > start, "texte du prompt système introuvable");
        prompt = dedent(source.substring(source.indexOf('\n', start) + 1, end));
    }

    // ---- L'exemple complet --------------------------------------------------------------------

    @Test
    void theCompleteExampleIsAcceptedAndPassesTheArchitecturalContract() {
        String yaml = section("UN EXEMPLE COMPLET ET VALIDE", "Remarque ce que cet exemple");
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("exemple complet refusé : " + problems));

        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        ArchitecturalAudit.Report report =
                ArchitecturalAudit.audit(expansion.blueprint(), plan.front(), plan.rules(),
                        expansion.provenance(), plan.anchorY());
        assertTrue(report.blocking().isEmpty(),
                "l'exemple que le prompt montre comme valide doit l'être pour l'audit : "
                        + report.blocking());
        // #282 : l'exemple montre DEUX étages réellement reliés — c'est ce que le modèle imitera.
        assertTrue(report.metrics().floors() == 2 && report.metrics().reachableFloors() == 2,
                "deux étages atteignables attendus : " + report.metrics());
    }

    /**
     * #472 : l'exemple d'estrade — plateau sur le terrain, marches d'entrée par façade — est
     * accepté et passe l'audit. C'est le gabarit que le modèle imitera pour l'estrade d'Elias.
     */
    @Test
    void theEstradeExampleIsAcceptedAndPassesTheArchitecturalContract() {
        String yaml = section("UN EXEMPLE VALIDE D'ESTRADE", "Le plateau (7 × 5");
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError("exemple d'estrade refusé : " + problems));
        BuildingPlanExpander.Result expansion = BuildingPlanExpander.expand(plan);
        assertTrue(expansion.ok(), expansion.error());
        ArchitecturalAudit.Report report = ArchitecturalAudit.audit(expansion.blueprint(),
                plan.front(), plan.rules(), expansion.provenance(), plan.anchorY());
        assertTrue(report.blocking().isEmpty(), "l'estrade du prompt : " + report.blocking());
    }

    // ---- Les exemples minimaux --------------------------------------------------------------

    /** Chaque exemple minimal, inséré tel quel dans un plan, est accepté par le vrai parseur. */
    @Test
    void everyMinimalExampleIsAcceptedByTheRealParser() {
        List<String> fragments = fragments(section("UN EXEMPLE MINIMAL PAR INSTRUCTION D'ARCHITECTURE",
                "L'ORDRE COMPTE"));
        assertTrue(fragments.size() >= 6, "un exemple par instruction d'architecture : " + fragments.size());

        for (String fragment : fragments) {
            List<String> problems = new ArrayList<>();
            boolean ok = BuildingPlanYaml.read(planWith(fragment), problems).isPresent();
            assertTrue(ok, "exemple du prompt REFUSÉ par le moteur :\n" + fragment + "\n→ " + problems);
        }
    }

    /** Toutes les instructions d'architecture du moteur ont leur exemple — aucune n'est oubliée. */
    @Test
    void everyArchitecturalInstructionHasAMinimalExample() {
        String fragments = section("UN EXEMPLE MINIMAL PAR INSTRUCTION D'ARCHITECTURE", "L'ORDRE COMPTE");
        for (String op : List.of("door", "window", "light", "staircase", "ladder", "pillars")) {
            assertTrue(fragments.contains("- op: " + op), "exemple manquant pour « " + op + " »");
        }
    }

    /** Le cas réel, pour qu'il ne revienne pas : il reste refusé, avec un message qui dit quoi écrire. */
    @Test
    void theRealFaultyLadderIsRefusedWithAnActionableMessage() {
        List<String> problems = new ArrayList<>();
        boolean ok = BuildingPlanYaml.read(planWith("""
                  - op: ladder
                    facade: BACK
                    from: [1, 1]
                    to: [1, 5]
                    block: minecraft:ladder
                """), problems).isPresent();

        assertFalse(ok);
        String message = String.join(" ", problems);
        assertTrue(message.contains("2 nombre(s) reçu(s), il en faut TROIS"), message);
        assertTrue(message.contains("« facade » ne remplace aucune coordonnée"), message);
    }

    /** Un « to » d'échelle hors de la colonne n'est plus ignoré en silence. */
    @Test
    void aSlantedLadderIsRefused() {
        List<String> problems = new ArrayList<>();
        boolean ok = BuildingPlanYaml.read(planWith("""
                  - op: ladder
                    facade: BACK
                    from: [1, 1, 5]
                    to: [3, 4, 5]
                    block: minecraft:ladder
                """), problems).isPresent();

        assertFalse(ok);
        assertTrue(String.join(" ", problems).contains("une échelle est verticale"), problems.toString());
    }

    // ---- Les clés documentées ------------------------------------------------------------------

    /**
     * Chaque clé que le prompt annonce (« Clés : … ») est une clé que le parseur lit réellement.
     * C'est la classe d'écart trouvée à la main pendant la V2 (« offset » annoncé pour l'échelle et
     * ignoré par la primitive) : elle est maintenant vérifiée.
     */
    @Test
    void everyDocumentedKeyIsReadByTheParser() throws IOException {
        Set<String> parsed = parserKeys();
        Set<String> documented = documentedKeys();
        assertFalse(documented.isEmpty(), "aucune clé documentée trouvée : extraction à revoir");
        Set<String> unknown = new LinkedHashSet<>(documented);
        unknown.removeAll(parsed);
        assertEquals(Set.of(), unknown, "clés annoncées par le prompt que le parseur ne lit pas");
    }

    // ---- Extraction ----------------------------------------------------------------------------

    private static String section(String startMarker, String endMarker) {
        int start = -1;
        int end = -1;
        for (int i = 0; i < prompt.size(); i++) {
            if (start < 0 && prompt.get(i).startsWith(startMarker)) {
                start = i + 1;
            } else if (start >= 0 && prompt.get(i).startsWith(endMarker)) {
                end = i;
                break;
            }
        }
        assertTrue(start > 0 && end > start, "section du prompt introuvable : " + startMarker);
        return String.join("\n", prompt.subList(start, end)) + "\n";
    }

    /** Les blocs « - op: … » d'une section, chacun avec ses lignes de clés. */
    private static List<String> fragments(String section) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        for (String line : section.split("\n")) {
            if (line.trim().startsWith("- op:")) {
                if (current != null) {
                    out.add(current.toString());
                }
                current = new StringBuilder();
            }
            if (current != null && !line.isBlank()) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            out.add(current.toString());
        }
        return out;
    }

    /** Le bâtiment de référence des exemples minimaux (front NORTH, 9 × 9 × 7), sol et murs posés. */
    private static String planWith(String fragment) {
        String step = fragment.lines()
                .map(l -> "  " + l.stripLeading().replaceFirst("^(?!- )", "  "))
                .reduce("", (a, b) -> a + b + "\n");
        return """
                id: exemple_minimal
                name: "Exemple"
                kind: habitable
                front: NORTH
                size:
                  x: 9
                  y: 9
                  z: 7
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 6]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [8, 4, 6]
                    block: minecraft:stone_bricks
                """ + step;
    }

    /** Les clés que {@code BuildingPlanYaml} lit : {@code map.get("…")} et les paramètres entiers. */
    private static Set<String> parserKeys() throws IOException {
        String source = Files.readString(PARSER_SOURCE);
        Set<String> keys = new LinkedHashSet<>();
        Matcher get = Pattern.compile("map\\.get\\(\"([a-z_]+)\"\\)").matcher(source);
        while (get.find()) {
            keys.add(get.group(1));
        }
        Matcher numbers = Pattern.compile("new String\\[\\] \\{([^}]*)\\}").matcher(source);
        while (numbers.find()) {
            Matcher word = Pattern.compile("\"([a-z_]+)\"").matcher(numbers.group(1));
            while (word.find()) {
                keys.add(word.group(1));
            }
        }
        return keys;
    }

    /** Les clés entre guillemets français après « Clés : » dans la description de chaque instruction. */
    private static Set<String> documentedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        StringBuilder description = null;
        for (String line : prompt) {
            String t = line.trim();
            if (t.startsWith("- op:") && t.contains("→")) {
                collect(description, keys);
                description = new StringBuilder(t);
            } else if (description != null && !t.isEmpty() && !t.startsWith("- op:")) {
                description.append(' ').append(t);
            } else {
                collect(description, keys);
                description = null;
            }
        }
        collect(description, keys);
        return keys;
    }

    private static void collect(StringBuilder description, Set<String> keys) {
        if (description == null) {
            return;
        }
        String text = description.toString();
        int at = text.indexOf("Clés :");
        if (at < 0) {
            return;
        }
        Matcher m = Pattern.compile("« ([a-z_]+) »").matcher(text.substring(at));
        while (m.find()) {
            keys.add(m.group(1));
        }
    }

    private static List<String> dedent(String block) {
        List<String> lines = block.lines().toList();
        int indent = lines.stream().filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        return lines.stream().map(l -> l.length() >= indent ? l.substring(indent) : l.stripLeading()).toList();
    }
}
