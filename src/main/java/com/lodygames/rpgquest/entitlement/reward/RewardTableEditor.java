package com.lodygames.rpgquest.entitlement.reward;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Écriture de la section {@code tables} de {@code entitlements.yml} depuis PlugAdmin (issue #177,
 * tranche 2). Garanties de {@link EntitlementsFile} (#461) : fichier actuel valide, relecture par le
 * vrai chargeur avant écriture, écriture atomique, restauration si le rechargement échoue.
 *
 * <p>Seule la section {@code tables} est modifiée ; le catalogue des récompenses et l'interrupteur
 * {@code enabled} ne sont jamais touchés (voir {@link RewardCatalogEditor}). Appelé hors du thread
 * principal (thread agent).</p>
 */
public final class RewardTableEditor {

    /**
     * Mots que YAML 1.1 lit comme des booléens : une quête qui écrirait {@code table: off} recevrait
     * {@code false}. Refusés comme identifiant d'une nouvelle table.
     */
    public static final java.util.Set<String> RESERVED_IDS =
            java.util.Set.of("y", "n", "yes", "no", "on", "off", "true", "false", "null");

    static final int LABEL_SUFFIX_ROOM = RewardCatalogLoader.LABEL_MAX - " (copie)".length();

    /** Résultat d'une écriture : {@code code} stable pour PlugAdmin, {@code message} en français. */
    public record Outcome(boolean success, String code, String message, @Nullable RewardTable table) {

        static Outcome refused(String code, String message) {
            return new Outcome(false, code, message, null);
        }
    }

    private final EntitlementsFile file;

    /**
     * @param reload relit le fichier et applique le catalogue (ex. {@link RewardEntitlementService#reloadNow()})
     */
    public RewardTableEditor(Path file, Supplier<RewardCatalogLoader.Result> reload, Logger logger) {
        this(new EntitlementsFile(file, reload, logger));
    }

    /** Partage le fichier (et son verrou) avec {@link RewardCatalogEditor}. */
    RewardTableEditor(EntitlementsFile file) {
        this.file = file;
    }

    /**
     * Crée ou remplace une table.
     *
     * @param expectedVersion version lue par le formulaire ({@code null} = pas de contrôle) : une table
     *                        modifiée entre-temps est refusée plutôt qu'écrasée
     */
    public Outcome save(RewardTable wanted, @Nullable String expectedVersion, boolean create) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            return save(current, wanted, expectedVersion, create);
        }
    }

    private Outcome save(EntitlementsFile.Current current, RewardTable wanted, @Nullable String expectedVersion,
                         boolean create) {
        RewardTable existing = current.catalog().tables().get(wanted.id());
        if (create && RESERVED_IDS.contains(wanted.id())) {
            return reservedId(wanted.id());
        }
        if (create && existing != null) {
            return Outcome.refused("EXISTS", "Une table « " + wanted.id() + " » existe déjà : choisir un autre "
                    + "identifiant, ou modifier celle-ci.");
        }
        if (!create && existing == null) {
            return Outcome.refused("NOT_FOUND", "Table « " + wanted.id() + " » introuvable (supprimée ou renommée "
                    + "dans entitlements.yml ?). Recharger la page.");
        }
        if (existing != null && expectedVersion != null && !expectedVersion.isBlank()
                && !existing.version().equals(expectedVersion)) {
            return Outcome.refused("STALE", "La table « " + wanted.id() + " » a changé depuis l'ouverture du "
                    + "formulaire (version " + expectedVersion + " → " + existing.version() + ") : rien n'a été "
                    + "écrit. Recharger la page puis refaire la modification.");
        }
        if (wanted.equals(existing)) {
            return new Outcome(true, "UNCHANGED", "Aucune modification : la table « " + wanted.label()
                    + " » est déjà enregistrée ainsi.", existing);
        }
        String versionNote = existing != null && !existing.version().equals(wanted.version())
                ? " Nouvelle version v" + wanted.version() + " (les gains déjà tirés gardent leur version v"
                + existing.version() + ")."
                : "";
        return write(current, wanted, create ? "CREATED" : "UPDATED",
                (create ? "Table « " : "Table modifiée : « ") + wanted.label() + " » enregistrée"
                        + (wanted.enabled() ? "" : " (désactivée : aucun tirage tant qu'elle ne l'est pas)") + "."
                        + versionNote);
    }

    /** Copie {@code sourceId} sous {@code newId}, <strong>désactivée</strong> : à vérifier puis activer. */
    public Outcome duplicate(String sourceId, String newId, @Nullable String newLabel) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            return duplicate(current, sourceId, newId, newLabel);
        }
    }

    private Outcome duplicate(EntitlementsFile.Current current, String sourceId, String newId,
                              @Nullable String newLabel) {
        RewardTable source = current.catalog().tables().get(sourceId);
        if (source == null) {
            return Outcome.refused("NOT_FOUND", "Table « " + sourceId + " » introuvable : recharger la page.");
        }
        if (RESERVED_IDS.contains(newId)) {
            return reservedId(newId);
        }
        if (current.catalog().tables().containsKey(newId)) {
            return Outcome.refused("EXISTS", "Une table « " + newId + " » existe déjà : choisir un autre identifiant.");
        }
        String label = newLabel == null || newLabel.isBlank()
                ? truncate(source.label(), LABEL_SUFFIX_ROOM) + " (copie)" : newLabel.trim();
        RewardTable copy = new RewardTable(newId, label, source.mode(), false, source.entries());
        return write(current, copy, "DUPLICATED", "Copie « " + label + " » créée, désactivée : la vérifier puis "
                + "l'activer. Aucune quête ni aucun boss ne l'utilise encore.");
    }

    /** Active ou désactive une table ; ses entrées et sa version ne changent pas. */
    public Outcome setEnabled(String id, boolean enabled) {
        synchronized (file) {
            EntitlementsFile.Current current;
            try {
                current = file.read();
            } catch (EntitlementsFile.Refusal refusal) {
                return Outcome.refused(refusal.failure.code(), refusal.failure.message());
            }
            return setEnabled(current, id, enabled);
        }
    }

    private Outcome setEnabled(EntitlementsFile.Current current, String id, boolean enabled) {
        RewardTable existing = current.catalog().tables().get(id);
        if (existing == null) {
            return Outcome.refused("NOT_FOUND", "Table « " + id + " » introuvable : recharger la page.");
        }
        if (existing.enabled() == enabled) {
            return new Outcome(true, "UNCHANGED", "La table « " + existing.label() + " » est déjà "
                    + (enabled ? "active" : "désactivée") + ".", existing);
        }
        RewardTable next = new RewardTable(existing.id(), existing.label(), existing.mode(), enabled,
                existing.entries());
        return write(current, next, "TOGGLED", enabled
                ? "Table « " + existing.label() + " » activée : les prochaines quêtes et victoires de boss la tirent."
                : "Table « " + existing.label() + " » désactivée : plus aucun tirage (les quêtes et boss qui "
                + "l'utilisent l'annoncent comme indisponible). Les gains déjà tirés restent remis.");
    }

    private static Outcome reservedId(String id) {
        return Outcome.refused("INVALID", "« " + id + " » ne peut pas servir d'identifiant de table (YAML le lirait "
                + "comme oui/non) : en choisir un autre, par exemple « " + id + "_coffre ».");
    }

    // ---- écriture ---------------------------------------------------------------------------------

    private Outcome write(EntitlementsFile.Current current, RewardTable table, String code, String message) {
        String text;
        try {
            text = render(current.yaml(), table);
        } catch (RuntimeException e) {
            return Outcome.refused("INVALID", "Table « " + table.id() + " » impossible à écrire : " + e.getMessage());
        }
        Map<String, RewardTable> tables = new LinkedHashMap<>(current.catalog().tables());
        tables.put(table.id(), table);
        RewardCatalog expected = new RewardCatalog(current.catalog().enabled(), current.catalog().rewards(), tables);
        EntitlementsFile.Failure failure = file.write(current.bytes(), text, expected, "table " + table.id());
        if (failure != null) {
            return Outcome.refused(failure.code(), failure.message());
        }
        file.logger().info("Récompenses : table « {} » v{} écrite depuis PlugAdmin ({}).", table.id(),
                table.version(), code);
        return new Outcome(true, code, message, table);
    }

    /** Remplace (ou ajoute) {@code tables.<id>} et rien d'autre ; l'ordre des tables est conservé. */
    static String render(YamlConfiguration yaml, RewardTable table) {
        ConfigurationSection tables = yaml.getConfigurationSection("tables");
        if (tables == null) {
            tables = yaml.createSection("tables");
        }
        ConfigurationSection section = tables.createSection(table.id());
        section.set("label", table.label());
        section.set("mode", table.mode().yaml());
        section.set("enabled", table.enabled());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RewardTable.Entry entry : table.entries()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", entry.id());
            line.put("reward", entry.rewardKey());
            line.put("chance", chanceValue(entry.chance()));
            entries.add(line);
        }
        section.set("entries", entries);
        return yaml.saveToString();
    }

    /** Centièmes de pour cent → nombre YAML exact ({@code 1250} → {@code 12.5}, {@code 500} → {@code 5}). */
    public static Number chanceValue(int hundredths) {
        BigDecimal percent = BigDecimal.valueOf(hundredths, 2).stripTrailingZeros();
        return percent.scale() <= 0 ? (Number) percent.intValueExact() : (Number) percent.doubleValue();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim();
    }
}
