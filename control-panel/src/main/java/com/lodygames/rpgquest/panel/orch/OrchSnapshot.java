package com.lodygames.rpgquest.panel.orch;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Instantané de la file du superviseur {@code lqorch} (issue #907), tel que publié par
 * {@code lqorch.py} ({@code /var/lib/lodyquests-orch/queue.json}). Lecture seule : le panel ne
 * modifie jamais la file et n'interroge jamais GitHub — tout ce qui est affiché vient de ce fichier.
 *
 * <p>Lecture tolérante : un champ absent ou d'un autre type devient une valeur vide, jamais une
 * exception — un superviseur plus ancien ou plus récent ne doit pas casser la page.</p>
 */
public record OrchSnapshot(
        int version,
        Instant generatedAt,
        String repo,
        Supervisor supervisor,
        Optional<Current> current,
        AutoExam auto,
        List<Ticket> tickets,
        List<SessionRun> sessions) {

    /** Version de format comprise par cette page ; une version supérieure est signalée, pas refusée. */
    public static final int SUPPORTED_VERSION = 1;

    public OrchSnapshot {
        tickets = List.copyOf(tickets);
        sessions = List.copyOf(sessions);
    }

    public record Supervisor(String mode, boolean alive, String code, Instant startedAt, String idleReason,
                             boolean reloadRequested, boolean nextStartAllowed, String nextStartReason,
                             Instant nextStartAt, int sessionsToday, int maxSessionsPerDay) {
    }

    public record Current(int issue, String title, Instant startedAt, Instant lastActivity) {
    }

    /** Dernier examen de la file automatique {@code agent:auto} (#836) — tickets ouverts sur GitHub. */
    public record AutoExam(boolean enabled, Instant examinedAt, List<AutoEntry> eligible, List<AutoEntry> excluded) {
        public AutoExam {
            eligible = List.copyOf(eligible);
            excluded = List.copyOf(excluded);
        }

        /** Rang P0–P3 du ticket s'il figure dans le dernier examen avec un label de priorité unique. */
        public Optional<Integer> rankOf(int issue) {
            return java.util.stream.Stream.concat(eligible.stream(), excluded.stream())
                    .filter(e -> e.issue() == issue && e.rank().isPresent())
                    .map(e -> e.rank().get()).findFirst();
        }

        public Optional<AutoEntry> entryOf(int issue) {
            return java.util.stream.Stream.concat(eligible.stream(), excluded.stream())
                    .filter(e -> e.issue() == issue).findFirst();
        }
    }

    /** Ticket du dernier examen : admissible ({@code reason} vide) ou écarté avec son motif. */
    public record AutoEntry(int issue, String title, Optional<Integer> rank, String reason, String rearm,
                            boolean eligible) {
    }

    public record Ticket(int issue, String title, String status, int priority, String deps, String branch,
                         String pr, String commitSha, String summary, String tests, String blockers,
                         String nextAction, String note, int attempts, String lastSession,
                         Instant lastActivity, Instant notBefore, Instant updatedAt, boolean autoHold,
                         List<String> reports) {
        public Ticket {
            reports = List.copyOf(reports);
        }
    }

    public record SessionRun(String id, int issue, String title, Instant startedAt, Instant endedAt,
                             String outcome, String detail) {
    }

    // ---- Lecture du JSON ----------------------------------------------------------------------

    /** Construit l'instantané depuis l'objet JSON racine ; lève {@link IllegalArgumentException} sans {@code generated_at}. */
    public static OrchSnapshot fromJson(Map<String, Object> root) {
        Instant generated = instant(root.get("generated_at"));
        if (generated == null) {
            throw new IllegalArgumentException("champ generated_at absent ou illisible");
        }
        Map<String, Object> sup = map(root.get("supervisor"));
        Map<String, Object> next = map(sup.get("next_start"));
        Supervisor supervisor = new Supervisor(str(sup.get("mode")), bool(sup.get("alive")), str(sup.get("code")),
                instant(sup.get("started_at")), str(sup.get("idle_reason")), bool(sup.get("reload_requested")),
                bool(next.get("allowed")), str(next.get("reason")), instant(next.get("until")),
                integer(sup.get("sessions_today")), integer(sup.get("max_sessions_per_day")));
        Map<String, Object> cur = map(root.get("current"));
        Optional<Current> current = cur.isEmpty() ? Optional.empty() : Optional.of(new Current(
                integer(cur.get("issue")), str(cur.get("title")), instant(cur.get("started_at")),
                instant(cur.get("last_activity"))));
        Map<String, Object> auto = map(root.get("auto"));
        AutoExam exam = new AutoExam(bool(auto.get("enabled")), instant(auto.get("examined_at")),
                entries(auto.get("eligible"), true), entries(auto.get("excluded"), false));
        List<Ticket> tickets = new ArrayList<>();
        for (Map<String, Object> t : maps(root.get("tasks"))) {
            tickets.add(new Ticket(integer(t.get("issue")), str(t.get("title")), str(t.get("status")),
                    integer(t.get("priority")), str(t.get("deps")), str(t.get("branch")), str(t.get("pr")),
                    str(t.get("commit_sha")), str(t.get("summary")), str(t.get("tests")), str(t.get("blockers")),
                    str(t.get("next_action")), str(t.get("note")), integer(t.get("attempts")),
                    str(t.get("last_session")), instant(t.get("last_activity")), instant(t.get("not_before")),
                    instant(t.get("updated_at")), bool(t.get("auto_hold")), strings(t.get("reports"))));
        }
        List<SessionRun> sessions = new ArrayList<>();
        for (Map<String, Object> s : maps(root.get("sessions"))) {
            sessions.add(new SessionRun(str(s.get("id")), integer(s.get("issue")), str(s.get("title")),
                    instant(s.get("started_at")), instant(s.get("ended_at")), str(s.get("outcome")),
                    str(s.get("detail"))));
        }
        return new OrchSnapshot(integer(root.get("version")), generated, str(root.get("repo")), supervisor,
                current, exam, tickets, sessions);
    }

    private static List<AutoEntry> entries(Object raw, boolean eligible) {
        List<AutoEntry> out = new ArrayList<>();
        for (Map<String, Object> e : maps(raw)) {
            Object rank = e.get("rank");
            out.add(new AutoEntry(integer(e.get("issue")), str(e.get("title")),
                    rank instanceof Number n ? Optional.of(n.intValue()) : Optional.empty(),
                    str(e.get("reason")), str(e.get("rearm")), eligible));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static String str(Object raw) {
        return raw instanceof String s ? s : "";
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean b && b;
    }

    private static int integer(Object raw) {
        return raw instanceof Number n ? n.intValue() : 0;
    }

    /** Horodatage ISO-8601 UTC du superviseur ({@code 2026-10-29T19:28:57Z}) ; {@code null} si vide ou illisible. */
    static Instant instant(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
