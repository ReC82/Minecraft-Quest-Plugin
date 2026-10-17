package com.lodygames.rpgquest.economy.price;

import com.lodygames.rpgquest.bootstrap.PluginService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Catalogue des prix et coûts (issue #336, {@code docs/ECONOMY.md}) : lit {@code prices.yml} au
 * démarrage et à chaque écriture depuis PlugAdmin. Fichier absent = catalogue vide (rien n'est
 * créé au démarrage) ; fichier refusé = catalogue vide et erreurs listées — aucune offre qui cite un
 * prix ne s'échange alors, jamais avec un montant par défaut.
 *
 * <p>{@link #generation()} change à chaque rechargement : une vitrine ouverte sur une génération
 * plus ancienne refuse le clic et se réaffiche, pour que le joueur paie toujours le prix qu'il voit.</p>
 */
public final class PriceCatalogService implements PluginService {

    private final Path file;
    private final Logger logger;
    private final PriceCatalogEditor editor;
    private final AtomicLong generation = new AtomicLong();

    private volatile PriceCatalog catalog = PriceCatalog.EMPTY;
    private volatile List<String> errors = List.of();
    private volatile List<String> warnings = List.of();

    public PriceCatalogService(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.editor = new PriceCatalogEditor(new PriceCatalogFile(file, this::reloadNow, logger));
    }

    @Override
    public void start() {
        reloadNow();
    }

    @Override
    public void stop() {
        // Rien à libérer : le catalogue vit en mémoire.
    }

    /** Relit le fichier et applique le résultat (catalogue vide si refusé). */
    public synchronized PriceCatalogLoader.Result reloadNow() {
        PriceCatalogLoader.Result result;
        if (!Files.isRegularFile(file)) {
            result = new PriceCatalogLoader.Result(PriceCatalog.EMPTY, List.of(), List.of());
        } else {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                result = PriceCatalogLoader.load(yaml);
            } catch (IOException | InvalidConfigurationException e) {
                result = new PriceCatalogLoader.Result(PriceCatalog.EMPTY,
                        List.of("prices.yml illisible : " + e.getMessage()), List.of());
            }
        }
        catalog = result.catalog();
        errors = result.errors();
        warnings = result.warnings();
        generation.incrementAndGet();
        if (result.valid()) {
            logger.info("Prix : {} prix chargé(s) depuis prices.yml{}.", catalog.prices().size(),
                    Files.isRegularFile(file) ? "" : " (fichier absent : aucun prix défini)");
        } else {
            logger.warn("Prix : prices.yml refusé, AUCUN prix appliqué ({} erreur(s)) : {}", errors.size(),
                    PriceCatalogFile.summary(errors));
        }
        warnings.forEach(w -> logger.warn("Prix : {}", w));
        return result;
    }

    public PriceCatalog catalog() {
        return catalog;
    }

    public List<String> lastErrors() {
        return errors;
    }

    public List<String> lastWarnings() {
        return warnings;
    }

    public long generation() {
        return generation.get();
    }

    public boolean fileExists() {
        return Files.isRegularFile(file);
    }

    public PriceCatalogEditor editor() {
        return editor;
    }
}
