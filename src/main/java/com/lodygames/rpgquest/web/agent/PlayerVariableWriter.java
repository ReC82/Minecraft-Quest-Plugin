package com.lodygames.rpgquest.web.agent;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Écriture asynchrone d'une variable joueur pour l'agent PlugAdmin — adapté sur
 * {@code PlayerVariableRepository#set} (même signature), isolé en interface pour rester testable
 * sans base. Utilisé uniquement par l'action <strong>whitelistée</strong>
 * {@code player.variable.set} (outil de debug bas niveau : confirmation exigée côté panel,
 * journalisée).
 */
@FunctionalInterface
public interface PlayerVariableWriter {

    CompletableFuture<Void> set(UUID uuid, String key, String value);
}
