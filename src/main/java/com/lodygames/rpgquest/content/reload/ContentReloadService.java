package com.lodygames.rpgquest.content.reload;

import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.AdvanceQuestAction;
import com.lodygames.rpgquest.dialogue.model.DialogueAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.npc.YamlNpcEngine;
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.story.StoryRegistry;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Service <strong>central</strong> de rechargement du contenu dans le runtime (issue #131).
 *
 * <p><strong>Pourquoi un service, et pourquoi central.</strong> Les six registres savaient déjà se
 * recharger ({@code reload()} renvoyant un rapport structuré), mais rien ne l'exposait : la
 * permission {@code ACTION_CONTENT_RELOAD} existait côté panel <em>sans aucune action derrière</em>,
 * et la seule porte réelle était {@code /rpgadmin mob reload} — une famille sur six. Le ticket
 * demande explicitement d'éviter plusieurs implémentations divergentes : tout passe donc ici, et
 * {@code /rpgadmin} comme l'agent PlugAdmin n'en sont que des appelants.</p>
 *
 * <p><strong>Le danger réel d'un reload naïf.</strong> {@code reload()} remplace l'ensemble actif
 * par les fichiers <em>valides</em>. Un fichier devenu invalide n'échoue donc pas : il
 * <strong>disparaît silencieusement du runtime</strong>. Une quête active pour des joueurs peut
 * ainsi s'évaporer d'un seul rechargement. C'est précisément l'« état partiellement chargé » que le
 * ticket interdit.</p>
 *
 * <p><strong>La séquence qui évite cela</strong>, et qui est la raison d'être de cette classe :</p>
 * <ol>
 *   <li><strong>dry-run</strong> de chaque famille demandée ({@code validate()}), qui ne touche
 *       <em>rien</em> ;</li>
 *   <li>si une erreur de parsing existe → <strong>on n'applique rien</strong>, l'ancien runtime
 *       valide reste en place, et les diagnostics sont renvoyés ;</li>
 *   <li><strong>validation des références croisées</strong> sur le graphe candidat (résultats du
 *       dry-run pour les familles rechargées, runtime courant pour les autres) ; une référence qui
 *       serait cassée annule l'opération et nomme la famille à recharger <em>conjointement</em> ;</li>
 *   <li><strong>application</strong> dans l'ordre de dépendance ({@link ReloadFamily}) ;</li>
 *   <li><strong>relecture du runtime</strong> pour confirmer, avec empreinte et durée.</li>
 * </ol>
 *
 * <p><strong>Ce que ce service ne fait jamais</strong> : aucun {@code /reload} Bukkit, aucun
 * despawn ou respawn, aucune redistribution de récompense, aucune écriture de progression, aucun
 * accès disque en dehors des dossiers de contenu déjà lus au démarrage. Les instances de mobs et de
 * boss vivantes sont <strong>conservées</strong> : recharger un profil change ce qui apparaîtra
 * ensuite, pas ce qui est déjà dans le monde.</p>
 *
 * <p><strong>Single-flight</strong> : deux rechargements simultanés sont refusés, pas sérialisés —
 * un double-clic ne doit pas produire deux permutations d'ensembles.</p>
 */
public final class ContentReloadService {

    /**
     * Résultat d'une famille, que l'opération ait été appliquée ou seulement validée.
     *
     * @param ids identifiants <strong>présents sur le disque du serveur</strong> et valides. C'est
     *     ce qui permet au panel de distinguer les trois états exigés par le ticket : un contenu
     *     absent de cette liste n'a jamais été <em>publié</em> sur VeryGames (un rechargement n'y
     *     changerait rien — il faut un déploiement), alors qu'un contenu présent ici mais absent du
     *     runtime est simplement <em>pas encore chargé</em> (un rechargement suffit).
     */
    public record FamilyOutcome(ReloadFamily family, int loaded, int issues, List<String> messages,
                                List<String> ids) {
        public FamilyOutcome {
            messages = messages == null ? List.of() : List.copyOf(messages);
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        public boolean clean() {
            return issues == 0;
        }
    }

    /** Au-delà, la liste d'identifiants est tronquée : un résultat d'action reste borné. */
    private static final int MAX_IDS_PER_FAMILY = 500;

    /**
     * Résultat structuré, tel que le panel l'affiche et que l'audit le conserve.
     *
     * @param applied        {@code true} = le runtime a réellement changé. {@code false} pour un
     *                       aperçu, un refus, ou un échec — jamais ambigu
     * @param code           {@code APPLIED} / {@code PREVIEW_OK} / {@code INVALID_CONTENT} /
     *                       {@code BROKEN_REFERENCES} / {@code BUSY} / {@code NOTHING_REQUESTED}
     * @param message        phrase lisible pour l'administrateur
     * @param families       issue par famille demandée
     * @param referenceErrors références croisées qui seraient cassées (vide si tout va bien)
     * @param suggestedFamilies familles à recharger <strong>conjointement</strong> pour réparer les
     *                       références — le « contenu lié » du ticket
     * @param runtimeHash    empreinte du runtime <em>après</em> l'opération (ou avant, si rien n'a
     *                       été appliqué) : deux rechargements identiques donnent la même valeur
     * @param durationMillis durée réelle de l'opération
     * @param restartRequired {@code true} si une partie de la demande ne peut pas être prise à chaud
     */
    public record ReloadResult(boolean applied, String code, String message,
                               List<FamilyOutcome> families, List<String> referenceErrors,
                               List<ReloadFamily> suggestedFamilies, String runtimeHash,
                               long durationMillis, boolean restartRequired) {
        public ReloadResult {
            families = families == null ? List.of() : List.copyOf(families);
            referenceErrors = referenceErrors == null ? List.of() : List.copyOf(referenceErrors);
            suggestedFamilies = suggestedFamilies == null ? List.of() : List.copyOf(suggestedFamilies);
        }

        public int totalLoaded() {
            return families.stream().mapToInt(FamilyOutcome::loaded).sum();
        }

        public int totalIssues() {
            return families.stream().mapToInt(FamilyOutcome::issues).sum();
        }
    }

    /** Au-delà, la liste de diagnostics est tronquée : un résultat d'action reste borné. */
    private static final int MAX_MESSAGES_PER_FAMILY = 20;

    private final YamlQuestEngine questEngine;
    private final StoryRegistry storyRegistry;
    private final YamlDialogueEngine dialogueEngine;
    private final YamlNpcEngine npcEngine;
    private final YamlCustomItemRegistry itemRegistry;
    private final SpecialMobRegistry mobRegistry;
    private final Logger logger;
    /** Invalidation des caches dérivés après application (ex. signal visuel des PNJ, #12). */
    private final Consumer<Set<ReloadFamily>> cacheInvalidator;

    private final AtomicBoolean running = new AtomicBoolean();

    public ContentReloadService(YamlQuestEngine questEngine, StoryRegistry storyRegistry,
                                YamlDialogueEngine dialogueEngine, YamlNpcEngine npcEngine,
                                YamlCustomItemRegistry itemRegistry, SpecialMobRegistry mobRegistry,
                                Logger logger, Consumer<Set<ReloadFamily>> cacheInvalidator) {
        this.questEngine = questEngine;
        this.storyRegistry = storyRegistry;
        this.dialogueEngine = dialogueEngine;
        this.npcEngine = npcEngine;
        this.itemRegistry = itemRegistry;
        this.mobRegistry = mobRegistry;
        this.logger = logger;
        this.cacheInvalidator = cacheInvalidator == null ? families -> { } : cacheInvalidator;
    }

    /**
     * Valide sans rien appliquer : c'est l'aperçu exigé avant mutation. Sûr à tout moment, même
     * pendant qu'un rechargement est en cours (aucun ensemble actif n'est touché).
     */
    public ReloadResult preview(Set<ReloadFamily> requested) {
        return run(requested, false);
    }

    /** Valide puis applique si — et seulement si — tout est propre. */
    public ReloadResult reload(Set<ReloadFamily> requested) {
        return run(requested, true);
    }

    /** Empreinte du runtime courant : identifiants chargés, triés, par famille. */
    public String runtimeHash() {
        return hash(currentRuntimeSignature());
    }

    // ---- Cœur -----------------------------------------------------------------------------

    private ReloadResult run(Set<ReloadFamily> requested, boolean apply) {
        long startedAt = System.nanoTime();
        if (requested == null || requested.isEmpty()) {
            return new ReloadResult(false, "NOTHING_REQUESTED", "Aucune famille de contenu demandée.",
                    List.of(), List.of(), List.of(), runtimeHash(), elapsed(startedAt), false);
        }
        Set<ReloadFamily> families = ReloadFamily.ordered(requested);

        if (apply && !running.compareAndSet(false, true)) {
            // Refusé, jamais mis en file : deux permutations d'ensembles qui s'enchaînent sans que
            // l'administrateur l'ait voulu seraient indébogables.
            return new ReloadResult(false, "BUSY",
                    "Un rechargement de contenu est déjà en cours. Réessayer dans un instant.",
                    List.of(), List.of(), List.of(), runtimeHash(), elapsed(startedAt), false);
        }
        try {
            // 1. Dry-run : on obtient les ensembles CANDIDATS sans toucher au runtime.
            Map<ReloadFamily, Candidate> candidates = new EnumMap<>(ReloadFamily.class);
            List<FamilyOutcome> outcomes = new ArrayList<>();
            for (ReloadFamily family : families) {
                Candidate candidate = validateFamily(family);
                candidates.put(family, candidate);
                outcomes.add(candidate.outcome());
            }

            // 2. Une seule erreur de parsing suffit à tout annuler. Appliquer malgré elle
            //    retirerait du runtime la définition fautive — exactement ce qu'il faut éviter.
            if (outcomes.stream().anyMatch(outcome -> !outcome.clean())) {
                String message = "Contenu invalide : rien n'a été rechargé, le runtime précédent est"
                        + " conservé. Corriger les erreurs listées puis réessayer.";
                logger.warn("[content-reload] refusé — {} erreur(s) de contenu sur {} famille(s).",
                        outcomes.stream().mapToInt(FamilyOutcome::issues).sum(), families.size());
                return new ReloadResult(false, "INVALID_CONTENT", message, outcomes, List.of(),
                        List.of(), runtimeHash(), elapsed(startedAt), false);
            }

            // 3. Références croisées sur le graphe candidat.
            CrossCheck crossCheck = checkReferences(candidates);
            if (!crossCheck.errors().isEmpty()) {
                String message = "Références croisées cassées : rien n'a été rechargé."
                        + (crossCheck.suggested().isEmpty() ? ""
                                : " Recharger aussi : " + labels(crossCheck.suggested()) + ".");
                logger.warn("[content-reload] refusé — {} référence(s) cassée(s).", crossCheck.errors().size());
                return new ReloadResult(false, "BROKEN_REFERENCES", message, outcomes,
                        crossCheck.errors(), List.copyOf(crossCheck.suggested()), runtimeHash(),
                        elapsed(startedAt), false);
            }

            if (!apply) {
                return new ReloadResult(false, "PREVIEW_OK",
                        "Aperçu : " + totalOf(outcomes) + " élément(s) valides, aucune référence cassée."
                                + " Rien n'a été appliqué.",
                        outcomes, List.of(), List.of(), runtimeHash(), elapsed(startedAt), false);
            }

            // 4. Application, dans l'ordre de dépendance.
            List<FamilyOutcome> applied = new ArrayList<>();
            for (ReloadFamily family : families) {
                applied.add(applyFamily(family));
            }
            // 5. Caches dérivés : sans cela, le signal visuel des PNJ (#12) continuerait d'afficher
            //    l'ancienne disponibilité jusqu'à sa propre expiration.
            cacheInvalidator.accept(families);

            String hash = runtimeHash();
            logger.info("[content-reload] appliqué : {} — {} élément(s), empreinte {}.",
                    labels(families), totalOf(applied), hash);
            return new ReloadResult(true, "APPLIED",
                    labels(families) + " rechargé(s) : " + totalOf(applied) + " élément(s) en jeu.",
                    applied, List.of(), List.of(), hash, elapsed(startedAt), false);
        } catch (RuntimeException e) {
            logger.error("[content-reload] échec interne", e);
            return new ReloadResult(false, "ERROR",
                    "Échec interne du rechargement (" + e.getClass().getSimpleName()
                            + ") : runtime inchangé si l'erreur est survenue avant l'application.",
                    List.of(), List.of(), List.of(), runtimeHash(), elapsed(startedAt), false);
        } finally {
            if (apply) {
                running.set(false);
            }
        }
    }

    /** Ensembles candidats d'une famille, issus du dry-run. */
    private record Candidate(FamilyOutcome outcome, Collection<?> loaded) {
    }

    private Candidate validateFamily(ReloadFamily family) {
        return switch (family) {
            case ITEMS -> {
                var report = itemRegistry.validate();
                yield new Candidate(outcome(family, report.loaded().size(),
                        report.issues().stream().map(i -> i.file() + " : " + i.message()).toList(),
                        idsOf(report.loaded())),
                        report.loaded());
            }
            case NPCS -> {
                var report = npcEngine.validate();
                yield new Candidate(outcome(family, report.loaded().size(),
                        report.issues().stream().map(i -> i.file() + " : " + i.message()).toList(),
                        idsOf(report.loaded())),
                        report.loaded());
            }
            case QUESTS -> {
                var report = questEngine.validate();
                yield new Candidate(outcome(family, report.loaded().size(),
                        report.issues().stream().map(i -> i.file() + " : " + i.message()).toList(),
                        idsOf(report.loaded())),
                        report.loaded());
            }
            case STORIES -> {
                var report = storyRegistry.validate();
                yield new Candidate(outcome(family, report.loaded().size(),
                        report.issues().stream().map(i -> i.file() + " : " + i.message()).toList(),
                        idsOf(report.loaded())),
                        report.loaded());
            }
            case DIALOGUES -> {
                var report = dialogueEngine.validate();
                yield new Candidate(outcome(family, report.loaded().size(),
                        report.issues().stream().map(i -> i.file() + " : " + i.message()).toList(),
                        idsOf(report.loaded())),
                        report.loaded());
            }
            case MOBS -> {
                var report = mobRegistry.validate();
                yield new Candidate(outcome(family, report.loaded().size(),
                        report.issues().stream().map(i -> i.file() + " : " + i.message()).toList(),
                        idsOf(report.loaded())),
                        report.loaded());
            }
        };
    }

    private FamilyOutcome applyFamily(ReloadFamily family) {
        return switch (family) {
            case ITEMS -> {
                var report = itemRegistry.reload();
                yield outcome(family, report.loaded().size(), List.of(), idsOf(report.loaded()));
            }
            case NPCS -> {
                var report = npcEngine.reload();
                yield outcome(family, report.loaded().size(), List.of(), idsOf(report.loaded()));
            }
            case QUESTS -> {
                var report = questEngine.reload();
                yield outcome(family, report.loaded().size(), List.of(), idsOf(report.loaded()));
            }
            case STORIES -> {
                var report = storyRegistry.reload();
                yield outcome(family, report.loaded().size(), List.of(), idsOf(report.loaded()));
            }
            case DIALOGUES -> {
                var report = dialogueEngine.reload();
                yield outcome(family, report.loaded().size(), List.of(), idsOf(report.loaded()));
            }
            case MOBS -> {
                // Ne touche QUE les définitions : les instances vivantes restent en place.
                var report = mobRegistry.reload();
                yield outcome(family, report.loaded().size(), List.of(), idsOf(report.loaded()));
            }
        };
    }

    private FamilyOutcome outcome(ReloadFamily family, int loaded, List<String> messages, List<String> ids) {
        List<String> bounded = messages.size() <= MAX_MESSAGES_PER_FAMILY ? messages
                : new ArrayList<>(messages.subList(0, MAX_MESSAGES_PER_FAMILY));
        if (messages.size() > MAX_MESSAGES_PER_FAMILY) {
            bounded.add("… et " + (messages.size() - MAX_MESSAGES_PER_FAMILY) + " autre(s) erreur(s).");
        }
        List<String> boundedIds = ids.size() <= MAX_IDS_PER_FAMILY ? ids
                : List.copyOf(ids.subList(0, MAX_IDS_PER_FAMILY));
        return new FamilyOutcome(family, loaded, messages.size(), bounded, boundedIds);
    }

    /** Identifiants triés d'un ensemble de définitions, quelle que soit la famille. */
    private static List<String> idsOf(Collection<?> definitions) {
        Set<String> ids = new TreeSet<>();
        for (Object definition : definitions) {
            switch (definition) {
                case QuestDefinition quest -> ids.add(quest.id().toString());
                case StoryDefinition story -> ids.add(story.id());
                case DialogueDefinition dialogue -> ids.add(dialogue.id().toString());
                case NpcDefinition npc -> ids.add(npc.id());
                case CustomItemDefinition item -> ids.add(item.id().toString());
                case SpecialMobDefinition mob -> ids.add(mob.id().toString());
                default -> { }
            }
        }
        return List.copyOf(ids);
    }

    // ---- Références croisées ---------------------------------------------------------------

    private record CrossCheck(List<String> errors, Set<ReloadFamily> suggested) {
    }

    /**
     * Vérifie le graphe <strong>candidat</strong> : pour chaque famille rechargée on prend les
     * définitions du dry-run, pour les autres l'état réellement chargé. C'est le seul graphe qui
     * décrit l'après-rechargement.
     *
     * <p>Quand une référence casse, on nomme la famille à recharger conjointement : le cas typique
     * est un dialogue qui démarre une quête nouvellement ajoutée — recharger les dialogues seuls
     * échouerait, recharger les deux réussit.</p>
     */
    private CrossCheck checkReferences(Map<ReloadFamily, Candidate> candidates) {
        List<QuestDefinition> quests = resolve(candidates, ReloadFamily.QUESTS, QuestDefinition.class,
                questEngine.quests());
        List<StoryDefinition> stories = resolve(candidates, ReloadFamily.STORIES, StoryDefinition.class,
                storyRegistry.stories());
        List<DialogueDefinition> dialogues = resolve(candidates, ReloadFamily.DIALOGUES,
                DialogueDefinition.class, dialogueEngine.dialogues());
        List<NpcDefinition> npcs = resolve(candidates, ReloadFamily.NPCS, NpcDefinition.class,
                npcEngine.definitions());

        Set<String> questIds = new HashSet<>();
        quests.forEach(quest -> questIds.add(quest.id().toString()));
        Set<String> dialogueIds = new HashSet<>();
        dialogues.forEach(dialogue -> dialogueIds.add(dialogue.id().toString()));
        Set<String> npcIds = new HashSet<>();
        npcs.forEach(npc -> npcIds.add(npc.id()));

        List<String> errors = new ArrayList<>();
        Set<ReloadFamily> suggested = new LinkedHashSet<>();

        for (QuestDefinition quest : quests) {
            for (NamespacedKey prerequisite : quest.prerequisites()) {
                if (!questIds.contains(prerequisite.toString())) {
                    errors.add("Quête « " + quest.id() + " » : prérequis inconnu « " + prerequisite + " ».");
                    suggested.add(ReloadFamily.QUESTS);
                }
            }
            if (quest.giver() != null && !quest.giver().isBlank() && !npcIds.contains(quest.giver())) {
                errors.add("Quête « " + quest.id() + " » : PNJ donneur inconnu « " + quest.giver() + " ».");
                suggested.add(ReloadFamily.NPCS);
            }
        }
        for (StoryDefinition story : stories) {
            for (NamespacedKey questId : story.questIds()) {
                if (!questIds.contains(questId.toString())) {
                    errors.add("Story « " + story.id() + " » : quête inconnue « " + questId + " ».");
                    suggested.add(ReloadFamily.QUESTS);
                }
            }
        }
        for (NpcDefinition npc : npcs) {
            if (npc.dialogueId() != null && !npc.dialogueId().isBlank()
                    && !dialogueIds.contains(normalizeKey(npc.dialogueId()))) {
                errors.add("PNJ « " + npc.id() + " » : dialogue inconnu « " + npc.dialogueId() + " ».");
                suggested.add(ReloadFamily.DIALOGUES);
            }
        }
        for (DialogueDefinition dialogue : dialogues) {
            for (DialogueNode node : dialogue.nodes().values()) {
                for (DialogueChoice choice : node.choices()) {
                    for (DialogueAction action : choice.actions()) {
                        collectActionError(dialogue, node, action, questIds, dialogueIds, errors, suggested);
                    }
                }
            }
        }
        return new CrossCheck(List.copyOf(errors), suggested);
    }

    private void collectActionError(DialogueDefinition dialogue, DialogueNode node, DialogueAction action,
                                    Set<String> questIds, Set<String> dialogueIds,
                                    List<String> errors, Set<ReloadFamily> suggested) {
        if (action instanceof StartQuestAction start && !questIds.contains(start.questId().toString())) {
            errors.add("Dialogue « " + dialogue.id() + " » nœud « " + node.id()
                    + " » : démarre une quête inconnue « " + start.questId() + " ».");
            suggested.add(ReloadFamily.QUESTS);
        } else if (action instanceof AdvanceQuestAction advance
                && !questIds.contains(advance.questId().toString())) {
            errors.add("Dialogue « " + dialogue.id() + " » nœud « " + node.id()
                    + " » : fait avancer une quête inconnue « " + advance.questId() + " ».");
            suggested.add(ReloadFamily.QUESTS);
        } else if (action instanceof OpenDialogueAction open
                && !dialogueIds.contains(open.dialogueId().toString())) {
            errors.add("Dialogue « " + dialogue.id() + " » nœud « " + node.id()
                    + " » : ouvre un dialogue inconnu « " + open.dialogueId() + " ».");
            suggested.add(ReloadFamily.DIALOGUES);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> resolve(Map<ReloadFamily, Candidate> candidates, ReloadFamily family,
                                Class<T> type, List<T> current) {
        Candidate candidate = candidates.get(family);
        if (candidate == null) {
            return current; // famille non rechargée : son état réel fait foi
        }
        return List.copyOf((Collection<T>) candidate.loaded());
    }

    /** Une clé sans namespace vaut {@code rpgquest:<clé>}, comme partout ailleurs. */
    private static String normalizeKey(String raw) {
        String value = raw.trim();
        return value.contains(":") ? value : "rpgquest:" + value;
    }

    // ---- Empreinte du runtime --------------------------------------------------------------

    /**
     * Signature déterministe du runtime : identifiants triés, famille par famille. Deux serveurs
     * chargés sur le même contenu produisent la même empreinte, ce qui permet au panel de
     * <strong>constater</strong> un changement plutôt que de le supposer.
     */
    private String currentRuntimeSignature() {
        StringBuilder sb = new StringBuilder();
        append(sb, ReloadFamily.ITEMS, itemRegistry.items().stream()
                .map(CustomItemDefinition::id).map(NamespacedKey::toString).toList());
        append(sb, ReloadFamily.NPCS, npcEngine.definitions().stream().map(NpcDefinition::id).toList());
        append(sb, ReloadFamily.QUESTS, questEngine.quests().stream()
                .map(QuestDefinition::id).map(NamespacedKey::toString).toList());
        append(sb, ReloadFamily.STORIES, storyRegistry.stories().stream()
                .map(StoryDefinition::id).toList());
        append(sb, ReloadFamily.DIALOGUES, dialogueEngine.dialogues().stream()
                .map(DialogueDefinition::id).map(NamespacedKey::toString).toList());
        append(sb, ReloadFamily.MOBS, mobRegistry.definitions().stream()
                .map(SpecialMobDefinition::id).map(NamespacedKey::toString).toList());
        return sb.toString();
    }

    private static void append(StringBuilder sb, ReloadFamily family, Collection<String> ids) {
        sb.append(family.wire()).append('=');
        sb.append(String.join(",", new TreeSet<>(ids)));
        sb.append(';');
    }

    /** Empreinte courte et stable (12 caractères hexadécimaux de SHA-256). */
    private static String hash(String signature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(signature.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est garanti par la plateforme ; ce chemin n'est pas atteignable.
            return "unavailable";
        }
    }

    private static long elapsed(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static int totalOf(List<FamilyOutcome> outcomes) {
        return outcomes.stream().mapToInt(FamilyOutcome::loaded).sum();
    }

    private static String labels(Collection<ReloadFamily> families) {
        return String.join(", ", families.stream().map(ReloadFamily::label).toList());
    }
}
