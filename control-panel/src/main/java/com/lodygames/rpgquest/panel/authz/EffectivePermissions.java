package com.lodygames.rpgquest.panel.authz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Droits <strong>effectifs</strong> d'un compte et leur <strong>provenance</strong> (issue #199).
 *
 * <p><strong>Règle de combinaison, explicite et unique : l'UNION.</strong> Un compte dispose d'une
 * permission s'il la tient de son rôle <em>ou</em> d'au moins un de ses groupes. Ce choix n'est pas
 * un défaut de conception, c'est le seul qui soit cohérent avec le modèle existant :
 * {@link Role} est un {@link EnumSet} et {@code Role#has} est un simple {@code contains} — il
 * <strong>n'existe aucun refus explicite</strong> dans ce modèle, donc rien à arbitrer. En
 * introduire un rendrait « retirer un droit » ambigu (faut-il retirer de tous les groupes, ou
 * ajouter un refus qui écrase ?) et transformerait chaque page en question de priorité. Retirer un
 * droit se fait donc en le retirant du groupe, ou en retirant l'utilisateur du groupe — un geste,
 * un effet.</p>
 *
 * <p>La provenance est conservée parce qu'un « oui » sans explication est inexploitable : quand un
 * compte a un droit qu'on ne lui voulait pas, la question utile est <em>par où</em>.</p>
 */
public final class EffectivePermissions {

    /** D'où vient une permission : le rôle de base, ou un groupe nommé. */
    public record Source(Kind kind, String name) {

        public enum Kind { ROLE, GROUP }

        public static Source role(Role role) {
            return new Source(Kind.ROLE, role.name());
        }

        public static Source group(String groupName) {
            return new Source(Kind.GROUP, groupName);
        }

        public String label() {
            return kind == Kind.ROLE ? "rôle " + name : "groupe « " + name + " »";
        }
    }

    private final Role role;
    private final List<PanelGroup> groups;
    private final Set<Permission> granted;
    private final Map<Permission, List<Source>> sources;

    public EffectivePermissions(Role role, List<PanelGroup> groups) {
        this.role = role;
        this.groups = List.copyOf(groups);
        Set<Permission> union = EnumSet.noneOf(Permission.class);
        Map<Permission, List<Source>> bySource = new EnumMap<>(Permission.class);
        if (role != null) {
            for (Permission permission : role.permissions()) {
                union.add(permission);
                bySource.computeIfAbsent(permission, key -> new ArrayList<>()).add(Source.role(role));
            }
        }
        for (PanelGroup group : this.groups) {
            for (Permission permission : group.permissions()) {
                union.add(permission);
                bySource.computeIfAbsent(permission, key -> new ArrayList<>()).add(Source.group(group.name()));
            }
        }
        this.granted = Collections.unmodifiableSet(union);
        bySource.replaceAll((key, value) -> List.copyOf(value));
        this.sources = Collections.unmodifiableMap(bySource);
    }

    /** Compte sans aucun groupe : les droits effectifs sont exactement ceux du rôle. */
    public static EffectivePermissions ofRoleOnly(Role role) {
        return new EffectivePermissions(role, List.of());
    }

    public boolean has(Permission permission) {
        return permission != null && granted.contains(permission);
    }

    public Set<Permission> granted() {
        return granted;
    }

    public Role role() {
        return role;
    }

    public List<PanelGroup> groups() {
        return groups;
    }

    /** Toutes les origines d'une permission, dans l'ordre rôle puis groupes. Vide si non accordée. */
    public List<Source> sourcesOf(Permission permission) {
        return sources.getOrDefault(permission, List.of());
    }
}
