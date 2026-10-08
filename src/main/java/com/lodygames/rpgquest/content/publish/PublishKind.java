package com.lodygames.rpgquest.content.publish;

import com.lodygames.rpgquest.content.reload.ReloadFamily;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Les familles de contenu <strong>publiables</strong>, et la seule chose autorisée à désigner un
 * dossier (issue #47).
 *
 * <h2>Pourquoi une énumération et pas un chemin</h2>
 *
 * <p>Le navigateur n'envoie jamais de chemin. Il envoie une <em>famille</em> et un
 * <em>identifiant</em> ; le serveur en déduit le fichier. Cette énumération <strong>est</strong> la
 * liste blanche : ce qui n'y figure pas n'est pas publiable, et aucune chaîne venue d'un formulaire
 * ne peut produire un chemin qui n'en découle pas.</p>
 *
 * <p>Conséquence directe : {@code data.db}, les mondes, les secrets, les données Citizens brutes, le
 * JAR du plugin et toute configuration inconnue sont hors d'atteinte <strong>par construction</strong>,
 * pas par une liste d'interdits qu'on aurait pu oublier de compléter.</p>
 *
 * <h2>Trois familles, et pas six</h2>
 *
 * <p>Seules les familles dont la source de vérité est un <strong>fichier YAML</strong> éditable
 * depuis le Control Panel sont ici. Les PNJ, les mobs et les objets ont déjà des écritures
 * <em>runtime</em> propres côté serveur (stores dédiés, actions agent) : les forcer à passer par une
 * copie de fichier serait inventer un second chemin pour un problème déjà résolu. Voir le rapport
 * d'audit de #47.</p>
 */
public enum PublishKind {

    QUESTS("quests", ReloadFamily.QUESTS, "quête", "la quête"),
    STORIES("stories", ReloadFamily.STORIES, "story", "la story"),
    DIALOGUES("dialogues", ReloadFamily.DIALOGUES, "dialogue", "le dialogue");

    /**
     * Forme d'un identifiant de ressource, alignée sur {@code ContentId.KEY_PATTERN} du panel.
     *
     * <p>Aucun séparateur, aucun point : un identifiant ne peut donc pas désigner un chemin, même
     * avant la vérification de confinement qui suit.</p>
     */
    public static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final String directory;
    private final ReloadFamily family;
    private final String label;
    private final String article;

    PublishKind(String directory, ReloadFamily family, String label, String article) {
        this.directory = directory;
        this.family = family;
        this.label = label;
        this.article = article;
    }

    /** Nom du dossier sous {@code plugins/RPGQuest/}. */
    public String directory() {
        return directory;
    }

    /** La famille à recharger après publication — ciblée, jamais « tout ». */
    public ReloadFamily family() {
        return family;
    }

    public String label() {
        return label;
    }

    /** Libellé avec article, pour composer des phrases lisibles dans les messages. */
    public String article() {
        return article;
    }

    /** Le nom de fichier d'une ressource. Un seul format : {@code <slug>.yml}. */
    public String fileName(String slug) {
        return slug + ".yml";
    }

    /** Lecture tolérante d'une famille reçue d'un formulaire. Vide si inconnue. */
    public static Optional<PublishKind> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        for (PublishKind kind : values()) {
            if (kind.directory.equals(clean) || kind.name().toLowerCase(Locale.ROOT).equals(clean)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** Vrai si l'identifiant a une forme acceptable. Vérifié avant toute résolution de chemin. */
    public static boolean validSlug(String slug) {
        return slug != null && SLUG_PATTERN.matcher(slug).matches();
    }
}
