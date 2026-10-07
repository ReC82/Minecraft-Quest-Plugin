package com.lodygames.rpgquest.config;

import java.util.List;
import org.bukkit.Material;

/**
 * Un palier du kit de départ (issue #218) : le contenu exact que le joueur récupère auprès du Guide
 * une fois ce palier débloqué. Purement déclaratif — aucun matériau n'est codé en dur ailleurs que
 * dans les valeurs par défaut de {@code ConfigValidator}.
 *
 * <p>Le palier 1 est le seul acquis <strong>automatiquement</strong> : il n'a pas de quête de
 * déblocage. Chaque palier suivant est débloqué par une quête, qui appelle
 * {@code /rpgadmin kit grant-tier <joueur> <niveau>} en récompense — jamais en écrivant une variable
 * à la main, pour que le moteur puisse refuser un saut de palier (voir
 * {@code player.StarterToolKitService#grantTier}).</p>
 *
 * @param level      niveau du palier, strictement positif, unique et contigu depuis 1
 * @param name       nom lisible (affiché au joueur et dans le Control Panel)
 * @param items      contenu remis, un exemplaire par entrée ; l'ordre est conservé
 * @param unlockQuest id de la quête qui débloque ce palier, {@code null} pour le palier 1 (acquis
 *                    d'office). Purement informatif côté moteur : c'est la récompense de la quête
 *                    qui débloque réellement, ce champ sert à l'afficher et à le vérifier.
 */
public record StarterKitTier(int level, String name, List<Material> items, String unlockQuest) {

    public StarterKitTier {
        if (level <= 0) {
            throw new IllegalArgumentException("level doit être strictement positif : " + level);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name est obligatoire (palier " + level + ").");
        }
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("un palier sans objet ne remettrait rien (palier " + level + ").");
        }
        if (unlockQuest != null && unlockQuest.isBlank()) {
            unlockQuest = null;
        }
    }

    /** Nombre d'emplacements libres nécessaires — calculé sur le contenu RÉEL de ce palier. */
    public int requiredSlots() {
        return items.size();
    }
}
