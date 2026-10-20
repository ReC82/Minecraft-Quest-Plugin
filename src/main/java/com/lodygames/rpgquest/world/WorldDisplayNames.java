package com.lodygames.rpgquest.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Noms d'affichage des mondes que Multiverse-Core ne connaît pas (issue #81) : pour ceux qu'il
 * connaît, le nom affiché est son alias ({@code MultiverseWorld#getAlias}), jamais une seconde copie.
 *
 * <p>Un nom d'affichage n'est qu'un libellé pour PlugAdmin : l'identifiant technique du monde ne
 * change jamais, et rien d'autre (configuration, liens, portails) n'est réécrit. Absent, PlugAdmin
 * reprend ses noms par défaut (Hub, Claims, Wild…).</p>
 *
 * <p>Fichier {@code plugins/RPGQuest/world-names.yml}, lu et écrit <strong>hors du thread
 * principal</strong> ({@code ioExecutor}) ; la mémoire sert les lectures. Seule l'action
 * {@code world.settings.apply} y écrit, après aperçu et confirmation.</p>
 */
public final class WorldDisplayNames {

    /** Valeur canonique « aucun nom personnalisé » (un champ vide veut dire « inchangé » dans PlugAdmin). */
    public static final String NONE = "-";
    /** Longueur maximale d'un nom d'affichage. */
    public static final int MAX_LENGTH = 32;

    private static final String SECTION = "display-names";

    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Object fileLock = new Object();

    public WorldDisplayNames(Path file, Executor ioExecutor, Logger logger) {
        this.file = file;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
    }

    /** Charge le fichier existant (hors thread principal). Un nom invalide est ignoré, jamais corrigé. */
    public void load() {
        ioExecutor.execute(() -> {
            if (!Files.exists(file)) {
                return;
            }
            ConfigurationSection section = YamlConfiguration.loadConfiguration(file.toFile())
                    .getConfigurationSection(SECTION);
            if (section == null) {
                return;
            }
            for (String world : section.getKeys(false)) {
                String name = normalize(section.getString(world));
                if (name == null || NONE.equals(name)) {
                    logger.warn("{} : nom d'affichage ignoré pour le monde « {} » (invalide).", file.getFileName(), world);
                    continue;
                }
                names.put(key(world), name);
            }
        });
    }

    /** Nom d'affichage enregistré pour ce monde, s'il y en a un. */
    public Optional<String> get(String world) {
        return Optional.ofNullable(names.get(key(world)));
    }

    /** Tous les noms enregistrés (identifiant du monde en minuscules → nom). */
    public Map<String, String> all() {
        return Map.copyOf(names);
    }

    /**
     * Enregistre (ou retire avec {@link #NONE}) le nom d'un monde, puis réécrit le fichier hors du
     * thread principal.
     *
     * @return {@code null} si la valeur est acceptée, sinon la raison
     */
    public String set(String world, String value) {
        String name = normalize(value);
        if (name == null) {
            return "Nom d'affichage invalide.";
        }
        if (NONE.equals(name)) {
            names.remove(key(world));
        } else {
            names.put(key(world), name);
        }
        Map<String, String> copy = new TreeMap<>(names);
        ioExecutor.execute(() -> write(copy));
        return null;
    }

    /**
     * Forme canonique d'un nom saisi : espaces réduits, 1 à {@value #MAX_LENGTH} caractères, sans
     * code de couleur ni balise ({@code < > & §}) ni caractère de contrôle. {@link #NONE} retire le nom.
     *
     * @return le nom normalisé, ou {@code null} si la saisie est invalide
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.strip().replaceAll("\\s+", " ");
        if (NONE.equals(v)) {
            return NONE;
        }
        if (v.isEmpty() || v.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '<' || c == '>' || c == '&' || c == '§' || Character.isISOControl(c)) {
                return null;
            }
        }
        return v;
    }

    private void write(Map<String, String> snapshot) {
        synchronized (fileLock) {
            try {
                Files.createDirectories(file.getParent());
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.options().setHeader(java.util.List.of(
                        "Noms d'affichage des mondes hors Multiverse-Core (issue #81), édités depuis PlugAdmin",
                        "(page Mondes > Réglages). L'identifiant technique du monde ne change jamais."));
                ConfigurationSection section = yaml.createSection(SECTION);
                snapshot.forEach(section::set);
                yaml.save(file.toFile());
            } catch (IOException e) {
                logger.error("Impossible d'écrire {}.", file, e);
            }
        }
    }

    private static String key(String world) {
        return world == null ? "" : world.toLowerCase(Locale.ROOT);
    }
}
