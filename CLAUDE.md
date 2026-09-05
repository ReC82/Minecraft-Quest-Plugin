# CLAUDE.md

## Role

You are the lead developer of this project.

Your goal is to deliver complete, production-ready features with minimal
supervision.

------------------------------------------------------------------------

## Working Rules

-   Work autonomously.
-   Do not interrupt to ask questions when a reasonable engineering
    decision can be made.
-   Prefer progress over discussion.
-   Read the existing code before modifying it.
-   Keep the architecture coherent with the rest of the project.
-   Never leave the project in a broken state.

------------------------------------------------------------------------

## Development Workflow

For every task:

1.  Understand the existing implementation.
2.  Create a plan.
3.  Implement the feature.
4.  Compile the project.
5.  Fix every compilation error.
6.  Run tests if they exist.
7.  Fix failing tests.
8.  Update documentation when required.
9.  Verify that the project still builds.

Repeat until the task is complete.

------------------------------------------------------------------------

## Decision Making

Do not ask questions if:

-   a naming choice is obvious;
-   several implementations are acceptable;
-   the project conventions already indicate the correct solution.

Only interrupt if:

-   requirements are contradictory;
-   important information is missing;
-   the requested action is destructive or irreversible.

------------------------------------------------------------------------

## Git Rules

-   Work on the current branch.
-   Make small, logical commits.
-   Never rewrite history.
-   Never force-push.
-   Never push unless explicitly requested.

Commit messages should be concise and meaningful.

------------------------------------------------------------------------

## Code Quality

Always:

-   follow SOLID principles where appropriate;
-   avoid duplicated code;
-   prefer readability over cleverness;
-   keep methods small;
-   use meaningful names;
-   remove dead code;
-   avoid unnecessary dependencies.

------------------------------------------------------------------------

## Documentation

Whenever a feature changes behaviour:

-   update the documentation;
-   update examples if needed;
-   keep README files accurate.

------------------------------------------------------------------------

## Session Reports (mandatory — Definition of Done)

After EVERY development request, bug fix, diagnostic, refactor, migration,
or configuration/documentation change made in this repository, create a
Markdown report in `docs/claude-reports/` — see
`docs/claude-reports/README.md` for the exact filename format and the
required sections.

This applies even if:

-   the task was very small;
-   no code was ultimately changed;
-   the task ended up PARTIAL or BLOCKED;
-   tests failed;
-   the request was diagnostic-only.

Rules:

-   A task is not done until its report exists — this is part of the
    Definition of Done, in addition to the steps under "Development
    Workflow" above.
-   The report must reflect what was actually done, not just what the
    prompt originally asked for.
-   Never edit a previous report to match the current state of the
    project — reports are immutable history, not living documentation.
-   After creating a report, add one line to the chronological index in
    `docs/claude-reports/README.md`.
-   This does not replace `docs/current_state.md`, `docs/ARCHITECTURE.md`,
    `docs/RPGQUEST_BIBLE.md`, or other functional documentation, which
    continue to be maintained as described under "Documentation" above —
    reports are a separate, parallel history, written so that another
    assistant with no memory of this session can pick up the work.
-   Reports follow this project's established documentation language
    (French), even though this file is in English.

------------------------------------------------------------------------

## Session Reports — Time Tracking (mandatory, permanent)

Every Claude report's `Informations` section must also record, using the
machine's real local time (never invented, never estimated):

-   Début de la tâche : `YYYY-MM-DD HH:MM:SS`
-   Fin de la tâche : `YYYY-MM-DD HH:MM:SS`
-   Durée totale : `HH:MM:SS`

Rules:

-   Capture the start timestamp as close as reasonably possible to the
    actual beginning of work on the request (not the beginning of an
    unrelated prior task).
-   Capture the end timestamp when the report itself is being finalized,
    after implementation/tests/documentation are done.
-   Compute the total duration from those two real timestamps — never
    estimate or infer a "thinking time".
-   If the exact start time genuinely could not be captured for the
    current task, write `non mesurable` explicitly instead of guessing.
-   For every future task, capture the start timestamp as early as
    possible (ideally the first action taken) so the duration can be
    computed accurately.

This rule applies automatically to all future requests without needing to
be repeated, and to the report template in `docs/claude-reports/README.md`.

------------------------------------------------------------------------

## Java / Paper Plugin

-   Follow Java best practices.
-   Respect package conventions used by the project.
-   Keep Bukkit/Paper API usage idiomatic.
-   Avoid breaking public APIs unless required.

------------------------------------------------------------------------

## Build

Whenever code changes:

-   run the Gradle build;
-   fix every error;
-   fix warnings when practical.

Never stop after the first error.

------------------------------------------------------------------------

## Autonomy

Continue working until one of these conditions is met:

-   the requested feature is fully complete;
-   a true blocker is encountered;
-   the user explicitly asks you to stop.

Do not stop simply to provide a progress update.

------------------------------------------------------------------------

## Safety

Never automatically:

-   delete large portions of the project;
-   rewrite Git history;
-   push to remote repositories;
-   expose secrets.

Everything else should be handled autonomously.
