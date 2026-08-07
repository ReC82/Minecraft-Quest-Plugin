package be.lloyd.rpgquest.config;

import be.lloyd.rpgquest.progression.model.SkillType;
import java.util.List;

/**
 * Paramètres de l'export périodique de snapshot (mission étape 21, point 4 :
 * "toutes les lectures doivent utiliser des snapshots/caches ou opérations
 * asynchrones"). Ne configure que le côté plugin — le module {@code web-api}
 * séparé lit ce fichier depuis le disque, jamais la base SQLite directement
 * (mission point 2). Voir {@link be.lloyd.rpgquest.web.WebSnapshotWriter} et
 * docs/WEB_API.md.
 */
public record WebExportConfig(
        boolean enabled,
        String outputDirectory,
        int intervalSeconds,
        boolean includeConnectedPlayers,
        int leaderboardSize,
        List<SkillType> leaderboardSkills,
        List<Announcement> announcements
) {
    public record Announcement(String title, String body) {
    }
}
