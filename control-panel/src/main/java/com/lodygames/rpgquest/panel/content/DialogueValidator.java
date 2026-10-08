package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation d'un {@link DialogueDraft} avant enregistrement dans la source (issues #145 et #146).
 *
 * <p>Couvre l'identité du dialogue, le nœud de départ, le locuteur et le texte, les cibles
 * {@code next} — et, depuis #146 phase 2, le <strong>vocabulaire des actions et des conditions</strong>
 * de chaque choix, confronté à {@link Descriptors#DIALOGUE_ACTIONS} et
 * {@link Descriptors#DIALOGUE_CONDITIONS}.</p>
 *
 * <p>Cette dernière partie manquait, et c'était un vrai trou : un fichier citant une action
 * inexistante traversait l'import sans un mot et n'échouait qu'au chargement du serveur Minecraft,
 * là où personne ne regarde au moment de l'enregistrement. Elle devient indispensable avec
 * l'atelier IA, dont toute la promesse est que la proposition soit confrontée aux validateurs
 * réels <em>avant</em> qu'un administrateur confirme. Le moteur reste l'autorité finale ; ce qui
 * est vérifié ici, c'est ce que le panel est capable de savoir — et il sait désormais quels types
 * existent.</p>
 */
public final class DialogueValidator {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    /** Les six états de {@code QuestState}, seule liste que le moteur accepte pour QUEST_STATE. */
    private static final Set<String> QUEST_STATES = Set.of(
            "NOT_STARTED", "ACTIVE", "READY_TO_TURN_IN", "COMPLETED", "FAILED", "ABANDONED");
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    private static final int MAX_TEXT = 512;

    private DialogueValidator() {
    }

    public static List<Diagnostic> validate(DialogueDraft d) {
        List<Diagnostic> out = new ArrayList<>();

        String id = DialogueYaml.plainId(d.id);
        if (id.isBlank()) {
            out.add(Diagnostic.error("id", "L'identifiant du dialogue est obligatoire."));
        } else if (!ID.matcher(id).matches()) {
            out.add(Diagnostic.error("id", "Identifiant invalide « " + id + " » : minuscules, chiffres, "
                    + "« _ » et « - » (max 64)."));
        }

        if (d.nodes.isEmpty()) {
            out.add(Diagnostic.error("nodes", "Un dialogue doit avoir au moins un nœud."));
            return out;
        }

        Set<String> nodeIds = new LinkedHashSet<>();
        for (DialogueDraft.Node n : d.nodes) {
            String nid = n.id == null ? "" : n.id.trim();
            String ctx = "nodes[" + nid + "]";
            if (nid.isBlank()) {
                out.add(Diagnostic.error("nodes", "Un nœud sans identifiant."));
            } else if (!NODE_ID.matcher(nid).matches()) {
                out.add(Diagnostic.error(ctx, "Identifiant de nœud invalide « " + nid + " »."));
            } else if (!nodeIds.add(nid)) {
                out.add(Diagnostic.error(ctx, "Nœud « " + nid + " » défini plusieurs fois."));
            }
            if (n.speaker == null || n.speaker.isBlank()) {
                out.add(Diagnostic.error(ctx + ".speaker", "Le locuteur du nœud « " + nid + " » est obligatoire."));
            } else if (n.speaker.length() > 128) {
                out.add(Diagnostic.error(ctx + ".speaker", "Locuteur trop long (max 128)."));
            }
            if (n.text == null || n.text.isBlank()) {
                out.add(Diagnostic.error(ctx + ".text", "Le texte du nœud « " + nid + " » est obligatoire."));
            } else if (n.text.length() > MAX_TEXT) {
                out.add(Diagnostic.error(ctx + ".text", "Texte trop long (max " + MAX_TEXT + ")."));
            }
        }

        String start = d.start == null ? "" : d.start.trim();
        if (start.isBlank()) {
            out.add(Diagnostic.error("start", "Le nœud de départ est obligatoire."));
        } else if (!nodeIds.contains(start)) {
            out.add(Diagnostic.error("start", "Le nœud de départ « " + start + " » n'existe pas."));
        }

        for (DialogueDraft.Node n : d.nodes) {
            for (int i = 0; i < n.choices.size(); i++) {
                DialogueDraft.Choice c = n.choices.get(i);
                String ctx = "nodes[" + n.id + "].choices[" + i + "]";
                if (c.text == null || c.text.isBlank()) {
                    out.add(Diagnostic.error(ctx, "Un choix sans texte."));
                }
                if (!c.close && c.next != null && !c.next.isBlank() && !nodeIds.contains(c.next.trim())) {
                    out.add(Diagnostic.error(ctx, "Le choix pointe vers un nœud inexistant « " + c.next.trim() + " »."));
                }
                if (!c.close && (c.next == null || c.next.isBlank())) {
                    out.add(Diagnostic.warning(ctx, "Choix sans destination et sans fermeture : "
                            + "en jeu, il fermera la conversation."));
                }
                for (int j = 0; j < c.conditions.size(); j++) {
                    validateEntry(out, ctx + ".conditions[" + j + "]", c.conditions.get(j),
                            Descriptors.DIALOGUE_CONDITIONS, "condition", true);
                }
                for (int j = 0; j < c.actions.size(); j++) {
                    validateEntry(out, ctx + ".actions[" + j + "]", c.actions.get(j),
                            Descriptors.DIALOGUE_ACTIONS, "action", false);
                }
            }
        }
        return out;
    }

    /**
     * Une entrée {@code actions[]} ou {@code conditions[]} confrontée à son descripteur : type
     * connu, champs obligatoires présents, aucun champ étranger, entiers positifs.
     *
     * @param allowNegate les conditions acceptent toutes {@code negate}, les actions aucune
     */
    private static void validateEntry(List<Diagnostic> out, String ctx, Map<String, String> entry,
                                      List<Descriptors.Descriptor> catalog, String what,
                                      boolean allowNegate) {
        String type = entry.getOrDefault("type", "").trim();
        if (type.isEmpty()) {
            out.add(Diagnostic.error(ctx, "Cette " + what + " n'a pas de « type »."));
            return;
        }
        Descriptors.Descriptor d = catalog.stream()
                .filter(c -> c.kind().equalsIgnoreCase(type)).findFirst().orElse(null);
        if (d == null) {
            out.add(Diagnostic.error(ctx, "Type de " + what + " inconnu « " + type + " ». Types "
                    + "acceptés : " + catalog.stream().map(Descriptors.Descriptor::kind)
                            .collect(java.util.stream.Collectors.joining(", ")) + "."));
            return;
        }

        for (Descriptors.Field f : d.fields()) {
            String value = entry.getOrDefault(f.name(), "").trim();
            if (value.isEmpty()) {
                if (f.required()) {
                    out.add(Diagnostic.error(ctx, "« " + f.name() + " » est obligatoire pour la "
                            + what + " " + type + "."));
                }
                continue;
            }
            if (f.type() == Descriptors.FieldType.INT && !positiveInt(value)) {
                out.add(Diagnostic.error(ctx, "« " + f.name() + " » doit être un entier positif, "
                        + "reçu « " + value + " »."));
            }
            if ("state".equals(f.name()) && !QUEST_STATES.contains(value.toUpperCase(Locale.ROOT))) {
                out.add(Diagnostic.error(ctx, "État de quête inconnu « " + value + " ». États "
                        + "acceptés : " + String.join(", ", QUEST_STATES) + "."));
            }
        }

        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("type");
        d.fields().forEach(f -> allowed.add(f.name()));
        if (allowNegate) {
            allowed.add(Descriptors.NEGATE.name());
        }
        for (String key : entry.keySet()) {
            if (!allowed.contains(key)) {
                out.add(Diagnostic.error(ctx, "Champ « " + key + " » inattendu pour la " + what
                        + " " + type + " ; attendus : " + String.join(", ", allowed) + "."));
            }
        }
    }

    private static boolean positiveInt(String value) {
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
