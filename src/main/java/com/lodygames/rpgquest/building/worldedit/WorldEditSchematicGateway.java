package com.lodygames.rpgquest.building.worldedit;

import com.lodygames.rpgquest.building.SchematicGateway;
import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * L'unique classe de RPGQuest qui connaisse WorldEdit (issue #213, lot « placement »).
 *
 * <h2>Pourquoi tout est ici, et nulle part ailleurs</h2>
 *
 * <p>WorldEdit est une dépendance d'infrastructure remplaçable. La confiner à une seule classe a un
 * effet concret et mesurable : <strong>le reste du lot se teste sans elle</strong>. La rotation,
 * l'emprise, les refus, l'enchaînement des étapes sont du code RPGQuest ordinaire, vérifié par des
 * tests qui n'ont jamais vu un {@code BlockVector3}. Ce qui reste ici est de la traduction, et c'est
 * précisément la part qu'aucun test honnête ne peut couvrir sans serveur.</p>
 *
 * <h2>Le sens de rotation n'est pas supposé : il est mesuré</h2>
 *
 * <p>La convention de signe de {@code AffineTransform#rotateY} est une décision interne à WorldEdit.
 * La deviner par le raisonnement serait un pari, et un pari perdu pose la hutte à l'envers — ce qui
 * ne se constate qu'en jeu, après avoir écrasé du terrain.</p>
 *
 * <p>Alors on ne devine pas : on <strong>applique la transformation aux coins du schematic</strong>,
 * on compare l'emprise obtenue à celle que le domaine a calculée, et on ne colle que si les deux
 * coïncident. Si le signe opposé est celui qui correspond, on l'utilise et on le journalise une
 * fois. Si aucun des deux ne correspond, on <strong>refuse</strong> plutôt que d'écrire dans le
 * monde. L'emprise annoncée à l'administrateur est ainsi toujours celle qui sera réellement
 * occupée.</p>
 *
 * <h2>Indisponibilité</h2>
 *
 * <p>Même conception que le pont Citizens : absence du plugin ou {@link LinkageError} (build
 * incompatible) donnent un refus <strong>nommé</strong>, jamais un échec de démarrage.</p>
 */
public final class WorldEditSchematicGateway implements SchematicGateway {

    /** Sponge v3 : le format écrit par WorldEdit moderne, et celui que lit la version installée. */
    private static final ClipboardFormat FORMAT = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC;

    private final Path directory;
    private final Logger logger;
    private final String unavailableReason;

    /** Journalisé une seule fois : le constat du sens de rotation réel du moteur. */
    private boolean rotationSignLogged;

    public WorldEditSchematicGateway(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
        this.unavailableReason = probe();
    }

    /**
     * Décide une fois pour toutes si le moteur est exploitable.
     *
     * <p>Deux causes d'indisponibilité, qui méritent deux messages différents : le plugin n'est pas
     * installé, ou il est installé mais sa build ne correspond pas aux classes attendues.</p>
     */
    private String probe() {
        try {
            if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
                return "WorldEdit n'est pas installé sur ce serveur.";
            }
            if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
                return "WorldEdit est installé mais désactivé.";
            }
            // Touche réellement les classes : une build incompatible échoue ici, au démarrage, et
            // pas au milieu d'un collage.
            WorldEdit.getInstance().getBlockFactory();
            BlockVector3.at(0, 0, 0);
            return "";
        } catch (LinkageError | RuntimeException error) {
            return "WorldEdit présent mais incompatible : " + describe(error);
        }
    }

    @Override
    public boolean available() {
        return unavailableReason.isEmpty();
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public boolean has(String fileName) {
        Path file = resolve(fileName);
        return file != null && Files.isRegularFile(file);
    }

    @Override
    public Outcome write(Blueprint blueprint, String fileName) {
        if (!available()) {
            return Outcome.failure(unavailableReason);
        }
        Optional<String> invalid = blueprint.validate();
        if (invalid.isPresent()) {
            return Outcome.failure(invalid.get());
        }
        Path file = resolve(fileName);
        if (file == null) {
            return Outcome.failure("Nom de fichier schematic invalide : « " + fileName + " ».");
        }
        try {
            CuboidRegion region = new CuboidRegion(BlockVector3.ZERO,
                    BlockVector3.at(blueprint.sizeX() - 1, blueprint.sizeY() - 1,
                            blueprint.sizeZ() - 1));
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(BlockVector3.ZERO);

            ParserContext context = new ParserContext();
            context.setRestricted(false);
            context.setTryLegacy(false);
            for (BlueprintBlock block : blueprint.blocks()) {
                BaseBlock state = WorldEdit.getInstance().getBlockFactory()
                        .parseFromInput(block.state(), context);
                clipboard.setBlock(BlockVector3.at(block.x(), block.y(), block.z()), state);
            }

            Files.createDirectories(file.getParent());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
                 ClipboardWriter writer = FORMAT.getWriter(out)) {
                writer.write(clipboard);
            }
            return Outcome.success();
        } catch (Exception | LinkageError error) {
            return Outcome.failure("Écriture du schematic impossible : " + describe(error));
        }
    }

    @Override
    public Optional<Dimensions> inspect(String fileName) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Clipboard clipboard = read(fileName);
            if (clipboard == null) {
                return Optional.empty();
            }
            BlockVector3 size = clipboard.getDimensions();
            return Optional.of(new Dimensions(size.x(), size.y(), size.z()));
        } catch (Exception | LinkageError error) {
            logger.log(Level.WARNING,
                    "[building] lecture du schematic " + fileName + " impossible : "
                            + describe(error));
            return Optional.empty();
        }
    }

    @Override
    public Outcome capture(BuildingFootprint footprint, String fileName) {
        if (!available()) {
            return Outcome.failure(unavailableReason);
        }
        Path file = resolve(fileName);
        if (file == null) {
            return Outcome.failure("Nom de fichier de sauvegarde invalide.");
        }
        org.bukkit.World bukkitWorld = Bukkit.getWorld(footprint.world());
        if (bukkitWorld == null) {
            return Outcome.failure("Monde « " + footprint.world() + " » non chargé.");
        }
        try {
            com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(bukkitWorld);
            BlockVector3 min = BlockVector3.at(footprint.minX(), footprint.minY(),
                    footprint.minZ());
            BlockVector3 max = BlockVector3.at(footprint.maxX(), footprint.maxY(),
                    footprint.maxZ());
            CuboidRegion region = new CuboidRegion(world, min, max);
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(min);

            try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
                ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, min);
                copy.setCopyingEntities(false);
                copy.setCopyingBiomes(false);
                Operations.complete(copy);
            }

            Files.createDirectories(file.getParent());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
                 ClipboardWriter writer = FORMAT.getWriter(out)) {
                writer.write(clipboard);
            }
            return Outcome.success();
        } catch (Exception | LinkageError error) {
            return Outcome.failure("Sauvegarde de la zone impossible : " + describe(error));
        }
    }

    @Override
    public Outcome paste(PasteOrder order) {
        if (!available()) {
            return Outcome.failure(unavailableReason);
        }
        org.bukkit.World bukkitWorld = Bukkit.getWorld(order.world());
        if (bukkitWorld == null) {
            return Outcome.failure("Monde « " + order.world() + " » non chargé.");
        }
        try {
            Clipboard clipboard = read(order.schematic());
            if (clipboard == null) {
                return Outcome.failure("Schematic « " + order.schematic() + " » introuvable.");
            }
            // L'origine du presse-papiers est l'ancre LOCALE : c'est elle qui tombera sur l'ancre du
            // monde, et c'est le point fixe de la rotation. La relire depuis la région plutôt que de
            // supposer qu'elle commence à zéro évite de dépendre de la façon dont le fichier a été
            // écrit.
            BlockVector3 min = clipboard.getRegion().getMinimumPoint();
            BlockVector3 localAnchor = min.add(order.localAnchorX(), order.localAnchorY(),
                    order.localAnchorZ());
            clipboard.setOrigin(localAnchor);

            BlockVector3 target = BlockVector3.at(order.anchorX(), order.anchorY(),
                    order.anchorZ());

            // On MESURE le sens de rotation du moteur au lieu de le supposer. Voir le javadoc.
            Integer usable = agreeingAngle(clipboard, localAnchor, target, order);
            if (usable == null) {
                return Outcome.failure(
                        "Rotation refusée : l'emprise calculée par WorldEdit ne correspond pas à "
                                + "l'emprise annoncée (" + order.expected().label()
                                + "). Aucun bloc n'a été modifié.");
            }

            com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(bukkitWorld);
            try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
                com.sk89q.worldedit.session.ClipboardHolder holder =
                        new com.sk89q.worldedit.session.ClipboardHolder(clipboard);
                holder.setTransform(new AffineTransform().rotateY(usable));
                Operations.complete(holder.createPaste(session)
                        .to(target)
                        // L'air DOIT être collé : c'est lui qui creuse l'intérieur de la hutte.
                        // L'ignorer laisserait le terrain à l'intérieur des murs.
                        .ignoreAirBlocks(false)
                        .copyEntities(false)
                        .copyBiomes(false)
                        .build());
            }
            return Outcome.success();
        } catch (Exception | LinkageError error) {
            return Outcome.failure("Collage impossible : " + describe(error));
        }
    }

    @Override
    public Outcome restore(String fileName, BuildingFootprint footprint) {
        if (!available()) {
            return Outcome.failure(unavailableReason);
        }
        org.bukkit.World bukkitWorld = Bukkit.getWorld(footprint.world());
        if (bukkitWorld == null) {
            return Outcome.failure("Monde « " + footprint.world() + " » non chargé.");
        }
        try {
            Clipboard clipboard = read(fileName);
            if (clipboard == null) {
                return Outcome.failure("Sauvegarde « " + fileName + " » introuvable.");
            }
            BlockVector3 size = clipboard.getDimensions();
            if (size.x() != footprint.sizeX() || size.y() != footprint.sizeY()
                    || size.z() != footprint.sizeZ()) {
                return Outcome.failure("La sauvegarde ne correspond pas à l'emprise enregistrée ("
                        + size.x() + " × " + size.z() + " × " + size.y() + " contre "
                        + footprint.sizeX() + " × " + footprint.sizeZ() + " × "
                        + footprint.sizeY() + "). Aucun bloc n'a été modifié.");
            }
            BlockVector3 min = BlockVector3.at(footprint.minX(), footprint.minY(),
                    footprint.minZ());
            clipboard.setOrigin(clipboard.getRegion().getMinimumPoint());

            com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(bukkitWorld);
            try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
                com.sk89q.worldedit.session.ClipboardHolder holder =
                        new com.sk89q.worldedit.session.ClipboardHolder(clipboard);
                // Aucune transformation : une restauration se repose telle quelle, sinon ce n'est
                // pas une restauration.
                Operations.complete(holder.createPaste(session)
                        .to(min)
                        .ignoreAirBlocks(false)
                        .copyEntities(false)
                        .copyBiomes(false)
                        .build());
            }
            return Outcome.success();
        } catch (Exception | LinkageError error) {
            return Outcome.failure("Restauration impossible : " + describe(error));
        }
    }

    /**
     * L'angle à donner au moteur pour obtenir l'emprise attendue, ou {@code null} si aucun des deux
     * sens ne la produit.
     *
     * <p>On essaie l'angle tel quel, puis son opposé. Ce n'est pas un tâtonnement : c'est une
     * <strong>vérification</strong>. L'emprise attendue est calculée par le domaine, qui est la
     * référence ; on cherche lequel des deux sens du moteur s'y conforme, et on refuse si aucun ne
     * le fait.</p>
     */
    private Integer agreeingAngle(Clipboard clipboard, BlockVector3 origin, BlockVector3 target,
                                  PasteOrder order) {
        int wanted = order.rotationDegrees();
        for (int angle : new int[] {wanted, -wanted}) {
            BuildingFootprint produced = transformedFootprint(clipboard, origin, target, angle,
                    order.world());
            if (produced.equals(order.expected())) {
                if (angle != wanted && !rotationSignLogged) {
                    rotationSignLogged = true;
                    logger.info("[building] le moteur applique rotateY dans le sens opposé à "
                            + "l'azimut : " + wanted + "° demandés, " + angle
                            + "° transmis. L'emprise annoncée est respectée.");
                }
                return angle;
            }
        }
        logger.warning("[building] aucun sens de rotation ne produit l'emprise attendue "
                + order.expected().label() + " — collage refusé.");
        return null;
    }

    /** L'emprise qu'occuperait réellement le collage, selon le moteur lui-même. */
    private static BuildingFootprint transformedFootprint(Clipboard clipboard, BlockVector3 origin,
                                                          BlockVector3 target, int angle,
                                                          String world) {
        AffineTransform transform = new AffineTransform().rotateY(angle);
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[] {min.x(), max.x()}) {
            for (int y : new int[] {min.y(), max.y()}) {
                for (int z : new int[] {min.z(), max.z()}) {
                    Vector3 offset = Vector3.at(x - origin.x(), y - origin.y(), z - origin.z());
                    Vector3 rotated = transform.apply(offset);
                    int wx = target.x() + (int) Math.round(rotated.x());
                    int wy = target.y() + (int) Math.round(rotated.y());
                    int wz = target.z() + (int) Math.round(rotated.z());
                    minX = Math.min(minX, wx);
                    minY = Math.min(minY, wy);
                    minZ = Math.min(minZ, wz);
                    maxX = Math.max(maxX, wx);
                    maxY = Math.max(maxY, wy);
                    maxZ = Math.max(maxZ, wz);
                }
            }
        }
        return new BuildingFootprint(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Empreinte SHA-256 du contenu du fichier (issue #234).
     *
     * <p>Lecture d'octets, sans WorldEdit : elle fonctionne donc même si le moteur est absent — et
     * c'est utile, puisque comparer deux versions ne demande pas de savoir coller.</p>
     */
    @Override
    public Optional<String> fingerprint(String fileName) {
        Path file = resolve(fileName);
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return Optional.of(hex.toString());
        } catch (java.security.NoSuchAlgorithmException | IOException e) {
            logger.log(Level.WARNING, "[building] empreinte du schematic « {0} » illisible : {1}",
                    new Object[] {fileName, describe(e)});
            return Optional.empty();
        }
    }

    private Clipboard read(String fileName) throws IOException {
        Path file = resolve(fileName);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
             ClipboardReader reader = FORMAT.getReader(in)) {
            return reader.read();
        }
    }

    /**
     * Résout un nom de fichier dans le dossier des schematics, ou {@code null} s'il est refusé.
     *
     * <p>Aucun séparateur, aucun {@code ..} : le nom vient d'un formulaire web, donc on ne lui fait
     * pas confiance pour désigner un chemin.</p>
     */
    private Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String clean = fileName.trim().toLowerCase(Locale.ROOT);
        if (clean.contains("/") || clean.contains("\\") || clean.contains("..")
                || !clean.endsWith(".schem")) {
            return null;
        }
        Path resolved = directory.resolve(clean).normalize();
        return resolved.startsWith(directory.normalize()) ? resolved : null;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : " — " + message);
    }
}
