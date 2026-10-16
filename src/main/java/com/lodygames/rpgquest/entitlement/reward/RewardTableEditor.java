package com.lodygames.rpgquest.entitlement.reward;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Écriture de la section {@code tables} de {@code entitlements.yml} depuis PlugAdmin (issue #177,
 * tranche 2). Mêmes garanties que l'écriture des profils de mobs (#461) :
 *
 * <ol>
 *   <li>le fichier actuel doit être valide — jamais d'écriture par-dessus un fichier que le
 *       serveur refuse déjà ;</li>
 *   <li>le texte final est relu par le <strong>vrai</strong> chargeur et doit redonner exactement la
 *       table voulue, sans rien changer aux récompenses ni aux autres tables — sinon rien n'est
 *       écrit ;</li>
 *   <li>écriture atomique (fichier temporaire puis remplacement) ;</li>
 *   <li>rechargement immédiat ; s'il n'aboutit pas à la table voulue, l'ancien fichier est remis à
 *       l'octet près et rechargé.</li>
 * </ol>
 *
 * <p>Seule la section {@code tables} est modifiée ; le catalogue des récompenses et l'interrupteur
 * {@code enabled} ne sont jamais touchés. Appelé hors du thread principal (thread agent).</p>
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

    private final Path file;
    private final Supplier<RewardCatalogLoader.Result> reload;
    private final Logger logger;

    /**
     * @param reload relit le fichier et applique le catalogue (ex. {@link RewardEntitlementService#reloadNow()})
     */
    public RewardTableEditor(Path file, Supplier<RewardCatalogLoader.Result> reload, Logger logger) {
        this.file = file;
        this.reload = reload;
        this.logger = logger;
    }

    /**
     * Crée ou remplace une table.
     *
     * @param expectedVersion version lue par le formulaire ({@code null} = pas de contrôle) : une table
     *                        modifiée entre-temps est refusée plutôt qu'écrasée
     */
    public synchronized Outcome save(RewardTable wanted, @Nullable String expectedVersion, boolean create) {
        Current current;
        try {
            current = readCurrent();
        } catch (Refusal refusal) {
            return refusal.outcome;
        }
        RewardTable existing = current.catalog.tables().get(wanted.id());
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
    public synchronized Outcome duplicate(String sourceId, String newId, @Nullable String newLabel) {
        Current current;
        try {
            current = readCurrent();
        } catch (Refusal refusal) {
            return refusal.outcome;
        }
        RewardTable source = current.catalog.tables().get(sourceId);
        if (source == null) {
            return Outcome.refused("NOT_FOUND", "Table « " + sourceId + " » introuvable : recharger la page.");
        }
        if (RESERVED_IDS.contains(newId)) {
            return reservedId(newId);
        }
        if (current.catalog.tables().containsKey(newId)) {
            return Outcome.refused("EXISTS", "Une table « " + newId + " » existe déjà : choisir un autre identifiant.");
        }
        String label = newLabel == null || newLabel.isBlank()
                ? truncate(source.label(), LABEL_SUFFIX_ROOM) + " (copie)" : newLabel.trim();
        RewardTable copy = new RewardTable(newId, label, source.mode(), false, source.entries());
        return write(current, copy, "DUPLICATED", "Copie « " + label + " » créée, désactivée : la vérifier puis "
                + "l'activer. Aucune quête ni aucun boss ne l'utilise encore.");
    }

    /** Active ou désactive une table ; ses entrées et sa version ne changent pas. */
    public synchronized Outcome setEnabled(String id, boolean enabled) {
        Current current;
        try {
            current = readCurrent();
        } catch (Refusal refusal) {
            return refusal.outcome;
        }
        RewardTable existing = current.catalog.tables().get(id);
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

    // ---- lecture / écriture ----------------------------------------------------------------------

    private record Current(byte[] bytes, YamlConfiguration yaml, RewardCatalog catalog) {
    }

    /** Refus levé pendant la lecture du fichier actuel. */
    private static final class Refusal extends Exception {
        private final Outcome outcome;

        Refusal(Outcome outcome) {
            super(outcome.message(), null, false, false);
            this.outcome = outcome;
        }
    }

    private Current readCurrent() throws Refusal {
        if (!Files.isRegularFile(file)) {
            // Sans fichier, il n'existe aucune récompense : une table n'aurait rien à tirer.
            throw new Refusal(Outcome.refused("NO_CATALOG", "entitlements.yml n'existe pas encore sur ce serveur : "
                    + "aucune récompense n'est définie, donc aucune table ne peut en tirer. Créer d'abord le "
                    + "catalogue des récompenses (voir docs/REWARD_ENTITLEMENTS.md)."));
        }
        byte[] bytes;
        YamlConfiguration yaml = newYaml();
        try {
            bytes = Files.readAllBytes(file);
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new Refusal(Outcome.refused("CURRENT_INVALID", "entitlements.yml ne se relit pas (" + e.getMessage()
                    + ") : rien n'a été écrit. Le corriger d'abord (voir Diagnostics)."));
        }
        RewardCatalogLoader.Result result = RewardCatalogLoader.load(yaml);
        if (!result.valid()) {
            throw new Refusal(Outcome.refused("CURRENT_INVALID", "entitlements.yml est actuellement refusé par le "
                    + "serveur (" + summary(result.errors()) + ") : rien n'a été écrit, pour ne pas aggraver la "
                    + "situation. Le corriger d'abord."));
        }
        return new Current(bytes, yaml, result.catalog());
    }

    private Outcome write(Current current, RewardTable table, String code, String message) {
        String text;
        try {
            text = render(current.yaml, table);
        } catch (RuntimeException e) {
            return Outcome.refused("INVALID", "Table « " + table.id() + " » impossible à écrire : " + e.getMessage());
        }
        // 1. Relecture par le vrai chargeur, AVANT toute écriture.
        YamlConfiguration reread = newYaml();
        try {
            reread.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return Outcome.refused("INVALID", "Rendu YAML illisible, rien n'a été écrit : " + e.getMessage());
        }
        RewardCatalogLoader.Result check = RewardCatalogLoader.load(reread);
        if (!check.valid()) {
            return Outcome.refused("INVALID", "Table refusée, rien n'a été écrit : " + summary(check.errors()));
        }
        if (!sameApartFrom(current.catalog, check.catalog(), table)) {
            return Outcome.refused("INVALID", "Le fichier relu ne redonne pas exactement la table voulue : rien n'a "
                    + "été écrit.");
        }
        // 2. Écriture atomique.
        try {
            writeAtomically(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Outcome.refused("ERROR", "Écriture de entitlements.yml impossible : " + e.getMessage());
        }
        // 3. Rechargement ; à défaut, retour à l'ancien fichier, à l'octet près.
        RewardCatalogLoader.Result applied = reload.get();
        if (applied.valid() && Objects.equals(applied.catalog().tables().get(table.id()), table)) {
            logger.info("Récompenses : table « {} » v{} écrite depuis PlugAdmin ({}).", table.id(), table.version(),
                    code);
            return new Outcome(true, code, message, table);
        }
        String why = applied.valid() ? "table absente après rechargement" : summary(applied.errors());
        try {
            writeAtomically(current.bytes);
        } catch (IOException e) {
            logger.error("Récompenses : restauration de {} IMPOSSIBLE après un rechargement refusé.", file, e);
            return Outcome.refused("ERROR", "Rechargement refusé (" + why + ") ET restauration impossible ("
                    + e.getMessage() + ") : vérifier entitlements.yml sur le serveur.");
        }
        reload.get();
        logger.warn("Récompenses : écriture de la table « {} » annulée, ancien entitlements.yml remis ({}).",
                table.id(), why);
        return Outcome.refused("ROLLED_BACK", "Le serveur n'a pas accepté le nouveau fichier (" + why + ") : "
                + "l'ancienne version de entitlements.yml a été remise, rien n'a changé.");
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

    private static boolean sameApartFrom(RewardCatalog before, RewardCatalog after, RewardTable table) {
        if (before.enabled() != after.enabled() || !before.rewards().equals(after.rewards())) {
            return false;
        }
        Map<String, RewardTable> expected = new LinkedHashMap<>(before.tables());
        expected.put(table.id(), table);
        return expected.equals(after.tables());
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        Path temp = file.resolveSibling("." + file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static YamlConfiguration newYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        return yaml;
    }

    private static String summary(List<String> errors) {
        List<String> shown = errors.subList(0, Math.min(3, errors.size()));
        return String.join(" ; ", shown) + (errors.size() > 3 ? " (+" + (errors.size() - 3) + ")" : "");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim();
    }
}
