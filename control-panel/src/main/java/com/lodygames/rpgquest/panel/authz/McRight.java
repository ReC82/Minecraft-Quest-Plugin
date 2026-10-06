package com.lodygames.rpgquest.panel.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Catalogue des droits <strong>Minecraft</strong> que le pont sait gérer (issue #200), côté panel.
 *
 * <h2>Pourquoi un catalogue et pas une saisie libre</h2>
 *
 * <p>Laisser taper un nœud arbitraire reviendrait à offrir une élévation de privilège : il suffirait
 * d'écrire {@code rpgquest.admin.world} pour tout obtenir. Le panel ne propose donc que des droits
 * connus, et le plugin revérifie de son côté ({@code ManagedNodePolicy}) — la liste du panel est une
 * commodité, pas la sécurité.</p>
 *
 * <h2>Quatre familles volontairement distinctes</h2>
 *
 * <p>C'est l'exigence explicite du ticket : « sépare clairement permissions PlugAdmin, OP
 * Minecraft, droits de construction par monde, création/gestion de PNJ, bypass gameplay ». Les
 * permissions PlugAdmin sont les {@link Permission} (une autre page) ; OP Minecraft reste
 * {@code player.op} (une autre action, réservée au propriétaire) ; ici on ne trouve que les trois
 * dernières familles.</p>
 */
public final class McRight {

    private McRight() {
    }

    /** À quoi sert un droit : décide de son regroupement et de l'exigence d'un monde. */
    public enum Family {
        BUILD("Construction", true),
        NPC("PNJ et commande d'administration", false),
        BYPASS("Bypass de protection", false);

        private final String label;
        private final boolean worldRequired;

        Family(String label, boolean worldRequired) {
            this.label = label;
            this.worldRequired = worldRequired;
        }

        public String label() {
            return label;
        }

        /**
         * {@code true} si un monde est <strong>obligatoire</strong>. Un droit de construction sans
         * monde s'appliquerait partout, ce qui est précisément ce que « par monde » doit empêcher :
         * autoriser le Hub ne doit pas autoriser les claims.
         */
        public boolean worldRequired() {
            return worldRequired;
        }
    }

    /**
     * @param node     nœud de permission
     * @param family   famille, qui décide de l'exigence d'un monde
     * @param label    libellé humain
     * @param help     ce que le droit fait, et ce qu'il ne fait pas
     * @param perWorld {@code true} si ce droit est décliné par monde (nœud construit à la volée)
     */
    public record Definition(String node, Family family, String label, String help, boolean perWorld) {
    }

    /** Préfixe du droit de construire dans un Hub — complété par le nom du monde. */
    public static final String BUILD_HUB_PREFIX = "rpgquest.build.hub.";
    public static final String BUILD_HUB_ALL = "rpgquest.build.hub.*";

    /** Droits à nœud fixe, proposés tels quels. */
    public static final List<Definition> FIXED = List.of(
            new Definition(BUILD_HUB_ALL, Family.BUILD, "Construire dans tous les Hubs",
                    "N'accorde aucun bypass de claim, de zone protégée ou de waypoint, et aucune "
                            + "commande d'administration.", false),
            new Definition("rpgquest.build.wild", Family.BUILD, "Construire dans le Wild",
                    "SANS EFFET à ce jour : le Wild n'a aucune restriction de construction, un "
                            + "joueur ordinaire y construit déjà librement. Proposé pour la "
                            + "convention et un éventuel verrou futur ; n'accorde aucun bypass.", false),
            new Definition("rpgquest.admin.command", Family.NPC, "Entrer dans /rpgadmin",
                    "Ouvre la commande SANS autoriser aucune de ses branches. Nécessaire pour "
                            + "utiliser /rpgadmin npc, inutile seul.", false),
            new Definition("rpgquest.admin.npc", Family.NPC, "Branche PNJ de /rpgadmin",
                    "Autorise tag, untag et info. ATTENTION : marquer une entité NE CRÉE PAS un PNJ "
                            + "Citizens — la création passe par les actions du panel (permissions "
                            + "PlugAdmin NPC_WRITE / NPC_SPAWN_WRITE) ou par les commandes de "
                            + "Citizens, qui ont leurs propres permissions.", false),
            new Definition("rpgquest.admin.npc.tag", Family.NPC, "Marquer une entité (tag)",
                    "Pose un identifiant RPGQuest stable sur l'entité visée.", false),
            new Definition("rpgquest.admin.npc.untag", Family.NPC, "Retirer le marquage (untag)",
                    "Retire l'identifiant RPGQuest de l'entité visée.", false),
            new Definition("rpgquest.admin.npc.info", Family.NPC, "Lire le marquage (info)",
                    "Lecture seule.", false),
            new Definition("rpgquest.bypass.claim", Family.BYPASS, "Contourner les claims d'autrui",
                    "Strictement distinct de toute permission de construction : un builder de Hub "
                            + "ne l'obtient jamais par son droit de build.", false),
            new Definition("rpgquest.bypass.zone", Family.BYPASS, "Contourner les zones protégées",
                    "Distinct des claims et de la construction.", false),
            new Definition("rpgquest.bypass.claimworld", Family.BYPASS,
                    "Accès administratif au monde des claims",
                    "Entrer sans être éligible et ignorer les règles de ce monde. Le correctif #22 "
                            + "reste appliqué : l'entrée n'est jamais refusée et une Pierre de "
                            + "retour est garantie.", false));

    /** Droit de construire dans un Hub précis, pour un monde connu du serveur. */
    public static Definition buildHub(String worldName) {
        return new Definition(BUILD_HUB_PREFIX + worldName, Family.BUILD,
                "Construire dans le Hub « " + worldName + " »",
                "Limité à ce monde. L'identifiant de Hub EST le nom du monde (voir la "
                        + "documentation) : plusieurs Hubs dans un même monde ne sont pas "
                        + "distinguables.", true);
    }

    /** Catalogue complet pour l'affichage : droits fixes + un droit de Hub par monde connu. */
    public static List<Definition> catalogue(List<String> knownWorlds) {
        List<Definition> out = new ArrayList<>();
        for (String world : knownWorlds) {
            if (world != null && !world.isBlank()) {
                out.add(buildHub(world.trim()));
            }
        }
        out.addAll(FIXED);
        return out;
    }

    /**
     * Droit du catalogue portant ce nœud, ou vide. Un nœud {@code rpgquest.build.hub.<monde>} est
     * reconnu même si le monde est inconnu du relevé courant : un droit déjà accordé ne doit pas
     * disparaître de l'écran parce que l'agent n'a pas répondu.
     */
    public static Optional<Definition> byNode(String node) {
        if (node == null || node.isBlank()) {
            return Optional.empty();
        }
        String candidate = node.trim().toLowerCase(Locale.ROOT);
        for (Definition definition : FIXED) {
            if (definition.node().equals(candidate)) {
                return Optional.of(definition);
            }
        }
        if (candidate.startsWith(BUILD_HUB_PREFIX) && !candidate.equals(BUILD_HUB_ALL)) {
            return Optional.of(buildHub(candidate.substring(BUILD_HUB_PREFIX.length())));
        }
        return Optional.empty();
    }

    /** {@code true} si le panel accepte de distribuer ce nœud. Le plugin revérifie de son côté. */
    public static boolean isOffered(String node) {
        return byNode(node).isPresent();
    }
}
