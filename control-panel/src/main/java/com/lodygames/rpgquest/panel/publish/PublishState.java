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
    SOURCE_ONLY("Source uniquement", "warn",
            "Enregistrée dans la source éditable, mais absente du serveur DEV : elle n'existe pas "
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
     * Les deux existent, mais leur contenu diffère.
     *
     * <p>Le cas le plus courant après une édition : on a enregistré, on n'a pas publié. C'est aussi
     * celui que l'ancien {@code SYNCED} masquait.</p>
     */
    DIFFERENT("Différent", "warn",
            "La source et le fichier DEV ne sont pas identiques : vos dernières modifications ne "
                    + "sont pas en jeu.",
            "Publier sur DEV"),

    /**
     * Le fichier DEV a changé en dehors du panel.
     *
     * <p>Publier écraserait un travail dont on ne sait rien. On exige donc une analyse explicite —
     * et surtout on ne tranche pas à la place de l'administrateur.</p>
     */
    CONFLICT("Conflit", "err",
            "Le fichier DEV a été modifié hors du panel depuis la dernière analyse. Publier "
                    + "écraserait cette modification.",
            "Voir les différences"),

    /**
     * Le fichier est sur DEV mais le moteur ne le charge pas.
     *
     * <p>Typiquement un identifiant déclaré dans le YAML qui ne correspond pas au nom du fichier, ou
     * une publication dont le rechargement a échoué. Le distinguer de « Différent » compte : ici le
     * contenu est bon, c'est le chargement qui ne l'est pas.</p>
     */
    NOT_LOADED("Publié, non chargé", "err",
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
        return this == CONFLICT || this == NOT_LOADED;
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
     * @param lastPublished empreinte DEV connue à la dernière publication réussie, {@code ""} si
     *                      aucune. Sert à distinguer « différent parce que je n'ai pas publié » de
     *                      « différent parce que quelqu'un a touché DEV »
     */
    public static PublishState of(boolean devKnown, boolean sourceKnown,
                                  String sourceSha, String devSha,
                                  boolean runtimeLoaded, String lastPublished) {
        if (!devKnown || !sourceKnown) {
            return UNKNOWN;
        }
        String source = sourceSha == null ? "" : sourceSha;
        String dev = devSha == null ? "" : devSha;

        if (source.isEmpty() && dev.isEmpty()) {
            return UNKNOWN;
        }
        if (source.isEmpty()) {
            return RUNTIME_ONLY;
        }
        if (dev.isEmpty()) {
            return SOURCE_ONLY;
        }
        // Le fichier DEV ne correspond ni à la source, ni à ce que nous y avions publié : quelqu'un
        // d'autre l'a modifié. C'est un conflit, pas un simple écart.
        if (!dev.equals(source) && lastPublished != null && !lastPublished.isEmpty()
                && !dev.equals(lastPublished)) {
            return CONFLICT;
        }
        if (!dev.equals(source)) {
            return DIFFERENT;
        }
        // Identiques — mais le moteur doit l'avoir confirmé pour mériter « Synchronisé ».
        return runtimeLoaded ? SYNCED : NOT_LOADED;
    }
}
