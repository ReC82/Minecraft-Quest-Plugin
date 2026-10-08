package com.lodygames.rpgquest.panel.ai;

import java.util.List;
import java.util.Optional;

/**
 * Catalogue des fournisseurs d'IA disponibles (issue #146) — le <strong>seul</strong> endroit qui
 * connaît la liste.
 *
 * <p>Ajouter un fournisseur, c'est écrire une classe et l'inscrire ici : ni la page de
 * configuration, ni l'atelier, ni le stockage n'énumèrent quoi que ce soit. L'ordre de cette liste
 * est l'ordre d'affichage.</p>
 */
public final class AiProviderRegistry {

    private final List<AiProvider> providers;

    public AiProviderRegistry() {
        this(List.of(new AnthropicProvider(), new OpenAiProvider(), new GeminiProvider()));
    }

    /** Constructeur de test : permet d'injecter un fournisseur bouchon sans réseau. */
    public AiProviderRegistry(List<AiProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public List<AiProvider> all() {
        return providers;
    }

    public List<String> ids() {
        return providers.stream().map(AiProvider::id).toList();
    }

    public Optional<AiProvider> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.trim();
        return providers.stream().filter(p -> p.id().equals(wanted)).findFirst();
    }
}
