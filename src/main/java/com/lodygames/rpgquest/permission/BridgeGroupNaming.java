package com.lodygames.rpgquest.permission;

import java.util.Locale;

/**
 * Nommage des groupes LuckPerms <strong>appartenant au pont</strong> (issue #200).
 *
 * <h2>Pourquoi des groupes dédiés plutôt que des nœuds posés sur l'utilisateur</h2>
 *
 * <p>Première version du pont : il écrivait les nœuds de permission directement sur l'utilisateur
 * et reconnaissait « les siens » par un <strong>préfixe</strong>
 * ({@code rpgquest.build.}, {@code rpgquest.bypass.}…). Ce critère est faux, et le défaut est
 * sérieux : un administrateur qui accorde <em>à la main</em>
 * {@code rpgquest.build.hub.world_hub} dans le contexte {@code world=world_hub} pose un nœud
 * <strong>identique</strong> à celui du pont. Une synchronisation ultérieure, ne voyant pas ce droit
 * dans l'état voulu, l'aurait <strong>supprimé</strong> — détruisant une décision externe sans
 * aucun signal.</p>
 *
 * <p>La provenance doit donc être <strong>structurelle</strong> et non devinée. Le pont :</p>
 * <ul>
 *   <li>place les droits gérés dans des <strong>groupes LuckPerms qui lui appartiennent</strong>,
 *       reconnaissables à leur préfixe de nom réservé ;</li>
 *   <li>ne manipule sur un utilisateur que des nœuds d'<strong>héritage</strong>
 *       ({@code group.<nom réservé>}) ;</li>
 *   <li>ne touche donc <strong>jamais</strong> un nœud de permission posé sur un utilisateur, qu'il
 *       porte le même nom et le même contexte ou non.</li>
 * </ul>
 *
 * <p>Conséquence directe : un droit externe survit à un retrait de groupe, à une dissociation et à
 * un redémarrage, parce qu'il n'est pas au même endroit — et pas parce qu'on a eu le réflexe de
 * l'épargner.</p>
 *
 * <h2>Nom stable, affichage lisible</h2>
 *
 * <p>Le nom technique dérive de l'<strong>identifiant</strong> du groupe PlugAdmin, pas de son
 * libellé : renommer un groupe dans le panel ne doit pas orpheliner un groupe LuckPerms ni faire
 * perdre ses droits à ses membres. Le libellé lisible est poussé séparément comme nom d'affichage.</p>
 */
public final class BridgeGroupNaming {

    private BridgeGroupNaming() {
    }

    /**
     * Préfixe réservé. Un groupe LuckPerms qui ne le porte pas n'appartient <strong>pas</strong> au
     * pont et n'est jamais modifié ni retiré, même s'il accorde exactement les mêmes droits.
     */
    public static final String PREFIX = "rpgq-";

    /** Nom technique du groupe LuckPerms correspondant à un groupe PlugAdmin. */
    public static String groupNameFor(String panelGroupId) {
        String normalized = panelGroupId == null ? "" : panelGroupId.replace("-", "").toLowerCase(Locale.ROOT);
        return PREFIX + normalized;
    }

    /** {@code true} si ce groupe LuckPerms appartient au pont. */
    public static boolean isBridgeGroup(String luckPermsGroupName) {
        return luckPermsGroupName != null
                && luckPermsGroupName.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }
}
