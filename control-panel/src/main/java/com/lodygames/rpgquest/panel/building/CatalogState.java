package com.lodygames.rpgquest.panel.building;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.json.Json;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Où en est un bâtiment dans la bibliothèque du serveur, d'après ce qui s'y est RÉELLEMENT passé
 * (#290).
 *
 * <p>Le défaut : après le retrait de la Maison du bibliothécaire, l'Atelier affichait encore
 * « Enregistré dans la bibliothèque » et proposait de la poser. L'écran se fiait au seul succès de
 * l'enregistrement de la version courante. L'état se lit maintenant sur le DERNIER fait connu pour
 * cet identifiant : un enregistrement réussi, un retrait réussi, ou un relevé de la bibliothèque
 * (« Rafraîchir », rechargement, redémarrage) qui le contient ou non.</p>
 *
 * @param version la version publiée connue, 0 si inconnue
 * @param at      la date du fait qui fonde cet état
 */
public record CatalogState(State state, int version, Instant at) {

    public enum State {
        /** Aucun fait connu : jamais enregistré, ou rien de relevé. */
        UNKNOWN,
        /** Présent dans la bibliothèque, posable. */
        IN_LIBRARY,
        /** Retiré par « Retirer de la bibliothèque » : archivé, plus posable. */
        RETIRED,
        /** Absent du dernier relevé de la bibliothèque, sans retrait connu. */
        ABSENT
    }

    private static final int SCAN = 200;
    private static final Pattern PLAN_ID = Pattern.compile("(?m)^id:\\s*([a-z0-9_]{3,48})\\s*$");

    public boolean inLibrary() {
        return state == State.IN_LIBRARY;
    }

    public boolean gone() {
        return state == State.RETIRED || state == State.ABSENT;
    }

    public static CatalogState unknown() {
        return new CatalogState(State.UNKNOWN, 0, Instant.EPOCH);
    }

    public static CatalogState of(AgentStore store, String agentId, String buildingId) {
        if (store == null || buildingId == null || buildingId.isBlank()) {
            return unknown();
        }
        CatalogState best = unknown();
        for (AgentActionRow row : store.successfulActionsOfType(agentId, "building.generate", SCAN)) {
            if (buildingId.equals(generatedId(row))) {
                int version = details(row).map(d -> d.get("version")).filter(Number.class::isInstance)
                        .map(n -> ((Number) n).intValue()).orElse(0);
                best = later(best, new CatalogState(State.IN_LIBRARY, version, when(row)));
                break;
            }
        }
        for (AgentActionRow row : store.successfulActionsOfType(agentId, "building.definition.retire", SCAN)) {
            if (buildingId.equals(row.params().getOrDefault("id", ""))) {
                best = later(best, new CatalogState(State.RETIRED, best.version(), when(row)));
                break;
            }
        }
        Optional<AgentActionRow> listing = store.latestSuccessfulActionOfType(agentId, "building.definition.list");
        if (listing.isPresent()) {
            var directory = details(listing.get()).map(BuildingLibraryDirectory::from);
            if (directory.isPresent() && directory.get().available()) {
                var found = directory.get().find(buildingId);
                CatalogState seen = found.map(b -> new CatalogState(State.IN_LIBRARY, b.version(), when(listing.get())))
                        .orElse(new CatalogState(best.state() == State.RETIRED ? State.RETIRED : State.ABSENT,
                                best.version(), when(listing.get())));
                if (best.state() != State.UNKNOWN || found.isPresent()) {
                    best = later(best, seen);
                }
            }
        }
        return best;
    }

    private static CatalogState later(CatalogState a, CatalogState b) {
        return b.at().isAfter(a.at()) || a.state() == State.UNKNOWN ? b : a;
    }

    private static Instant when(AgentActionRow row) {
        return row.completedAt() != null ? row.completedAt() : row.createdAt();
    }

    /** L'identifiant réellement écrit par cet enregistrement. */
    public static String generatedIdOf(AgentActionRow row) {
        return generatedId(row);
    }

    /** L'identifiant réellement écrit : celui du résultat, à défaut celui du plan envoyé. */
    static String generatedId(AgentActionRow row) {
        Optional<String> fromResult = details(row).map(d -> d.get("buildingId"))
                .filter(String.class::isInstance).map(String.class::cast).filter(s -> !s.isBlank());
        if (fromResult.isPresent()) {
            return fromResult.get();
        }
        Matcher m = PLAN_ID.matcher(row.params().getOrDefault("plan", "").replace("\r\n", "\n"));
        return m.find() ? m.group(1) : "";
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> details(AgentActionRow row) {
        if (row.resultJson() == null || row.resultJson().isBlank()) {
            return Optional.empty();
        }
        try {
            Object details = Json.parseObject(row.resultJson()).get("details");
            return details instanceof Map<?, ?> m ? Optional.of((Map<String, Object>) m) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Pour l'écran : la ligne qui dit où en est le bâtiment. */
    public String label() {
        return switch (state) {
            case IN_LIBRARY -> "Dans la bibliothèque" + (version > 0 ? " (version " + version + ")" : "");
            case RETIRED -> "Retiré de la bibliothèque";
            case ABSENT -> "Absent de la bibliothèque au dernier relevé";
            case UNKNOWN -> "Jamais enregistré";
        };
    }

}
