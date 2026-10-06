package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.McRight;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Règles métier du pont vers les droits Minecraft (issue #200). Le stockage est dans
 * {@link McBridgeRepository} ; toutes les garanties sont ici.
 *
 * <h2>Un pseudonyme ne prouve rien</h2>
 *
 * <p>L'identité du joueur est son <strong>UUID</strong>. Le pseudonyme n'est conservé que pour
 * l'affichage, et le panel le dit : il change, et il ne constitue pas une preuve de contrôle du
 * compte. Pour ce lot, seule une <strong>liaison administrative</strong> existe — un administrateur
 * déclare « ce compte PlugAdmin correspond à ce joueur ». Il n'y a volontairement aucune
 * auto-liaison publique : elle exigerait une preuve de possession (code à taper en jeu), et c'est
 * un chantier distinct.</p>
 *
 * <h2>Conflits impossibles, pas seulement déconseillés</h2>
 *
 * <p>Un compte n'a qu'un joueur, un joueur n'a qu'un compte — garanti par deux contraintes
 * d'unicité en base. Modifier une liaison est explicite, et dissocier aussi.</p>
 */
public final class McBridgeDirectory {

    private final McBridgeRepository repo;

    public McBridgeDirectory(McBridgeRepository repo) {
        this.repo = repo;
    }

    // ---- Liaisons ------------------------------------------------------------------------------

    public Optional<McBridgeRepository.Link> linkOf(String userId) {
        return userId == null ? Optional.empty() : repo.linkOfUser(userId);
    }

    public List<McBridgeRepository.Link> links() {
        return repo.allLinks();
    }

    /**
     * Lie un compte PlugAdmin à un joueur, par UUID.
     *
     * <p>Le joueur peut être <strong>hors ligne</strong> : l'UUID suffit, et c'est volontaire —
     * exiger une connexion empêcherait de préparer les droits d'un builder avant son arrivée.</p>
     */
    public Outcome link(PanelUser target, String rawUuid, String mcName, String actor) {
        if (target == null) {
            return Outcome.failed("Compte introuvable.");
        }
        UUID uuid = parseUuid(rawUuid);
        if (uuid == null) {
            return Outcome.failed("UUID invalide : la forme attendue est "
                    + "« 00000000-0000-0000-0000-000000000000 ».");
        }
        String canonical = uuid.toString().toLowerCase(Locale.ROOT);

        Optional<McBridgeRepository.Link> onPlayer = repo.linkOfPlayer(canonical);
        if (onPlayer.isPresent() && !onPlayer.get().userId().equals(target.id())) {
            return Outcome.failed("Ce joueur est déjà lié à un autre compte PlugAdmin. "
                    + "Dissociez d'abord l'autre compte : un joueur ne peut pas recevoir les droits "
                    + "de deux comptes à la fois.");
        }
        Optional<McBridgeRepository.Link> before = repo.linkOfUser(target.id());
        if (before.isPresent() && before.get().mcUuid().equals(canonical)) {
            return Outcome.failed("Ce compte est déjà lié à ce joueur : rien à changer.");
        }

        McBridgeRepository.Link link = new McBridgeRepository.Link(target.id(), canonical,
                mcName == null || mcName.isBlank() ? null : mcName.trim(), Instant.now(), actor);
        try {
            repo.link(link);
        } catch (McBridgeRepository.PlayerAlreadyLinkedException e) {
            return Outcome.failed("Ce joueur est déjà lié à un autre compte PlugAdmin.");
        }
        return new Outcome(true, null, before.orElse(null), link);
    }

    /**
     * Dissocie un compte. <strong>Les droits Minecraft du joueur doivent être retirés</strong> par
     * une synchronisation : la dissociation seule n'enlève rien en jeu, et l'appelant doit le faire
     * savoir plutôt que de laisser croire que c'est fait.
     */
    public Outcome unlink(PanelUser target) {
        if (target == null) {
            return Outcome.failed("Compte introuvable.");
        }
        Optional<McBridgeRepository.Link> before = repo.linkOfUser(target.id());
        if (before.isEmpty()) {
            return Outcome.failed("Ce compte n'est lié à aucun joueur.");
        }
        repo.unlink(target.id());
        return new Outcome(true, null, before.get(), null);
    }

    // ---- Droits Minecraft par groupe -----------------------------------------------------------

    public List<McBridgeRepository.GroupNode> nodesOf(String groupId) {
        return groupId == null ? List.of() : repo.nodesOfGroup(groupId);
    }

    /**
     * Remplace les droits Minecraft d'un groupe.
     *
     * <p>Deux refus, et chacun protège une exigence du ticket : un nœud hors catalogue serait une
     * porte vers {@code rpgquest.admin.world} ; un droit de construction sans monde s'appliquerait
     * partout, ce qui annulerait la séparation « Hub ≠ claims ».</p>
     */
    public NodeOutcome setNodes(String groupId, Set<McBridgeRepository.GroupNode> wanted) {
        List<McBridgeRepository.GroupNode> before = nodesOf(groupId);
        Set<McBridgeRepository.GroupNode> cleaned = new LinkedHashSet<>();
        for (McBridgeRepository.GroupNode node : wanted) {
            Optional<McRight.Definition> definition = McRight.byNode(node.node());
            if (definition.isEmpty()) {
                return NodeOutcome.failed("Droit Minecraft inconnu ou non distribuable : « "
                        + node.node() + " ». Seuls les droits du catalogue peuvent être accordés — "
                        + "l'ombrelle rpgquest.admin.world n'en fait volontairement pas partie.");
            }
            if (definition.get().family().worldRequired() && node.isGlobal()) {
                return NodeOutcome.failed("« " + definition.get().label() + " » exige un monde : "
                        + "sans monde, le droit s'appliquerait partout et autoriser le Hub "
                        + "autoriserait aussi les claims.");
            }
            cleaned.add(new McBridgeRepository.GroupNode(groupId,
                    node.node().toLowerCase(Locale.ROOT), node.world() == null ? "" : node.world()));
        }
        repo.replaceNodesOfGroup(groupId, cleaned);
        return new NodeOutcome(true, null, before, nodesOf(groupId));
    }

    public void forgetGroup(String groupId) {
        repo.deleteNodesOfGroup(groupId);
    }

    /**
     * État <strong>voulu</strong> des droits Minecraft d'un compte : union des droits de ses
     * groupes. C'est ce que le panel demandera au serveur d'appliquer — l'état <em>réel</em>, lui,
     * se lit sur le serveur et peut différer (agent injoignable, LuckPerms absent).
     */
    public List<McBridgeRepository.GroupNode> desiredFor(List<PanelGroup> groups) {
        Set<McBridgeRepository.GroupNode> union = new LinkedHashSet<>();
        for (PanelGroup group : groups) {
            union.addAll(nodesOf(group.id()));
        }
        return new ArrayList<>(union);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Résultat d'une mutation de liaison, avec l'avant et l'après pour l'audit. */
    public record Outcome(boolean ok, String error, McBridgeRepository.Link before,
                          McBridgeRepository.Link after) {

        static Outcome failed(String error) {
            return new Outcome(false, error, null, null);
        }
    }

    /** Résultat d'une mutation de droits de groupe, avec l'avant et l'après pour l'audit. */
    public record NodeOutcome(boolean ok, String error, List<McBridgeRepository.GroupNode> before,
                              List<McBridgeRepository.GroupNode> after) {

        static NodeOutcome failed(String error) {
            return new NodeOutcome(false, error, List.of(), List.of());
        }
    }
}
