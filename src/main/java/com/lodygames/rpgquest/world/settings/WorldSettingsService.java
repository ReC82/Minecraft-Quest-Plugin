package com.lodygames.rpgquest.world.settings;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Réglages d'un monde existant (issue #561, #331-W2) : relecture, aperçu sans écriture, puis
 * application confirmée avec relecture et journal. Toutes les méthodes s'appellent sur le
 * <strong>thread principal</strong> (état Bukkit) ; seul le journal écrit sur disque, en tâche de fond.
 *
 * <p>Rien n'est jamais appliqué automatiquement : ni au démarrage, ni au rechargement, ni à la
 * lecture. Seule {@link #apply} écrit, et seulement si l'état relu est encore celui de l'aperçu
 * (même empreinte) — sinon rien n'est écrit et il faut refaire un aperçu.</p>
 */
public final class WorldSettingsService {

    /** Verdict global d'une application. */
    public enum Result { APPLIED, PARTIAL, FAILED, STALE, REFUSED, NOT_LOADED }

    /** Un monde relu, avec ses dernières modifications. */
    public record Reading(Optional<WorldSettingsSnapshot> snapshot, String fingerprint,
                          List<WorldSettingsAuditLog.Entry> history) {
    }

    /** Aperçu : {@code plan} vide si le monde n'est pas chargé. */
    public record Preview(Optional<WorldSettingsSnapshot> snapshot, Optional<WorldSettingsPlanner.Plan> plan,
                          String message) {
    }

    /**
     * Ce qui s'est passé pour un réglage.
     *
     * @param outcome {@code OK} (relu = demandé), {@code MISMATCH} (écrit mais relu différent),
     *                {@code FAILED} (écriture refusée par le serveur), {@code NOT_ATTEMPTED}
     *                (non tenté après un échec précédent)
     */
    public record Applied(String key, String label, String before, String requested, String after, String outcome,
                          String detail) {
    }

    public record Application(String world, Result result, String message, List<Applied> applied,
                              List<String> notes) {
    }

    private final WorldSettingsGateway gateway;
    private final WorldSettingsAuditLog audit;
    private final Clock clock;

    public WorldSettingsService(WorldSettingsGateway gateway, WorldSettingsAuditLog audit, Clock clock) {
        this.gateway = gateway;
        this.audit = audit;
        this.clock = clock;
    }

    public Reading read(String world) {
        Optional<WorldSettingsSnapshot> snapshot = gateway.read(world);
        return new Reading(snapshot, snapshot.map(WorldSettingsPlanner::fingerprint).orElse(""),
                audit.recent(world, 10));
    }

    /**
     * Aperçu sans écriture. {@code spawnFromPlayer} (facultatif) : le point d'arrivée proposé est la
     * position actuelle de ce joueur, qui doit se trouver dans ce monde.
     */
    public Preview preview(String world, Map<String, String> requested, String spawnFromPlayer) {
        Optional<WorldSettingsSnapshot> snapshot = gateway.read(world);
        if (snapshot.isEmpty()) {
            return new Preview(snapshot, Optional.empty(), notLoaded(world));
        }
        Map<String, String> wanted = new LinkedHashMap<>(requested);
        String playerProblem = null;
        if (spawnFromPlayer != null && !spawnFromPlayer.isBlank()) {
            Optional<String> position = gateway.playerPosition(world, spawnFromPlayer);
            if (position.isPresent()) {
                wanted.put(WorldSetting.SPAWN.key(), position.get());
            } else {
                playerProblem = "Le joueur « " + spawnFromPlayer + " » n'est pas connecté dans le monde « " + world
                        + " » : sa position ne peut pas servir de point d'arrivée.";
            }
        }
        WorldSettingsPlanner.Plan plan = WorldSettingsPlanner.plan(snapshot.get(), wanted);
        if (playerProblem != null) {
            List<WorldSettingsPlanner.Change> changes = new ArrayList<>(plan.changes());
            changes.add(new WorldSettingsPlanner.Change(WorldSetting.SPAWN.key(), WorldSetting.SPAWN.label(),
                    snapshot.get().values().get(WorldSetting.SPAWN.key()), null,
                    snapshot.get().writers().getOrDefault(WorldSetting.SPAWN.key(), ""),
                    WorldSettingsPlanner.Status.REFUSED, playerProblem, ""));
            plan = new WorldSettingsPlanner.Plan(plan.world(), List.copyOf(changes), plan.fingerprint(),
                    plan.warnings());
        }
        String message = plan.changes().isEmpty() ? "Aucun réglage demandé."
                : plan.applicable() + " réglage(s) à appliquer, " + plan.refused() + " refusé(s). Rien n'a été écrit.";
        return new Preview(snapshot, Optional.of(plan), message);
    }

    /**
     * Applique exactement les réglages d'un aperçu. Refuse sans rien écrire si le monde n'est pas
     * chargé, si l'état a changé depuis l'aperçu ({@code expectedFingerprint}), si un réglage serait
     * refusé, ou s'il n'y a rien à appliquer. Écrit un réglage à la fois et s'arrête au premier échec,
     * puis relit tout et compare.
     */
    public Application apply(String world, Map<String, String> requested, String expectedFingerprint, String actor) {
        Optional<WorldSettingsSnapshot> before = gateway.read(world);
        if (before.isEmpty()) {
            return refusal(world, Result.NOT_LOADED, notLoaded(world));
        }
        WorldSettingsPlanner.Plan plan = WorldSettingsPlanner.plan(before.get(), requested);
        if (!plan.fingerprint().equals(expectedFingerprint)) {
            return refusal(world, Result.STALE, "Les réglages du monde « " + world + " » ont changé depuis l'aperçu. "
                    + "Rien n'a été écrit : refaire l'aperçu pour voir les valeurs actuelles.");
        }
        if (plan.refused() > 0) {
            return refusal(world, Result.REFUSED, plan.refused() + " réglage(s) refusé(s) dans la demande. Rien n'a été "
                    + "écrit.");
        }
        if (plan.applicable() == 0) {
            return refusal(world, Result.REFUSED, "Rien à appliquer : toutes les valeurs sont déjà en place.");
        }
        List<WorldSettingsPlanner.Change> toApply = plan.changes().stream()
                .filter(c -> c.status() == WorldSettingsPlanner.Status.APPLY).toList();
        Map<String, String> failures = new LinkedHashMap<>();
        boolean stopped = false;
        for (WorldSettingsPlanner.Change change : toApply) {
            if (stopped) {
                failures.put(change.key(), null);
                continue;
            }
            String error = gateway.write(world, change);
            if (error != null) {
                failures.put(change.key(), error);
                stopped = true;
            }
        }
        List<String> notes = new ArrayList<>();
        String persistError = gateway.persist(world);
        if (persistError != null) {
            notes.add("Enregistrement Multiverse-Core impossible : " + persistError
                    + ". Les valeurs sont en place mais pourraient revenir au prochain redémarrage.");
        }
        Optional<WorldSettingsSnapshot> after = gateway.read(world);
        List<Applied> applied = new ArrayList<>();
        int ok = 0;
        for (WorldSettingsPlanner.Change change : toApply) {
            String reread = after.map(s -> s.current(change.key())).orElse(null);
            String outcome;
            String detail;
            if (failures.containsKey(change.key())) {
                String error = failures.get(change.key());
                outcome = error == null ? "NOT_ATTEMPTED" : "FAILED";
                detail = error == null ? "Non tenté : un réglage précédent a échoué." : error;
            } else if (change.proposed().equals(reread)) {
                outcome = "OK";
                detail = "Valeur relue identique à la valeur demandée.";
                ok++;
            } else {
                outcome = "MISMATCH";
                detail = "Écrit, mais le serveur relit « " + (reread == null ? "inconnu" : reread)
                        + " » au lieu de « " + change.proposed() + " ».";
            }
            applied.add(new Applied(change.key(), change.label(), change.current(), change.proposed(), reread, outcome,
                    detail));
        }
        Result result = ok == toApply.size() && persistError == null ? Result.APPLIED
                : ok == 0 ? Result.FAILED : Result.PARTIAL;
        audit.append(new WorldSettingsAuditLog.Entry(Instant.now(clock).toString(),
                actor == null || actor.isBlank() ? "inconnu" : actor, world, result.name(),
                applied.stream().map(a -> new WorldSettingsAuditLog.Line(a.key(), nz(a.before()), a.requested(),
                        nz(a.after()), a.outcome())).toList()));
        String message = switch (result) {
            case APPLIED -> ok + " réglage(s) appliqué(s) et relu(s) sur « " + world + " ».";
            case PARTIAL -> ok + " réglage(s) sur " + toApply.size() + " confirmé(s) par relecture sur « " + world
                    + " » : voir les écarts.";
            default -> "Aucun réglage confirmé par relecture sur « " + world + " » : voir le détail.";
        };
        return new Application(world, result, message, List.copyOf(applied), List.copyOf(notes));
    }

    private static Application refusal(String world, Result result, String message) {
        return new Application(world, result, message, List.of(), List.of());
    }

    private static String notLoaded(String world) {
        return "Le monde « " + world + " » n'est pas chargé : ses réglages ne peuvent être ni relus ni modifiés "
                + "(le chargement d'un monde ne se fait pas depuis cette page).";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
