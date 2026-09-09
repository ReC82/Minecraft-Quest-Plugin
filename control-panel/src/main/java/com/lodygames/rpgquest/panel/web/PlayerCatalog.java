package com.lodygames.rpgquest.panel.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vue <strong>annuaire</strong> des joueurs (issue #96), dérivée du payload de l'action agent
 * {@code player.catalog} sans aucune dépendance Bukkit — testable en JUnit pur.
 *
 * <p>La source de vérité des joueurs est le serveur Paper ({@code OfflinePlayer}) : l'agent renvoie
 * <em>tous</em> les joueurs déjà venus au moins une fois + les connectés. PlugAdmin ne conserve
 * aucune base de joueurs propre — tri, filtre, recherche et pagination sont faits ici, à la lecture.</p>
 */
public final class PlayerCatalog {

    /** Taille de page par défaut de la liste {@code /players}. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private PlayerCatalog() {
    }

    /**
     * Un joueur de l'annuaire. {@code uuid} = identité stable (backend) ; {@code name} = identité
     * UX (dernier pseudo connu, éventuellement {@code null}). Instants en millisecondes epoch,
     * {@code null} si indisponibles. Monde / position seulement si {@code online}.
     */
    public record Entry(String uuid, String name, boolean online, boolean banned, String banReason,
                        Long firstPlayed, Long lastSeen, String world, Integer x, Integer y, Integer z) {

        /** Libellé UX : le pseudo, ou un repli explicite — jamais l'UUID à la place du nom. */
        public String displayName() {
            return name == null || name.isBlank() || "null".equals(name) ? "(pseudo inconnu)" : name;
        }

        boolean matches(String loweredQuery) {
            if (loweredQuery.isEmpty()) {
                return true;
            }
            if (name != null && name.toLowerCase(Locale.ROOT).contains(loweredQuery)) {
                return true;
            }
            return uuid != null && uuid.toLowerCase(Locale.ROOT).contains(loweredQuery);
        }
    }

    /** Filtres de la liste. Combinables avec la recherche (ET). */
    public enum Filter {
        ALL, ONLINE, OFFLINE, BANNED;

        public static Filter of(String raw) {
            if (raw == null) {
                return ALL;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "online", "en-ligne" -> ONLINE;
                case "offline", "hors-ligne" -> OFFLINE;
                case "banned", "bannis", "banni" -> BANNED;
                default -> ALL;
            };
        }

        boolean keep(Entry e) {
            return switch (this) {
                case ALL -> true;
                case ONLINE -> e.online();
                case OFFLINE -> !e.online();
                case BANNED -> e.banned();
            };
        }
    }

    /** Ordre de la liste. {@code RECENT} = connectés d'abord, puis dernière connexion décroissante. */
    public enum Sort {
        RECENT, NAME;

        public static Sort of(String raw) {
            return raw != null && raw.trim().equalsIgnoreCase("name") ? NAME : RECENT;
        }
    }

    /** Résultat paginé + compteurs globaux (pour les puces de filtre et la synthèse). */
    public record Page(List<Entry> entries, int total, int matched, int page, int pageSize, int pageCount,
                       int online, int offline, int banned) {
    }

    /** Parse les lignes brutes ({@code List<Map<String,Object>>}) du payload {@code player.catalog}. */
    public static List<Entry> parse(Object rawRows) {
        List<Entry> out = new ArrayList<>();
        if (!(rawRows instanceof List<?> rows)) {
            return out;
        }
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String uuid = str(m.get("uuid"));
            if (uuid.isEmpty()) {
                continue; // une ligne sans identité stable est inexploitable — ignorée, jamais fatale
            }
            out.add(new Entry(
                    uuid,
                    nullableStr(m.get("name")),
                    truthy(m.get("online")),
                    truthy(m.get("banned")),
                    nullableStr(m.get("banReason")),
                    epoch(m.get("firstPlayed")),
                    epoch(m.get("lastSeen")),
                    nullableStr(m.get("world")),
                    intOrNull(m.get("x")), intOrNull(m.get("y")), intOrNull(m.get("z"))));
        }
        return out;
    }

    /**
     * Applique recherche + filtre + tri + pagination. {@code page} est 1-indexé (borné) ;
     * {@code pageSize} borné à [1, 500].
     */
    public static Page view(List<Entry> all, String query, Filter filter, Sort sort, int page, int pageSize) {
        List<Entry> source = all == null ? List.of() : all;
        int online = 0;
        int banned = 0;
        for (Entry e : source) {
            if (e.online()) {
                online++;
            }
            if (e.banned()) {
                banned++;
            }
        }

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Filter f = filter == null ? Filter.ALL : filter;
        List<Entry> matched = new ArrayList<>();
        for (Entry e : source) {
            if (f.keep(e) && e.matches(q)) {
                matched.add(e);
            }
        }

        matched.sort((a, b) -> {
            if (sort == Sort.NAME) {
                int byName = a.displayName().compareToIgnoreCase(b.displayName());
                return byName != 0 ? byName : a.uuid().compareToIgnoreCase(b.uuid());
            }
            if (a.online() != b.online()) {
                return a.online() ? -1 : 1;
            }
            long la = a.lastSeen() == null ? Long.MIN_VALUE : a.lastSeen();
            long lb = b.lastSeen() == null ? Long.MIN_VALUE : b.lastSeen();
            if (la != lb) {
                return Long.compare(lb, la);
            }
            return a.displayName().compareToIgnoreCase(b.displayName());
        });

        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, 500));
        int pageCount = Math.max(1, (matched.size() + size - 1) / size);
        int p = Math.max(1, Math.min(page, pageCount));
        int from = (p - 1) * size;
        int to = Math.min(from + size, matched.size());
        List<Entry> slice = from >= matched.size() ? List.of() : List.copyOf(matched.subList(from, to));

        return new Page(slice, source.size(), matched.size(), p, size, pageCount,
                online, source.size() - online, banned);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String nullableStr(Object v) {
        String s = str(v);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(str(v));
    }

    private static Long epoch(Object v) {
        if (v instanceof Number n) {
            long l = n.longValue();
            return l > 0 ? l : null;
        }
        try {
            long l = Long.parseLong(str(v).trim());
            return l > 0 ? l : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(str(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
