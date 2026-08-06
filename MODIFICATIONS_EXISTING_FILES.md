# Modifications à appliquer aux fichiers existants

Ne pas écraser aveuglément les fichiers existants. Fusionner les sections suivantes.

## `CLAUDE.md` — ajouter près du début

```md
## Session bootstrap

Au début de toute session de travail sur RPGQuest, lire `.ai/SESSION_START.md`.

Pour une session autonome longue durée, lire ensuite `.ai/NIGHT_MODE.md`.

Le dépôt Git, le code et les tests sont la source de vérité. Si `.ai/ROADMAP.md` est obsolète, le corriger avant de poursuivre.
```

Conserver toutes les règles autonomes et spécifiques déjà présentes dans le fichier.

## `README.md` — ajouter une section Développement avec Claude Code

```md
## Développement avec Claude Code

La mémoire opérationnelle du projet se trouve dans `.ai/`.

Pour démarrer une session :

`Lis .ai/SESSION_START.md puis reprends le travail à la première étape réellement incomplète.`

Pour une session longue :

`Lis .ai/SESSION_START.md puis .ai/NIGHT_MODE.md et poursuis automatiquement aussi loin que possible.`

L'avancement est résumé dans `.ai/ROADMAP.md`, mais Git, le code et les tests restent la source de vérité.
```

## `TODO.md`

Ne pas supprimer TODO.md.

Utiliser :
- `TODO.md` pour les tâches techniques ponctuelles ;
- `.ai/ROADMAP.md` pour le statut des grandes étapes de la feuille de route.

Éviter de maintenir deux roadmaps concurrentes.

## `PROJECT_RULES.md`

Vérifier qu'il contient explicitement :

- package cible `com.lodygames.rpgquest` ;
- Java 21 ;
- Paper API publique ;
- pas de NMS / réflexion CraftBukkit ;
- MiniMessage / Adventure ;
- PDC pour custom items/entities ;
- SQLite asynchrone ;
- aucune I/O bloquante sur le main thread ;
- Git sans force-push ni push automatique.

Ne pas dupliquer une règle déjà présente.
