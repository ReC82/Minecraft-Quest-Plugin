package com.lodygames.rpgquest.web.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Exécute une {@link AgentAction} reçue de PlugAdmin en appelant les <strong>services métier</strong>
 * du plugin — jamais une commande texte {@code /rpgadmin …}, jamais un {@code dispatchCommand}
 * (issue #51).
 *
 * <p>Discipline :</p>
 * <ul>
 *   <li>type absent de {@link AgentActionType} → {@link AgentActionOutcome#REJECTED} sans effet ;</li>
 *   <li>paramètres invalides → {@code REJECTED} ;</li>
 *   <li>cible/données introuvables → {@code FAILED} ;</li>
 *   <li>aucune exception ne remonte : tout échec devient un {@code FAILED} lisible.</li>
 * </ul>
 *
 * <p>Toutes les opérations sont asynchrones ; aucune ne touche le thread principal Paper.</p>
 */
public final class AgentActionExecutor {

    /** Clés de variable acceptées (ex. {@code CLAIM_TIER_1}) — bornées, jamais du texte libre. */
    private static final Pattern VARIABLE_KEY = Pattern.compile("[A-Za-z0-9_.:\\-]{1,128}");

    private final PlayerDirectory players;
    private final PlayerVariables variables;

    public AgentActionExecutor(PlayerDirectory players, PlayerVariables variables) {
        this.players = players;
        this.variables = variables;
    }

    public CompletableFuture<AgentActionOutcome> execute(AgentAction action) {
        if (action == null || action.id() == null || action.id().isBlank()) {
            return done(AgentActionOutcome.rejected("<sans-id>", "Action sans identifiant."));
        }
        Optional<AgentActionType> type = AgentActionType.fromWire(action.type());
        if (type.isEmpty()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Type d'action non whitelisté : « " + safe(action.type()) + " »."));
        }
        try {
            return switch (type.get()) {
                case PLAYER_VARIABLE_GET -> playerVariableGet(action);
            };
        } catch (RuntimeException e) {
            return done(AgentActionOutcome.failed(action.id(), "Échec interne : " + e.getClass().getSimpleName()));
        }
    }

    // ---- player.variable.get -------------------------------------------------------------

    private CompletableFuture<AgentActionOutcome> playerVariableGet(AgentAction action) {
        String key = firstNonBlank(action.param("key"), action.param("variable"));
        String playerRef = firstNonBlank(action.param("player_uuid"), action.param("player"), action.param("name"));
        if (key == null || !VARIABLE_KEY.matcher(key).matches()) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « key » manquant ou invalide (attendu : " + VARIABLE_KEY.pattern() + ")."));
        }
        if (playerRef == null) {
            return done(AgentActionOutcome.rejected(action.id(),
                    "Paramètre « player » ou « player_uuid » manquant."));
        }
        return players.resolve(playerRef).thenCompose(resolved -> {
            if (resolved.isEmpty()) {
                return done(AgentActionOutcome.failed(action.id(),
                        "Joueur inconnu (jamais connecté) : « " + safe(playerRef) + " »."));
            }
            UUID uuid = resolved.get().uuid();
            String name = resolved.get().name();
            return variables.get(uuid, key).thenApply(opt -> {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("player_uuid", uuid.toString());
                details.put("player_name", name);
                details.put("key", key);
                details.put("present", opt.isPresent());
                String value = opt.orElse(null);
                String message = opt.isPresent()
                        ? name + " : " + key + " = " + value
                        : name + " : " + key + " absente (équivaut à non définie).";
                return AgentActionOutcome.success(action.id(), value, message, details);
            });
        }).exceptionally(error -> AgentActionOutcome.failed(action.id(),
                "Échec de lecture : " + rootCauseName(error)));
    }

    // ---- utilitaires -------------------------------------------------------------------

    private static CompletableFuture<AgentActionOutcome> done(AgentActionOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) + "…" : trimmed;
    }

    private static String rootCauseName(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }
}
