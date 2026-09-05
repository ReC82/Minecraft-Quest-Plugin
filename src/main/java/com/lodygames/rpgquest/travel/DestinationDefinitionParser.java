package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.Destination;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide et construit une {@link Destination} à partir d'un fichier YAML
 * déjà parsé. Même conception que {@code ZoneDefinitionParser} : purement
 * structurel, ne dépend que de {@link ConfigurationSection}, accumule
 * toutes les erreurs avant d'échouer.
 */
final class DestinationDefinitionParser {

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
        if (!section.isSet("x") || !section.isSet("y") || !section.isSet("z")) {
            errors.add("« x », « y » et « z » sont obligatoires.");
        }

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors.stream().map(m -> new DestinationLoadIssue(fileName, m)).toList());
        }

        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw", 0.0);
        float pitch = (float) section.getDouble("pitch", 0.0);

        try {
            return ParseResult.success(new Destination(id, world, x, y, z, yaw, pitch));
        } catch (IllegalArgumentException e) {
            return ParseResult.failure(List.of(new DestinationLoadIssue(fileName, e.getMessage())));
        }
    }

    record ParseResult(Destination destination, List<DestinationLoadIssue> issues) {

        static ParseResult success(Destination destination) {
            return new ParseResult(destination, List.of());
        }

        static ParseResult failure(List<DestinationLoadIssue> issues) {
            return new ParseResult(null, issues);
        }

        boolean isSuccess() {
            return destination != null;
        }
    }
}
