package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern GENERATED_STEP_ID = Pattern.compile("step_(\\d{1,6})");

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
    /**
     * Délai de ré-acceptation d'une quête répétable (issue #13) : vide = aucun, sinon secondes ou
     * nombre + unité {@code s/m/h/d} (« 20h »). Format du moteur, validé par {@link QuestValidator}.
     */
    public String cooldown = "";
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

    /**
     * Prochain identifiant d'étape généré (#143) : {@code step_<N>} avec N strictement supérieur à
     * tout {@code step_<n>} déjà présent dans la quête <em>ou</em> réservé. Un id d'étape n'est
     * unique que dans sa quête (le moteur le persiste avec l'id de quête), donc chaque quête a
     * son propre {@code step_1}. {@code reserved} = ids de la version enregistrée : un id d'étape
     * supprimée n'est jamais réattribué à une nouvelle étape, sa progression ne s'y raccroche pas.
     */
    public static String nextStepId(List<Step> steps, Collection<String> reserved) {
        Set<String> taken = new HashSet<>(reserved);
        for (Step s : steps) {
            taken.add(s.id == null ? "" : s.id.trim());
        }
        int max = 0;
        for (String id : taken) {
            Matcher m = GENERATED_STEP_ID.matcher(id);
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return "step_" + (max + 1);
    }

    /**
     * Donne un identifiant généré à chaque étape qui n'en a pas (#143). Les ids existants ne sont
     * <strong>jamais</strong> renumérotés : ils sont persistés dans la progression des joueurs,
     * déplacer ou supprimer une étape ne doit pas changer l'id des autres.
     */
    public void assignMissingStepIds(Collection<String> reserved) {
        for (Step s : steps) {
            if (s.id == null || s.id.isBlank()) {
                s.id = nextStepId(steps, reserved);
            }
        }
    }

    /** Ids d'étape (non vides), dans l'ordre. */
    public List<String> stepIds() {
        List<String> ids = new ArrayList<>();
        for (Step s : steps) {
            if (s.id != null && !s.id.isBlank()) {
                ids.add(s.id.trim());
            }
        }
        return ids;
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
