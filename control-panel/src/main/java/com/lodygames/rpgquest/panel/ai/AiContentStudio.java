package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.content.ContentPackImport;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.RefData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestration de l'atelier IA (issue #146) : la chaîne complète, sans aucun effet de bord, pour
 * les trois familles de contenu — quête, dialogue et story.
 *
 * <p>{@code FORMULAIRE → IA → RÉPONSE → EXTRACTION → PARSING → VALIDATION → DIAGNOSTICS → APERÇU}.
 * Cette classe s'arrête là, <strong>exprès</strong> : elle n'écrit jamais. La suite — diff,
 * arbitrage des collisions, confirmation, enregistrement source — est celle de l'import #109,
 * réutilisée telle quelle. Il n'existe donc qu'un seul chemin d'écriture dans tout le panel, déjà
 * couvert par ses propres tests, et l'IA n'en obtient aucun raccourci.</p>
 *
 * <p>C'est ce qui garantit la dernière exigence du ticket : <em>aucune publication ni modification
 * automatique sans approbation humaine</em>. Non parce qu'on s'en est souvenu à l'écran, mais parce
 * que le code qui appelle l'IA n'a pas de quoi écrire.</p>
 */
public final class AiContentStudio {

    private final AiProviderRegistry registry;
    private final AiSettingsStore settings;

    public AiContentStudio(AiProviderRegistry registry, AiSettingsStore settings) {
        this.registry = registry;
        this.settings = settings;
    }

    /**
     * Résultat d'une génération, prêt à être affiché.
     *
     * @param providerLabel fournisseur réellement utilisé
     * @param rawResponse   réponse brute du modèle, conservée pour le mode source et la correction
     * @param yaml          document extrait ({@code null} si rien d'exploitable)
     * @param extractionNote ce qui a dû être fait pour isoler le document, {@code null} si la réponse
     *                       était conforme à la consigne
     * @param analysis      analyse d'import du document ({@code null} si aucun document)
     * @param error         échec de l'appel ({@code null} si l'appel a abouti)
     */
    public record Generation(ContentPromptBuilder.Kind kind, String providerId, String providerLabel,
                             String model, String usage, String rawResponse, String yaml,
                             String extractionNote, ContentPackImport.Analysis analysis,
                             String error) {

        public boolean callOk() {
            return error == null;
        }

        /** Problèmes à renvoyer à l'IA pour correction — vide si le document est importable. */
        public List<String> problems() {
            List<String> out = new ArrayList<>();
            if (analysis == null) {
                if (error != null) {
                    out.add(error);
                }
                return out;
            }
            analysis.envelope().stream()
                    .filter(d -> d.level() == com.lodygames.rpgquest.panel.content.Diagnostic.Level.ERROR)
                    .forEach(d -> out.add("Enveloppe du pack : " + d.message()));
            for (ContentPackImport.Element e : analysis.elements()) {
                if (e.status() == ContentPackImport.Status.INVALID) {
                    out.add("Élément « " + e.key() + " » : " + e.reason());
                    e.diagnostics().stream()
                            .filter(d -> d.level() == com.lodygames.rpgquest.panel.content.Diagnostic.Level.ERROR)
                            .forEach(d -> out.add("  · " + d.field() + " — " + d.message()));
                }
            }
            return out;
        }

        public boolean correctable() {
            return yaml != null && !problems().isEmpty();
        }
    }

    /** Fournisseurs réellement utilisables : activés et pourvus d'une clé. */
    public List<AiProvider> usableProviders() {
        return registry.all().stream().filter(p -> settings.get(p.id()).usable()).toList();
    }

    /**
     * Génère une proposition de quête. Ne lève jamais, n'écrit jamais : un échec d'appel, un délai
     * dépassé ou une réponse illisible produisent une {@link Generation} en erreur, que l'écran
     * affiche — et rien d'autre ne se passe.
     */
    public Generation generateQuest(String providerId, ContentPromptBuilder.QuestRequest request,
                                    RefData refs, ContentWorkspace workspace) {
        return run(ContentPromptBuilder.Kind.QUEST, providerId,
                ContentPromptBuilder.userPrompt(request, refs), refs, workspace);
    }

    /**
     * Génère une proposition de dialogue. Mêmes garanties : aucune écriture, aucune exception, et le
     * document passe par les validateurs réels avant d'être montré.
     */
    public Generation generateDialogue(String providerId,
                                       ContentPromptBuilder.DialogueRequest request,
                                       RefData refs, ContentWorkspace workspace) {
        return run(ContentPromptBuilder.Kind.DIALOGUE, providerId,
                ContentPromptBuilder.userPrompt(request, refs), refs, workspace);
    }

    /** Génère une proposition de story — un enchaînement de quêtes qui, elles, existent déjà. */
    public Generation generateStory(String providerId, ContentPromptBuilder.StoryRequest request,
                                    RefData refs, ContentWorkspace workspace) {
        return run(ContentPromptBuilder.Kind.STORY, providerId,
                ContentPromptBuilder.userPrompt(request, refs), refs, workspace);
    }

    /**
     * Redemande une correction à partir de la sortie précédente et des diagnostics réels. L'IA voit
     * donc ce qui n'allait pas, au lieu de repartir de zéro — c'est ce qui rend le bouton utile.
     */
    public Generation correct(ContentPromptBuilder.Kind kind, String providerId, String previousYaml,
                              List<String> problems, RefData refs, ContentWorkspace workspace) {
        return run(kind, providerId,
                ContentPromptBuilder.correctionPrompt(kind, previousYaml, problems, refs),
                refs, workspace);
    }

    private Generation run(ContentPromptBuilder.Kind kind, String providerId, String userPrompt,
                           RefData refs, ContentWorkspace workspace) {
        AiProvider provider = registry.byId(providerId).orElse(null);
        if (provider == null) {
            return failed(kind, providerId, "Fournisseur inconnu : « " + providerId + " ».");
        }
        AiProviderSettings config = settings.get(provider.id());
        if (!config.enabled()) {
            return failed(kind, providerId,
                    "Le fournisseur « " + provider.label() + " » est désactivé.");
        }
        if (!config.hasKey()) {
            return failed(kind, providerId,
                    "Aucune clé API enregistrée pour « " + provider.label() + " ».");
        }

        AiProvider.Request call = new AiProvider.Request(ContentPromptBuilder.systemPrompt(kind),
                userPrompt,
                config.maxOutputTokens(), Duration.ofSeconds(config.timeoutSeconds()));
        AiProvider.Result result = provider.generate(call, config);
        if (!result.ok()) {
            return new Generation(kind, provider.id(), provider.label(), null, null, null, null,
                    null, null, result.error());
        }

        AiYamlExtractor.Extraction extraction = AiYamlExtractor.extract(result.text());
        ContentPackImport.Analysis analysis = extraction.ok()
                ? ContentPackImport.analyze(extraction.yaml(), workspace, refs, Map.of())
                : null;
        return new Generation(kind, provider.id(), provider.label(), result.model(), result.usage(),
                result.text(), extraction.yaml(), extraction.note(), analysis, null);
    }

    private Generation failed(ContentPromptBuilder.Kind kind, String providerId, String error) {
        String label = registry.byId(providerId).map(AiProvider::label).orElse(providerId);
        return new Generation(kind, providerId, label, null, null, null, null, null, null, error);
    }
}
