package com.lodygames.rpgquest.panel.ai;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Les exigences ACTUALISÉES d'un projet de l'Atelier : les consignes que l'administrateur a données
 * en demandant une amélioration, depuis la dernière modification de sa description (#747).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Le 23/10/2026, la « Cabane du Hub » était décrite comme « une seule pièce vide, sans mobilier »,
 * et sa conception v1 disait « aucun mobilier ». Puis l'administrateur a demandé, dans « Améliorer
 * avec l'IA », un lit rouge, un coffre, un établi, un four et des torches murales. La demande partait
 * bien — mais sous un prompt qui imposait de traduire FIDÈLEMENT la conception et disait que « le
 * brief reste la référence » : le besoin avait changé, sa priorité non. Et la reprise suivante
 * oubliait la consigne, qui n'était écrite nulle part ailleurs que dans le motif de cet appel.</p>
 *
 * <h2>Ce qu'elles deviennent</h2>
 *
 * <ul>
 *   <li>chaque consigne est rangée dans le journal du projet ({@link #KIND}) au moment de la demande ;</li>
 *   <li>toutes celles données depuis la dernière modification de la description partent avec CHAQUE
 *       appel suivant (plan, conception, réparation, critique), dans un bloc qui prime sur la
 *       description et sur la conception — la plus récente l'emporte en cas de contradiction ;</li>
 *   <li>modifier la description les clôt : l'administrateur y a consolidé son besoin.</li>
 * </ul>
 */
public final class RequirementUpdates {

    /** Le type d'événement de journal qui porte une consigne. */
    public static final String KIND = "REQUIREMENT_UPDATE";

    /** Consignes rendues au plus : les plus récentes. */
    static final int MAX_UPDATES = 6;

    /** Longueur totale rendue au plus (les plus récentes d'abord). */
    static final int MAX_CHARS = 3_000;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(ZoneId.of("Europe/Brussels"));

    /** Une consigne : quand, et son texte tel que saisi. */
    public record Update(String when, String text) {
    }

    private final List<Update> updates;

    private RequirementUpdates(List<Update> updates) {
        this.updates = List.copyOf(updates);
    }

    /** Les types d'événement à relire : les consignes, et les modifications de la fiche qui les closent. */
    public static final Set<String> JOURNAL_KINDS = Set.of(KIND, "PROJECT_CHANGED");

    public static RequirementUpdates none() {
        return new RequirementUpdates(List.of());
    }

    /** Relit les consignes d'un projet dans son journal, depuis la dernière modification de la description. */
    public static RequirementUpdates fromJournal(List<AiJournalStore.Event> events) {
        List<AiJournalStore.Event> sinceBrief = new ArrayList<>();
        for (AiJournalStore.Event e : events == null ? List.<AiJournalStore.Event>of() : events) {
            if ("PROJECT_CHANGED".equals(e.kind()) && briefChanged(e.body())) {
                sinceBrief.clear();
            } else if (KIND.equals(e.kind())) {
                sinceBrief.add(e);
            }
        }
        // Même texte saisi deux fois (réessai d'un appel échoué) : une seule consigne, à sa dernière date.
        Set<String> seen = new LinkedHashSet<>();
        List<Update> newestFirst = new ArrayList<>();
        int chars = 0;
        for (int i = sinceBrief.size() - 1; i >= 0 && newestFirst.size() < MAX_UPDATES; i--) {
            String text = String.valueOf(sinceBrief.get(i).body().getOrDefault("notes", "")).strip();
            if (text.isEmpty() || !seen.add(text)) {
                continue;
            }
            if (chars + text.length() > MAX_CHARS && !newestFirst.isEmpty()) {
                break;
            }
            chars += text.length();
            newestFirst.add(new Update(WHEN.format(sinceBrief.get(i).at()), text));
        }
        List<Update> ordered = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(ordered);
        return new RequirementUpdates(ordered);
    }

    private static boolean briefChanged(Map<String, Object> body) {
        // La fiche d'avant et celle d'après portent l'empreinte de leur description.
        Object before = body.get("before") instanceof Map<?, ?> b ? b.get("briefSha256") : null;
        Object after = body.get("after") instanceof Map<?, ?> a ? a.get("briefSha256") : null;
        return before == null || after == null || !before.equals(after);
    }

    public List<Update> updates() {
        return updates;
    }

    public boolean isEmpty() {
        return updates.isEmpty();
    }

    /** Le texte cumulé des consignes, pour la lecture des exigences (éléments demandés). */
    public String text() {
        return String.join("\n", updates.stream().map(Update::text).toList());
    }

    /**
     * Le bloc ajouté à la description envoyée à l'IA ; vide sans consigne. Il suit la description
     * initiale et dit explicitement sa priorité.
     */
    public String promptBlock() {
        if (updates.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nEXIGENCES ACTUALISÉES PAR L'ADMINISTRATEUR — données après la "
                + "description ci-dessus, de la plus ancienne à la plus récente. Elles PRIMENT sur la description "
                + "initiale ET sur la conception détaillée : là où elles les contredisent (par exemple « pièce vide » "
                + "puis « ajoute un lit »), c'est la consigne la plus récente qui s'applique. Ce qu'elles demandent "
                + "est une exigence à part entière : garde-le à chaque reprise.\n");
        int n = 1;
        for (Update u : updates) {
            sb.append(n++).append(". (").append(u.when()).append(") ").append(u.text().replace("\n", "\n   "))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Une ligne pour l'écran et le journal : combien de consignes partent, et la plus récente. */
    public String summary() {
        if (updates.isEmpty()) {
            return "Aucune exigence actualisée depuis la description.";
        }
        String last = updates.get(updates.size() - 1).text().replace('\n', ' ');
        return updates.size() + " exigence(s) actualisée(s) conservée(s), prioritaires sur la description et la "
                + "conception — la plus récente : « " + (last.length() > 120 ? last.substring(0, 120) + "…" : last)
                + " »";
    }
}
