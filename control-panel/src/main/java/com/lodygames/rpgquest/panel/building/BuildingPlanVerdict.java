package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le verdict rendu par le serveur sur un plan de bâtiment, tel que le panel le relit (issue #94,
 * lot F).
 *
 * <h2>Pourquoi le panel relit un verdict au lieu de le calculer</h2>
 *
 * <p>Le panel n'embarque ni bibliothèque YAML, ni moteur d'expansion, et il ne doit pas en
 * embarquer : le plugin est le validateur autoritaire, et un second juge finirait par contredire le
 * premier. Ce record ne fait donc que <strong>projeter</strong> le détail de l'action
 * {@code building.plan.audit} en types lisibles par un écran.</p>
 *
 * <h2>Un verdict n'est pas un bâtiment</h2>
 *
 * <p>{@link #accepted()} veut dire « le serveur accepterait ce plan », et rien de plus. Aucun
 * fichier n'existe, aucune entrée de bibliothèque n'a été créée. C'est la distinction que l'écran
 * doit rendre visible, parce que c'est exactement celle que la V1 avait brouillée en annonçant
 * « prêt à être posé » pour un bâtiment hermétique.</p>
 */
public record BuildingPlanVerdict(boolean available, boolean accepted, String code, String message,
                                  String buildingId, String kind, int sizeX, int sizeY, int sizeZ,
                                  int blocks, List<String> codes, List<String> warnings,
                                  boolean exists, Map<String, Object> metrics) {

    public BuildingPlanVerdict {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
        buildingId = buildingId == null ? "" : buildingId;
        kind = kind == null ? "" : kind;
        codes = List.copyOf(codes == null ? List.of() : codes);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        metrics = Map.copyOf(metrics == null ? Map.of() : metrics);
    }

    /** Aucun audit n'a encore été demandé — à ne pas confondre avec « le plan est bon ». */
    public static BuildingPlanVerdict none() {
        return new BuildingPlanVerdict(false, false, "", "", "", "", 0, 0, 0, 0,
                List.of(), List.of(), false, Map.of());
    }

    /**
     * Projette le détail d'une action {@code building.plan.audit}.
     *
     * <p>Les clés absentes restent à leur valeur neutre : un détail tronqué ne doit pas se lire
     * comme une mesure nulle. C'est pour cela que {@link #metrics()} est rendu tel quel, à charge
     * pour l'écran de n'afficher que ce qui y est.</p>
     */
    public static BuildingPlanVerdict from(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return none();
        }
        String code = str(details, "code");
        return new BuildingPlanVerdict(true, "OK".equals(code), code, str(details, "message"),
                str(details, "buildingId"), str(details, "kind"),
                intOf(details, "sizeX"), intOf(details, "sizeY"), intOf(details, "sizeZ"),
                intOf(details, "blocks"), strings(details.get("codes")),
                strings(details.get("warnings")),
                Boolean.TRUE.equals(details.get("exists")),
                mapOf(details.get("metrics")));
    }

    /** Le verdict, en une phrase, pour l'en-tête d'une carte. */
    public String label() {
        if (!available) {
            return "Aucun audit demandé";
        }
        return accepted
                ? "Plan conforme — aucun artefact n'a été écrit"
                : "Plan refusé (" + code + ")";
    }

    private static String str(Map<String, Object> details, String key) {
        Object value = details.get(key);
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

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
