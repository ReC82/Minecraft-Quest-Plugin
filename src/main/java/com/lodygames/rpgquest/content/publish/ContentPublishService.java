package com.lodygames.rpgquest.content.publish;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publier une ressource de contenu sur ce serveur, puis <strong>prouver</strong> qu'elle est
 * chargée (issue #47).
 *
 * <h2>La règle qui gouverne tout ce fichier</h2>
 *
 * <p>« Fichier copié » n'est pas un succès. « Reload demandé » n'est pas un succès. Le seul succès
 * est : <em>le moteur voit maintenant cette ressource</em>. Chaque chemin de sortie ci-dessous dit
 * donc exactement jusqu'où on est allé, et {@link PublishOutcome#synchronized_()} n'est vrai que
 * lorsque la relecture du runtime l'a confirmé.</p>
 *
 * <h2>L'ordre, et pourquoi il n'est pas négociable</h2>
 *
 * <ol>
 *   <li>valider la famille et l'identifiant — une entrée forgée ne doit jamais atteindre le disque ;</li>
 *   <li>comparer l'état DEV à celui que l'appelant croyait voir — sinon on écrase une modification
 *       faite entre-temps, en silence ;</li>
 *   <li><strong>sauvegarder</strong> si un fichier existe. Si la sauvegarde échoue, on s'arrête :
 *       publier sans pouvoir revenir en arrière n'est pas acceptable ;</li>
 *   <li>écrire, de façon atomique ;</li>
 *   <li>recharger <strong>la seule famille concernée</strong> ;</li>
 *   <li>relire le runtime et vérifier que l'identifiant attendu y est.</li>
 * </ol>
 *
 * <p>Un échec à l'étape 5 ou 6 laisse le fichier écrit — et le résultat le <strong>dit</strong>,
 * avec le chemin de la sauvegarde. Prétendre le contraire serait pire que l'échec lui-même, parce
 * que l'administrateur chercherait au mauvais endroit.</p>
 *
 * <h2>Thread</h2>
 *
 * <p>À appeler <strong>sur le thread principal</strong>, comme {@code content.reload} dont il
 * dépend : le rechargement permute des ensembles lus par des listeners du thread principal. L'écriture
 * de quelques kilo-octets qui le précède est négligeable devant le rechargement lui-même, qui relit
 * déjà tous les fichiers de contenu sur ce même thread.</p>
 */
public final class ContentPublishService {

    private final ContentPublishStore store;
    private final ContentApplier applier;
    private final Clock clock;

    /**
     * Verrou logique <strong>par ressource</strong>.
     *
     * <p>Deux publications de la même ressource ne doivent pas s'entrelacer : la seconde lirait
     * l'état DEV laissé par la première et conclurait à un conflit, ou écraserait sa sauvegarde.
     * Deux ressources <em>différentes</em> ne se bloquent pas — rien ne le justifierait.</p>
     */
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public ContentPublishService(ContentPublishStore store, ContentApplier applier) {
        this(store, applier, Clock.systemUTC());
    }

    public ContentPublishService(ContentPublishStore store, ContentApplier applier, Clock clock) {
        this.store = store;
        this.applier = applier;
        this.clock = clock;
    }

    /**
     * Le compte rendu d'une publication, assez détaillé pour être auditable sans relire le serveur.
     *
     * @param code             {@code PUBLISHED}, {@code UNCHANGED}, {@code CONFLICT},
     *                         {@code INVALID_KIND}, {@code INVALID_ID}, {@code EMPTY_CONTENT},
     *                         {@code BACKUP_FAILED}, {@code WRITE_FAILED}, {@code RELOAD_FAILED},
     *                         {@code RUNTIME_MISSING}, {@code BUSY}
     * @param created          vrai si la ressource n'existait pas encore sur DEV
     * @param backupPath       chemin relatif de la sauvegarde, ou {@code ""} s'il n'y avait rien à
     *                         sauvegarder (ressource nouvelle)
     * @param runtimeConfirmed le moteur voit-il réellement la ressource, après rechargement
     */
    public record PublishOutcome(boolean ok, String code, String message,
                                 PublishKind kind, String slug, String expectedId,
                                 String devShaBefore, String devShaAfter, String sourceSha,
                                 boolean created, String backupPath,
                                 boolean reloadApplied, String reloadCode, String reloadMessage,
                                 int loadedCount, int issueCount,
                                 boolean runtimeConfirmed, String runtimeHash,
                                 Instant verifiedAt) {

        /** Le seul état qui autorise le badge « Synchronisé ». */
        public boolean synchronized_() {
            return ok && runtimeConfirmed;
        }
    }

    /** Ce que le panel a besoin de savoir de l'état DEV, sans rien modifier. */
    public record DevState(PublishKind kind, String slug, String sha256, boolean present,
                           boolean runtimeLoaded) {
    }

    /**
     * L'état DEV d'une ressource : présente sur disque ? chargée par le moteur ?
     *
     * <p><strong>N'écrit rien.</strong> C'est ce qui permet au panel de distinguer « différent » de
     * « conflit » <em>avant</em> de proposer de publier.</p>
     */
    public DevState state(PublishKind kind, String slug, String expectedId) {
        if (kind == null || !PublishKind.validSlug(slug)) {
            return new DevState(kind, slug, "", false, false);
        }
        String sha = store.sha256(kind, slug);
        String id = expectedId == null || expectedId.isBlank() ? slug : expectedId;
        return new DevState(kind, slug, sha, !sha.isEmpty(),
                applier.runtimeHas(kind.family(), id));
    }

    /** Toutes les ressources présentes sur DEV pour une famille, avec leur empreinte. */
    public List<ContentPublishStore.DevFile> list(PublishKind kind) {
        return store.list(kind);
    }

    /** Les identifiants réellement chargés par le moteur pour cette famille. */
    public List<String> runtimeIds(PublishKind kind) {
        return applier.loadedIds(kind.family());
    }

    /**
     * Publie une ressource.
     *
     * @param expectedDevSha l'empreinte DEV que l'appelant croyait voir ({@code ""} = « absente »).
     *                       {@code null} désactive la détection de conflit, à n'utiliser que pour
     *                       une publication délibérément forcée
     * @param expectedId     l'identifiant que le moteur devra porter. Il peut différer du slug (une
     *                       quête déclare {@code rpgquest:<id>} dans son YAML), et c'est lui qu'on
     *                       vérifie — vérifier le slug donnerait un faux échec
     */
    public PublishOutcome publish(PublishKind kind, String slug, String yaml,
                                  String expectedDevSha, String expectedId) {
        Instant now = clock.instant();
        if (kind == null) {
            return refused("INVALID_KIND", "Famille de contenu inconnue.", null, slug, now);
        }
        if (!PublishKind.validSlug(slug)) {
            return refused("INVALID_ID",
                    "Identifiant de ressource invalide : « " + slug + " ».", kind, slug, now);
        }
        if (yaml == null || yaml.isBlank()) {
            return refused("EMPTY_CONTENT", "Contenu vide : rien à publier.", kind, slug, now);
        }
        if (store.resolve(kind, slug).isEmpty()) {
            // Ne devrait pas arriver après les contrôles ci-dessus — mais si un jour le motif du
            // slug s'assouplit, c'est ce refus qui tiendra.
            return refused("INVALID_ID", "Chemin de ressource refusé.", kind, slug, now);
        }

        String lockKey = kind.directory() + "/" + slug;
        if (inFlight.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return refused("BUSY",
                    "Une publication de cette ressource est déjà en cours.", kind, slug, now);
        }
        try {
            String sourceSha = ContentPublishStore.sha256Of(yaml);
            String before = store.sha256(kind, slug);
            boolean created = before.isEmpty();
            String id = expectedId == null || expectedId.isBlank() ? slug : expectedId;

            // Conflit : l'état DEV n'est pas celui que l'appelant avait sous les yeux. On ne tranche
            // pas à sa place, on refuse et on lui rend les deux empreintes.
            if (expectedDevSha != null && !expectedDevSha.equals(before)) {
                // Le message dépend de ce que l'APPELANT croyait voir, pas de l'état courant :
                // « vous pensiez qu'elle n'existait pas, or elle existe » et « le fichier a changé »
                // amènent l'administrateur à vérifier deux choses différentes.
                boolean appearedSincePreview = expectedDevSha.isEmpty() && !before.isEmpty();
                boolean vanishedSincePreview = !expectedDevSha.isEmpty() && before.isEmpty();
                return new PublishOutcome(false, "CONFLICT",
                        appearedSincePreview
                                ? "La ressource a été créée sur DEV depuis votre dernière analyse : "
                                        + "publication refusée pour ne rien écraser."
                                : vanishedSincePreview
                                        ? "Le fichier DEV a disparu depuis votre dernière analyse : "
                                                + "relancez l'analyse avant de publier."
                                        : "Le fichier DEV a changé depuis votre dernière analyse : "
                                                + "publication refusée pour ne rien écraser en "
                                                + "silence.",
                        kind, slug, id, before, before, sourceSha, created, "",
                        false, "", "", 0, 0,
                        applier.runtimeHas(kind.family(), id),
                        applier.runtimeHash(), now);
            }

            // Déjà identique. On ne réécrit pas — mais si le moteur ne la voit pas, c'est qu'elle
            // n'a jamais été rechargée : on recharge sans toucher au fichier.
            if (!created && before.equals(sourceSha)) {
                if (applier.runtimeHas(kind.family(), id)) {
                    return new PublishOutcome(true, "UNCHANGED",
                            "Le fichier DEV est déjà identique à la source, et "
                                    + kind.article() + " est chargé" + femme(kind) + ".",
                            kind, slug, id, before, before, sourceSha, false, "",
                            false, "ALREADY_LOADED", "", 0, 0, true,
                            applier.runtimeHash(), clock.instant());
                }
                return reloadAndVerify(kind, slug, id, before, before, sourceSha, false, "",
                        "UNCHANGED");
            }

            // Sauvegarde AVANT écriture. Un échec ici arrête tout : publier sans pouvoir revenir en
            // arrière n'est pas acceptable.
            String backupPath = "";
            if (!created) {
                try {
                    Optional<ContentPublishStore.Backup> backup =
                            store.backup(kind, slug, ContentPublishStore.stamp(now));
                    backupPath = backup.map(ContentPublishStore.Backup::relativePath).orElse("");
                } catch (IOException error) {
                    return new PublishOutcome(false, "BACKUP_FAILED",
                            "Sauvegarde du fichier DEV impossible, donc rien n'a été publié : "
                                    + describe(error),
                            kind, slug, id, before, before, sourceSha, false, "",
                            false, "", "", 0, 0, false, applier.runtimeHash(), now);
                }
            }

            String after;
            try {
                after = store.write(kind, slug, yaml);
            } catch (IOException error) {
                // L'écriture a échoué : on tente de remettre la sauvegarde pour ne pas laisser un
                // fichier à moitié remplacé.
                String restored = "";
                final String savedBackup = backupPath;
                if (!savedBackup.isEmpty()) {
                    restored = store.readBackup(savedBackup).map(text -> {
                        try {
                            store.write(kind, slug, text);
                            return " L'ancien fichier a été remis en place.";
                        } catch (IOException ignored) {
                            return " ATTENTION : l'ancien fichier n'a pas pu être remis en place ; "
                                    + "la sauvegarde est « " + savedBackup + " ».";
                        }
                    }).orElse("");
                }
                return new PublishOutcome(false, "WRITE_FAILED",
                        "Écriture impossible sur DEV : " + describe(error) + restored,
                        kind, slug, id, before, store.sha256(kind, slug), sourceSha, created,
                        backupPath, false, "", "", 0, 0, false, applier.runtimeHash(), now);
            }

            return reloadAndVerify(kind, slug, id, before, after, sourceSha, created, backupPath,
                    "PUBLISHED");
        } finally {
            inFlight.remove(lockKey);
        }
    }

    /**
     * Retire une publication.
     *
     * <p>Deux cas, et un seul est une vraie restauration :</p>
     * <ul>
     *   <li>une sauvegarde existe → on la repose, donc on <strong>restaure</strong> l'état
     *       précédent ;</li>
     *   <li>la ressource était <strong>nouvelle</strong> → il n'y a rien à restaurer, et le seul
     *       retour arrière honnête est de <strong>retirer</strong> le fichier qu'on a ajouté.</li>
     * </ul>
     *
     * <p>On ne prétend donc jamais « restaurer » quand on supprime, ni l'inverse.</p>
     */
    public PublishOutcome rollback(PublishKind kind, String slug, String backupPath,
                                   String expectedId) {
        Instant now = clock.instant();
        if (kind == null || !PublishKind.validSlug(slug)) {
            return refused("INVALID_ID", "Ressource invalide.", kind, slug, now);
        }
        String lockKey = kind.directory() + "/" + slug;
        if (inFlight.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return refused("BUSY", "Une opération sur cette ressource est déjà en cours.",
                    kind, slug, now);
        }
        try {
            String id = expectedId == null || expectedId.isBlank() ? slug : expectedId;
            String before = store.sha256(kind, slug);

            if (backupPath != null && !backupPath.isBlank()) {
                Optional<String> text = store.readBackup(backupPath);
                if (text.isEmpty()) {
                    return new PublishOutcome(false, "BACKUP_MISSING",
                            "Sauvegarde « " + backupPath + " » introuvable ou refusée : "
                                    + "aucune restauration tentée.",
                            kind, slug, id, before, before, "", false, backupPath,
                            false, "", "", 0, 0, false, applier.runtimeHash(), now);
                }
                String after;
                try {
                    after = store.write(kind, slug, text.get());
                } catch (IOException error) {
                    return new PublishOutcome(false, "WRITE_FAILED",
                            "Restauration impossible : " + describe(error),
                            kind, slug, id, before, store.sha256(kind, slug), "", false,
                            backupPath, false, "", "", 0, 0, false,
                            applier.runtimeHash(), now);
                }
                return reloadAndVerify(kind, slug, id, before, after,
                        ContentPublishStore.sha256Of(text.get()), false, backupPath, "RESTORED");
            }

            // Pas de sauvegarde : la ressource était nouvelle, donc on la retire.
            boolean removed;
            try {
                removed = store.delete(kind, slug);
            } catch (IOException error) {
                return new PublishOutcome(false, "WRITE_FAILED",
                        "Retrait impossible : " + describe(error),
                        kind, slug, id, before, before, "", false, "", false, "", "", 0, 0,
                        false, applier.runtimeHash(), now);
            }
            if (!removed) {
                return new PublishOutcome(true, "ALREADY_ABSENT",
                        "Le fichier n'était plus sur DEV : rien à retirer.",
                        kind, slug, id, before, "", "", false, "", false, "", "", 0, 0,
                        false, applier.runtimeHash(), clock.instant());
            }
            // Après retrait, « confirmé » veut dire : le moteur ne la voit PLUS.
            ContentApplier.ApplyResult reload = applier.reload(kind.family());
            boolean gone = !applier.runtimeHas(kind.family(), id);
            return new PublishOutcome(reload.applied() && gone,
                    gone ? "WITHDRAWN" : "RUNTIME_STILL_PRESENT",
                    gone
                            ? "Fichier retiré de DEV et " + kind.article()
                                    + " n'est plus chargé" + femme(kind) + "."
                            : "Fichier retiré mais le moteur la voit encore : rechargement "
                                    + reload.code() + ".",
                    kind, slug, id, before, "", "", false, "",
                    reload.applied(), reload.code(), reload.message(),
                    reload.loaded(), reload.issues(), !gone,
                    reload.runtimeHash(), clock.instant());
        } finally {
            inFlight.remove(lockKey);
        }
    }

    /**
     * Recharge la famille puis vérifie que le moteur porte l'identifiant attendu.
     *
     * <p>C'est le cœur de la promesse de #47, et le seul endroit qui accorde {@code ok = true}.</p>
     */
    private PublishOutcome reloadAndVerify(PublishKind kind, String slug, String id,
                                           String before, String after, String sourceSha,
                                           boolean created, String backupPath, String successCode) {
        ContentApplier.ApplyResult reload = applier.reload(kind.family());
        if (!reload.applied()) {
            return new PublishOutcome(false, "RELOAD_FAILED",
                    "Le fichier est écrit sur DEV, mais le rechargement a échoué ("
                            + reload.code() + ") : " + reload.message()
                            + (backupPath.isEmpty() ? "" : " Sauvegarde : « " + backupPath + " »."),
                    kind, slug, id, before, after, sourceSha, created, backupPath,
                    false, reload.code(), reload.message(),
                    reload.loaded(), reload.issues(), false, reload.runtimeHash(),
                    clock.instant());
        }
        boolean confirmed = applier.runtimeHas(kind.family(), id);
        if (!confirmed) {
            return new PublishOutcome(false, "RUNTIME_MISSING",
                    "Le fichier est écrit et la famille rechargée, mais le moteur ne voit pas « "
                            + id + " ». Vérifiez que l'identifiant déclaré dans le YAML correspond "
                            + "bien au nom du fichier.",
                    kind, slug, id, before, after, sourceSha, created, backupPath,
                    true, reload.code(), reload.message(),
                    reload.loaded(), reload.issues(), false, reload.runtimeHash(),
                    clock.instant());
        }
        return new PublishOutcome(true, successCode,
                successCode.equals("RESTORED")
                        ? "Version précédente restaurée et rechargée : " + kind.article()
                                + " est de nouveau chargé" + femme(kind) + " en jeu."
                        : (created ? "Créé" + femme(kind) : "Mis à jour")
                                + " sur DEV, rechargé" + femme(kind)
                                + " et confirmé" + femme(kind) + " par le moteur.",
                kind, slug, id, before, after, sourceSha, created, backupPath,
                true, reload.code(), reload.message(),
                reload.loaded(), reload.issues(), true, reload.runtimeHash(),
                clock.instant());
    }

    private PublishOutcome refused(String code, String message, PublishKind kind, String slug,
                                   Instant now) {
        return new PublishOutcome(false, code, message, kind, slug, slug, "", "", "", false, "",
                false, "", "", 0, 0, false, applier.runtimeHash(), now);
    }

    /** Accord en genre : « la story est chargée », « le dialogue est chargé ». */
    private static String femme(PublishKind kind) {
        return kind == PublishKind.QUESTS || kind == PublishKind.STORIES ? "e" : "";
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : " — " + message);
    }
}
