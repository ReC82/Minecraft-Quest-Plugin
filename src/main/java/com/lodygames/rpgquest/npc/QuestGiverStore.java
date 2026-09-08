package com.lodygames.rpgquest.npc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Pose le champ {@code giver:} d'une quête <strong>existante</strong> sur disque, sans jamais
 * réécrire tout le YAML : localise le fichier de la quête (par son {@code id:}, jamais par un
 * chemin fourni), applique {@link QuestGiverEditor} (transformation de texte), puis écrit de façon
 * atomique. Le rechargement du moteur de quêtes est du ressort de l'appelant.
 *
 * <p>Purement IO : testable avec {@code @TempDir}.</p>
 */
public final class QuestGiverStore {

    private static final String DEFAULT_NAMESPACE = "rpgquest";

    private final Path questsDirectory;

    public QuestGiverStore(Path questsDirectory) {
        this.questsDirectory = questsDirectory;
    }

    /** @param code {@code SET} / {@code UNKNOWN_QUEST} / {@code ERROR} */
    public record Result(boolean ok, String code, String message, String file) {
        static Result fail(String code, String message) {
            return new Result(false, code, message, null);
        }
    }

    public Result setGiver(String questId, String npcId) {
        String wanted = normalize(questId);
        if (wanted == null) {
            return Result.fail("UNKNOWN_QUEST", "Identifiant de quête invalide : « " + questId + " ».");
        }
        if (!Files.isDirectory(questsDirectory)) {
            return Result.fail("UNKNOWN_QUEST", "Aucun dossier de quêtes.");
        }
        Path target = null;
        try (var stream = Files.newDirectoryStream(questsDirectory, "*.y*ml")) {
            for (Path file : stream) {
                org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
                try {
                    yaml.load(file.toFile());
                } catch (Exception ignored) {
                    continue;
                }
                if (wanted.equals(normalize(yaml.getString("id")))) {
                    target = file;
                    break;
                }
            }
        } catch (IOException e) {
            return Result.fail("ERROR", "Lecture du dossier de quêtes impossible : " + e.getMessage());
        }
        if (target == null) {
            return Result.fail("UNKNOWN_QUEST", "Quête introuvable : « " + questId + " ».");
        }

        try {
            String original = Files.readString(target, StandardCharsets.UTF_8);
            String updated = QuestGiverEditor.setGiver(original, npcId);
            if (updated.equals(original)) {
                return new Result(true, "SET", "Le donneur « " + npcId + " » était déjà en place.", target.getFileName().toString());
            }
            Path tmp = questsDirectory.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, updated, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(true, "SET", "Donneur « " + npcId + " » attribué à la quête.", target.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            return Result.fail("ERROR", "Écriture impossible : " + e.getMessage());
        }
    }

    private static String normalize(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String trimmed = rawId.trim().toLowerCase(Locale.ROOT);
        return trimmed.contains(":") ? trimmed : DEFAULT_NAMESPACE + ":" + trimmed;
    }
}
