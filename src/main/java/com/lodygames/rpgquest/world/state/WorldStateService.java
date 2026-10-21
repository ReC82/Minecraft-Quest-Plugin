package com.lodygames.rpgquest.world.state;

import com.lodygames.rpgquest.world.create.WorldCreationAuditLog;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Charger, décharger, importer un dossier existant et régler « charger au démarrage » depuis
 * PlugAdmin (issue #563, #331-W4). Une seule opération à la fois, idempotente (jeton), revérifiée
 * sur l'état réel juste avant d'agir, relue et journalisée. Méthodes appelées sur le
 * <strong>thread principal</strong> (état Bukkit et opérations Multiverse synchrones).
 *
 * <p>Rien n'est fait automatiquement : ni au démarrage, ni au rechargement, ni par le relevé. Aucune
 * opération ne supprime de fichier, ne régénère un monde ni ne le retire de Multiverse-Core.</p>
 */
public final class WorldStateService {

    /** Verdict d'une demande. */
    public enum Result {
        /** Fait et relu conforme. */
        DONE,
        /** Fait, mais une valeur relue diffère de la demande. */
        MISMATCH,
        /** Multiverse-Core a refusé ou échoué. */
        FAILED,
        /** Demande refusée par les règles : rien n'a été fait. */
        REFUSED,
        /** Une autre opération est en cours : rien n'a été fait. */
        BUSY
    }

    public record Check(String label, String expected, String actual, boolean ok) {
    }

    /** @param replayed {@code true} = jeton déjà traité : résultat d'origine renvoyé, rien n'a été refait */
    public record Outcome(String world, WorldStatePlanner.Operation operation, Result result, String message,
                          boolean replayed, List<Check> checks, List<String> notes, WorldStatePlanner.Plan plan,
                          List<String> history) {
    }

    /** Relevé de la page : contexte, verdicts par monde connu ou chargé, dossiers importables, journal. */
    public record Options(WorldStatePlanner.Context context,
                          Map<String, Map<WorldStatePlanner.Operation, WorldStatePlanner.Plan>> worlds,
                          List<WorldStatePlanner.Folder> importable, List<WorldCreationAuditLog.Entry> history) {
    }

    private final WorldStateGateway gateway;
    private final WorldCreationAuditLog audit;
    private final Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();

    public WorldStateService(WorldStateGateway gateway, WorldCreationAuditLog audit, Clock clock) {
        this.gateway = gateway;
        this.audit = audit;
        this.clock = clock;
    }

    /** Lecture seule : ce que chaque monde permet, et les dossiers importables. */
    public Options options(List<WorldStatePlanner.Folder> folders) {
        WorldStatePlanner.Context context = gateway.context(folders);
        Map<String, Map<WorldStatePlanner.Operation, WorldStatePlanner.Plan>> worlds = new java.util.TreeMap<>(
                String.CASE_INSENSITIVE_ORDER);
        context.players().keySet().forEach(w -> worlds.put(w, WorldStatePlanner.verdicts(w, context)));
        context.known().values().forEach(k -> worlds.putIfAbsent(k.name(), WorldStatePlanner.verdicts(k.name(), context)));
        return new Options(context, worlds, WorldStatePlanner.importable(context), audit.recent(10));
    }

    /** Exécute la demande si les règles, revérifiées ici sur l'état réel, l'autorisent. */
    public Outcome apply(WorldStatePlanner.Request request, List<WorldStatePlanner.Folder> folders, String token,
                         String actor) {
        Optional<WorldCreationAuditLog.Entry> done = audit.byToken(token);
        if (done.isPresent()) {
            WorldCreationAuditLog.Entry e = done.get();
            return new Outcome(e.world(), request.operation(), parse(e.result()), "Demande déjà traitée le " + e.at()
                    + " : rien n'a été refait. " + e.message(), true, List.of(), List.of(), null, e.checks());
        }
        if (!busy.compareAndSet(false, true)) {
            return new Outcome(request.world(), request.operation(), Result.BUSY, "Une autre opération sur un monde est "
                    + "en cours : rien n'a été fait, réessayer ensuite.", false, List.of(), List.of(), null, List.of());
        }
        try {
            return record(token, actor, attempt(request, folders));
        } finally {
            busy.set(false);
        }
    }

    private Outcome attempt(WorldStatePlanner.Request request, List<WorldStatePlanner.Folder> folders) {
        WorldStatePlanner.Context context = gateway.context(folders);
        WorldStatePlanner.Plan plan = WorldStatePlanner.plan(request, context);
        String verb = plan.operation().label();
        if (!plan.ok()) {
            return outcome(plan, Result.REFUSED, verb + " « " + plan.world() + " » refusé : "
                    + String.join(" ", plan.errors()) + " Rien n'a été fait.", List.of(), List.of());
        }
        String world = plan.world();
        String error = switch (plan.operation()) {
            case LOAD -> gateway.load(world);
            case UNLOAD -> gateway.unload(world);
            case IMPORT -> gateway.importFolder(world, plan.environment());
            case AUTOLOAD -> gateway.setAutoLoad(world, plan.autoLoad());
        };
        if (error != null) {
            return outcome(plan, Result.FAILED, "Multiverse-Core n'a pas pu " + verb.toLowerCase(java.util.Locale.ROOT)
                    + " « " + world + " » : " + error + ".", List.of(), List.of());
        }
        List<String> notes = new ArrayList<>();
        if (plan.operation() != WorldStatePlanner.Operation.AUTOLOAD) {
            Boolean current = gateway.describe(world).map(WorldStateGateway.Reread::autoLoad).orElse(null);
            if (!Objects.equals(current, plan.autoLoad())) {
                String autoError = gateway.setAutoLoad(world, plan.autoLoad());
                if (autoError != null) {
                    notes.add("« Charger au démarrage » n'a pas pu être réglé : " + autoError + ".");
                }
            }
        }
        Optional<WorldStateGateway.Reread> reread = gateway.describe(world);
        if (reread.isEmpty()) {
            return outcome(plan, Result.MISMATCH, "Multiverse-Core a répondu « fait » mais « " + world
                    + " » est introuvable à la relecture.", List.of(), notes);
        }
        List<Check> checks = checks(plan, reread.get());
        long gaps = checks.stream().filter(c -> !c.ok()).count();
        return gaps == 0
                ? outcome(plan, Result.DONE, done(plan), checks, notes)
                : outcome(plan, Result.MISMATCH, verb + " « " + world + " » : fait, mais " + gaps
                        + " valeur(s) relue(s) diffèrent de la demande — voir le détail.", checks, notes);
    }

    private static String done(WorldStatePlanner.Plan plan) {
        String w = "« " + plan.world() + " »";
        return switch (plan.operation()) {
            case LOAD -> "Monde " + w + " chargé et relu conforme.";
            case UNLOAD -> "Monde " + w + " enregistré puis déchargé ; fichiers conservés, relu conforme.";
            case IMPORT -> "Dossier " + w + " importé dans Multiverse-Core, chargé et relu conforme.";
            case AUTOLOAD -> "« Charger au démarrage » " + (plan.autoLoad() ? "activé" : "désactivé") + " pour " + w + ".";
        };
    }

    /** Relecture comparée à la demande. */
    static List<Check> checks(WorldStatePlanner.Plan plan, WorldStateGateway.Reread r) {
        List<Check> out = new ArrayList<>();
        boolean expectLoaded = switch (plan.operation()) {
            case LOAD, IMPORT -> true;
            case UNLOAD -> false;
            case AUTOLOAD -> r.loaded();
        };
        if (plan.operation() != WorldStatePlanner.Operation.AUTOLOAD) {
            out.add(check("Chargé", String.valueOf(expectLoaded), String.valueOf(r.loaded())));
        }
        out.add(check("Géré par Multiverse-Core", "true", String.valueOf(r.multiverseKnown())));
        if (plan.operation() == WorldStatePlanner.Operation.IMPORT) {
            out.add(check("Dimension", plan.environment(), r.environment()));
        }
        out.add(check("Chargé au démarrage", String.valueOf(plan.autoLoad()),
                r.autoLoad() == null ? null : String.valueOf(r.autoLoad())));
        return out;
    }

    private static Check check(String label, String expected, String actual) {
        return new Check(label, expected, actual == null ? "inconnu" : actual, Objects.equals(expected, actual));
    }

    private static Outcome outcome(WorldStatePlanner.Plan plan, Result result, String message, List<Check> checks,
                                   List<String> notes) {
        return new Outcome(plan.world(), plan.operation(), result, message, false, List.copyOf(checks),
                List.copyOf(notes), plan, List.of());
    }

    private Outcome record(String token, String actor, Outcome o) {
        List<String> lines = new ArrayList<>();
        lines.add("OPÉRATION " + o.operation().name());
        o.checks().forEach(k -> lines.add((k.ok() ? "OK " : "ÉCART ") + k.label() + " : demandé " + k.expected()
                + ", relu " + k.actual()));
        o.notes().forEach(n -> lines.add("NOTE " + n));
        audit.append(new WorldCreationAuditLog.Entry(Instant.now(clock).toString(),
                actor == null || actor.isBlank() ? "inconnu" : actor, token, o.world(), o.result().name(), o.message(),
                lines));
        return o;
    }

    private static Result parse(String raw) {
        try {
            return Result.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Result.FAILED;
        }
    }
}
