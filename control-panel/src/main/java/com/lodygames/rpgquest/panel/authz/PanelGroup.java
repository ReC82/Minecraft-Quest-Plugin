package com.lodygames.rpgquest.panel.authz;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Un groupe PlugAdmin (issue #199) : un ensemble <strong>modifiable</strong> de {@link Permission},
 * attribuable à plusieurs utilisateurs, et auquel un utilisateur peut appartenir plusieurs fois
 * (plusieurs groupes).
 *
 * <p><strong>Différence avec {@link Role}, et pourquoi les deux coexistent.</strong> Un rôle est un
 * ensemble <em>figé dans le code</em> : il garantit qu'un compte existant ne change jamais de
 * droits parce qu'on a touché à la base. Un groupe est une donnée, éditable depuis le panel. Les
 * rôles restent donc la <strong>base</strong> de chaque compte — la migration de l'issue #199 ne les
 * convertit pas en groupes, précisément pour ne pas élargir ni réduire un droit existant — et les
 * groupes <strong>ajoutent</strong> par-dessus.</p>
 *
 * @param id          identifiant interne stable (UUID)
 * @param name        nom affiché, unique (comparaison insensible à la casse)
 * @param description à quoi sert ce groupe ; vide autorisé
 * @param permissions permissions accordées par ce groupe
 */
public record PanelGroup(String id, String name, String description,
                         Set<Permission> permissions, Instant createdAt) {

    public PanelGroup {
        permissions = permissions == null || permissions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }
}
