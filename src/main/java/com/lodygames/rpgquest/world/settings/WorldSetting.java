package com.lodygames.rpgquest.world.settings;

import java.util.Locale;
import java.util.Optional;

/**
 * Réglages d'un monde existant administrables depuis PlugAdmin (issue #561, #331-W2).
 *
 * <p>Chaque réglage a <strong>un seul chemin d'écriture</strong>, choisi par la passerelle selon le
 * gestionnaire du monde : Multiverse-Core quand il connaît le monde (sinon il réappliquerait sa
 * propre valeur au prochain chargement), l'API Bukkit sinon. Les règles de jeu couvertes par un
 * réglage dédié ({@link #dedicatedRule()}) ne sont pas modifiables une seconde fois dans la liste
 * des règles avancées.</p>
 *
 * <p>Les règles de jeu avancées n'apparaissent pas ici : elles sont lues au runtime dans le
 * registre du serveur (noms 1.21.11) et portent la clé {@code rule.<nom>}.</p>
 */
public enum WorldSetting {

    /**
     * Nom affiché dans PlugAdmin (issue #81) : alias Multiverse-Core si Multiverse connaît le monde,
     * sinon {@code plugins/RPGQuest/world-names.yml}. L'identifiant technique ne change jamais.
     */
    ALIAS("alias", "Nom affiché", null),
    DIFFICULTY("difficulty", "Difficulté", null),
    PVP("pvp", "PvP (combat entre joueurs)", "pvp"),
    MONSTERS("monsters", "Apparition des monstres", "spawn_monsters"),
    ANIMALS("animals", "Apparition des animaux", null),
    GAMEMODE("gamemode", "Mode de jeu à l'arrivée", null),
    ADVANCE_TIME("advance_time", "Jour et nuit qui avancent", "advance_time"),
    TIME("time", "Heure du monde", null),
    ADVANCE_WEATHER("advance_weather", "Météo qui change", "advance_weather"),
    WEATHER("weather", "Météo actuelle", null),
    BORDER_CENTER("border_center", "Centre de la bordure", null),
    BORDER_SIZE("border_size", "Diamètre de la bordure", null),
    SPAWN("spawn", "Point d'arrivée", null);

    /** Préfixe des règles de jeu avancées : {@code rule.keep_inventory}. */
    public static final String RULE_PREFIX = "rule.";

    private final String key;
    private final String label;
    private final String dedicatedRule;

    WorldSetting(String key, String label, String dedicatedRule) {
        this.key = key;
        this.label = label;
        this.dedicatedRule = dedicatedRule;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Règle de jeu (clé 1.21.11, sans espace de noms) que ce réglage pilote, ou {@code null}. */
    public String dedicatedRule() {
        return dedicatedRule;
    }

    public static Optional<WorldSetting> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (WorldSetting s : values()) {
            if (s.key.equals(k)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Réglage dédié qui pilote la règle {@code rule}, s'il existe (la règle n'est alors pas éditable seule). */
    public static Optional<WorldSetting> owningRule(String rule) {
        for (WorldSetting s : values()) {
            if (s.dedicatedRule != null && s.dedicatedRule.equals(rule)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
