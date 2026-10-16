package com.lodygames.rpgquest.entitlement.reward;

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
 * Écriture sûre de {@code entitlements.yml} depuis PlugAdmin, partagée par l'éditeur de tables
 * (#177) et l'éditeur du catalogue (#474). Mêmes garanties que l'écriture des profils de mobs (#461) :
 *
 * <ol>
 *   <li>le fichier actuel doit être valide — jamais d'écriture par-dessus un fichier que le serveur
 *       refuse déjà ;</li>
 *   <li>le texte final est relu par le <strong>vrai</strong> chargeur et doit redonner
 *       <em>exactement</em> le catalogue attendu (interrupteur, récompenses, tables) — sinon rien
 *       n'est écrit ;</li>
 *   <li>écriture atomique (fichier temporaire puis remplacement ; création sans jamais écraser un
 *       fichier apparu entre-temps) ;</li>
 *   <li>rechargement immédiat ; s'il ne redonne pas le catalogue attendu, l'ancien fichier est remis
 *       à l'octet près (ou retiré s'il n'existait pas) et rechargé.</li>
 * </ol>
 *
 * <p>Un seul verrou pour les deux éditeurs : deux écritures ne se croisent jamais. Appelé hors du
 * thread principal (thread agent).</p>
 */
final class EntitlementsFile {

    /** Refus : {@code code} stable pour PlugAdmin, {@code message} en français. */
    record Failure(String code, String message) {
    }

    /** Fichier actuel accepté par le serveur ; {@code bytes == null} : il n'existe pas. */
    record Current(byte @Nullable [] bytes, YamlConfiguration yaml, RewardCatalog catalog) {
    }

    /** Refus levé pendant la lecture du fichier actuel. */
    static final class Refusal extends Exception {
        final Failure failure;

        Refusal(String code, String message) {
            super(message, null, false, false);
            this.failure = new Failure(code, message);
        }
    }

    private final Path file;
    private final Supplier<RewardCatalogLoader.Result> reload;
    private final Logger logger;

    /**
     * @param reload relit le fichier et applique le catalogue (ex. {@link RewardEntitlementService#reloadNow()})
     */
    EntitlementsFile(Path file, Supplier<RewardCatalogLoader.Result> reload, Logger logger) {
        this.file = file;
        this.reload = reload;
        this.logger = logger;
    }

    Path path() {
        return file;
    }

    Logger logger() {
        return logger;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /** Lit et valide le fichier actuel ; absent = refus {@code NO_CATALOG}. */
    Current read() throws Refusal {
        if (!exists()) {
            throw new Refusal("NO_CATALOG", "Aucun catalogue de récompenses sur ce serveur (entitlements.yml absent) : "
                    + "le créer d'abord depuis PlugAdmin (Récompenses → « Créer le catalogue »).");
        }
        byte[] bytes;
        YamlConfiguration yaml = newYaml();
        try {
            bytes = Files.readAllBytes(file);
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new Refusal("CURRENT_INVALID", "entitlements.yml ne se relit pas (" + e.getMessage()
                    + ") : rien n'a été écrit. Le corriger d'abord (voir Diagnostics).");
        }
        RewardCatalogLoader.Result result = RewardCatalogLoader.load(yaml);
        if (!result.valid()) {
            throw new Refusal("CURRENT_INVALID", "entitlements.yml est actuellement refusé par le serveur ("
                    + summary(result.errors()) + ") : rien n'a été écrit, pour ne pas aggraver la situation. "
                    + "Le corriger d'abord.");
        }
        return new Current(bytes, yaml, result.catalog());
    }

    /**
     * Valide {@code text} avec le vrai chargeur, l'écrit atomiquement, recharge, et remet l'ancien
     * fichier si le catalogue rechargé n'est pas {@code expected}.
     *
     * @param previous octets actuels, {@code null} = création (refusée si un fichier est apparu)
     * @param what     objet de l'écriture, pour le journal (« table x », « récompense y »…)
     * @return {@code null} si tout a réussi, sinon le refus
     */
    @Nullable
    Failure write(byte @Nullable [] previous, String text, RewardCatalog expected, String what) {
        // 1. Relecture par le vrai chargeur, AVANT toute écriture.
        YamlConfiguration reread = newYaml();
        try {
            reread.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return new Failure("INVALID", "Rendu YAML illisible, rien n'a été écrit : " + e.getMessage());
        }
        RewardCatalogLoader.Result check = RewardCatalogLoader.load(reread);
        if (!check.valid()) {
            return new Failure("INVALID", "Refusé par le serveur, rien n'a été écrit : " + summary(check.errors()));
        }
        if (!expected.equals(check.catalog())) {
            return new Failure("INVALID", "Le fichier relu ne redonne pas exactement le catalogue voulu : rien n'a "
                    + "été écrit.");
        }
        // 2. Écriture atomique.
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (previous == null) {
                createAtomically(bytes);
            } else {
                writeAtomically(bytes);
            }
        } catch (FileAlreadyExistsException e) {
            return new Failure("EXISTS", "Un entitlements.yml est apparu entre-temps sur le serveur : il n'a pas été "
                    + "touché. Recharger la page.");
        } catch (IOException e) {
            return new Failure("ERROR", "Écriture de entitlements.yml impossible : " + e.getMessage());
        }
        // 3. Rechargement ; à défaut, retour à l'état d'avant, à l'octet près.
        RewardCatalogLoader.Result applied = reload.get();
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
            logger.error("Récompenses : restauration de {} IMPOSSIBLE après un rechargement refusé.", file, e);
            return new Failure("ERROR", "Rechargement refusé (" + why + ") ET restauration impossible ("
                    + e.getMessage() + ") : vérifier entitlements.yml sur le serveur.");
        }
        reload.get();
        logger.warn("Récompenses : écriture ({}) annulée, ancien entitlements.yml remis ({}).", what, why);
        return new Failure("ROLLED_BACK", "Le serveur n'a pas accepté le nouveau fichier (" + why + ") : "
                + (previous == null ? "le fichier a été retiré" : "l'ancienne version de entitlements.yml a été remise")
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

    /**
     * Création : fichier complet d'abord, puis lien physique vers le nom définitif — l'opération échoue
     * (sans rien écraser) si un fichier porte déjà ce nom.
     */
    private void createAtomically(byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = temp();
        try {
            Files.write(temp, bytes);
            try {
                Files.createLink(file, temp);
            } catch (UnsupportedOperationException e) {
                Files.move(temp, file); // sans REPLACE_EXISTING : refuse un fichier existant
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
