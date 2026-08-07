package com.lodygames.rpgquest.store;

import com.lodygames.rpgquest.backpack.model.BackpackSize;
import com.lodygames.rpgquest.store.model.StoreGrantType;
import com.lodygames.rpgquest.store.model.StoreProductDefinition;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Charge les définitions de produits (ce qu'ils accordent) depuis {@code
 * plugins/RPGQuest/store-products/*.yml}. Un fichier invalide n'empêche pas
 * le chargement des autres ; les id dupliqués entre fichiers sont rejetés
 * dans une seconde passe — même conception que {@code
 * mob.SpecialMobLoader}.
 */
public final class StoreProductLoader {

    public StoreProductLoadReport loadDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return new StoreProductLoadReport(List.of(), List.of());
        }

        List<Path> files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, this::isYamlFile)) {
            files = new ArrayList<>();
            stream.forEach(files::add);
        } catch (IOException e) {
            return new StoreProductLoadReport(List.of(), List.of(new StoreProductLoadIssue(
                    directory.toString(), "Impossible de lister le dossier de produits : " + e.getMessage())));
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<StoreProductDefinition> loaded = new ArrayList<>();
        List<StoreProductLoadIssue> issues = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (Path file : files) {
            String name = file.getFileName().toString();
            ConfigurationSection section;
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file.toFile());
                section = yaml;
            } catch (IOException | InvalidConfigurationException e) {
                issues.add(new StoreProductLoadIssue(name, "Fichier YAML invalide : " + e.getMessage()));
                continue;
            }

            try {
                StoreProductDefinition definition = parse(section);
                if (!seenIds.add(definition.id())) {
                    issues.add(new StoreProductLoadIssue(name, "Identifiant de produit dupliqué : \"" + definition.id() + "\"."));
                    continue;
                }
                loaded.add(definition);
            } catch (IllegalArgumentException e) {
                issues.add(new StoreProductLoadIssue(name, e.getMessage()));
            }
        }

        return new StoreProductLoadReport(loaded, issues);
    }

    private StoreProductDefinition parse(ConfigurationSection section) {
        String id = section.getString("id", "");
        String rawGrantType = section.getString("grant-type", "");
        StoreGrantType grantType;
        try {
            grantType = StoreGrantType.valueOf(rawGrantType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "« grant-type » invalide : \"" + rawGrantType + "\" (valides : BACKPACK_SIZE, ENTITLEMENT).");
        }

        BackpackSize backpackSize = null;
        if (grantType == StoreGrantType.BACKPACK_SIZE) {
            String rawSize = section.getString("backpack-size", "");
            try {
                backpackSize = BackpackSize.valueOf(rawSize.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "« backpack-size » invalide : \"" + rawSize + "\" (valides : SMALL, MEDIUM, LARGE).");
            }
        }

        String entitlementKey = grantType == StoreGrantType.ENTITLEMENT ? section.getString("entitlement-key", "") : null;
        String entitlementTier = grantType == StoreGrantType.ENTITLEMENT ? section.getString("entitlement-tier", "") : null;

        return new StoreProductDefinition(id, grantType, backpackSize, entitlementKey, entitlementTier);
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }
}
