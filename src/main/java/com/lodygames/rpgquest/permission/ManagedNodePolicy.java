package com.lodygames.rpgquest.permission;

import java.util.List;
import java.util.Locale;

/**
 * Ce que le pont a le droit de toucher dans LuckPerms (issue #200) — et rien d'autre.
 *
 * <h2>Pourquoi une liste blanche de préfixes</h2>
 *
 * <p>La synchronisation doit « révoquer uniquement les droits gérés par ce pont et conserver les
 * droits externes indépendants ». Une synchronisation qui calculerait « l'état voulu » puis
 * effacerait tout le reste détruirait les permissions d'autres plugins (Citizens, Essentials, un
 * grade VIP…). La seule façon sûre est donc de borner le pont à des nœuds qu'il reconnaît
 * <strong>explicitement</strong> comme les siens.</p>
 *
 * <h2>Ce qui est volontairement EXCLU</h2>
 *
 * <p>{@code rpgquest.admin.world} — l'ombrelle historique — n'est <strong>pas</strong> gérable par
 * le pont. Elle donne tout : la laisser distribuable depuis une case à cocher de groupe reviendrait
 * à offrir une élévation de privilège en un clic, et contredirait la consigne « ne jamais
 * l'attribuer aux groupes builder ou éditeur PNJ ». Elle reste attribuable à la main par un
 * administrateur LuckPerms, en pleine conscience.</p>
 */
public final class ManagedNodePolicy {

    private ManagedNodePolicy() {
    }

    /**
     * Préfixes des nœuds que le pont peut écrire et retirer. Tout le reste est considéré comme
     * <strong>externe</strong> et n'est jamais modifié.
     */
    public static final List<String> MANAGED_PREFIXES = List.of(
            "rpgquest.build.",
            "rpgquest.bypass.",
            "rpgquest.admin.command",
            "rpgquest.admin.npc");

    /** Nœuds explicitement interdits au pont, même s'ils correspondent à un préfixe géré. */
    public static final List<String> NEVER_MANAGED = List.of(
            RpgPermissions.LEGACY_ADMIN_WORLD,
            // Écriture bas niveau : jamais distribuée par un groupe.
            "rpgquest.admin.debug");

    /** {@code true} si le pont peut écrire et retirer ce nœud. */
    public static boolean isManaged(String node) {
        if (node == null || node.isBlank()) {
            return false;
        }
        String candidate = node.trim().toLowerCase(Locale.ROOT);
        for (String forbidden : NEVER_MANAGED) {
            if (candidate.equals(forbidden)) {
                return false;
            }
        }
        for (String prefix : MANAGED_PREFIXES) {
            if (candidate.equals(prefix) || candidate.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Motif de refus lisible, ou {@code null} si le nœud est gérable. Un motif plutôt qu'un
     * booléen : l'administrateur doit savoir <em>pourquoi</em> le pont refuse.
     */
    public static String refusalReason(String node) {
        if (node == null || node.isBlank()) {
            return "nœud vide";
        }
        String candidate = node.trim().toLowerCase(Locale.ROOT);
        if (NEVER_MANAGED.contains(candidate)) {
            return "« " + candidate + " » donne des droits trop larges pour être distribué par un "
                    + "groupe : il reste à attribuer à la main dans LuckPerms, en pleine conscience.";
        }
        if (!isManaged(candidate)) {
            return "« " + candidate + " » n'appartient pas aux droits gérés par ce pont ("
                    + String.join(", ", MANAGED_PREFIXES) + ") : il est considéré comme externe et "
                    + "n'est jamais modifié.";
        }
        return null;
    }
}
