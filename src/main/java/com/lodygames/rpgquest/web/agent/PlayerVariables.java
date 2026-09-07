package com.lodygames.rpgquest.web.agent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Lecture asynchrone d'une variable joueur pour l'agent PlugAdmin. Adapté sur
 * {@code PlayerVariableRepository#get} (même signature), isolé en interface pour rester testable
 * sans base. <strong>Lecture pure</strong> — l'agent n'expose aucune écriture en #51.
 */
public interface PlayerVariables {

    CompletableFuture<Optional<String>> get(UUID uuid, String key);
}
