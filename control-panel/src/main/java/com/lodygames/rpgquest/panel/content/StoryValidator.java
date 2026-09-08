package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation d'un {@link StoryDraft} avant enregistrement (#46, B8). Reproduit les contrôles de
 * {@code story.StoryDefinitionParser} / {@code StoryDefinition} (id au format strict, nom
 * obligatoire, au moins une quête) et ajoute la cohérence de référence via {@link RefData}
 * (quête inexistante, doublon incohérent).
 */
public final class StoryValidator {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern QUEST_KEY = Pattern.compile("[a-z0-9._/-]+");

    private StoryValidator() {
    }

    public static List<Diagnostic> validate(StoryDraft s, RefData ref) {
        List<Diagnostic> out = new ArrayList<>();
        RefData refData = ref == null ? RefData.empty() : ref;

        String id = StoryYaml.plainId(s.id);
        if (id.isBlank()) {
            out.add(Diagnostic.error("id", "L'identifiant de la story est obligatoire."));
        } else if (!ID.matcher(id).matches()) {
            out.add(Diagnostic.error("id", "Identifiant invalide « " + id + " » : minuscules, chiffres, « _ » et « - » uniquement."));
        }
        if (s.name == null || s.name.isBlank()) {
            out.add(Diagnostic.error("name", "Le nom affiché est obligatoire."));
        }

        List<String> quests = new ArrayList<>();
        for (String q : s.questIds) {
            if (q != null && !q.isBlank()) {
                quests.add(QuestYaml.plainId(q));
            }
        }
        if (quests.isEmpty()) {
            out.add(Diagnostic.error("quests", "Une story doit contenir au moins une quête."));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < quests.size(); i++) {
            String q = quests.get(i);
            String ctx = "quests[" + i + "]";
            if (!QUEST_KEY.matcher(q).matches()) {
                out.add(Diagnostic.error(ctx, "Identifiant de quête invalide « " + q + " »."));
                continue;
            }
            if (!seen.add(q)) {
                out.add(Diagnostic.warning(ctx, "Quête « " + q + " » présente plusieurs fois dans la chaîne."));
            }
            if (refData.questsKnown() && !refData.isQuestKnown(q)) {
                out.add(Diagnostic.warning(ctx, "Quête inconnue « " + q + " » (absente du dernier relevé « quest.list »)."));
            } else if (!refData.questsKnown()) {
                out.add(Diagnostic.info(ctx, "Impossible de vérifier la quête « " + q + " » : aucun relevé « quest.list »."));
            }
        }
        return out;
    }
}
