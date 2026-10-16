package com.lodygames.rpgquest.mob.model;

/**
 * Issue #175 — mode de déclenchement d'un profil {@link MobCategory#BOSS}, distinct du choix
 * SPECIAL/BOSS lui-même.
 *
 * <ul>
 *   <li>{@code QUEST} (défaut, rétro-compatible) : n'apparaît que par spawn de test admin ou, plus
 *       tard, par une quête personnelle (#171). Jamais tiré par le scheduler communautaire.</li>
 *   <li>{@code COMMUNITY} : éligible au scheduler des boss communautaires du Wild
 *       ({@code mob.community.CommunityBossService}), avec son poids {@code community-weight}.</li>
 * </ul>
 *
 * <p>Sans effet sur un profil {@code SPECIAL} : le parseur refuse {@code COMMUNITY} sur un tel
 * profil plutôt que de l'ignorer en silence.</p>
 */
public enum BossMode {
    QUEST,
    COMMUNITY
}
