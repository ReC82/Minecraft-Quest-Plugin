package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * La bibliothèque de bâtiments, telle que le panel la lit (issue #213, lot « placement »).
 *
 * <p>Construite depuis le dernier relevé {@code building.definition.list} <strong>réussi</strong>.
 * Comme pour les emplacements, un relevé absent donne une bibliothèque
 * {@link #available() indisponible} : l'écran doit alors dire « cliquez sur Rafraîchir » plutôt
 * qu'afficher une liste vide, qui se lirait comme « aucun bâtiment disponible ».</p>
 *
 * @param engineAvailable le moteur de schematics est-il exploitable sur le serveur ? La
 *                        bibliothèque reste consultable sans lui, mais rien ne peut être posé — et
 *                        c'est exactement ce qu'il faut dire, plutôt que de laisser un bouton
 *                        « Placer » promettre ce qu'il ne peut pas tenir
 */
public record BuildingLibraryDirectory(List<BuildingDefinitionView> buildings,
                                       List<String> problems,
                                       boolean engineAvailable,
                                       String engineReason,
                                       boolean available) {

    public BuildingLibraryDirectory {
        buildings = List.copyOf(buildings == null ? List.of() : buildings);
        problems = List.copyOf(problems == null ? List.of() : problems);
        engineReason = engineReason == null ? "" : engineReason;
    }

    public static BuildingLibraryDirectory unavailable() {
        return new BuildingLibraryDirectory(List.of(), List.of(), false, "", false);
    }

    /** Projette les détails d'un {@code building.definition.list}. {@code null} ⇒ indisponible. */
    public static BuildingLibraryDirectory from(Map<String, Object> details) {
        if (details == null) {
            return unavailable();
        }
        List<BuildingDefinitionView> out = new ArrayList<>();
        for (Object raw : asList(details.get("buildings"))) {
            Map<String, Object> m = asMap(raw);
            String id = str(m.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            List<String> materials = asList(m.get("materials")).stream()
                    .map(BuildingLibraryDirectory::str)
                    .filter(value -> !value.isEmpty())
                    .toList();
            out.add(new BuildingDefinitionView(id, str(m.get("name")), str(m.get("description")),
                    intOr(m.get("sizeX")), intOr(m.get("sizeY")), intOr(m.get("sizeZ")),
                    intOr(m.get("anchorX")), intOr(m.get("anchorY")), intOr(m.get("anchorZ")),
                    str(m.get("front")), materials, str(m.get("schematic")),
                    bool(m.get("schematicPresent")), intOr(m.get("version"))));
        }
        List<String> problems = asList(details.get("problems")).stream()
                .map(BuildingLibraryDirectory::str)
                .filter(value -> !value.isEmpty())
                .toList();
        return new BuildingLibraryDirectory(out, problems, bool(details.get("engineAvailable")),
                str(details.get("engineReason")), true);
    }

    public Optional<BuildingDefinitionView> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return buildings.stream()
                .filter(b -> b.id().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /** Les bâtiments réellement posables : définition chargée ET fichier présent. */
    public List<BuildingDefinitionView> placeable() {
        return buildings.stream().filter(BuildingDefinitionView::schematicPresent).toList();
    }

    public int total() {
        return buildings.size();
    }

    private static List<Object> asList(Object raw) {
        return raw instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean value ? value : Boolean.parseBoolean(str(raw));
    }

    private static int intOr(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(str(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
