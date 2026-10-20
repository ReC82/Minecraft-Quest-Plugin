package com.lodygames.rpgquest.world.create;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Création d'un monde depuis PlugAdmin (issue #562, #331-W3) : aperçu sans écriture, puis création
 * confirmée, une seule à la fois, idempotente (jeton), relue et journalisée. Méthodes appelées sur
 * le <strong>thread principal</strong> (état Bukkit et création Multiverse synchrones).
 *
 * <p>Rien n'est jamais créé automatiquement : ni au démarrage, ni au rechargement, ni par l'aperçu.
 * Le nouveau monde ne reçoit aucun rôle Hub / Wild / Claims, aucun portail, aucune règle ; les
 * waypoints automatiques y restent interdits (monde absent de {@code travel.waypoint.worlds}).</p>
 */
public final class WorldCreationService {

    /** Verdict d'une demande de création. */
    public enum Result {
        /** Créé, chargé et relu conforme. */
        CREATED,
        /** Créé, mais une valeur relue diffère de la demande. */
        MISMATCH,
        /** Multiverse-Core a refusé ou échoué : aucun monde chargé. */
        FAILED,
        /** Demande invalide : rien n'a été créé. */
        REFUSED,
        /** La demande diffère de l'aperçu confirmé : rien n'a été créé. */
        STALE,
        /** Une autre création est en cours : rien n'a été créé. */
        BUSY
    }

    /** Un contrôle de relecture. */
    public record Check(String label, String expected, String actual, boolean ok) {
    }

    /**
     * @param replayed {@code true} = jeton déjà traité : résultat d'origine renvoyé, rien n'a été refait
     */
    public record Creation(String world, Result result, String message, boolean replayed, List<Check> checks,
                           List<String> notes, WorldCreationPlanner.Plan plan, List<String> history) {
    }

    private final WorldCreationGateway gateway;
    private final WorldCreationAuditLog audit;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();

    public WorldCreationService(WorldCreationGateway gateway, WorldCreationAuditLog audit, Clock clock) {
        this.gateway = gateway;
        this.audit = audit;
        this.clock = clock;
    }

    /** Aperçu : validation complète, aucune écriture. */
    public WorldCreationPlanner.Plan preview(WorldCreationRequest request, Set<String> folders) {
        return WorldCreationPlanner.plan(request, gateway.context(folders));
    }

    /** Ce que la page « Créer un monde » propose : aucune écriture. */
    public record Options(WorldCreationPlanner.Context context, List<String> biomes, List<String> blocks,
                          List<WorldCreationAuditLog.Entry> history) {
    }

    public Options options(Set<String> folders) {
        return new Options(gateway.context(folders), gateway.biomes(), gateway.blocks(), audit.recent(10));
    }

    /**
     * Crée exactement le monde de l'aperçu confirmé. Un jeton déjà traité renvoie le résultat
     * d'origine sans rien refaire ; une demande invalide, différente de l'aperçu ou concurrente
     * d'une autre création est refusée sans rien créer.
     */
    public Creation create(WorldCreationRequest request, Set<String> folders, String expectedFingerprint,
                           String token, String actor) {
        Optional<WorldCreationAuditLog.Entry> done = audit.byToken(token);
        if (done.isPresent()) {
            WorldCreationAuditLog.Entry e = done.get();
            return new Creation(e.world(), parse(e.result()), "Demande déjà traitée le " + e.at() + " : rien n'a été "
                    + "refait. " + e.message(), true, List.of(), List.of(), null, e.checks());
        }
        if (!busy.compareAndSet(false, true)) {
            return new Creation(WorldCreationPlanner.identifier(request.name()), Result.BUSY,
                    "Une autre création de monde est en cours : rien n'a été créé, réessayer ensuite.", false,
                    List.of(), List.of(), null, List.of());
        }
        try {
            return record(token, actor, attempt(request, folders, expectedFingerprint));
        } finally {
            busy.set(false);
        }
    }

    private Creation attempt(WorldCreationRequest request, Set<String> folders, String expectedFingerprint) {
        WorldCreationPlanner.Plan plan = WorldCreationPlanner.plan(request, gateway.context(folders));
        if (!plan.ok()) {
            return outcome(plan, Result.REFUSED, "Création refusée : " + String.join(" ", plan.errors())
                    + " Rien n'a été créé.", List.of(), List.of());
        }
        if (!plan.fingerprint().equals(expectedFingerprint)) {
            return outcome(plan, Result.STALE, "La demande ne correspond plus à l'aperçu confirmé : rien n'a été créé. "
                    + "Refaire l'aperçu.", List.of(), List.of());
        }
        String error = gateway.create(plan);
        if (error != null) {
            return outcome(plan, Result.FAILED, "Multiverse-Core n'a pas créé « " + plan.world() + " » : " + error + ".",
                    List.of(), List.of());
        }
        List<String> notes = new ArrayList<>(gateway.configureCreated(plan.world()));
        Optional<WorldCreationGateway.Created> reread = gateway.describe(plan.world());
        if (reread.isEmpty()) {
            return outcome(plan, Result.MISMATCH, "Multiverse-Core a répondu « créé » mais le monde « " + plan.world()
                    + " » n'est pas chargé à la relecture.", List.of(), notes);
        }
        List<Check> checks = checks(plan, reread.get());
        long gaps = checks.stream().filter(c -> !c.ok()).count();
        return gaps == 0
                ? outcome(plan, Result.CREATED, "Monde « " + plan.world() + " » créé, chargé et relu conforme.", checks,
                        notes)
                : outcome(plan, Result.MISMATCH, "Monde « " + plan.world() + " » créé, mais " + gaps
                        + " valeur(s) relue(s) diffèrent de la demande : voir le détail.", checks, notes);
    }

    /** Relecture comparée à la demande. Le type de terrain ne se relit que pour un monde normal. */
    static List<Check> checks(WorldCreationPlanner.Plan plan, WorldCreationGateway.Created c) {
        List<Check> out = new ArrayList<>();
        out.add(check("Dimension", plan.dimension().name(), c.environment()));
        if (plan.dimension() == WorldCreationPlanner.Dimension.NORMAL) {
            out.add(check("Terrain", plan.terrain().name(), c.worldType()));
        }
        if (plan.seed() != null) {
            out.add(check("Graine", String.valueOf(plan.seed()), String.valueOf(c.seed())));
        }
        out.add(check("Structures", String.valueOf(plan.structures()), String.valueOf(c.structures())));
        if (plan.generatorSettings() != null) {
            out.add(check("Couches et biome (réglages du monde plat)", plan.generatorSettings(), c.generatorSettings()));
        }
        out.add(check("Géré par Multiverse-Core", "true", String.valueOf(c.multiverseKnown())));
        out.add(check("Rechargé au redémarrage", "true", String.valueOf(c.autoLoad())));
        out.add(check("Zone d'apparition gardée en mémoire", "false",
                c.keepSpawnLoaded() == null ? null : String.valueOf(c.keepSpawnLoaded())));
        return out;
    }

    private static Check check(String label, String expected, String actual) {
        return new Check(label, expected, actual == null ? "inconnu" : actual, Objects.equals(expected, actual));
    }

    private static Creation outcome(WorldCreationPlanner.Plan plan, Result result, String message, List<Check> checks,
                                    List<String> notes) {
        return new Creation(plan.world(), result, message, false, List.copyOf(checks), List.copyOf(notes), plan,
                List.of());
    }

    private Creation record(String token, String actor, Creation c) {
        List<String> lines = new ArrayList<>();
        c.checks().forEach(k -> lines.add((k.ok() ? "OK " : "ÉCART ") + k.label() + " : demandé " + k.expected()
                + ", relu " + k.actual()));
        c.notes().forEach(n -> lines.add("NOTE " + n));
        audit.append(new WorldCreationAuditLog.Entry(Instant.now(clock).toString(),
                actor == null || actor.isBlank() ? "inconnu" : actor, token, c.world(), c.result().name(), c.message(),
                lines));
        return c;
    }

    private static Result parse(String raw) {
        try {
            return Result.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Result.FAILED;
        }
    }
}
