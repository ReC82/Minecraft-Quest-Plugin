package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Écriture <strong>sûre</strong> des profils de mob spécial sur disque (issue #172) : même
 * discipline que {@link com.lodygames.rpgquest.npc.NpcDefinitionStore} -- un fichier par profil
 * sous le dossier {@code mobs/}, écriture atomique (fichier temporaire + {@code move}), jamais de
 * YAML brut envoyé par l'appelant ({@link SpecialMobDefinitionYaml} produit le texte), vérification
 * par relecture avant de déclarer le succès. Purement IO + {@link SpecialMobLoader} (testable sans
 * MockBukkit au-delà de ce que {@code SpecialMobDefinitionParser} exige déjà).
 */
public final class SpecialMobDefinitionStore {

    private final Path directory;
    private final SpecialMobLoader loader = new SpecialMobLoader();

    public SpecialMobDefinitionStore(Path directory) {
        this.directory = directory;
    }

    /** @param code {@code CREATED} / {@code UPDATED} / {@code EXISTS} / {@code NOT_FOUND} / {@code ERROR} */
    public record Result(boolean ok, String code, String message, String file, SpecialMobLoadReport report) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null, null);
        }
    }

    public Result create(SpecialMobDefinition definition) {
        Path target = fileFor(definition.id());
        if (existingFileFor(definition.id()) != null) {
            return Result.fail("EXISTS", "Un profil « " + definition.id() + " » existe déjà — "
                    + "utiliser la modification, jamais l'écrasement.");
        }
        return writeAndReload(target, definition, "CREATED", "Profil de mob spécial « " + definition.id() + " » créé.");
    }

    public Result update(SpecialMobDefinition definition) {
        Path target = existingFileFor(definition.id());
        if (target == null) {
            return Result.fail("NOT_FOUND", "Aucun profil « " + definition.id() + " » à modifier.");
        }
        return writeAndReload(target, definition, "UPDATED", "Profil de mob spécial « " + definition.id() + " » modifié.");
    }

    public Optional<SpecialMobDefinition> find(NamespacedKey id) {
        return loader.loadDirectory(directory).loaded().stream()
                .filter(d -> d.id().equals(id)).findFirst();
    }

    public SpecialMobLoadReport list() {
        return loader.loadDirectory(directory);
    }

    // ---- interne -----------------------------------------------------------------------------

    private Result writeAndReload(Path target, SpecialMobDefinition definition, String code, String message) {
        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, SpecialMobDefinitionYaml.render(definition), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        SpecialMobLoadReport report = loader.loadDirectory(directory);
        boolean present = report.loaded().stream().anyMatch(d -> d.id().equals(definition.id()));
        if (!present) {
            return new Result(false, "ERROR",
                    "Le fichier a été écrit mais ne se recharge pas proprement — voir les erreurs.",
                    target.getFileName().toString(), report);
        }
        return new Result(true, code, message, target.getFileName().toString(), report);
    }

    private Path fileFor(NamespacedKey id) {
        return directory.resolve(id.getKey() + ".yml");
    }

    private Path existingFileFor(NamespacedKey id) {
        for (String ext : new String[] {".yml", ".yaml"}) {
            Path direct = directory.resolve(id.getKey() + ext);
            if (Files.exists(direct)) {
                return direct;
            }
        }
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (var stream = Files.newDirectoryStream(directory, "*.y*ml")) {
            for (Path file : stream) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception ignored) {
                    continue;
                }
                String raw = yaml.getString("id");
                if (raw != null && id.equals(toNamespacedKey(raw.trim()))) {
                    return file;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static NamespacedKey toNamespacedKey(String raw) {
        try {
            return raw.contains(":") ? NamespacedKey.fromString(raw)
                    : new NamespacedKey(SpecialMobDefinitionParser.DEFAULT_NAMESPACE, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
