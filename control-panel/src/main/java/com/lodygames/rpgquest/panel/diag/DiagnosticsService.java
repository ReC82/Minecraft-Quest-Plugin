package com.lodygames.rpgquest.panel.diag;

import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Service central d'observabilité RPGQuest (issue #38). Agrège les {@link DiagnosticProvider}
 * enregistrés en <strong>un seul modèle</strong> ({@link DiagnosticEntry}) trié et dédoublonné. Ne
 * déclenche aucune requête : il lit le dernier snapshot connu de l'agent (catalogues + heartbeat).
 *
 * <p>Étendre = ajouter un {@code DiagnosticProvider} dans {@link #providers} (pas de {@code switch}
 * géant). Le wording humain reste dans {@code DiagnosticHelp}.</p>
 */
public final class DiagnosticsService {

    /** Types de relevés agent dont dépendent les diagnostics (pour le refresh coordonné). */
    public static final List<String> REFRESH_TYPES =
            List.of("npc.list", "dialogue.list", "quest.list", "story.list");

    private final AgentStore store;
    private final List<DiagnosticProvider> providers;

    public DiagnosticsService(AgentStore store, AgentLiveness.Thresholds thresholds) {
        this.store = store;
        this.providers = List.of(
                new NpcDiagnosticProvider(),
                new DialogueDiagnosticProvider(),
                new QuestDiagnosticProvider(),
                new StoryDiagnosticProvider(),
                new ServerDiagnosticProvider(thresholds));
    }

    /** Domaines qu'un producteur enregistré peut alimenter (filtres « réellement supportés », §5). */
    public Set<Domain> supportedDomains() {
        Set<Domain> d = EnumSet.noneOf(Domain.class);
        for (DiagnosticProvider p : providers) {
            d.add(p.domain());
        }
        // certains producteurs émettent aussi dans un domaine voisin
        d.add(Domain.AGENT);
        d.add(Domain.WORLDS);
        return d;
    }

    public DiagnosticsReport collect(String agentId) {
        DiagnosticContext ctx = new DiagnosticContext(store, agentId);

        List<DiagnosticEntry> raw = new ArrayList<>();
        for (DiagnosticProvider p : providers) {
            try {
                raw.addAll(p.collect(ctx));
            } catch (RuntimeException e) {
                // un producteur qui échoue ne doit pas casser toute la page
                raw.add(DiagnosticEntry.free("PROVIDER_ERROR", Severity.WARNING, Domain.OTHER,
                        p.label(), "Diagnostic « " + p.label() + " » indisponible",
                        "Le calcul des diagnostics « " + p.label() + " » a échoué : " + rootMessage(e),
                        "Cette catégorie de problèmes n'apparaît pas dans la liste ci-dessous.",
                        "Réessayer après un rafraîchissement ; si l'erreur persiste, la signaler.",
                        e.getClass().getSimpleName(), "", "", "diagnostics", null));
            }
        }

        // dédoublonnage (même code + même ressource)
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<DiagnosticEntry> entries = new ArrayList<>();
        for (DiagnosticEntry e : raw) {
            if (seen.add(e.dedupeKey())) {
                entries.add(e);
            }
        }

        entries.sort(Comparator
                .comparingInt((DiagnosticEntry e) -> e.severity().rank())
                .thenComparing(e -> e.domain().ordinal())
                .thenComparing(e -> e.resourceLabel().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(DiagnosticEntry::code));

        int errors = 0;
        int warnings = 0;
        int infos = 0;
        Set<Domain> present = EnumSet.noneOf(Domain.class);
        Instant oldest = null;
        for (DiagnosticEntry e : entries) {
            switch (e.severity()) {
                case ERROR -> errors++;
                case WARNING -> warnings++;
                case INFO -> infos++;
            }
            present.add(e.domain());
            if (e.observedAt() != null && (oldest == null || e.observedAt().isBefore(oldest))) {
                oldest = e.observedAt();
            }
        }

        boolean anyData = ctx.details("npc.list").isPresent() || ctx.details("dialogue.list").isPresent()
                || ctx.details("quest.list").isPresent() || ctx.details("story.list").isPresent()
                || ctx.heartbeat().isPresent();

        return new DiagnosticsReport(entries, errors, warnings, infos, present, anyData, oldest);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }
}
