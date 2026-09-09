package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.http.Http;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Diagnostics Stories (issue #38) : {@code story.list} ne porte pas de code moteur. On vérifie côté
 * panel que chaque quête de la chaîne existe bien dans le catalogue de quêtes (mêmes règles que la
 * carte détaillée de {@code /stories}). Ignoré si {@code quest.list} n'a jamais été chargé (rien à
 * comparer).
 */
public final class StoryDiagnosticProvider implements DiagnosticProvider {

    @Override
    public Domain domain() {
        return Domain.STORIES;
    }

    @Override
    public List<DiagnosticEntry> collect(DiagnosticContext ctx) {
        Optional<Map<String, Object>> details = ctx.details("story.list");
        if (details.isEmpty()) {
            return List.of();
        }
        Optional<Map<String, Object>> questDet = ctx.details("quest.list");
        if (questDet.isEmpty()) {
            return List.of();
        }
        Instant seen = ctx.freshnessOf("story.list").orElse(null);
        List<String> questIds = DiagnosticContext.list(questDet.get().get("quests")).stream()
                .map(o -> DiagnosticContext.str(DiagnosticContext.map(o).get("id"))).toList();
        Set<String> known = RefKeys.idKeySet(questIds);

        List<DiagnosticEntry> out = new ArrayList<>();
        String base = ctx.hasAgent() ? "/stories?agent=" + Http.esc(ctx.agentId()) : "/stories";

        for (Object o : DiagnosticContext.list(details.get().get("stories"))) {
            Map<String, Object> s = DiagnosticContext.map(o);
            String id = DiagnosticContext.str(s.get("id"));
            String label = QuestDiagnosticProvider.plainOr(DiagnosticContext.str(s.get("title")), id);
            String href = ctx.hasAgent() ? base + "&focus=" + Http.esc(id) : "";
            for (Object qid : DiagnosticContext.list(s.get("stepQuestIds"))) {
                String sid = DiagnosticContext.str(qid);
                if (!RefKeys.known(known, sid)) {
                    out.add(DiagnosticEntry.of("STORY_QUEST_UNKNOWN", Domain.STORIES, "story", id, label,
                            sid, "", "warning", "", href, null, "story.list", seen));
                }
            }
        }
        return out;
    }
}
