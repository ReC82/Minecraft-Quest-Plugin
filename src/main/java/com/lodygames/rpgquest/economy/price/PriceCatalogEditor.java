package com.lodygames.rpgquest.economy.price;

import com.lodygames.rpgquest.economy.price.PriceDefinition.PriceItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

/**
 * Administration du catalogue {@code prices.yml} depuis PlugAdmin (issue #336) : créer, modifier,
 * dupliquer, activer / désactiver un prix. Garanties de {@link PriceCatalogFile} (fichier actuel
 * valide, relecture par le vrai chargeur, écriture atomique, restauration). Chaque opération ne
 * touche que sa clé : les autres prix et les commentaires sont conservés. Le premier enregistrement
 * crée le fichier.
 *
 * <p>Pas de suppression : un prix peut être cité par des marchands ; on le désactive (l'offre est
 * alors affichée « indisponible »). Une copie est créée désactivée. Appelé hors du thread principal.</p>
 */
public final class PriceCatalogEditor {

    /** Résultat : {@code code} stable pour PlugAdmin, {@code message} en français. */
    public record Outcome(boolean success, String code, String message, @Nullable PriceDefinition price) {

        static Outcome refused(String code, String message) {
            return new Outcome(false, code, message, null);
        }
    }

    private static final int LABEL_SUFFIX_ROOM = PriceCatalogLoader.LABEL_MAX - " (copie)".length();

    private final PriceCatalogFile file;

    PriceCatalogEditor(PriceCatalogFile file) {
        this.file = file;
    }

    /**
     * Crée ({@code create}) ou remplace un prix.
     *
     * @param expectedVersion version lue par le formulaire ({@code null} = pas de contrôle)
     */
    public Outcome savePrice(PriceDefinition wanted, @Nullable String expectedVersion, boolean create) {
        synchronized (file) {
            PriceCatalogFile.Current current;
            try {
                current = file.read();
            } catch (PriceCatalogFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            PriceDefinition existing = current.catalog().prices().get(wanted.key());
            if (create) {
                Outcome refused = refuseNewKey(current.catalog(), wanted.key());
                if (refused != null) {
                    return refused;
                }
            } else if (existing == null) {
                return Outcome.refused("NOT_FOUND", "Prix « " + wanted.key() + " » introuvable : recharger la page.");
            }
            if (existing != null && expectedVersion != null && !expectedVersion.isBlank()
                    && !existing.version().equals(expectedVersion)) {
                return Outcome.refused("STALE", "Le prix « " + wanted.key() + " » a changé depuis l'ouverture du "
                        + "formulaire : rien n'a été écrit. Recharger la page puis refaire la modification.");
            }
            if (wanted.equals(existing)) {
                return new Outcome(true, "UNCHANGED", "Aucune modification : le prix « " + wanted.label()
                        + " » est déjà enregistré ainsi.", existing);
            }
            return write(current, wanted, create ? "CREATED" : "UPDATED", "Prix « " + wanted.label() + " » "
                    + (create ? "créé" : "modifié") + " : " + wanted.summary()
                    + (wanted.enabled() ? "." : " (désactivé : les offres qui le citent restent indisponibles).")
                    + (create ? "" : " Les vitrines déjà ouvertes devront être rouvertes."));
        }
    }

    /** Copie {@code sourceKey} sous {@code newKey}, <strong>désactivée</strong>. */
    public Outcome duplicatePrice(String sourceKey, String newKey, @Nullable String newLabel) {
        synchronized (file) {
            PriceCatalogFile.Current current;
            try {
                current = file.read();
            } catch (PriceCatalogFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            PriceDefinition source = current.catalog().prices().get(sourceKey);
            if (source == null) {
                return Outcome.refused("NOT_FOUND", "Prix « " + sourceKey + " » introuvable : recharger la page.");
            }
            Outcome refused = refuseNewKey(current.catalog(), newKey);
            if (refused != null) {
                return refused;
            }
            String label = newLabel == null || newLabel.isBlank()
                    ? truncate(source.label(), LABEL_SUFFIX_ROOM) + " (copie)" : newLabel.trim();
            return write(current, source.with(newKey, label, false), "DUPLICATED", "Copie « " + label + " » créée, "
                    + "désactivée : la modifier, puis l'activer. Aucun marchand ne la cite encore.");
        }
    }

    /** Active ou désactive un prix ; son contenu ne change pas. */
    public Outcome setPriceEnabled(String key, boolean enabled) {
        synchronized (file) {
            PriceCatalogFile.Current current;
            try {
                current = file.read();
            } catch (PriceCatalogFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            PriceDefinition existing = current.catalog().prices().get(key);
            if (existing == null) {
                return Outcome.refused("NOT_FOUND", "Prix « " + key + " » introuvable : recharger la page.");
            }
            if (existing.enabled() == enabled) {
                return new Outcome(true, "UNCHANGED", "Le prix « " + existing.label() + " » est déjà "
                        + (enabled ? "actif" : "désactivé") + ".", existing);
            }
            return write(current, existing.with(key, existing.label(), enabled), "TOGGLED", enabled
                    ? "Prix « " + existing.label() + " » activé : les offres qui le citent s'échangent de nouveau."
                    : "Prix « " + existing.label() + " » désactivé : les offres qui le citent sont affichées "
                    + "« indisponible » et ne s'échangent plus. Aucun solde ni objet n'est touché.");
        }
    }

    private static @Nullable Outcome refuseNewKey(PriceCatalog catalog, String key) {
        if (!PriceCatalogLoader.KEY.matcher(key).matches()) {
            return Outcome.refused("INVALID", "Identifiant « " + key + " » invalide : minuscules, chiffres, _ ou - "
                    + "(64 caractères au plus), ex. pain_x4.");
        }
        if (PriceCatalogLoader.RESERVED_KEYS.contains(key)) {
            return Outcome.refused("INVALID", "« " + key + " » ne peut pas servir d'identifiant (YAML le lirait comme "
                    + "oui/non) : en choisir un autre, par exemple « " + key + "_prix ».");
        }
        if (catalog.prices().containsKey(key)) {
            return Outcome.refused("EXISTS", "Un prix « " + key + " » existe déjà : choisir un autre identifiant, ou "
                    + "modifier celui-ci.");
        }
        if (catalog.prices().size() >= PriceCatalogLoader.MAX_PRICES) {
            return Outcome.refused("INVALID", PriceCatalogLoader.MAX_PRICES + " prix au plus.");
        }
        return null;
    }

    private Outcome write(PriceCatalogFile.Current current, PriceDefinition price, String code, String message) {
        String text;
        try {
            text = render(current.yaml(), price);
        } catch (RuntimeException e) {
            return Outcome.refused("INVALID", "Prix « " + price.key() + " » impossible à écrire : " + e.getMessage());
        }
        if (current.bytes() == null) {
            text = PriceCatalogFile.HEADER + text;
        }
        Map<String, PriceDefinition> prices = new LinkedHashMap<>(current.catalog().prices());
        prices.put(price.key(), price);
        PriceCatalogFile.Failure failure = file.write(current.bytes(), text, new PriceCatalog(prices),
                "prix " + price.key());
        if (failure != null) {
            return Outcome.refused(failure.code(), failure.message());
        }
        file.logger().info("Prix : « {} » v{} écrit depuis PlugAdmin ({}) — {}{}.", price.key(), price.version(), code,
                price.summary(), price.enabled() ? "" : ", désactivé");
        return new Outcome(true, code, message, price);
    }

    /** Remplace (ou ajoute) {@code prices.<clé>} et rien d'autre ; l'ordre des prix est conservé. */
    static String render(YamlConfiguration yaml, PriceDefinition price) {
        ConfigurationSection prices = yaml.getConfigurationSection("prices");
        if (prices == null) {
            prices = yaml.createSection("prices");
        }
        ConfigurationSection section = prices.createSection(price.key());
        section.set("label", price.label());
        section.set("type", price.type().name());
        section.set("item", price.item().itemId());
        section.set("amount", price.item().amount());
        section.set("coins", price.coins());
        if (!price.ingredients().isEmpty()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (PriceItem ingredient : price.ingredients()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("item", ingredient.itemId());
                line.put("amount", ingredient.amount());
                lines.add(line);
            }
            section.set("ingredients", lines);
        }
        section.set("demo", price.demo());
        section.set("enabled", price.enabled());
        return yaml.saveToString();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim();
    }
}
