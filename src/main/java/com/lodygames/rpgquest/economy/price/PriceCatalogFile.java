package com.lodygames.rpgquest.economy.price;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Écriture sûre de {@code prices.yml} depuis PlugAdmin (issue #336). Mêmes garanties que
 * {@code entitlements.yml} (#474) :
 *
 * <ol>
 *   <li>le fichier actuel doit être valide — jamais d'écriture par-dessus un fichier que le serveur
 *       refuse déjà ;</li>
 *   <li>le texte final est relu par le <strong>vrai</strong> chargeur et doit redonner
 *       <em>exactement</em> le catalogue attendu — sinon rien n'est écrit ;</li>
 *   <li>écriture atomique (création sans jamais écraser un fichier apparu entre-temps) ;</li>
 *   <li>rechargement immédiat ; s'il ne redonne pas le catalogue attendu, l'ancien fichier est remis
 *       à l'octet près (ou retiré s'il n'existait pas) et rechargé.</li>
 * </ol>
 *
 * <p>Appelé hors du thread principal (thread agent) ; l'appelant tient le verrou de l'objet.</p>
 */
final class PriceCatalogFile {

    /** Refus : {@code code} stable pour PlugAdmin, {@code message} en français. */
    record Failure(String code, String message) {
    }

    /** Fichier actuel accepté par le serveur ; {@code bytes == null} : il n'existe pas (catalogue vide). */
    record Current(byte @Nullable [] bytes, YamlConfiguration yaml, PriceCatalog catalog) {
    }

    static final class Refusal extends Exception {
        final Failure failure;

        Refusal(String code, String message) {
            super(message, null, false, false);
            this.failure = new Failure(code, message);
        }
    }

    /** En-tête du fichier créé au premier enregistrement depuis PlugAdmin. */
    static final String HEADER = """
            # Catalogue des prix RPGQuest (prices.yml), géré depuis PlugAdmin → Prix et coûts (issue #336).
            # Aucune modification à la main n'est nécessaire. Aucun prix n'est jamais déduit : une offre de
            # marchand qui cite un prix absent ou désactivé est affichée « indisponible » et ne s'échange pas.
            """;

    private final Path file;
    private final Supplier<PriceCatalogLoader.Result> reload;
    private final Logger logger;

    PriceCatalogFile(Path file, Supplier<PriceCatalogLoader.Result> reload, Logger logger) {
        this.file = file;
        this.reload = reload;
        this.logger = logger;
    }

    Logger logger() {
        return logger;
    }

    /** Lit et valide le fichier actuel ; absent = catalogue vide, créé au premier enregistrement. */
    Current read() throws Refusal {
        YamlConfiguration yaml = newYaml();
        if (!Files.isRegularFile(file)) {
            return new Current(null, yaml, PriceCatalog.EMPTY);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new Refusal("CURRENT_INVALID", "prices.yml ne se relit pas (" + e.getMessage()
                    + ") : rien n'a été écrit. Le corriger d'abord (voir Diagnostics).");
        }
        PriceCatalogLoader.Result result = PriceCatalogLoader.load(yaml);
        if (!result.valid()) {
            throw new Refusal("CURRENT_INVALID", "prices.yml est actuellement refusé par le serveur ("
                    + summary(result.errors()) + ") : rien n'a été écrit, pour ne pas aggraver la situation.");
        }
        return new Current(bytes, yaml, result.catalog());
    }

    /**
     * Valide {@code text} avec le vrai chargeur, l'écrit atomiquement, recharge, et remet l'ancien
     * fichier si le catalogue rechargé n'est pas {@code expected}.
     *
     * @param previous octets actuels, {@code null} = création (refusée si un fichier est apparu)
     * @return {@code null} si tout a réussi, sinon le refus
     */
    @Nullable
    Failure write(byte @Nullable [] previous, String text, PriceCatalog expected, String what) {
        YamlConfiguration reread = newYaml();
        try {
            reread.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return new Failure("INVALID", "Rendu YAML illisible, rien n'a été écrit : " + e.getMessage());
        }
        PriceCatalogLoader.Result check = PriceCatalogLoader.load(reread);
        if (!check.valid()) {
            return new Failure("INVALID", "Refusé par le serveur, rien n'a été écrit : " + summary(check.errors()));
        }
        if (!expected.equals(check.catalog())) {
            return new Failure("INVALID", "Le fichier relu ne redonne pas exactement le catalogue voulu : rien n'a "
                    + "été écrit.");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (previous == null) {
                createAtomically(bytes);
            } else {
                writeAtomically(bytes);
            }
        } catch (FileAlreadyExistsException e) {
            return new Failure("STALE", "Un prices.yml est apparu entre-temps sur le serveur : il n'a pas été "
                    + "touché. Recharger la page.");
        } catch (IOException e) {
            return new Failure("ERROR", "Écriture de prices.yml impossible : " + e.getMessage());
        }
        PriceCatalogLoader.Result applied = reload.get();
        if (applied.valid() && expected.equals(applied.catalog())) {
            return null;
        }
        String why = applied.valid() ? "catalogue rechargé différent de celui écrit" : summary(applied.errors());
        try {
            if (previous == null) {
                Files.deleteIfExists(file);
            } else {
                writeAtomically(previous);
            }
        } catch (IOException e) {
            logger.error("Prix : restauration de {} IMPOSSIBLE après un rechargement refusé.", file, e);
            return new Failure("ERROR", "Rechargement refusé (" + why + ") ET restauration impossible ("
                    + e.getMessage() + ") : vérifier prices.yml sur le serveur.");
        }
        reload.get();
        logger.warn("Prix : écriture ({}) annulée, ancien prices.yml remis ({}).", what, why);
        return new Failure("ROLLED_BACK", "Le serveur n'a pas accepté le nouveau fichier (" + why + ") : "
                + (previous == null ? "le fichier a été retiré" : "l'ancienne version de prices.yml a été remise")
                + ", rien n'a changé.");
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        Path temp = temp();
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Fichier complet d'abord, puis lien physique : échoue sans rien écraser si le nom est déjà pris. */
    private void createAtomically(byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = temp();
        try {
            Files.write(temp, bytes);
            try {
                Files.createLink(file, temp);
            } catch (UnsupportedOperationException e) {
                Files.move(temp, file);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path temp() {
        return file.resolveSibling("." + file.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    static YamlConfiguration newYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        return yaml;
    }

    static String summary(List<String> errors) {
        List<String> shown = errors.subList(0, Math.min(3, errors.size()));
        return String.join(" ; ", shown) + (errors.size() > 3 ? " (+" + (errors.size() - 3) + ")" : "");
    }
}
