package com.lodygames.rpgquest.webapi;

import com.lodygames.rpgquest.webapi.site.StatLabels;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * État du serveur Minecraft tel que le portail peut honnêtement l'annoncer (#421).
 *
 * <p>Seul le serveur lui-même peut attester un arrêt : {@link Kind#OFFLINE} n'est retenu que si
 * l'instantané reçu porte {@code server.online = false} (arrêt annoncé par le plugin). L'absence de
 * données ({@link Kind#UNKNOWN}) ou une donnée trop ancienne ({@link Kind#STALE}) ne prouvent rien :
 * le canal de télémétrie peut être en panne pendant que Minecraft tourne.</p>
 *
 * @param lastUpdate date du dernier relevé reçu, {@code null} si aucun
 */
public record ServerStatus(Kind kind, Instant lastUpdate) {

    public enum Kind { ONLINE, STALE, OFFLINE, UNKNOWN }

    public boolean online() {
        return kind == Kind.ONLINE;
    }

    /** Lit l'état écrit par {@link ServerState#resolve} dans {@code server.status}. */
    public static ServerStatus of(Map<String, Object> state) {
        if (!(state.get("server") instanceof Map<?, ?> server)) {
            return new ServerStatus(Kind.UNKNOWN, null);
        }
        Kind kind;
        try {
            kind = Kind.valueOf(String.valueOf(server.get("status")));
        } catch (IllegalArgumentException e) {
            kind = Boolean.TRUE.equals(server.get("online")) ? Kind.ONLINE : Kind.UNKNOWN;
        }
        return new ServerStatus(kind, parse(server.get("lastUpdate")));
    }

    /** Date du dernier relevé lisible par un humain (heure de Bruxelles), ou « date inconnue ». */
    public String lastUpdateText() {
        return lastUpdate == null ? "date inconnue" : StatLabels.dateTime(lastUpdate);
    }

    /**
     * Bandeau affiché en haut des pages. Neutre tant que rien n'atteste un arrêt : le site lui-même
     * fonctionne, seul l'état du jeu est inconnu ou en retard.
     */
    public String bannerHtml() {
        return switch (kind) {
            case ONLINE -> "";
            case UNKNOWN -> "<p class=\"unknown\">État du serveur de jeu inconnu — aucune donnée reçue pour le moment. "
                    + "Le site fonctionne ; les données du jeu (statut, classements, profils) s'afficheront dès "
                    + "réception.</p>";
            case STALE -> "<p class=\"unknown\">Informations non actualisées depuis le " + lastUpdateText()
                    + ". L'état actuel du serveur de jeu n'est pas connu.</p>";
            case OFFLINE -> "<p class=\"offline\">Serveur de jeu hors ligne — arrêt annoncé par le serveur le "
                    + lastUpdateText() + ".</p>";
        };
    }

    private static Instant parse(Object raw) {
        if (raw instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }
}
