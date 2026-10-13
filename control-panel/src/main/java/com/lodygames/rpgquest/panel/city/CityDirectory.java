package com.lodygames.rpgquest.panel.city;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Le dernier relevé {@code city.list} réussi (issue #178). Sans relevé, l'annuaire est
 * explicitement <em>indisponible</em> : une liste vide affirmerait qu'aucune ville n'existe, ce
 * qu'on ne sait pas.
 */
public record CityDirectory(List<CityView> cities, List<PendingMove> pendingMoves, boolean available,
                            int minDistance, int maxDistance, int defaultDistance) {

    /** Un déplacement de centre armé, en attente du clic d'outil du joueur. */
    public record PendingMove(String player, String cityId, String requestedBy, String expiresAt) {
    }

    public CityDirectory {
        cities = List.copyOf(cities == null ? List.of() : cities);
        pendingMoves = List.copyOf(pendingMoves == null ? List.of() : pendingMoves);
    }

    public static CityDirectory unavailable() {
        return new CityDirectory(List.of(), List.of(), false, 5, 500, 50);
    }

    public static CityDirectory from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        List<CityView> out = new ArrayList<>();
        for (Object raw : list(details.get("cities"))) {
            Map<String, Object> m = map(raw);
            String id = str(m.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            out.add(new CityView(id, str(m.get("name")), str(m.get("world")), dbl(m.get("x")), dbl(m.get("y")),
                    dbl(m.get("z")), integer(m.get("centerX")), integer(m.get("centerZ")),
                    integer(m.get("distance")), bool(m.get("active")), str(m.get("createdAt")),
                    bool(m.get("worldLoaded")), integer(m.get("minX")), integer(m.get("maxX")),
                    integer(m.get("minZ")), integer(m.get("maxZ")), integer(m.get("width")),
                    integer(m.get("discoveries")),
                    list(m.get("overlaps")).stream().map(CityDirectory::str).filter(s -> !s.isEmpty()).toList()));
        }
        List<PendingMove> moves = new ArrayList<>();
        for (Object raw : list(details.get("pendingMoves"))) {
            Map<String, Object> m = map(raw);
            moves.add(new PendingMove(str(m.get("player")), str(m.get("cityId")), str(m.get("requestedBy")),
                    str(m.get("expiresAt"))));
        }
        return new CityDirectory(out, moves, true, orDefault(details.get("minDistance"), 5),
                orDefault(details.get("maxDistance"), 500), orDefault(details.get("defaultDistance"), 50));
    }

    public Optional<CityView> find(String id) {
        return cities.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Nom affiché d'une ville connue, sinon l'identifiant tel quel. */
    public String nameOf(String id) {
        return find(id).map(CityView::name).orElse(id);
    }

    public List<String> worlds() {
        return cities.stream().map(CityView::world).distinct().sorted().toList();
    }

    public Optional<PendingMove> pendingFor(String cityId) {
        return pendingMoves.stream().filter(m -> m.cityId().equals(cityId)).findFirst();
    }

    private static List<Object> list(Object o) {
        if (o instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int integer(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : (int) Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int orDefault(Object o, int fallback) {
        return o == null ? fallback : integer(o);
    }

    private static double dbl(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? 0 : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equalsIgnoreCase(str(o));
    }
}
