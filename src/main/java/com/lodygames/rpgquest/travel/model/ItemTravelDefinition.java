package com.lodygames.rpgquest.travel.model;

import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;

/**
 * Une destination logique de voyage déclenchée par un objet personnalisé RPGQuest (mission
 * « mécanique RPG générique de voyage par objet », premier objet : Pierre de retour). Volontairement
 * séparée de la canalisation/du rendu ({@code travel.ItemTravelService}) et de l'objet lui-même
 * ({@code item.YamlCustomItemRegistry}) : une future pierre/destination n'ajoute qu'une nouvelle
 * définition enregistrée, jamais un nouveau moteur.
 *
 * <p>{@code destination} reste un simple fournisseur (jamais une {@link Location} figée) : résolue
 * à chaque téléportation réussie, pour toujours pointer vers l'état courant (ex. {@code
 * spawn.SpawnService#resolve}) plutôt qu'une position capturée une fois pour toutes.</p>
 */
public record ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, Supplier<Optional<Location>> destination) {

    public ItemTravelDefinition {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId est obligatoire.");
        }
        if (channelSeconds <= 0) {
            throw new IllegalArgumentException("channelSeconds doit être strictement positif.");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination est obligatoire.");
        }
    }
}
