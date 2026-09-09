# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-09
* Heure : 12:45
* Sujet : #101 — bug(control-panel) : un PNJ Citizens réel (« Stan ») n'apparaît pas dans `/npcs`
* Statut : DONE (déploiement + validation live : voir sections dédiées)
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel au démarrage : `d039303`
* Début de la tâche : 2026-09-09 12:25:19
* Fin de la tâche : 2026-09-09 __:__:__
* Durée totale : __:__:__

## Demande

Le 2026-09-08 l'owner a créé en jeu un PNJ Citizens nommé **Stan**. Le 2026-09-09, Stan
n'apparaît pas dans la liste `/npcs` de PlugAdmin, alors que la console VeryGames indique
`[Citizens] Loaded 8 NPCs` et que PlugAdmin affiche « 8 PNJ Citizens ». Les totaux
concordent (8 = 8) mais l'identité « Stan » est absente.

Consigne stricte : **investigation avant fix**, tracer la chaîne complète
`Citizens runtime → npc.citizens.list → payload agent → stockage PlugAdmin → parsing →
NpcCatalog / merge → rendu /npcs`, dire précisément **où** Stan disparaît et **pourquoi**.
Ne rien recréer / supprimer / renommer / relier arbitrairement ; corriger la **cause**.

## Analyse

### Chaîne tracée, étape par étape

| Étape | Stan présent ? | Détail |
|---|---|---|
| Registre Citizens runtime | **oui** | `CitizensNpcBridge.roster()` itère `CitizensAPI.getNPCRegistry()` (registre par défaut), sans filtre `spawned` / monde / trait. |
| Action agent `npc.citizens.list` | **oui** | Payload DEV réel (action `7d394d2d…`, 2026-09-09 11:33) : 8 entrées `#0 Guide … #6 Garde` liées + **`#7 Stan`** `linkedNpcId:null` `availableForBinding:true` `spawned:false` `uuid:5e081a06-6596-47a3-b769-aef5fcd9e676`. Sérialisation = **liste 1:1** (`AgentActionExecutor.npcCitizensList`), aucune `Map`, aucune clé par nom, aucun dédoublonnage. |
| Stockage PlugAdmin | **oui** | `agent_action.result_json` contient les 8, Stan inclus. |
| Action agent `npc.list` (`NpcCatalog`) | **NON** | `npc.list` (`d8442211…`/`603a3938…`) : 8 lignes = 6 `CITIZENS_ORPHAN` (binding sans définition : guide/help/jeff/jo/junior/libraire) + `guard` `LINKED` + `woodcutter_bob` `NOT_LINKED`. **Aucune ligne Stan.** `NpcCatalog.build()` ne reçoit que : définitions `npcs/*.yml`, dialogues, quêtes, **liaisons** Citizens (`npc_citizens_bindings`, clé = `npcId`). Il ne lit **jamais** le registre Citizens (documenté : « PNJ Citizens non tagués = hors périmètre »). Un PNJ Citizens sans définition **et** sans binding **et** sans référence de contenu ne produit aucune ligne. |
| Rendu `/npcs` (`AgentPages.npcs()`) | **NON** | La liste (accordéon) itère **uniquement** `npc.list.npcs`. Le relevé `npc.citizens.list` n'était utilisé que pour (a) la ligne de synthèse « Citizens : 8 · 1 libre(s) · 7 lié(s) » et (b) `citizensNameFor()` — le nom Citizens des PNJ **déjà liés**. Stan (libre) n'a donc aucune ligne. |

### Cause racine

`/npcs` construit sa liste **exclusivement** à partir de `npc.list` (catalogue RPGQuest =
définitions + liaisons + références de contenu). Un PNJ Citizens réel **sans fiche RPGQuest
ni liaison** (cas de Stan, créé par `/npc create Stan` puis jamais tagué) n'existe dans
aucune de ces sources : il est **absent de la liste rendue**, même si la ligne de synthèse
le compte. Le « 8 = 8 » du ticket est une coïncidence : les 8 de `npc.list`
(6 orphelins + `guard` + `woodcutter_bob`) et les 8 de `npc.citizens.list` (`#0`..`#7`) ne
sont **pas** le même ensemble — ils se recoupent sur 7 seulement.

**Étape où Stan disparaissait : le rendu du Control Panel (`AgentPages.npcs()`).**
Pas le registre Citizens, pas l'action agent, pas le stockage, pas de sérialisation par
nom, pas de collision d'id, pas de merge destructif.

### Décision : correction côté Control Panel uniquement

`npc.citizens.list` renvoie déjà tout le nécessaire (id numérique, UUID, nom, `linkedNpcId`,
`availableForBinding`, `spawned`). Étendre `NpcCatalog` pour ingérer le registre Citizens
serait une extension du modèle canonique (explicitement hors périmètre de la classe) et
imposerait un redéploiement plugin + redémarrage VeryGames (risque anti-auto-reboot) pour
un gain nul ici. Le fix se fait donc **entièrement dans `control-panel`** — déployable par
`scripts/plugadmin/deploy.sh` sans toucher Minecraft.

## Travail effectué

### `AgentPages.npcs()` — raccordement des PNJ Citizens libres

- Calcul de `freeCitizens` = entrées de `npc.citizens.list` dont `linkedNpcId` est vide/`null`.
- La liste rend d'abord les lignes `npc.list`, puis une **ligne d'identité physique** par
  PNJ Citizens libre (`renderFreeCitizensAccordionItem`) :
  - **libellé principal = nom en jeu** (MiniMessage rendu), sous-titre `Citizens #N` ;
    jamais `undefined` / `unknown` / un id technique. Si le nom Citizens est vide →
    « PNJ Citizens #N ».
  - badges `sans fiche RPGQuest` (danger) + `non lié` (warning) ; état d'affichage
    `CITIZENS_ONLY`.
  - `data-filter-cat="unlinked"` → visible sous **Tous** et **Non liés**.
  - `data-filter-text` = nom + `#N` + id numérique + **UUID** → recherche « Stan », « 7 »
    ou UUID (#101 §14). `data-res-id="citizens-<N>"` pour les liens profonds `/diagnostics`.
  - détail **Identité Citizens** : nom en jeu, numéro, UUID, présence (spawné) ; section
    **Fiche RPGQuest** = « aucune » + phrase humaine ; section **Diagnostics** =
    `DiagnosticHelp.render("CITIZENS_ONLY", …)` (INFO).
  - **Actions** (selon permissions) : *Créer une fiche RPGQuest* (`npcDefForm` en mode
    `create`, id pré-rempli = nom normalisé `[a-z0-9._-]`, repli `citizens_<N>`) ;
    *Lier à une fiche existante* = **liaison inverse** (`citizensInverseLinkForm`) : le PNJ
    Citizens est fixé (`citizens_id=<N>`), l'admin choisit une fiche **prête et non liée**
    dans un `<select>` — réutilise l'action agent `npc.citizens.link` (aucun spawn, aucun
    déplacement, aucun rebind).
- Le **compteur** `/npcs` = `npc.list.size() + freeCitizens.size()`.
- L'état vide ne court-circuite plus que si `npc.list` **et** `freeCitizens` sont vides.
- `npcStateBadge` : nouveau cas `CITIZENS_ONLY` → pastille « Citizens seul » (neutre).

### Diagnostics (#38 / #49)

- `DiagnosticHelp` : nouvelle entrée `CITIZENS_ONLY` (niveau **INFO**), titre humain
  « PNJ du jeu sans fiche RPGQuest », conséquence + action, fiche `pnj-depannage`.
- `pnj-depannage.md` : nouvelle section « PNJ du jeu sans fiche RPGQuest » (ancre =
  `slug(titre)` = `pnj-du-jeu-sans-fiche-rpgquest`), avec le **tableau des trois états**
  (Citizens uniquement / fiche uniquement / lié) et la marche à suivre pour rattacher.
- `NpcDiagnosticProvider` : émet un INFO `CITIZENS_ONLY` par PNJ Citizens libre lu dans
  `npc.citizens.list` (sans effet si le relevé est absent — les tests existants ne le
  fournissent pas). Un PNJ Citizens libre **n'est pas** transformé en erreur.

### Ce qui n'a **pas** été fait (délibérément)

- Aucune modification plugin / agent : `npc.citizens.list` est correct.
- Pas d'extension de `NpcCatalog` au registre Citizens (hors périmètre du modèle canonique).
- Pas de suppression / rebind / déplacement de PNJ Citizens depuis cette ligne (chantiers
  séparés déjà notés pour #81).

## Fichiers créés

* `docs/claude-reports/2026-09-09_1245_fix-101-npc-citizens-libre-masque-npcs.md` (ce rapport)

## Fichiers modifiés

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
  — calcul `freeCitizens` + `definedUnlinkedIds`, rendu des lignes Citizens libres,
  `renderFreeCitizensAccordionItem`, `citizensNameToId`, `citizensInverseLinkForm`,
  cas `CITIZENS_ONLY` dans `npcStateBadge`, compteur, état vide.
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/DiagnosticHelp.java`
  — entrée `CITIZENS_ONLY` (INFO).
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/diag/NpcDiagnosticProvider.java`
  — émission INFO `CITIZENS_ONLY` depuis `npc.citizens.list`.
* `control-panel/src/main/resources/docs/pnj-depannage.md`
  — section « PNJ du jeu sans fiche RPGQuest ».
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/NpcsCatalogTest.java`
  — 2 tests #101 + correction de la borne de la fiche `guard` dans un test existant.
* `src/test/java/com/lodygames/rpgquest/web/agent/NpcCitizensPayloadTest.java`
  — test « la sérialisation conserve chaque entrée du registre (libre + homonymes) ».
* `NPC_FORMAT.md` — ligne `CITIZENS_ONLY` dans le tableau des états + note.
* `docs/RPGQUEST_BIBLE.md` — section 5 : « un PNJ peut exister dans Citizens sans être géré
  par RPGQuest », les trois états, actions de rattachement.
* `docs/current_state.md` — bullet #101 dans la section Control Panel.
* `docs/control-panel/ROADMAP.md` — « Étape 3h — bug #101 » (LIVRÉ).
* `docs-site/npc.html` — note publique « Citizens sans gestion RPGQuest ».
* `docs/deployment/SERVER_CHANGELOG.md` — entrée « 2026-09-09 - Control Panel : fix /npcs (#101) ».

## Base de données / migrations

Aucune. `control-panel.db` non touché. Aucun schéma plugin modifié.

## Configuration / données

Aucun changement de configuration. Aucune donnée Citizens (`saves.yml`), aucun binding,
aucune définition `npcs/*.yml` modifiés — investigation en lecture seule.

## Tests automatiques

* `./gradlew :control-panel:test` — __ /0 (à confirmer ; `NpcsCatalogTest` étendu :
  `freeCitizensNpcWithoutDefinitionIsListedSearchableAndAttachable`,
  `twoFreeCitizensWithSameNameBothAppear` ; `DiagnosticHelpTest` valide l'ancre doc de
  `CITIZENS_ONLY`).
* `./gradlew test` — __ (à confirmer ; `NpcCitizensPayloadTest` étendu).
* `./gradlew build` — __ (à confirmer).

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — après déploiement AWS :

1. `/npcs` → « ↻ Citizens » puis « ↻ Catalogue RPGQuest ».
2. Vérifier que **Stan** apparaît, libellé « Stan », sous-titre `Citizens #7`, badges
   « sans fiche RPGQuest » + « non lié ».
3. Filtre **Non liés** → Stan présent ; filtre **Liés** → Stan absent.
4. Recherche « Stan », « 7 », `5e081a06` → Stan trouvé.
5. Détail : UUID `5e081a06-6596-47a3-b769-aef5fcd9e676`, « Présent en jeu : non ».
6. Vérifier qu'**aucun autre PNJ n'a disparu** (6 orphelins + guard + woodcutter_bob + Stan
   = 9 lignes, compteur « 9 PNJ »).
7. `/diagnostics` → un INFO « PNJ du jeu sans fiche RPGQuest » pour Stan (pas une erreur).

## Résultat attendu

Tout PNJ présent dans le registre Citizens est visible dans `/npcs`, qu'il ait ou non une
fiche RPGQuest, une liaison, un dialogue, une quête ou un rôle. Un PNJ Citizens libre est
listé sous **Tous** et **Non liés**, cherchable par nom / id numérique / UUID, et
rattachable en un clic (créer une fiche, ou lier à une fiche existante).

## Reset / retour à l'état initial

`git revert` du/des commit(s) de la branche, ou `scripts/plugadmin/rollback.sh app`.
Aucune donnée à nettoyer (fix de rendu only).

## Déploiement VeryGames

### À transférer

**Rien vers VeryGames.** Control Panel AWS uniquement (`scripts/plugadmin/deploy.sh`).

### Ne PAS transférer/altérer

Le JAR RPGQuest du serveur, `data.db`, `plugins/Citizens/saves.yml`, les mondes, la config.
Aucun redémarrage Minecraft.

### Redémarrage requis

`systemctl restart plugadmin` uniquement (fait par `deploy.sh`). VeryGames : **non**.

### Migration automatique

Aucune.

## Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente sous
`/opt/plugadmin/releases/`). Aucune migration à défaire.

## Logs / diagnostic

Payload DEV réel utilisé pour la preuve (lecture seule de `control-panel.db`) :
`npc.citizens.list` action `7d394d2d-3171-4be9-a8ca-10b404c927a3` (2026-09-09 11:33) —
`#7 Stan`, `linkedNpcId:null`, `availableForBinding:true`, `spawned:false`,
`uuid:5e081a06-6596-47a3-b769-aef5fcd9e676`. `npc.list` action
`603a3938-cd66-4618-aa33-4b20cafaaec7` (2026-09-09 11:36) — aucune ligne `stan`.

## Documentation mise à jour

`NPC_FORMAT.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/control-panel/ROADMAP.md`, `docs-site/npc.html`,
`control-panel/src/main/resources/docs/pnj-depannage.md`,
`docs/deployment/SERVER_CHANGELOG.md`.

## Limitations / travail restant

* Rattacher un PNJ Citizens libre à une fiche RPGQuest reste en **deux temps** si on passe
  par « Créer une fiche » (création puis liaison) — cohérent avec le flux existant des
  PNJ orphelins ; « Lier à une fiche existante » le fait en une action.
* `NpcCatalog` (`npc.list`) reste volontairement aveugle au registre Citizens ; la
  visibilité des PNJ libres est portée par le Control Panel. Une éventuelle unification
  (roster injecté côté agent) est un chantier séparé, non nécessaire ici.
* Position / monde d'un PNJ Citizens libre non affichés (l'action `npc.citizens.list`
  ne les expose pas encore — hors périmètre de ce fix).

## Prochaine étape suggérée

Validation live après déploiement (checklist ci-dessus), puis fermeture de #101 par
l'owner. Envisager, si utile, d'ajouter monde + position au payload `npc.citizens.list`
(#81 / #66) pour enrichir la ligne des PNJ libres.
