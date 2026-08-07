package be.lloyd.rpgquest.dialogue.session;

import org.bukkit.NamespacedKey;

/**
 * Session en mémoire uniquement (pas persistée) : une déconnexion en cours
 * de dialogue met simplement fin à la session, comme la fermeture d'un
 * inventaire vanilla — voir docs/ARCHITECTURE.md.
 */
record DialogueSession(NamespacedKey dialogueId, String nodeId) {
}
