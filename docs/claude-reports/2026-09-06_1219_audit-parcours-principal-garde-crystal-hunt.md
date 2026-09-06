# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-06
* Heure : 12:19 (heure locale réelle de la machine)
* Sujet : Audit complet du parcours principal (nouveau joueur → `crystal_hunt` → déblocage `CLAIM_TIER_1`) et ré-audit des issues #21 (accès monde claims) / #22 (retour Hub). Cause du « Garde introuvable » et de « La chasse aux cristaux introuvable ».
* Statut : PARTIAL — causes identifiées et corrigées côté dépôt + serveur ; création du PNJ Garde et validation en jeu = actions manuelles restantes (voir checklist). Aucune issue fermée, aucune branche fusionnée.
* Branche Git : `deploy/issue-21-claims-journey`
* Commit actuel si disponible : voir « Commit(s) »
* Début de la tâche : 2026-09-06 12:04:11
* Fin de la tâche : 2026-09-06 12:__:__
* Durée totale : 00:__:__

## Demande

Auditer et rendre réellement jouable, de bout en bout, le parcours principal d'un joueur
reset/neuf jusqu'au déblocage réel de `CLAIM_TIER_1` sur le serveur DEV VeryGames :

1. Audit de la chaîne principale (quêtes, ordre, ids, prérequis, conditions, PNJ, récompenses,
   moment exact où `CLAIM_TIER_1=true`).
2. Audit des PNJ requis (Guide, Libraire, Garde, Jo…) : id logique, nom, tag Citizens, dialogue,
   quêtes démarrées/validées, existence physique.
3. Audit de `crystal_hunt` (disponibilité, PNJ donneur/valideur, dépendance manquante, reset).
4. Vérifier le contenu réellement déployé vs le dépôt ; liste exacte des fichiers à transférer.
5. Reproduire le parcours après `resetnew`.
6. Ré-auditer #21 / #22 (validation manuelle KO malgré tests verts).
7. Tests automatiques.
8. Déploiement DEV du strict nécessaire.
9. Checklist manuelle séquentielle.
10. Rapport + mise à jour de la doc de référence.

## Analyse

Sources croisées : code + ressources embarquées du JAR + **contenu réel du serveur DEV** récupéré
en lecture seule par FTP (`plugins/RPGQuest/**`, `plugins/Citizens/saves.yml`, une copie de
`plugins/RPGQuest/data.db` inspectée hors ligne). Rien n'a été modifié sur le serveur pendant
l'audit.

### 1. Chaîne principale — `stories/main_story.yml`

```
rpgquest:premiers_pas  →  rpgquest:first_steps  →  rpgquest:crystal_hunt
```

`main_story.yml` est un **conteneur logique ordonné** : il ne démarre jamais rien tout seul
(`story.StoryService`, cf. commentaire du fichier et `docs/storylines.md`). Chaque quête est
démarrée/rendue par un PNJ.

| Ordre | Quest ID | Nom affiché | Déclenchement (démarrage) | Objectif(s) | PNJ de rendu | Récompense | Débloque la suite |
|---|---|---|---|---|---|---|---|
| 1 | `rpgquest:premiers_pas` | « Premiers pas » | Dialogue **Guide** (`rpgquest:guide`, nœud `greeting`, choix « Très bien, j'y vais. ») → `START_QUEST` si `premiers_pas` = `NOT_STARTED`. `repeatable: true`. Aucun prérequis. | `TALK_TO_NPC npc: libraire` | **Libraire** (clic droit = complétion de l'unique objectif → quête `COMPLETED`, récompense auto). Pas de nœud de rendu explicite. | 20 XP | Rien ne bloque `first_steps` (pas de prérequis dessus). |
| 2 | `rpgquest:first_steps` | « Premiers pas » (même titre) | Dialogue **Garde** (`rpgquest:guard`, nœud `greeting`, choix « J'accepte la quête ») → `START_QUEST` si `first_steps` = `NOT_STARTED`. `repeatable: false`. Aucun prérequis. | `KILL_ENTITY entity: SPIDER amount: 10` | **Garde** — objectif de type KILL, quête `COMPLETED` au 10ᵉ kill, récompense auto (pas de rendu par dialogue). | 50 XP + 1× `IRON_SWORD` ; pose la variable `tutorial_started="true"`. | `first_steps = COMPLETED` est (a) le prérequis de `crystal_hunt` et (b) la condition du choix « J'ai entendu dire… » du Garde. |
| 3 | `rpgquest:crystal_hunt` | « La chasse aux cristaux » | Dialogue **Garde** (`rpgquest:guard`, nœud `greeting`, choix « J'ai entendu dire que tu avais besoin d'aide pour forger un équipement »), conditions : `first_steps = COMPLETED` **et** `crystal_hunt = NOT_STARTED` → `START_QUEST`. `repeatable: false`. `prerequisites: [rpgquest:first_steps]`. | Étape `hunt_spiders` : `KILL_ENTITY SPIDER ×5`. Étape `gather_crystals` : `COLLECT_ITEM material: AMETHYST_SHARD ×2` (éclat d'améthyste vanilla **ou** `rpgquest:refined_crystal`, dont le matériau de base est `AMETHYST_SHARD` — obtenu au nœud `crystal_ore` ou via `refined_crystal_recipe` = 4× `QUARTZ`). Étape `forge_blade` : `CRAFT_ITEM material: DIAMOND_SWORD ×1` (limite connue : n'importe quelle épée en diamant vanilla valide l'étape). Étape `report_to_guard` : `TALK_TO_NPC npc: guard`. | **Garde** (clic droit = étape `report_to_guard` → quête `COMPLETED`). | 100 XP + `COMMAND "customitem give %player% rpgquest:miner_pickaxe 1"` + **`VARIABLE key: CLAIM_TIER_1 value: "true"`** ; pose `crystal_hunt_started="true"`. | Dernière quête de `main_story`. |

**Moment exact où `CLAIM_TIER_1=true` est accordé** : à la **remise de `crystal_hunt`** (clic droit
sur le PNJ `guard` quand les étapes 1–3 sont finies) — `quest.progress.QuestProgressEngine`
applique la récompense `VARIABLE`. Jamais affiché comme récompense visible
(`QuestProgressEngine#grantRewards`). **Aucun autre point du code** ne pose `CLAIM_TIER_1` (vérifié
par recherche : seuls `quests/crystal_hunt.yml` le met à `"true"` et
`ClaimService.resetTierOneClaimForTesting` le met à `"false"`).

### 2. PNJ requis pour le parcours principal

| Rôle | Id logique RPGQuest | Nom affiché attendu | Liaison Citizens | Dialogue | Démarre / valide | Sur DEV aujourd'hui |
|---|---|---|---|---|---|---|
| Guide | `guide` | « Guide » | PNJ Citizens lié via `/rpgadmin npc tag guide` (mapping en base `npc_citizens_bindings`, jamais par le nom) | `dialogues/guide.yml` (`id: rpgquest:guide`) | Démarre `premiers_pas` ; centre d'aide du Hub | **✅ présent** — Citizens `#0`, uuid `0de7c993…`, lié `guide` |
| Libraire | `libraire` | « Libraire » | `/rpgadmin npc tag libraire` | `dialogues/libraire.yml` | Cible de rendu de `premiers_pas` (`TALK_TO_NPC libraire`) ; remet le journal | **✅ présent** — Citizens `#1`, uuid `b32d261a…`, lié `libraire` |
| **Garde** | **`guard`** | « Garde » (le nom affiché est libre ; **seul l'id de liaison doit être exactement `guard`**) | **`/rpgadmin npc tag guard`** sur un PNJ Citizens | `dialogues/guard.yml` (`id: rpgquest:guard`) | Démarre `first_steps` ; démarre **et** valide `crystal_hunt` → accorde `CLAIM_TIER_1` | **❌ ABSENT** — aucun PNJ, aucune ligne `guard` dans `npc_citizens_bindings` |
| Jo | `jo` | « Jo » | `/rpgadmin npc tag jo` | `dialogues/jo.yml` | Remet `rpgquest:acte_propriete` quand `CLAIM_TIER_1="true"` ; aide claims | **✅ présent** — Citizens `#5`, uuid `3720db0d…`, lié `jo` |
| (Marchand) | `merchant` | « Marchand » | hors chaîne principale | `dialogues/merchant.yml` | Boutique uniquement | ❌ absent — **non requis** pour le parcours claim |

PNJ présents sur DEV mais **hors chaîne principale** : `help`, `jeff`, `junior` (contenu de test
fait main sur le serveur ; quêtes `help_recolte`, `jeff_nettoyage`, `junior_chantier` — aucune
n'accorde `CLAIM_TIER_1`).

Rappel du mécanisme (voir `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1) : un PNJ Citizens est relié à
RPGQuest par **`NPC#getUniqueId()` ↔ id logique**, persisté dans `data.db`
(`npc_citizens_bindings`), **jamais par le nom affiché**. Le nom « Garde » n'a aucun effet à lui
seul : il faut `/rpgadmin npc tag guard`. Le dialogue `rpgquest:guard` s'ouvre alors au clic droit
et l'objectif `TALK_TO_NPC npc: guard` se valide sur ce même PNJ.

### 3. `crystal_hunt` — disponibilité

- **Devient disponible uniquement** en cliquant droit sur le PNJ lié `guard` et en choisissant
  « J'ai entendu dire… » (conditions `first_steps = COMPLETED` + `crystal_hunt = NOT_STARTED`).
  **Pas d'auto-acceptation**, aucun autre PNJ ne la propose, `StoryService` ne la lance jamais.
- **Apparaît dans le journal seulement après acceptation** (le journal n'affiche que les quêtes
  déjà acceptées — `guide.yml` nœud `help_journal`, GUI 2 onglets « en cours » / « terminées »).
- **Dépendances manquantes qui empêchent son apparition — les deux sont présentes sur DEV :**
  1. **Aucun PNJ lié `guard`** (cf. §2) → impossible de démarrer `first_steps` **ni**
     `crystal_hunt`.
  2. **Le `dialogues/guard.yml` déployé est une version périmée** : il ne contient **pas** la
     branche `crystal_hunt` (choix « J'ai entendu dire… ») ni le nœud `crystal_hunt_accepted`.
     Même en créant un Garde aujourd'hui, `crystal_hunt` resterait indémarrable tant que ce
     fichier n'est pas remplacé. (Les YAML embarqués n'écrasent jamais un fichier déjà présent ;
     les déploiements passés n'ont poussé que `guide.yml` / `jo.yml`.)
- **Après `/rpgadmin player resetnew <joueur> confirm`** : `PlayerResetService` efface la
  progression de quêtes, les variables, les claims, remet `CLAIM_TIER_1="false"` puis efface
  toutes les variables → `crystal_hunt` et `first_steps` repassent `NOT_STARTED`, `CLAIM_TIER_1`
  absente. La chaîne est **entièrement rejouable — à condition que le Garde existe**.

**Preuve en base (copie DEV `data.db`)** — cohérente avec « le joueur a fait toutes les quêtes
trouvées sans jamais voir `crystal_hunt` » :

- `npc_citizens_bindings` : `guide, libraire, help, jeff, junior, jo` — **pas de `guard`**.
- `quest_progress` : **0 ligne** `first_steps` ou `crystal_hunt`, pour aucun joueur. Seul
  `premiers_pas = COMPLETED` (2 joueurs) + les quêtes de test faites main.
- `player_variables` : aucune ligne `CLAIM_TIER_1` (seulement `RUNE_RAPPEL_GRANTED`).
- `claims` : **0 ligne**. `story_progress` : vide.

**Cause racine du « Garde / crystal_hunt introuvable »** : le PNJ `guard` n'a jamais été créé ni
lié sur DEV, **et** le `guard.yml` du serveur est antérieur à l'ajout de la branche `crystal_hunt`.
La chaîne principale est donc rompue à l'étape 2 : rien ne permet de démarrer `first_steps`, donc
jamais `crystal_hunt`, donc jamais `CLAIM_TIER_1`.

### 4. Contenu déployé vs dépôt

| Fichier serveur | État |
|---|---|
| `RPGQuest/dialogues/guide.yml` | **identique** au dépôt ✅ |
| `RPGQuest/dialogues/jo.yml` | **identique** ✅ |
| `RPGQuest/dialogues/guard.yml` | **PÉRIMÉ** — branche `crystal_hunt` + nœud `crystal_hunt_accepted` absents ❌ |
| `RPGQuest/quests/crystal_hunt.yml` | identique ✅ |
| `RPGQuest/quests/first_steps.yml` | **édité à la main sur le serveur** (`description` « Apprends les bases modifiées ! », `icon` retiré). Cosmétique, non bloquant. |
| `RPGQuest/quests/premiers_pas.yml` | seule différence : fins de ligne CRLF / pas de newline final. Sans effet. |
| `RPGQuest/stories/main_story.yml` | identique ✅ |
| `RPGQuest/world-portals/hub_to_claims.yml` | présent, correct : `world: world_hub` [730..732 / 66..69 / -676], `destination-world: claims`, `enabled: true`, `WORLD_SPAWN` ✅ |
| `RPGQuest/world-portals/hub_to_wild.yml` | présent ✅ |
| `RPGQuest/config.yml` | `claims.world: claims`, `hub.world: world_hub`, `travel.wild-world: wild` — cohérent ✅ |
| `RPGQuest/items/*.yml` | tous présents (`acte_propriete`, `pierre_retour`, `refined_crystal`, `miner_pickaxe`, …) ✅ |
| JAR déployé | sha256 `3806243c…8946` = **identique** au JAR construit localement au commit `0e4678e` ; contient `ClaimWorldAccessGuard`, `ClaimWorldSafetyListener`, `NegatedCondition`, `CompositeWorldPortalEntryGuard` ✅ |
| Plugins serveur | WorldEdit, **Citizens 2.0.43-b4232**, spark, **Multiverse-Core 5.7.3**, RPGQuest, bStats. Mondes Multiverse : `claims`, `overworld`, `the_end`, `the_nether`, `world_hub`. |

### 5. Parcours après `resetnew` (attendu)

Avec le Garde créé + `guard.yml` à jour + JAR à jour :

1. `resetnew` → toutes les quêtes `NOT_STARTED`, `CLAIM_TIER_1` absente, aucun claim.
2. Clic droit **Guide** → choix « Très bien, j'y vais. » → `premiers_pas` démarrée. Menu d'aide
   disponible (« Comment fonctionnent les claims ? » énonce le prérequis).
3. Clic droit **Libraire** → `premiers_pas` `COMPLETED` + journal remis.
4. Clic droit **Garde** → « J'accepte la quête » → `first_steps` démarrée.
5. Tuer 10 araignées → `first_steps` `COMPLETED` (50 XP + épée en fer).
6. Re-clic droit **Garde** → « J'ai entendu dire… » → `crystal_hunt` démarrée (visible dans le
   journal).
7. 5 araignées ; 2 éclats d'améthyste (ou cristaux raffinés) ; fabriquer une épée en diamant ;
   re-clic droit **Garde** → `crystal_hunt` `COMPLETED` → **`CLAIM_TIER_1="true"`** + `miner_pickaxe`.
8. Clic droit **Jo** → « Je viens réclamer mon acte de propriété » (visible car `CLAIM_TIER_1="true"`
   + `NO_MAIN_CLAIM`) → remet `rpgquest:acte_propriete`.
9. Portail Hub → claims (`hub_to_claims`) : `ClaimWorldAccessGuard` autorise le passage ; à
   l'arrivée `ClaimWorldSafetyListener` donne une `pierre_retour` si absente.
10. Clic droit avec l'acte dans le monde `claims` → premier claim posé ; clic droit
    `pierre_retour` → retour au Hub sans commande.

### 6. Ré-audit #21 / #22

**Faits établis :**

- `hub_to_claims` **est** un World-Portal RPGQuest valide vers `claims` ; `claims.world = claims` ;
  le monde `claims` existe (Multiverse). Donc `ClaimWorldAccessGuard` **s'applique bien** à ce
  portail (`portal.destinationWorld().equals(config.world())`).
- Le JAR en ligne = le JAR local (sha256 identique) et contient les deux gardes. Le dialogue de Jo
  à 3 états (modificateur `negate`) fonctionne en jeu d'après le test → **le nouveau JAR est bien
  celui qui tourne**, donc `ClaimWorldAccessGuard` **et** `ClaimWorldSafetyListener` sont actifs.
- `RPGQuest/spawn.yml` = `world_hub` (738.8 / 67 / -679.6) → la cible de repli Hub de
  `ClaimWorldSafetyListener` se résout correctement.
- **Aucun plugin de permissions installé** (WorldEdit / Citizens / spark / Multiverse-Core /
  bStats). Le nœud `rpgquest.admin.world` (défaut `op` dans `plugin.yml`) est donc détenu
  **si et seulement si le compte est OP**. `LoDyMcFly` est propriétaire (`owner`) de **tous** les
  PNJ Citizens → compte OP.

**Trace du chemin de code pour un joueur NON privilégié, sans `CLAIM_TIER_1`, sans claim :**

- Il marche dans `hub_to_claims` → `WorldPortalTeleportListener.onMove` consulte le garde composé →
  `ClaimWorldAccessGuard.allowEntry` : pas de laissez-passer `cleared`, **pas** le bypass,
  `mainClaimOf` vide → lecture asynchrone `hasClaimTierOne` → **retourne `false`**, aucune
  téléportation, `refuse()` (2 messages d'orientation). ✅ **Il ne peut pas entrer.**
- S'il atteint `claims` autrement (`/mv tp`, etc. — mais ces commandes sont OP) →
  `ClaimWorldSafetyListener.onWorldChange` → pas le bypass, `mainClaimOf` vide → `hasClaimTierOne`
  `false` → `sendBackToHub` (renvoi au spawn `world_hub`). ✅ **Il ne reste pas coincé.**
- Joueur **éligible** sans claim → à l'arrivée, `ensureReturnStone` lui donne une `pierre_retour`
  (idempotent). ✅ **Retour Hub sans commande.**

**Conclusion #21/#22 :** pour le **parcours normal (compte non opéré)**, **aucun défaut de code**.
La seule explication cohérente à la fois avec #21 (entrée libre) **et** #22 (ni renvoi, ni Pierre
de retour, obligé de se déco/reco) est que le test a été fait **depuis un compte OP**, qui déclenche
le **bypass `rpgquest.admin.world` volontaire** dans les deux composants :

- `ClaimWorldAccessGuard.allowEntry` : `player.hasPermission(BYPASS_PERMISSION)` → `return true`
  (entre sans contrôle) ;
- `ClaimWorldSafetyListener.handleArrival` : même test → `return` immédiat (ni renvoi, ni objet
  imposé — décision documentée du lot précédent).

Hypothèses alternatives examinées et **écartées** : nom de monde divergent (les deux valent
`claims`), portail non-RPGQuest (`hub_to_claims` existe et correspond), listeners non enregistrés
(le bootstrap câble les deux), JAR périmé (sha identique + comportement de Jo), cible de repli Hub
vide (`spawn.yml` valide).

**Correctif retenu pour #21/#22 :** rejouer la validation manuelle **avec un compte réellement non
opéré** (`Rondoudou9000` / `Madix666`, jamais `LoDyMcFly`). Durcissement appliqué : **journalisation
de décision ciblée** dans les deux composants, pour que le prochain test en jeu soit **concluant**
(voir « Logs / diagnostic »). Le comportement fonctionnel n'est pas modifié.

## Travail effectué

1. **Audit complet** (ci-dessus) croisant code, JAR embarqué et contenu réel du serveur DEV
   (FTP lecture seule + copie hors ligne de `data.db`).
2. **Code — journalisation de décision** (permanente, niveau `INFO`, utile en exploitation) :
   - `claim/ClaimWorldAccessGuard` : log explicite à chaque branche de `allowEntry` — bypass
     (`AUTORISÉE par le bypass … (compte OP …)`), claim existant, `CLAIM_TIER_1` débloqué
     (téléportation relancée), **refus** (aucune téléportation).
   - `claim/ClaimWorldSafetyListener` : log explicite à chaque branche de `handleArrival` —
     bypass (`filet de sécurité IGNORÉ`), propriétaire d'un claim, éligible (Pierre de retour
     garantie), **non éligible** (renvoi au Hub).
3. **Contenu serveur** : identification du `guard.yml` périmé (branche `crystal_hunt` manquante) →
   à redéployer.
4. **Documentation de référence** mise à jour pour qu'on ne puisse plus oublier quels PNJ doivent
   exister physiquement (voir « Documentation mise à jour »).
5. **Déploiement DEV** du strict nécessaire (voir « Déploiement VeryGames »).

## Fichiers créés

- `docs/claude-reports/2026-09-06_1219_audit-parcours-principal-garde-crystal-hunt.md` (ce rapport).

## Fichiers modifiés

- `src/main/java/com/lodygames/rpgquest/claim/ClaimWorldAccessGuard.java` — journalisation de
  décision (`[claims-access]`), aucun changement de comportement.
- `src/main/java/com/lodygames/rpgquest/claim/ClaimWorldSafetyListener.java` — journalisation de
  décision (`[claims-safety]`), aucun changement de comportement.
- `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` — nouvelle section « PNJ obligatoires du parcours principal »
  (Guide, Libraire, **Garde**, Jo) + procédure exacte de création/liaison du Garde.
- `docs/RPGQUEST_BIBLE.md` — parcours principal : la chaîne `premiers_pas → first_steps →
  crystal_hunt` exige un PNJ lié `guard` ; `CLAIM_TIER_1` accordé à la remise de `crystal_hunt`.
- `docs/current_state.md` — prérequis opérationnel (PNJ `guard` + `guard.yml` à jour).
- `docs/deployment/SERVER_CHANGELOG.md` — entrée de ce déploiement DEV.
- `docs/deployment/VERYGAMES.md` — checklist « PNJ Citizens obligatoires » + rappel World-Portal
  `hub_to_claims`.
- (commit `docs` séparé) `docs/CLAIMS.md`, `docs/RPGQUEST_BIBLE.md`, `docs/NPC_DIALOGUES_QUESTS_GUIDE.md`,
  `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`,
  `docs/claude-reports/2026-09-06_1116_parcours-claims-coherent-issues-21-22-23.md` — documentation
  laissée non commitée par la session précédente (code déjà livré en `c8cd5bb` / `0a484cc`).

## Base de données / migrations

Aucune migration, aucun changement de schéma. La journalisation ajoutée est en lecture seule
(`hasClaimTierOne`, `mainClaimOf`). La création du Garde ajoutera **une** ligne à
`npc_citizens_bindings` (`<uuid PNJ>|<id numérique>|guard|<horodatage>`) via `/rpgadmin npc tag`,
côté serveur — aucun fichier à déployer pour ça.

## Configuration / données

Aucun changement de `config.yml`. `dialogue.allowed-commands` contient déjà `customitem` et
`claim`. Le `guard.yml` redéployé n'utilise que `START_QUEST` (pas de `RUN_SAFE_COMMAND`).

## Tests automatiques

- Journalisation seule ajoutée au code : aucun test existant n'assert sur ces logs.
- `./gradlew test` — __RÉSULTAT À COMPLÉTER__
- `./gradlew build` — __RÉSULTAT À COMPLÉTER__

Note (règle de la demande) : MockBukkit ne prouve pas #21/#22 puisque la logique dépend du **nom
réel du monde**, d'un **World-Portal configuré** et du **statut OP du testeur** — d'où l'audit du
contenu serveur réel ci-dessus et la journalisation de décision plutôt qu'un énième test mock.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — voir la checklist séquentielle en fin de rapport. Points durs :
création du PNJ Garde, test #21/#22 **avec un compte non opéré**.

## Résultat attendu

- Après redéploiement (`guard.yml` + JAR) **et** création du PNJ `guard` : un joueur reset peut
  enchaîner Guide → Libraire → Garde (`first_steps`) → Garde (`crystal_hunt`) → `CLAIM_TIER_1` →
  Jo (acte) → portail claims → claim posé → Pierre de retour → Hub.
- Compte **non opéré** sans `CLAIM_TIER_1` : portail Hub → claims **refusé** (message, aucune
  téléportation) ; s'il arrive autrement, **renvoyé** au Hub ; jamais coincé.
- Compte **opéré** : entre librement et n'est pas doté/renvoyé — **c'est le bypass voulu**, tracé
  dans la console (`[claims-access] … AUTORISÉE par le bypass …`).

## Reset / retour à l'état initial

- Code : `git revert` du commit de journalisation (ou `git checkout` du fichier). Aucune donnée.
- Serveur : voir « Rollback ».

## Déploiement VeryGames

### À transférer

1. `rpgquest-0.1.0-SNAPSHOT.jar` (reconstruit — ajoute la journalisation de décision) → racine FTP
   (= `plugins/`).
2. `RPGQuest/dialogues/guard.yml` — **obligatoire** : débloque la branche `crystal_hunt`.
3. `RPGQuest/quests/first_steps.yml` — **recommandé** : réaligne la version éditée à la main sur
   le serveur (écrase le texte « modifiées ! » et rétablit `icon: IRON_SWORD`). Non bloquant ;
   à omettre si l'on veut conserver l'édition serveur.

Chaque fichier distant remplacé est sauvegardé (backup daté) par le script de déploiement.

### Ne PAS transférer / altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `worlds.yml`, tout autre fichier de
`RPGQuest/`, les mondes, `plugins/Citizens/**`, `plugins/Multiverse-Core/**`, les autres plugins.
Aucune synchronisation de dossier. Aucune fusion GitHub. Aucune issue fermée.

### Redémarrage requis

Oui — nouveau JAR (bootstrap) + rechargement des dialogues au démarrage du plugin.

### Migration automatique

Aucune.

## Rollback

1. Arrêter le serveur.
2. Restaurer l'ancien JAR **et** l'ancien `dialogues/guard.yml` (et `quests/first_steps.yml` s'il
   a été remplacé) depuis les backups datés.
3. Redémarrer, vérifier `/rpgquest version`.
4. `scripts/rollback-verygames.sh --latest` restaure le JAR ; pour les `--also`, réutiliser le
   dossier `extra-<stamp>/` (voir `MANIFEST.txt`).

`CLAIM_TIER_1`, les claims et les liaisons PNJ ne sont jamais touchés par ce déploiement.

## Logs / diagnostic

Nouveaux logs `INFO` (console serveur), à observer lors du prochain test en jeu :

- `[claims-access] <joueur> : entrée dans « claims » via le portail hub_to_claims AUTORISÉE par le
  bypass rpgquest.admin.world (compte OP ou permission explicite) — contrôle CLAIM_TIER_1 non
  appliqué.` → **le testeur est OP** : rejouer avec un compte non opéré.
- `[claims-access] <joueur> : entrée dans « claims » REFUSÉE (CLAIM_TIER_1 non débloqué, aucun
  claim) — aucune téléportation.` → comportement attendu pour un nouveau joueur.
- `[claims-access] <joueur> : entrée dans « claims » autorisée (CLAIM_TIER_1 débloqué) —
  téléportation relancée.` → parcours nominal après `crystal_hunt`.
- `[claims-safety] <joueur> présent dans « claims » : filet de sécurité IGNORÉ (bypass
  rpgquest.admin.world) — ni renvoi au Hub ni Pierre de retour.` → explique #22 pour un OP.
- `[claims-safety] <joueur> NON éligible dans « claims » : renvoi au Hub.` / `… éligible … :
  garantie d'une Pierre de retour.` → filet de sécurité nominal.

## Documentation mise à jour

`docs/NPC_DIALOGUES_QUESTS_GUIDE.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/deployment/SERVER_CHANGELOG.md`, `docs/deployment/VERYGAMES.md` (+ commit `docs` de
rattrapage de la session précédente).

## Limitations / travail restant

- **Le PNJ Garde doit être créé manuellement** sur DEV (Citizens + `/rpgadmin npc tag guard`) —
  action serveur, non automatisable depuis le dépôt. Voir checklist A.
- **#21/#22 non revalidés en jeu** : la validation exige un compte non opéré + le portail réel.
  Le code est correct pour ce cas ; la journalisation rendra le prochain test concluant.
- Aucune issue GitHub fermée. Aucune branche fusionnée.
- `docs/deployment/VERYGAMES.md` conserve par ailleurs des passages datés (identité PNJ « par id
  numérique ») hors périmètre de cette tâche.
- Étape `forge_blade` de `crystal_hunt` : `CRAFT_ITEM DIAMOND_SWORD` validé par n'importe quelle
  épée en diamant vanilla (limite connue, déjà documentée dans `ARCHITECTURE.md`).

## Prochaine étape suggérée

1. Redémarrer DEV sur le nouveau JAR + `guard.yml`.
2. Exécuter la checklist manuelle (créer le Garde, reset `Rondoudou9000`, dérouler le parcours,
   tester #21/#22 non opéré).
3. Selon les logs `[claims-access]` / `[claims-safety]` : confirmer que #21/#22 étaient un
   artefact de test OP, ou rouvrir l'analyse avec la trace.

---

## Checklist manuelle (séquentielle)

Toutes les commandes en jeu, joueur OP sauf mention contraire.

**A. Créer / configurer le Garde** *(après redéploiement du JAR + `guard.yml` + redémarrage)*
1. `/npc create Garde --type player`
2. *(optionnel)* `/npc skin <pseudo>` — apparence.
3. Se placer à moins de 6 blocs, **regarder droit le Garde**, puis `/rpgadmin npc info`
   → doit répondre « Cette entité n'est pas identifiée. »
4. `/rpgadmin npc tag guard` → « Entité identifiée : guard (Citizens NPC #N) »
5. `/rpgadmin npc info` → « Identifiant : guard (Citizens NPC #N) » *(persiste après redémarrage)*
6. Placer le Garde près des autres PNJ du Hub (`/npc move` ou recréer à l'emplacement voulu).

**B. Reset joueur** *(compte de test non opéré, ex. `Rondoudou9000`)*
7. `/rpgadmin player resetnew Rondoudou9000 confirm`

**C. Ordre exact des PNJ à visiter**
8. Clic droit **Guide** → « Très bien, j'y vais. » *(démarre `premiers_pas`)*
9. Clic droit **Libraire** *(rend `premiers_pas`, remet le journal)*
10. Clic droit **Garde** → « J'accepte la quête » *(démarre `first_steps`)*
11. Tuer **10 araignées** *(→ `first_steps` COMPLETED : 50 XP + épée en fer)*

**D. Faire apparaître `crystal_hunt`**
12. Re-clic droit **Garde** → choix « J'ai entendu dire que tu avais besoin d'aide pour forger un
    équipement » *(démarre `crystal_hunt` — visible ensuite dans le journal, onglet « en cours »)*

**E. Terminer `crystal_hunt`**
13. Tuer **5 araignées**.
14. Obtenir **2 éclats d'améthyste** (miner une géode d'améthyste) **ou** 2 `Cristal raffiné`
    (nœud `crystal_ore`, ou fabriquer avec 4× quartz via `refined_crystal_recipe`).
15. Fabriquer **1 épée en diamant**.
16. Re-clic droit **Garde** *(→ `crystal_hunt` COMPLETED : 100 XP + `miner_pickaxe` + `CLAIM_TIER_1`)*

**F. Vérifier `CLAIM_TIER_1`**
17. Pas de commande de lecture dédiée. Vérifs indirectes : clic droit **Jo** → l'option
    « Je viens réclamer mon acte de propriété » **apparaît** ; et/ou console au passage du portail :
    `[claims-access] Rondoudou9000 : entrée … autorisée (CLAIM_TIER_1 débloqué)`.
    *(Option admin : inspecter `player_variables` hors ligne — ne pas éditer `data.db` en direct.)*

**G. Accès Claims refusé AVANT déblocage** *(faire cette étape juste après B, avant D–F, avec le
compte non opéré)*
18. Marcher dans le portail Hub → claims. Attendu : 2 messages rouges d'orientation, **aucune
    téléportation**. Console : `[claims-access] Rondoudou9000 : … REFUSÉE (CLAIM_TIER_1 non
    débloqué …)`.
19. Si l'entrée réussit : lire la console. `… AUTORISÉE par le bypass rpgquest.admin.world` ⇒ le
    compte est OP → refaire avec un compte réellement non opéré.

**H. Accès Claims autorisé APRÈS déblocage**
20. Après l'étape 16, reprendre le portail Hub → claims. Attendu : téléportation dans le monde
    `claims`. Console : `[claims-access] … autorisée (CLAIM_TIER_1 débloqué)`.

**I. Réception de la Pierre de retour**
21. À l'arrivée dans `claims` : message « Tu reçois une Pierre de retour. » + objet en inventaire
    (une seule fois). Console : `[claims-safety] … éligible dans « claims » … Pierre de retour`.

**J. Retour Hub**
22. Clic droit avec la **Pierre de retour** → retour au spawn du village, **sans commande**.
23. *(Anti-blocage)* Avec le compte non opéré, se faire `/tp` dans `claims` sans `CLAIM_TIER_1` :
    attendu = renvoi immédiat au Hub + message. Console : `[claims-safety] … NON éligible … renvoi
    au Hub.`
