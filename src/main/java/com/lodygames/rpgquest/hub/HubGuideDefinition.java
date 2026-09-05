package com.lodygames.rpgquest.hub;

import java.util.List;
import org.bukkit.NamespacedKey;

/**
 * Configuration du Guide (centre d'aide / d'orientation) d'un Hub — issue #11, partie A.
 *
 * <p>C'est la <strong>structure d'extensibilité multi-Hub</strong> : chaque Hub a son entrée
 * ({@code plugins/RPGQuest/hub-guides/&lt;hub&gt;.yml}), avec son texte d'accueil, sa spécialité
 * locale, ses orientations vers les PNJ propres au Hub et le dialogue qui porte concrètement le menu
 * d'aide ({@link #guideDialogueId()} au nœud {@link #helpNodeId()}). La V1 ne configure qu'un seul
 * Hub réel ({@code hub_depart}), mais rien n'est codé en dur : ajouter un Hub = ajouter un fichier.</p>
 *
 * @param hubId          identifiant du Hub (minuscules, chiffres, {@code _}, {@code -})
 * @param worlds         mondes servis par ce Guide (peut être vide : résolution alors uniquement par {@code hubId})
 * @param guideDialogueId dialogue RPGQuest portant le menu d'aide
 * @param helpNodeId     nœud du dialogue à ouvrir pour le menu d'aide (défaut {@code help_menu})
 * @param welcome        message d'accueil propre au Hub (peut être vide)
 * @param specialty      présentation locale « ici nous sommes spécialisés en … » (peut être vide)
 * @param referrals      orientations textuelles vers les PNJ du Hub
 */
public record HubGuideDefinition(
        String hubId,
        List<String> worlds,
        NamespacedKey guideDialogueId,
        String helpNodeId,
        String welcome,
        String specialty,
        List<HubGuideReferral> referrals) {

    public static final String DEFAULT_HELP_NODE = "help_menu";

    public HubGuideDefinition {
        if (hubId == null || hubId.isBlank()) {
            throw new IllegalArgumentException("hubId ne peut pas être vide.");
        }
        if (guideDialogueId == null) {
            throw new IllegalArgumentException("guideDialogueId ne peut pas être null.");
        }
        worlds = worlds == null ? List.of() : List.copyOf(worlds);
        referrals = referrals == null ? List.of() : List.copyOf(referrals);
        helpNodeId = (helpNodeId == null || helpNodeId.isBlank()) ? DEFAULT_HELP_NODE : helpNodeId;
        welcome = welcome == null ? "" : welcome;
        specialty = specialty == null ? "" : specialty;
    }
}
