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

    /**
     * Apparence par défaut d'un PNJ de type joueur (issue #325) — la même que le plugin
     * ({@code NpcIdentityService.DEFAULT_SKIN_URL}).
     */
    public static final String DEFAULT_SKIN_URL = "https://minesk.in/f534ead9d5a3413e9bb51bce9b211995";


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
        TYPE_UNKNOWN,
        /**
         * Issue #325 — la source est enregistrée, mais l'image que porte réellement le personnage
         * n'est pas la sienne (relue sur Citizens). C'était le cas de Lucien.
         */
        NOT_WORN
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
        return of(citizensBound, entityType, skinSourceKind, skinSourceValue, null, lastAttempt);
    }

    /**
     * @param worn issue #325 — l'image portée par le personnage est-elle celle de la source
     *             enregistrée ({@code skinMatchesSource} du relevé) ? {@code null} = invérifiable.
     */
    public static NpcAppearance of(boolean citizensBound, String entityType,
                                   String skinSourceKind, String skinSourceValue,
                                   Boolean worn, LastAttempt lastAttempt) {
        String type = clean(entityType);
        String kind = clean(skinSourceKind).toUpperCase(Locale.ROOT);
        String value = clean(skinSourceValue);
        LastAttempt attempt = lastAttempt == null ? LastAttempt.NONE : lastAttempt;
        String source = sourceLabelOf(kind, value);

        // Issue #325 : libellés et explications en français simple, sans mécanisme interne.
        if (!citizensBound) {
            return new NpcAppearance(Status.NOT_APPLICABLE, Reason.NO_ENTITY, "pas encore en jeu",
                    "Ce PNJ n'est pas encore dans le monde.", source);
        }
        if (attempt == LastAttempt.FAILED) {
            return new NpcAppearance(Status.ERROR, Reason.ATTEMPT_FAILED, "non appliquée",
                    "La dernière apparence n'a pas pu être appliquée. Le PNJ garde la précédente : "
                            + "réessayez avec un autre lien.", source);
        }
        if (attempt == LastAttempt.PENDING) {
            return new NpcAppearance(Status.IN_PROGRESS, Reason.ATTEMPT_PENDING, "en cours",
                    "Apparence en cours d'application. Actualisez les PNJ dans un instant pour "
                            + "voir le résultat.", source);
        }
        if (type.isEmpty()) {
            return new NpcAppearance(Status.TO_CONFIGURE, Reason.TYPE_UNKNOWN, "inconnue",
                    "Actualisez les PNJ pour connaître son apparence.", source);
        }
        if (!"PLAYER".equals(type.toUpperCase(Locale.ROOT))) {
            return new NpcAppearance(Status.NOT_APPLICABLE, Reason.NON_PLAYER,
                    type.toLowerCase(Locale.ROOT).replace('_', ' '),
                    "Ce PNJ n'a pas la forme d'un joueur : il n'a pas de skin à choisir.", source);
        }
        if (kind.isEmpty()) {
            // Ancien PNJ dont l'apparence a été posée hors du panel. Ce n'est plus un blocage
            // (#325) : un renommage pose l'apparence par défaut. On le dit, en une phrase.
            return new NpcAppearance(Status.TO_CONFIGURE, Reason.SOURCE_UNKNOWN, "d'origine",
                    "Son apparence a été choisie hors de PlugAdmin. Si vous le renommez sans en "
                            + "choisir une autre, il prendra l'apparence par défaut.", source);
        }
        boolean isDefault = "URL".equals(kind) && DEFAULT_SKIN_URL.equals(value);
        if (Boolean.FALSE.equals(worn)) {
            return new NpcAppearance(Status.ERROR, Reason.NOT_WORN, "pas appliquée",
                    "Le personnage ne porte pas " + (isDefault ? "l'apparence par défaut" : "l'apparence choisie")
                            + " en jeu. Appuyez sur « Réessayer ».", source);
        }
        String checked = Boolean.TRUE.equals(worn) ? " Vérifié sur le personnage." : "";
        return new NpcAppearance(Status.CONFIGURED, Reason.SOURCE_RECORDED,
                isDefault ? "par défaut" : "personnalisée",
                (isDefault ? "Ce PNJ porte l'apparence par défaut." : "Apparence choisie : " + source + ".")
                        + checked,
                source);
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
        return reason == Reason.TYPE_UNKNOWN;
    }

    /** Un renommage remplacera-t-il l'apparence actuelle par l'apparence par défaut (#325) ? */
    public boolean renameAppliesDefault() {
        return reason == Reason.SOURCE_UNKNOWN;
    }

    /** Classe Bootstrap de la pastille — rouge seulement quand quelque chose est vraiment cassé. */
    public String badgeClass() {
        return switch (status) {
            case CONFIGURED -> "text-bg-success";
            case TO_CONFIGURE -> "text-bg-secondary";
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
        return status == Status.ERROR ? "todo" : "";
    }

    /** Description de la source enregistrée, ou texte vide si RPGQuest n'en connaît aucune. */
    private static String sourceLabelOf(String kind, String value) {
        if (kind.isEmpty() || value.isEmpty()) {
            return "";
        }
        return switch (kind) {
            case "URL" -> DEFAULT_SKIN_URL.equals(value) ? "apparence par défaut" : "lien MineSkin " + value;
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
