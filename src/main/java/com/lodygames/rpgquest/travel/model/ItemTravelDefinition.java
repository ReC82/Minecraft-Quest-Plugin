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
 *
 * <p>{@code freeRescueWorld} (issue #154 — secours Hub via la Rune de rappel existante) : monde
 * <strong>optionnel</strong> dans lequel cet objet téléporte immédiatement et gratuitement (sans
 * canalisation ni cooldown, jamais de consommation — l'objet n'est de toute façon jamais consommé
 * par ce moteur) vers {@code destination}, <strong>avant</strong> toute évaluation de {@code
 * requiredWorld}. Totalement indépendant de {@code requiredWorld} : la Rune de rappel garde sa
 * restriction au monde d'exploration pour son usage normal, et gagne en plus ce second monde où
 * elle fonctionne différemment — jamais un contournement de {@code requiredWorld} lui-même.
 * {@link Optional#empty()} (défaut) signifie « aucun secours gratuit », comme pour la Pierre de
 * retour qui n'a pas besoin de cette mécanique.</p>
 */
public record ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, int cooldownSeconds,
                                    Supplier<Optional<Location>> destination, Supplier<Optional<String>> requiredWorld,
                                    Supplier<Optional<String>> freeRescueWorld) {

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
        if (freeRescueWorld == null) {
            throw new IllegalArgumentException("freeRescueWorld est obligatoire (Optional::empty si aucun secours gratuit).");
        }
    }

    /** Confort : aucune restriction de monde, aucun cooldown, aucun secours gratuit. */
    public ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, Supplier<Optional<Location>> destination) {
        this(itemId, channelSeconds, 0, destination, Optional::empty, Optional::empty);
    }

    /** Confort : restriction de monde, aucun cooldown, aucun secours gratuit. */
    public ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, Supplier<Optional<Location>> destination,
                                 Supplier<Optional<String>> requiredWorld) {
        this(itemId, channelSeconds, 0, destination, requiredWorld, Optional::empty);
    }

    /** Confort : restriction de monde + cooldown, aucun secours gratuit. */
    public ItemTravelDefinition(NamespacedKey itemId, int channelSeconds, int cooldownSeconds,
                                 Supplier<Optional<Location>> destination, Supplier<Optional<String>> requiredWorld) {
        this(itemId, channelSeconds, cooldownSeconds, destination, requiredWorld, Optional::empty);
    }

    /** {@code true} si cette pierre applique un délai de rechargement après un voyage réussi. */
    public boolean hasCooldown() {
        return cooldownSeconds > 0;
    }
}
