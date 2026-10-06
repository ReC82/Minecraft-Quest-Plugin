package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.EffectivePermissions;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Règles métier des groupes PlugAdmin (issue #199). Le stockage est dans
 * {@link GroupRepository} ; <strong>toutes</strong> les garanties sont ici.
 *
 * <h2>Anti-élévation de privilège : une seule règle, générale</h2>
 *
 * <p><strong>On ne peut jamais accorder une permission que l'on ne détient pas soi-même.</strong>
 * Cette règle unique ferme toutes les portes d'un coup, au lieu d'énumérer des cas particuliers qui
 * se périment : un administrateur ne peut pas créer un groupe portant {@code PLAYER_OP_WRITE} pour
 * se l'attribuer, ni {@code ECONOMY_WRITE}, ni {@code USER_MANAGE}, ni une permission inventée
 * demain. Elle s'applique à la création d'un groupe, à la modification de ses permissions, et à
 * l'attribution d'un groupe à un utilisateur.</p>
 *
 * <p>Un second verrou existe indépendamment : {@link Permission#USER_MANAGE} n'est détenue que par
 * {@code OWNER} aujourd'hui, donc seul un propriétaire atteint ces écrans. Les deux verrous sont
 * volontairement redondants — le premier survit à une future décision d'accorder
 * {@code USER_MANAGE} à un autre rôle.</p>
 *
 * <h2>Combinaison : union, jamais de refus</h2>
 *
 * <p>Voir {@link EffectivePermissions} : les droits effectifs sont l'union du rôle et des groupes.
 * Aucun refus explicite n'existe dans ce modèle, donc aucune priorité à arbitrer.</p>
 *
 * <h2>Protection du propriétaire</h2>
 *
 * <p>Un groupe ne peut <strong>que</strong> ajouter des droits. Il est donc structurellement
 * impossible de verrouiller un propriétaire en jouant sur les groupes : ni en le retirant d'un
 * groupe, ni en supprimant un groupe, ni en vidant ses permissions. La protection du dernier
 * propriétaire (rôle non abaissable, compte non désactivable) reste celle de
 * {@link UserDirectory}, et reste nécessaire : c'est le <em>rôle</em> qui porte l'accès, pas un
 * groupe.</p>
 */
public final class GroupDirectory {

    /** Nom de groupe : lisible, sans surprise à l'affichage, et stable comme clé d'unicité. */
    public static final Pattern GROUP_NAME = Pattern.compile("[\\p{L}0-9][\\p{L}0-9 ._-]{1,47}");
    public static final int DESCRIPTION_MAX = 200;

    private final GroupRepository repo;

    public GroupDirectory(GroupRepository repo) {
        this.repo = repo;
    }

    public List<PanelGroup> list() {
        return repo.all();
    }

    public Optional<PanelGroup> byId(String id) {
        return repo.findById(id);
    }

    public List<PanelGroup> groupsOf(String userId) {
        return userId == null ? List.of() : repo.groupsOf(userId);
    }

    public List<String> membersOf(String groupId) {
        return repo.membersOf(groupId);
    }

    /** Droits effectifs d'un compte : son rôle, plus l'union de ses groupes. */
    public EffectivePermissions effectiveFor(PanelUser user) {
        if (user == null) {
            return EffectivePermissions.ofRoleOnly(null);
        }
        return new EffectivePermissions(user.role(), groupsOf(user.id()));
    }

    // ---- Mutations -----------------------------------------------------------------------------

    public Outcome create(EffectivePermissions actor, String name, String description,
                          Set<Permission> permissions) {
        String error = validateDetails(name, description);
        if (error != null) {
            return Outcome.failed(error);
        }
        if (repo.findByName(name).isPresent()) {
            return Outcome.failed("Un groupe porte déjà ce nom.");
        }
        List<Permission> refused = notHeldBy(actor, permissions);
        if (!refused.isEmpty()) {
            return Outcome.failed(escalationMessage(refused));
        }
        PanelGroup group = new PanelGroup(UUID.randomUUID().toString(), name.trim(),
                description == null ? "" : description.trim(), permissions, Instant.now());
        try {
            repo.insert(group);
        } catch (GroupRepository.DuplicateGroupNameException e) {
            return Outcome.failed("Un groupe porte déjà ce nom.");
        }
        return Outcome.ok(null, group);
    }

    public Outcome rename(EffectivePermissions actor, String groupId, String name, String description) {
        Optional<PanelGroup> found = repo.findById(groupId);
        if (found.isEmpty()) {
            return Outcome.failed("Groupe introuvable.");
        }
        String error = validateDetails(name, description);
        if (error != null) {
            return Outcome.failed(error);
        }
        Optional<PanelGroup> clash = repo.findByName(name);
        if (clash.isPresent() && !clash.get().id().equals(groupId)) {
            return Outcome.failed("Un autre groupe porte déjà ce nom.");
        }
        // Renommer ne redistribue aucun droit : aucun contrôle d'élévation nécessaire ici. Mais
        // modifier un groupe dont on ne détient pas toutes les permissions reviendrait à agir sur
        // des droits qu'on ne maîtrise pas — on le refuse donc aussi.
        List<Permission> refused = notHeldBy(actor, found.get().permissions());
        if (!refused.isEmpty()) {
            return Outcome.failed("Ce groupe accorde des droits que vous ne détenez pas ("
                    + names(refused) + ") : vous ne pouvez pas le modifier.");
        }
        try {
            repo.updateDetails(groupId, name.trim(), description == null ? "" : description.trim());
        } catch (GroupRepository.DuplicateGroupNameException e) {
            return Outcome.failed("Un autre groupe porte déjà ce nom.");
        }
        return Outcome.ok(found.get(), repo.findById(groupId).orElse(null));
    }

    public Outcome setPermissions(EffectivePermissions actor, String groupId, Set<Permission> permissions) {
        Optional<PanelGroup> found = repo.findById(groupId);
        if (found.isEmpty()) {
            return Outcome.failed("Groupe introuvable.");
        }
        PanelGroup before = found.get();
        // Contrôle dans les DEUX sens : on ne peut ni accorder un droit qu'on n'a pas, ni retirer
        // un droit qu'on n'a pas (retirer serait un moyen détourné de modifier un groupe réservé).
        Set<Permission> touched = EnumSet.noneOf(Permission.class);
        touched.addAll(symmetricDifference(before.permissions(), permissions));
        List<Permission> refused = notHeldBy(actor, touched);
        if (!refused.isEmpty()) {
            return Outcome.failed(escalationMessage(refused));
        }
        repo.replacePermissions(groupId, permissions);
        return Outcome.ok(before, repo.findById(groupId).orElse(null));
    }

    public Outcome delete(EffectivePermissions actor, String groupId) {
        Optional<PanelGroup> found = repo.findById(groupId);
        if (found.isEmpty()) {
            return Outcome.failed("Groupe introuvable.");
        }
        List<Permission> refused = notHeldBy(actor, found.get().permissions());
        if (!refused.isEmpty()) {
            return Outcome.failed("Ce groupe accorde des droits que vous ne détenez pas ("
                    + names(refused) + ") : vous ne pouvez pas le supprimer.");
        }
        repo.delete(groupId);
        return Outcome.ok(found.get(), null);
    }

    /**
     * Remplace les appartenances d'un utilisateur.
     *
     * <p>Contrôle d'élévation sur les groupes <strong>ajoutés</strong> comme sur ceux
     * <strong>retirés</strong> : ajouter donnerait un droit qu'on ne détient pas, retirer
     * permettrait de défaire une décision prise par un propriétaire.</p>
     */
    public MembershipOutcome setMemberships(EffectivePermissions actor, PanelUser target,
                                            Set<String> groupIds) {
        if (target == null) {
            return new MembershipOutcome(false, "Compte introuvable.", List.of(), List.of());
        }
        List<PanelGroup> before = repo.groupsOf(target.id());
        Set<String> beforeIds = new LinkedHashSet<>();
        before.forEach(group -> beforeIds.add(group.id()));

        Set<String> requested = new LinkedHashSet<>(groupIds);
        for (String id : requested) {
            if (repo.findById(id).isEmpty()) {
                return new MembershipOutcome(false, "Groupe introuvable : " + id, List.of(), List.of());
            }
        }

        Set<Permission> touched = EnumSet.noneOf(Permission.class);
        for (String id : symmetricDifferenceOfIds(beforeIds, requested)) {
            repo.findById(id).ifPresent(group -> touched.addAll(group.permissions()));
        }
        List<Permission> refused = notHeldBy(actor, touched);
        if (!refused.isEmpty()) {
            return new MembershipOutcome(false, escalationMessage(refused), List.of(), List.of());
        }

        repo.replaceMemberships(target.id(), requested);
        List<PanelGroup> after = repo.groupsOf(target.id());
        return new MembershipOutcome(true, null, before, after);
    }

    /** Appelé à la suppression d'un compte : une appartenance orpheline n'aurait aucun sens. */
    public void forgetUser(String userId) {
        repo.removeAllMemberships(userId);
    }

    // ---- interne -------------------------------------------------------------------------------

    /**
     * Permissions que {@code actor} ne détient pas. Un acteur {@code null} (montage dégradé) ne
     * détient rien : refuser est le choix sûr.
     */
    private static List<Permission> notHeldBy(EffectivePermissions actor, Set<Permission> wanted) {
        List<Permission> refused = new ArrayList<>();
        for (Permission permission : wanted) {
            if (actor == null || !actor.has(permission)) {
                refused.add(permission);
            }
        }
        return refused;
    }

    private static Set<Permission> symmetricDifference(Set<Permission> before, Set<Permission> after) {
        Set<Permission> diff = EnumSet.noneOf(Permission.class);
        for (Permission permission : before) {
            if (!after.contains(permission)) {
                diff.add(permission);
            }
        }
        for (Permission permission : after) {
            if (!before.contains(permission)) {
                diff.add(permission);
            }
        }
        return diff;
    }

    private static Set<String> symmetricDifferenceOfIds(Set<String> before, Set<String> after) {
        Set<String> diff = new LinkedHashSet<>();
        before.stream().filter(id -> !after.contains(id)).forEach(diff::add);
        after.stream().filter(id -> !before.contains(id)).forEach(diff::add);
        return diff;
    }

    private static String escalationMessage(List<Permission> refused) {
        return "Vous ne pouvez pas accorder ni retirer un droit que vous ne détenez pas vous-même : "
                + names(refused) + ".";
    }

    private static String names(List<Permission> permissions) {
        List<String> out = new ArrayList<>();
        permissions.forEach(permission -> out.add(permission.name()));
        return String.join(", ", out);
    }

    private static String validateDetails(String name, String description) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "Le nom du groupe est obligatoire.";
        }
        if (!GROUP_NAME.matcher(trimmed).matches()) {
            return "Nom de groupe invalide : 2 à 48 caractères, lettres, chiffres, espace, « . », "
                    + "« _ » ou « - », commençant par une lettre ou un chiffre.";
        }
        if (description != null && description.length() > DESCRIPTION_MAX) {
            return "Description trop longue (max " + DESCRIPTION_MAX + " caractères).";
        }
        return null;
    }

    /** Résultat d'une mutation de groupe, avec l'avant et l'après pour le journal d'audit. */
    public record Outcome(boolean ok, String error, PanelGroup before, PanelGroup after) {

        static Outcome ok(PanelGroup before, PanelGroup after) {
            return new Outcome(true, null, before, after);
        }

        static Outcome failed(String error) {
            return new Outcome(false, error, null, null);
        }
    }

    /** Résultat d'une mutation d'appartenances, avec l'avant et l'après pour l'audit. */
    public record MembershipOutcome(boolean ok, String error, List<PanelGroup> before, List<PanelGroup> after) {
    }

    /** Rôles proposés comme base, pour l'affichage des droits effectifs. */
    public static List<Role> roles() {
        return List.of(Role.values());
    }
}
