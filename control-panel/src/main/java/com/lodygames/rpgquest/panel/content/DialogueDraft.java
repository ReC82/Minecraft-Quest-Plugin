package com.lodygames.rpgquest.panel.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Modèle éditable <strong>minimal</strong> d'un dialogue pour le Control Panel (issue #145). Il ne
 * couvre <em>que</em> ce que l'éditeur de création manipule : identité du dialogue, nœud de départ,
 * et — pour la relecture de fichiers existants — la liste des nœuds et de leurs choix simples
 * ({@code text} / {@code next} / fermeture).
 *
 * <p>Ce n'est <strong>pas</strong> le modèle complet du moteur de dialogues (conditions, actions de
 * quête typées, embranchements avancés) : #82 couvre l'éditeur avancé. Un fichier qui utilise ces
 * constructions se relit en « best-effort » (les choix avec conditions/actions sont conservés
 * structurellement mais marqués non simples) et n'est jamais réécrit par le Control Panel via ce
 * modèle — la création écrit un squelette neuf, la lecture ne sert qu'au catalogue fusionné.</p>
 */
public final class DialogueDraft {

    /** Un choix d'un nœud. {@code close} = ce choix ferme le dialogue ({@code next} vide). */
    public static final class Choice {
        public String text = "";
        public String next = "";
        public boolean close = false;
        /** {@code false} dès qu'un choix porte une condition ou une action non {@code CLOSE}. */
        public boolean simple = true;

        public Choice() {
        }

        public Choice(String text, String next, boolean close) {
            this.text = text == null ? "" : text;
            this.next = next == null ? "" : next;
            this.close = close;
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
