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
     * @param code {@code CREATED} / {@code UPDATED} / {@code REPLACED} / {@code CONFLICT} / {@code EXISTS} / {@code NOT_FOUND} / {@code INVALID}
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

    /** Nom du fichier qui porte ce profil, {@code null} s'il n'existe pas (issue #214). */
    public String fileName(NamespacedKey id) {
        Path file = id == null ? null : existingFileFor(id);
        return file == null ? null : file.getFileName().toString();
    }

    /** Empreinte SHA-256 (hex) du fichier actuel de ce profil, {@code null} s'il n'existe pas (issue #214). */
    public String fileSha(NamespacedKey id) {
        Path file = id == null ? null : existingFileFor(id);
        if (file == null) {
            return null;
        }
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Issue #214 — écrit un profil importé, et lui seul.
     *
     * <ul>
     *   <li>nouveau profil : {@code expectedSha} doit être vide — un profil du même identifiant apparu
     *       depuis la vérification est un {@code CONFLICT}, jamais un écrasement ;</li>
     *   <li>profil existant : exige {@code replace} ({@code EXISTS} sinon) et que le fichier soit encore
     *       celui vérifié ({@code expectedSha}, {@code CONFLICT} sinon). L'ancien fichier est copié dans
     *       {@code backupDirectory} avant l'écriture ;</li>
     *   <li>l'écriture elle-même est celle de #461 : relecture par le parseur avant, écriture atomique,
     *       {@code ROLLED_BACK} si le dossier ne recharge pas le profil.</li>
     * </ul>
     *
     * @param backupDirectory dossier des sauvegardes, hors de {@code mobs/} (le chargeur ne doit jamais les lire)
     */
    public synchronized Result importDefinition(SpecialMobDefinition definition, boolean replace, String expectedSha,
                                                Path backupDirectory) {
        Path existing = existingFileFor(definition.id());
        String expected = expectedSha == null ? "" : expectedSha.trim();
        if (existing == null) {
            if (!expected.isEmpty()) {
                return Result.fail("CONFLICT", "Le profil « " + definition.id() + " » a disparu depuis la "
                        + "vérification : rien n'a été écrit. Vérifier à nouveau le fichier.");
            }
            return writeAndReload(fileFor(definition.id()), definition, "CREATED",
                    "Profil « " + definition.id() + " » importé (nouveau).");
        }
        if (!replace) {
            return Result.fail("EXISTS", "Un profil « " + definition.id() + " » existe déjà : cocher « Remplacer "
                    + "le profil existant » après avoir relu les différences. Rien n'a été écrit.");
        }
        String current;
        byte[] previous;
        try {
            previous = Files.readAllBytes(existing);
            current = sha256(previous);
        } catch (IOException e) {
            return Result.fail("ERROR", "Lecture du profil actuel impossible : " + e.getMessage());
        }
        if (!current.equals(expected)) {
            return Result.fail("CONFLICT", "Le profil « " + definition.id() + " » a été modifié depuis la "
                    + "vérification : rien n'a été écrit. Vérifier à nouveau pour voir les nouvelles différences.");
        }
        String backup;
        try {
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
            Path dir = backupDirectory.resolve(stamp);
            Files.createDirectories(dir);
            Path copy = dir.resolve(existing.getFileName().toString());
            Files.write(copy, previous);
            backup = backupDirectory.getFileName() + "/" + stamp + "/" + existing.getFileName();
        } catch (IOException | UncheckedIOException e) {
            return Result.fail("ERROR", "Sauvegarde de l'ancien profil impossible (" + e.getMessage()
                    + ") : rien n'a été écrit.");
        }
        Result r = writeAndReload(existing, definition, "REPLACED",
                "Profil « " + definition.id() + " » remplacé ; ancienne version sauvegardée dans " + backup + ".");
        return r.ok() ? r : new Result(false, r.code(), r.message() + " (sauvegarde : " + backup + ")", r.file(),
                r.report());
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
