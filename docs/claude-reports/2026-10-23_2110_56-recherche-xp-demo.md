# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-23
* Heure : 21:10
* Sujet : #56 — portail public : recherche de pseudo et XP incohérentes dans l'aperçu fictif (retour propriétaire du 23/10)
* Statut : DONE (livré, déployé, vérifié en public ; TC-414-bis PENDING MANUAL VALIDATION)
* Branche Git : `fix/56-demo-coherence` (PR #802, fusionnée dans `chore/257-ci-github-actions`) ; rapport `docs/56-rapport-recherche-xp`
* Commit actuel si disponible : `a2149a92` (fusion de la PR #802)
* Début de la tâche : 2026-10-23 20:38:27
* Fin de la tâche : 2026-10-23 21:08:23
* Durée totale : 00:29:56

## Demande
Session autonome lqorch sur #56. Deux défauts signalés par le propriétaire sur l'aperçu fictif `/demo/joueurs` :
1. chercher `Brasier` affichait « Aucun profil public pour ce pseudo » alors que `Demo_Brasier` existe ;
2. XP incohérentes : la liste et la fiche donnent 5 200 XP « d'expérience totale » alors que la piste Combat
   seule vaut 6 100 XP ; « Meilleur rang : 1er » semblait contredire les rangs affichés.
Exiger une seule source de calcul, des tests de cohérence liste ↔ fiche ↔ classements, vérifier les vraies
pages, sans publier de données réelles.

## Analyse
Reproduit sur le site public avant correction :
- **Recherche** : `PlayersPageHandler` redirigeait tout texte au format d'un pseudo vers `/demo/joueurs/<texte>`
  sans chercher dans les profils. `Demo_Brasier` / `demo_brasier` marchaient (recherche exacte, casse ignorée à
  l'affichage), mais `Brasier` → 404 « Aucun profil public », et `Demo Brasier` (espace) était ignoré en silence
  (retour à la liste complète). La recherche ne quittait pas la démonstration (`routes.players()`).
- **XP** : les valeurs étaient cohérentes entre elles (liste, fiche et classement « XP globale » lisent la même
  clé `GLOBAL` de `DemoData`, et les classements sont calculés depuis les profils), mais **le libellé était faux** :
  `GLOBAL` n'est pas un total. Dans le plugin c'est une piste à part, alimentée par un miroir de chaque piste
  (`progression.global-mirror-ratio`, 50 % par défaut) et par les quêtes (docs/PROGRESSION.md). Les données
  fictives suivent cette règle : Demo_Brasier = (6 100 + 1 200 + 300 + 400 + 900) / 2 + 15 × 50 = 5 200.
  Le même libellé « Expérience totale » / « d'expérience totale » était utilisé sur les vraies pages, les
  classements et la page compte (#53) : même défaut, sans conséquence sur des données.
- **Meilleur rang** : exact (1er en XP Combat), mais ne disait pas dans quel classement, d'où l'impression de
  contradiction avec « XP globale 5e ».

Choix : garder la règle réelle du jeu (changer `GLOBAL` en somme serait un changement de gameplay hors portail)
et la rendre **lisible** — option « règle documentée et affichée » de la demande.

## Travail effectué
- Recherche (`PlayersPageHandler#search`) : comparaison sans casse, espaces ni « _ » ; pseudo exact ou résultat
  unique → 303 vers la fiche sous son pseudo exact ; plusieurs → liste « N profils correspondent à « … » » ; aucun →
  « Aucun profil public ne correspond à « … » » (texte échappé). Recherche limitée aux profils de la page courante :
  l'aperçu ne renvoie jamais vers `/joueurs`, et les vraies pages ne voient aucun joueur fictif. Champ sans
  `pattern` (qui bloquait les espaces côté navigateur), 32 caractères max.
- Libellé « XP globale » partout (`StatLabels.title`, liste, fiche, `LinkedProgressView`), constante unique
  `StatLabels.GLOBAL_XP_RULE` affichée sous l'en-tête de la fiche et sur la page compte.
- « Meilleur rang : 1er en XP Combat ».
- Tests de cohérence et de recherche, doc `docs/WEB_PORTAL.md`.
- PR #802 → CI verte (PR et commit de fusion) → fusion `a2149a92` → `scripts/web-portal/deploy.sh` depuis le
  commit de fusion.

## Fichiers créés
- `docs/claude-reports/2026-10-23_2110_56-recherche-xp-demo.md`

## Fichiers modifiés
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/site/PlayersPageHandler.java`
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/site/StatLabels.java`
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/account/LinkedProgressView.java`
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/PublicPortalRefonteTest.java` (+2 tests)
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/PublicPortalV1Test.java` (libellés)
- `docs/WEB_PORTAL.md`, `docs/MANUAL_TEST_PLAN.md` (TC-414-bis), `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 15e),
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. `player-data-public=false`, `web-export.enabled`, `accounts-enabled` inchangés. Données fictives
`DemoData` inchangées (déjà cohérentes avec la règle).

## Tests automatiques
- Local : `./gradlew :web-api:test` complet vert (`PublicPortalRefonteTest` 11, `PublicPortalV1Test` 9,
  `AccountHttpTest` 12…).
- Nouveaux : `demoListProfileAndBoardsShowTheSameValues` (12 joueurs fictifs : XP globale = moitié des pistes +
  50 × quêtes ; même valeur sur liste, fiche et classement « XP globale » ; chaque piste ; chaque médaille = rang
  du classement « depuis toujours » ; meilleur rang ; règle affichée) et
  `demoSearchFindsFictionalPlayersByExactPartialOrSpacedNameWithoutLeavingTheDemo`.
- CI GitHub : PR #802 verte, commit de fusion `a2149a92` vert.
- Public après déploiement : `Demo_Brasier`, `demo_brasier`, `Brasier`, `brasier`, `demo brasier`, `bras` → 303
  `/demo/joueurs/Demo_Brasier` ; `demo_` → 12 correspondances ; `Zorro` → aucun ; `/joueurs?pseudo=Demo_Brasier`
  → 200 sans lien fictif ; `/`, `/classements`, `/joueurs`, `/evenements`, `/progression`, `/demo/*` en 200 ;
  aucun `Demo_` sur l'accueil réel. Chromium 1280 et 390 sur 4 pages : 0 débordement, 0 message console.

## Tests manuels à effectuer
TC-414-bis (PENDING MANUAL VALIDATION, navigateur, 3 min), puis le reste de TC-414.

## Résultat attendu
Chercher `Brasier` ouvre la fiche fictive ; 5 200 XP globale partout, avec la règle expliquée sur la fiche.

## Reset / retour à l'état initial
`scripts/web-portal/rollback.sh app` (release `releases/20261023-210720` = version précédente).

## Déploiement VeryGames
Aucun : rien à transférer sur le serveur Minecraft, pas de redémarrage, pas de migration. Portail AWS seul,
déployé par `scripts/web-portal/deploy.sh` (`a2149a9`, health UP). Ne pas toucher à la configuration du portail.

## Points restants
- Avis propriétaire sur #56 : règle d'XP globale claire ? garder l'aperçu fictif public ?
- Vrais classements/profils : décision #55 et instantané complet (toujours `MISSING` sur AWS).
