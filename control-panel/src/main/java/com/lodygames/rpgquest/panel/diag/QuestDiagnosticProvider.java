package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.web.MiniText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Diagnostics Quêtes (issue #38). {@code quest.list} ne porte pas de code moteur : les
 * <strong>vérifications de référence</strong> (prérequis inconnu, donneur sans fiche) sont calculées
 * côté panel, avec la même normalisation d'identifiants ({@link RefKeys}) et le même wording
 * ({@code DiagnosticHelp}) que les cartes détaillées de {@code /quests} (issue #38, §16).
 */
public final class QuestDiagnosticProvider implements DiagnosticProvider {

    @Override
    public Domain domain() {
        return Domain.QUESTS;
    }

    @Override
    public List<DiagnosticEntry> collect(DiagnosticContext ctx) {
        Optional<Map<String, Object>> details = ctx.details("quest.list");
        if (details.isEmpty()) {
            return List.of();
        }
        Instant seen = ctx.freshnessOf("quest.list").orElse(null);
        List<Object> quests = DiagnosticContext.list(details.get().get("quests"));

        List<String> questIds = quests.stream().map(o -> DiagnosticContext.str(DiagnosticContext.map(o).get("id"))).toList();
        Set<String> knownQuestKeys = RefKeys.idKeySet(questIds);
        Set<String> knownNpcKeys = npcKeys(ctx); // null si npc.list jamais chargé

        List<DiagnosticEntry> out = new ArrayList<>();
        String base = ctx.hasAgent() ? "/quests?agent=" + Http.esc(ctx.agentId()) : "/quests";

        for (Object o : quests) {
            Map<String, Object> q = DiagnosticContext.map(o);
            String id = DiagnosticContext.str(q.get("id"));
            String label = plainOr(DiagnosticContext.str(q.get("title")), id);
            String href = ctx.hasAgent() ? base + "&focus=" + Http.esc(id) : "";

            for (Object p : DiagnosticContext.list(q.get("prerequisites"))) {
                String pid = DiagnosticContext.str(p);
                if (!RefKeys.known(knownQuestKeys, pid)) {
                    out.add(DiagnosticEntry.of("QUEST_PREREQ_UNKNOWN", Domain.QUESTS, "quest", id, label,
                            pid, "", "warning", "", href, null, "quest.list", seen));
                }
            }
            String giverId = DiagnosticContext.str(q.get("giverId"));
            if (!giverId.isBlank() && !"null".equals(giverId) && knownNpcKeys != null
                    && !RefKeys.known(knownNpcKeys, giverId)) {
                String npcHref = ctx.hasAgent() ? "/npcs?agent=" + Http.esc(ctx.agentId()) + "&focus=" + Http.esc(giverId) : "";
                out.add(DiagnosticEntry.of("QUEST_GIVER_UNKNOWN", Domain.QUESTS, "quest", id, label,
                        giverId, "", "warning", "", href,
                        npcHref.isBlank() ? null : new DiagnosticEntry.QuickAction("Ouvrir le PNJ donneur", npcHref),
                        "quest.list", seen));
            }
        }
        return out;
    }

    /** Ids de PNJ connus (définitions + ids canoniques). {@code null} si {@code npc.list} jamais chargé. */
    static Set<String> npcKeys(DiagnosticContext ctx) {
        Optional<Map<String, Object>> npc = ctx.details("npc.list");
        if (npc.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (Object o : DiagnosticContext.list(npc.get().get("definedIds"))) {
            ids.add(DiagnosticContext.str(o));
        }
        for (Object o : DiagnosticContext.list(npc.get().get("canonicalIds"))) {
            ids.add(DiagnosticContext.str(o));
        }
        return RefKeys.idKeySet(ids);
    }

    static String plainOr(String miniMessage, String fallbackId) {
        String p = MiniText.plain(miniMessage);
        return p.isBlank() || "null".equals(p) ? MiniText.prettifyId(fallbackId) : p;
    }
}
