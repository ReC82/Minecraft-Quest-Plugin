package com.lodygames.rpgquest.web.agent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Résolution d'un joueur (en ligne ou hors ligne) pour l'agent PlugAdmin — isole
 * {@link AgentActionExecutor} de l'API Bukkit et garantit qu'aucune résolution ne bloque le thread
 * principal. L'implémentation réelle est {@code BukkitPlayerDirectory} ; les tests fournissent une
 * fausse table.
 */
public interface PlayerDirectory {

    /** Un joueur résolu : identité stable ({@link UUID}) + dernier nom connu. */
    record ResolvedPlayer(UUID uuid, String name) {
    }

    /**
     * Résout {@code nameOrUuid} : un UUID canonique est accepté tel quel, sinon le nom est cherché
     * (en ligne d'abord, puis hors ligne). Ne bloque jamais le thread principal.
     *
     * @return le joueur, ou {@link Optional#empty()} s'il n'a jamais été vu / entrée invalide.
     */
    CompletableFuture<Optional<ResolvedPlayer>> resolve(String nameOrUuid);
}
