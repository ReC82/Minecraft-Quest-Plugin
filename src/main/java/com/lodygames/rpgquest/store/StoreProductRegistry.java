package com.lodygames.rpgquest.store;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.store.model.StoreProductDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Registre des produits de la boutique (ce qu'ils accordent en jeu — voir
 * {@link StoreProductDefinition}). Même conception que {@code
 * mob.SpecialMobRegistry} : exemples embarqués générés au premier démarrage,
 * jamais écrasés, id namespacés en chaîne libre (correspondant à
 * {@code web-api/products.json}).
 */
public final class StoreProductRegistry implements PluginService {

    private static final String[] BUNDLED_EXAMPLES = {
            "small_backpack.yml", "upgrade_medium.yml", "upgrade_large.yml", "vip_pass_test.yml", "cape_aurora.yml"
    };

    private final Path productsDirectory;
    private final Logger logger;
    private final StoreProductLoader loader = new StoreProductLoader();

    private volatile List<StoreProductDefinition> products = List.of();
    private volatile StoreProductLoadReport lastReport = new StoreProductLoadReport(List.of(), List.of());

    public StoreProductRegistry(Path productsDirectory, Logger logger) {
        this.productsDirectory = productsDirectory;
        this.logger = logger;
    }

    @Override
    public void start() {
        ensureExamplesExist();
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer : les définitions vivent en mémoire, pas de ressource externe.
    }

    public StoreProductLoadReport reload() {
        StoreProductLoadReport report = loader.loadDirectory(productsDirectory);
        this.products = report.loaded();
        this.lastReport = report;
        logger.info("Chargement des produits boutique : {} chargé(s), {} erreur(s).",
                report.loaded().size(), report.issues().size());
        for (StoreProductLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
        return report;
    }

    public List<StoreProductDefinition> products() {
        return products;
    }

    public StoreProductLoadReport lastReport() {
        return lastReport;
    }

    public Optional<StoreProductDefinition> find(String id) {
        return products.stream().filter(product -> product.id().equals(id)).findFirst();
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(productsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de produits boutique {}.", productsDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = productsDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = StoreProductRegistry.class.getResourceAsStream("/store-products/" + example)) {
                if (in == null) {
                    logger.warn("Produit boutique d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le produit boutique d'exemple {}.", example, e);
            }
        }
    }
}
