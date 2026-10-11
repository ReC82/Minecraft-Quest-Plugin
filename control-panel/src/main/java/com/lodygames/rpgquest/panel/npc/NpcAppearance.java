package com.lodygames.rpgquest.panel.npc;

import java.util.Locale;

/**
 * État d'apparence d'un PNJ, tel que le panel peut honnêtement l'affirmer (issue #197).
 *
 * <p><strong>Le malentendu que cette classe existe pour empêcher.</strong> L'API publique de
 * Citizens ne permet pas de <em>lire</em> le skin appliqué à un PNJ. RPGQuest ne connaît donc que
 * les apparences qu'il a lui-même posées et enregistrées ({@code npc_citizens_skins}). Il serait
 * faux d'en conclure qu'un PNJ dont la source est inconnue « n'a pas de skin » : il en a
 * probablement un, visible par tous les joueurs, dont nous ignorons seulement l'origine. La nuance
 * n'est pas cosmétique — c'est exactement elle qui faisait échouer le renommage du Guide en Elias
 * avec un message incompréhensible. Le statut dit donc <em>ce que RPGQuest sait</em>, jamais ce
 * qu'il suppose.</p>
 *
 * <p><strong>Pourquoi une classe pure.</strong> Aucun type Citizens, Bukkit ni HTML ici : les
 * cinq états et leurs règles de priorité se testent sans serveur, comme
 * {@code SkinPreservationPlanner} côté plugin — dont cette classe est le pendant d'affichage.</p>
 *
 * @param status      l'état retenu
 * @param reason      le fait précis qui a produit cet état — deux situations très différentes
 *                    peuvent mener au même statut, et seule la raison permet de savoir si un
 *                    renommage sera refusé
 * @param label       libellé court, pour une pastille de tuile
 * @param explanation phrase complète, en français, qui dit ce qui est su et ce qui ne l'est pas
 * @param sourceLabel description de la source enregistrée, ou texte vide
 */
public record NpcAppearance(Status status, Reason reason, String label, String explanation,
                            String sourceLabel) {

    /** Les cinq états demandés par l'issue. Aucun ne signifie « probablement ». */
    public enum Status {
        /** RPGQuest connaît la source de l'apparence et sait la reproduire à l'identique. */
        CONFIGURED,
        /**
         * Il reste quelque chose à décider. Le cas le plus fréquent : un PNJ joueur dont
         * l'apparence existe en jeu mais dont la source n'est pas enregistrée.
         */
        TO_CONFIGURE,
        /** Une apparence a été demandée ; le relevé Citizens ne l'a pas encore confirmée. */
        IN_PROGRESS,
        /** La dernière tentative d'apparence a échoué. */
        ERROR,
        /**
         * La question du <strong>skin de joueur</strong> ne se pose pas : ce PNJ n'est pas de type
         * {@code PLAYER}. Son apparence est son type d'entité, et elle est connue.
         */
        NOT_APPLICABLE
    }

    /**
     * Le fait qui a produit le statut. Les deux dernières valeurs correspondent exactement aux deux
     * refus de {@code SkinPreservationPlanner} côté plugin : c'est ce qui permet au panel
     * d'<strong>avertir avant la soumission</strong> plutôt que de laisser le serveur refuser.
     */
    public enum Reason {
        /** Aucun PNJ Citizens lié : il n'y a pas d'apparence en jeu à décrire. */
        NO_ENTITY,
        /** La dernière tentative a échoué. */
        ATTEMPT_FAILED,
        /** Tentative acceptée mais non encore confirmée par un relevé. */
        ATTEMPT_PENDING,
        /** PNJ non joueur : la question du skin ne se pose pas. */
        NON_PLAYER,
        /** RPGQuest connaît la source et sait la reproduire. */
        SOURCE_RECORDED,
        /** PNJ joueur dont la source d'apparence est inconnue — le renommage sera refusé. */
        SOURCE_UNKNOWN,
        /** Citizens n'a pas donné le type : le renommage sera refusé aussi, par prudence. */
        TYPE_UNKNOWN
    }

    /**
     * Ce que le panel sait de la dernière tentative d'apparence sur ce PNJ.
     *
     * <p>Le rendu d'un skin est asynchrone : Citizens accepte une demande, la résout, puis
     * l'applique. Un succès d'action veut dire « demande transmise », pas « skin visible ». Ces
     * trois valeurs sont les seules que le journal d'actions du panel permette d'affirmer.</p>
     */
    public enum LastAttempt {
        /** Aucune tentative récente connue. */
        NONE,
        /** Tentative acceptée, mais postérieure au dernier relevé Citizens : non confirmée. */
        PENDING,
        /** Tentative refusée ou en échec. */
        FAILED
    }

    public NpcAppearance {
        label = label == null ? "" : label;
        explanation = explanation == null ? "" : explanation;
        sourceLabel = sourceLabel == null ? "" : sourceLabel;
    }

    /**
     * L'état d'apparence déduit des seuls faits du relevé.
     *
     * <p>Ordre de priorité délibéré : un échec connu passe devant tout le reste (il demande une
     * action immédiate), une demande non confirmée passe devant un état déduit du type (sinon on
     * annoncerait « configurée » alors que rien n'est encore prouvé), et le type d'entité ne
     * tranche qu'ensuite.</p>
     *
     * @param citizensBound   un PNJ Citizens est-il réellement lié à cette fiche
     * @param entityType      type d'entité du relevé ({@code PLAYER}, {@code VILLAGER}…), ou vide /
     *                        {@code null} si Citizens ne l'a pas donné. Vide = <strong>inconnu</strong>
     * @param skinSourceKind  {@code URL} ou {@code NAME} si RPGQuest a enregistré une source, sinon vide
     * @param skinSourceValue valeur de cette source (lien MineSkin ou pseudo), sinon vide
     * @param lastAttempt     ce que le journal d'actions du panel permet d'affirmer
     */
    public static NpcAppearance of(boolean citizensBound, String entityType,
                                   String skinSourceKind, String skinSourceValue,
                                   LastAttempt lastAttempt) {
        String type = clean(entityType);
        String kind = clean(skinSourceKind).toUpperCase(Locale.ROOT);
        String value = clean(skinSourceValue);
        LastAttempt attempt = lastAttempt == null ? LastAttempt.NONE : lastAttempt;
        String source = sourceLabelOf(kind, value);

        if (!citizensBound) {
            return new NpcAppearance(Status.NOT_APPLICABLE, Reason.NO_ENTITY, "pas de PNJ en jeu",
                    "Aucun PNJ Citizens n'est lié à cette fiche : il n'y a pas encore d'apparence "
                            + "en jeu. Lier ou créer le PNJ d'abord.", source);
        }
        if (attempt == LastAttempt.FAILED) {
            return new NpcAppearance(Status.ERROR, Reason.ATTEMPT_FAILED, "erreur",
                    "La dernière application d'apparence a échoué. Le PNJ garde l'apparence qu'il "
                            + "avait : rien n'a été remplacé. Voir le détail de l'action, puis réessayer.",
                    source);
        }
        if (attempt == LastAttempt.PENDING) {
            return new NpcAppearance(Status.IN_PROGRESS, Reason.ATTEMPT_PENDING, "en cours",
                    "Une apparence a été demandée et acceptée, mais le relevé Citizens est plus "
                            + "ancien que cette demande : le panel ne peut pas encore la confirmer. "
                            + "Rafraîchir le relevé Citizens, puis vérifier en jeu — un client déjà "
                            + "connecté doit parfois se reconnecter pour voir le changement.",
                    source);
        }
        if (type.isEmpty()) {
            return new NpcAppearance(Status.TO_CONFIGURE, Reason.TYPE_UNKNOWN, "type inconnu",
                    "Citizens n'a pas indiqué le type de ce PNJ : impossible de dire si la question "
                            + "du skin le concerne. Rafraîchir le relevé Citizens — le panel préfère "
                            + "dire « inconnu » plutôt que de supposer.", source);
        }
        if (!"PLAYER".equals(type.toUpperCase(Locale.ROOT))) {
            return new NpcAppearance(Status.NOT_APPLICABLE, Reason.NON_PLAYER, "non applicable",
                    "Ce PNJ est de type « " + type + " » : il n'a pas de skin de joueur. Son "
                            + "apparence est son type d'entité, et elle est connue. Aucune source "
                            + "de skin n'est à configurer, et un renommage ne risque pas de la changer.",
                    source);
        }
        if (kind.isEmpty()) {
            // LE cas du Guide. Ne jamais écrire « sans skin » ici : c'est la SOURCE qui manque.
            return new NpcAppearance(Status.TO_CONFIGURE, Reason.SOURCE_UNKNOWN, "à configurer",
                    "Ce PNJ est de type joueur et son apparence n'est pas décrite dans RPGQuest. "
                            + "Elle est probablement bien visible en jeu, mais elle a été posée en "
                            + "dehors du panel : son origine est inconnue, donc impossible à "
                            + "reproduire. Conséquence concrète : son renommage est refusé, car il "
                            + "changerait son visage sans pouvoir le restituer. Définir une "
                            + "apparence règle les deux points.", source);
        }
        return new NpcAppearance(Status.CONFIGURED, Reason.SOURCE_RECORDED, "configurée",
                "L'apparence de ce PNJ est décrite dans RPGQuest (" + source + ") : elle sera "
                        + "réappliquée à l'identique avant chaque renommage, et survit aux "
                        + "redémarrages.", source);
    }

    /** Le statut appelle-t-il une action de l'administrateur ? C'est le critère du filtre. */
    public boolean needsConfiguration() {
        return status == Status.TO_CONFIGURE;
    }

    /**
     * Le serveur refusera-t-il un renommage à cause de l'apparence ?
     *
     * <p>Les deux raisons listées ici sont exactement les deux refus de
     * {@code SkinPreservationPlanner} ({@code REFUSE_SKIN_SOURCE_UNKNOWN} et
     * {@code REFUSE_TYPE_UNKNOWN}). C'est ce qui permet d'avertir <strong>avant</strong> la
     * soumission : l'écran annonce le refus que le serveur prononcerait, au lieu de le découvrir
     * après le clic — ce qui est précisément ce qui s'est passé avec Elias.</p>
     */
    public boolean blocksRename() {
        return reason == Reason.SOURCE_UNKNOWN || reason == Reason.TYPE_UNKNOWN;
    }

    /** Classe Bootstrap de la pastille — rouge seulement quand quelque chose est vraiment cassé. */
    public String badgeClass() {
        return switch (status) {
            case CONFIGURED -> "text-bg-success";
            case TO_CONFIGURE -> "text-bg-warning";
            case IN_PROGRESS -> "text-bg-info";
            case ERROR -> "text-bg-danger";
            case NOT_APPLICABLE -> "text-bg-secondary";
        };
    }

    /**
     * Catégorie de filtre portée par la tuile ({@code data-filter-cat}).
     *
     * <p><strong>Volontairement plus étroit que {@link #needsConfiguration()}.</strong> Le filtre
     * « Apparence à configurer » répond à une question de travail : « lesquels me reste-t-il à
     * habiller ? ». Un type d'entité inconnu n'est pas une apparence à décider, c'est un relevé
     * Citizens périmé — et comme un relevé antérieur à cette issue ne porte aucun type, l'inclure
     * ferait apparaître <em>tous</em> les PNJ dans le filtre après une mise à jour. La tuile le
     * signale quand même par sa pastille ; seule la liste de travail reste honnête.</p>
     */
    public String filterCategory() {
        return reason == Reason.SOURCE_UNKNOWN ? "skin-todo" : "";
    }

    /** Description de la source enregistrée, ou texte vide si RPGQuest n'en connaît aucune. */
    private static String sourceLabelOf(String kind, String value) {
        if (kind.isEmpty() || value.isEmpty()) {
            return "";
        }
        return switch (kind) {
            case "URL" -> "lien MineSkin " + value;
            case "NAME" -> "skin du compte « " + value + " »";
            default -> kind.toLowerCase(Locale.ROOT) + " " + value;
        };
    }

    /** Le relevé transporte parfois la chaîne {@code "null"} : elle ne veut dire que « absent ». */
    private static String clean(String raw) {
        String s = raw == null ? "" : raw.trim();
        return s.isEmpty() || "null".equals(s) ? "" : s;
    }
}
