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
 *
 * <p>{@code requiredWorld} (mission « Pierre de retour limitée à `claims` ») : restriction
 * <strong>optionnelle</strong> sur le monde depuis lequel l'objet peut être utilisé — {@link
 * Optional#empty()} signifie « aucune restriction ». Portée par la définition (donc par la
 * pierre elle-même, via son enregistrement) plutôt que par {@code travel.ItemTravelService}, qui
 * reste entièrement générique : une future pierre sans restriction n'a qu'à fournir {@code
 * Optional::empty}, jamais besoin de changer le moteur. Un fournisseur (jamais une valeur figée)
 * pour rester cohérent avec une config potentiellement rechargée à chaud (ex. {@code claims.world}).</p>
 */
public record ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, int cooldownSeconds,
                                    Supplier<Optional<Location>> destination, Supplier<Optional<String>> requiredWorld) {

    public ItemTravelDefinition {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId est obligatoire.");
        }
        if (channelSeconds <= 0) {
            throw new IllegalArgumentException("channelSeconds doit être strictement positif.");
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds ne peut pas être négatif (0 = aucun cooldown).");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination est obligatoire.");
        }
        if (requiredWorld == null) {
            throw new IllegalArgumentException("requiredWorld est obligatoire (Optional::empty si aucune restriction).");
        }
    }

    /** Confort : aucune restriction de monde, aucun cooldown. */
    public ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, Supplier<Optional<Location>> destination) {
        this(itemId, channelSeconds, 0, destination, Optional::empty);
    }

    /** Confort : restriction de monde, aucun cooldown. */
    public ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, Supplier<Optional<Location>> destination,
                                 Supplier<Optional<String>> requiredWorld) {
        this(itemId, channelSeconds, 0, destination, requiredWorld);
    }

    /** {@code true} si cette pierre applique un délai de rechargement après un voyage réussi. */
    public boolean hasCooldown() {
        return cooldownSeconds > 0;
    }
}
