package com.lodygames.rpgquest.hub;

import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import java.util.List;
import java.util.Set;
import org.bukkit.block.Block;

/**
 * Exception explicite à la fermeture des postes de travail du Hub (issue #675, retour du 22/10), lue
 * dans {@code hub.stations.exceptions} de {@code config.yml} — liste <strong>vide par défaut</strong>.
 *
 * <p>Une exception s'applique à un poste si son type figure dans {@link #stations} (vide = tous les
 * postes) <strong>et</strong>, si {@link #blocks} n'est pas vide, si le bloc cliqué en fait partie. Elle
 * ouvre alors le poste à qui remplit toutes ses conditions : niveau RPG ({@code required-level}, règle
 * #532) et conditions joueur du même évaluateur que les dialogues et les portails (#567). Plusieurs
 * exceptions concernées : une seule suffit.</p>
 *
 * <p><strong>Accès achetés (point d'extension, non implémenté)</strong> : {@link #id} est la clé stable
 * qu'un futur droit acheté désignera. Le modèle prévu ne demande aucun nouveau type de condition : un
 * achat (prix #336) ou un droit à récompense (#422) posera une variable joueur, par exemple {@code
 * HUB_STATION_<ID>=true}, et l'exception l'exigera par une condition {@code VARIABLE_EQUALS}.</p>
 *
 * @param id            identifiant stable (minuscules, chiffres, {@code _} ou {@code -})
 * @param stations      postes concernés (vide = tous)
 * @param blocks        blocs précis (vide = tous les blocs des postes concernés)
 * @param requiredLevel niveau RPG minimum ({@code null} = aucun)
 * @param conditions    conditions joueur, toutes exigées (vide = aucune)
 * @param message       refus si les conditions ne sont pas remplies (MiniMessage, {@code ""} = message par défaut)
 */
public record HubStationException(String id, Set<HubStation> stations, List<BlockRef> blocks, Integer requiredLevel,
                                  List<DialogueCondition> conditions, String message) {

    /** Un bloc précis : monde exact et coordonnées entières. */
    public record BlockRef(String world, int x, int y, int z) {

        boolean matches(Block block) {
            return block.getX() == x && block.getY() == y && block.getZ() == z
                    && block.getWorld().getName().equals(world);
        }
    }

    public HubStationException {
        stations = Set.copyOf(stations);
        blocks = List.copyOf(blocks);
        conditions = List.copyOf(conditions);
        message = message == null ? "" : message.strip();
    }

    boolean appliesTo(HubStation station, Block block) {
        return (stations.isEmpty() || stations.contains(station))
                && (blocks.isEmpty() || blocks.stream().anyMatch(b -> b.matches(block)));
    }

    /** Exception sans aucune condition : elle ouvre le poste immédiatement. */
    boolean unconditional() {
        return requiredLevel == null && conditions.isEmpty();
    }
}
