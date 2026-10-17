package com.lodygames.rpgquest.economy.price;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Le catalogue des prix (issue #336), par clé, dans l'ordre du fichier. */
public record PriceCatalog(Map<String, PriceDefinition> prices) {

    public static final PriceCatalog EMPTY = new PriceCatalog(Map.of());

    public PriceCatalog {
        prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices));
    }

    /** Prix utilisable par un échange : présent et activé. Absent ou désactivé = aucun prix, jamais un défaut. */
    public Optional<PriceDefinition> usable(String key) {
        PriceDefinition price = prices.get(key);
        return price != null && price.enabled() ? Optional.of(price) : Optional.empty();
    }
}
