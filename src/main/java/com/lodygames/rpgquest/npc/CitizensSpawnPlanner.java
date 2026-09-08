package com.lodygames.rpgquest.npc;

import java.util.Set;

/**
 * Décision <strong>pure</strong> (aucun type Bukkit, aucune I/O) du spawn d'un PNJ Citizens à
 * partir d'une {@link com.lodygames.rpgquest.npc.model.NpcDefinition} — issue #81, phase 2.
 *
 * <p>Toutes les préconditions <em>logiques</em> sont vérifiées ici, avant que la moindre entité ne
 * soit créée : existence + activation de la définition, absence de binding préexistant pour cet
 * {@code npc_id}, disponibilité de Citizens, appartenance du monde à la liste blanche RPGQuest, et
 * bornes de sécurité de la position. Le contrôle fin de {@code y} par rapport aux limites réelles
 * du monde reste un garde-fou <em>secondaire</em> côté thread principal (le monde doit d'abord
 * être résolu).</p>
 *
 * <p>Une position invalide n'est jamais « corrigée » : elle est refusée telle quelle
 * ({@code INVALID_POSITION}).</p>
 */
public final class CitizensSpawnPlanner {

    /** Demi-largeur du bord de monde Bukkit par défaut ({@code 29 999 984}). */
    public static final double HORIZONTAL_LIMIT = 29_999_984.0D;
    /** Borne basse absolue de {@code y} (les limites réelles du monde sont revérifiées ensuite). */
    public static final double Y_MIN = -2048.0D;
    /** Borne haute absolue de {@code y}. */
    public static final double Y_MAX = 2048.0D;

    public enum Action { PROCEED, REJECT }

    /**
     * @param code {@code PROCEED} / {@code CITIZENS_UNAVAILABLE} / {@code UNKNOWN_NPC} /
     *             {@code NPC_DISABLED} / {@code NPC_ALREADY_LINKED} / {@code UNKNOWN_WORLD} /
     *             {@code INVALID_POSITION}
     */
    public record Plan(Action action, String code, String message) {

        static Plan proceed() {
            return new Plan(Action.PROCEED, "PROCEED", "Préconditions vérifiées — prêt à créer.");
        }

        static Plan reject(String code, String message) {
            return new Plan(Action.REJECT, code, message);
        }
    }

    private CitizensSpawnPlanner() {
    }

    /**
     * @param npcId              identifiant logique visé (déjà filtré par un motif d'id en amont)
     * @param definitionPresent  une {@code NpcDefinition} porte cet id
     * @param definitionEnabled  cette définition est {@code enabled: true}
     * @param npcIdAlreadyLinked cet id est déjà présent dans {@code npc_citizens_bindings}
     * @param citizensAvailable  Citizens est installé et actif sur le serveur cible
     * @param world              nom du monde demandé
     * @param allowedWorlds      noms des mondes RPGQuest autorisés au spawn (liste blanche logique)
     * @param x                  coordonnées demandées (bloc ou décimal)
     * @param yaw                orientation horizontale (0 = sud), {@code pitch} vertical (-90 haut, +90 bas)
     */
    public static Plan plan(String npcId, boolean definitionPresent, boolean definitionEnabled,
                            boolean npcIdAlreadyLinked, boolean citizensAvailable,
                            String world, Set<String> allowedWorlds,
                            double x, double y, double z, float yaw, float pitch) {
        if (!citizensAvailable) {
            return Plan.reject("CITIZENS_UNAVAILABLE", "Citizens n'est pas actif sur le serveur cible.");
        }
        if (!definitionPresent) {
            return Plan.reject("UNKNOWN_NPC",
                    "Aucune définition logique « " + safe(npcId) + " » — la créer d'abord.");
        }
        if (!definitionEnabled) {
            return Plan.reject("NPC_DISABLED",
                    "La définition « " + safe(npcId) + " » est désactivée — la réactiver avant de spawn.");
        }
        if (npcIdAlreadyLinked) {
            return Plan.reject("NPC_ALREADY_LINKED",
                    "« " + safe(npcId) + " » est déjà lié à un PNJ Citizens — aucun rebind dans cette phase.");
        }
        String requestedWorld = world == null ? "" : world.trim();
        if (requestedWorld.isEmpty() || !containsIgnoreCase(allowedWorlds, requestedWorld)) {
            return Plan.reject("UNKNOWN_WORLD",
                    "Monde « " + safe(world) + " » hors de la liste blanche RPGQuest "
                            + describe(allowedWorlds) + ".");
        }
        String positionError = positionError(x, y, z, yaw, pitch);
        if (positionError != null) {
            return Plan.reject("INVALID_POSITION", positionError);
        }
        return Plan.proceed();
    }

    /**
     * Contrôle de sécurité de la position, réutilisable côté panel. {@code null} = position saine.
     */
    public static String positionError(double x, double y, double z, float yaw, float pitch) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            return "Coordonnées non finies (NaN / Infinity refusés).";
        }
        if (Float.isNaN(yaw) || Float.isInfinite(yaw) || Float.isNaN(pitch) || Float.isInfinite(pitch)) {
            return "Orientation non finie (NaN / Infinity refusés).";
        }
        if (Math.abs(x) > HORIZONTAL_LIMIT || Math.abs(z) > HORIZONTAL_LIMIT) {
            return "X/Z hors du bord de monde (±" + (long) HORIZONTAL_LIMIT + ").";
        }
        if (y < Y_MIN || y > Y_MAX) {
            return "Y=" + trim(y) + " hors bornes de sécurité (" + (long) Y_MIN + " à " + (long) Y_MAX + ").";
        }
        if (pitch < -90.0F || pitch > 90.0F) {
            return "Pitch=" + pitch + " hors bornes (-90 à 90).";
        }
        return null;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static boolean containsIgnoreCase(Set<String> values, String needle) {
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Set<String> allowedWorlds) {
        if (allowedWorlds == null || allowedWorlds.isEmpty()) {
            return "(aucun monde autorisé)";
        }
        return "(" + String.join(", ", allowedWorlds.stream().map(CitizensSpawnPlanner::safe).sorted().toList()) + ")";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.strip();
        return t.length() > 64 ? t.substring(0, 64) + "…" : t;
    }
}
