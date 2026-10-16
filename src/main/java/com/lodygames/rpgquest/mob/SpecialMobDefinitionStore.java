package com.lodygames.rpgquest.mob;

import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
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

    /**
     * @param code {@code CREATED} / {@code UPDATED} / {@code EXISTS} / {@code NOT_FOUND} / {@code INVALID}
     *             (refusé avant écriture) / {@code ROLLED_BACK} (écrit, non rechargé, ancienne version remise)
     *             / {@code ERROR}
     */
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

    /** Vrai si un fichier porte déjà ce profil, même illisible (issue #461). */
    public boolean hasFile(NamespacedKey id) {
        return id != null && existingFileFor(id) != null;
    }

    public SpecialMobLoadReport list() {
        return loader.loadDirectory(directory);
    }

    // ---- interne -----------------------------------------------------------------------------

    /**
     * Issue #461 : (1) le texte final est relu par le parseur réel <em>avant</em> toute écriture et doit
     * redonner la définition demandée -- sinon rien n'est écrit ; (2) écriture atomique ; (3) si le
     * dossier ne recharge pas le profil après écriture, l'ancien contenu est remis (ou le nouveau
     * fichier retiré pour une création) : jamais de fichier illisible laissé en place.
     */
    private Result writeAndReload(Path target, SpecialMobDefinition definition, String code, String message) {
        String text = SpecialMobDefinitionYaml.render(definition);
        String invalid = validateRendered(target.getFileName().toString(), text, definition);
        if (invalid != null) {
            return Result.fail("INVALID", "Profil refusé avant écriture (aucun fichier modifié) : " + invalid);
        }
        byte[] previous;
        try {
            Files.createDirectories(directory);
            previous = Files.exists(target) ? Files.readAllBytes(target) : null;
            writeAtomically(target, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
        SpecialMobLoadReport report = loader.loadDirectory(directory);
        boolean present = report.loaded().stream().anyMatch(d -> d.id().equals(definition.id()));
        if (present) {
            return new Result(true, code, message, target.getFileName().toString(), report);
        }
        String restored;
        try {
            if (previous == null) {
                Files.deleteIfExists(target);
                restored = "le nouveau fichier a été retiré";
            } else {
                writeAtomically(target, previous);
                restored = "l'ancienne version du fichier a été remise à l'identique";
            }
        } catch (IOException | UncheckedIOException e) {
            return new Result(false, "ERROR", "Le profil ne se recharge pas et la restauration a échoué ("
                    + e.getMessage() + ") — fichier à contrôler.", target.getFileName().toString(), report);
        }
        return new Result(false, "ROLLED_BACK", "Le profil écrit ne se rechargeait pas : " + restored
                + " — voir les erreurs.", target.getFileName().toString(), report);
    }

    /** {@code null} si {@code text} se relit en une définition équivalente à {@code expected}. */
    static String validateRendered(String fileName, String text, SpecialMobDefinition expected) {
        for (Set<String> values : List.of(expected.allowedWorlds(), expected.allowedBiomes(), expected.allowedZones())) {
            for (String value : values) {
                if (value.isBlank() || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                    return "valeur de monde / biome / zone invalide « " + value.strip()
                            + " » (une seule valeur par entrée, sans espace ni retour à la ligne).";
                }
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return "YAML généré invalide : " + e.getMessage();
        }
        SpecialMobDefinitionParser.ParseResult parsed = new SpecialMobDefinitionParser().parse(fileName, yaml);
        if (!parsed.isSuccess()) {
            return parsed.issues().stream().map(SpecialMobLoadIssue::message)
                    .collect(java.util.stream.Collectors.joining(" ; "));
        }
        SpecialMobDefinition d = parsed.definition();
        List<String> diffs = new ArrayList<>();
        compare(diffs, "identifiant", d.id(), expected.id());
        compare(diffs, "nom affiché", d.displayName(), expected.displayName());
        compare(diffs, "Mondes autorisés", d.allowedWorlds(), lowercase(expected.allowedWorlds()));
        compare(diffs, "Biomes autorisés", d.allowedBiomes(), lowercase(expected.allowedBiomes()));
        compare(diffs, "Zones autorisées", d.allowedZones(), lowercase(expected.allowedZones()));
        compare(diffs, "capacités", d.abilities(), expected.abilities());
        compare(diffs, "drops", d.drops(), expected.drops());
        compare(diffs, "récompenses de participation", d.participationRewards(), expected.participationRewards());
        return diffs.isEmpty() ? null : "relecture différente pour : " + String.join(", ", diffs) + ".";
    }

    private static void compare(List<String> diffs, String label, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            diffs.add(label);
        }
    }

    private static Set<String> lowercase(Set<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = directory.resolve("." + target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
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
