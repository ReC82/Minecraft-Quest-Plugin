package com.lodygames.rpgquest.panel.content;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Les dialogues que le serveur DEV a réellement chargés, d'après le dernier relevé
 * {@code dialogue.list} réussi, avec sa date (issue #248).
 *
 * <h2>Pourquoi ce relevé, et pourquoi sa fraîcheur compte</h2>
 *
 * <p>Avant #248, l'aperçu de suppression ne regardait que la source éditable. Le dialogue
 * {@code rpgquest:help}, chargé seulement côté serveur, démarrait {@code help_recolte} : supprimer
 * cette quête affichait « aucune référence ». Faux sentiment de sûreté. Le relevé du serveur dit ce
 * qui est <em>réellement</em> chargé — PNJ liés, quêtes démarrées, dialogues qui en ouvrent
 * d'autres — mais seulement à la date où il a été pris.</p>
 *
 * <p>D'où trois états, jamais confondus : <strong>absent</strong> (on ne sait rien),
 * <strong>périmé</strong> (on savait, il y a trop longtemps), <strong>exploitable</strong>. Seul le
 * troisième permet de conclure « aucune référence ». Le serveur revérifie de toute façon avant
 * d'exécuter ; cette classe sert à ne pas <em>annoncer</em> une sûreté qu'on n'a pas.</p>
 */
public final class RuntimeDialogueInventory {

    /**
     * Âge maximal d'un relevé pour conclure. Quinze minutes : assez pour préparer une suppression
     * sans re-rafraîchir à chaque clic, assez court pour qu'une liaison PNJ faite entre-temps depuis
     * un autre onglet ne passe pas inaperçue longtemps — et le serveur revérifie au moment d'agir.
     */
    public static final Duration MAX_AGE = Duration.ofMinutes(15);

    /** Ce que le relevé dit d'un dialogue. */
    public record Entry(String id, String key, List<String> linkedNpcIds, List<String> startsQuestIds,
                        List<String> referencedQuestIds, List<Opener> opens) {
    }

    /** Un choix qui ouvre un autre dialogue ({@code OPEN_DIALOGUE}). */
    public record Opener(String nodeId, String targetKey) {
    }

    private final Instant surveyedAt;
    private final Map<String, Entry> byKey;

    private RuntimeDialogueInventory(Instant surveyedAt, Map<String, Entry> byKey) {
        this.surveyedAt = surveyedAt;
        this.byKey = byKey;
    }

    public static RuntimeDialogueInventory unavailable() {
        return new RuntimeDialogueInventory(null, Map.of());
    }

    /**
     * @param details    le corps {@code details} d'un {@code dialogue.list} réussi
     * @param surveyedAt fin de cette action ; {@code null} ⇒ fraîcheur inconnue, donc inexploitable
     */
    public static RuntimeDialogueInventory from(Map<String, Object> details, Instant surveyedAt) {
        if (details == null || !(details.get("dialogues") instanceof List<?> rows)) {
            return unavailable();
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> row)) {
                continue;
            }
            String id = str(row.get("id"));
            String key = key(id.isEmpty() ? str(row.get("key")) : id);
            if (key.isEmpty()) {
                continue;
            }
            List<Opener> opens = new ArrayList<>();
            for (Object n : list(row.get("nodes"))) {
                if (!(n instanceof Map<?, ?> node)) {
                    continue;
                }
                for (Object c : list(node.get("choices"))) {
                    if (!(c instanceof Map<?, ?> choice)) {
                        continue;
                    }
                    for (Object a : list(choice.get("actions"))) {
                        if (a instanceof Map<?, ?> action && "OPEN_DIALOGUE".equals(str(action.get("kind")))) {
                            opens.add(new Opener(str(node.get("id")), key(str(action.get("target")))));
                        }
                    }
                }
            }
            byKey.put(key, new Entry(id, key, strings(row.get("linkedNpcIds")),
                    keys(row.get("startsQuestIds")), keys(row.get("referencedQuestIds")), List.copyOf(opens)));
        }
        return new RuntimeDialogueInventory(surveyedAt, byKey);
    }

    /** Un relevé existe-t-il ? */
    public boolean available() {
        return surveyedAt != null;
    }

    /** Peut-on s'appuyer dessus pour affirmer une absence de référence ? */
    public boolean usable(Instant now) {
        return available() && !surveyedAt.isBefore(now.minus(MAX_AGE));
    }

    public Optional<Instant> surveyedAt() {
        return Optional.ofNullable(surveyedAt);
    }

    public Optional<Entry> find(String rawId) {
        return Optional.ofNullable(byKey.get(key(rawId)));
    }

    /** Les dialogues (autres que lui) dont un choix ouvre celui-ci. */
    public List<String> openersOf(String rawId) {
        String wanted = key(rawId);
        List<String> out = new ArrayList<>();
        for (Entry e : byKey.values()) {
            if (e.key().equals(wanted)) {
                continue;
            }
            for (Opener o : e.opens()) {
                if (o.targetKey().equals(wanted)) {
                    out.add(e.id() + " (nœud « " + o.nodeId() + " »)");
                }
            }
        }
        return out;
    }

    /** Les clés des dialogues (autres que lui) qui ouvrent celui-ci. */
    public List<String> openerKeys(String rawId) {
        String wanted = key(rawId);
        return byKey.values().stream()
                .filter(e -> !e.key().equals(wanted))
                .filter(e -> e.opens().stream().anyMatch(o -> o.targetKey().equals(wanted)))
                .map(Entry::key)
                .toList();
    }

    /** Les dialogues qui démarrent ou testent cette quête. */
    public List<Entry> referencingQuest(String questId) {
        String wanted = key(questId);
        return byKey.values().stream()
                .filter(e -> e.startsQuestIds().contains(wanted) || e.referencedQuestIds().contains(wanted))
                .toList();
    }

    /** Identifiant comparable : minuscules, sans le namespace {@code rpgquest:} implicite. */
    public static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static List<?> list(Object raw) {
        return raw instanceof List<?> l ? l : List.of();
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        for (Object o : list(raw)) {
            if (o != null && !String.valueOf(o).isBlank()) {
                out.add(String.valueOf(o));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> keys(Object raw) {
        return strings(raw).stream().map(RuntimeDialogueInventory::key).toList();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
