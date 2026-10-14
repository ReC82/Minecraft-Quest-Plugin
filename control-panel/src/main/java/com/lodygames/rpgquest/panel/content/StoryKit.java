package com.lodygames.rpgquest.panel.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Le « modèle d'histoire » téléchargeable depuis Import contenu (issue #211) : de quoi faire écrire
 * une histoire complète par une IA externe, sans qu'elle connaisse RPGQuest.
 *
 * <p>Deux natures de fichiers, volontairement séparées : les <strong>fiches narratives</strong>
 * ({@code .md}, en français courant, pour l'auteur) et les <strong>packs JSON</strong> (seuls
 * importables). Le guide pour l'IA est la partie pédagogique fixe suivie du contrat
 * {@link ContentPackTemplates#aiDocumentation()}, <em>généré</em> depuis les descripteurs du
 * moteur : le catalogue des types ne peut donc pas diverger du jeu.</p>
 *
 * <p>Liste blanche : seuls les fichiers de {@link #FILES} sont servis ; un nom inconnu ne résout
 * rien, et aucun chemin ne vient de la requête.</p>
 */
public final class StoryKit {

    /** Nom du guide servi (partie fixe + contrat généré). */
    public static final String GUIDE = "guide-ia-lodyquests.md";
    public static final String LILY_PACK = "exemple-pack-lily.json";
    public static final String ZIP_NAME = "modele-histoire-lodyquests.zip";

    private static final String BASE = "/content-packs/";
    private static final String GUIDE_BASE = "guide-ia-base.md";

    /** Un fichier du kit, dans l'ordre d'affichage. */
    public record KitFile(String name, String contentType, String label, String role) {
    }

    public static final List<KitFile> FILES = List.of(
            new KitFile("modele-histoire-lily.md", "text/markdown", "Fiche narrative Lily",
                    "fiche d'auteur remplie, en français courant — à donner à l'IA, PAS à importer"),
            new KitFile(LILY_PACK, "application/json", "Pack JSON Lily",
                    "exemple importable : 2 personnages, 1 story, 5 quêtes, 2 dialogues"),
            new KitFile("modele-histoire-vierge.md", "text/markdown", "Fiche narrative vierge",
                    "à remplir pour votre propre histoire"),
            new KitFile("modele-pack-vierge.json", "application/json", "Pack JSON vierge",
                    "squelette valide « À REMPLIR », point de départ de l'IA"),
            new KitFile("prompt-ia.txt", "text/plain", "Prompt prêt à copier",
                    "à coller dans l'IA avec la fiche, le guide et l'exemple"),
            new KitFile(GUIDE, "text/markdown", "Guide complet pour l'IA",
                    "format, règles, liens et capacités réelles du moteur"));

    private StoryKit() {
    }

    public static Optional<KitFile> file(String name) {
        return FILES.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    /** Contenu d'un fichier du kit ; vide pour un nom hors liste blanche. */
    public static Optional<String> content(String name) {
        if (file(name).isEmpty()) {
            return Optional.empty();
        }
        if (GUIDE.equals(name)) {
            return Optional.of(resource(GUIDE_BASE) + ContentPackTemplates.aiDocumentation());
        }
        return Optional.of(resource(name));
    }

    /** Le pack Lily, pour l'essai d'analyse en un clic. */
    public static String lilyPack() {
        return resource(LILY_PACK);
    }

    /** Le prompt, affiché tel quel sur la page pour être copié. */
    public static String prompt() {
        return resource("prompt-ia.txt");
    }

    /** Tout le kit en une archive : un seul téléchargement, même sur téléphone. */
    public static byte[] zip() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "LISEZ-MOI.txt", readme());
            for (KitFile f : FILES) {
                put(zip, f.name(), content(f.name()).orElseThrow());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String readme() {
        StringBuilder sb = new StringBuilder("""
                MODÈLE D'HISTOIRE RPGQUEST
                ==========================

                1. Télécharger ce modèle (c'est fait).
                2. Remplir la fiche narrative (modele-histoire-vierge.md) avec votre histoire,
                   ou garder modele-histoire-lily.md pour essayer.
                3. Ouvrir une IA (ChatGPT, Claude, Le Chat…), y joindre la fiche, le guide
                   guide-ia-lodyquests.md et l'exemple exemple-pack-lily.json, puis coller le
                   contenu de prompt-ia.txt.
                4. Enregistrer la réponse de l'IA dans un fichier .json.
                5. PlugAdmin > Import contenu : déposer le .json, lire l'analyse et l'aperçu.
                6. Confirmer « Enregistrer dans la source ». Rien n'est publié à ce moment-là.
                7. Publier ensuite sur DEV depuis « Changements en attente », puis lier les
                   personnages à un PNJ Citizens depuis la page PNJ.

                Seuls les fichiers .json sont importables. Les fiches .md sont pour l'auteur et l'IA.

                Contenu de l'archive :
                """);
        for (KitFile f : FILES) {
            sb.append("  - ").append(f.name()).append(" : ").append(f.role()).append('\n');
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String resource(String name) {
        try (InputStream in = StoryKit.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("Ressource du modèle d'histoire absente : " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
