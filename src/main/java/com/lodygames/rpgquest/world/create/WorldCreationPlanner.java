package com.lodygames.rpgquest.world.create;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Validation <strong>pure</strong> d'une demande de création de monde (issue #562, #331-W3) : aucun
 * appel serveur, aucune écriture. Produit l'identifiant du dossier, les options réellement passées
 * à Multiverse-Core (dimension, type de terrain, graine, structures, réglages JSON du monde plat),
 * le résumé lisible, les refus en français et l'empreinte qui lie l'application à cet aperçu.
 *
 * <p>Seules les combinaisons prises en charge par Paper 1.21.11 et Multiverse-Core 5.7.3 sont
 * acceptées : le Nether et l'End n'ont qu'une génération (pas de terrain plat, amplifié ni grands
 * biomes) ; les couches et le biome n'existent que pour un monde plat.</p>
 */
public final class WorldCreationPlanner {

    public static final int MAX_LAYERS = 16;
    /** Hauteur totale d'un monde normal (de -64 à 320). */
    public static final int MAX_TOTAL_HEIGHT = 384;
    public static final int MAX_SEED_LENGTH = 32;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{2,31}");
    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,20}");
    /** Dossiers du serveur qui ne doivent jamais devenir un monde, même absents. */
    private static final Set<String> RESERVED = Set.of("plugins", "logs", "libraries", "versions", "cache", "config",
            "crash-reports", "debug", "bundler", "backups", "world", "world_nether", "world_the_end");

    public enum Dimension {
        NORMAL("Monde normal"), NETHER("Nether"), THE_END("End");

        private final String label;

        Dimension(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Types de terrain de {@code org.bukkit.WorldType} pris en charge. */
    public enum Terrain {
        NORMAL("normal"), FLAT("plat"), AMPLIFIED("amplifié"), LARGE_BIOMES("grands biomes");

        private final String label;

        Terrain(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Préréglages de monde plat proposés par la maquette {@code monde-creer.html}. */
    public enum FlatPreset {
        CLASSIQUE("Classique", "minecraft:plains", false,
                List.of(layer("minecraft:bedrock", 1), layer("minecraft:dirt", 2), layer("minecraft:grass_block", 1))),
        NEIGE("Neige", "minecraft:snowy_plains", false,
                List.of(layer("minecraft:bedrock", 1), layer("minecraft:dirt", 2), layer("minecraft:snow_block", 1))),
        DESERT("Désert", "minecraft:desert", false,
                List.of(layer("minecraft:bedrock", 1), layer("minecraft:sandstone", 3), layer("minecraft:sand", 2))),
        /** Comme le préréglage vanilla « Le vide » : une seule plateforme au point d'arrivée. */
        VIDE("Vide (une plateforme)", "minecraft:the_void", true, List.of(layer("minecraft:air", 1)));

        private final String label;
        private final String biome;
        private final boolean features;
        private final List<WorldCreationRequest.Layer> layers;

        FlatPreset(String label, String biome, boolean features, List<WorldCreationRequest.Layer> layers) {
            this.label = label;
            this.biome = biome;
            this.features = features;
            this.layers = layers;
        }

        public String label() {
            return label;
        }

        public String biome() {
            return biome;
        }

        public List<WorldCreationRequest.Layer> layers() {
            return layers;
        }
    }

    /**
     * Ce que le serveur sait au moment de l'aperçu. Les noms sont comparés sans tenir compte de la
     * casse (un dossier {@code Terres} et un monde {@code terres} se confondent sous Windows).
     *
     * @param multiverseAvailable {@code false} = création impossible ({@code multiverseReason})
     * @param loaded              mondes chargés
     * @param multiverseKnown     mondes déclarés dans Multiverse-Core, chargés ou non
     * @param folders             dossiers présents dans le répertoire des mondes
     * @param essential           monde principal et mondes à rôle Hub / Wild / Claims
     * @param blockValid          identifiant de bloc connu du registre du serveur
     * @param biomeValid          identifiant de biome connu du registre du serveur
     */
    public record Context(boolean multiverseAvailable, String multiverseReason, String mainWorld, Set<String> loaded,
                          Set<String> multiverseKnown, Set<String> folders, Set<String> essential,
                          Predicate<String> blockValid, Predicate<String> biomeValid) {
    }

    /**
     * Résultat de la validation. {@code seed} {@code null} = graine choisie au hasard par le serveur ;
     * {@code generatorSettings} {@code null} hors monde plat.
     */
    public record Plan(String world, Dimension dimension, Terrain terrain, Long seed, String seedLabel,
                       boolean structures, FlatPreset preset, List<WorldCreationRequest.Layer> layers, String biome,
                       String generatorSettings, List<String> summary, List<String> warnings, List<String> errors,
                       String fingerprint) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private WorldCreationPlanner() {
    }

    public static Plan plan(WorldCreationRequest r, Context ctx) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!ctx.multiverseAvailable()) {
            errors.add("Création impossible : " + (ctx.multiverseReason() == null || ctx.multiverseReason().isBlank()
                    ? "Multiverse-Core n'est pas disponible" : ctx.multiverseReason())
                    + ". Les nouveaux mondes sont gérés par Multiverse-Core, qui les recharge à chaque démarrage.");
        }
        String world = identifier(r.name());
        checkName(world, r.name(), ctx, errors);

        Dimension dimension = parse(Dimension.class, r.environment(), Dimension.NORMAL);
        if (dimension == null) {
            errors.add("Dimension inconnue : choisir Monde normal, Nether ou End.");
            dimension = Dimension.NORMAL;
        }
        Terrain terrain = parse(Terrain.class, r.terrain(), Terrain.NORMAL);
        if (terrain == null) {
            errors.add("Type de terrain inconnu : choisir normal, plat, amplifié ou grands biomes.");
            terrain = Terrain.NORMAL;
        }
        if (dimension != Dimension.NORMAL && terrain != Terrain.NORMAL) {
            errors.add("Le terrain « " + terrain.label() + " » n'existe que pour un monde normal : le " + dimension.label()
                    + " n'a qu'une seule génération.");
        }

        Long seed = null;
        String seedLabel = "au hasard";
        String rawSeed = r.seed() == null ? "" : r.seed().trim();
        if (!rawSeed.isEmpty()) {
            if (rawSeed.length() > MAX_SEED_LENGTH) {
                errors.add("Graine trop longue (au plus " + MAX_SEED_LENGTH + " caractères).");
            } else if (NUMBER.matcher(rawSeed).matches()) {
                try {
                    seed = Long.parseLong(rawSeed);
                    seedLabel = String.valueOf(seed);
                } catch (NumberFormatException e) {
                    errors.add("Graine hors limites : un nombre entre -9223372036854775808 et 9223372036854775807.");
                }
            } else {
                // Même règle que Minecraft pour une graine saisie en texte.
                seed = (long) rawSeed.hashCode();
                seedLabel = "« " + rawSeed + " » → " + seed;
            }
        }

        FlatPreset preset = null;
        List<WorldCreationRequest.Layer> layers = List.of();
        String biome = null;
        String generatorSettings = null;
        boolean flatInput = notBlank(r.flatPreset()) || !r.layers().isEmpty() || notBlank(r.biome());
        if (terrain == Terrain.FLAT && dimension == Dimension.NORMAL) {
            preset = parse(FlatPreset.class, r.flatPreset(), FlatPreset.CLASSIQUE);
            if (preset == null) {
                errors.add("Préréglage de monde plat inconnu.");
                preset = FlatPreset.CLASSIQUE;
            }
            layers = r.layers().isEmpty() ? preset.layers() : r.layers();
            checkLayers(layers, ctx, errors);
            biome = notBlank(r.biome()) ? namespaced(r.biome()) : preset.biome();
            if (biome == null || !ctx.biomeValid().test(biome)) {
                errors.add("Biome inconnu du serveur : « " + safe(r.biome()) + " ».");
            }
            if (r.structures()) {
                warnings.add("Monde plat avec structures : seuls des villages et des forteresses seront générés.");
            }
            generatorSettings = flatJson(layers, biome == null ? "minecraft:plains" : biome,
                    preset.features && r.layers().isEmpty(), r.structures());
        } else if (flatInput) {
            errors.add("Préréglage, couches et biome ne s'appliquent qu'à un monde normal au terrain plat.");
        }
        if (terrain == Terrain.AMPLIFIED) {
            warnings.add("Terrain amplifié : la génération initiale fige le serveur plus longtemps.");
        }
        warnings.add("La création fige le serveur quelques secondes (génération synchrone) : une seule à la fois.");

        List<String> summary = new ArrayList<>();
        summary.add("Identifiant du dossier : " + (world.isEmpty() ? "—" : world));
        summary.add(dimension.label() + (dimension == Dimension.NORMAL ? ", terrain " + terrain.label() : ""));
        summary.add("Graine : " + seedLabel);
        summary.add("Structures : " + (r.structures() ? "oui" : "non"));
        if (generatorSettings != null) {
            summary.add("Couches (du bas vers le haut) : " + layersLabel(layers) + " · biome " + biome);
        }
        summary.add("Aucun rôle Hub / Wild / Claims, aucun portail, aucun waypoint automatique, aucune règle modifiée.");
        summary.add("Zone d'apparition non gardée en mémoire ; rechargé automatiquement au redémarrage par Multiverse-Core.");

        String fingerprint = fingerprint(world, dimension, terrain, seed, r.structures(), generatorSettings);
        return new Plan(world, dimension, terrain, seed, seedLabel, r.structures(), preset, List.copyOf(layers), biome,
                generatorSettings, List.copyOf(summary), List.copyOf(warnings), List.copyOf(errors), fingerprint);
    }

    /** Identifiant de dossier déduit du nom saisi : sans accents, minuscules, espaces → {@code _}. */
    public static String identifier(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("_{2,}", "_");
        return s.replaceAll("^[_-]+|[_-]+$", "");
    }

    private static void checkName(String world, String raw, Context ctx, List<String> errors) {
        if (world.isEmpty()) {
            errors.add("Nom du monde manquant.");
            return;
        }
        if (!ID.matcher(world).matches()) {
            errors.add("Identifiant « " + world + " » invalide : 3 à 32 caractères, minuscules, chiffres, _ et -, "
                    + "commençant par une lettre ou un chiffre.");
            return;
        }
        String main = ctx.mainWorld() == null ? "" : ctx.mainWorld().toLowerCase(Locale.ROOT);
        if (contains(ctx.essential(), world) || world.equals(main) || world.equals(main + "_nether")
                || world.equals(main + "_the_end")) {
            errors.add("« " + world + " » est un monde essentiel (monde principal, Hub, Wild ou Claims) : nom refusé.");
        } else if (contains(ctx.loaded(), world)) {
            errors.add("Un monde « " + world + " » est déjà chargé sur le serveur.");
        } else if (contains(ctx.multiverseKnown(), world)) {
            errors.add("Multiverse-Core connaît déjà un monde « " + world + " » (peut-être non chargé).");
        } else if (contains(ctx.folders(), world)) {
            errors.add("Un dossier « " + world + " » existe déjà sur le serveur : il ne sera ni écrasé ni importé.");
        } else if (RESERVED.contains(world)) {
            errors.add("« " + world + " » est un nom réservé du serveur.");
        }
    }

    private static void checkLayers(List<WorldCreationRequest.Layer> layers, Context ctx, List<String> errors) {
        if (layers.size() > MAX_LAYERS) {
            errors.add("Trop de couches (au plus " + MAX_LAYERS + ").");
            return;
        }
        int total = 0;
        for (WorldCreationRequest.Layer l : layers) {
            String block = namespaced(l.block());
            if (block == null || !ctx.blockValid().test(block)) {
                errors.add("Bloc inconnu du serveur : « " + safe(l.block()) + " ».");
            }
            if (l.height() < 1 || l.height() > MAX_TOTAL_HEIGHT) {
                errors.add("Épaisseur invalide pour « " + safe(l.block()) + " » : entre 1 et " + MAX_TOTAL_HEIGHT + ".");
            }
            total += Math.max(0, l.height());
        }
        if (total > MAX_TOTAL_HEIGHT) {
            errors.add("Épaisseur totale " + total + " : au plus " + MAX_TOTAL_HEIGHT + " blocs.");
        }
    }

    /**
     * Réglages JSON vanilla du monde plat ({@code generatorSettings}). Structures : {@code []} pour
     * aucune, sinon villages et forteresses (comme le monde plat classique).
     */
    static String flatJson(List<WorldCreationRequest.Layer> layers, String biome, boolean features,
                           boolean structures) {
        StringBuilder sb = new StringBuilder("{\"layers\":[");
        for (int i = 0; i < layers.size(); i++) {
            WorldCreationRequest.Layer l = layers.get(i);
            String block = namespaced(l.block());
            sb.append(i == 0 ? "" : ",").append("{\"block\":\"").append(block == null ? "minecraft:air" : block)
                    .append("\",\"height\":").append(l.height()).append('}');
        }
        sb.append("],\"biome\":\"").append(biome).append("\",\"features\":").append(features)
                .append(",\"lakes\":false,\"structure_overrides\":[")
                .append(structures ? "\"minecraft:villages\",\"minecraft:strongholds\"" : "").append("]}");
        return sb.toString();
    }

    /** {@code grass_block} → {@code minecraft:grass_block} ; {@code null} si l'identifiant est malformé. */
    static String namespaced(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (s.indexOf(':') < 0) {
            s = "minecraft:" + s;
        }
        return NAMESPACED.matcher(s).matches() && s.length() <= 96 ? s : null;
    }

    static String fingerprint(String world, Dimension d, Terrain t, Long seed, boolean structures, String settings) {
        String canonical = world + "|" + d + "|" + t + "|" + (seed == null ? "random" : seed) + "|" + structures + "|"
                + (settings == null ? "" : settings);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String layersLabel(List<WorldCreationRequest.Layer> layers) {
        List<String> parts = new ArrayList<>();
        for (WorldCreationRequest.Layer l : layers) {
            String block = namespaced(l.block());
            parts.add(l.height() + " × " + (block == null ? safe(l.block()) : block.replace("minecraft:", "")));
        }
        return String.join(", ", parts);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean contains(Set<String> names, String world) {
        return names != null && names.stream().anyMatch(n -> n != null && n.equalsIgnoreCase(world));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static WorldCreationRequest.Layer layer(String block, int height) {
        return new WorldCreationRequest.Layer(block, height);
    }
}
