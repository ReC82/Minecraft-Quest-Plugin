package be.lloyd.rpgquest.dialogue.render;

/** Un choix déjà filtré par ses conditions (visible pour ce joueur, à cet instant) et prêt à être affiché. */
public record VisibleChoice(int index, String label) {
}
