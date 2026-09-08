package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modèle <strong>éditable</strong> d'une quête pour l'éditeur guidé #46. Reprend fidèlement les
 * champs de {@code quest.model.QuestDefinition} du moteur RPGQuest (pas un second modèle : c'est
 * la même structure, exprimée en types simples pour le formulaire). La sérialisation
 * ({@link QuestYaml}) produit exactement le YAML attendu par {@code QuestDefinitionParser}.
 *
 * <p>Les objectifs et récompenses sont des {@code Map<champ, valeur>} portant une clé
 * {@code kind} — l'ensemble des champs valides pour un {@code kind} est donné par
 * {@link Descriptors}.</p>
 */
public final class QuestDraft {

    /** Étape : id + objectifs (chaque objectif = {@code {kind, …champs}}). */
    public static final class Step {
        public String id = "";
        public final List<Map<String, String>> objectives = new ArrayList<>();

        public Step() {
        }

        public Step(String id) {
            this.id = id;
        }
    }

    public String id = "";
    public String title = "";
    public String description = "";
    public String category = "";
    public String icon = "BOOK";
    public boolean repeatable = false;
    public boolean secret = false;
    public String giver = "";
    public final List<String> prerequisites = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();
    public final List<Map<String, String>> rewards = new ArrayList<>();
    public final Map<String, String> variables = new LinkedHashMap<>();

    /** Un brouillon neuf minimal : une étape avec un objectif KILL_ENTITY vierge. */
    public static QuestDraft blank() {
        QuestDraft d = new QuestDraft();
        d.category = "tutorial";
        Step s = new Step("step_1");
        Map<String, String> obj = new LinkedHashMap<>();
        obj.put("kind", "KILL_ENTITY");
        s.objectives.add(obj);
        d.steps.add(s);
        return d;
    }

    public QuestDraft copy() {
        QuestDraft d = new QuestDraft();
        d.id = id;
        d.title = title;
        d.description = description;
        d.category = category;
        d.icon = icon;
        d.repeatable = repeatable;
        d.secret = secret;
        d.giver = giver;
        d.prerequisites.addAll(prerequisites);
        for (Step s : steps) {
            Step c = new Step(s.id);
            for (Map<String, String> o : s.objectives) {
                c.objectives.add(new LinkedHashMap<>(o));
            }
            d.steps.add(c);
        }
        for (Map<String, String> r : rewards) {
            d.rewards.add(new LinkedHashMap<>(r));
        }
        d.variables.putAll(variables);
        return d;
    }
}
