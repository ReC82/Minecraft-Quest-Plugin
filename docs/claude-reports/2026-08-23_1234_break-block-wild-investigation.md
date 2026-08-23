# RPGQuest — Rapport Claude

## Informations
* Date : 2026-08-23
* Heure : 12:34
* Sujet : Investigation d'un bug confirmé sur VeryGames — compteur `BREAK_BLOCK` de `story_test` (`test_break_block`) qui ne progresse jamais dans le monde `wild`
* Statut : **BLOCKED** — cause exacte non confirmée avec certitude après investigation ; aucun correctif de code appliqué (voir « Limitations / travail restant »)
* Branche Git : `feature/23-mod-prototype`
* Commit actuel si disponible : `77ff6fc` (HEAD) — le travail de cette session, comme celui des deux sessions précédentes non commitées, reste dans l'arbre de travail

## Demande

Le message initial de l'utilisateur décrivant le bug a été **tronqué à l'envoi** (« - story_test e son interaction ; » coupait visiblement une phrase plus longue). Les contraintes reçues, elles, étaient complètes :

- Corriger un bug confirmé sur VeryGames impliquant `story_test` et le monde `wild`.
- Ne pas refactoriser autre chose.
- **Garder les quêtes `BREAK_BLOCK` globales entre mondes**, comme actuellement conçu et documenté
  (`docs/RPGQUEST_BIBLE.md`, section 3 : « Global — aucun champ `world` ; compte dans n'importe
  quel monde »).
- Ajouter un test reproduisant exactement : quête `ACTIVE` + objectif `BREAK_BLOCK DIRT 3` +
  `BlockBreakEvent` dans `wild` ⇒ compteur `1/3` puis `3/3`.
- `./gradlew clean build`, doc concernée si nécessaire, rapport Claude obligatoire, m'arrêter après
  ce correctif.

N'ayant pas la description exacte du symptôme, j'ai posé trois questions de clarification
successives (voir « Analyse ») avant d'écrire quoi que ce soit de définitif, conformément à la règle
« interrompre uniquement si une information importante manque ».

## Analyse

**Étape 1 — reproduction directe.** Plutôt que de deviner, j'ai d'abord écrit et exécuté *exactement*
le test demandé par la mission : une quête de test avec un objectif `BREAK_BLOCK` (matériau `DIRT`,
quantité 3) acceptée pour un joueur, puis un vrai `BlockBreakEvent` construit sur un second monde
nommé `wild` (jamais le monde par défaut du serveur de test) et diffusé via
`server.getPluginManager().callEvent(...)` — donc en passant réellement par le `QuestBlockBreakListener`
enregistré par `QuestProgressEngine#start()`, pas par un appel direct à une méthode interne.

**Résultat : ce test passe sans modification du code.** Le compteur progresse bien `1/3` puis `3/3`,
et la quête se termine (`COMPLETED`) — exactement le comportement attendu. Ceci confirme par le code
(`QuestObjectiveIndex`, `QuestProgressEngine#handleCandidates`) qu'aucun filtre par monde n'existe
sur `BREAK_BLOCK`, ni dans l'index ni dans la logique de progression — le mécanisme central est
correct et déjà couvert par un test qui l'exercerait s'il régressait un jour.

**Étape 2 — clarification du symptôme réel.** Trois questions ont permis d'établir :
1. Le symptôme exact : **« le compteur ne bouge jamais dans `wild` »** (jamais `1/3`), alors que la
   même action fonctionne dans le Hub/`world`.
2. **Aucun Claim** (terrain protégé joueur, `claim.ClaimProtectionListener`) connu à l'endroit testé
   — hypothèse écartée par l'utilisateur.
3. **Zones protégées admin** (`/rpgadmin zone`, système distinct des Claims — `zone.ZoneProtectionListener`,
   qui annule `BlockBreakEvent` par défaut dans toute zone où `allow-block-break: false`, valeur par
   défaut d'une zone fraîchement créée) — **non vérifié** par l'utilisateur à ce stade.

**Hypothèses examinées et leur statut :**

| Piste | Statut | Preuve |
|---|---|---|
| Filtre par monde dans `QuestObjectiveIndex`/`QuestProgressEngine` | **Écartée** | Lecture complète du code (aucune notion de monde dans `breakBlock(Material)`/`handleCandidates`) + test de reproduction qui passe. |
| Claim joueur (`ClaimProtectionListener`) annulant l'événement | **Écartée** | Confirmé par l'utilisateur : aucun claim connu à l'endroit testé. |
| Zone protégée admin (`ZoneProtectionListener`) annulant l'événement | **Non écartée, la plus probable restante** | Comportement du code confirmé (une zone bloque `BlockBreakEvent` par défaut) ; présence effective d'une telle zone à l'endroit testé non vérifiée. Même famille de cause que le bug `hub_to_claims` déjà rencontré (zone/portail mal dimensionné débordant sur une zone voisine). |
| `ResourceNodeBreakListener`/`MiningXpListener` interférant | **Très improbable** | Lecture complète : ni l'un ni l'autre n'annule l'événement pour un bloc `DIRT` ordinaire (le premier ne réagit qu'aux blocs enregistrés comme nœuds de ressource, le second n'annule jamais l'événement). |
| Cache `activeByPlayer` vide au moment du cassage (course asynchrone après connexion/démarrage de Story) | **Non exclue mais peu probable comme cause systématique** | Plausible seulement dans une fenêtre de quelques ticks après connexion/démarrage — n'expliquerait pas un échec *systématique et spécifique à `wild`*, seulement un échec ponctuel et aléatoire indépendant du monde. |

Aucune de ces vérifications n'a nécessité, ni justifié, de modifier `QuestProgressEngine`,
`QuestObjectiveIndex`, `QuestBlockBreakListener`, `ZoneProtectionListener` ou `ClaimProtectionListener`
— consigne respectée de ne jamais rendre `BREAK_BLOCK` spécifique à un monde.

## Travail effectué

- Ajout d'un test de non-régression reproduisant exactement le scénario demandé par la mission
  (voir « Tests automatiques »), qui **documente et garantit** le comportement global de `BREAK_BLOCK`
  entre mondes — utile indépendamment de l'issue de cette investigation.
- Aucune modification de code de production : aucune cause de code confirmée à corriger à ce stade.

## Fichiers créés

- `docs/claude-reports/2026-08-23_1234_break-block-wild-investigation.md` (ce rapport)

## Fichiers modifiés

- `src/test/java/com/lodygames/rpgquest/quest/progress/QuestProgressEngineTest.java` — nouveau test
  `breakBlockObjectiveProgressesFromABlockBreakEventFiredInAnyNamedWorldIncludingWild` + helper
  `writeBreakBlockQuest`/`breakDirtInWild` + constante `BREAK_QUEST` + imports (`Material`, `World`,
  `Block`, `BlockBreakEvent`).

## Base de données / migrations

Aucune modification.

## Configuration / données

Aucune. Aucun document fonctionnel mis à jour (`storylines.md`, `RPGQUEST_BIBLE.md`, etc.) : aucun
comportement n'a changé, il n'y avait donc rien à documenter de nouveau — seule la présente
investigation est nouvelle, et elle vit dans ce rapport.

## Tests automatiques

- Commande exécutée : `./gradlew clean build` (jamais `-x test`).
- Résultat : `BUILD SUCCESSFUL in 2m 26s`.
- Nouveau test ajouté : 1 (`breakBlockObjectiveProgressesFromABlockBreakEventFiredInAnyNamedWorldIncludingWild`),
  passe sans modification de code de production.
- Nombre total (dernier build complet, module principal) : **781 tests, 0 échec, 11 ignorés**
  (`skipped`, préexistants, sans rapport avec cette investigation).

## Tests manuels à effectuer

**Sur VeryGames, avant tout correctif de code** (la cause n'étant pas confirmée, un test de code ne
suffit pas à valider une correction qui n'existe pas encore) :

1. Se placer exactement à l'endroit où le test `story_test`/`test_break_block` a échoué dans `wild`.
2. `/rpgadmin zone list` — noter tous les id de zone.
3. Pour chaque zone listée : `/rpgadmin zone info <id>` — comparer ses bornes (`minX/maxX`,
   `minY/maxY`, `minZ/maxZ`, et surtout son **monde**) avec la position exacte testée.
4. Si une zone couvre ce point avec `allow-block-break: false` (défaut) : **cause confirmée**,
   c'est une zone mal dimensionnée débordant sur `wild` — même famille que `hub_to_claims`.
5. Si aucune zone ne couvre ce point : reproduire le test en présence d'un administrateur qui
   observe la console serveur en direct (recherche de toute ligne d'avertissement/erreur au moment
   du cassage), et noter précisément : le monde exact (`wild` ou un autre monde nommé différemment
   en production ?), les coordonnées exactes, et si `/quest progress` montre bien la quête comme
   `ACTIVE` **au moment précis** du test (pas juste avant/après).

## Résultat attendu

Si l'hypothèse « zone protégée admin » se confirme : une zone apparaîtra dans `/rpgadmin zone list`
avec des bornes couvrant le point testé dans `wild`. Sinon, revenir avec les informations demandées
à l'étape 5 ci-dessus pour une investigation plus poussée (probablement nécessitera d'ajouter une
instrumentation de log temporaire ciblée, comme cela a déjà été fait pour le bug WorldPortal).

## Reset / retour à l'état initial

Sans objet — aucune donnée modifiée par cette investigation.

## Déploiement VeryGames

**Aucun déploiement recommandé pour cette étape** — aucun correctif de code n'a été produit. Voir
« Tests manuels à effectuer » pour la procédure de diagnostic à exécuter *sur le serveur existant*,
sans transfert de fichier.

### À transférer
Rien.

### Ne PAS transférer/altérer
Rien de concerné par cette investigation. Ne pas retirer le JAR actuellement en production.

### Redémarrage requis
Non.

### Migration automatique
Non applicable.

## Rollback

Sans objet — aucun changement de production.

## Logs / diagnostic

- Aucun nouveau préfixe de log ajouté par cette session.
- Si l'hypothèse « zone » ne se confirme pas à l'étape 4 du test manuel ci-dessus, la prochaine
  étape technique probable serait d'ajouter un log `DEBUG`/`INFO` temporaire dans
  `QuestProgressEngine#handleCandidates` (ex. « BREAK_BLOCK <material> reçu pour <joueur>, monde
  <world>, playerActive=<vide/rempli>, quêtes candidates=<liste> ») pour observer directement, sur
  le serveur réel, si l'événement atteint bien ce point avec un cache actif correct — mais **pas
  fait ici**, en attente de la confirmation/infirmation de la piste « zone ».

## Documentation mise à jour

Aucune — aucun comportement de code n'a changé.

## Limitations / travail restant

- **La cause exacte n'est pas confirmée.** Cette session a écarté deux hypothèses concrètes (bug de
  code dans le moteur de quête ; Claim joueur) et laisse ouverte la plus probable des pistes
  restantes (zone protégée admin), non vérifiable depuis cet environnement de développement (aucun
  accès au serveur VeryGames réel, ni à ses fichiers `plugins/RPGQuest/zones/*.yml`).
- Aucun correctif de code n'a donc été appliqué — appliquer un correctif sans cause confirmée aurait
  risqué de modifier du code sans rapport avec le vrai problème, à l'encontre de la consigne
  explicite de ne pas refactoriser autre chose.
- Le test ajouté reste une preuve solide que **le moteur de quête** n'est pas en cause, mais ne peut
  pas, par nature (test unitaire isolé, sans Zones/Claims enregistrées), confirmer ou infirmer une
  interférence d'un autre système de protection.

## Prochaine étape suggérée

Exécuter la procédure de diagnostic ci-dessus sur VeryGames (`/rpgadmin zone list`/`info` à
l'endroit exact du test) et revenir avec le résultat — si une zone est en cause, le correctif sera
probablement une simple redéfinition de zone (comme pour `hub_to_claims`), sans changement de code.
Si aucune zone n'est en cause, revenir avec les informations précises demandées à l'étape 5 pour
permettre une instrumentation de log ciblée. Rien commencé automatiquement au-delà de cette
investigation.
