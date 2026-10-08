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
 * <p>{@code FORMULAIRE → IA → RÉPONSE → EXTRACTION → PARSING → VALIDATION → CONTRÔLE DES
 * CONTRAINTES → DIAGNOSTICS → APERÇU}. Cette classe s'arrête là, <strong>exprès</strong> : elle
 * n'écrit jamais. La suite — diff, arbitrage des collisions, confirmation, enregistrement source —
 * est celle de l'import #109, réutilisée telle quelle. Il n'existe donc qu'un seul chemin d'écriture
 * dans tout le panel, déjà couvert par ses propres tests, et l'IA n'en obtient aucun
 * raccourci.</p>
 *
 * <p>C'est ce qui garantit la dernière exigence du ticket : <em>aucune publication ni modification
 * automatique sans approbation humaine</em>. Non parce qu'on s'en est souvenu à l'écran, mais parce
 * que le code qui appelle l'IA n'a pas de quoi écrire.</p>
 *
 * <p><strong>Deux refus distincts, et c'est voulu (issues #223, #224).</strong> Un document peut
 * être parfaitement valide pour le moteur sans être ce qui avait été demandé — cinq nœuds réclamés,
 * quatre produits ; un identifiant imposé, un autre inventé. Les validateurs ne peuvent pas le
 * savoir : eux ne voient que le document. Le contrôle des contraintes, lui, compare la proposition à
 * la demande, et bloque l'enregistrement tout aussi fermement.</p>
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
     * @param unmet         exigences du formulaire non tenues par la proposition (#223, #224)
     * @param error         échec de l'appel ({@code null} si l'appel a abouti)
     */
    public record Generation(ContentPromptBuilder.Kind kind, String providerId, String providerLabel,
                             String model, String usage, String rawResponse, String yaml,
                             String extractionNote, ContentPackImport.Analysis analysis,
                             List<String> unmet, String error) {

        public Generation {
            unmet = List.copyOf(unmet == null ? List.of() : unmet);
        }

        public boolean callOk() {
            return error == null;
        }

        /**
         * Problèmes à renvoyer à l'IA pour correction — vide si la proposition est importable
         * <em>et</em> conforme à la demande.
         *
         * <p>Les exigences non tenues viennent en tête : ce sont celles que l'IA doit corriger sans
         * défaire le reste, et les mettre en premier dans le prompt les rend bien plus
         * difficiles à ignorer.</p>
         */
        public List<String> problems() {
            List<String> out = new ArrayList<>(unmet);
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

        /**
         * La proposition peut-elle partir vers la page d'import ? Il faut qu'elle passe les
         * validateurs <strong>et</strong> qu'elle respecte ce qui avait été imposé.
         */
        public boolean acceptable() {
            return analysis != null && analysis.importable() && unmet.isEmpty();
        }

        public boolean correctable() {
            return yaml != null && !problems().isEmpty();
        }
    }

    /**
     * Ce qu'il faut pour relancer une correction, conservé entre deux requêtes (issue #222).
     *
     * <p>Ce contexte voyage dans le formulaire de la page, puis revient : c'est lui qui permet au
     * bouton de renvoyer la sortie précédente, les diagnostics exacts et la demande d'origine. Il
     * sert aussi de <strong>repli</strong> quand la correction elle-même échoue (réseau, 401,
     * délai) : la proposition précédente et ses diagnostics ne sont pas perdus, et le bouton reste
     * disponible au lieu de disparaître avec le seul message d'erreur.</p>
     *
     * @param request      la demande d'origine, rejouée à l'identique
     * @param providerId   fournisseur choisi, conservé tel quel — jamais redevine
     * @param previousYaml le document refusé
     * @param problems     les diagnostics réels qui l'ont fait refuser
     */
    public record Correction(ContentPromptBuilder.Demand request, String providerId,
                             String previousYaml, List<String> problems) {

        public Correction {
            problems = List.copyOf(problems == null ? List.of() : problems);
        }

        public boolean usable() {
            return request != null && previousYaml != null && !previousYaml.isBlank()
                    && !problems.isEmpty();
        }
    }

    /** Fournisseurs réellement utilisables : activés et pourvus d'une clé. */
    public List<AiProvider> usableProviders() {
        return registry.all().stream().filter(p -> settings.get(p.id()).usable()).toList();
    }

    /**
     * Génère une proposition, quelle que soit la famille. Ne lève jamais, n'écrit jamais : un échec
     * d'appel, un délai dépassé ou une réponse illisible produisent une {@link Generation} en
     * erreur, que l'écran affiche — et rien d'autre ne se passe.
     */
    public Generation generate(String providerId, ContentPromptBuilder.Demand request,
                               RefData refs, ContentWorkspace workspace) {
        return run(request, providerId, ContentPromptBuilder.userPrompt(request, refs), refs,
                workspace);
    }

    /**
     * Redemande une correction à partir de la sortie précédente, des diagnostics réels et de la
     * demande d'origine. L'IA voit donc ce qui n'allait pas <em>et</em> ce qui ne devait pas
     * changer — c'est ce qui rend le bouton utile.
     *
     * <p>Le fournisseur est celui du contexte, donc celui que l'administrateur avait choisi : une
     * correction ne change jamais de modèle en route.</p>
     */
    public Generation correct(Correction correction, RefData refs, ContentWorkspace workspace) {
        ContentPromptBuilder.Demand request = correction.request();
        return run(request, correction.providerId(),
                ContentPromptBuilder.correctionPrompt(request, correction.previousYaml(),
                        correction.problems(), refs),
                refs, workspace);
    }

    private Generation run(ContentPromptBuilder.Demand request, String providerId, String userPrompt,
                           RefData refs, ContentWorkspace workspace) {
        ContentPromptBuilder.Kind kind = request.kind();
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
                    null, null, List.of(), result.error());
        }

        AiYamlExtractor.Extraction extraction = AiYamlExtractor.extract(result.text());
        ContentPackImport.Analysis analysis = extraction.ok()
                ? ContentPackImport.analyze(extraction.yaml(), workspace, refs, Map.of())
                : null;
        List<String> unmet = extraction.ok()
                ? request.imposed().verify(kind, extraction.yaml())
                : List.of();
        return new Generation(kind, provider.id(), provider.label(), result.model(), result.usage(),
                result.text(), extraction.yaml(), extraction.note(), analysis, unmet, null);
    }

    private Generation failed(ContentPromptBuilder.Kind kind, String providerId, String error) {
        String label = registry.byId(providerId).map(AiProvider::label).orElse(providerId);
        return new Generation(kind, providerId, label, null, null, null, null, null, null,
                List.of(), error);
    }
}
