# RPGQuest — Night Mode

Ce mode est destiné aux sessions Claude Code longues et autonomes.

## Objectif

Travailler aussi longtemps que possible sans intervention humaine, tout en laissant le dépôt récupérable à n'importe quel moment.

## Démarrage

Lire d'abord :

1. `/.ai/SESSION_START.md`
2. `/.ai/ROADMAP.md`
3. `/.ai/CONTEXT.md`
4. les règles et documents référencés par SESSION_START.md.

Faire ensuite l'audit Git obligatoire.

## Boucle de travail

Pour chaque étape :

1. confirmer la première étape réellement incomplète ;
2. auditer le code déjà présent ;
3. compléter ce qui manque ;
4. lancer les tests concernés ;
5. lancer le build ;
6. corriger jusqu'à réussite ;
7. documenter ;
8. mettre ROADMAP.md à jour ;
9. commit si l'étape est validée ;
10. intégrer dans `develop` conformément au workflow ;
11. vérifier `develop` ;
12. créer la branche suivante ;
13. continuer automatiquement.

## Ne pas interrompre l'utilisateur pour

- choix de nom interne raisonnable ;
- organisation de classes cohérente ;
- ajout de tests ;
- lancement de Gradle ;
- correction d'erreurs ;
- choix entre deux implémentations équivalentes compatibles avec l'architecture ;
- mise à jour de documentation ;
- passage à l'étape suivante après validation.

## Quota / interruption

Le quota peut être atteint pendant la nuit.

Le système doit donc être résilient.

Après chaque jalon significatif :

- maintenir le build aussi proche que possible d'un état vert ;
- sauvegarder le travail dans Git lorsqu'un commit cohérent est possible ;
- mettre ROADMAP.md à jour ;
- documenter clairement ce qui reste.

Si une interruption semble proche :

1. ne commence pas un gros refactoring ;
2. termine l'unité de travail courante ;
3. lance les tests pertinents ;
4. corrige les erreurs critiques ;
5. mets ROADMAP.md à jour ;
6. commit si l'état constitue un jalon cohérent ;
7. laisse une section `RESUME HERE` dans ROADMAP.md.

## Tests manuels

Les tests nécessitant un client Minecraft réel doivent être marqués :

`PENDING MANUAL VALIDATION`

Ne jamais inventer leur réussite.

Si seuls ces tests restent, continuer à l'étape suivante si l'automatisation est saine.

## Priorité

Qualité > quantité d'étapes.

Ne jamais sacrifier :

- intégrité des données ;
- sécurité transactionnelle ;
- anti-duplication ;
- stabilité Paper ;
- tests ;
- architecture ;

pour avancer plus vite.

## Arrêt autorisé uniquement si

- blocage externe réel ;
- action destructive irréversible nécessaire ;
- secret/compte externe indispensable ;
- quota ou environnement empêchant matériellement de continuer ;
- toutes les étapes disponibles sont terminées.

## Rapport

Ne pas produire un long rapport après chaque sous-tâche.

À la fin de la session, fournir :

- état initial ;
- étapes terminées ;
- commits ;
- merges ;
- build final ;
- tests ;
- tests manuels en attente ;
- blocages ;
- première étape à reprendre.

==================================================
GESTION STRICTE DU BUDGET DE SESSION
==================================================

Cette session possède un budget de travail maximal de 4 heures.

IMPORTANT :
Tu ne dois PAS essayer d'utiliser les 4 heures jusqu'à la dernière minute.

Au début de la session :

1. Exécute une commande système pour connaître l'heure actuelle.
2. Enregistre l'heure de début dans :

.ai/SESSION_STATE.md

3. Calcule une SOFT DEADLINE à :

heure de début + 3 heures 30 minutes

Les 30 dernières minutes sont exclusivement réservées à la sécurisation et à la préparation de la reprise.

Tu ne peux pas connaître précisément le pourcentage de quota Claude restant.
Ne prétends donc jamais connaître le quota exact.
Utilise le temps écoulé comme mécanisme de sécurité.

==================================================
CHECKPOINTS
==================================================

Environ toutes les 30 à 45 minutes, ou après chaque jalon important :

mets à jour :

.ai/SESSION_STATE.md

avec :

- heure actuelle ;
- branche ;
- étape ;
- sous-tâche actuelle ;
- éléments terminés ;
- éléments restants ;
- dernier build ;
- dernier résultat des tests ;
- dernier commit ;
- fichiers principaux modifiés.

Cette mise à jour doit rester courte.

==================================================
À 3H30 : ARRÊT DU NOUVEAU DÉVELOPPEMENT
==================================================

Lorsque la SOFT DEADLINE est atteinte :

NE COMMENCE PLUS :
- nouvelle feature ;
- nouvelle étape ;
- gros refactoring ;
- migration importante.

Passe immédiatement en MODE HANDOFF.

==================================================
MODE HANDOFF
==================================================

1. Termine uniquement la petite unité de travail actuellement en cours si cela peut être fait rapidement.

2. Lance les tests pertinents.

3. Si raisonnable, lance :

gradlew.bat clean build
gradlew.bat test

4. Corrige uniquement les problèmes nécessaires pour laisser le dépôt dans l'état le plus propre possible.

5. Ne merge jamais une étape incomplète dans develop.

6. Si l'étape est complètement validée :
   - commit normal ;
   - mets ROADMAP à jour ;
   - merge selon le workflow habituel.

7. Si l'étape est incomplète :
   - laisse-la sur sa branche feature ;
   - ne prétends pas qu'elle est terminée ;
   - conserve tous les changements ;
   - mets ROADMAP en IN_PROGRESS.

==================================================
FICHIER DE REPRISE
==================================================

Avant de terminer la session, crée ou remplace :

.ai/HANDOFF.md

Il doit contenir exactement :

# RPGQuest — Session Handoff

## Session
Date :
Heure début :
Heure fin :
Branche :

## Étape
Étape :
Statut : DONE / IN_PROGRESS / BLOCKED

## Terminé
- ...

## En cours
- ...

## Reste à faire
1. ...
2. ...
3. ...

## Git
Dernier commit :
Working tree :
Fichiers non commités :

## Validation
Build :
Tests :
Nombre de tests :
Tests manuels en attente :

## Problèmes connus
- ...

## RESUME HERE
Décris précisément la toute première action que la prochaine session doit effectuer.

==================================================
PROMPT DE LA SESSION SUIVANTE
==================================================

Crée également :

.ai/NEXT_SESSION_PROMPT.md

Ce fichier doit contenir un prompt directement utilisable lors de la prochaine fenêtre Claude.

Il doit être autonome et commencer par :

"Reprends la session RPGQuest précédente."

Il doit demander :

1. de lire :
   - CLAUDE.md
   - PROJECT_RULES.md
   - .ai/SESSION_START.md
   - .ai/ROADMAP.md
   - .ai/HANDOFF.md

2. d'auditer Git ;

3. de vérifier que HANDOFF correspond encore au dépôt réel ;

4. de reprendre précisément à RESUME HERE ;

5. de continuer l'étape incomplète avant toute nouvelle étape ;

6. de lancer build et tests ;

7. de travailler à nouveau avec un budget maximal de 4 heures et une SOFT DEADLINE à 3h30 ;

8. de produire à nouveau HANDOFF.md et NEXT_SESSION_PROMPT.md avant la fin.

Le prompt doit contenir suffisamment de contexte pour que je puisse simplement faire :

claude --dangerously-skip-permissions

puis copier-coller le contenu de :

.ai/NEXT_SESSION_PROMPT.md

sans devoir expliquer moi-même où le projet en est.

==================================================
FIN DE SESSION
==================================================

Avant de t'arrêter, vérifie impérativement que :

- ROADMAP.md reflète l'état réel ;
- HANDOFF.md existe et est à jour ;
- NEXT_SESSION_PROMPT.md existe ;
- la branche actuelle est clairement indiquée ;
- aucun travail n'est présenté comme terminé s'il ne l'est pas ;
- le prochain point de reprise est explicite.

La priorité des 30 dernières minutes est la récupérabilité du travail, pas l'ajout de fonctionnalités.