package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Les emplacements de construction du serveur, tels que le panel peut les raisonner (issue #213).
 *
 * <p>Construit depuis le dernier relevé {@code building.site.list} <strong>réussi</strong>. Aucune
 * requête n'est déclenchée : un relevé absent donne un annuaire {@link #available() indisponible},
 * et l'écran doit alors dire « cliquez sur Rafraîchir » au lieu d'afficher une liste vide, qui se
 * lirait comme « aucun emplacement ».</p>
 */
public record BuildingSiteDirectory(List<BuildingSiteView> sites, List<String> worlds,
                                    boolean available) {

    public BuildingSiteDirectory {
        sites = List.copyOf(sites == null ? List.of() : sites);
        worlds = List.copyOf(worlds == null ? List.of() : worlds);
    }

    public static BuildingSiteDirectory unavailable() {
        return new BuildingSiteDirectory(List.of(), List.of(), false);
    }

    /** Projette les détails d'un {@code building.site.list}. {@code null} ⇒ indisponible. */
    public static BuildingSiteDirectory from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        List<BuildingSiteView> out = new ArrayList<>();
        for (Object raw : asList(details.get("sites"))) {
            Map<String, Object> m = asMap(raw);
            String id = str(m.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            out.add(new BuildingSiteView(id, str(m.get("name")), str(m.get("description")),
                    str(m.get("world")), intOr(m.get("x")), intOr(m.get("y")), intOr(m.get("z")),
                    str(m.get("facing")), str(m.get("status")), str(m.get("createdBy")),
                    str(m.get("createdAt")), bool(m.get("worldLoaded"))));
        }
        // Les mondes viennent du serveur, jamais recalculés ici : lui seul sait ce qu'il porte.
        List<String> worlds = asList(details.get("worlds")).stream()
                .map(BuildingSiteDirectory::str).filter(w -> !w.isEmpty()).sorted().toList();
        return new BuildingSiteDirectory(out, worlds, true);
    }

    public Optional<BuildingSiteView> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return sites.stream()
                .filter(s -> s.id().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /** Les emplacements d'un monde donné ; monde vide ou {@code null} = tous. */
    public List<BuildingSiteView> inWorld(String world) {
        if (world == null || world.isBlank()) {
            return sites;
        }
        String wanted = world.trim();
        return sites.stream().filter(s -> s.world().equals(wanted)).toList();
    }

    /**
     * Les emplacements situés dans un monde que le serveur n'a pas chargé. Ils restent parfaitement
     * valides — c'est une information à afficher, pas une anomalie à corriger.
     */
    public List<BuildingSiteView> inUnloadedWorlds() {
        return sites.stream().filter(s -> !s.worldLoaded()).toList();
    }

    public int total() {
        return sites.size();
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static List<Object> asList(Object raw) {
        return raw instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim();
        return "null".equals(s) ? "" : s;
    }

    private static boolean bool(Object raw) {
        return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(str(raw));
    }

    private static int intOr(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = str(raw);
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
