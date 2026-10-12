package com.lodygames.rpgquest.panel.ai;

import java.time.Duration;
import java.util.List;

/**
 * Appelle une IA pour obtenir un plan de bâtiment (issue #94).
 *
 * <h2>Cette classe n'écrit rien, et ne valide pas le plan</h2>
 *
 * <p>Même discipline que {@link AiContentStudio} : la chaîne s'arrête à la réponse extraite.
 * {@code FORMULAIRE → IA → RÉPONSE → EXTRACTION}. Aucune écriture, aucun effet de bord, et donc
 * aucune génération possible sans un second geste humain explicite.</p>
 *
 * <p><strong>Et volontairement, aucune validation du plan ici.</strong> Le panel n'embarque
 * aucune bibliothèque YAML — c'est un choix d'isolation du module, pas un oubli — et surtout le
 * plugin est le <em>validateur autoritaire</em> : lui seul connaît les bornes du moteur, le
 * vocabulaire des instructions et la bibliothèque existante. Dupliquer ces règles ici garantirait
 * seulement que les deux finissent par divergier, et ferait passer pour valide un plan que le
 * serveur refuse. L'administrateur voit donc le plan brut, l'envoie, et obtient un refus
 * <em>précis</em> du serveur s'il y a lieu — avec le rang de l'instruction fautive.</p>
 */
public final class AiBuildingStudio {

    private final AiProviderRegistry registry;
    private final AiSettingsStore settings;

    public AiBuildingStudio(AiProviderRegistry registry, AiSettingsStore settings) {
        this.registry = registry;
        this.settings = settings;
    }

    /**
     * Le résultat d'un appel, prêt à être affiché.
     *
     * @param plan           le plan YAML extrait, ou {@code null} si rien d'exploitable
     * @param extractionNote ce qu'il a fallu faire pour isoler le document ; {@code null} si la
     *                       réponse était conforme à la consigne
     * @param error          échec de l'appel ; {@code null} si l'appel a abouti
     */
    public record Proposal(String providerId, String providerLabel, String model, String usage,
                           String rawResponse, String plan, String extractionNote, String error) {

        public boolean callOk() {
            return error == null;
        }

        /** Y a-t-il un plan à soumettre au serveur ? */
        public boolean hasPlan() {
            return plan != null && !plan.isBlank();
        }
    }

    /** Les fournisseurs réellement utilisables : activés et dotés d'une clé. */
    public List<AiProvider> usableProviders() {
        return registry.all().stream().filter(p -> settings.get(p.id()).usable()).toList();
    }

    /** Demande un plan. Ne lève jamais : un échec devient un {@link Proposal} en erreur. */
    public Proposal propose(String providerId, BuildingPromptBuilder.Demand demand) {
        return propose(providerId, demand, List.of());
    }

    /**
     * Demande un plan en montrant au modèle les images de référence du projet (issue #283) : leurs
     * pixels partent avec la demande, et le prompt précise qu'elles inspirent le style sans jamais
     * imposer une conversion bloc pour pixel.
     */
    public Proposal propose(String providerId, BuildingPromptBuilder.Demand demand,
                            List<AiProvider.Image> images) {
        return run(providerId, BuildingPromptBuilder.withReferences(
                BuildingPromptBuilder.userPrompt(demand), images.size()), images);
    }

    /** Redemande un plan corrigé, en montrant au modèle le refus réel du serveur. */
    public Proposal correct(String providerId, BuildingPromptBuilder.Demand demand,
                            String previousPlan, String refusal) {
        return correct(providerId, demand, previousPlan, refusal, "");
    }

    /**
     * Même chose, en joignant les <strong>mesures</strong> prises par le serveur sur la grille.
     *
     * <p>C'est la pièce la plus efficace de la correction : un modèle est volontiers convaincu
     * d'avoir posé des fenêtres, et « cellules vitrées : 0 » le détrompe sans discussion.</p>
     */
    public Proposal correct(String providerId, BuildingPromptBuilder.Demand demand,
                            String previousPlan, String refusal, String metrics) {
        return correct(providerId, demand, previousPlan, refusal, metrics, List.of());
    }

    public Proposal correct(String providerId, BuildingPromptBuilder.Demand demand,
                            String previousPlan, String refusal, String metrics,
                            List<AiProvider.Image> images) {
        return run(providerId, BuildingPromptBuilder.withReferences(
                BuildingPromptBuilder.correctionPrompt(demand, previousPlan, refusal, metrics),
                images.size()), images);
    }

    /** La passe de critique architecturale d'un plan déjà conforme (issue #282). */
    public Proposal critique(String providerId, BuildingPromptBuilder.Demand demand, String plan,
                             String metrics, String warnings) {
        return critique(providerId, demand, plan, metrics, warnings, List.of());
    }

    public Proposal critique(String providerId, BuildingPromptBuilder.Demand demand, String plan,
                             String metrics, String warnings, List<AiProvider.Image> images) {
        return run(providerId, BuildingPromptBuilder.withReferences(
                BuildingPromptBuilder.critiquePrompt(demand, plan, metrics, warnings), images.size()),
                images);
    }

    private Proposal run(String providerId, String userPrompt) {
        return run(providerId, userPrompt, List.of());
    }

    private Proposal run(String providerId, String userPrompt, List<AiProvider.Image> images) {
        AiProvider provider = registry.byId(providerId).orElse(null);
        if (provider == null) {
            return failed(providerId, providerId, "Fournisseur inconnu : « " + providerId + " ».");
        }
        AiProviderSettings config = settings.get(provider.id());
        if (!config.enabled()) {
            return failed(provider.id(), provider.label(),
                    "Le fournisseur « " + provider.label() + " » est désactivé.");
        }
        if (!config.hasKey()) {
            return failed(provider.id(), provider.label(),
                    "Aucune clé API enregistrée pour « " + provider.label() + " ».");
        }

        AiProvider.Request call = new AiProvider.Request(BuildingPromptBuilder.systemPrompt(),
                userPrompt, config.maxOutputTokens(),
                Duration.ofSeconds(config.timeoutSeconds()), images);
        AiProvider.Result result = provider.generate(call, config);
        if (!result.ok()) {
            return failed(provider.id(), provider.label(), result.error());
        }

        // Le même extracteur que l'atelier de contenu : un modèle qui encadre son YAML d'un bloc de
        // code ou d'une phrase d'introduction est une banalité, et la traiter deux fois
        // différemment produirait deux comportements à expliquer.
        AiYamlExtractor.Extraction extraction = AiYamlExtractor.extract(result.text());
        return new Proposal(provider.id(), provider.label(), result.model(), result.usage(),
                result.text(), extraction.ok() ? extraction.yaml() : null,
                extraction.note(), null);
    }

    private static Proposal failed(String id, String label, String error) {
        return new Proposal(id, label, null, null, null, null, null,
                error == null ? "Échec non précisé." : error);
    }
}
