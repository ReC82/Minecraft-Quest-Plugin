package com.lodygames.rpgquest.panel.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyse des conséquences d'une suppression de quête, de story (issue #194) ou de dialogue
 * (issue #248).
 *
 * <p><strong>Trois règles de conduite</strong>, qui expliquent toutes les décisions ci-dessous :</p>
 *
 * <ol>
 *   <li><strong>Jamais de cascade.</strong> Supprimer une quête ne supprime ni PNJ, ni dialogue,
 *       ni story. On <em>retire la référence</em>, on ne détruit pas le contenu qui la portait.</li>
 *   <li><strong>Jamais de lien orphelin.</strong> Si retirer une référence rendrait un contenu
 *       invalide, on <strong>bloque</strong> en disant où et quoi faire, plutôt que de produire un
 *       fichier que le serveur refusera au chargement.</li>
 *   <li><strong>Jamais de progression joueur touchée.</strong> La suppression est
 *       <em>éditoriale</em>. Les lignes de progression existantes restent en base ; elles
 *       deviennent simplement sans objet. Un reset joueur est une opération distincte, qui a sa
 *       propre action et sa propre permission.</li>
 * </ol>
 */
public final class ContentDeletionAnalyzer {

    /**
     * Quêtes livrées comme exemples dans le JAR du plugin. Elles sont <strong>recréées au démarrage
     * du serveur si le fichier manque</strong> ({@code YamlQuestEngine#BUNDLED_EXAMPLES}) : les
     * supprimer de la source ne suffit donc pas à les faire disparaître du serveur tant qu'un
     * nouveau JAR n'a pas été construit et déployé.
     *
     * <p>Cette liste est une copie — le Control Panel ne peut pas dépendre du plugin. Sa
     * synchronisation avec la source du plugin est vérifiée par un test qui lit le fichier Java
     * réel du dépôt, donc une dérive est détectée au lieu d'être subie.</p>
     */
    public static final List<String> BUNDLED_QUESTS = List.of(
            "premiers_pas", "woodcutters_request",
            "guard_tier1", "guard_tier2", "guard_tier3", "guard_tier4", "guard_tier5",
            "kit_tier2", "kit_tier3", "kit_tier4", "kit_tier5",
            "encounter_bandit_spiders", "encounter_merchant_cactus", "encounter_smith_ore",
            "daily_demo_bucheron", "daily_demo_mineur", "daily_demo_chasseur", "weekly_demo_expedition");

    /** Stories livrées comme exemples dans le JAR ({@code StoryRegistry#BUNDLED_EXAMPLES}). */
    public static final List<String> BUNDLED_STORIES = List.of("main_story");

    /**
     * Dialogues livrés comme exemples dans le JAR ({@code YamlDialogueEngine#BUNDLED_EXAMPLES}).
     * Depuis #248, leur suppression côté serveur pose une pierre tombale qui empêche l'amorçage de
     * les recréer — à condition que le JAR déployé contienne #248.
     */
    public static final List<String> BUNDLED_DIALOGUES = List.of("guard", "woodcutter_bob");

    /** Un choix de dialogue qui ouvre un autre dialogue : {@code dialogue: rpgquest:x}. */
    private static final Pattern DIALOGUE_OPEN_REF =
            Pattern.compile("(?m)^\\s*dialogue:\\s*[\"']?(?:rpgquest:)?([a-z0-9][a-z0-9_/-]*)[\"']?\\s*$");

    /**
     * Référence à une quête dans un dialogue : {@code quest: rpgquest:first_steps} ou
     * {@code quest: first_steps}. Le modèle de dialogue du panel est volontairement simplifié et
     * ne porte ni conditions ni actions : la détection se fait donc sur le texte YAML brut. C'est
     * suffisant, parce qu'on <strong>bloque</strong> sur un dialogue au lieu de le réécrire.
     */
    private static final Pattern DIALOGUE_QUEST_REF =
            Pattern.compile("(?m)^\\s*quest:\\s*(?:rpgquest:)?([a-z0-9][a-z0-9_/-]*)\\s*$");

    private final ContentWorkspace workspace;
    private final SourceCatalog source;

    public ContentDeletionAnalyzer(ContentWorkspace workspace, SourceCatalog source) {
        this.workspace = workspace;
        this.source = source;
    }

    /**
     * Calcule le plan de suppression.
     *
     * @param kind           {@code "quests"} ou {@code "stories"}
     * @param slug           nom de fichier sans extension
     * @param runtimePresent le dernier relevé du serveur connaît-il ce contenu
     */
    public DeletionPlan analyze(String kind, String slug, boolean runtimePresent) {
        return analyze(kind, slug, runtimePresent, true);
    }

    /**
     * @param runtimeListingAvailable un relevé du serveur existe-t-il ? Si <strong>non</strong>, « le
     *                        serveur ne connaît pas ce contenu » serait une affirmation fausse :
     *                        on ne lui a jamais demandé. La nuance compte, parce que sans
     *                        suppression côté serveur le contenu réapparaît au rafraîchissement.
     */
    public DeletionPlan analyze(String kind, String slug, boolean runtimePresent,
                                boolean runtimeListingAvailable) {
        return analyze(kind, slug, runtimePresent, runtimeListingAvailable, null, Instant.now());
    }

    /**
     * Variante complète (issue #248) : le contenu <strong>réellement chargé</strong> par le serveur
     * entre dans l'analyse, en plus de la source éditable.
     *
     * @param inventory dialogues du dernier relevé serveur ; {@code null} = non considéré (ancien
     *                  comportement, quêtes et stories seulement)
     * @param now       instant de référence pour juger la fraîcheur du relevé
     */
    public DeletionPlan analyze(String kind, String slug, boolean runtimePresent,
                                boolean runtimeListingAvailable, RuntimeDialogueInventory inventory,
                                Instant now) {
        if ("dialogues".equals(kind)) {
            return analyzeDialogue(slug, inventory == null
                    ? RuntimeDialogueInventory.unavailable() : inventory, now);
        }
        List<DeletionPlan.Edit> edits = new ArrayList<>();
        List<DeletionPlan.Blocker> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        String plainId = QuestYaml.plainId(slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT));
        Optional<ContentWorkspace.ContentFile> file = workspace.read(kind, plainId);
        boolean sourcePresent = file.isPresent();
        String label = plainId;

        if (!"quests".equals(kind) && !"stories".equals(kind)) {
            blockers.add(new DeletionPlan.Blocker(
                    "Les contenus de type « " + kind + " » ne peuvent pas être supprimés ici : "
                            + "seulement les quêtes, les stories et les dialogues.", kind,
                    "Un PNJ se supprime depuis sa propre fiche (« Supprimer le PNJ »)."));
            return plan(kind, plainId, label, false, null, runtimePresent, false,
                    edits, blockers, notes);
        }

        if (!sourcePresent && !runtimePresent) {
            blockers.add(new DeletionPlan.Blocker(
                    "Ce contenu n'existe ni dans la source éditable, ni dans le dernier relevé du "
                            + "serveur : il n'y a rien à supprimer.", plainId,
                    "Rafraîchir le catalogue, puis vérifier l'identifiant."));
            return plan(kind, plainId, label, false, null, runtimePresent, false,
                    edits, blockers, notes);
        }

        if (sourcePresent) {
            if (!workspace.writable(kind)) {
                blockers.add(new DeletionPlan.Blocker(
                        "L'espace de travail source est en lecture seule : le service PlugAdmin n'a "
                                + "pas les droits d'écriture sur « " + kind + "/ ».", kind,
                        "Exécuter scripts/plugadmin/grant-content-access.sh, puis recharger."));
            }
            if (!workspace.backupsConfigured()) {
                blockers.add(new DeletionPlan.Blocker(
                        "Aucun dossier de sauvegarde configuré : une suppression sans sauvegarde "
                                + "préalable est refusée.", "configuration du panel",
                        "Vérifier RPGQUEST_PANEL_DB (les sauvegardes vivent à côté de la base)."));
            }
            label = labelOf(kind, plainId, label);
        } else {
            notes.add("Aucun fichier dans la source éditable : ce contenu n'existe que sur le "
                    + "serveur. Seule la suppression côté serveur sera demandée.");
        }

        boolean bundled = ("quests".equals(kind) ? BUNDLED_QUESTS : BUNDLED_STORIES).contains(plainId);

        if ("quests".equals(kind)) {
            analyzeQuestReferences(plainId, edits, blockers);
            if (inventory != null) {
                analyzeRuntimeQuestReferences(plainId, inventory, now, blockers);
            }
        } else {
            analyzeStoryReferences(plainId, notes);
        }

        addRuntimeAndProgressNotes(kind, plainId, sourcePresent, runtimePresent, runtimeListingAvailable,
                bundled, notes);

        return plan(kind, plainId, label, sourcePresent,
                file.map(ContentWorkspace.ContentFile::sha256).orElse(null),
                runtimePresent, bundled, edits, blockers, notes);
    }

    // ---- Références vers une quête ----------------------------------------------------------

    private void analyzeQuestReferences(String plainId, List<DeletionPlan.Edit> edits,
                                        List<DeletionPlan.Blocker> blockers) {
        // 1. Prérequis d'autres quêtes : un prérequis est facultatif, le retirer laisse la quête
        //    valide. C'est donc le seul cas réellement automatisable sans rien casser.
        for (SourceCatalog.QuestSource qs : source.quests()) {
            if (qs.plainId().equals(plainId)) {
                continue;
            }
            boolean references = qs.parseOk() && qs.draft().prerequisites.stream()
                    .anyMatch(p -> QuestYaml.plainId(p).equals(plainId));
            if (!qs.parseOk()) {
                // On ne réécrit jamais un fichier qu'on n'a pas su lire : on ne saurait pas ce
                // qu'on écrase. On ne bloque que s'il mentionne l'identifiant.
                if (mentions("quests", qs.slug(), plainId)) {
                    blockers.add(new DeletionPlan.Blocker(
                            "La quête « " + qs.slug() + " » mentionne « " + plainId + " » mais son "
                                    + "fichier n'a pas pu être analysé : impossible d'en retirer la "
                                    + "référence sans risquer d'écraser autre chose.",
                            "src/main/resources/quests/" + qs.slug() + ".yml",
                            "Corriger ce fichier dans l'éditeur, puis relancer la suppression."));
                }
                continue;
            }
            if (!references) {
                continue;
            }
            QuestDraft copy = withoutPrerequisite(qs.draft(), plainId);
            edits.add(edit("quests", qs.slug(),
                    "Retirer « " + plainId + " » des prérequis de la quête « " + qs.slug() + " ».",
                    QuestYaml.write(copy)));
        }

        // 2. Chaînes de story : retirer une quête d'une chaîne est sûr… sauf si elle était la
        //    seule. Une story à chaîne vide est refusée par le moteur, donc on bloque plutôt que
        //    de produire un fichier invalide.
        for (SourceCatalog.StorySource ss : source.stories()) {
            if (!ss.parseOk()) {
                if (mentions("stories", ss.slug(), plainId)) {
                    blockers.add(new DeletionPlan.Blocker(
                            "La story « " + ss.slug() + " » mentionne « " + plainId + " » mais son "
                                    + "fichier n'a pas pu être analysé.",
                            "src/main/resources/stories/" + ss.slug() + ".yml",
                            "Corriger ce fichier dans l'éditeur, puis relancer la suppression."));
                }
                continue;
            }
            List<String> remaining = ss.draft().questIds.stream()
                    .filter(q -> !QuestYaml.plainId(q).equals(plainId))
                    .toList();
            if (remaining.size() == ss.draft().questIds.size()) {
                continue;
            }
            if (remaining.isEmpty()) {
                blockers.add(new DeletionPlan.Blocker(
                        "La story « " + ss.slug() + " » n'enchaîne que cette quête : la retirer "
                                + "laisserait une story sans aucune quête, que le serveur refuse "
                                + "de charger.",
                        "src/main/resources/stories/" + ss.slug() + ".yml",
                        "Ajouter une autre quête à cette story, ou la supprimer elle aussi — puis "
                                + "relancer."));
                continue;
            }
            StoryDraft copy = ss.draft().copy();
            copy.questIds.clear();
            copy.questIds.addAll(remaining);
            edits.add(edit("stories", ss.slug(),
                    "Retirer « " + plainId + " » de la chaîne de la story « " + ss.slug()
                            + " » (" + remaining.size() + " quête(s) restante(s)).",
                    StoryYaml.write(copy)));
        }

        // 3. Dialogues : on ne les réécrit JAMAIS automatiquement. Retirer une action START_QUEST
        //    laisserait un choix sans effet ; retirer le choix entier change le sens du dialogue.
        //    C'est une décision éditoriale, pas un nettoyage mécanique.
        for (SourceCatalog.DialogueSource ds : source.dialogues()) {
            List<Integer> lines = questReferenceLines(ds.slug(), plainId);
            if (lines.isEmpty()) {
                continue;
            }
            blockers.add(new DeletionPlan.Blocker(
                    "Le dialogue « " + ds.slug() + " » référence cette quête "
                            + (lines.size() == 1 ? "à la ligne " : "aux lignes ")
                            + joinLines(lines) + " (condition QUEST_STATE et/ou action "
                            + "START_QUEST). Retirer l'action laisserait un choix sans effet, et "
                            + "retirer le choix change le dialogue : ce n'est pas un nettoyage "
                            + "mécanique.",
                    "src/main/resources/dialogues/" + ds.slug() + ".yml",
                    "Modifier ce dialogue dans son éditeur pour retirer ou réaffecter ces "
                            + "références, puis relancer la suppression."));
        }
    }

    /**
     * Issue #248 — les dialogues <strong>chargés par le serveur</strong> qui démarrent ou testent
     * la quête. C'était le trou de #194 : un dialogue hors source ({@code rpgquest:help}) démarrait
     * {@code help_recolte}, et l'aperçu ne voyait que la source.
     */
    private void analyzeRuntimeQuestReferences(String plainId, RuntimeDialogueInventory inventory,
                                               Instant now, List<DeletionPlan.Blocker> blockers) {
        if (!inventory.usable(now)) {
            blockers.add(incompleteVerification(inventory, now,
                    "aucun dialogue chargé par le serveur ne démarre cette quête"));
            return;
        }
        for (RuntimeDialogueInventory.Entry e : inventory.referencingQuest(plainId)) {
            if (workspace.read("dialogues", e.key()).isPresent()) {
                continue; // déjà signalé, à la ligne près, par l'analyse de la source
            }
            blockers.add(new DeletionPlan.Blocker(
                    "Le dialogue « " + e.id() + " », chargé sur le serveur mais absent de la source "
                            + "(hors source), " + (e.startsQuestIds().contains(plainId)
                            ? "démarre" : "teste l'état de") + " cette quête.",
                    "serveur DEV — dialogues/" + e.key() + ".yml",
                    "Supprimer ce dialogue (« Supprimer le dialogue » sur sa fiche) ou retirer la "
                            + "référence, puis rafraîchir le relevé des dialogues et relancer."));
        }
    }

    /** Le relevé ne permet pas de conclure : on le dit, et on refuse. */
    private static DeletionPlan.Blocker incompleteVerification(RuntimeDialogueInventory inventory,
                                                               Instant now, String claim) {
        String age = inventory.surveyedAt()
                .map(at -> "date de " + java.time.Duration.between(at, now).toMinutes() + " min")
                .orElse("n'a jamais été fait");
        return new DeletionPlan.Blocker(
                "Vérification incomplète : le relevé des dialogues du serveur " + age + " (au-delà "
                        + "de " + RuntimeDialogueInventory.MAX_AGE.toMinutes() + " min, il ne fait "
                        + "plus foi). Impossible d'affirmer que " + claim + ".",
                "relevé dialogue.list",
                "Cliquer « Rafraîchir le relevé des dialogues » ci-dessous, attendre quelques "
                        + "secondes, puis recharger cet aperçu.");
    }

    // ---- Suppression d'un dialogue (issue #248) ----------------------------------------------

    /**
     * Plan de suppression d'un dialogue.
     *
     * <p>Mêmes règles que pour une quête : <strong>jamais de cascade</strong> (ni le PNJ, ni les
     * quêtes qu'il démarre), <strong>jamais de lien orphelin</strong> (un PNJ lié ou un dialogue qui
     * l'ouvre bloque, au lieu d'être réécrit), et <strong>jamais de conclusion sans preuve</strong> :
     * un relevé absent ou périmé bloque, plutôt que d'afficher « aucune référence ».</p>
     */
    private DeletionPlan analyzeDialogue(String slug, RuntimeDialogueInventory inventory, Instant now) {
        List<DeletionPlan.Blocker> blockers = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String plainId = DialogueYaml.plainId(slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT));
        Optional<ContentWorkspace.ContentFile> file = workspace.read("dialogues", plainId);
        boolean sourcePresent = file.isPresent();
        Optional<RuntimeDialogueInventory.Entry> runtime = inventory.find(plainId);
        boolean runtimePresent = runtime.isPresent();
        boolean usable = inventory.usable(now);

        if (!usable) {
            blockers.add(incompleteVerification(inventory, now,
                    "aucun PNJ ni aucun autre dialogue n'utilise celui-ci"));
        }
        if (!sourcePresent && !runtimePresent && usable) {
            blockers.add(new DeletionPlan.Blocker(
                    "Ce dialogue n'existe ni dans la source éditable, ni dans le relevé du serveur : "
                            + "il n'y a rien à supprimer.", plainId,
                    "Rafraîchir le catalogue des dialogues, puis vérifier l'identifiant."));
        }
        if (sourcePresent) {
            if (!workspace.writable("dialogues")) {
                blockers.add(new DeletionPlan.Blocker(
                        "L'espace de travail source est en lecture seule : le service PlugAdmin n'a "
                                + "pas les droits d'écriture sur « dialogues/ ».", "dialogues",
                        "Exécuter scripts/plugadmin/grant-content-access.sh, puis recharger."));
            }
            if (!workspace.backupsConfigured()) {
                blockers.add(new DeletionPlan.Blocker(
                        "Aucun dossier de sauvegarde configuré : une suppression sans sauvegarde "
                                + "préalable est refusée.", "configuration du panel",
                        "Vérifier RPGQUEST_PANEL_DB (les sauvegardes vivent à côté de la base)."));
            }
        } else if (runtimePresent) {
            notes.add("Hors source : ce dialogue n'existe que sur le serveur. Seule sa copie "
                    + "serveur sera supprimée, avec sauvegarde.");
        }

        // 1. PNJ liés — d'après le serveur (déclaration « dialogue: » ou convention rpgquest:<pnj>).
        runtime.ifPresent(e -> {
            for (String npc : e.linkedNpcIds()) {
                blockers.add(new DeletionPlan.Blocker(
                        "Lié au(x) PNJ « " + npc + " » : le PNJ resterait sans dialogue. Le PNJ "
                                + "n'est jamais supprimé ici.",
                        "PNJ " + npc,
                        "Changer le dialogue de ce PNJ depuis sa fiche (ou supprimer le PNJ), "
                                + "rafraîchir le relevé, puis relancer.",
                        "/npcs?focus=" + npc, npc));
            }
        });

        // 2. Dialogues qui l'ouvrent — serveur, puis source. Les réécrire changerait leur sens :
        //    on bloque, comme pour une quête démarrée par un dialogue.
        for (String opener : usable ? inventory.openersOf(plainId) : List.<String>of()) {
            blockers.add(new DeletionPlan.Blocker(
                    "Ouvert par le dialogue " + opener + " (OPEN_DIALOGUE). Le serveur refuserait "
                            + "de recharger ce dialogue s'il pointait vers un dialogue disparu.",
                    "serveur DEV — dialogues",
                    "Modifier ce dialogue pour retirer ou réorienter ce choix, puis relancer.",
                    "/dialogues/edit/" + DialogueYaml.plainId(opener), DialogueYaml.plainId(opener)));
        }
        for (SourceCatalog.DialogueSource ds : source.dialogues()) {
            if (ds.plainId().equals(plainId)) {
                continue;
            }
            List<Integer> lines = referenceLines(DIALOGUE_OPEN_REF, "dialogues", ds.slug(), plainId);
            boolean alreadyFromRuntime = usable && inventory.openerKeys(plainId).contains(ds.plainId());
            if (!lines.isEmpty() && !alreadyFromRuntime) {
                blockers.add(new DeletionPlan.Blocker(
                        "Le dialogue source « " + ds.slug() + " » l'ouvre "
                                + (lines.size() == 1 ? "à la ligne " : "aux lignes ")
                                + joinLines(lines) + " (OPEN_DIALOGUE).",
                        "src/main/resources/dialogues/" + ds.slug() + ".yml",
                        "Modifier ce dialogue dans son éditeur, puis relancer.",
                        "/dialogues/edit/" + ds.slug(), ds.slug()));
            }
        }

        // 3. Références SORTANTES : informatives, jamais supprimées.
        runtime.ifPresent(e -> {
            List<String> out = new ArrayList<>();
            e.startsQuestIds().forEach(q -> out.add("démarre la quête « " + q + " »"));
            e.referencedQuestIds().stream().filter(q -> !e.startsQuestIds().contains(q))
                    .forEach(q -> out.add("teste l'état de la quête « " + q + " »"));
            e.opens().forEach(o -> out.add("ouvre le dialogue « " + o.targetKey() + " »"));
            if (!out.isEmpty()) {
                notes.add("Références sortantes, qui NE sont PAS supprimées : " + String.join(" ; ", out)
                        + ". Après suppression, elles ne sont simplement plus atteintes par ce "
                        + "dialogue — une quête démarrée seulement ici ne sera plus proposée.");
            }
        });
        if (runtimePresent && runtime.get().linkedNpcIds().isEmpty()) {
            notes.add("Aucun PNJ RPGQuest lié, d'après le relevé du serveur.");
        }

        notes.add("Les guides de Hub (hub-guides/*.yml) ne sont pas relevés par le panel : le "
                + "serveur les vérifie au moment d'exécuter, et refuse si l'un utilise ce dialogue "
                + "comme menu d'aide. Il revérifie aussi les PNJ et les dialogues.");
        notes.add("Aucune progression de joueur n'est effacée, et aucune quête n'est supprimée.");
        notes.add("La suppression côté serveur exige un plugin qui contient #248. Avec un JAR "
                + "antérieur, l'agent la refuse (« Paramètre « kind » attendu ») sans rien toucher.");
        boolean bundled = BUNDLED_DIALOGUES.contains(plainId);
        if (bundled) {
            notes.add("Exemple embarqué dans le JAR : le serveur le recrée au démarrage s'il manque. "
                    + "La suppression serveur inscrit une pierre tombale "
                    + "(content-tombstones/dialogues/) qui l'en empêche — pour le faire revenir, "
                    + "republier le fichier ou supprimer la pierre tombale.");
        }
        return plan("dialogues", plainId, plainId, sourcePresent,
                file.map(ContentWorkspace.ContentFile::sha256).orElse(null),
                runtimePresent, bundled, List.of(), blockers, notes);
    }

    private void analyzeStoryReferences(String plainId, List<String> notes) {
        // Rien dans le contenu ne référence une story : ni les quêtes, ni les dialogues (aucune
        // action ni condition de story n'existe dans le moteur), ni les définitions de PNJ. Une
        // story ne porte que des références SORTANTES vers des quêtes.
        Optional<SourceCatalog.StorySource> self = source.stories().stream()
                .filter(s -> s.plainId().equals(plainId))
                .findFirst();
        int chained = self.filter(SourceCatalog.StorySource::parseOk)
                .map(s -> s.draft().questIds.size()).orElse(0);
        notes.add("Les " + chained + " quête(s) enchaînée(s) par cette story ne sont pas "
                + "supprimées : une story ne fait que les ordonner.");
    }

    // ---- Notes communes --------------------------------------------------------------------

    private void addRuntimeAndProgressNotes(String kind, String plainId, boolean sourcePresent,
                                            boolean runtimePresent, boolean runtimeListingAvailable,
                                            boolean bundled, List<String> notes) {
        String what = "quests".equals(kind) ? "quête" : "story";

        // Progression joueur : politique explicite, jamais un effacement silencieux.
        notes.add("Aucune progression de joueur n'est effacée. Les lignes déjà enregistrées restent "
                + "en base et deviennent sans objet — la suppression est éditoriale. Réinitialiser "
                + "un joueur est une opération distincte (" + ("quests".equals(kind)
                ? "« Réinitialiser la quête »" : "« Réinitialiser la story »") + "), avec sa propre "
                + "permission.");

        if (runtimePresent) {
            notes.add("Le serveur connaît encore cette " + what + " : sa suppression côté serveur "
                    + "sera demandée en même temps, avec sauvegarde du fichier distant. Sans cela, "
                    + "elle réapparaîtrait au prochain rafraîchissement du catalogue.");
        } else if (!runtimeListingAvailable) {
            // Ne jamais présenter une absence de donnée comme une donnée.
            notes.add("ATTENTION — aucun relevé du serveur n'a encore été fait : impossible de "
                    + "savoir si cette " + what + " y existe, donc aucune suppression côté serveur "
                    + "ne sera demandée. Si elle est présente sur le serveur, elle réapparaîtra au "
                    + "prochain rafraîchissement du catalogue. Cliquer « Rafraîchir » sur le "
                    + "catalogue avant de supprimer lève le doute.");
        } else if (sourcePresent) {
            notes.add("Le dernier relevé du serveur ne connaît pas cette " + what + " : il n'y a "
                    + "donc rien à supprimer côté serveur.");
        }

        if (bundled) {
            notes.add("ATTENTION — cette " + what + " fait partie des exemples embarqués dans le "
                    + "JAR du plugin. Le serveur les recrée au démarrage si le fichier manque. "
                    + "Elle réapparaîtra donc après un redémarrage tant que le JAR actuellement "
                    + "déployé la contient : il faut reconstruire et redéployer le plugin depuis "
                    + "la source sans elle pour que la suppression soit définitive.");
        }
    }

    // ---- Utilitaires -----------------------------------------------------------------------

    private DeletionPlan plan(String kind, String plainId, String label, boolean sourcePresent,
                              String sourceSha, boolean runtimePresent, boolean bundled,
                              List<DeletionPlan.Edit> edits, List<DeletionPlan.Blocker> blockers,
                              List<String> notes) {
        return new DeletionPlan(kind, plainId, plainId, label, sourcePresent, sourceSha,
                runtimePresent, bundled, edits, blockers, notes);
    }

    private DeletionPlan.Edit edit(String kind, String slug, String description, String newYaml) {
        String sha = workspace.read(kind, slug)
                .map(ContentWorkspace.ContentFile::sha256).orElse(null);
        return new DeletionPlan.Edit(kind, slug, "src/main/resources/" + kind + "/" + slug + ".yml",
                description, newYaml, sha);
    }

    /** Titre humain du contenu visé, pour que la confirmation parle de contenu et pas de fichier. */
    private String labelOf(String kind, String plainId, String fallback) {
        if ("quests".equals(kind)) {
            return source.quests().stream()
                    .filter(q -> q.plainId().equals(plainId) && q.parseOk())
                    .map(q -> q.draft().title)
                    .filter(t -> t != null && !t.isBlank())
                    .findFirst().orElse(fallback);
        }
        return source.stories().stream()
                .filter(s -> s.plainId().equals(plainId) && s.parseOk())
                .map(s -> s.draft().name)
                .filter(n -> n != null && !n.isBlank())
                .findFirst().orElse(fallback);
    }

    /** Le fichier mentionne-t-il cet identifiant, même sans avoir pu être analysé ? */
    private boolean mentions(String kind, String slug, String plainId) {
        return workspace.read(kind, slug)
                .map(ContentWorkspace.ContentFile::text)
                .map(text -> text.contains(plainId))
                .orElse(false);
    }

    /** Lignes d'un dialogue qui référencent la quête, pour pointer l'endroit exact. */
    private List<Integer> questReferenceLines(String dialogueSlug, String plainId) {
        return referenceLines(DIALOGUE_QUEST_REF, "dialogues", dialogueSlug, plainId);
    }

    /** Lignes d'un fichier dont le motif capture cet identifiant, numérotées pour un humain. */
    private List<Integer> referenceLines(Pattern pattern, String kind, String slug, String plainId) {
        List<Integer> lines = new ArrayList<>();
        String text = workspace.read(kind, slug)
                .map(ContentWorkspace.ContentFile::text).orElse(null);
        if (text == null) {
            return lines;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!QuestYaml.plainId(matcher.group(1)).equals(plainId)) {
                continue;
            }
            // Numéro de ligne humain : on compte les sauts de ligne avant la correspondance.
            int line = 1;
            for (int i = 0; i < matcher.start(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    private static String joinLines(List<Integer> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(i == lines.size() - 1 ? " et " : ", ");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static QuestDraft withoutPrerequisite(QuestDraft original, String plainId) {
        QuestDraft copy = original.copy();
        copy.prerequisites.removeIf(p -> QuestYaml.plainId(p).equals(plainId));
        return copy;
    }
}
