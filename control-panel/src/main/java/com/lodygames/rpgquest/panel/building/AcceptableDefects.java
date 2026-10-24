package com.lodygames.rpgquest.panel.building;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Les défauts qu'une vérification déclare contournables (#747), lus dans le verdict du serveur.
 *
 * <p>Le panel ne décide pas lui-même ce qui est contournable : le plugin le dit dans le détail de
 * son audit ({@code acceptable} : code → effet en jeu). Le panel vérifie seulement que la liste
 * couvre EXACTEMENT les codes bloquants du verdict ; un audit d'avant #747 (sans la clé) n'offre
 * donc rien, et l'écran invite à vérifier de nouveau.</p>
 */
public final class AcceptableDefects {

    private AcceptableDefects() {
    }

    /** Code → effet, trié ; vide si cette vérification ne permet pas d'enregistrer malgré ses défauts. */
    public static Map<String, String> of(BuildingPlanWorkbench.CheckState audit) {
        if (audit == null || !audit.current() || audit.status() != BuildingPlanWorkbench.Status.DONE) {
            return Map.of();
        }
        return of(audit.details());
    }

    /** Même lecture, sur le détail brut d'un audit. */
    public static Map<String, String> of(Map<String, Object> details) {
        if (details == null || !(details.get("acceptable") instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        new TreeSet<>(raw.keySet().stream().map(String::valueOf).toList())
                .forEach(code -> out.put(code, String.valueOf(raw.get(code))));
        Object codes = details.get("codes");
        List<String> blocking = codes instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        return new TreeSet<>(blocking).equals(new TreeSet<>(out.keySet())) ? java.util.Collections.unmodifiableMap(out) : Map.of();
    }

    /** Les codes, dans la forme attendue par {@code building.generate} : triés, séparés par des virgules. */
    public static String param(Map<String, String> defects) {
        return String.join(",", new TreeSet<>(defects.keySet()));
    }
}
