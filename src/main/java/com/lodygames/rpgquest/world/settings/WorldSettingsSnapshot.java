package com.lodygames.rpgquest.world.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Valeurs <strong>réellement relues</strong> sur le serveur pour un monde chargé (issue #561).
 * Aucun type Bukkit ni Multiverse : la passerelle produit ce relevé sur le thread principal, la
 * planification et les tests travaillent dessus sans serveur.
 *
 * @param world       nom du monde
 * @param multiverse  {@code true} = Multiverse-Core connaît ce monde : il est l'unique chemin
 *                    d'écriture des réglages qu'il persiste
 * @param roles       rôles LodyQuests ({@code HUB}, {@code WILD}, {@code CLAIMS}) — vide = aucun
 * @param players     joueurs présents dans ce monde au relevé
 * @param minHeight   hauteur minimale constructible (borne du point d'arrivée)
 * @param maxHeight   hauteur maximale (exclue)
 * @param values      clé de {@link WorldSetting} → valeur canonique ; une clé absente = réglage
 *                    indisponible pour ce monde (voir {@code unavailable})
 * @param writers     clé → chemin d'écriture ({@code MULTIVERSE}, {@code BUKKIT}, {@code GAMERULE})
 * @param unavailable clé → raison lisible de l'indisponibilité
 * @param rules       règles de jeu lues dans le registre du serveur au moment du relevé
 * @param notes       constats à afficher (écart Multiverse / serveur, météo bloquée par Multiverse…)
 */
public record WorldSettingsSnapshot(String world, boolean multiverse, List<String> roles, int players,
                                    int minHeight, int maxHeight, Map<String, String> values,
                                    Map<String, String> writers, Map<String, String> unavailable,
                                    List<Rule> rules, List<String> notes) {

    public static final String WRITER_MULTIVERSE = "MULTIVERSE";
    public static final String WRITER_BUKKIT = "BUKKIT";
    public static final String WRITER_GAMERULE = "GAMERULE";

    /** Une règle de jeu : {@code type} {@code BOOLEAN} ou {@code INTEGER}, valeur canonique. */
    public record Rule(String name, String type, String value) {
    }

    public WorldSettingsSnapshot {
        roles = roles == null ? List.of() : List.copyOf(roles);
        values = values == null ? Map.of() : Map.copyOf(values);
        writers = writers == null ? Map.of() : Map.copyOf(writers);
        unavailable = unavailable == null ? Map.of() : Map.copyOf(unavailable);
        rules = rules == null ? List.of() : List.copyOf(rules);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public Optional<Rule> rule(String name) {
        return rules.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /** Valeur actuelle d'une clé de réglage ou de règle ({@code rule.<nom>}), {@code null} si inconnue. */
    public String current(String key) {
        if (key.startsWith(WorldSetting.RULE_PREFIX)) {
            return rule(key.substring(WorldSetting.RULE_PREFIX.length())).map(Rule::value).orElse(null);
        }
        return values.get(key);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
