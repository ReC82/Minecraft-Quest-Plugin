package com.lodygames.rpgquest.mob.community;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Issue #175 — textes joueur des boss communautaires (MiniMessage). Le biome est envoyé en clé de
 * traduction vanilla ({@code biome.minecraft.dark_forest}) : chaque client l'affiche dans sa propre
 * langue. Le nom de waypoint est inséré <em>non interprété</em> (jamais de balise injectée par un nom).
 *
 * <p>Ne promet jamais de récompense (#176/#177 non livrés) et ne mentionne aucune téléportation.</p>
 */
public final class CommunityBossMessages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private CommunityBossMessages() {
    }

    public static Component spawned(CommunityBossInstance i, String profileDisplayName) {
        return MM.deserialize("<gold>⚔ Boss communautaire :</gold> <name> <yellow>vient d'apparaître</yellow> "
                + "<where><test>", resolvers(i, profileDisplayName));
    }

    public static Component joinHeader(int count) {
        return MM.deserialize(count == 1
                ? "<gold>⚔ Un boss communautaire rôde en ce moment dans le Wild :</gold>"
                : "<gold>⚔ " + count + " boss communautaires rôdent en ce moment dans le Wild :</gold>");
    }

    public static Component joinLine(CommunityBossInstance i, String profileDisplayName) {
        return MM.deserialize("<gray> • </gray><name> <where><test>", resolvers(i, profileDisplayName));
    }

    public static Component defeated(String profileDisplayName, String killerName, boolean test) {
        return MM.deserialize(killerName == null
                        ? "<gold>⚔</gold> <name> <gray>a été vaincu.</gray><test>"
                        : "<gold>⚔</gold> <name> <gray>a été vaincu par</gray> <white><killer></white><gray>.</gray><test>",
                Placeholder.component("name", MM.deserialize(profileDisplayName)),
                Placeholder.unparsed("killer", killerName == null ? "" : killerName),
                Placeholder.parsed("test", test ? " <dark_gray>(test)</dark_gray>" : ""));
    }

    /** Toutes les lignes du rappel de connexion, en-tête compris ; vide si rien n'est actif. */
    public static List<Component> joinReminder(List<CommunityBossInstance> active,
                                               java.util.function.Function<CommunityBossInstance, String> names) {
        if (active.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(joinHeader(active.size()));
        for (CommunityBossInstance i : active) {
            lines.add(joinLine(i, names.apply(i)));
        }
        return lines;
    }

    /** « dans <biome>, à ~120 blocs au nord-est du waypoint X » — ou le biome seul, honnêtement. */
    static Component where(CommunityBossInstance i) {
        Component biome = biomeComponent(i.biome());
        if (i.waypointName() == null) {
            // Aucun waypoint à portée (#471) : une indication navigable plutôt qu'un nom inventé.
            return MM.deserialize("<gray>dans le biome</gray> <white><biome></white><gray>, loin de tout waypoint "
                            + "connu, vers X ≈ <x>, Z ≈ <z>.</gray>", Placeholder.component("biome", biome),
                    Placeholder.unparsed("x", String.valueOf(roundCoordinate(i.x()))),
                    Placeholder.unparsed("z", String.valueOf(roundCoordinate(i.z()))));
        }
        if (i.waypointDirection() == null || i.waypointDirection().isBlank()) {
            return MM.deserialize("<gray>dans le biome</gray> <white><biome></white><gray>, tout près du waypoint"
                            + "</gray> <aqua><wp></aqua><gray>.</gray>",
                    Placeholder.component("biome", biome), Placeholder.unparsed("wp", i.waypointName()));
        }
        return MM.deserialize("<gray>dans le biome</gray> <white><biome></white><gray>, à environ <dist> blocs "
                        + "<dir> du waypoint</gray> <aqua><wp></aqua><gray>.</gray>",
                Placeholder.component("biome", biome), Placeholder.unparsed("wp", i.waypointName()),
                Placeholder.unparsed("dist", String.valueOf(roundDistance(i.waypointDistance()))),
                Placeholder.unparsed("dir", towards(i.waypointDirection())));
    }

    /** « au nord », « au sud-ouest », mais « à l'est », « à l'ouest ». */
    static String towards(String direction) {
        return direction.startsWith("est") || direction.startsWith("ouest") ? "à l'" + direction : "au " + direction;
    }

    /** Arrondi à la dizaine : une zone à rejoindre, pas une coordonnée exacte. */
    static int roundCoordinate(int value) {
        return (int) (Math.round(value / 10.0) * 10);
    }

    /** Arrondi à la dizaine : une indication, pas une coordonnée exacte. */
    static int roundDistance(int distance) {
        return Math.max(10, (int) (Math.round(distance / 10.0) * 10));
    }

    static Component biomeComponent(String biomeKey) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return Component.text("inconnu");
        }
        int colon = biomeKey.indexOf(':');
        String namespace = colon >= 0 ? biomeKey.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? biomeKey.substring(colon + 1) : biomeKey;
        return Component.translatable("biome." + namespace + "." + path, path.replace('_', ' '));
    }

    private static TagResolver resolvers(CommunityBossInstance i, String profileDisplayName) {
        return TagResolver.resolver(
                Placeholder.component("name", MM.deserialize(profileDisplayName)),
                Placeholder.component("where", where(i)),
                Placeholder.parsed("test", i.test() ? " <dark_gray>(apparition de test)</dark_gray>" : ""));
    }
}
