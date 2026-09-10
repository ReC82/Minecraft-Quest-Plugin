package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation d'un {@link DialogueDraft} avant enregistrement dans la source (issue #145). Volontairement
 * limitée au périmètre du ticket : identité du dialogue, nœud de départ présent, locuteur / texte
 * renseignés, cibles {@code next} existantes. L'éditeur avancé (conditions, actions de quête,
 * graphe complet) est le périmètre de #82 et n'est pas validé ici — le moteur RPGQuest reste
 * l'autorité finale au chargement du serveur.
 */
public final class DialogueValidator {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
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
            }
        }
        return out;
    }
}
