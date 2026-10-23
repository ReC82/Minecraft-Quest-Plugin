package com.lodygames.rpgquest.content.publish;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     *                         {@code RUNTIME_MISSING}, {@code BUSY}, {@code RETIRED} (#798)
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
                                 Instant verifiedAt, ContentApplier.ReloadDetail reloadDetail) {

        public PublishOutcome {
            reloadDetail = reloadDetail == null ? ContentApplier.ReloadDetail.EMPTY : reloadDetail;
        }

        /** Compte rendu sans rechargement (refus amont, conflit, écriture impossible…). */
        public PublishOutcome(boolean ok, String code, String message, PublishKind kind, String slug,
                              String expectedId, String devShaBefore, String devShaAfter,
                              String sourceSha, boolean created, String backupPath,
                              boolean reloadApplied, String reloadCode, String reloadMessage,
                              int loadedCount, int issueCount, boolean runtimeConfirmed,
                              String runtimeHash, Instant verifiedAt) {
            this(ok, code, message, kind, slug, expectedId, devShaBefore, devShaAfter, sourceSha,
                    created, backupPath, reloadApplied, reloadCode, reloadMessage, loadedCount,
                    issueCount, runtimeConfirmed, runtimeHash, verifiedAt, ContentApplier.ReloadDetail.EMPTY);
        }

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

    /**
     * Le texte du fichier présent sur DEV, pour permettre une comparaison lisible (issue #47).
     *
     * <p>Lecture seule, et <strong>bornée</strong> : au-delà de {@link #MAX_READ_CHARS}, on refuse
     * plutôt que de faire voyager un fichier démesuré dans un résultat d'action. L'empreinte reste
     * de toute façon comparable.</p>
     */
    public Optional<String> readDev(PublishKind kind, String slug) {
        if (kind == null || !PublishKind.validSlug(slug)) {
            return Optional.empty();
        }
        return store.read(kind, slug).filter(text -> text.length() <= MAX_READ_CHARS);
    }

    /** Borne de lecture, alignée sur celle de la charge utile de publication. */
    public static final int MAX_READ_CHARS = 256 * 1024;

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
        if (RetiredContent.isRetired(kind, slug)) {
            // Issue #798 : un contenu retiré pour de bon ne revient jamais, quel que soit l'émetteur.
            return refused("RETIRED", RetiredContent.refusal(slug), kind, slug, now);
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

            // Issue #731 : le texte d'origine, pour remettre DEV en l'état si le moteur refuse.
            String original = created ? null : store.read(kind, slug).orElse(null);

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

            PublishOutcome outcome = reloadAndVerify(kind, slug, id, before, after, sourceSha, created,
                    backupPath, "PUBLISHED");
            if ("RELOAD_FAILED".equals(outcome.code())) {
                if (!created && original == null) {
                    original = store.readBackup(backupPath).orElse(null);
                }
                return putBack(outcome, created, original);
            }
            return outcome;
        } finally {
            inFlight.remove(lockKey);
        }
    }

    // ---- Publication d'un lot (issue #427) --------------------------------------------------

    /** Au-delà, un lot est refusé : un pack d'histoire tient très largement dedans. */
    public static final int MAX_BATCH_ITEMS = 50;

    /**
     * Une ressource d'un lot.
     *
     * @param expectedDevSha empreinte DEV que l'appelant croyait voir ({@code ""} = absente) ;
     *                       jamais {@code null} dans un lot — on ne publie pas un pack à l'aveugle
     */
    public record BatchItem(PublishKind kind, String slug, String yaml, String expectedDevSha,
                            String expectedId) {
    }

    /**
     * Le sort d'une ressource du lot.
     *
     * @param status {@code CREATED}, {@code UPDATED}, {@code UNCHANGED} (fichier identique),
     *               {@code CONFLICT}, {@code INVALID}, {@code REJECTED_BY_ENGINE},
     *               {@code NOT_LOADED}, {@code RESTORED} (écrite puis remise en l'état),
     *               {@code CHECKED} (vérification seule)
     * @param problems ce que le moteur reproche à <strong>cette</strong> ressource, tel quel
     */
    public record BatchItemOutcome(PublishKind kind, String slug, String expectedId, String status,
                                   boolean created, String devShaBefore, String devShaAfter,
                                   String sourceSha, String backupPath, boolean runtimeConfirmed,
                                   List<String> problems) {
        public BatchItemOutcome {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /**
     * Le compte rendu d'un lot.
     *
     * @param code         {@code PUBLISHED}, {@code ALREADY_PUBLISHED}, {@code CHECKED},
     *                     {@code REFUSED}, {@code CONFLICT}, {@code BUSY}, {@code BACKUP_FAILED},
     *                     {@code WRITE_FAILED}, {@code INVALID_CONTENT}, {@code BROKEN_REFERENCES},
     *                     {@code RUNTIME_MISSING}, {@code ROLLBACK_INCOMPLETE}
     * @param dryRun       vérification seule : rien n'est resté écrit, rien n'a été rechargé
     * @param filesRestored vrai si des fichiers écrits ont été remis dans leur état d'avant
     * @param otherProblems erreurs du moteur qui ne concernent <strong>aucune</strong> ressource du
     *                     lot (un autre fichier déjà présent sur DEV, une référence cassée…) : elles
     *                     bloquent quand même, et le dire évite de chercher dans le pack
     */
    public record BatchOutcome(boolean ok, String code, String message, boolean dryRun,
                               boolean filesRestored, List<BatchItemOutcome> items,
                               List<String> otherProblems, List<String> families,
                               List<String> autoIncludedFamilies, String reloadCode,
                               int loadedCount, String runtimeHash, Instant verifiedAt) {
        public BatchOutcome {
            items = items == null ? List.of() : List.copyOf(items);
            otherProblems = otherProblems == null ? List.of() : List.copyOf(otherProblems);
            families = families == null ? List.of() : List.copyOf(families);
            autoIncludedFamilies = autoIncludedFamilies == null ? List.of()
                    : List.copyOf(autoIncludedFamilies);
        }

        /** Vrai si le moteur voit maintenant chaque ressource du lot. */
        public boolean synchronized_() {
            return ok && !dryRun && items.stream().allMatch(BatchItemOutcome::runtimeConfirmed);
        }
    }

    /** Une ressource en cours de traitement : ce qu'il faut pour la remettre en l'état. */
    private static final class Staged {
        final BatchItem item;
        final String id;
        final String sourceSha;
        final String before;
        /** Le texte DEV d'avant, en mémoire : c'est lui qu'on repose en cas d'échec. */
        String original;
        String backupPath = "";
        String after;
        boolean written;

        Staged(BatchItem item, String id, String sourceSha, String before) {
            this.item = item;
            this.id = id;
            this.sourceSha = sourceSha;
            this.before = before;
            this.after = before;
        }

        boolean changes() {
            return !before.equals(sourceSha);
        }

        String key() {
            return item.kind().directory() + "/" + item.slug();
        }
    }

    /**
     * Publie un lot de ressources <strong>comme une seule opération</strong> (issue #427).
     *
     * <p>Le défaut corrigé : un pack publié ressource par ressource fait valider au moteur des états
     * intermédiaires. Une quête dont le prérequis n'est pas encore sur DEV est rejetée
     * ({@code INVALID_CONTENT}), et le résultat dépend de l'ordre d'envoi — c'est exactement ce qui
     * est arrivé au pack Lily. Ici, toutes les ressources sont écrites, puis
     * <strong>un</strong> rechargement de toutes les familles concernées (et de celles dont elles
     * dépendent, #371) valide l'ensemble complet. L'ordre n'a donc plus d'importance.</p>
     *
     * <h2>Tout ou rien</h2>
     *
     * <ul>
     *   <li>contrôles, conflits et verrous <strong>avant</strong> la première écriture : un refus à
     *       ce stade n'a rien touché ;</li>
     *   <li>si le moteur refuse l'ensemble, chaque fichier écrit est remis dans son état d'avant
     *       (texte d'origine, ou retrait s'il était nouveau) — et le runtime, que le refus n'a pas
     *       touché, reste celui d'avant ;</li>
     *   <li>si une ressource manque au runtime après rechargement, même retour arrière, suivi d'un
     *       rechargement qui rétablit l'ensemble d'avant ;</li>
     *   <li>si un retour arrière échoue, on le <strong>dit</strong>, fichier par fichier, avec la
     *       sauvegarde : un état à moitié publié n'est jamais présenté comme final.</li>
     * </ul>
     *
     * @param dryRun vérification seule : les fichiers sont écrits, le moteur les juge sans rien
     *               appliquer, puis ils sont remis en l'état — sans sauvegarde sur disque, puisque
     *               rien ne doit en rester
     */
    public BatchOutcome publishBatch(List<BatchItem> items, boolean dryRun) {
        Instant now = clock.instant();
        if (items == null || items.isEmpty()) {
            return batchRefused("REFUSED", "Aucune ressource dans le lot : rien à publier.",
                    List.of(), dryRun, now);
        }
        if (items.size() > MAX_BATCH_ITEMS) {
            return batchRefused("REFUSED", "Lot trop grand (" + items.size() + " ressources, "
                    + MAX_BATCH_ITEMS + " au plus).", List.of(), dryRun, now);
        }

        // 1. Contrôles statiques : rien n'est lu ni écrit tant que le lot entier n'est pas sain.
        List<BatchItemOutcome> invalid = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (BatchItem item : items) {
            String problem = staticProblem(item);
            if (problem == null && !seen.add(item.kind().directory() + "/" + item.slug())) {
                problem = "présente deux fois dans le lot.";
            }
            if (problem != null) {
                invalid.add(itemOutcome(item, "INVALID", false, "", "", "", "", false,
                        List.of(problem)));
            }
        }
        if (!invalid.isEmpty()) {
            return batchRefused("REFUSED", invalid.size()
                    + " ressource(s) invalide(s) dans le lot : rien n'a été publié.", invalid,
                    dryRun, now);
        }

        // 2. Verrous de TOUTES les ressources, ou d'aucune.
        List<String> locked = new java.util.ArrayList<>();
        for (BatchItem item : items) {
            String key = item.kind().directory() + "/" + item.slug();
            if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
                locked.forEach(inFlight::remove);
                return batchRefused("BUSY", "Une publication de « " + key
                        + " » est déjà en cours : réessayer dans un instant.", List.of(), dryRun, now);
            }
            locked.add(key);
        }
        try {
            return publishLocked(items, dryRun, now);
        } finally {
            locked.forEach(inFlight::remove);
        }
    }

    private BatchOutcome publishLocked(List<BatchItem> items, boolean dryRun, Instant now) {
        // 3. Conflits : l'état DEV doit être celui que l'appelant a vu, pour CHAQUE ressource.
        List<Staged> staged = new java.util.ArrayList<>();
        List<BatchItemOutcome> conflicts = new java.util.ArrayList<>();
        for (BatchItem item : items) {
            String before = store.sha256(item.kind(), item.slug());
            String id = item.expectedId() == null || item.expectedId().isBlank()
                    ? item.slug() : item.expectedId();
            Staged one = new Staged(item, id, ContentPublishStore.sha256Of(item.yaml()), before);
            staged.add(one);
            if (!item.expectedDevSha().equals(before)) {
                conflicts.add(itemOutcome(item, "CONFLICT", before.isEmpty(), before, before,
                        one.sourceSha, "", applier.runtimeHas(item.kind().family(), id),
                        List.of(item.expectedDevSha().isEmpty()
                                ? "créée sur DEV depuis votre analyse."
                                : before.isEmpty() ? "disparue de DEV depuis votre analyse."
                                        : "modifiée sur DEV depuis votre analyse.")));
            }
        }
        if (!conflicts.isEmpty()) {
            return batchRefused("CONFLICT", conflicts.size() + " ressource(s) ont changé sur DEV"
                    + " depuis votre analyse : rien n'a été publié, pour ne rien écraser en"
                    + " silence. Relancer l'analyse.", conflicts, dryRun, now);
        }

        Set<com.lodygames.rpgquest.content.reload.ReloadFamily> families =
                new java.util.LinkedHashSet<>();
        staged.forEach(one -> families.add(one.item.kind().family()));
        boolean anyChange = staged.stream().anyMatch(Staged::changes);
        if (!anyChange && staged.stream()
                .allMatch(one -> applier.runtimeHas(one.item.kind().family(), one.id))) {
            return new BatchOutcome(true, dryRun ? "CHECKED" : "ALREADY_PUBLISHED",
                    "Les " + staged.size() + " ressource(s) sont déjà identiques sur DEV et chargées"
                            + " par le moteur : rien à faire.",
                    dryRun, false, outcomes(staged, "UNCHANGED", true, Map.of()), List.of(),
                    wires(families), List.of(), "ALREADY_LOADED", 0, applier.runtimeHash(), now);
        }

        // 4. Sauvegardes (publication réelle), textes d'origine en mémoire (toujours).
        String stamp = ContentPublishStore.stamp(now);
        for (Staged one : staged) {
            if (!one.changes() || one.before.isEmpty()) {
                continue;
            }
            one.original = store.read(one.item.kind(), one.item.slug()).orElse(null);
            if (one.original == null) {
                return batchRefused("BACKUP_FAILED", "Lecture du fichier DEV « " + one.key()
                        + " » impossible : rien n'a été publié.", List.of(), dryRun, now);
            }
            if (dryRun) {
                continue;
            }
            try {
                one.backupPath = store.backup(one.item.kind(), one.item.slug(), stamp)
                        .map(ContentPublishStore.Backup::relativePath).orElse("");
            } catch (IOException error) {
                return batchRefused("BACKUP_FAILED", "Sauvegarde de « " + one.key()
                        + " » impossible, donc rien n'a été publié : " + describe(error), List.of(),
                        dryRun, now);
            }
        }

        // 5. Écritures. Un échec remet en l'état tout ce qui a déjà été écrit.
        for (Staged one : staged) {
            if (!one.changes()) {
                continue;
            }
            try {
                one.after = store.write(one.item.kind(), one.item.slug(), one.item.yaml());
                one.written = true;
            } catch (IOException error) {
                List<String> leftovers = restore(staged);
                return finish(false, "WRITE_FAILED", "Écriture de « " + one.key()
                        + " » impossible sur DEV : " + describe(error) + ". " + restoredSentence(leftovers),
                        staged, dryRun, leftovers, null, now);
            }
        }

        // 6. UN verdict du moteur sur l'ensemble complet.
        ContentApplier.ApplyResult reload = dryRun ? applier.check(families) : applier.reload(families);
        boolean accepted = dryRun ? isCleanPreview(reload) : reload.applied();
        if (!accepted) {
            List<String> leftovers = restore(staged);
            String code = reload.code() == null || reload.code().isBlank() ? "RELOAD_FAILED"
                    : reload.code();
            return finish(false, code, (dryRun ? "Vérification" : "Publication")
                            + " refusée par le moteur (" + code + ") : rien n'a été mis en jeu, le"
                            + " contenu en jeu est inchangé. " + restoredSentence(leftovers),
                    staged, dryRun, leftovers, reload, now);
        }
        if (dryRun) {
            List<String> leftovers = restore(staged);
            boolean clean = leftovers.isEmpty();
            return finish(clean, clean ? "CHECKED" : "ROLLBACK_INCOMPLETE",
                    clean ? "Vérification réussie : les " + staged.size() + " ressource(s) seraient"
                            + " acceptées ensemble par le moteur. Rien n'a été mis en jeu."
                            : "Vérification réussie, mais " + restoredSentence(leftovers),
                    staged, true, leftovers, reload, now);
        }

        // 7. Preuve : chaque identifiant attendu est-il réellement chargé ?
        List<Staged> missing = staged.stream()
                .filter(one -> !applier.runtimeHas(one.item.kind().family(), one.id)).toList();
        if (!missing.isEmpty()) {
            List<String> leftovers = restore(staged);
            ContentApplier.ApplyResult back = applier.reload(families);
            String names = String.join(", ", missing.stream().map(one -> one.id).toList());
            return finish(false, "RUNTIME_MISSING", "Rechargé, mais le moteur ne voit pas : " + names
                            + ". Vérifier que l'identifiant déclaré dans chaque fichier correspond à"
                            + " son nom. " + restoredSentence(leftovers)
                            + (back.applied() ? " Contenu d'avant rechargé."
                                    : " ATTENTION : le rechargement de retour a échoué ("
                                            + back.code() + ")."),
                    staged, false, leftovers, reload, now);
        }
        return finish(true, "PUBLISHED", "Les " + staged.size() + " ressource(s) sont en jeu sur"
                        + " DEV, confirmées une à une par le moteur (" + reload.loaded()
                        + " élément(s) chargés).",
                staged, false, List.of(), reload, now);
    }

    /** Un aperçu propre : pas une erreur de contenu, pas une référence cassée. */
    private static boolean isCleanPreview(ContentApplier.ApplyResult result) {
        return "PREVIEW_OK".equals(result.code());
    }

    /**
     * Remet chaque fichier écrit dans son état d'avant : texte d'origine, ou retrait s'il était
     * nouveau.
     *
     * @return les ressources qu'on n'a pas pu remettre en l'état, avec leur sauvegarde
     */
    private List<String> restore(List<Staged> staged) {
        List<String> leftovers = new java.util.ArrayList<>();
        for (Staged one : staged) {
            if (!one.written) {
                continue;
            }
            try {
                if (one.original == null) {
                    store.delete(one.item.kind(), one.item.slug());
                } else {
                    store.write(one.item.kind(), one.item.slug(), one.original);
                }
                one.written = false;
                one.after = store.sha256(one.item.kind(), one.item.slug());
            } catch (IOException error) {
                leftovers.add(one.key() + (one.backupPath.isEmpty() ? ""
                        : " (sauvegarde « " + one.backupPath + " »)") + " : " + describe(error));
            }
        }
        return leftovers;
    }

    private static String restoredSentence(List<String> leftovers) {
        return leftovers.isEmpty()
                ? "Les fichiers DEV sont remis dans leur état d'avant."
                : "ATTENTION : " + leftovers.size() + " fichier(s) n'ont pas pu être remis en l'état"
                        + " — " + String.join(" ; ", leftovers) + ".";
    }

    private BatchOutcome finish(boolean ok, String code, String message, List<Staged> staged,
                                boolean dryRun, List<String> leftovers,
                                ContentApplier.ApplyResult reload, Instant now) {
        boolean anyWritten = staged.stream().anyMatch(Staged::changes);
        boolean restored = !ok || dryRun;
        String finalCode = !leftovers.isEmpty() ? "ROLLBACK_INCOMPLETE" : code;
        ContentApplier.ReloadDetail detail = reload == null ? ContentApplier.ReloadDetail.EMPTY
                : reload.detail();
        Map<String, List<String>> problems = new java.util.LinkedHashMap<>();
        List<String> others = new java.util.ArrayList<>(detail.referenceErrors());
        for (String issue : detail.contentIssues()) {
            Staged owner = staged.stream().filter(one -> issue.startsWith(
                    one.item.kind().directory() + "/" + one.item.kind().fileName(one.item.slug())))
                    .findFirst().orElse(null);
            if (owner == null) {
                others.add(issue);
            } else {
                problems.computeIfAbsent(owner.key(), k -> new java.util.ArrayList<>()).add(issue);
            }
        }
        List<BatchItemOutcome> outcomes = new java.util.ArrayList<>();
        for (Staged one : staged) {
            List<String> mine = problems.getOrDefault(one.key(), List.of());
            boolean loaded = applier.runtimeHas(one.item.kind().family(), one.id);
            String status;
            if (ok && !dryRun) {
                status = !one.changes() ? "UNCHANGED" : one.before.isEmpty() ? "CREATED" : "UPDATED";
            } else if (!mine.isEmpty()) {
                status = "REJECTED_BY_ENGINE";
            } else if (ok) {
                status = "CHECKED";
            } else if ("RUNTIME_MISSING".equals(code) && !loaded) {
                status = "NOT_LOADED";
            } else {
                status = one.changes() && anyWritten && restored ? "RESTORED" : "UNCHANGED";
            }
            outcomes.add(itemOutcome(one.item, status, one.before.isEmpty(), one.before,
                    one.written ? one.after : store.sha256(one.item.kind(), one.item.slug()),
                    one.sourceSha, one.backupPath, ok && !dryRun && loaded, mine));
        }
        return new BatchOutcome(ok && leftovers.isEmpty(), finalCode, message, dryRun,
                restored && anyWritten && leftovers.isEmpty(), outcomes, others,
                detail.families(), detail.autoIncludedFamilies(),
                reload == null ? "" : reload.code(), reload == null ? 0 : reload.loaded(),
                applier.runtimeHash(), clock.instant());
    }

    private List<BatchItemOutcome> outcomes(List<Staged> staged, String status, boolean confirmed,
                                            Map<String, List<String>> problems) {
        return staged.stream().map(one -> itemOutcome(one.item, status, false, one.before,
                one.before, one.sourceSha, "", confirmed,
                problems.getOrDefault(one.key(), List.of()))).toList();
    }

    private static BatchItemOutcome itemOutcome(BatchItem item, String status, boolean created,
                                                String before, String after, String sourceSha,
                                                String backupPath, boolean confirmed,
                                                List<String> problems) {
        String id = item == null || item.expectedId() == null || item.expectedId().isBlank()
                ? (item == null ? "" : item.slug()) : item.expectedId();
        return new BatchItemOutcome(item == null ? null : item.kind(), item == null ? "" : item.slug(),
                id, status, created, before, after, sourceSha, backupPath, confirmed, problems);
    }

    /** Le motif de refus d'une ressource avant toute lecture du disque, ou {@code null}. */
    private String staticProblem(BatchItem item) {
        if (item == null || item.kind() == null) {
            return "famille de contenu inconnue.";
        }
        if (!PublishKind.validSlug(item.slug()) || store.resolve(item.kind(), item.slug()).isEmpty()) {
            return "identifiant de ressource invalide.";
        }
        if (item.yaml() == null || item.yaml().isBlank()) {
            return "contenu vide.";
        }
        if (item.expectedDevSha() == null) {
            return "empreinte DEV attendue manquante : relancer l'analyse.";
        }
        if (RetiredContent.isRetired(item.kind(), item.slug())) {
            return "retirée définitivement du jeu (#778) : publication refusée.";
        }
        return null;
    }

    private BatchOutcome batchRefused(String code, String message, List<BatchItemOutcome> items,
                                      boolean dryRun, Instant now) {
        return new BatchOutcome(false, code, message, dryRun, false, items, List.of(), List.of(),
                List.of(), "", 0, applier.runtimeHash(), now);
    }

    private static List<String> wires(Set<com.lodygames.rpgquest.content.reload.ReloadFamily> families) {
        return families.stream().map(com.lodygames.rpgquest.content.reload.ReloadFamily::wire).toList();
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
                    reload.runtimeHash(), clock.instant(), reload.detail());
        } finally {
            inFlight.remove(lockKey);
        }
    }

    /**
     * Le moteur a refusé le fichier qu'on vient d'écrire : rien n'a été mis en jeu (issue #731).
     *
     * <p>On remet DEV dans son état d'avant — texte d'origine, ou retrait d'une ressource
     * nouvelle — comme le fait déjà un lot (#427). Sans cela, DEV gardait une version que le jeu
     * n'avait pas acceptée, d'empreinte inconnue du panel : la correction suivante, faite dans le
     * même éditeur, était refusée pour conflit. Le rechargement n'ayant rien appliqué, le contenu
     * en jeu n'a pas changé ; on ne recharge donc pas une seconde fois.</p>
     */
    private PublishOutcome putBack(PublishOutcome failed, boolean created, String original) {
        PublishKind kind = failed.kind();
        String slug = failed.slug();
        String note;
        try {
            if (created) {
                store.delete(kind, slug);
            } else if (original != null) {
                store.write(kind, slug, original);
            } else {
                throw new IOException("texte d'origine illisible");
            }
            note = " Rien n'a été mis en jeu : le fichier DEV est remis dans son état d'avant"
                    + (created ? " (retiré, il n'existait pas)." : ".");
        } catch (IOException error) {
            note = " ATTENTION : le fichier DEV n'a pas pu être remis dans son état d'avant ("
                    + describe(error) + ")"
                    + (failed.backupPath().isEmpty() ? "." : " ; sauvegarde « " + failed.backupPath() + " ».");
        }
        return new PublishOutcome(false, "RELOAD_FAILED",
                "Le serveur a refusé ce contenu au rechargement (" + failed.reloadCode() + ") : "
                        + failed.reloadMessage() + culprits(failed.reloadDetail()) + note,
                kind, slug, failed.expectedId(), failed.devShaBefore(), store.sha256(kind, slug),
                failed.sourceSha(), failed.created(), failed.backupPath(),
                false, failed.reloadCode(), failed.reloadMessage(), failed.loadedCount(),
                failed.issueCount(), false, failed.runtimeHash(), failed.verifiedAt(),
                failed.reloadDetail());
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
                            + culprits(reload.detail())
                            + (backupPath.isEmpty() ? "" : " Sauvegarde : « " + backupPath + " »."),
                    kind, slug, id, before, after, sourceSha, created, backupPath,
                    false, reload.code(), reload.message(),
                    reload.loaded(), reload.issues(), false, reload.runtimeHash(),
                    clock.instant(), reload.detail());
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
                    clock.instant(), reload.detail());
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
                clock.instant(), reload.detail());
    }

    private PublishOutcome refused(String code, String message, PublishKind kind, String slug,
                                   Instant now) {
        return new PublishOutcome(false, code, message, kind, slug, slug, "", "", "", false, "",
                false, "", "", 0, 0, false, applier.runtimeHash(), now);
    }

    /**
     * Les références fautives, nommées dans le message lui-même (#371) — bornées à trois. Les
     * erreurs du validateur sont déjà dans le message du rechargement (#427).
     */
    private static String culprits(ContentApplier.ReloadDetail detail) {
        List<String> errors = detail.referenceErrors();
        if (errors.isEmpty()) {
            return "";
        }
        String shown = String.join(" ", errors.subList(0, Math.min(3, errors.size())));
        return " " + shown + (errors.size() > 3 ? " (+" + (errors.size() - 3) + " autre(s))" : "");
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
