package com.lodygames.rpgquest.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.slf4j.Logger;

/**
 * Pont vers LuckPerms (issue #200) : porte les droits Minecraft <strong>gérés</strong> d'un joueur
 * dans des <strong>groupes LuckPerms dédiés</strong>, avec leur contexte de monde.
 *
 * <h2>Utilisable sans LuckPerms</h2>
 *
 * <p>L'API est en {@code compileOnly} et l'accès passe par {@link LuckPermsProvider}, qui lève si
 * le plugin n'est pas là. Toute absence — plugin non installé, version incompatible, classe
 * manquante — est rattrapée et transformée en <strong>état réel</strong> : le pont se déclare
 * indisponible <em>avec son motif</em>. Il ne renvoie jamais un faux succès, parce qu'un faux
 * succès ferait croire à un administrateur qu'un builder a ses droits alors qu'il ne les a pas.</p>
 *
 * <h2>Provenance structurelle : pourquoi des groupes dédiés</h2>
 *
 * <p>Voir {@link BridgeGroupNaming} pour le raisonnement complet. En deux phrases : reconnaître
 * « ses » droits à un <em>préfixe de nœud</em> serait faux, parce qu'un administrateur peut accorder
 * à la main un nœud <strong>identique</strong>, même contexte de monde compris — et une
 * synchronisation l'aurait alors supprimé. Le pont ne manipule donc, sur un utilisateur, que des
 * nœuds d'<strong>héritage</strong> {@code group.rpgq-…}. Un droit externe posé directement sur
 * l'utilisateur n'est jamais au même endroit, donc jamais touché : il survit à un retrait de
 * groupe, à une dissociation et à un redémarrage <em>par construction</em>.</p>
 *
 * <h2>Synchronisation idempotente</h2>
 *
 * <p>{@link #syncUserGroups} et {@link #syncGroupDefinition} comparent l'état voulu à l'état réel
 * et n'écrivent que la différence. Rejouées, elles ne changent rien et le disent.</p>
 */
public final class LuckPermsBridge {

    /** Pourquoi le pont ne peut pas agir, ou {@code null} s'il peut. */
    public record Availability(boolean available, String reason) {

        public static Availability ok() {
            return new Availability(true, null);
        }

        public static Availability unavailable(String reason) {
            return new Availability(false, reason);
        }
    }

    /**
     * Résultat d'une synchronisation, tel qu'il s'est réellement passé.
     *
     * @param added     éléments ajoutés par cet appel
     * @param removed   éléments retirés par cet appel
     * @param unchanged éléments déjà conformes — c'est la preuve d'idempotence
     * @param preserved éléments <strong>externes</strong> rencontrés et volontairement laissés
     */
    public record SyncResult(boolean ok, String message, List<String> added, List<String> removed,
                             List<String> unchanged, List<String> preserved) {

        public static SyncResult failed(String message) {
            return new SyncResult(false, message, List.of(), List.of(), List.of(), List.of());
        }
    }

    private final Logger logger;

    public LuckPermsBridge(Logger logger) {
        this.logger = logger;
    }

    /** État réel du pont : disponible, ou indisponible avec son motif. */
    public Availability availability() {
        try {
            LuckPermsProvider.get();
            return Availability.ok();
        } catch (IllegalStateException e) {
            return Availability.unavailable("LuckPerms n'est pas installé ou pas encore chargé sur ce serveur.");
        } catch (LinkageError e) {
            // NoClassDefFoundError en est une sous-classe : un seul catch suffit et compile.
            return Availability.unavailable("API LuckPerms absente ou incompatible : " + e.getClass().getSimpleName());
        }
    }

    // ---- Définition d'un groupe du pont --------------------------------------------------------

    /**
     * Fait correspondre les droits du groupe LuckPerms {@code rpgq-<panelGroupId>} à {@code wanted}.
     * Crée le groupe s'il n'existe pas, et pousse {@code displayName} comme nom d'affichage pour
     * qu'un administrateur reconnaisse l'origine dans LuckPerms.
     *
     * <p>À l'intérieur d'un groupe du pont, seuls les nœuds correspondant à
     * {@link ManagedNodePolicy} sont ajoutés ou retirés : si un administrateur y a ajouté autre
     * chose à la main, cela reste en place et est signalé comme préservé.</p>
     */
    public CompletableFuture<SyncResult> syncGroupDefinition(String panelGroupId, String displayName,
                                                             Set<ManagedNode> wanted) {
        Availability availability = availability();
        if (!availability.available()) {
            return CompletableFuture.completedFuture(SyncResult.failed(availability.reason()));
        }
        List<String> refused = new ArrayList<>();
        Set<ManagedNode> desired = new LinkedHashSet<>();
        for (ManagedNode node : wanted) {
            String reason = ManagedNodePolicy.refusalReason(node.node());
            if (reason == null) {
                desired.add(node);
            } else {
                refused.add(reason);
            }
        }
        if (!refused.isEmpty()) {
            return CompletableFuture.completedFuture(SyncResult.failed(
                    "Droits refusés par la politique du pont : " + String.join(" ", refused)));
        }

        String groupName = BridgeGroupNaming.groupNameFor(panelGroupId);
        // Contrôle AVANT l'appel : LuckPerms lève IllegalArgumentException sur un nom trop long, et
        // la classe d'exception nue ne dit rien d'utile à l'administrateur qui lit le panel.
        String refusal = BridgeGroupNaming.refusalReason(groupName);
        if (refusal != null) {
            logger.error("Nom de groupe LuckPerms refusé pour {} : {}", panelGroupId, refusal);
            return CompletableFuture.completedFuture(SyncResult.failed(
                    "Nom de groupe refusé : " + refusal + ". Aucun droit modifié."));
        }
        try {
            LuckPerms api = LuckPermsProvider.get();
            return api.getGroupManager().createAndLoadGroup(groupName)
                    .thenApply(group -> applyGroupNodes(api, group, displayName, desired));
        } catch (RuntimeException | LinkageError e) {
            logger.error("Écriture du groupe LuckPerms {} impossible", groupName, e);
            return CompletableFuture.completedFuture(SyncResult.failed(
                    "Écriture impossible : " + describe(e) + ". Aucun droit modifié."));
        }
    }

    private SyncResult applyGroupNodes(LuckPerms api, Group group, String displayName,
                                        Set<ManagedNode> desired) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> preserved = new ArrayList<>();

        Set<ManagedNode> current = new LinkedHashSet<>();
        List<Node> managed = new ArrayList<>();
        for (Node node : group.getNodes()) {
            if (!(node instanceof PermissionNode permission) || !node.getValue()) {
                continue;
            }
            if (!ManagedNodePolicy.isManaged(permission.getPermission())) {
                preserved.add(permission.getPermission());
                continue;
            }
            current.add(new ManagedNode(permission.getPermission(), worldOf(node)));
            managed.add(node);
        }

        for (ManagedNode wanted : desired) {
            if (current.contains(wanted)) {
                unchanged.add(wanted.describe());
                continue;
            }
            group.data().add(toPermissionNode(wanted));
            added.add(wanted.describe());
        }
        for (Node node : managed) {
            ManagedNode held = new ManagedNode(((PermissionNode) node).getPermission(), worldOf(node));
            if (!desired.contains(held)) {
                group.data().remove(node);
                removed.add(held.describe());
            }
        }
        if (displayName != null && !displayName.isBlank()) {
            group.data().clear(node -> node instanceof net.luckperms.api.node.types.DisplayNameNode);
            group.data().add(net.luckperms.api.node.types.DisplayNameNode.builder(displayName).build());
        }
        api.getGroupManager().saveGroup(group).join();

        String message = added.isEmpty() && removed.isEmpty()
                ? "Groupe déjà conforme : aucun droit modifié."
                : added.size() + " droit(s) ajouté(s), " + removed.size() + " retiré(s).";
        return new SyncResult(true, message, List.copyOf(added), List.copyOf(removed),
                List.copyOf(unchanged), List.copyOf(preserved));
    }

    /** Retire un groupe du pont (suppression d'un groupe PlugAdmin). Sans effet s'il n'existe pas. */
    public CompletableFuture<SyncResult> deleteGroup(String panelGroupId) {
        Availability availability = availability();
        if (!availability.available()) {
            return CompletableFuture.completedFuture(SyncResult.failed(availability.reason()));
        }
        String groupName = BridgeGroupNaming.groupNameFor(panelGroupId);
        try {
            LuckPerms api = LuckPermsProvider.get();
            Group existing = api.getGroupManager().getGroup(groupName);
            if (existing == null) {
                return CompletableFuture.completedFuture(new SyncResult(true,
                        "Aucun groupe LuckPerms à retirer.", List.of(), List.of(), List.of(), List.of()));
            }
            return api.getGroupManager().deleteGroup(existing).thenApply(ignored -> new SyncResult(true,
                    "Groupe LuckPerms « " + groupName + " » supprimé : ses membres perdent exactement "
                            + "les droits qu'il portait, et rien d'autre.",
                    List.of(), List.of(groupName), List.of(), List.of()));
        } catch (RuntimeException | LinkageError e) {
            logger.error("Suppression du groupe LuckPerms {} impossible", groupName, e);
            return CompletableFuture.completedFuture(SyncResult.failed(
                    "Suppression impossible : " + describe(e)));
        }
    }

    // ---- Appartenances d'un joueur -------------------------------------------------------------

    /**
     * Fait correspondre les groupes <strong>du pont</strong> de ce joueur à {@code wantedPanelGroupIds}.
     *
     * <p>Ne touche <strong>que</strong> des nœuds d'héritage {@code group.rpgq-…}. Tout le reste —
     * nœuds de permission posés directement sur l'utilisateur, appartenance à un groupe LuckPerms
     * externe — est laissé intact et signalé comme préservé. C'est ce qui fait qu'un droit externe
     * de même nom et même contexte survit à un retrait de groupe ou à une dissociation.</p>
     */
    public CompletableFuture<SyncResult> syncUserGroups(UUID playerId, Set<String> wantedPanelGroupIds) {
        Availability availability = availability();
        if (!availability.available()) {
            return CompletableFuture.completedFuture(SyncResult.failed(availability.reason()));
        }
        Set<String> wantedGroups = new LinkedHashSet<>();
        for (String panelGroupId : wantedPanelGroupIds) {
            wantedGroups.add(BridgeGroupNaming.groupNameFor(panelGroupId));
        }
        try {
            LuckPerms api = LuckPermsProvider.get();
            return api.getUserManager().loadUser(playerId)
                    .thenApply(user -> applyUserGroups(api, user, wantedGroups));
        } catch (RuntimeException | LinkageError e) {
            logger.error("Synchronisation LuckPerms impossible pour {}", playerId, e);
            return CompletableFuture.completedFuture(SyncResult.failed(
                    "Synchronisation impossible : " + describe(e) + ". Aucun droit n'a été modifié."));
        }
    }

    private SyncResult applyUserGroups(LuckPerms api, User user, Set<String> wantedGroups) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> preserved = new ArrayList<>();

        Set<String> currentBridgeGroups = new LinkedHashSet<>();
        List<Node> bridgeInheritance = new ArrayList<>();
        for (Node node : user.getNodes()) {
            if (node instanceof InheritanceNode inheritance) {
                if (BridgeGroupNaming.isBridgeGroup(inheritance.getGroupName())) {
                    currentBridgeGroups.add(inheritance.getGroupName().toLowerCase(java.util.Locale.ROOT));
                    bridgeInheritance.add(node);
                } else {
                    // Appartenance EXTERNE (grade VIP, équipe…) : jamais touchée.
                    preserved.add("groupe " + inheritance.getGroupName());
                }
                continue;
            }
            if (node instanceof PermissionNode permission && node.getValue()) {
                // Nœud de permission posé DIRECTEMENT sur l'utilisateur : hors du pont, même s'il
                // porte exactement un nom géré et le même contexte de monde. C'est le point qui
                // empêche d'écraser une décision prise à la main.
                preserved.add(permission.getPermission()
                        + (worldOf(node) == null ? "" : " (monde " + worldOf(node) + ")"));
            }
        }

        for (String wanted : wantedGroups) {
            if (currentBridgeGroups.contains(wanted)) {
                unchanged.add("groupe " + wanted);
                continue;
            }
            user.data().add(InheritanceNode.builder(wanted).build());
            added.add("groupe " + wanted);
        }
        for (Node node : bridgeInheritance) {
            String held = ((InheritanceNode) node).getGroupName().toLowerCase(java.util.Locale.ROOT);
            if (!wantedGroups.contains(held)) {
                user.data().remove(node);
                removed.add("groupe " + held);
            }
        }
        api.getUserManager().saveUser(user).join();

        String message = added.isEmpty() && removed.isEmpty()
                ? "Déjà conforme : aucune appartenance modifiée."
                : added.size() + " appartenance(s) ajoutée(s), " + removed.size() + " retirée(s).";
        return new SyncResult(true, message, List.copyOf(added), List.copyOf(removed),
                List.copyOf(unchanged), List.copyOf(preserved));
    }

    /**
     * Droits <strong>effectivement</strong> portés par les groupes du pont auxquels ce joueur
     * appartient, avec le groupe d'où ils viennent. Sert à afficher la provenance réelle plutôt que
     * l'état voulu par le panel.
     */
    public CompletableFuture<List<String>> readBridgeProvenance(UUID playerId) {
        Availability availability = availability();
        if (!availability.available()) {
            return CompletableFuture.failedFuture(new IllegalStateException(availability.reason()));
        }
        try {
            LuckPerms api = LuckPermsProvider.get();
            return api.getUserManager().loadUser(playerId).thenApply(user -> {
                List<String> out = new ArrayList<>();
                for (Node node : user.getNodes()) {
                    if (!(node instanceof InheritanceNode inheritance)
                            || !BridgeGroupNaming.isBridgeGroup(inheritance.getGroupName())) {
                        continue;
                    }
                    Group group = api.getGroupManager().getGroup(inheritance.getGroupName());
                    if (group == null) {
                        continue;
                    }
                    for (Node granted : group.getNodes()) {
                        if (granted instanceof PermissionNode permission && granted.getValue()
                                && ManagedNodePolicy.isManaged(permission.getPermission())) {
                            out.add(new ManagedNode(permission.getPermission(), worldOf(granted)).describe()
                                    + " ← " + inheritance.getGroupName());
                        }
                    }
                }
                return out;
            });
        } catch (RuntimeException | LinkageError e) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Lecture LuckPerms impossible : " + e.getClass().getSimpleName(), e));
        }
    }

    /**
     * Classe <strong>et message</strong> de l'erreur. La classe seule ne suffit pas : c'est
     * précisément ce qui a rendu un refus de longueur de nom indéchiffrable depuis le panel.
     */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + " — " + message;
    }

    private static Node toPermissionNode(ManagedNode managed) {
        PermissionNode.Builder builder = PermissionNode.builder(managed.node()).value(true);
        if (!managed.isGlobal()) {
            builder.context(ImmutableContextSet.of("world", managed.world()));
        }
        return builder.build();
    }

    /** Monde porté par le contexte du nœud, ou {@code null} s'il est global. */
    private static String worldOf(Node node) {
        Optional<String> world = node.getContexts().getAnyValue("world");
        return world.orElse(null);
    }
}
