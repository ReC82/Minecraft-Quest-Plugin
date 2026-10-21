package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.dialogue.DialogueConditionYaml;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.travel.model.PortalAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * Section {@code access:} d'un fichier {@code world-portals/<id>.yml} (issue #567) :
 *
 * <pre>
 * access:
 *   required-level: 5
 *   level-message: "&lt;red&gt;Reviens au niveau %level%.&lt;/red&gt;"
 *   cooldown-seconds: 30
 *   cooldown-message: ""
 *   conditions:
 *     - type: QUEST_STATE          # mêmes clés que dans un dialogue
 *       quest: rpgquest:crystal_hunt
 *       state: COMPLETED
 *       negate: false
 *       message: "&lt;red&gt;Termine d'abord la chasse au cristal.&lt;/red&gt;"
 * </pre>
 *
 * <p>Une section invalide fait <strong>ignorer le fichier</strong> (exception au chargement) : un
 * portail dont les conditions sont illisibles n'est jamais chargé sans elles.</p>
 */
final class PortalAccessYaml {

    static final String SECTION = "access";

    private PortalAccessYaml() {
    }

    static PortalAccess read(ConfigurationSection root) {
        if (!root.isSet(SECTION)) {
            return PortalAccess.NONE;
        }
        ConfigurationSection s = root.getConfigurationSection(SECTION);
        if (s == null) {
            throw new IllegalArgumentException("« access » doit être une section.");
        }
        Integer level = null;
        if (s.isSet("required-level")) {
            if (!s.isInt("required-level")) {
                throw new IllegalArgumentException("« access.required-level » doit être un entier.");
            }
            level = s.getInt("required-level");
        }
        int cooldown = 0;
        if (s.isSet("cooldown-seconds")) {
            if (!s.isInt("cooldown-seconds")) {
                throw new IllegalArgumentException("« access.cooldown-seconds » doit être un entier.");
            }
            cooldown = s.getInt("cooldown-seconds");
        }
        List<PortalAccess.Rule> rules = new ArrayList<>();
        List<Map<?, ?>> raw = s.getMapList("conditions");
        if (s.isSet("conditions") && !s.isList("conditions")) {
            throw new IllegalArgumentException("« access.conditions » doit être une liste.");
        }
        for (int i = 0; i < raw.size(); i++) {
            ConfigurationSection c = toSection(raw.get(i));
            String context = "access.conditions[" + i + "]";
            List<String> errors = new ArrayList<>();
            DialogueCondition condition = DialogueConditionYaml.parse(c, context, errors);
            if (condition == null || !errors.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ; ", errors.isEmpty()
                        ? List.of(context + " : condition invalide.") : errors));
            }
            try {
                rules.add(new PortalAccess.Rule(condition, c.getString("message", "")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(context + " : " + e.getMessage());
            }
        }
        return new PortalAccess(level, s.getString("level-message", ""), rules, cooldown,
                s.getString("cooldown-message", ""));
    }

    /** Rien n'est écrit pour {@link PortalAccess#isEmpty() aucune condition} : fichier identique à avant #567. */
    static void write(ConfigurationSection root, PortalAccess access) {
        if (access.isEmpty()) {
            return;
        }
        if (access.requiredLevel() != null) {
            root.set(SECTION + ".required-level", access.requiredLevel());
            if (!access.levelMessage().isEmpty()) {
                root.set(SECTION + ".level-message", access.levelMessage());
            }
        }
        if (access.cooldownSeconds() > 0) {
            root.set(SECTION + ".cooldown-seconds", access.cooldownSeconds());
            if (!access.cooldownMessage().isEmpty()) {
                root.set(SECTION + ".cooldown-message", access.cooldownMessage());
            }
        }
        if (!access.rules().isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (PortalAccess.Rule r : access.rules()) {
                Map<String, Object> m = new LinkedHashMap<>(DialogueConditionYaml.toMap(r.condition()));
                if (!r.message().isEmpty()) {
                    m.put("message", r.message());
                }
                list.add(m);
            }
            root.set(SECTION + ".conditions", list);
        }
    }

    /** Forme canonique stable (empreinte, journal). */
    static String canonical(PortalAccess access) {
        StringBuilder sb = new StringBuilder();
        sb.append("level=").append(access.requiredLevel() == null ? "" : access.requiredLevel())
                .append(";levelMsg=").append(access.levelMessage())
                .append(";cooldown=").append(access.cooldownSeconds())
                .append(";cooldownMsg=").append(access.cooldownMessage());
        for (PortalAccess.Rule r : access.rules()) {
            sb.append(";rule=").append(DialogueConditionYaml.toMap(r.condition())).append("/").append(r.message());
        }
        return sb.toString();
    }

    private static ConfigurationSection toSection(Map<?, ?> map) {
        MemoryConfiguration memory = new MemoryConfiguration();
        for (var entry : map.entrySet()) {
            memory.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return memory;
    }
}
