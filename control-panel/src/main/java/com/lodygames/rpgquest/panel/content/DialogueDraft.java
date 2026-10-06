package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Modèle éditable d'un dialogue pour le Control Panel (issue #145). Il porte l'identité du
 * dialogue, son nœud de départ, ses nœuds et leurs choix.
 *
 * <p>Depuis #145 le modèle est <strong>fidèle</strong> : les conditions et les actions d'un choix
 * sont conservées telles quelles (listes ordonnées de couples clé → valeur, {@code type} en tête),
 * même si l'éditeur du panel ne sait pas les <em>modifier</em>. L'édition d'un dialogue existant
 * repart donc toujours du fichier réel et ne peut plus le réduire à un squelette : les formulaires
 * du panel ne touchent qu'aux champs qu'ils affichent. L'édition fine des conditions et des actions
 * reste du ressort de l'éditeur guidé {@code /dialogues} (#82), qui opère via le moteur.</p>
 */
public final class DialogueDraft {

    /** Un choix d'un nœud. {@code close} = ce choix ferme le dialogue ({@code next} vide). */
    public static final class Choice {
        public String text = "";
        public String next = "";
        public boolean close = false;
        /** {@code false} dès qu'un choix porte une condition ou une action non {@code CLOSE}. */
        public boolean simple = true;
        /** Conditions du choix, dans l'ordre du fichier ({@code type} d'abord). Jamais interprétées ici. */
        public final List<java.util.Map<String, String>> conditions = new ArrayList<>();
        /** Actions du choix, dans l'ordre du fichier — y compris {@code CLOSE}. */
        public final List<java.util.Map<String, String>> actions = new ArrayList<>();

        public Choice() {
        }

        public Choice(String text, String next, boolean close) {
            this.text = text == null ? "" : text;
            this.next = next == null ? "" : next;
            this.close = close;
            if (close) {
                java.util.Map<String, String> closeAction = new java.util.LinkedHashMap<>();
                closeAction.put("type", "CLOSE");
                this.actions.add(closeAction);
            }
        }
    }

    /** Un nœud du dialogue. */
    public static final class Node {
        public String id = "";
        public String speaker = "";
        public String text = "";
        public final List<Choice> choices = new ArrayList<>();

        public Node() {
        }

        public Node(String id) {
            this.id = id == null ? "" : id;
        }
    }

    /** Id « nu » (sans préfixe {@code rpgquest:}). Le fichier s'appelle {@code <id>.yml}. */
    public String id = "";
    /** Id du nœud de départ. */
    public String start = "start";
    public final List<Node> nodes = new ArrayList<>();

    public static DialogueDraft blank() {
        DialogueDraft d = new DialogueDraft();
        Node start = new Node("start");
        d.nodes.add(start);
        return d;
    }

    /** Le nœud de départ, ou {@code null} si {@link #start} ne correspond à aucun nœud. */
    public Node startNode() {
        for (Node n : nodes) {
            if (n.id.equals(start)) {
                return n;
            }
        }
        return null;
    }

    public int choiceCount() {
        int c = 0;
        for (Node n : nodes) {
            c += n.choices.size();
        }
        return c;
    }
}
