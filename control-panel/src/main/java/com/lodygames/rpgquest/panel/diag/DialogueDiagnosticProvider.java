package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.web.MiniText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Diagnostics Dialogues (issue #38) : {@code warnings[]} par dialogue ({@code dialogue.DialogueCatalog}),
 * plus les {@code loadIssues[]} (fichiers rejetés) et {@code declaredButMissing[]} (fiche PNJ pointant
 * vers un dialogue absent), tels qu'exposés par l'action agent {@code dialogue.list}.
 */
public final class DialogueDiagnosticProvider implements DiagnosticProvider {

    @Override
    public Domain domain() {
        return Domain.DIALOGUES;
    }

    @Override
    public List<DiagnosticEntry> collect(DiagnosticContext ctx) {
        Optional<Map<String, Object>> details = ctx.details("dialogue.list");
        if (details.isEmpty()) {
            return List.of();
        }
        Instant seen = ctx.freshnessOf("dialogue.list").orElse(null);
        Map<String, Object> d = details.get();
        List<DiagnosticEntry> out = new ArrayList<>();
        String base = ctx.hasAgent() ? "/dialogues?agent=" + Http.esc(ctx.agentId()) : "/dialogues";

        for (Object o : DiagnosticContext.list(d.get("dialogues"))) {
            Map<String, Object> dg = DiagnosticContext.map(o);
            String id = DiagnosticContext.str(dg.get("id"));
            String key = DiagnosticContext.str(dg.get("key"));
            String label = MiniText.prettifyId(key.isBlank() ? id : key);
            String href = ctx.hasAgent() ? base + "&focus=" + Http.esc(id) : "";
            for (Object w : DiagnosticContext.list(dg.get("warnings"))) {
                Map<String, Object> wm = DiagnosticContext.map(w);
                out.add(DiagnosticEntry.of(DiagnosticContext.str(wm.get("code")), Domain.DIALOGUES, "dialogue",
                        id, label, "", DiagnosticContext.str(wm.get("message")),
                        DiagnosticContext.str(wm.get("severity")), "", href, null, "dialogue.list", seen));
            }
        }

        for (Object o : DiagnosticContext.list(d.get("loadIssues"))) {
            Map<String, Object> m = DiagnosticContext.map(o);
            String file = DiagnosticContext.str(m.get("file"));
            out.add(DiagnosticEntry.of("DIALOGUE_LOAD_ISSUE", Domain.DIALOGUES, "dialogue-file", file, file,
                    DiagnosticContext.str(m.get("message")), DiagnosticContext.str(m.get("message")), "error",
                    "", "", null, "dialogue.list", seen));
        }

        for (Object o : DiagnosticContext.list(d.get("declaredButMissing"))) {
            Map<String, Object> m = DiagnosticContext.map(o);
            String npcId = DiagnosticContext.str(m.get("npcId"));
            String dialogueId = DiagnosticContext.str(m.get("dialogueId"));
            String href = ctx.hasAgent() ? "/npcs?agent=" + Http.esc(ctx.agentId()) + "&focus=" + Http.esc(npcId) : "";
            out.add(DiagnosticEntry.of("DIALOGUE_DECLARED_MISSING", Domain.DIALOGUES, "npc", npcId,
                    MiniText.prettifyId(npcId), dialogueId, "", "error", "", href, null, "dialogue.list", seen));
        }
        return out;
    }
}
