package com.lodygames.rpgquest.panel.ai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                           String rawResponse, String plan, String extractionNote, String error,
                           AiProvider.Exchange exchange, Map<String, Object> manifest) {

        public Proposal {
            manifest = manifest == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(manifest));
        }

        /** Forme antérieure à #739 : sans trace de l'échange ni inventaire de la demande. */
        public Proposal(String providerId, String providerLabel, String model, String usage,
                        String rawResponse, String plan, String extractionNote, String error) {
            this(providerId, providerLabel, model, usage, rawResponse, plan, extractionNote, error, null, null);
        }

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
        String user = BuildingPromptBuilder.withReferences(BuildingPromptBuilder.userPrompt(demand), images.size());
        return run(providerId, user, images, manifest("PROPOSITION", BuildingPromptBuilder.systemPrompt(), user,
                demand, null, null, null, null, images));
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
        String user = BuildingPromptBuilder.withReferences(
                BuildingPromptBuilder.correctionPrompt(demand, previousPlan, refusal, metrics), images.size());
        return run(providerId, user, images, manifest("CORRECTION", BuildingPromptBuilder.systemPrompt(), user,
                demand, previousPlan, refusal, metrics, null, images));
    }

    /** La passe de critique architecturale d'un plan déjà conforme (issue #282). */
    public Proposal critique(String providerId, BuildingPromptBuilder.Demand demand, String plan,
                             String metrics, String warnings) {
        return critique(providerId, demand, plan, metrics, warnings, List.of());
    }

    public Proposal critique(String providerId, BuildingPromptBuilder.Demand demand, String plan,
                             String metrics, String warnings, List<AiProvider.Image> images) {
        String user = BuildingPromptBuilder.withReferences(
                BuildingPromptBuilder.critiquePrompt(demand, plan, metrics, warnings), images.size());
        return run(providerId, user, images, manifest("CRITIQUE", BuildingPromptBuilder.systemPrompt(), user,
                demand, plan, null, metrics, warnings, images));
    }

    /**
     * L'étape 1 (#289) : la conception détaillée, en texte. Aucun plan n'est extrait — la réponse
     * entière EST la conception, rangée dans {@link Proposal#rawResponse()}.
     */
    public Proposal design(String providerId, BuildingPromptBuilder.Demand demand,
                           List<AiProvider.Image> images) {
        String system = BuildingPromptBuilder.designSystemPrompt();
        String user = BuildingPromptBuilder.withReferences(BuildingPromptBuilder.designUserPrompt(demand),
                images.size());
        return run(providerId, system, user, images, false, manifest("CONCEPTION_DETAILLEE", system, user, demand,
                null, null, null, null, images));
    }

    private Proposal run(String providerId, String userPrompt, List<AiProvider.Image> images,
                         Map<String, Object> manifest) {
        return run(providerId, BuildingPromptBuilder.systemPrompt(), userPrompt, images, true, manifest);
    }

    /**
     * L'inventaire de ce qui compose la demande (#739) : quelles pièces de contexte sont parties,
     * leur taille, leur empreinte, et si elles figurent <em>réellement</em> dans le texte envoyé —
     * vérifié ici sur le texte final, pas déclaré. Complété par {@link #run} une fois le texte bâti.
     */
    private static Map<String, Object> manifest(String operation, String system, String user,
                                                BuildingPromptBuilder.Demand demand, String plan,
                                                String refusal, String metrics, String warnings,
                                                List<AiProvider.Image> images) {
        String sent = system + "\n" + user;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", operation);
        m.put("systemPrompt", piece(system, null));
        m.put("userPrompt", piece(user, null));
        m.put("projectId", nz(demand.siteId()));
        m.put("projectName", nz(demand.siteName()));
        m.put("buildingId", nz(demand.buildingId()));
        m.put("facing", nz(demand.facing()));
        m.put("maxWidth", demand.maxWidth());
        m.put("maxDepth", demand.maxDepth());
        m.put("maxHeight", demand.maxHeight());
        m.put("brief", piece(demand.brief(), sent));
        m.put("design", piece(demand.design(), sent));
        if (plan != null) {
            m.put("previousPlan", piece(plan, sent));
        }
        if (refusal != null) {
            m.put("refusal", piece(refusal, sent));
        }
        if (metrics != null) {
            m.put("metrics", piece(metrics, sent));
        }
        if (warnings != null) {
            m.put("warnings", piece(warnings, sent));
        }
        List<Object> pics = new java.util.ArrayList<>();
        for (AiProvider.Image image : images) {
            byte[] data = image.data();
            pics.add(Map.of("mime", image.mime(), "bytes", data.length,
                    "sha256", AiTrace.sha256(data).substring(0, 16)));
        }
        m.put("images", pics);
        return m;
    }

    /**
     * Taille et empreinte d'une pièce de contexte, et — si {@code sent} est donné — sa présence mot
     * pour mot dans le texte réellement envoyé : « non » signale une pièce perdue ou réduite.
     */
    private static Map<String, Object> piece(String text, String sent) {
        String t = text == null ? "" : text.trim();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("chars", t.length());
        p.put("sha256", t.isEmpty() ? "" : AiTrace.sha256(t).substring(0, 16));
        if (sent != null) {
            p.put("presentInRequest", t.isEmpty() ? "vide" : sent.contains(t) ? "oui" : "non");
        }
        return p;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Proposal run(String providerId, String systemPrompt, String userPrompt,
                         List<AiProvider.Image> images, boolean expectPlan, Map<String, Object> manifest) {
        AiProvider provider = registry.byId(providerId).orElse(null);
        if (provider == null) {
            return failed(providerId, providerId, "Fournisseur inconnu : « " + providerId + " ».", null, manifest);
        }
        AiProviderSettings config = settings.get(provider.id());
        if (!config.enabled()) {
            return failed(provider.id(), provider.label(),
                    "Le fournisseur « " + provider.label() + " » est désactivé.", null, manifest);
        }
        if (!config.hasKey()) {
            return failed(provider.id(), provider.label(),
                    "Aucune clé API enregistrée pour « " + provider.label() + " ».", null, manifest);
        }

        AiProvider.Request call = new AiProvider.Request(systemPrompt,
                userPrompt, config.maxOutputTokens(),
                Duration.ofSeconds(config.timeoutSeconds()), images);
        Map<String, Object> m = new LinkedHashMap<>(manifest);
        m.put("provider", provider.id());
        m.put("configuredModel", config.model().isEmpty() ? provider.defaultModel() + " (défaut du fournisseur)"
                : config.model());
        m.put("maxOutputTokens", call.maxOutputTokens());
        m.put("timeoutSeconds", config.timeoutSeconds());
        AiProvider.Result result = provider.generate(call, config);
        if (!result.ok()) {
            return failed(provider.id(), provider.label(), result.error(), result.exchange(), m);
        }

        // Le même extracteur que l'atelier de contenu : un modèle qui encadre son YAML d'un bloc de
        // code ou d'une phrase d'introduction est une banalité, et la traiter deux fois
        // différemment produirait deux comportements à expliquer.
        if (!expectPlan) {
            return new Proposal(provider.id(), provider.label(), result.model(), result.usage(),
                    result.text(), null, null, null, result.exchange(), m);
        }
        AiYamlExtractor.Extraction extraction = AiYamlExtractor.extract(result.text());
        return new Proposal(provider.id(), provider.label(), result.model(), result.usage(),
                result.text(), extraction.ok() ? extraction.yaml() : null,
                extraction.note(), null, result.exchange(), m);
    }

    private static Proposal failed(String id, String label, String error, AiProvider.Exchange exchange,
                                   Map<String, Object> manifest) {
        return new Proposal(id, label, null, null, null, null, null,
                error == null ? "Échec non précisé." : error, exchange, manifest);
    }
}
