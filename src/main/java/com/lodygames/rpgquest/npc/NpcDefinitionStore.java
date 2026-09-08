package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Écriture <strong>sûre</strong> des définitions PNJ sur disque : un fichier par PNJ sous le
 * dossier {@code npcs/}, jamais de chemin arbitraire (le nom de fichier est toujours
 * {@code <id>.yml}, l'{@code id} étant validé en amont par {@link NpcDefinition}). Pas de YAML
 * brut : {@link NpcDefinitionYaml} produit le texte. Écriture atomique (fichier temporaire +
 * {@code move}). Purement IO : testable avec {@code @TempDir}, aucune dépendance Bukkit au-delà
 * de {@link NpcDefinitionLoader} (qui fonctionne en JUnit pur).
 */
public final class NpcDefinitionStore {

    private final Path directory;
    private final NpcDefinitionLoader loader = new NpcDefinitionLoader();

    public NpcDefinitionStore(Path directory) {
        this.directory = directory;
    }

    /** @param code {@code CREATED} / {@code UPDATED} / {@code EXISTS} / {@code NOT_FOUND} / {@code ERROR} */
    public record Result(boolean ok, String code, String message, String file, NpcLoadReport report) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null, null);
        }
    }

    public Result create(NpcDefinition definition) {
        Path target = directory.resolve(definition.id() + ".yml");
        if (Files.exists(target) || Files.exists(directory.resolve(definition.id() + ".yaml"))) {
            return Result.fail("EXISTS", "Une définition « " + definition.id() + " » existe déjà — "
                    + "utiliser la modification, jamais l'écrasement.");
        }
        return writeAndReload(target, definition, "CREATED", "Définition PNJ « " + definition.id() + " » créée.");
    }

    public Result update(NpcDefinition definition) {
        Path target = existingFileFor(definition.id());
        if (target == null) {
            return Result.fail("NOT_FOUND", "Aucune définition « " + definition.id() + " » à modifier.");
        }
        return writeAndReload(target, definition, "UPDATED", "Définition PNJ « " + definition.id() + " » modifiée.");
    }

    public Optional<NpcDefinition> find(String id) {
        return loader.loadDirectory(directory).loaded().stream()
                .filter(d -> d.id().equals(id)).findFirst();
    }

    public NpcLoadReport list() {
        return loader.loadDirectory(directory);
    }

    // ---- interne -----------------------------------------------------------------------------

    private Result writeAndReload(Path target, NpcDefinition definition, String code, String message) {
        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, NpcDefinitionYaml.render(definition), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        NpcLoadReport report = loader.loadDirectory(directory);
        boolean present = report.loaded().stream().anyMatch(d -> d.id().equals(definition.id()));
        if (!present) {
            return new Result(false, "ERROR",
                    "Le fichier a été écrit mais ne se recharge pas proprement — voir les erreurs.", target.getFileName().toString(), report);
        }
        return new Result(true, code, message, target.getFileName().toString(), report);
    }

    private Path existingFileFor(String id) {
        for (String ext : new String[] {".yml", ".yaml"}) {
            Path direct = directory.resolve(id + ext);
            if (Files.exists(direct)) {
                return direct;
            }
        }
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (var stream = Files.newDirectoryStream(directory, "*.y*ml")) {
            for (Path file : stream) {
                org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception ignored) {
                    continue;
                }
                if (id.equals(trim(yaml.getString("id")))) {
                    return file;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
