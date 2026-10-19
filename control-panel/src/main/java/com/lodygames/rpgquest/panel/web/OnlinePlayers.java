package com.lodygames.rpgquest.panel.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Joueurs connectés connus de PlugAdmin (issue #169, retour du 19/10), sans dépendance Bukkit.
 *
 * <p>Deux relevés de l'agent décrivent les connectés : {@code player.catalog} (page Joueurs, annuaire
 * complet) et {@code player.list} (connectés seuls). Le sélecteur « Près de » ne lisait que le second,
 * jamais lancé depuis /mobs : il restait vide alors que la page Joueurs montrait un joueur en ligne.
 * On retient désormais le relevé <em>le plus récent</em> des deux, avec sa date, et on classe chaque
 * joueur dans ou hors du monde Wild annoncé par le heartbeat. Rien n'est rafraîchi en arrière-plan :
 * la liste date du dernier relevé.</p>
 *
 * @param loaded    {@code false} : aucun relevé de joueurs n'a encore été fait
 * @param at        date du relevé retenu ({@code null} si aucun)
 * @param wildWorld nom du monde Wild (heartbeat), vide s'il est inconnu
 * @param players   joueurs connectés au moment du relevé
 */
public record OnlinePlayers(boolean loaded, Instant at, String wildWorld, List<Player> players) {

    /** Un joueur connecté et le monde où il se trouvait au moment du relevé ({@code ""} si inconnu). */
    public record Player(String name, String world) {
    }

    public OnlinePlayers {
        wildWorld = wildWorld == null ? "" : wildWorld;
        players = players == null ? List.of() : List.copyOf(players);
    }

    /** Un relevé brut : les {@code details} d'une action réussie et sa date. */
    public record Reading(Map<String, Object> details, Instant at) {
    }

    /**
     * Retient le relevé le plus récent. {@code fromCatalog} vient de {@code player.catalog} (on n'y garde
     * que {@code online: true}) ; {@code fromList} de {@code player.list} (tous connectés).
     */
    public static OnlinePlayers of(Reading fromCatalog, Reading fromList, String wildWorld) {
        boolean useCatalog = fromCatalog != null
                && (fromList == null || !isBefore(fromCatalog.at(), fromList.at()));
        Reading chosen = useCatalog ? fromCatalog : fromList;
        if (chosen == null) {
            return new OnlinePlayers(false, null, wildWorld, List.of());
        }
        List<Player> out = new ArrayList<>();
        Object rows = chosen.details() == null ? null : chosen.details().get("players");
        if (rows instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || (useCatalog && !Boolean.TRUE.equals(m.get("online")))) {
                    continue;
                }
                String name = clean(m.get("name"));
                if (!name.isEmpty()) {
                    out.add(new Player(name, clean(m.get("world"))));
                }
            }
        }
        return new OnlinePlayers(true, chosen.at(), wildWorld, out);
    }

    /** Le monde Wild est-il connu ? Sinon on ne peut pas trier : tous les connectés restent proposés. */
    public boolean wildKnown() {
        return !wildWorld.isBlank();
    }

    /** Dans le Wild (même nom de monde, casse ignorée), ou monde Wild inconnu. */
    public boolean inWild(Player p) {
        return !wildKnown() || wildWorld.equalsIgnoreCase(p.world());
    }

    public List<Player> inWild() {
        return players.stream().filter(this::inWild).toList();
    }

    public List<Player> outsideWild() {
        return players.stream().filter(p -> !inWild(p)).toList();
    }

    public List<String> names() {
        return players.stream().map(Player::name).toList();
    }

    private static boolean isBefore(Instant a, Instant b) {
        return (a != null && b != null && a.isBefore(b)) || (a == null && b != null);
    }

    private static String clean(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        return "null".equals(s.toLowerCase(Locale.ROOT)) ? "" : s;
    }
}
