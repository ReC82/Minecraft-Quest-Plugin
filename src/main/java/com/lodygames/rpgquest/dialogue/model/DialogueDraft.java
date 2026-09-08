package com.lodygames.rpgquest.dialogue.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Modèle logique <strong>éditable</strong> minimal d'un dialogue (base d'un futur éditeur) — issue
 * « V1 /dialogues ». Volontairement plus pauvre que {@link DialogueDefinition} : pas de conditions,
 * pas d'actions riches (seul {@code CLOSE} ou une redirection {@code next}), pas de
 * {@link org.bukkit.NamespacedKey} — uniquement des chaînes déjà validées. Le Control Panel
 * n'envoie <strong>jamais</strong> de YAML : il remplit ce modèle, et {@code DialogueDefinitionYaml}
 * produit le fichier.
 *
 * <p>Ce squelette suffit à créer un dialogue chargeable ({@code id} + {@code start} + un nœud avec
 * {@code speaker}/{@code text} + au moins un choix). L'ajout de nœuds, de choix conditionnels et
 * d'actions ({@code START_QUEST}…) est le périmètre du futur éditeur, pas de cette V1.</p>
 */
public record DialogueDraft(String id, String startNodeId, List<Node> nodes) {

    /** Fragment de clé RPGQuest — le fichier est {@code dialogues/<key>.yml}, jamais un chemin fourni. */
    public static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9._-]{1,64}");
    public static final String DEFAULT_NAMESPACE = "rpgquest";

    public DialogueDraft {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id de dialogue obligatoire.");
        }
        if (startNodeId == null || startNodeId.isBlank()) {
            throw new IllegalArgumentException("startNodeId obligatoire.");
        }
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("un dialogue doit avoir au moins un nœud.");
        }
        if (nodes.stream().noneMatch(n -> n.id().equals(startNodeId))) {
            throw new IllegalArgumentException("le nœud de départ « " + startNodeId + " » n'existe pas.");
        }
    }

    public record Node(String id, String speaker, String text, List<Choice> choices) {
        public Node {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id de nœud obligatoire.");
            }
            if (speaker == null || speaker.isBlank()) {
                throw new IllegalArgumentException("speaker obligatoire (nœud « " + id + " »).");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text obligatoire (nœud « " + id + " »).");
            }
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("le nœud « " + id + " » doit avoir au moins un choix.");
            }
        }
    }

    /** Un choix : {@code close = true} ferme le dialogue ; sinon {@code nextNodeId} redirige (les deux exclusifs). */
    public record Choice(String text, String nextNodeId, boolean close) {
        public Choice {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text de choix obligatoire.");
            }
        }
    }

    /** Squelette : {@code rpgquest:<key>}, un nœud {@code start} avec un unique choix « fermer ». */
    public static DialogueDraft skeleton(String key, String speaker, String text, String closeChoiceLabel) {
        String normalizedKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(normalizedKey).matches()) {
            throw new IllegalArgumentException("clé de dialogue invalide : « " + key + " ».");
        }
        return new DialogueDraft(DEFAULT_NAMESPACE + ":" + normalizedKey, "start",
                List.of(new Node("start", speaker, text,
                        List.of(new Choice(closeChoiceLabel == null || closeChoiceLabel.isBlank()
                                ? "Au revoir" : closeChoiceLabel, null, true)))));
    }

    /** Clé sans namespace (nom de fichier attendu {@code <key>.yml}). */
    public String key() {
        return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
    }
}
