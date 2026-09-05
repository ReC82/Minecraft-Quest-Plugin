package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.slf4j.Logger;

/**
 * Implémentation {@code PluginService} de {@link MerchantRegistry} : charge
 * les définitions de marchands YAML depuis {@code merchantsDirectory}. Même
 * conception que {@code YamlCustomItemRegistry} : ne dépend que de
 * {@link Path}/{@link Logger}, l'exemple embarqué est lu depuis le classpath
 * du plugin, jamais écrasé si déjà présent au démarrage.
 */
public final class YamlMerchantRegistry implements MerchantRegistry {

    private static final String[] BUNDLED_EXAMPLES = {"village_merchant.yml"};

    private final Path merchantsDirectory;
    private final Logger logger;
    private final MerchantLoader loader = new MerchantLoader();

    private volatile List<MerchantDefinition> merchants = List.of();
    private volatile MerchantLoadReport lastReport = new MerchantLoadReport(List.of(), List.of());

    public YamlMerchantRegistry(Path merchantsDirectory, Logger logger) {
        this.merchantsDirectory = merchantsDirectory;
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

    /** Recharge depuis le disque et remplace l'ensemble de définitions actif. */
    public MerchantLoadReport reload() {
        MerchantLoadReport report = loader.loadDirectory(merchantsDirectory);
        this.merchants = report.loaded();
        this.lastReport = report;
        logReport("Chargement", report);
        return report;
    }

    /** Charge et valide depuis le disque sans toucher à l'ensemble actif (dry-run). */
    public MerchantLoadReport validate() {
        MerchantLoadReport report = loader.loadDirectory(merchantsDirectory);
        logReport("Validation", report);
        return report;
    }

    public List<MerchantDefinition> merchants() {
        return merchants;
    }

    public MerchantLoadReport lastReport() {
        return lastReport;
    }

    public Optional<MerchantDefinition> find(NamespacedKey id) {
        return merchants.stream().filter(merchant -> merchant.id().equals(id)).findFirst();
    }

    private void logReport(String action, MerchantLoadReport report) {
        logger.info("{} des marchands : {} chargé(s), {} erreur(s).",
                action, report.loaded().size(), report.issues().size());
        for (MerchantLoadIssue issue : report.issues()) {
            logger.warn("[{}] {}", issue.file(), issue.message());
        }
    }

    private void ensureExamplesExist() {
        try {
            Files.createDirectories(merchantsDirectory);
        } catch (IOException e) {
            logger.error("Impossible de créer le dossier de marchands {}.", merchantsDirectory, e);
            return;
        }

        for (String example : BUNDLED_EXAMPLES) {
            Path target = merchantsDirectory.resolve(example);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream in = YamlMerchantRegistry.class.getResourceAsStream("/merchants/" + example)) {
                if (in == null) {
                    logger.warn("Marchand d'exemple introuvable dans le jar : {}", example);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                logger.error("Impossible de générer le marchand d'exemple {}.", example, e);
            }
        }
    }
}
