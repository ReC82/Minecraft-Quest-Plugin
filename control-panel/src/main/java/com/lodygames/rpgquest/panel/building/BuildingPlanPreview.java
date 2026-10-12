package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les coupes 2D d'un plan, telles que le panel les relit (issue #94, lot E).
 *
 * <h2>Aucun moteur de géométrie ici</h2>
 *
 * <p>Ce record ne calcule rien : il <strong>projette</strong> le détail de l'action
 * {@code building.plan.preview}. C'est une exigence explicite du ticket — pas de second moteur
 * d'expansion dans le panel. Un aperçu dessiné ici montrerait ce que le panel <em>croit</em> que le
 * plan décrit, et la V1 a prouvé que l'intention d'un plan et la grille produite peuvent diverger de
 * cinq blocs : son percement de porte visait {@code x = 5} alors que la façade était en
 * {@code x = 0}. Un aperçu qui reproduirait cette erreur serait pire que pas d'aperçu.</p>
 *
 * <h2>Un aperçu n'est pas un bâtiment</h2>
 *
 * <p>Comme {@link BuildingPlanVerdict}, cet objet décrit un plan, pas un artefact. Aucun fichier
 * n'existe quand il est affiché.</p>
 */
public record BuildingPlanPreview(boolean available, boolean accepted, String code, String message,
                                  String buildingId, String kind, int sizeX, int sizeY, int sizeZ,
                                  int blocks, int anchorX, int anchorY, int anchorZ, String front,
                                  List<String> codes, List<String> warnings,
                                  List<Slice> plans, List<Slice> elevations) {

    /** La légende des caractères, dans l'ordre où elle se lit le mieux. */
    public static final List<Map.Entry<String, String>> LEGEND = List.of(
            Map.entry("#", "matière opaque (mur, sol, toit)"),
            Map.entry("o", "vitrage"),
            Map.entry("+", "franchissable : porte, trappe, portillon"),
            Map.entry("/", "escalier ou dalle — ce qui fait gagner de la hauteur"),
            Map.entry("H", "grimpable : échelle"),
            Map.entry("*", "source de lumière utile"),
            Map.entry("f", "mobilier et contenu intérieur"),
            Map.entry(".", "vide"));

    public BuildingPlanPreview {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
        buildingId = buildingId == null ? "" : buildingId;
        kind = kind == null ? "" : kind;
        front = front == null ? "" : front;
        codes = List.copyOf(codes == null ? List.of() : codes);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        plans = List.copyOf(plans == null ? List.of() : plans);
        elevations = List.copyOf(elevations == null ? List.of() : elevations);
    }

    /** Une coupe, prête à être rendue telle quelle. */
    public record Slice(String id, String label, String axes, List<String> rows) {

        public Slice {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            axes = axes == null ? "" : axes;
            rows = List.copyOf(rows == null ? List.of() : rows);
        }

        /** La grille, en un seul bloc de texte prêt pour un {@code <pre>}. */
        public String text() {
            return String.join("\n", rows);
        }

        public int width() {
            return rows.isEmpty() ? 0 : rows.get(0).length();
        }
    }

    public static BuildingPlanPreview none() {
        return new BuildingPlanPreview(false, false, "", "", "", "", 0, 0, 0, 0, 0, 0, 0, "",
                List.of(), List.of(), List.of(), List.of());
    }

    public static BuildingPlanPreview from(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return none();
        }
        String code = str(details, "code");
        return new BuildingPlanPreview(true, "OK".equals(code), code, str(details, "message"),
                str(details, "buildingId"), str(details, "kind"),
                intOf(details, "sizeX"), intOf(details, "sizeY"), intOf(details, "sizeZ"),
                intOf(details, "blocks"), intOf(details, "anchorX"), intOf(details, "anchorY"),
                intOf(details, "anchorZ"), str(details, "front"),
                labels(details.get("codes")), labels(details.get("warnings")),
                slices(details.get("plans")), slices(details.get("elevations")));
    }

    /** Y a-t-il quelque chose à dessiner ? Un plan illisible n'a aucune coupe. */
    public boolean hasSlices() {
        return !plans.isEmpty() || !elevations.isEmpty();
    }

    /** L'ancre, en une phrase : c'est la question que pose forcément quiconque pose un bâtiment. */
    public String anchorLabel() {
        return "x = " + anchorX + ", y = " + anchorY + ", z = " + anchorZ
                + (anchorX == (sizeX - 1) / 2 && anchorZ == (sizeZ - 1) / 2 && anchorY == 0
                        ? " — centre de la base (convention par défaut)"
                        : " — ancre explicite déclarée par le plan");
    }

    private static List<Slice> slices(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Slice> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                // « getOrDefault » sur une Map<?, ?> refuse une valeur par défaut typée : la clé
                // capturée interdit d'y passer une chaîne. On lit donc, puis on remplace le null.
                out.add(new Slice(text(map.get("id")), text(map.get("label")),
                        text(map.get("axes")), strings(map.get("rows"))));
            }
        }
        return out;
    }

    private static String str(Map<String, Object> details, String key) {
        return text(details.get(key));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intOf(Map<String, Object> details, String key) {
        Object value = details.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** Des libellés : une entrée vide est du bruit et ne doit pas devenir un code fantôme. */
    private static List<String> labels(Object raw) {
        List<String> out = new ArrayList<>();
        for (String item : strings(raw)) {
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Les lignes d'une grille.
     *
     * <p>Contrairement aux listes de codes, une ligne <strong>vide ou faite d'espaces est
     * significative</strong> et doit être conservée : c'est un niveau sans rien à cet endroit. La
     * supprimer décalerait toutes les lignes suivantes et le dessin mentirait.</p>
     */
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            out.add(item == null ? "" : String.valueOf(item));
        }
        return out;
    }
}
