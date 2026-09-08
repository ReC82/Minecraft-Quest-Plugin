package com.lodygames.rpgquest.npc;

import com.lodygames.rpgquest.npc.NpcIdentityService.BindResult;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestration <strong>transactionnelle applicative</strong> du spawn d'un PNJ Citizens (issue
 * #81, phase 2), sans aucun type Bukkit ni I/O directe : {@code create → bind → success}, et si la
 * liaison échoue, {@code delete Citizens just created → failure}. Le rollback ne supprime
 * <strong>que</strong> l'entité créée par cet appel (elle est passée telle quelle à
 * {@link Spawner#destroyCreated}), jamais un PNJ préexistant.
 *
 * <p>Les deux collaborateurs hoppent eux-mêmes sur le thread principal dans l'implémentation réelle
 * ; ici tout est exprimé en {@link CompletableFuture}, ce qui rend l'orchestration testable avec
 * de simples fakes.</p>
 */
public final class CitizensSpawnCoordinator {

    /** Résultat d'un spawn orchestré. Voir {@code AgentActions.CitizensCreateResult} pour le vocabulaire des codes. */
    public record Result(boolean ok, String code, String message, Integer citizensNumericId,
                         String npcId, List<String> effects, boolean rolledBack) {
    }

    /** Accès Citizens : création+spawn de l'entité, et suppression <em>de l'entité créée</em> (rollback). */
    public interface Spawner {
        CompletableFuture<Optional<CitizensNpc>> createAndSpawn();

        CompletableFuture<Boolean> destroyCreated(CitizensNpc created);
    }

    /** Persistance de la liaison (réutilise {@code NpcIdentityService.bindCitizens} en réel). */
    public interface Binder {
        CompletableFuture<BindResult> bind(String npcId, CitizensNpc ref);
    }

    private CitizensSpawnCoordinator() {
    }

    public static CompletableFuture<Result> run(String npcId, String positionLabel,
                                                Spawner spawner, Binder binder) {
        return spawner.createAndSpawn().thenCompose(created -> {
            if (created.isEmpty()) {
                return completed(new Result(false, "CREATE_FAILED",
                        "Citizens n'a pas pu matérialiser le PNJ (spawn refusé).", null, npcId, List.of(), false));
            }
            CitizensNpc ref = created.get();
            return binder.bind(npcId, ref).thenCompose(bind -> {
                if (bind.ok()) {
                    return completed(new Result(true, "CREATED",
                            "« " + npcId + " » créé et lié à Citizens #" + ref.numericId()
                                    + " (« " + ref.name() + " ») — " + positionLabel + ".",
                            ref.numericId(), npcId,
                            List.of("Citizens #" + ref.numericId() + " spawné dans " + positionLabel,
                                    "binding " + npcId + " <-> Citizens #" + ref.numericId()),
                            false));
                }
                // Liaison impossible après création : rollback du seul PNJ qu'on vient de créer.
                return spawner.destroyCreated(ref).thenApply(destroyed -> new Result(false,
                        "BIND_FAILED_ROLLED_BACK",
                        "Liaison impossible (" + bind.message() + ") — PNJ Citizens #" + ref.numericId()
                                + (destroyed
                                ? " supprimé (rollback effectué, aucun PNJ orphelin)."
                                : " NON supprimé : vérifier manuellement le registre Citizens."),
                        ref.numericId(), npcId, List.of(), destroyed));
            });
        });
    }

    private static CompletableFuture<Result> completed(Result r) {
        return CompletableFuture.completedFuture(r);
    }
}
