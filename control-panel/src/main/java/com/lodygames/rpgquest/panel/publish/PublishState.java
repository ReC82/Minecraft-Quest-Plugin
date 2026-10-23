package com.lodygames.rpgquest.panel.publish;

/**
 * L'état d'une ressource entre la source éditable et le serveur DEV (issue #47).
 *
 * <h2>Un seul vocabulaire, pour toutes les pages</h2>
 *
 * <p>Avant #47, chaque écran disait les choses à sa façon : « Source uniquement », « pas encore
 * chargé en jeu », « hors source ». Pire, {@code SYNCED} signifiait seulement <em>présent des deux
 * côtés</em> — donc une quête modifiée dans la source mais pas republiée s'affichait
 * « Synchronisé », ce qui est exactement le mensonge que ce ticket existe pour supprimer.</p>
 *
 * <p>Chaque état porte ici son <strong>code stable</strong> (pour le journal et les tests), son
 * <strong>libellé humain</strong>, son <strong>explication</strong> et <strong>l'action
 * possible</strong>. Un écran qui affiche un état sans savoir quoi proposer ensuite laisse
 * l'utilisateur bloqué ; c'est pour cela que l'action fait partie de l'état.</p>
 */
public enum PublishState {

    /**
     * Source et DEV identiques, <strong>et</strong> le moteur l'a confirmé.
     *
     * <p>Le seul état qui autorise ce mot. Ni la copie du fichier, ni l'envoi de l'action, ni la
     * demande de rechargement ne suffisent à l'accorder.</p>
     */
    SYNCED("Synchronisé", "ok",
            "Le fichier DEV est identique à la source, et le moteur la voit en jeu.",
            null),

    /**
     * Enregistrée dans la source, absente de DEV.
     *
     * <p>C'est l'état du cas de référence de #47 : la quête existe, elle est validée, et elle
     * n'est tout simplement jamais arrivée sur le serveur.</p>
     */
    SOURCE_ONLY("Absent de DEV", "warn",
            "Enregistré dans PlugAdmin, mais absent du serveur DEV : ce contenu n'existe pas "
                    + "en jeu.",
            "Publier sur DEV"),

    /**
     * Présente sur DEV, absente de la source éditable.
     *
     * <p>Ni une erreur ni un défaut : du contenu livré avec le JAR, ou déposé à la main. On ne
     * propose donc pas de « publier », qui n'aurait rien à envoyer.</p>
     */
    RUNTIME_ONLY("Hors source", "info",
            "Chargée par le serveur mais absente de la source éditable du panel : elle vient du "
                    + "JAR ou a été déposée à la main.",
            null),

    /**
     * Les deux existent, DEV porte encore la version commune connue, et la source a changé depuis :
     * un <strong>brouillon</strong> enregistré, pas encore publié.
     *
     * <p>Issue #798 : cet état n'est plus accordé sans <em>référence commune</em> connue. Sans elle,
     * « différent » ne dit pas qui est le plus récent — et l'ancien libellé « pas encore en jeu »
     * faisait passer une source périmée pour un brouillon à publier.</p>
     */
    DIFFERENT("Brouillon non publié", "warn",
            "Vous avez modifié ce contenu dans PlugAdmin depuis la dernière version commune avec DEV : "
                    + "vos modifications ne sont pas encore en jeu.",
            "Publier sur DEV"),

    /**
     * Issue #798 — la source porte encore la version commune, mais DEV a changé depuis : la version
     * la plus récente est sur DEV (livrée avec le plugin, ou modifiée en jeu). Publier la source
     * ramènerait une ancienne version : on propose de récupérer celle de DEV.
     */
    DEV_NEWER("Plus récent sur DEV", "warn",
            "DEV a une version plus récente que celle de PlugAdmin. Publier remettrait l'ancienne : "
                    + "récupérez d'abord la version de DEV.",
            "Récupérer depuis DEV"),

    /**
     * Issue #798 — les deux versions diffèrent et aucune version commune n'est connue (ou DEV a
     * retiré une ressource que nous avions en commun) : on ne sait pas laquelle est la bonne. Rien
     * n'est publié tant que l'administrateur n'a pas comparé puis choisi.
     */
    DIVERGED("Versions différentes", "err",
            "PlugAdmin et DEV ont chacun une version différente, et rien ne dit laquelle est la plus "
                    + "récente. Comparez, puis récupérez celle de DEV ou conservez votre brouillon.",
            "Comparer"),

    /**
     * Le fichier DEV a changé en dehors du panel <strong>et</strong> la source aussi.
     *
     * <p>Publier écraserait un travail dont on ne sait rien. On exige donc une analyse explicite —
     * et surtout on ne tranche pas à la place de l'administrateur.</p>
     */
    CONFLICT("Conflit", "err",
            "Le fichier DEV et votre version ont tous deux changé depuis la dernière version commune. "
                    + "Publier écraserait la modification faite sur DEV.",
            "Voir les différences"),

    /**
     * Issue #798 — contenu retiré définitivement (#778). Il ne doit jamais revenir en jeu, même si
     * un ancien fichier traîne dans la source : la publication est refusée, seule ou en lot.
     */
    RETIRED("Retiré définitivement", "err",
            "Ce contenu a été retiré du jeu pour de bon : il ne peut plus être publié.",
            "Ne pas publier"),

    /**
     * Le fichier est sur DEV mais le moteur ne le charge pas.
     *
     * <p>Typiquement un identifiant déclaré dans le YAML qui ne correspond pas au nom du fichier, ou
     * une publication dont le rechargement a échoué. Le distinguer de « Différent » compte : ici le
     * contenu est bon, c'est le chargement qui ne l'est pas.</p>
     */
    NOT_LOADED("Sur DEV, non chargé", "err",
            "Le fichier est présent sur DEV mais le moteur ne le charge pas : vérifiez que "
                    + "l'identifiant déclaré correspond au nom du fichier.",
            "Republier"),

    /**
     * On ne sait pas.
     *
     * <p>Pas de relevé DEV, ou pas d'espace de travail source configuré. Afficher « Synchronisé »
     * ou « Source uniquement » par défaut serait une affirmation qu'on n'a pas le droit de faire —
     * c'est tout l'objet de cet état.</p>
     */
    UNKNOWN("État inconnu", "info",
            "Aucun relevé du serveur DEV : l'état réel de cette ressource n'est pas connu.",
            "Analyser");

    private final String label;
    private final String tone;
    private final String explanation;
    private final String action;

    PublishState(String label, String tone, String explanation, String action) {
        this.label = label;
        this.tone = tone;
        this.explanation = explanation;
        this.action = action;
    }

    /** Code stable, pour le journal d'audit et les tests. Jamais traduit. */
    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    /** Teinte d'affichage : {@code ok}, {@code warn}, {@code err} ou {@code info}. */
    public String tone() {
        return tone;
    }

    public String explanation() {
        return explanation;
    }

    /** Le libellé de l'action proposée, ou {@code null} s'il n'y a rien à faire. */
    public String action() {
        return action;
    }

    /** Vrai si une publication a un sens dans cet état. */
    public boolean publishable() {
        return this == SOURCE_ONLY || this == DIFFERENT || this == NOT_LOADED;
    }

    /** Vrai si l'état exige une décision humaine avant d'écrire. */
    public boolean needsAttention() {
        return needsDecision() || this == NOT_LOADED;
    }

    /**
     * Issue #798 — PlugAdmin et DEV divergent : il faut comparer, puis « Récupérer depuis DEV » ou
     * « Conserver mon brouillon ». Aucune publication, seule ou en lot, tant que ce choix n'est pas fait.
     */
    public boolean needsDecision() {
        return this == CONFLICT || this == DIVERGED || this == DEV_NEWER;
    }

    /**
     * L'état déduit des faits.
     *
     * <p>Fonction <strong>pure</strong>, et volontairement : c'est la règle que toutes les pages
     * partagent, et la seule façon d'être sûr qu'elles disent la même chose est qu'elles appellent
     * la même fonction. Elle est donc testée pour chaque combinaison.</p>
     *
     * @param devKnown      a-t-on un relevé du serveur ? Sinon rien n'est affirmable
     * @param sourceKnown   l'espace de travail source est-il configuré ?
     * @param sourceSha     empreinte de la source, {@code ""} si absente
     * @param devSha        empreinte du fichier DEV, {@code ""} si absent
     * @param runtimeLoaded le moteur porte-t-il cet identifiant ?
     * @param lastPublished la <strong>référence commune</strong> : la dernière version connue pour
     *                      avoir été identique des deux côtés (notre dernière écriture, une version
     *                      reprise de DEV, ou une égalité relevée), {@code ""} si aucune. C'est elle
     *                      qui dit QUI a changé : la source (brouillon), DEV (plus récent sur DEV),
     *                      ou les deux (conflit). Sans elle, on ne devine pas (issue #798)
     */
    public static PublishState of(boolean devKnown, boolean sourceKnown,
                                  String sourceSha, String devSha,
                                  boolean runtimeLoaded, String lastPublished) {
        return of(devKnown, sourceKnown, sourceSha, devSha, runtimeLoaded, lastPublished, false);
    }

    /**
     * {@link #of(boolean, boolean, String, String, boolean, String)}, en sachant si la ressource est
     * retirée définitivement ({@link RetiredContent}).
     */
    public static PublishState of(boolean devKnown, boolean sourceKnown,
                                  String sourceSha, String devSha,
                                  boolean runtimeLoaded, String lastPublished, boolean retired) {
        if (!devKnown || !sourceKnown) {
            return UNKNOWN;
        }
        String source = sourceSha == null ? "" : sourceSha;
        String dev = devSha == null ? "" : devSha;
        String base = lastPublished == null ? "" : lastPublished;

        if (source.isEmpty() && dev.isEmpty()) {
            return UNKNOWN;
        }
        if (source.isEmpty()) {
            return RUNTIME_ONLY;
        }
        if (retired) {
            return RETIRED;
        }
        if (dev.isEmpty()) {
            // Absente de DEV alors que nous avions une version commune : DEV l'a retirée. La
            // republier sans décision la ferait revenir en douce.
            return base.isEmpty() ? SOURCE_ONLY : DIVERGED;
        }
        if (dev.equals(source)) {
            // Identiques — mais le moteur doit l'avoir confirmé pour mériter « Synchronisé ».
            return runtimeLoaded ? SYNCED : NOT_LOADED;
        }
        if (base.isEmpty()) {
            return DIVERGED;
        }
        if (dev.equals(base)) {
            return DIFFERENT;
        }
        if (source.equals(base)) {
            return DEV_NEWER;
        }
        return CONFLICT;
    }

    /**
     * Réconcilie l'état lu dans un relevé avec une opération <strong>plus récente que lui</strong>
     * (issue #47, lot C).
     *
     * <h2>Le problème concret</h2>
     *
     * <p>Une publication est asynchrone : elle part, le serveur l'exécute, et le relevé d'état du
     * panel — qui date d'avant — continue d'afficher l'ancienne situation. L'administrateur voit
     * donc « Source uniquement » juste après avoir publié avec succès, ce qui ressemble à un échec.</p>
     *
     * <h2>Pourquoi on peut faire confiance à l'opération</h2>
     *
     * <p>Parce qu'elle a fait elle-même la vérification : le serveur a écrit le fichier, rechargé la
     * famille, <strong>relu le runtime</strong>, et renvoyé {@code runtimeConfirmed}. C'est une
     * preuve plus fraîche <em>et</em> plus directe qu'un relevé antérieur.</p>
     *
     * <p><strong>La règle du ticket est préservée</strong> : « Synchronisé » n'est accordé que si
     * l'opération a réellement confirmé le runtime <em>et</em> que l'empreinte qu'elle a laissée sur
     * DEV correspond à la source d'aujourd'hui. Si la source a bougé depuis, on ne réconcilie pas —
     * l'état redevient celui du relevé, qui n'est pas plus faux que le nôtre.</p>
     *
     * @param fromReading      l'état déduit du dernier relevé
     * @param readingIsStale   le relevé est-il antérieur à la dernière opération ?
     * @param lastOpOk         l'opération a-t-elle réussi ?
     * @param lastOpConfirmed  a-t-elle confirmé le runtime ?
     * @param lastOpDevSha     l'empreinte qu'elle a laissée sur DEV
     * @param sourceSha        l'empreinte de la source aujourd'hui
     */
    public static PublishState reconciled(PublishState fromReading, boolean readingIsStale,
                                          boolean lastOpOk, boolean lastOpConfirmed,
                                          String lastOpDevSha, String sourceSha) {
        if (!readingIsStale || !lastOpOk || !lastOpConfirmed) {
            return fromReading;
        }
        String left = lastOpDevSha == null ? "" : lastOpDevSha;
        String right = sourceSha == null ? "" : sourceSha;
        if (left.isEmpty() || !left.equals(right)) {
            // L'opération a confirmé un contenu qui n'est plus celui de la source : on ne peut rien
            // affirmer de mieux que le relevé.
            return fromReading;
        }
        return SYNCED;
    }
}
