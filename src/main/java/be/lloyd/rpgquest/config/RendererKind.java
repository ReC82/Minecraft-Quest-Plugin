package be.lloyd.rpgquest.config;

/**
 * Choix du renderer de dialogues. {@code PAPER_DIALOG} utilise l'API Dialog
 * native de Paper, marquée {@code @ApiStatus.Experimental} dans cette
 * version — {@code CHAT} (texte cliquable, compatible avec tout client) est
 * le choix par défaut le plus sûr. Voir docs/ARCHITECTURE.md.
 */
public enum RendererKind {
    CHAT,
    PAPER_DIALOG
}
