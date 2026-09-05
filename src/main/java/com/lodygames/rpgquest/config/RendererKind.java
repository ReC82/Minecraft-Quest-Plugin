package com.lodygames.rpgquest.config;

/**
 * Choix du renderer de dialogues. {@code PAPER_DIALOG} utilise l'API Dialog
 * native de Paper (vraie fenêtre avec boutons cliquables) — marquée
 * {@code @ApiStatus.Experimental} par Paper dans cette version (le contrat
 * de l'API pourrait changer dans une future version de Paper), mais réelle
 * et fonctionnelle contre {@code paper-api-1.21.11}, la version exacte
 * ciblée par ce projet : c'est désormais le choix par défaut
 * ({@code config.yml} → {@code dialogue.renderer: paper-dialog}), avec repli
 * automatique sur {@code CHAT} en cas d'échec à l'exécution (voir
 * {@code dialogue.render.FallbackDialogueRenderer}). {@code CHAT} (texte
 * cliquable dans le chat) reste disponible sans aucune API instable —
 * choisissez-le explicitement si vous voulez éviter toute dépendance à une
 * API expérimentale. Voir docs/ARCHITECTURE.md et
 * docs/NPC_DIALOGUES_QUESTS_GUIDE.md.
 */
public enum RendererKind {
    CHAT,
    PAPER_DIALOG
}
