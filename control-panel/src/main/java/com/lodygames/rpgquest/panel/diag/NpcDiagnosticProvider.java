package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.web.MiniText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Diagnostics PNJ (issue #38) : consomme <strong>directement</strong> les {@code warnings[]} déjà
 * calculés par le moteur ({@code npc.NpcCatalog}) et exposés par l'action agent {@code npc.list}.
 * Aucune logique de détection re-codée ici : seulement le mapping vers {@link DiagnosticEntry}.
 */
public final class NpcDiagnosticProvider implements DiagnosticProvider {

    @Override
    public Domain domain() {
        return Domain.PNJ;
    }

    @Override
    public List<DiagnosticEntry> collect(DiagnosticContext ctx) {
        Optional<Map<String, Object>> details = ctx.details("npc.list");
        if (details.isEmpty()) {
            return List.of();
        }
        Instant seen = ctx.freshnessOf("npc.list").orElse(null);
        List<DiagnosticEntry> out = new ArrayList<>();
        Map<String, Object> d = details.get();

        // Citizens indisponible sur la cible : les bindings ne peuvent pas être vérifiés.
        if (d.containsKey("citizensAvailable") && !DiagnosticContext.isTrue(d.get("citizensAvailable"))) {
            out.add(DiagnosticEntry.free("CITIZENS_UNAVAILABLE", Severity.INFO, Domain.PNJ, "Citizens",
                    "Citizens inactif sur le serveur cible",
                    "Le plugin Citizens n'est pas actif : les liaisons entre PNJ du jeu et fiches "
                            + "RPGQuest ne peuvent pas être vérifiées.",
                    "Les fiches RPGQuest restent gérables, mais leur présence en jeu n'est pas contrôlée.",
                    "Installer / activer Citizens sur le serveur cible si des PNJ doivent apparaître en jeu.",
                    "", "/docs/pnj-depannage#citizens-inactif-sur-le-serveur-cible",
                    "/npcs?agent=" + Http.esc(ctx.agentId()), "npc.list", seen));
        }

        for (Object o : DiagnosticContext.list(d.get("npcs"))) {
            Map<String, Object> n = DiagnosticContext.map(o);
            String id = DiagnosticContext.str(n.get("id"));
            String dn = DiagnosticContext.str(n.get("displayName"));
            String label = dn.isBlank() || "null".equals(dn) ? "" : MiniText.plain(dn);
            String href = ctx.hasAgent() ? "/npcs?agent=" + Http.esc(ctx.agentId()) + "&focus=" + Http.esc(id) : "";
            boolean canFix = ctx.hasAgent();

            for (Object w : DiagnosticContext.list(n.get("warnings"))) {
                Map<String, Object> wm = DiagnosticContext.map(w);
                String code = DiagnosticContext.str(wm.get("code"));
                String sev = DiagnosticContext.str(wm.get("severity"));
                String msg = DiagnosticContext.str(wm.get("message"));

                DiagnosticEntry.QuickAction quick = null;
                if (canFix) {
                    switch (code.toUpperCase(java.util.Locale.ROOT)) {
                        case "BINDING_NO_DEFINITION", "NO_DEFINITION" -> quick = new DiagnosticEntry.QuickAction(
                                "Créer la définition", href + "&fix=create");
                        case "DIALOGUE_MISSING", "DISABLED", "GIVER_NO_DIALOGUE" -> quick =
                                new DiagnosticEntry.QuickAction("Modifier la fiche", href + "&fix=edit");
                        case "NOT_LINKED" -> quick = new DiagnosticEntry.QuickAction(
                                "Lier un PNJ Citizens", href + "&fix=link");
                        default -> { /* pas de correction sûre en un clic */ }
                    }
                }
                out.add(DiagnosticEntry.of(code, Domain.PNJ, "npc", id, label, "", msg, sev,
                        "", href, quick, "npc.list", seen));
            }
        }
        return out;
    }
}
