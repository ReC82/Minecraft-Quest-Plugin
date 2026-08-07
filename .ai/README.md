# Dossier `.ai`

Ce dossier contient la mémoire opérationnelle utilisée par Claude Code pour reprendre RPGQuest sans gros prompt manuel.

## Fichiers

- `SESSION_START.md` : point d'entrée unique d'une nouvelle session.
- `ROADMAP.md` : état d'avancement courant.
- `CONTEXT.md` : décisions techniques durables.
- `NIGHT_MODE.md` : règles des longues sessions autonomes.
- `PROMPTS/` : cahiers des charges / PDF de la feuille de route.

## Nouvelle session

Prompt minimal :

```text
Lis .ai/SESSION_START.md puis reprends le travail à la première étape réellement incomplète.
```

## Session de nuit

```text
Lis .ai/SESSION_START.md puis .ai/NIGHT_MODE.md et poursuis automatiquement aussi loin que possible.
```

Le dépôt Git et les tests sont toujours prioritaires sur ROADMAP.md.
