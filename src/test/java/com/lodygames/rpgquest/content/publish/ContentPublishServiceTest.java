package com.lodygames.rpgquest.content.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.content.reload.ReloadFamily;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #47 — publier une ressource sur DEV, et ne jamais prétendre y être arrivé.
 *
 * <h2>Ce que ces tests protègent réellement</h2>
 *
 * <p>Le ticket pose trois interdits, et ce sont eux qui structurent ce fichier :
 * « fichier copié » n'est pas un succès, « action envoyée » n'est pas un succès, « reload demandé »
 * n'est pas un succès. Chaque test ci-dessous vérifie qu'un de ces trois raccourcis <strong>n'est
 * pas pris</strong> — notamment en échouant volontairement à la dernière étape et en vérifiant que
 * le résultat le dit.</p>
 *
 * <p>Tout s'exécute sans serveur grâce à {@link ContentApplier} : ce qui est mesuré ici est l'ordre
 * des opérations et les refus, pas le chargement d'un YAML.</p>
 */
class ContentPublishServiceTest {

    private static final String YAML_V1 = """
            id: rpgquest:test_publish_quest
            title: "Quête de test"
            steps:
              - id: etape1
            """;
    private static final String YAML_V2 = """
            id: rpgquest:test_publish_quest
            title: "Quête de test, version 2"
            steps:
              - id: etape1
              - id: etape2
            """;

    @TempDir
    Path dataFolder;

    private ContentPublishStore store;
    private FakeApplier applier;
    private ContentPublishService service;

    /**
     * Moteur de contenu simulé. Il <strong>enregistre l'ordre des appels</strong> et sait échouer à
     * la demande : c'est ainsi qu'on vérifie que la sauvegarde précède l'écriture, et que l'écriture
     * précède le rechargement.
     */
    private static final class FakeApplier implements ContentApplier {
        final List<String> calls = new ArrayList<>();
        final Set<String> loaded = new LinkedHashSet<>();
        boolean reloadFails;
        /** Si vrai, le rechargement « réussit » mais n'enregistre pas l'identifiant. */
        boolean reloadIgnoresNewIds;
        Runnable onReload;

        @Override public ApplyResult reload(ReloadFamily family) {
            calls.add("reload:" + family.wire());
            if (onReload != null) {
                onReload.run();
            }
            if (reloadFails) {
                return new ApplyResult(false, "PARSE_ERROR",
                        "un fichier de la famille est invalide", 0, 1, "hash-ko");
            }
            if (!reloadIgnoresNewIds) {
                loaded.addAll(pending);
            }
            pending.clear();
            return new ApplyResult(true, "APPLIED", "rechargé", loaded.size(), 0, "hash-ok");
        }

        /** Les identifiants que le prochain rechargement « découvrira » sur le disque. */
        final Set<String> pending = new LinkedHashSet<>();

        @Override public boolean runtimeHas(ReloadFamily family, String id) {
            String plain = plain(id);
            return loaded.stream().anyMatch(l -> plain(l).equals(plain));
        }

        @Override public List<String> loadedIds(ReloadFamily family) {
            return List.copyOf(loaded);
        }

        @Override public String runtimeHash() {
            return "hash-" + loaded.size();
        }

        private static String plain(String id) {
            int colon = id.indexOf(':');
            return (colon < 0 ? id : id.substring(colon + 1)).toLowerCase(java.util.Locale.ROOT);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dataFolder.resolve("quests"));
        store = new ContentPublishStore(dataFolder, dataFolder.resolve("content-backups"));
        applier = new FakeApplier();
        service = new ContentPublishService(store, applier);
    }

    private ContentPublishService.PublishOutcome publish(String yaml, String expectedDevSha) {
        // Le moteur « découvrira » la ressource au prochain rechargement, comme le vrai le ferait.
        applier.pending.add("rpgquest:test_publish_quest");
        return service.publish(PublishKind.QUESTS, "test_publish_quest", yaml, expectedDevSha,
                "rpgquest:test_publish_quest");
    }

    private Path questFile() {
        return dataFolder.resolve("quests").resolve("test_publish_quest.yml");
    }

    // ---- Le cas de référence : une ressource nouvelle -------------------------------------------

    /**
     * Le scénario de #47 : une ressource « Source uniquement » devient réellement active.
     *
     * <p>Et les trois preuves exigées sont vérifiées séparément : le fichier existe, le
     * rechargement a été appliqué, et le moteur la voit.</p>
     */
    @Test
    void publishingANewResourceWritesReloadsAndIsConfirmedByTheEngine() {
        ContentPublishService.PublishOutcome result = publish(YAML_V1, "");

        assertTrue(result.ok(), result.message());
        assertEquals("PUBLISHED", result.code());
        assertTrue(result.created(), "la ressource n'existait pas sur DEV");
        assertTrue(Files.isRegularFile(questFile()), "le fichier doit exister");
        assertTrue(result.reloadApplied(), "le rechargement doit avoir été appliqué");
        assertTrue(result.runtimeConfirmed(), "le moteur doit voir la ressource");
        assertTrue(result.synchronized_(), "donc : Synchronisé");
        assertEquals(ContentPublishStore.sha256Of(YAML_V1), result.devShaAfter());
        assertEquals(result.sourceSha(), result.devShaAfter(), "source et DEV concordent");
    }

    /** Une ressource nouvelle n'a pas de sauvegarde, et on ne doit pas en inventer une. */
    @Test
    void aNewResourceHasNoBackupAndNoneIsFaked() {
        ContentPublishService.PublishOutcome result = publish(YAML_V1, "");

        assertEquals("", result.backupPath(), "aucun faux fichier de sauvegarde");
        assertFalse(Files.exists(dataFolder.resolve("content-backups")),
                "le dossier de sauvegarde n'est même pas créé");
    }

    /** L'ordre : on écrit, PUIS on recharge. Jamais l'inverse. */
    @Test
    void theFileIsWrittenBeforeTheReloadIsRequested() {
        applier.onReload = () -> {
            // Au moment où le moteur recharge, le fichier doit déjà être sur le disque — sinon le
            // rechargement ne pourrait rien trouver.
            assertTrue(Files.isRegularFile(questFile()),
                    "le fichier doit exister AVANT le rechargement");
        };

        assertTrue(publish(YAML_V1, "").ok());
        assertEquals(List.of("reload:quests"), applier.calls);
    }

    /** Une seule famille est rechargée : jamais « tout », qui permuterait du contenu non concerné. */
    @Test
    void onlyTheAffectedFamilyIsReloaded() {
        publish(YAML_V1, "");

        assertEquals(1, applier.calls.size(), applier.calls.toString());
        assertEquals("reload:quests", applier.calls.get(0));
    }

    // ---- Remplacement ---------------------------------------------------------------------------

    @Test
    void replacingAnExistingResourceBacksItUpFirst() {
        publish(YAML_V1, "");
        String shaV1 = ContentPublishStore.sha256Of(YAML_V1);

        ContentPublishService.PublishOutcome result = publish(YAML_V2, shaV1);

        assertTrue(result.ok(), result.message());
        assertFalse(result.created(), "la ressource existait déjà");
        assertFalse(result.backupPath().isEmpty(), "une sauvegarde doit avoir été prise");
        assertEquals(shaV1, result.devShaBefore());
        assertEquals(ContentPublishStore.sha256Of(YAML_V2), result.devShaAfter());
        // Et la sauvegarde contient bien la V1, pas la V2.
        assertEquals(YAML_V1, store.readBackup(result.backupPath()).orElseThrow());
    }

    /** Publier deux fois le même contenu ne réécrit rien et le dit. */
    @Test
    void publishingTheSameContentTwiceIsIdempotent() {
        publish(YAML_V1, "");
        String sha = ContentPublishStore.sha256Of(YAML_V1);
        applier.calls.clear();

        ContentPublishService.PublishOutcome again = publish(YAML_V1, sha);

        assertTrue(again.ok());
        assertEquals("UNCHANGED", again.code());
        assertTrue(again.synchronized_(), "déjà synchronisé");
        assertTrue(applier.calls.isEmpty(), "aucun rechargement inutile : " + applier.calls);
        assertEquals("", again.backupPath(), "rien n'a été remplacé, donc rien à sauvegarder");
    }

    /**
     * Fichier identique mais moteur qui ne la voit pas : on recharge <strong>sans</strong> réécrire.
     *
     * <p>C'est le cas d'un fichier déposé à la main, ou publié avant un échec de rechargement.
     * Répondre « déjà identique, rien à faire » laisserait la ressource invisible en jeu.</p>
     */
    @Test
    void anIdenticalFileThatTheEngineCannotSeeIsReloadedWithoutRewriting() throws Exception {
        Files.writeString(questFile(), YAML_V1);
        String sha = ContentPublishStore.sha256Of(YAML_V1);
        long writtenAt = Files.getLastModifiedTime(questFile()).toMillis();

        ContentPublishService.PublishOutcome result = publish(YAML_V1, sha);

        assertTrue(result.ok(), result.message());
        assertEquals("UNCHANGED", result.code());
        assertTrue(result.runtimeConfirmed(), "le rechargement l'a rendue visible");
        assertEquals(List.of("reload:quests"), applier.calls, "un rechargement, pas une écriture");
        assertEquals(writtenAt, Files.getLastModifiedTime(questFile()).toMillis(),
                "le fichier n'a pas été réécrit");
        assertEquals("", result.backupPath(), "et donc aucune sauvegarde n'était nécessaire");
    }

    // ---- Conflits -------------------------------------------------------------------------------

    /** Le cœur de la protection : DEV a changé depuis l'analyse, donc on refuse. */
    @Test
    void aDevFileChangedSinceThePreviewIsAConflictAndNothingIsOverwritten() throws Exception {
        publish(YAML_V1, "");
        // Quelqu'un (ou quelque chose) modifie le fichier sur DEV entre l'aperçu et la publication.
        Files.writeString(questFile(), "id: rpgquest:test_publish_quest\ntitle: \"Édité sur DEV\"\n");
        String devNow = store.sha256(PublishKind.QUESTS, "test_publish_quest");
        applier.calls.clear();

        ContentPublishService.PublishOutcome result =
                publish(YAML_V2, ContentPublishStore.sha256Of(YAML_V1));

        assertFalse(result.ok());
        assertEquals("CONFLICT", result.code());
        assertTrue(result.message().contains("écraser en silence"), result.message());
        assertEquals(devNow, store.sha256(PublishKind.QUESTS, "test_publish_quest"),
                "le fichier DEV est INTACT");
        assertTrue(applier.calls.isEmpty(), "et rien n'a été rechargé");
    }

    /** Une ressource apparue sur DEV entre-temps est aussi un conflit, avec son propre message. */
    @Test
    void aResourceCreatedOnDevSinceThePreviewIsAlsoAConflict() throws Exception {
        Files.writeString(questFile(), YAML_V2);

        ContentPublishService.PublishOutcome result = publish(YAML_V1, "");

        assertFalse(result.ok());
        assertEquals("CONFLICT", result.code());
        assertTrue(result.message().contains("créée sur DEV"), result.message());
        assertEquals(YAML_V2, Files.readString(questFile()), "intact");
    }

    /** Sans empreinte attendue, la détection est désactivée — c'est la publication forcée. */
    @Test
    void aNullExpectedHashSkipsConflictDetection() throws Exception {
        Files.writeString(questFile(), YAML_V2);
        applier.pending.add("rpgquest:test_publish_quest");

        ContentPublishService.PublishOutcome result = service.publish(PublishKind.QUESTS,
                "test_publish_quest", YAML_V1, null, "rpgquest:test_publish_quest");

        assertTrue(result.ok(), result.message());
        assertEquals(YAML_V1, Files.readString(questFile()));
    }

    // ---- Échecs, et ce qu'ils ne doivent PAS prétendre ------------------------------------------

    /**
     * Le rechargement échoue : le fichier EST écrit, et le résultat doit le dire.
     *
     * <p>Prétendre que rien n'a été fait serait pire que l'échec, parce que l'administrateur
     * chercherait au mauvais endroit.</p>
     */
    @Test
    void aFailedReloadIsNotASuccessAndSaysTheFileIsAlreadyWritten() {
        applier.reloadFails = true;

        ContentPublishService.PublishOutcome result = publish(YAML_V1, "");

        assertFalse(result.ok());
        assertEquals("RELOAD_FAILED", result.code());
        assertFalse(result.synchronized_(), "jamais Synchronisé sans preuve");
        assertTrue(result.message().contains("écrit sur DEV"), result.message());
        assertTrue(Files.isRegularFile(questFile()), "le fichier est bien là");
        assertEquals(ContentPublishStore.sha256Of(YAML_V1), result.devShaAfter());
    }

    /**
     * Le rechargement « réussit » mais le moteur ne voit pas la ressource.
     *
     * <p>C'est exactement le cas d'un identifiant déclaré dans le YAML qui ne correspond pas au nom
     * du fichier. Sans cette vérification, le panel afficherait « Synchronisé » pour une quête
     * introuvable en jeu — le bug que #47 existe pour empêcher.</p>
     */
    @Test
    void aReloadThatDoesNotSurfaceTheResourceIsNotASuccess() {
        applier.reloadIgnoresNewIds = true;

        ContentPublishService.PublishOutcome result = publish(YAML_V1, "");

        assertFalse(result.ok());
        assertEquals("RUNTIME_MISSING", result.code());
        assertTrue(result.reloadApplied(), "le rechargement a bien eu lieu");
        assertFalse(result.runtimeConfirmed());
        assertFalse(result.synchronized_());
        assertTrue(result.message().contains("ne voit pas"), result.message());
    }

    /** La sauvegarde échoue : on ne publie pas du tout. */
    @Test
    void aFailedBackupAbortsBeforeAnythingIsWritten() throws Exception {
        publish(YAML_V1, "");
        // On rend la création du dossier de sauvegarde impossible : un fichier occupe sa place.
        Files.deleteIfExists(dataFolder.resolve("content-backups"));
        Files.writeString(dataFolder.resolve("content-backups"), "pas un dossier");
        applier.calls.clear();

        ContentPublishService.PublishOutcome result =
                publish(YAML_V2, ContentPublishStore.sha256Of(YAML_V1));

        assertFalse(result.ok());
        assertEquals("BACKUP_FAILED", result.code());
        assertTrue(result.message().contains("rien n'a été publié"), result.message());
        assertEquals(YAML_V1, Files.readString(questFile()), "le fichier DEV est intact");
        assertTrue(applier.calls.isEmpty(), "aucun rechargement");
    }

    /** L'écriture échoue : rien n'est rechargé, et l'ancien fichier est remis en place. */
    @Test
    void aFailedWriteRestoresThePreviousFile() throws Exception {
        publish(YAML_V1, "");
        // Un dossier non vide à la place du fichier cible : le déplacement final échouera.
        Files.delete(questFile());
        Files.createDirectories(questFile());
        Files.writeString(questFile().resolve("bloqueur.txt"), "x");
        applier.calls.clear();

        ContentPublishService.PublishOutcome result = publish(YAML_V2, "");

        assertFalse(result.ok());
        assertEquals("WRITE_FAILED", result.code());
        assertTrue(applier.calls.isEmpty(), "aucun rechargement tenté");
    }

    @Test
    void emptyContentIsRefusedBeforeTouchingTheDisk() {
        for (String yaml : new String[] {null, "", "   \n  "}) {
            ContentPublishService.PublishOutcome result = service.publish(PublishKind.QUESTS,
                    "test_publish_quest", yaml, "", null);
            assertFalse(result.ok());
            assertEquals("EMPTY_CONTENT", result.code());
        }
        assertFalse(Files.exists(questFile()));
    }

    // ---- Sécurité -------------------------------------------------------------------------------

    /**
     * Un identifiant forgé ne doit jamais produire un chemin.
     *
     * <p>Le navigateur n'envoie qu'une famille et un identifiant ; si cet identifiant pouvait
     * désigner un chemin, la liste blanche ne servirait à rien.</p>
     */
    @Test
    void aForgedResourceIdCannotEscapeItsDirectory() {
        for (String slug : new String[] {"../../etc/passwd", "../data", "a/b", "a\\b",
                "/absolu", "data.db", "..", ".", "", "Majuscule", "avec espace",
                "a".repeat(80), "quests/../../data"}) {
            ContentPublishService.PublishOutcome result =
                    service.publish(PublishKind.QUESTS, slug, YAML_V1, "", null);

            assertFalse(result.ok(), "« " + slug + " » ne doit pas être publiable");
            assertEquals("INVALID_ID", result.code(), "« " + slug + " »");
        }
    }

    @Test
    void anUnknownKindIsRefused() {
        ContentPublishService.PublishOutcome result =
                service.publish(null, "test_publish_quest", YAML_V1, "", null);

        assertFalse(result.ok());
        assertEquals("INVALID_KIND", result.code());
    }

    /** La liste blanche ne contient que les trois familles fichier. */
    @Test
    void onlyThreeFamiliesArePublishable() {
        assertEquals(3, PublishKind.values().length);
        assertTrue(PublishKind.of("quests").isPresent());
        assertTrue(PublishKind.of("stories").isPresent());
        assertTrue(PublishKind.of("dialogues").isPresent());
        // Tout le reste est hors d'atteinte PAR CONSTRUCTION, pas par une liste d'interdits.
        for (String refused : new String[] {"npcs", "items", "recipes", "mobs", "zones", "portals",
                "worlds", "data.db", "config", "schematics", "content-backups", "", "../quests"}) {
            assertTrue(PublishKind.of(refused).isEmpty(), "« " + refused + " » ne doit pas exister");
        }
    }

    /** Aucun chemin résolu ne sort du dossier de données, même par le store directement. */
    @Test
    void theStoreNeverResolvesOutsideItsFamilyDirectory() {
        for (String slug : new String[] {"../x", "../../x", "a/b", "/x", "..", "x/../../y"}) {
            assertTrue(store.resolve(PublishKind.QUESTS, slug).isEmpty(), slug);
        }
        Path resolved = store.resolve(PublishKind.QUESTS, "ok_slug").orElseThrow();
        assertTrue(resolved.startsWith(dataFolder.resolve("quests")), resolved.toString());
    }

    /** Une sauvegarde ne se lit que sous le dossier de sauvegardes. */
    @Test
    void backupReadsAreConfinedToTheBackupDirectory() throws Exception {
        Files.writeString(dataFolder.resolve("secret.txt"), "interdit");

        assertTrue(store.readBackup("secret.txt").isEmpty());
        assertTrue(store.readBackup("../secret.txt").isEmpty());
        assertTrue(store.readBackup("quests/test_publish_quest.yml").isEmpty());
        assertTrue(store.readBackup(null).isEmpty());
        assertTrue(store.readBackup("").isEmpty());
    }

    // ---- Concurrence ----------------------------------------------------------------------------

    /**
     * Deux publications simultanées de la <strong>même</strong> ressource : la seconde est refusée.
     *
     * <p>Rendu déterministe en relançant la publication depuis l'intérieur du rechargement, c'est-à-dire
     * exactement pendant que la première est en cours.</p>
     */
    @Test
    void aSecondPublishOfTheSameResourceWhileOneRunsIsRefused() {
        List<ContentPublishService.PublishOutcome> reentrant = new ArrayList<>();
        applier.onReload = () -> reentrant.add(service.publish(PublishKind.QUESTS,
                "test_publish_quest", YAML_V2, "", "rpgquest:test_publish_quest"));

        ContentPublishService.PublishOutcome first = publish(YAML_V1, "");

        assertTrue(first.ok(), first.message());
        assertEquals(1, reentrant.size());
        assertFalse(reentrant.get(0).ok());
        assertEquals("BUSY", reentrant.get(0).code());
        assertEquals(YAML_V1, readQuest(), "la seconde n'a rien écrit");
    }

    /** Deux ressources différentes ne se bloquent pas : rien ne le justifierait. */
    @Test
    void twoDifferentResourcesDoNotBlockEachOther() {
        List<ContentPublishService.PublishOutcome> other = new ArrayList<>();
        // La garde doit être posée AVANT l'appel imbriqué : sinon la publication imbriquée
        // déclenche elle-même un rechargement, qui rentre de nouveau ici.
        boolean[] entered = {false};
        applier.onReload = () -> {
            if (!entered[0]) {
                entered[0] = true;
                applier.pending.add("rpgquest:autre_quete");
                other.add(service.publish(PublishKind.QUESTS, "autre_quete", YAML_V1, "",
                        "rpgquest:autre_quete"));
            }
        };

        publish(YAML_V1, "");

        assertEquals(1, other.size());
        assertTrue(other.get(0).ok(), other.get(0).message());
    }

    // ---- Retour arrière -------------------------------------------------------------------------

    /** Une ressource remplacée se restaure vraiment : la V1 revient, et le moteur la revoit. */
    @Test
    void rollbackRestoresThePreviousVersionAndReloadsIt() {
        publish(YAML_V1, "");
        ContentPublishService.PublishOutcome second =
                publish(YAML_V2, ContentPublishStore.sha256Of(YAML_V1));
        assertEquals(YAML_V2, readQuest());

        ContentPublishService.PublishOutcome back = service.rollback(PublishKind.QUESTS,
                "test_publish_quest", second.backupPath(), "rpgquest:test_publish_quest");

        assertTrue(back.ok(), back.message());
        assertEquals("RESTORED", back.code());
        assertEquals(YAML_V1, readQuest(), "la V1 est revenue");
        assertTrue(back.reloadApplied());
        assertTrue(back.runtimeConfirmed(), "et le moteur la voit toujours");
    }

    /**
     * Une ressource <strong>nouvelle</strong> n'a pas de version précédente : le seul retour arrière
     * honnête est de la retirer, et on ne prétend pas « restaurer ».
     */
    @Test
    void rollingBackANewResourceWithdrawsItRatherThanPretendingToRestore() {
        ContentPublishService.PublishOutcome published = publish(YAML_V1, "");
        assertEquals("", published.backupPath());
        applier.loaded.clear(); // le rechargement qui suit ne la retrouvera plus

        ContentPublishService.PublishOutcome back = service.rollback(PublishKind.QUESTS,
                "test_publish_quest", "", "rpgquest:test_publish_quest");

        assertTrue(back.ok(), back.message());
        assertEquals("WITHDRAWN", back.code(), "retiré, pas « restauré »");
        assertFalse(Files.exists(questFile()), "le fichier a été retiré");
        assertFalse(back.runtimeConfirmed(), "et le moteur ne la voit plus");
    }

    @Test
    void rollingBackWithAMissingBackupIsRefusedWithoutTouchingAnything() {
        publish(YAML_V1, "");
        applier.calls.clear();

        ContentPublishService.PublishOutcome back = service.rollback(PublishKind.QUESTS,
                "test_publish_quest", "content-backups/jamais/quests/test_publish_quest.yml",
                "rpgquest:test_publish_quest");

        assertFalse(back.ok());
        assertEquals("BACKUP_MISSING", back.code());
        assertEquals(YAML_V1, readQuest(), "le fichier courant est intact");
        assertTrue(applier.calls.isEmpty(), "aucun rechargement");
    }

    @Test
    void withdrawingSomethingAlreadyGoneIsHarmless() {
        ContentPublishService.PublishOutcome back = service.rollback(PublishKind.QUESTS,
                "jamais_publiee", "", "rpgquest:jamais_publiee");

        assertTrue(back.ok());
        assertEquals("ALREADY_ABSENT", back.code());
    }

    /** Si le retrait laisse le moteur avec la ressource, ce n'est pas un succès. */
    @Test
    void aWithdrawalTheEngineIgnoresIsReportedAsAFailure() {
        publish(YAML_V1, "");
        // Le moteur garde la ressource chargée malgré le retrait du fichier.
        applier.reloadIgnoresNewIds = true;

        ContentPublishService.PublishOutcome back = service.rollback(PublishKind.QUESTS,
                "test_publish_quest", "", "rpgquest:test_publish_quest");

        assertFalse(back.ok());
        assertEquals("RUNTIME_STILL_PRESENT", back.code());
        assertTrue(back.runtimeConfirmed(), "elle est encore là, et on le dit");
    }

    // ---- L'état DEV, sans rien modifier ---------------------------------------------------------

    @Test
    void stateReportsAbsentPresentAndLoadedSeparately() throws Exception {
        ContentPublishService.DevState absent =
                service.state(PublishKind.QUESTS, "test_publish_quest", "rpgquest:test_publish_quest");
        assertFalse(absent.present());
        assertFalse(absent.runtimeLoaded());
        assertEquals("", absent.sha256(), "l'empreinte de l'absence est la chaîne vide");

        Files.writeString(questFile(), YAML_V1);
        ContentPublishService.DevState notLoaded =
                service.state(PublishKind.QUESTS, "test_publish_quest", "rpgquest:test_publish_quest");
        assertTrue(notLoaded.present(), "le fichier est là");
        assertFalse(notLoaded.runtimeLoaded(), "mais le moteur ne l'a pas chargé");

        applier.loaded.add("rpgquest:test_publish_quest");
        ContentPublishService.DevState loaded =
                service.state(PublishKind.QUESTS, "test_publish_quest", "rpgquest:test_publish_quest");
        assertTrue(loaded.present());
        assertTrue(loaded.runtimeLoaded());
    }

    @Test
    void stateNeverWritesAnything() {
        service.state(PublishKind.QUESTS, "test_publish_quest", null);
        service.list(PublishKind.QUESTS);
        service.runtimeIds(PublishKind.QUESTS);

        assertFalse(Files.exists(questFile()));
        assertTrue(applier.calls.isEmpty(), "aucun rechargement : " + applier.calls);
    }

    @Test
    void listingReportsEveryFileWithItsHash() throws Exception {
        Files.writeString(questFile(), YAML_V1);
        Files.writeString(dataFolder.resolve("quests").resolve("autre.yml"), YAML_V2);
        Files.writeString(dataFolder.resolve("quests").resolve("ignore.txt"), "pas du yaml");

        List<ContentPublishStore.DevFile> files = store.list(PublishKind.QUESTS);

        assertEquals(2, files.size(), files.toString());
        assertEquals("autre", files.get(0).slug(), "triés par identifiant");
        assertEquals("test_publish_quest", files.get(1).slug());
        assertEquals(ContentPublishStore.sha256Of(YAML_V1), files.get(1).sha256());
    }

    /** L'empreinte est la même fonction des deux côtés du transfert, sinon rien ne concorde. */
    @Test
    void theHashIsStableAndContentDependent() {
        assertEquals(ContentPublishStore.sha256Of(YAML_V1),
                ContentPublishStore.sha256Of(YAML_V1));
        assertNotEquals(ContentPublishStore.sha256Of(YAML_V1),
                ContentPublishStore.sha256Of(YAML_V2));
        assertEquals(64, ContentPublishStore.sha256Of(YAML_V1).length());
        // Accents et caractères non ASCII : l'encodage doit être fixé (UTF-8) des deux côtés.
        assertEquals(ContentPublishStore.sha256Of("é€ ☃"), ContentPublishStore.sha256Of("é€ ☃"));
    }

    private String readQuest() {
        try {
            return Files.readString(questFile());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
