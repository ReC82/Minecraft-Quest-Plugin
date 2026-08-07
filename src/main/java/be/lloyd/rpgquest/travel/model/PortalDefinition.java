package be.lloyd.rpgquest.travel.model;

import be.lloyd.rpgquest.quest.model.QuestState;
import org.bukkit.NamespacedKey;

/**
 * Un portail : une zone d'activation cuboïde (même forme que {@code
 * ZoneDefinition}, délibérément dupliquée plutôt que réutilisée — un
 * portail n'a ni id à motif imposé par une zone protégée ni {@code
 * ZoneFlags}, ce sont deux concepts distincts) reliée à une destination
 * par id (résolue paresseusement à l'activation, jamais validée au
 * chargement — même choix que les offres de marchand référençant un
 * objet personnalisé, voir {@code economy.merchant}), avec des conditions
 * d'accès cumulatives optionnelles.
 */
public record PortalDefinition(
        String id,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        String destinationId,
        int channelSeconds,
        int cooldownSeconds,
        String requiredPermission,
        NamespacedKey requiredQuestId,
        QuestState requiredQuestState,
        Integer requiredLevel,
        Long cost
) {

    public PortalDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "id invalide : \"" + id + "\" (minuscules, chiffres, « _ » et « - » uniquement).");
        }
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world est obligatoire.");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("les bornes min doivent être inférieures ou égales aux bornes max.");
        }
        if (destinationId != null && destinationId.isBlank()) {
            throw new IllegalArgumentException("destinationId ne peut pas être vide si présent.");
        }
        if (channelSeconds < 0) {
            throw new IllegalArgumentException("channelSeconds ne peut pas être négatif.");
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds ne peut pas être négatif.");
        }
        if (requiredPermission != null && requiredPermission.isBlank()) {
            throw new IllegalArgumentException("requiredPermission ne peut pas être vide si présent.");
        }
        if (requiredLevel != null && requiredLevel < 0) {
            throw new IllegalArgumentException("requiredLevel ne peut pas être négatif.");
        }
        if (cost != null && cost <= 0) {
            throw new IllegalArgumentException("cost doit être strictement positif si présent.");
        }
        // Une quête référencée sans état explicite sous-entend « doit être terminée », même convention que MerchantOffer.
        if (requiredQuestId != null && requiredQuestState == null) {
            requiredQuestState = QuestState.COMPLETED;
        }
    }

    public boolean hasDestination() {
        return destinationId != null;
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(PortalDefinition other) {
        if (!world.equals(other.world)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
