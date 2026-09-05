package com.lodygames.rpgquest.zone;

import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit une {@link ZoneDefinition} à partir d'un fichier YAML
 * déjà parsé. Même conception que {@code QuestDefinitionParser}/{@code
 * ResourceNodeDefinitionParser} : purement structurel, ne dépend que de
 * {@link ConfigurationSection}, accumule toutes les erreurs avant d'échouer.
 */
final class ZoneDefinitionParser {

    ParseResult parse(String fileName, ConfigurationSection section) {
        List<String> errors = new ArrayList<>();

        String id = section.getString("id");
        if (id == null || id.isBlank()) {
            errors.add("« id » est obligatoire.");
        }
        String world = section.getString("world");
        if (world == null || world.isBlank()) {
            errors.add("« world » est obligatoire.");
        }

        int[] min = parsePoint(section, "min", errors);
        int[] max = parsePoint(section, "max", errors);
        ZoneFlags flags = parseFlags(section);

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new ZoneLoadIssue(fileName, m)).toList());
        }

        try {
            ZoneDefinition zone = new ZoneDefinition(
                    id, world, min[0], min[1], min[2], max[0], max[1], max[2], flags);
            return ParseResult.success(zone);
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new ZoneLoadIssue(fileName, e.getMessage())));
        }
    }

    private int[] parsePoint(ConfigurationSection section, String key, List<String> errors) {
        ConfigurationSection point = section.getConfigurationSection(key);
        if (point == null) {
            errors.add("« " + key + " » est obligatoire (x, y, z).");
            return new int[]{0, 0, 0};
        }
        if (!point.isSet("x") || !point.isSet("y") || !point.isSet("z")) {
            errors.add("« " + key + " » doit contenir x, y et z.");
            return new int[]{0, 0, 0};
        }
        return new int[]{point.getInt("x"), point.getInt("y"), point.getInt("z")};
    }

    private ZoneFlags parseFlags(ConfigurationSection section) {
        ConfigurationSection flags = section.getConfigurationSection("flags");
        ZoneFlags defaults = ZoneFlags.defaults();
        if (flags == null) {
            return defaults;
        }
        return new ZoneFlags(
                flags.getBoolean("pvp", defaults.allowPvp()),
                flags.getBoolean("block-break", defaults.allowBlockBreak()),
                flags.getBoolean("block-place", defaults.allowBlockPlace()),
                flags.getBoolean("explosions", defaults.allowExplosions()),
                flags.getBoolean("fire", defaults.allowFire()),
                flags.getBoolean("lava", defaults.allowLava()),
                flags.getBoolean("pistons-across-border", defaults.allowPistonsAcrossBorder()),
                flags.getBoolean("hostile-spawn", defaults.allowHostileSpawn()),
                flags.getBoolean("hostile-damage", defaults.allowHostileDamage()),
                flags.getBoolean("environmental-damage", defaults.allowEnvironmentalDamage()),
                flags.getBoolean("npc-damage", defaults.allowNpcDamage()),
                flags.getBoolean("doors", defaults.allowDoors()),
                flags.getBoolean("buttons", defaults.allowButtons()),
                flags.getBoolean("levers", defaults.allowLevers()),
                flags.getBoolean("npc-interact", defaults.allowNpcInteract()),
                flags.getBoolean("public-containers", defaults.allowPublicContainers()),
                flags.getBoolean("force-day", defaults.forceDay()));
    }

    record ParseResult(ZoneDefinition zone, List<ZoneLoadIssue> issues) {

        static ParseResult success(ZoneDefinition zone) {
            return new ParseResult(zone, List.of());
        }

        static ParseResult failure(List<ZoneLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return zone != null;
        }
    }
}
