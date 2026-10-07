package com.lodygames.rpgquest.dialogue;

import org.bukkit.NamespacedKey;

/**
 * Résout le PNJ concerné par une action ou une condition de dialogue qui n'en nomme pas
 * explicitement un (issue #123).
 *
 * <p><strong>Convention du projet</strong> : la clé d'un dialogue est l'id du PNJ qui le porte
 * ({@code rpgquest:guard} ↔ PNJ {@code guard}) — voir « Convention id dialogue ↔ id PNJ » dans
 * {@code docs/RPGQUEST_BIBLE.md}. C'est ce qui permet d'écrire une branche de remise
 * <em>générique</em> : copiée dans le dialogue d'un autre PNJ, elle remet à cet autre PNJ sans
 * qu'aucune donnée ne nomme qui que ce soit.</p>
 *
 * <p>Un id explicite l'emporte toujours : il reste nécessaire pour un dialogue partagé, ou ouvert
 * par {@code OPEN_DIALOGUE} depuis un PNJ différent du destinataire.</p>
 */
public final class DialogueNpcResolution {

    private DialogueNpcResolution() {
    }

    /**
     * @return l'id explicite s'il est renseigné, sinon la clé du dialogue courant, sinon
     *         {@code null} — et {@code null} ne vise jamais aucun PNJ (aucune remise possible),
     *         plutôt qu'un destinataire deviné.
     */
    public static String resolve(String explicitNpcId, NamespacedKey dialogueId) {
        if (explicitNpcId != null && !explicitNpcId.isBlank()) {
            return explicitNpcId;
        }
        return dialogueId == null ? null : dialogueId.getKey();
    }
}
