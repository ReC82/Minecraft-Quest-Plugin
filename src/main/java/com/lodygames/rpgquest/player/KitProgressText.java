package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.config.StarterKitTier;
import com.lodygames.rpgquest.config.StarterToolKitConfig;
import com.lodygames.rpgquest.quest.progress.DeliveryLine;
import com.lodygames.rpgquest.quest.progress.DeliveryStatusText;
import java.util.List;
import java.util.Optional;

/**
 * Rend, en texte MiniMessage, la progression du kit de départ d'un joueur (issue #235) : palier
 * actuel, prochain palier, et ce qu'il reste à rapporter pour l'obtenir.
 *
 * <h2>Pourquoi trois valeurs et non un bloc tout fait</h2>
 *
 * <p>Le joueur qui parle au Guide doit lire « <em>Kit actuel : Palier 1 — Nouveau venu</em> ». La
 * tentation serait de composer cette phrase ici. Mais alors le libellé « Kit actuel : » vivrait dans
 * le code, et le ticket demande explicitement que les textes restent administrables.</p>
 *
 * <p>Cette classe ne rend donc que les <strong>valeurs</strong> — exactement comme
 * {@link DeliveryStatusText} pour {@code %delivery_status%} (issue #123) : la phrase qui les entoure
 * vit dans {@code dialogues/guide.yml}, donc éditable depuis le Control Panel sans toucher au code.
 * C'est la convention déjà établie du projet, pas une invention de ce lot.</p>
 *
 * <p>Classe <strong>pure</strong> : aucun accès au serveur, à la base ni au joueur. Les faits lui
 * sont fournis par {@link KitProgressService}, ce qui rend chaque cas (palier maximum, palier
 * retiré de la configuration, quête absente) réellement exécutable en test.</p>
 */
public final class KitProgressText {

    /** Affiché quand le palier n'est pas déterminable — jamais un chiffre inventé. */
    static final String UNKNOWN = "<gray>inconnu</gray>";

    private KitProgressText() {
    }

    /**
     * Le palier actuel : {@code "Palier 1 — Nouveau venu"}.
     *
     * <p>Le nom vient de {@code config.yml}, jamais d'une table en dur. Si le palier débloqué n'est
     * plus défini en configuration, on affiche le palier <em>réellement applicable</em> — le même
     * que celui qui serait remis — plutôt qu'un numéro auquel plus aucun contenu ne correspond.</p>
     */
    public static String current(StarterToolKitConfig config, int unlockedLevel) {
        Optional<StarterKitTier> tier = config.effectiveTier(unlockedLevel);
        return tier.map(t -> "<aqua>Palier " + t.level() + " — " + t.name() + "</aqua>").orElse(UNKNOWN);
    }

    /**
     * Le prochain palier, ou une phrase complète quand il n'y en a pas.
     *
     * <p>Rendre {@code "—"} obligerait le texte du dialogue à dire « Prochaine amélioration : — »,
     * ce qui ne veut rien dire pour un joueur. On rend donc une clause lisible dans les deux cas,
     * pour qu'un seul nœud de dialogue suffise.</p>
     */
    public static String next(StarterToolKitConfig config, int unlockedLevel) {
        Optional<StarterKitTier> next = nextTier(config, unlockedLevel);
        return next.map(t -> "<gold>Palier " + t.level() + " — " + t.name() + "</gold>")
                .orElse("<gray>aucune — tu as déjà le meilleur équipement de secours disponible.</gray>");
    }

    /**
     * Ce qu'il reste à rapporter pour le prochain palier, une ligne par matériau, avec ce qui est
     * <strong>déjà remis</strong> ({@code ✔ Bâton 1/1}).
     *
     * <p>Délégué à {@link DeliveryStatusText} : le joueur voit donc exactement la même présentation
     * ici et dans la branche de remise du dialogue, et les noms d'objets restent traduits par le
     * client via {@code <lang:…>}. Aucune liste de matériaux n'est écrite dans ce lot — elle est
     * dérivée de la définition de la quête du palier.</p>
     *
     * @param requirements lignes dérivées de la quête de déblocage ; vide s'il n'y a pas de palier
     *                     suivant, ou si sa quête ne demande aucune remise
     */
    public static String requirements(List<DeliveryLine> requirements) {
        if (requirements.isEmpty()) {
            return "<gray>rien de particulier</gray>";
        }
        return DeliveryStatusText.render(requirements);
    }

    /**
     * Le palier immédiatement suivant celui débloqué, s'il est défini. Borne sur
     * {@link StarterToolKitConfig#effectiveTier} pour que la progression reste contiguë même si la
     * variable en base porte une valeur aberrante.
     */
    public static Optional<StarterKitTier> nextTier(StarterToolKitConfig config, int unlockedLevel) {
        int effective = config.effectiveTier(unlockedLevel).map(StarterKitTier::level).orElse(1);
        return config.tier(effective + 1);
    }
}
