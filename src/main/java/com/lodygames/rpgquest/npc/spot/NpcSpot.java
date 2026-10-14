package com.lodygames.rpgquest.npc.spot;

import java.time.Instant;
import java.util.Locale;

/**
 * Un emplacement de PNJ : une position nommée, réservée en jeu, où un PNJ peut être placé depuis
 * PlugAdmin (issue #381).
 *
 * <h2>L'identité est l'{@code id}, jamais la position, le nom ni le PNJ</h2>
 *
 * <p>{@code id} est attribué une fois ({@code npcspot_0001}) et ne change plus. Le {@code name} est
 * un libellé humain modifiable, l'orientation se corrige, et le PNJ affecté change au gré des
 * déplacements : aucun de ces gestes ne modifie l'identité. C'est ce qui permet de renommer un PNJ
 * ou une ville sans rendre un emplacement invalide.</p>
 *
 * <h2>Coordonnées exactes</h2>
 *
 * <p>Contrairement à un emplacement de construction, ancré sur la grille, un PNJ se tient à une
 * position flottante : l'administrateur réserve <em>l'endroit où il se tient</em>, orientation
 * comprise. Les coordonnées sont donc des {@code double}, comme les destinations de voyage.</p>
 *
 * <h2>Affectation</h2>
 *
 * <p>{@code npcId} désigne le PNJ <strong>logique</strong> placé ici (jamais un nom affiché, jamais
 * un identifiant Citizens, qui se relit depuis la liaison existante). {@code null} = emplacement
 * libre. Un PNJ n'est affecté qu'à un emplacement à la fois : c'est le service qui le garantit, en
 * une seule transaction.</p>
 *
 * @param id        identité stable ({@code npcspot_0001})
 * @param name      libellé humain, modifiable
 * @param world     nom du monde Minecraft
 * @param x         position exacte en X
 * @param y         position exacte en Y (pieds du PNJ)
 * @param z         position exacte en Z
 * @param yaw       orientation horizontale, en degrés
 * @param pitch     inclinaison, en degrés
 * @param radius    rayon de sécurité en blocs : un PNJ trouvé plus loin n'est plus « à sa place »
 * @param npcId     PNJ logique affecté, ou {@code null} si l'emplacement est libre
 * @param createdBy pseudo du créateur, vide si inconnu — jamais inventé
 * @param createdAt instant de création
 * @param updatedAt instant de la dernière modification
 */
public record NpcSpot(String id, String name, String world, double x, double y, double z,
                      float yaw, float pitch, int radius, String npcId, String createdBy,
                      Instant createdAt, Instant updatedAt) {

    /** Libellé d'un emplacement réservé sans nom. */
    public static final String DEFAULT_NAME = "Emplacement PNJ";

    /** Longueur maximale d'un nom, côté serveur comme côté panel. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Rayon de sécurité par défaut, en blocs. */
    public static final int DEFAULT_RADIUS = 2;

    /** Rayon de sécurité maximal accepté. */
    public static final int MAX_RADIUS = 16;

    public NpcSpot {
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
        world = world == null ? "" : world.trim();
        yaw = normalizeYaw(yaw);
        pitch = Math.max(-90f, Math.min(90f, pitch));
        radius = Math.max(1, Math.min(MAX_RADIUS, radius));
        npcId = npcId == null || npcId.isBlank() ? null : npcId.trim();
        createdBy = createdBy == null ? "" : createdBy.trim();
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /** {@code true} si aucun PNJ n'est affecté à cet emplacement. */
    public boolean free() {
        return npcId == null;
    }

    /** Même bloc (comparaison sur les coordonnées arrondies à l'entier inférieur). */
    public boolean sameBlockAs(String otherWorld, double ox, double oy, double oz) {
        return world.equals(otherWorld)
                && (int) Math.floor(x) == (int) Math.floor(ox)
                && (int) Math.floor(y) == (int) Math.floor(oy)
                && (int) Math.floor(z) == (int) Math.floor(oz);
    }

    /** Distance horizontale et verticale maximale à cette position (norme infinie), même monde. */
    public double distanceTo(double ox, double oy, double oz) {
        return Math.max(Math.abs(x - ox), Math.max(Math.abs(y - oy), Math.abs(z - oz)));
    }

    public NpcSpot withName(String newName, Instant now) {
        return new NpcSpot(id, newName, world, x, y, z, yaw, pitch, radius, npcId, createdBy, createdAt, now);
    }

    public NpcSpot withOrientation(float newYaw, float newPitch, Instant now) {
        return new NpcSpot(id, name, world, x, y, z, newYaw, newPitch, radius, npcId, createdBy, createdAt, now);
    }

    public NpcSpot withNpc(String newNpcId, Instant now) {
        return new NpcSpot(id, name, world, x, y, z, yaw, pitch, radius, newNpcId, createdBy, createdAt, now);
    }

    /** Ramène un angle dans {@code [-180, 180)}, comme Minecraft. */
    static float normalizeYaw(float raw) {
        if (!Float.isFinite(raw)) {
            return 0f;
        }
        float value = raw % 360f;
        if (value >= 180f) {
            value -= 360f;
        } else if (value < -180f) {
            value += 360f;
        }
        return value;
    }
}
