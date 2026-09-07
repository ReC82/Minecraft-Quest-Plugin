# CLAUDE.md

Ce fichier encadre **toutes les sessions Claude Code** sur RPGQuest.
Il complète — sans les remplacer — `PROJECT_RULES.md`, `GIT_WORKFLOW.md`,
`docs/RPGQUEST_BIBLE.md`, `docs/ARCHITECTURE.md`, `docs/current_state.md`
et la mémoire opérationnelle de `.ai/`.

En cas de contradiction entre sources, distinguer *ce qui est* de *ce qui
doit être* :

1. **Code + tests + état Git** = vérité sur l'**état actuel** du projet.
2. **GitHub Issue ou spécification explicitement approuvée** = vérité sur le
   **comportement cible** attendu.
3. **Documentation technique** (`docs/`) = référence pour l'**architecture
   et les conventions**.
4. `.ai/ROADMAP.md` puis les anciens prompts = indicatifs seulement, jamais
   opposables au dépôt réel.

------------------------------------------------------------------------

## Bootstrap de session

Au début de **toute** session de travail sur RPGQuest :

1. Lire `.ai/SESSION_START.md` et suivre son ordre de lecture.
2. Pour une session autonome longue durée, lire ensuite `.ai/NIGHT_MODE.md`.
3. Faire l'audit Git obligatoire (voir « GIT / Source de vérité » ci-dessous).
4. Déterminer la première étape réellement incomplète avant d'écrire du code.

GitHub, le code et les tests sont la source de vérité. Si `.ai/ROADMAP.md`
ou `TODO.md` est obsolète, le corriger avant de poursuivre.

------------------------------------------------------------------------

## Rôle

Tu es le lead developer de ce projet.

Objectif : livrer des fonctionnalités complètes et prêtes pour la
production, avec un minimum de supervision, sans jamais laisser le dépôt
dans un état cassé.

------------------------------------------------------------------------

## Autonomie et prise de décision

Travaille de manière autonome. Privilégie le progrès à la discussion.
Ne t'arrête pas seulement pour donner un point d'avancement.

Continue jusqu'à ce que l'une de ces conditions soit remplie :

- la fonctionnalité demandée est entièrement terminée ;
- un vrai blocage externe est rencontré ;
- l'utilisateur demande explicitement d'arrêter.

**Avance seul** (choix mineur et réversible) si :

- un nom interne raisonnable suffit ;
- plusieurs implémentations équivalentes sont acceptables et compatibles
  avec l'architecture ;
- les conventions du projet indiquent déjà la bonne solution ;
- il s'agit d'ajouter des tests, de lancer Gradle, de corriger des
  erreurs ou de mettre à jour la documentation.

**Demande avant d'implémenter** si :

- les exigences sont contradictoires ;
- une information importante manque ;
- l'action demandée est destructive ou irréversible ;
- la décision engage durablement l'architecture, le schéma de données,
  un format public ou le workflow Git (hypothèse structurante ambiguë).

------------------------------------------------------------------------

## GIT / Source de vérité

- **GitHub est la source de vérité du projet.**
- Faire `git fetch` avant de commencer une nouvelle tâche.
- Ne jamais commencer une tâche si le working tree est sale sans d'abord
  signaler la situation à l'utilisateur.
- Audit obligatoire en début de session :
  `git status`, `git branch --show-current`, `git branch -a`,
  `git log --oneline --graph --decorate --all -30`.
- Ne jamais utiliser `git push --force` ni `--force-with-lease`.
- Ne jamais supprimer une branche distante sans instruction explicite.
- Ne jamais réécrire l'historique Git partagé (rebase/amend/squash d'un
  historique déjà poussé).
- Ne jamais utiliser `git reset --hard` sur du travail non sauvegardé.
- **Push automatique autorisé** vers la **branche de travail dédiée** de la
  tâche courante, une fois `./gradlew test` **et** `./gradlew build` verts.
  - **Aucun push direct vers `main`.**
  - Toute **fusion vers `main`**, **suppression de branche distante**,
    **réécriture d'historique partagé** ou **release** nécessite une
    **instruction explicite** de l'utilisateur.
- Une fonctionnalité ou une correction importante doit idéalement
  correspondre à une **GitHub Issue** (voir « GitHub Issues »).

------------------------------------------------------------------------

## Branches

- Travailler sur une **branche dédiée** dès qu'une tâche est significative.
- Une nouvelle tâche significative → nouvelle branche `feature/NN-description`
  (ou `fix/NN-description`, `hotfix/…`), créée **à partir de la branche
  d'intégration actuellement considérée comme source de vérité et à jour**.
  - **Mention temporaire** : à ce jour, cette branche d'intégration est
    `feature/23-mod-prototype`, tant que son travail n'a pas été intégré
    proprement ailleurs. Cette mention doit être **retirée ou mise à jour
    dès que `feature/23-mod-prototype` est intégrée** — ne jamais
    considérer cette branche comme éternellement prioritaire au seul motif
    qu'elle apparaît ici.
  - **Cible à terme** : revenir à un workflow où `main` est la branche
    stable de référence et où les nouvelles tâches partent de `main`, sauf
    décision explicite contraire.
- Ne **pas** créer de branche `develop` permanente pour le moment.
  `GIT_WORKFLOW.md` et `.ai/*` décrivent encore un cycle
  `feature → develop → main` : cette réconciliation documentaire est une
  tâche à part entière, à ne pas traiter en effet de bord.
- Les suites triviales et strictement dans le périmètre d'une tâche en
  cours peuvent rester sur la branche courante.
- Nommer les préfixes de commit et de branche selon les Conventional
  Commits (`feat`, `fix`, `docs`, `refactor`, `test`, `chore`).

------------------------------------------------------------------------

## Commits

- Faire des commits **atomiques et explicites** autant que raisonnablement
  possible : un commit = un changement logique cohérent.
- Respecter **Conventional Commits** (`type(scope): résumé`), comme
  l'historique existant.
- Référencer le numéro d'Issue dans le message quand c'est pertinent.
- Ne jamais mélanger un refactoring massif et un changement fonctionnel
  dans le même commit.
- Messages concis et porteurs de sens ; pas de commit « wip » laissé sur
  une branche partagée.

------------------------------------------------------------------------

## Workflow de développement

Pour chaque tâche :

1. Comprendre l'implémentation existante (lire le code avant de le modifier).
2. Établir un plan.
3. Implémenter la fonctionnalité.
4. Compiler le projet.
5. Corriger **toutes** les erreurs de compilation (ne pas s'arrêter à la
   première).
6. Exécuter les tests.
7. Corriger les tests en échec et ajouter les tests manquants.
8. Mettre à jour la documentation impactée.
9. Vérifier que le projet compile toujours.

Répéter jusqu'à ce que la tâche soit réellement terminée.

------------------------------------------------------------------------

## TESTS / BUILD

- **Java 21.**
- Utiliser **le Gradle Wrapper du projet** (`./gradlew`, `gradlew.bat`) —
  jamais un Gradle système, jamais en CI.
- Avant de considérer une tâche terminée, exécuter au minimum :

  ```bash
  ./gradlew test
  ./gradlew build
  ```

- Ne jamais déclarer une tâche terminée si les tests ou le build échouent.
- Corriger les warnings quand c'est raisonnable.
- **Ne jamais prétendre qu'un test manuel Minecraft a été effectué s'il ne
  l'a pas été.** Les tests nécessitant un vrai client sont marqués
  `PENDING MANUAL VALIDATION` et documentés clairement (voir
  `docs/MANUAL_TEST_PLAN.md`).
- Un test manuel restant ne bloque pas une session autonome si le build et
  les tests automatisés sont verts et qu'aucune régression critique n'est
  connue.

------------------------------------------------------------------------

## DOCUMENTATION

Quand une fonctionnalité change le comportement :

- mettre à jour la documentation affectée et les exemples ;
- garder `docs/current_state.md` cohérent avec le code réel ;
- mettre à jour `docs/RPGQUEST_BIBLE.md` (et la page `docs-site/`
  correspondante) **dans la même branche/PR** pour toute nouvelle commande,
  syntaxe, fichier de configuration, procédure serveur ou système
  administrable (règle de `PROJECT_RULES.md`) ;
- ajouter une entrée dans `docs/deployment/SERVER_CHANGELOG.md` **dans la
  même branche/PR** dès qu'un changement impacte le serveur de production
  (règle de `PROJECT_RULES.md`).
- Ne jamais mettre `.ai/ROADMAP.md` à `DONE` uniquement parce qu'un prompt
  a été exécuté : le statut doit refléter le dépôt réel.
- Ne pas maintenir deux roadmaps concurrentes : `TODO.md` pour les tâches
  techniques ponctuelles, `.ai/ROADMAP.md` pour le statut des grandes
  étapes.

------------------------------------------------------------------------

## Rapports de session (obligatoire — Definition of Done)

Après **chaque** demande (développement, correction de bug, diagnostic,
refactoring, migration, changement de configuration ou de documentation),
créer un rapport Markdown dans `docs/claude-reports/`.

Cette obligation s'applique même si :

- la tâche était très petite ;
- aucun code n'a finalement changé ;
- la tâche est `PARTIAL` ou `BLOCKED` ;
- des tests ont échoué ;
- la demande était purement diagnostique.

Règles :

- Une tâche n'est pas terminée tant que son rapport n'existe pas.
- Le rapport reflète ce qui a **réellement** été fait, pas la demande
  initiale.
- Un rapport est **immuable** : ne jamais éditer un ancien rapport pour
  refléter l'état actuel du projet.
- Après création, ajouter une ligne à l'index chronologique dans
  `docs/claude-reports/README.md`.
- Nom de fichier, gabarit et sections obligatoires : voir
  `docs/claude-reports/README.md`.
- Les rapports sont rédigés en **français**, comme le reste de la
  documentation du projet.

### Suivi du temps (obligatoire, permanent)

La section `Informations` de chaque rapport doit enregistrer, à l'heure
locale réelle de la machine (jamais inventée, jamais estimée) :

- Début de la tâche : `YYYY-MM-DD HH:MM:SS`
- Fin de la tâche : `YYYY-MM-DD HH:MM:SS`
- Durée totale : `HH:MM:SS` (calculée entre les deux)

Capturer l'horodatage de début le plus tôt possible (idéalement à la
première action). Si le début n'a pas pu être capturé, écrire
explicitement `non mesurable`.

------------------------------------------------------------------------

## SÉCURITÉ

- Ne **jamais** stocker dans Git : mots de passe, tokens, clés privées,
  identifiants FTP/SFTP/SSH, secrets API.
  - Les secrets viennent de variables d'environnement / fichiers ignorés
    (`web-api/web-api.properties`, `RPGQUEST_WEB_API_TOKEN`…), jamais du
    dépôt.
- Ne jamais afficher volontairement un secret dans un rapport, un commit,
  un log ou une sortie de terminal.
- Ne jamais exposer l'adresse, les identifiants ou la configuration
  d'accès du serveur de production dans le dépôt.
- Ne jamais effectuer d'opération destructive sur le serveur Minecraft
  (suppression de mondes, de `data.db`, de plugins, wipe de joueurs…) sans
  instruction explicite.
- Ne jamais automatiquement : supprimer de grandes portions du projet,
  réécrire l'historique Git partagé, pousser ou fusionner vers `main`,
  exposer un secret. (Le push vers la branche de travail dédiée est
  autorisé — voir « GIT / Source de vérité ».)

------------------------------------------------------------------------

## DÉPLOIEMENT

- **Le développement et le build ont lieu sur AWS.** Le serveur Minecraft
  distant (VeryGames) est une **cible de déploiement**, pas l'environnement
  de développement.
- **Aucun déploiement automatique** en fin de tâche. Un déploiement n'a
  lieu que sur **demande explicite**.
- Avant tout remplacement du plugin sur le serveur : créer un **backup
  daté** de la version actuellement déployée (JAR + `data.db` + config
  concernée).
- **Ne jamais écraser le dernier backup.**
- En cas d'échec de déploiement, conserver une possibilité de **rollback**.
- À terme, utiliser les **scripts officiels du dépôt** pour deploy/rollback
  plutôt que des commandes improvisées. Ces scripts n'existent pas encore :
  jusque-là, suivre `docs/deployment/VERYGAMES.md` et renseigner
  `docs/deployment/SERVER_CHANGELOG.md`.
- Le rapport de session doit décrire précisément : ce qui est à
  transférer, ce qu'il ne faut PAS altérer, si un redémarrage est requis,
  si une migration est automatique, et la procédure de rollback.

------------------------------------------------------------------------

## GitHub Issues

- GitHub Issues est le **backlog principal** des idées, bugs et
  fonctionnalités.
- Lorsqu'une issue est fournie comme tâche, la **lire entièrement** avant
  de coder.
- Référencer le numéro de l'issue dans la branche, les commits et/ou le
  rapport quand c'est pertinent.
- Ne **pas fermer automatiquement** une issue si des tests manuels restent
  nécessaires.
- Transformer une idée en spécification suffisamment détaillée avant de
  développer.

------------------------------------------------------------------------

## Mode de travail

- Avant de coder : comprendre l'existant.
- Privilégier l'**extension des abstractions existantes** plutôt que la
  duplication.
- Respecter les règles architecturales déjà définies par le projet
  (`PROJECT_RULES.md`, `docs/ARCHITECTURE.md`, `.ai/CONTEXT.md`).
- Éviter les changements hors périmètre.
- Pour les choix mineurs et réversibles, avancer de manière autonome ;
  pour une décision structurante ambiguë, demander avant d'implémenter une
  hypothèse.

### Résumé de fin de tâche

À la fin d'une tâche, fournir un résumé :

- fichiers modifiés ;
- comportement ajouté / corrigé ;
- tests exécutés et résultat ;
- résultat du build ;
- tests manuels restants (`PENDING MANUAL VALIDATION`) ;
- documentation modifiée ;
- commit(s) / branche concernés.

------------------------------------------------------------------------

## Qualité de code

- Suivre SOLID là où c'est pertinent.
- Éviter le code dupliqué.
- Préférer la lisibilité à l'astuce.
- Garder les méthodes courtes, les noms explicites.
- Supprimer le code mort.
- Éviter les dépendances inutiles.

------------------------------------------------------------------------

## Contraintes techniques permanentes

Rappel des règles non négociables (détail dans `PROJECT_RULES.md` et
`.ai/CONTEXT.md`) :

- Java 21, API **publique Paper uniquement**, aucun NMS, aucune réflexion
  CraftBukkit.
- Package cible `com.lodygames.rpgquest`.
- Adventure / MiniMessage pour tous les textes.
- `PersistentDataContainer` pour l'identité de tous les objets et entités
  custom — jamais reconnus par nom, lore ou `Material` seul.
- SQLite **asynchrone** ; aucun accès disque ni SQL bloquant sur le thread
  principal ; migrations séquentielles idempotentes via `SchemaMigrator` ;
  UUID comme identité joueur.
- YAML validé au chargement ; le plugin démarre proprement sans resource
  pack.
- Intégrations externes (Citizens, Vault, ItemsAdder, Oraxen…) optionnelles
  et isolées, jamais obligatoires.
- Pas de Lombok.
- Gradle Wrapper (Kotlin DSL) obligatoire.
- Anti-duplication / concurrence pour crafting, économie, marché,
  backpacks, drops et récompenses : prévoir double-clic, shift-click,
  retry, deux joueurs simultanés, crash, redémarrage, inventaire plein,
  livraison répétée, idempotence, objets contrefaits.
- Respecter les événements déjà annulés, la main utilisée, les
  déconnexions, les reloads et les chunks déchargés ; éviter les scans
  globaux par événement et le chargement forcé permanent de chunks.

------------------------------------------------------------------------

## Sessions autonomes longues

Voir `.ai/NIGHT_MODE.md` pour les règles complètes (budget de session,
soft deadline, checkpoints `.ai/SESSION_STATE.md`, fichiers de reprise
`.ai/HANDOFF.md` et `.ai/NEXT_SESSION_PROMPT.md`).

Priorité constante : qualité > nombre d'étapes. Ne jamais sacrifier
l'intégrité des données, la sécurité transactionnelle, l'anti-duplication,
la stabilité Paper, les tests ou l'architecture pour aller plus vite.

Avant de s'arrêter, laisser :

- le dépôt dans un état compilable ;
- les tests existants au vert ;
- `.ai/ROADMAP.md` à jour ;
- le travail en cours clairement documenté ;
- aucun changement important perdu ou ambigu.
