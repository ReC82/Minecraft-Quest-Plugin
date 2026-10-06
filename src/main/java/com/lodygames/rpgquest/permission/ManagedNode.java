package com.lodygames.rpgquest.permission;

import java.util.Locale;

/**
 * Un droit Minecraft <strong>géré par le pont</strong> (issue #200) : un nœud de permission, et
 * éventuellement le monde auquel il s'applique.
 *
 * <p>Le monde est le <em>contexte</em> LuckPerms : {@code world=<nom>}. Sans monde ({@code null}),
 * le droit s'applique partout — ce qui n'est presque jamais ce qu'on veut pour un droit de
 * construction, et c'est pour cela que le panel exige un monde pour les nœuds
 * {@code rpgquest.build.*}.</p>
 *
 * @param node  nœud de permission, toujours en minuscules
 * @param world nom du monde, ou {@code null} pour « partout »
 */
public record ManagedNode(String node, String world) {

    public ManagedNode {
        node = node == null ? "" : node.trim().toLowerCase(Locale.ROOT);
        world = world == null || world.isBlank() ? null : world.trim();
    }

    public static ManagedNode global(String node) {
        return new ManagedNode(node, null);
    }

    public static ManagedNode inWorld(String node, String world) {
        return new ManagedNode(node, world);
    }

    public boolean isGlobal() {
        return world == null;
    }

    /** Forme lisible et stable, utilisée dans les comparaisons, les rapports et l'audit. */
    public String describe() {
        return isGlobal() ? node : node + " (monde " + world + ")";
    }
}
