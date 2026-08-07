# RPGQuest — Session Start

Ce fichier est le point d'entrée de toute nouvelle session Claude Code.

## 1. Lire les sources dans cet ordre

Avant toute modification, lire intégralement :

1. `/CLAUDE.md`
2. `/PROJECT_RULES.md`
3. `/.ai/ROADMAP.md`
4. `/.ai/CONTEXT.md`
5. `/docs/ARCHITECTURE.md` si présent
6. `/GIT_WORKFLOW.md` ou `/docs/GIT_WORKFLOW.md`
7. `/TODO.md`
8. `/README.md`
9. Les documents de `/.ai/PROMPTS/` uniquement pour vérifier le cahier des charges détaillé d'une étape.

## 2. Le dépôt est la source de vérité

Ne jamais faire confiance aveuglément à ROADMAP.md ou TODO.md.

Au début de chaque session, exécuter au minimum :

```bash
git status
git branch --show-current
git branch -a
git log --oneline --graph --decorate --all -30
```

Puis inspecter le code, les tests et les ressources correspondant à l'étape indiquée comme en cours.

En cas de contradiction :

1. code + tests + Git ;
2. documentation technique actuelle ;
3. ROADMAP.md ;
4. anciens prompts PDF.

Le dépôt réel gagne toujours.

## 3. Déterminer automatiquement où reprendre

Identifier :

- la dernière étape réellement terminée ;
- la première étape incomplète ;
- la branche actuellement active ;
- les changements non commités ;
- le dernier build/test connu.

Mettre à jour `/.ai/ROADMAP.md` si son état ne correspond plus au dépôt.

Ne jamais recommencer une fonctionnalité déjà correcte.

## 4. Règles de travail

Pour chaque étape :

1. auditer l'existant ;
2. compléter uniquement ce qui manque ;
3. compiler régulièrement ;
4. exécuter les tests ;
5. corriger toutes les erreurs ;
6. ajouter les tests manquants ;
7. mettre à jour la documentation ;
8. mettre à jour ROADMAP.md ;
9. faire un commit logique lorsque l'étape est réellement validée ;
10. intégrer dans `develop` uniquement si le workflow du projet le prévoit et si l'étape est validée.

Ne jamais pousser sur le remote sans instruction explicite.

## 5. Tests manuels

Ne jamais prétendre qu'un test manuel Minecraft a été effectué s'il ne l'a pas été.

Les tests nécessitant un vrai joueur doivent être marqués :

`PENDING MANUAL VALIDATION`

Ils ne doivent pas bloquer une longue session autonome si :

- le build est vert ;
- les tests automatisés sont verts ;
- aucune régression critique connue n'existe.

## 6. Mode normal / mode nuit

Session normale :
- reprendre la première étape réellement incomplète ;
- travailler jusqu'à sa validation ou jusqu'à un blocage réel.

Session longue :
- lire aussi `/.ai/NIGHT_MODE.md` ;
- enchaîner automatiquement les étapes validées tant que le quota et l'environnement le permettent.

## 7. Avant de s'arrêter

Toujours essayer de laisser :

- le dépôt dans un état compilable ;
- les tests existants au vert ;
- ROADMAP.md à jour ;
- le travail en cours clairement documenté ;
- aucun changement important perdu ou ambigu.

Si une interruption de quota est imminente, privilégier la sécurisation de l'état du dépôt plutôt qu'une nouvelle fonctionnalité.
