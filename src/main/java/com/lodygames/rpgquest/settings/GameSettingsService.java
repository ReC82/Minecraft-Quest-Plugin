package com.lodygames.rpgquest.settings;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.economy.CurrencyName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Lit et écrit {@code settings.yml} (issue #335). Seule source de vérité des paramètres généraux.
 *
 * <ul>
 *   <li>Écriture <strong>atomique</strong> (fichier temporaire puis renommage) : un arrêt au mauvais
 *       moment ne laisse jamais un fichier à moitié écrit.</li>
 *   <li>Après écriture, le fichier est <strong>relu</strong> : ce qui est rendu au panel est ce qui
 *       est réellement sur disque, pas ce qui a été demandé.</li>
 *   <li>Un fichier illisible n'est jamais écrasé en silence : les valeurs par défaut sont utilisées
 *       et le motif est journalisé et exposé ({@link #loadProblem()}).</li>
 * </ul>
 *
 * <p>Fichier minuscule, lu au démarrage et à chaque modification : l'accès disque se fait sur le
 * thread de l'action agent, jamais à chaque événement de jeu.</p>
 */
public final class GameSettingsService implements PluginService {

    /** Ce qui a été écrit puis relu, avec l'auteur et la date. */
    public record Saved(boolean ok, String message, GameSettings settings, String updatedAt, String updatedBy) {
    }

    private final Path file;
    private final Logger logger;
    private volatile GameSettings current = GameSettings.DEFAULTS;
    private volatile String updatedAt = "";
    private volatile String updatedBy = "";
    private volatile String loadProblem = "";

    public GameSettingsService(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("settings.yml");
        this.logger = logger;
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void stop() {
        // Rien à libérer.
    }

    public GameSettings current() {
        return current;
    }

    public String updatedAt() {
        return updatedAt;
    }

    public String updatedBy() {
        return updatedBy;
    }

    /** Vide si le fichier se lit correctement (ou n'existe pas encore). */
    public String loadProblem() {
        return loadProblem;
    }

    public synchronized void reload() {
        if (!Files.isRegularFile(file)) {
            apply(GameSettings.DEFAULTS, "", "", "");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            logger.error("settings.yml illisible : valeurs par défaut utilisées, fichier laissé tel quel ({})",
                    e.getMessage());
            apply(GameSettings.DEFAULTS, "", "", "settings.yml illisible : " + e.getMessage());
            return;
        }
        GameSettings d = GameSettings.DEFAULTS;
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (yaml.isSet("economy.currency-name")) {
            values.put("currency_name", yaml.getString("economy.currency-name"));
        }
        if (yaml.isSet("login.beta-warning.enabled")) {
            values.put("beta_warning_enabled", String.valueOf(yaml.getBoolean("login.beta-warning.enabled")));
        }
        if (yaml.isSet("login.beta-warning.text")) {
            values.put("beta_warning_text", yaml.getString("login.beta-warning.text"));
        }
        GameSettings.Change change = d.with(values);
        String problem = change.ok() ? "" : "settings.yml : " + String.join(" ", change.errors())
                + " Valeurs par défaut utilisées pour ces paramètres.";
        if (!change.ok()) {
            logger.warn(problem);
            // Ce qui est valide est gardé, valeur par valeur.
            GameSettings partial = d;
            for (Map.Entry<String, String> e : values.entrySet()) {
                GameSettings.Change one = partial.with(Map.of(e.getKey(), e.getValue()));
                if (one.ok()) {
                    partial = one.settings();
                }
            }
            apply(partial, yaml.getString("meta.updated-at", ""), yaml.getString("meta.updated-by", ""), problem);
            return;
        }
        apply(change.settings(), yaml.getString("meta.updated-at", ""), yaml.getString("meta.updated-by", ""), "");
    }

    /** Valide, écrit, relit. Rien n'est écrit si une valeur est invalide. */
    public synchronized Saved update(Map<String, String> changes, String actor) {
        if (!loadProblem.isEmpty() && Files.isRegularFile(file)) {
            return new Saved(false, "settings.yml est illisible sur le serveur : corrigez-le ou supprimez-le "
                    + "avant de modifier les paramètres (" + loadProblem + ").", current, updatedAt, updatedBy);
        }
        GameSettings.Change change = current.with(changes);
        if (!change.ok()) {
            return new Saved(false, String.join(" ", change.errors()), current, updatedAt, updatedBy);
        }
        GameSettings next = change.settings();
        String at = Instant.now().toString();
        String by = actor == null || actor.isBlank() ? "PlugAdmin" : actor.trim();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "Paramètres généraux de RPGQuest — modifiés depuis PlugAdmin > Paramètres généraux.",
                "Fichier écrit par le plugin : inutile de le modifier à la main."));
        yaml.set("economy.currency-name", next.currencyName());
        yaml.set("login.beta-warning.enabled", next.betaWarningEnabled());
        yaml.set("login.beta-warning.text", next.betaWarningText());
        yaml.set("meta.updated-at", at);
        yaml.set("meta.updated-by", by);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("settings.yml.tmp");
            Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Écriture de settings.yml impossible", e);
            return new Saved(false, "Le serveur n'a pas pu enregistrer les paramètres (" + e.getMessage()
                    + "). Rien n'a changé.", current, updatedAt, updatedBy);
        }
        reload();
        boolean matches = current.equals(next);
        logger.info("[settings] modifiés par {} : {}", by, changes.keySet());
        return new Saved(matches, matches ? "Paramètres enregistrés et relus sur le serveur."
                : "Les paramètres relus ne correspondent pas à ceux envoyés : vérifiez settings.yml.",
                current, updatedAt, updatedBy);
    }

    private void apply(GameSettings settings, String at, String by, String problem) {
        current = settings;
        updatedAt = at == null ? "" : at;
        updatedBy = by == null ? "" : by;
        loadProblem = problem;
        CurrencyName.set(settings.currencyName());
    }
}
