package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.web.ObjectiveText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #178 — objectif {@code DISCOVER_CITY} dans l'éditeur : écriture YAML relue par le plugin,
 * validation des deux formes et de la référence de ville, résumé sans identifiant technique.
 */
class DiscoverCityEditorTest {

    private static final RefData REF = RefData.empty()
            .withCities(Map.of("ville_port_lily", "Port-Lily", "ville_bourg_neuf", "Bourg-Neuf"), true);

    private static QuestDraft quest(String... kv) {
        QuestDraft d = new QuestDraft();
        d.id = "test_cities";
        d.title = "Voyageur";
        d.description = "Découvrir des villes.";
        d.category = "exploration";
        d.icon = "COMPASS";
        QuestDraft.Step step = new QuestDraft.Step("step1");
        Map<String, String> o = new LinkedHashMap<>();
        o.put("kind", "DISCOVER_CITY");
        for (int i = 0; i < kv.length; i += 2) {
            o.put(kv[i], kv[i + 1]);
        }
        step.objectives.add(o);
        d.steps.add(step);
        return d;
    }

    private static List<Diagnostic> errors(QuestDraft d, RefData ref) {
        return QuestValidator.validate(d, ref).stream().filter(x -> x.level() == Diagnostic.Level.ERROR).toList();
    }

    @Test
    void bothFormsRoundTripAsThePluginReadsThem() {
        String specific = QuestYaml.write(quest("city", "ville_port_lily", "count-mode", "INCLUDE_EXISTING"));
        assertEquals(List.of(), QuestYaml.roundTripProblems(specific), specific);
        assertTrue(specific.contains("city: ville_port_lily"), specific);
        assertTrue(specific.contains("count-mode: INCLUDE_EXISTING"), specific);
        assertFalse(specific.contains("amount:"), specific);

        String distinct = QuestYaml.write(quest("amount", "3", "count-mode", "NEW_ONLY"));
        assertEquals(List.of(), QuestYaml.roundTripProblems(distinct), distinct);
        assertTrue(distinct.contains("amount: 3"), distinct);
        Map<String, String> back = QuestYaml.read(distinct).draft().steps.get(0).objectives.get(0);
        assertEquals("3", back.get("amount"));
        assertEquals("NEW_ONLY", back.get("count-mode"));
    }

    @Test
    void validFormsHaveNoError() {
        assertEquals(List.of(), errors(quest("city", "ville_port_lily"), REF));
        assertEquals(List.of(), errors(quest("amount", "2", "count-mode", "INCLUDE_EXISTING"), REF));
    }

    @Test
    void neitherCityNorAmountIsAnError() {
        assertEquals(1, errors(quest("count-mode", "NEW_ONLY"), REF).size());
    }

    @Test
    void aCityWithAnAmountOtherThanOneIsAnError() {
        assertFalse(errors(quest("city", "ville_port_lily", "amount", "3"), REF).isEmpty());
    }

    @Test
    void anUnknownCityIsAnErrorOnceCitiesAreKnownAndOnlyInfoBefore() {
        List<Diagnostic> known = errors(quest("city", "ville_fantome"), REF);
        assertEquals(1, known.size());
        assertTrue(known.get(0).message().contains("Ville inconnue"));
        assertEquals(List.of(), errors(quest("city", "ville_fantome"), RefData.empty()),
                "sans relevé : on ne bloque pas, on informe");
    }

    @Test
    void anUnknownCountModeIsAnError() {
        assertFalse(errors(quest("amount", "2", "count-mode", "PARFOIS"), REF).isEmpty());
    }

    @Test
    void theSummaryNamesTheCityAndTheRule() {
        Map<String, Object> specific = Map.of("kind", "DISCOVER_CITY", "target", "ville_port_lily",
                "amount", 1, "countMode", "INCLUDE_EXISTING");
        String label = ObjectiveText.fromSummary(specific, REF::cityLabel).label();
        assertTrue(label.contains("« Port-Lily »"), label);
        assertFalse(label.contains("ville_port_lily"), "aucun identifiant technique imposé");
        assertTrue(label.contains("déjà acquises incluses"), label);

        Map<String, Object> distinct = Map.of("kind", "DISCOVER_CITY", "target", "", "amount", 3,
                "countMode", "NEW_ONLY");
        String distinctLabel = ObjectiveText.fromSummary(distinct, REF::cityLabel).label();
        assertTrue(distinctLabel.contains("3 ville(s) distincte(s)"), distinctLabel);
        assertTrue(distinctLabel.contains("nouvelles découvertes"), distinctLabel);
    }
}
