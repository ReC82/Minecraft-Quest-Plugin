package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import com.lodygames.rpgquest.economy.merchant.model.MerchantOffer;
import com.lodygames.rpgquest.economy.merchant.model.OfferItemKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Administration des catalogues de marchands ({@code merchants/*.yml}) depuis PlugAdmin (issue #17) :
 * créer, modifier, dupliquer, activer / désactiver, supprimer. Mêmes garanties que le catalogue des
 * prix (#336) :
 *
 * <ol>
 *   <li>le texte écrit est relu par le <strong>vrai</strong> parseur et doit redonner exactement le
 *       marchand voulu — sinon rien n'est écrit ;</li>
 *   <li>contrôle de version (empreinte du fichier lu par le formulaire) : une modification faite
 *       entre-temps n'est jamais écrasée ;</li>
 *   <li>écriture atomique, création sans jamais écraser un fichier apparu entre-temps ;</li>
 *   <li>rechargement immédiat ; si le marchand rechargé n'est pas celui écrit (id dupliqué…),
 *       l'ancien fichier est remis à l'octet près (ou retiré s'il n'existait pas) et rechargé.</li>
 * </ol>
 *
 * <p>Un fichier d'exemple embarqué n'est jamais supprimé (il serait recréé au démarrage) ; un
 * catalogue cité par un dialogue non plus. Appelé hors du thread principal (thread agent).</p>
 */
public final class MerchantCatalogEditor {

    /** Clé d'un nouveau catalogue → id {@code rpgquest:<clé>}, fichier {@code <clé>.yml}. */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_-]{1,64}");
    public static final int TITLE_MAX = 120;
    /** Cases de contenu d'une vitrine de six lignes (la dernière ligne est réservée). */
    public static final int MAX_OFFERS = 45;

    /** Résultat : {@code code} stable pour PlugAdmin, {@code message} en français. */
    public record Outcome(boolean success, String code, String message, @Nullable MerchantDefinition merchant) {

        static Outcome refused(String code, String message) {
            return new Outcome(false, code, message, null);
        }
    }

    private static final String HEADER = """
            # Catalogue de marchand RPGQuest, géré depuis PlugAdmin → Marchands (issue #17).
            # Relié à un PNJ par une action de dialogue OPEN_MERCHANT. Voir MERCHANT_FORMAT.md.
            """;

    private final YamlMerchantRegistry registry;
    private final Logger logger;
    private final MerchantDefinitionParser parser = new MerchantDefinitionParser();

    public MerchantCatalogEditor(YamlMerchantRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    /** Empreinte (8 hex) du fichier du marchand chargé {@code id}, ou {@code ""} s'il est illisible. */
    public String version(NamespacedKey id) {
        return registry.fileOf(id).map(name -> {
            try {
                return fingerprint(Files.readAllBytes(registry.directory().resolve(name)));
            } catch (IOException e) {
                return "";
            }
        }).orElse("");
    }

    /**
     * Crée ({@code create}) ou remplace un marchand.
     *
     * @param expectedVersion version lue par le formulaire ({@code null} = pas de contrôle)
     */
    public synchronized Outcome save(MerchantDefinition wanted, @Nullable String expectedVersion, boolean create) {
        if (create) {
            Outcome refused = refuseNew(wanted.id());
            if (refused != null) {
                return refused;
            }
            return write(null, newFile(wanted.id()), new YamlConfiguration(), wanted, "CREATED",
                    "Marchand « " + plain(wanted.title()) + " » créé" + (wanted.enabled() ? "." : ", fermé.")
                            + " Le relier à un PNJ : action de dialogue « Ouvrir un marchand ».");
        }
        Optional<MerchantDefinition> existing = registry.find(wanted.id());
        if (existing.isEmpty()) {
            return Outcome.refused("NOT_FOUND", "Marchand « " + wanted.id() + " » introuvable : recharger la page.");
        }
        Current current;
        try {
            current = readCurrent(wanted.id(), expectedVersion);
        } catch (Refusal refusal) {
            return refusal.outcome;
        }
        if (wanted.equals(existing.get())) {
            return new Outcome(true, "UNCHANGED", "Aucune modification : le marchand « " + plain(wanted.title())
                    + " » est déjà enregistré ainsi.", existing.get());
        }
        return write(current.bytes(), current.file(), current.yaml(), wanted, "UPDATED", "Marchand « "
                + plain(wanted.title()) + " » modifié. Un joueur dont la vitrine est ouverte la voit se mettre à jour "
                + "au prochain clic, sans rien échanger.");
    }

    /** Copie {@code sourceId} sous {@code rpgquest:<newKey>}, <strong>fermée</strong>. */
    public synchronized Outcome duplicate(NamespacedKey sourceId, String newKey, @Nullable String newTitle) {
        Optional<MerchantDefinition> source = registry.find(sourceId);
        if (source.isEmpty()) {
            return Outcome.refused("NOT_FOUND", "Marchand « " + sourceId + " » introuvable : recharger la page.");
        }
        if (!KEY.matcher(newKey).matches()) {
            return Outcome.refused("INVALID", "Identifiant « " + newKey + " » invalide : minuscules, chiffres, _ ou - "
                    + "(64 caractères au plus).");
        }
        NamespacedKey newId = new NamespacedKey(MerchantDefinitionParser.DEFAULT_NAMESPACE, newKey);
        Outcome refused = refuseNew(newId);
        if (refused != null) {
            return refused;
        }
        String title = newTitle == null || newTitle.isBlank() ? source.get().title() + " (copie)" : newTitle.trim();
        if (title.length() > TITLE_MAX) {
            title = title.substring(0, TITLE_MAX).trim();
        }
        return write(null, newFile(newId), new YamlConfiguration(), source.get().with(newId, title, false),
                "DUPLICATED", "Copie « " + plain(title) + " » créée, fermée : la modifier, puis l'ouvrir. Aucun "
                        + "dialogue ne la cite encore.");
    }

    /** Ouvre ou ferme une boutique ; ses offres ne changent pas. */
    public synchronized Outcome setEnabled(NamespacedKey id, boolean enabled) {
        Optional<MerchantDefinition> existing = registry.find(id);
        if (existing.isEmpty()) {
            return Outcome.refused("NOT_FOUND", "Marchand « " + id + " » introuvable : recharger la page.");
        }
        if (existing.get().enabled() == enabled) {
            return new Outcome(true, "UNCHANGED", "Le marchand « " + plain(existing.get().title()) + " » est déjà "
                    + (enabled ? "ouvert" : "fermé") + ".", existing.get());
        }
        Current current;
        try {
            current = readCurrent(id, null);
        } catch (Refusal refusal) {
            return refusal.outcome;
        }
        MerchantDefinition wanted = existing.get().with(id, existing.get().title(), enabled);
        return write(current.bytes(), current.file(), current.yaml(), wanted, "TOGGLED", enabled
                ? "Marchand « " + plain(wanted.title()) + " » ouvert : ses offres s'échangent de nouveau."
                : "Marchand « " + plain(wanted.title()) + " » fermé : les dialogues qui l'ouvrent affichent « boutique "
                + "fermée » et les vitrines ouvertes se ferment au prochain clic. Aucun solde ni objet n'est touché.");
    }

    /**
     * Supprime le fichier du marchand. Refusé pour un exemple embarqué (recréé au démarrage) et
     * pour un marchand cité par un dialogue ({@code usages} non vide, calculé par l'appelant).
     */
    public synchronized Outcome delete(NamespacedKey id, @Nullable String expectedVersion, List<String> usages) {
        Optional<MerchantDefinition> existing = registry.find(id);
        Optional<String> fileName = registry.fileOf(id);
        if (existing.isEmpty() || fileName.isEmpty()) {
            return Outcome.refused("NOT_FOUND", "Marchand « " + id + " » introuvable : recharger la page.");
        }
        if (YamlMerchantRegistry.isBundled(fileName.get())) {
            return Outcome.refused("IN_USE", "« " + fileName.get() + " » est un exemple fourni avec le plugin : il "
                    + "serait recréé au prochain démarrage. Le fermer plutôt que le supprimer.");
        }
        if (!usages.isEmpty()) {
            return Outcome.refused("IN_USE", "Marchand cité par " + String.join(", ", usages) + " : retirer d'abord "
                    + "l'action « Ouvrir un marchand » de ces dialogues, ou fermer la boutique. Rien n'a été supprimé.");
        }
        Current current;
        try {
            current = readCurrent(id, expectedVersion);
        } catch (Refusal refusal) {
            return refusal.outcome;
        }
        try {
            Files.delete(current.file());
        } catch (IOException e) {
            return Outcome.refused("ERROR", "Suppression de " + fileName.get() + " impossible : " + e.getMessage());
        }
        MerchantLoadReport report = registry.reload();
        if (report.loaded().stream().anyMatch(m -> m.id().equals(id))) {
            restore(current.file(), current.bytes(), "suppression " + id);
            return Outcome.refused("ROLLED_BACK", "Le marchand est encore chargé après suppression (autre fichier "
                    + "au même identifiant ?) : le fichier a été remis, rien n'a changé.");
        }
        logger.info("Marchands : « {} » ({}) supprimé depuis PlugAdmin.", id, fileName.get());
        return new Outcome(true, "DELETED", "Marchand « " + plain(existing.get().title()) + " » supprimé ("
                + fileName.get() + "). Aucun solde ni objet de joueur n'est touché.", null);
    }

    // ---- Écriture sûre ------------------------------------------------------------------------------

    private record Current(Path file, byte[] bytes, YamlConfiguration yaml) {
    }

    private static final class Refusal extends Exception {
        final Outcome outcome;

        Refusal(String code, String message) {
            super(message, null, false, false);
            this.outcome = Outcome.refused(code, message);
        }
    }

    private Current readCurrent(NamespacedKey id, @Nullable String expectedVersion) throws Refusal {
        Optional<String> fileName = registry.fileOf(id);
        if (fileName.isEmpty()) {
            throw new Refusal("NOT_FOUND", "Fichier du marchand « " + id + " » introuvable : recharger la page.");
        }
        Path file = registry.directory().resolve(fileName.get());
        byte[] bytes;
        YamlConfiguration yaml = newYaml();
        try {
            bytes = Files.readAllBytes(file);
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new Refusal("CURRENT_INVALID", fileName.get() + " ne se relit pas (" + e.getMessage()
                    + ") : rien n'a été écrit.");
        }
        if (expectedVersion != null && !expectedVersion.isBlank() && !fingerprint(bytes).equals(expectedVersion)) {
            throw new Refusal("STALE", "Le marchand « " + id + " » a changé depuis l'ouverture du formulaire : rien "
                    + "n'a été écrit. Recharger la page puis refaire la modification.");
        }
        return new Current(file, bytes, yaml);
    }

    private @Nullable Outcome refuseNew(NamespacedKey id) {
        if (registry.find(id).isPresent()) {
            return Outcome.refused("EXISTS", "Un marchand « " + id + " » existe déjà : choisir un autre identifiant.");
        }
        if (!MerchantDefinitionParser.DEFAULT_NAMESPACE.equals(id.getNamespace()) || !KEY.matcher(id.getKey()).matches()) {
            return Outcome.refused("INVALID", "Identifiant « " + id + " » invalide : minuscules, chiffres, _ ou - (64 "
                    + "caractères au plus).");
        }
        if (Files.exists(newFile(id))) {
            return Outcome.refused("EXISTS", "Un fichier « " + newFile(id).getFileName() + " » existe déjà (marchand "
                    + "refusé au chargement ?) : il n'a pas été touché. Choisir un autre identifiant.");
        }
        return null;
    }

    private Path newFile(NamespacedKey id) {
        return registry.directory().resolve(id.getKey() + ".yml");
    }

    private Outcome write(byte @Nullable [] previous, Path file, YamlConfiguration yaml, MerchantDefinition wanted,
                          String code, String message) {
        if (wanted.offers().size() > MAX_OFFERS) {
            return Outcome.refused("INVALID", MAX_OFFERS + " offres au plus par marchand (une vitrine de six lignes).");
        }
        String text = (previous == null ? HEADER : "") + render(yaml, wanted);
        YamlConfiguration reread = newYaml();
        try {
            reread.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            return Outcome.refused("INVALID", "Rendu YAML illisible, rien n'a été écrit : " + e.getMessage());
        }
        MerchantDefinitionParser.ParseResult check = parser.parse(file.getFileName().toString(), reread);
        if (!check.isSuccess()) {
            return Outcome.refused("INVALID", "Refusé par le serveur, rien n'a été écrit : " + summary(check.issues()));
        }
        if (!wanted.equals(check.merchant())) {
            return Outcome.refused("INVALID", "Le fichier relu ne redonne pas exactement le marchand voulu : rien n'a "
                    + "été écrit.");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (previous == null) {
                createAtomically(file, bytes);
            } else {
                writeAtomically(file, bytes);
            }
        } catch (FileAlreadyExistsException e) {
            return Outcome.refused("STALE", "Un fichier " + file.getFileName() + " est apparu entre-temps : il n'a pas "
                    + "été touché. Recharger la page.");
        } catch (IOException e) {
            return Outcome.refused("ERROR", "Écriture de " + file.getFileName() + " impossible : " + e.getMessage());
        }
        MerchantLoadReport report = registry.reload();
        Optional<MerchantDefinition> applied = report.loaded().stream().filter(m -> m.id().equals(wanted.id()))
                .findFirst();
        if (applied.isPresent() && wanted.equals(applied.get())
                && file.getFileName().toString().equals(report.files().get(wanted.id()))) {
            logger.info("Marchands : « {} » écrit depuis PlugAdmin ({}) — {} offre(s), type {}{}.", wanted.id(), code,
                    wanted.offers().size(), wanted.type(), wanted.enabled() ? "" : ", fermé");
            return new Outcome(true, code, message, wanted);
        }
        String why = report.issues().stream().filter(i -> i.file().equals(file.getFileName().toString()))
                .map(MerchantLoadIssue::message).findFirst().orElse("marchand rechargé différent de celui écrit");
        Outcome restored = restore(file, previous, code + " " + wanted.id());
        return restored != null ? restored : Outcome.refused("ROLLED_BACK", "Le serveur n'a pas accepté le nouveau "
                + "fichier (" + why + ") : " + (previous == null ? "le fichier a été retiré" : "l'ancienne version a "
                + "été remise") + ", rien n'a changé.");
    }

    /** Remet {@code previous} (ou retire le fichier s'il n'existait pas) ; {@code null} si la remise a réussi. */
    private @Nullable Outcome restore(Path file, byte @Nullable [] previous, String what) {
        try {
            if (previous == null) {
                Files.deleteIfExists(file);
            } else {
                writeAtomically(file, previous);
            }
        } catch (IOException e) {
            logger.error("Marchands : restauration de {} IMPOSSIBLE ({}).", file, what, e);
            return Outcome.refused("ERROR", "Rechargement refusé ET restauration impossible (" + e.getMessage()
                    + ") : vérifier " + file.getFileName() + " sur le serveur.");
        }
        registry.reload();
        logger.warn("Marchands : écriture ({}) annulée, ancien fichier remis.", what);
        return null;
    }

    /** Remplace titre, type, état et offres ; les autres clés et les commentaires d'en-tête restent. */
    static String render(YamlConfiguration yaml, MerchantDefinition merchant) {
        yaml.set("id", merchant.id().toString());
        yaml.set("title", merchant.title());
        yaml.set("type", merchant.type().name());
        yaml.set("enabled", merchant.enabled());
        List<Map<String, Object>> offers = new ArrayList<>();
        for (MerchantOffer offer : merchant.offers()) {
            Map<String, Object> line = new LinkedHashMap<>();
            if (offer.linked()) {
                line.put("price-ref", offer.priceRef());
            } else {
                line.put("direction", offer.direction().name());
                if (offer.itemKind() == OfferItemKind.VANILLA) {
                    line.put("material", offer.vanillaMaterial().name());
                } else {
                    line.put("custom-item", offer.customItemId().toString());
                }
                line.put("quantity", offer.quantity());
                line.put("price", offer.price());
            }
            if (offer.requiredPermission() != null) {
                line.put("required-permission", offer.requiredPermission());
            }
            if (offer.requiredQuestId() != null) {
                line.put("required-quest", offer.requiredQuestId().toString());
                line.put("required-quest-state", offer.requiredQuestState().name());
            }
            if (offer.requiredLevel() != null) {
                line.put("required-level", offer.requiredLevel());
            }
            offers.add(line);
        }
        yaml.set("offers", offers);
        return yaml.saveToString();
    }

    private static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Path temp = temp(file);
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
    private static void createAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = temp(file);
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

    /** Fichier temporaire caché, sans extension .yml : jamais lu par le chargeur. */
    private static Path temp(Path file) {
        return file.resolveSibling("." + file.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private static YamlConfiguration newYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        return yaml;
    }

    static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String summary(List<MerchantLoadIssue> issues) {
        List<String> shown = issues.stream().limit(3).map(MerchantLoadIssue::message).toList();
        return String.join(" ; ", shown) + (issues.size() > 3 ? " (+" + (issues.size() - 3) + ")" : "");
    }

    private static String plain(String miniMessage) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(miniMessage);
    }
}
