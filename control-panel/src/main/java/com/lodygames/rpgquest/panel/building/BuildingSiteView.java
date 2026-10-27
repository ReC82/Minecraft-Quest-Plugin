package com.lodygames.rpgquest.panel.building;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Un emplacement de construction, projeté en type du panel (issue #213).
 *
 * <p>Même raison d'être que {@code NpcView} : le relevé arrive en {@code Map<String, Object>},
 * praticable pour afficher une liste et impossible à raisonner dessus. Les écrans ont besoin de
 * savoir si un monde est chargé, de formater une date, de comparer une orientation — autant de
 * questions qui méritent un nom.</p>
 *
 * @param facing      orientation en texte ({@code NORTH}…), parce que le panel ne partage aucune
 *                    énumération avec le plugin
 * @param worldLoaded le monde est-il chargé sur le serveur au moment du relevé ? Un emplacement
 *                    dans un monde déchargé reste valide : il faut le dire, pas le cacher
 * @param kind        type d'emplacement (issue #568) : {@code BUILDING} (défaut, y compris pour un
 *                    relevé d'un plugin plus ancien) ou {@code PORTAL}
 */
public record BuildingSiteView(String id, String name, String description, String aiBrief,
                               String world, int x, int y, int z, String facing, String status,
                               String createdBy, String createdAt, boolean worldLoaded, String kind) {

    /** Les types d'emplacement, identiques à {@code SiteKind} côté plugin (issue #568). */
    public static final List<String> KINDS = List.of("BUILDING", "PORTAL");

    /** Les quatre orientations proposées à l'écran, dans l'ordre de la boussole. */
    public static final List<String> FACINGS = List.of("NORTH", "EAST", "SOUTH", "WEST");

    /**
     * Borne du brief architectural (issue #94), identique côté plugin
     * ({@code BuildingSite.MAX_AI_BRIEF_LENGTH}) et côté validation d'action. Trois seuils
     * différents reviendraient à tronquer ou rejeter à des endroits imprévisibles.
     */
    public static final int MAX_BRIEF = BuildingProject.MAX_BRIEF;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withLocale(Locale.FRANCE)
                    .withZone(ZoneOffset.UTC);

    public BuildingSiteView {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        aiBrief = aiBrief == null ? "" : aiBrief;
        world = world == null ? "" : world.trim();
        facing = facing == null ? "" : facing.trim().toUpperCase(Locale.ROOT);
        status = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        createdBy = createdBy == null ? "" : createdBy.trim();
        createdAt = createdAt == null ? "" : createdAt.trim();
        kind = kind == null || !KINDS.contains(kind.trim().toUpperCase(Locale.ROOT))
                ? "BUILDING" : kind.trim().toUpperCase(Locale.ROOT);
    }

    /** Emplacement de bâtiment : relevé d'avant #568. */
    public BuildingSiteView(String id, String name, String description, String aiBrief,
                            String world, int x, int y, int z, String facing, String status,
                            String createdBy, String createdAt, boolean worldLoaded) {
        this(id, name, description, aiBrief, world, x, y, z, facing, status, createdBy, createdAt,
                worldLoaded, "BUILDING");
    }

    /** Emplacement de portail : il ne reçoit qu'une arche (issue #568). */
    public boolean portal() {
        return "PORTAL".equals(kind);
    }

    /** Type en français, pour l'écran. */
    public String kindLabel() {
        return portal() ? "portail" : "bâtiment";
    }

    /**
     * Ce type d'emplacement accepte-t-il cette construction ? Même règle que {@code SiteKind}
     * côté plugin — l'écran s'en sert pour filtrer ses listes, le serveur refuse de toute façon.
     */
    public boolean accepts(BuildingDefinitionView building) {
        return building != null && building.portal() == portal();
    }

    /** Position lisible, dans l'ordre où un administrateur la lit en jeu. */
    public String positionLabel() {
        return x + " / " + y + " / " + z;
    }

    /** Orientation en français, pour l'écran. Une valeur inconnue est rendue telle quelle. */
    public String facingLabel() {
        return switch (facing) {
            case "NORTH" -> "nord";
            case "EAST" -> "est";
            case "SOUTH" -> "sud";
            case "WEST" -> "ouest";
            default -> facing.isEmpty() ? "inconnue" : facing.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Vrai si le serveur dit cet emplacement <strong>occupé</strong> (issue #239).
     *
     * <p>C'est une donnée du serveur, <em>indépendante</em> de la présence du placement dans le
     * relevé. La fiche s'en sert pour ne jamais proposer une pose sur un emplacement déjà occupé,
     * même quand le détail du bâtiment manque — c'était exactement le défaut constaté en jeu.</p>
     */
    public boolean occupied() {
        return "OCCUPIED".equals(status);
    }

    /** État en français. {@code EMPTY} est le seul état que le serveur sait produire à ce jour. */
    public String statusLabel() {
        return switch (status) {
            case "EMPTY" -> "vide";
            case "RESERVED" -> "réservé";
            case "OCCUPIED" -> "occupé";
            default -> status.isEmpty() ? "inconnu" : status.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Date de création lisible, en UTC. Une date illisible est rendue brute plutôt que masquée : si
     * le serveur a écrit quelque chose d'inattendu, mieux vaut le voir.
     */
    public String createdAtLabel() {
        if (createdAt.isEmpty()) {
            return "inconnue";
        }
        try {
            return STAMP.format(Instant.parse(createdAt)) + " UTC";
        } catch (RuntimeException e) {
            return createdAt;
        }
    }

    /** L'auteur est-il connu ? Il peut légitimement ne pas l'être, et on ne l'invente jamais. */
    public boolean hasAuthor() {
        return !createdBy.isEmpty();
    }

    /** Un brief architectural est-il renseigné ? C'est la précondition d'une génération. */
    public boolean hasAiBrief() {
        return !aiBrief.isEmpty();
    }

    /**
     * Texte sur lequel la recherche de la page porte : identifiant, nom, monde, position, note.
     *
     * <p>Le <strong>brief</strong> en est volontairement absent. Il fait plusieurs milliers de
     * caractères : l'inclure ferait correspondre presque n'importe quelle recherche à presque
     * n'importe quel emplacement, et le filtre ne servirait plus à rien.</p>
     */
    public String searchText() {
        return (id + " " + name + " " + world + " " + x + " " + y + " " + z + " "
                + facingLabel() + " " + kindLabel() + " " + description).toLowerCase(Locale.ROOT);
    }
}
