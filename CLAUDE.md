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
