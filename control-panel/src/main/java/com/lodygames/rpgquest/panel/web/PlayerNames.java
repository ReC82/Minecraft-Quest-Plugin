package com.lodygames.rpgquest.panel.web;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Annuaire UUID → pseudo connu (retour propriétaire du 22/10 sur #93) : l'historique des actions et
 * les notifications montrent le <strong>pseudo</strong> d'un joueur ciblé, l'UUID restant en
 * secondaire. Pur et sans état partagé : construit à partir d'un relevé {@code player.catalog} déjà
 * présent dans la base du panel — jamais de requête vers le serveur à l'affichage.
 */
public final class PlayerNames {

    public static final PlayerNames EMPTY = new PlayerNames(Map.of());

    private final Map<String, String> byUuid;

    private PlayerNames(Map<String, String> byUuid) {
        this.byUuid = Map.copyOf(byUuid);
    }

    /** Depuis les {@code details} d'un relevé {@code player.catalog} ({@code players: [{uuid, name}]}). */
    public static PlayerNames fromCatalogDetails(Map<String, Object> details) {
        if (details == null) {
            return EMPTY;
        }
        Map<String, String> out = new HashMap<>();
        for (PlayerCatalog.Entry e : PlayerCatalog.parse(details.get("players"))) {
            String name = e.name();
            if (name != null && !name.isBlank() && !"null".equals(name)) {
                out.put(e.uuid().toLowerCase(Locale.ROOT), name.trim());
            }
        }
        return out.isEmpty() ? EMPTY : new PlayerNames(out);
    }

    /** Le pseudo connu d'un UUID ; vide si la référence n'est pas un UUID connu (ou est déjà un pseudo). */
    public Optional<String> nameOf(String ref) {
        return ref == null ? Optional.empty() : Optional.ofNullable(byUuid.get(ref.trim().toLowerCase(Locale.ROOT)));
    }
}
