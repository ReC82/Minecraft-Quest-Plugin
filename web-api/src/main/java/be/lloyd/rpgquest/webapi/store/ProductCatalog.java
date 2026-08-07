package be.lloyd.rpgquest.webapi.store;

import be.lloyd.rpgquest.webapi.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogue de produits, chargé une fois au démarrage depuis
 * {@code products.json} (voir {@code web-api/products.json.example}) —
 * jamais rechargé à chaud (un redémarrage du processus suffit, le catalogue
 * change rarement).
 */
public final class ProductCatalog {

    private final Map<String, Product> productsById;

    private ProductCatalog(Map<String, Product> productsById) {
        this.productsById = productsById;
    }

    public static ProductCatalog load(Path productsFile) throws IOException {
        if (!Files.exists(productsFile)) {
            return new ProductCatalog(Map.of());
        }
        String content = Files.readString(productsFile, StandardCharsets.UTF_8);
        Object parsed = Json.parse(content);
        if (!(parsed instanceof List<?> rawList)) {
            throw new IllegalArgumentException("products.json doit contenir un tableau JSON à la racine.");
        }

        Map<String, Product> byId = new LinkedHashMap<>();
        for (Object rawEntry : rawList) {
            if (!(rawEntry instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Chaque produit doit être un objet JSON.");
            }
            String id = requireString(raw, "id");
            String name = requireString(raw, "name");
            long priceCents = requireLong(raw, "priceCents");
            String currency = requireString(raw, "currency");
            if (priceCents <= 0) {
                throw new IllegalArgumentException("Produit \"" + id + "\" : priceCents doit être strictement positif.");
            }
            if (byId.containsKey(id)) {
                throw new IllegalArgumentException("Identifiant de produit dupliqué : \"" + id + "\".");
            }
            byId.put(id, new Product(id, name, priceCents, currency));
        }
        return new ProductCatalog(Map.copyOf(byId));
    }

    private static String requireString(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Champ \"" + key + "\" manquant ou vide dans products.json.");
        }
        return text;
    }

    private static long requireLong(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Champ \"" + key + "\" manquant ou non numérique dans products.json.");
        }
        return number.longValue();
    }

    public Optional<Product> find(String id) {
        return Optional.ofNullable(productsById.get(id));
    }

    public List<Product> all() {
        return List.copyOf(productsById.values());
    }
}
