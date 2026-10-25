# Missions de test en jeu — audit et architecture proposée (#819)

> **Statut : PROPOSITION — rien n'est développé.** Ce document est le livrable de la première étape de
> #819 (« auditer l'existant et proposer une architecture »). Les décisions marquées **[D1]…[D7]**
> attendent l'accord du propriétaire avant le premier lot de code. Aucune intégration GitHub ou
> Discord, aucun token, webhook, rôle ou dépôt n'a été créé, et aucune récompense n'a été remise.

## 1. Objectif (rappel de #819)

PlugAdmin crée une mission → choix du joueur et de la récompense → publication dans un espace GitHub
de test → annonce Discord `#missions-de-test` (mention `@Testeur` facultative) → le joueur fait le test
**uniquement dans Minecraft** (ni OP, ni GitHub, ni PlugAdmin, ni Discord) → retour du testeur →
validation par un administrateur → récompense **automatique et unique**.

Exigences transverses : interface simple (13 ans), confidentialité des joueurs (mineurs), historique
complet, protection contre les doubles récompenses, intégrations externes désactivées par défaut.

## 2. Audit de l'existant (dépôt au 25/10/2026, branche d'intégration `a98e9ec0`)

| Besoin | Existant réutilisable | Écart |
| --- | --- | --- |
| Choisir un joueur | `player.list` / `player.catalog` (agent), recherche par pseudo partiel (#788), identité UUID partout | aucun |
| Remise unique d'objets / avantages | **Droits à récompense #422** (`entitlement/reward/RewardEntitlementService`, table `reward_entitlements`, V41) : clé d'idempotence, `PENDING → DELIVERED` conditionnel, remise tout ou rien via `ItemDelivery` (#318), reprise à la connexion, catalogue `entitlements.yml` édité depuis PlugAdmin (#474) | source `MISSION` à ajouter à `RewardSource` ; le catalogue ne contient **que des objets et des avantages** (`perk`), pas de pièces ni d'XP |
| Pièces LodyCoins | `EconomyService.credit` (journal `transactions`) ; dettes de quête idempotentes `quest_reward_grants` (V25/V26, `payQuestReward(grantId)`) | `credit` seul **n'est pas idempotent** (un retry crédite deux fois) → passer par une ligne de remise à clé unique, comme `quest_reward_grants` |
| XP RPG | `ProgressionService.awardXp(…, eventId)`, table `xp_grants` à clé primaire `(joueur, compétence, event_id)` → idempotent par construction | soumis au plafond anti-farm (`THROTTLED`) : à contourner explicitement pour une récompense validée, ou à laisser hors MVP |
| Message au joueur, en ligne ou à la connexion | `OperatorNoticeService` (#316, message privé + un seul rappel à la connexion), `ActionBarFeedback` / `QuestTitleQueue` (#817) | aucun |
| Interface en jeu sans OP | Paper Dialog API déjà utilisée (`dialogue/render/PaperDialogRenderer`, `travel/PaperDialogWildEntryPromptPresenter`) ; livre de quêtes `ui/QuestJournal*` | nouvelle commande joueur `/missions` (permission par défaut `true`) |
| Commandes PlugAdmin → plugin | Actions agent whitelistées (`AgentActionType` ↔ `AgentActionCatalog` ↔ `AgentActionExecutor`), table panel `agent_action`, résultat JSON relu | nouvelles actions `mission.*` |
| Droits PlugAdmin | `panel.authz.Permission` / `Role` / groupes (`panel_group_permission`) | nouveaux droits distincts création / validation |
| Historique | `panel.audit.AuditLog` (table `audit_log`) côté panel ; horodatages et `attempts`/`last_error` côté droits #422 | journal d'événements de mission côté plugin |
| Notifications PlugAdmin | `NotificationCenter` (#93) | « mission à valider » à y brancher (phase 2) |
| GitHub | `gh` CLI sur l'hôte AWS (usage interne des sessions) ; **aucun client GitHub dans le panel** | adaptateur à écrire |
| Discord | **rien** dans le code | adaptateur webhook à écrire |

Constats GitHub vérifiés en lecture seule (`gh api`) le 25/10 :

- le dépôt `ReC82/Minecraft-Quest-Plugin` est **public** et a les **Discussions désactivées** ;
- aucun label `tests-in-game` (seul `needs-manual-test` existe).

## 3. Architecture proposée

### 3.1 Qui possède quoi

```
PlugAdmin (AWS)                                   Plugin RPGQuest (VeryGames)
───────────────                                   ──────────────────────────
Page « Tests joueurs »                            Table test_missions (+ test_mission_events)
  créer / assigner / valider ──agent mission.*──▶   état de la mission, retour du joueur
  tableau (mission.list)     ◀──résultat JSON───    remise unique de la récompense
Boîte d'envoi externe (panel DB)                  /missions en jeu (Dialog) + rappel à la connexion
  publication GitHub / annonce Discord
  secrets côté panel uniquement
```

- **[D1] Le plugin est la source de vérité de la mission** (assignation, retour, validation,
  récompense) : le joueur interagit en jeu quand PlugAdmin n'est pas là, et la remise doit se faire dans
  la même base que les portefeuilles et les droits #422. Le panel lit par `mission.list`.
- **Le panel possède seulement la publication externe** (GitHub, Discord) : les secrets restent sur
  AWS, jamais dans le plugin ni sur VeryGames.

### 3.2 Modèle de données plugin — migration ADDITIVE V45 (**[D2]**)

`test_missions` : `mission_id` (clé, ex. `M-0007`), `title`, `steps` (texte, ≤ 10 lignes courtes),
`expected`, `issue_ref` / `tc_ref` facultatifs (lien vers #NNN / TC-xxx), `visibility`
(`ASSIGNED` | `PUBLIC`), `player_uuid` (nullable pour une mission publique pas encore prise),
`reward_kind` (`CATALOG` | `COINS`), `reward_key` / `reward_amount`, `status`, `created_by`,
`approved_by`, `feedback_outcome`, `feedback_text` (≤ 256 caractères), `version`, horodatages.

`test_mission_events` : journal en ajout seul (`mission_id`, `at`, `actor` = utilisateur panel ou
UUID joueur, `kind`, `detail`) → **historique complet** sans jamais réécrire une ligne.

Aucune suppression, aucun `ALTER` destructif ; sauvegarde de `data.db` avant le déploiement, comme
pour #739.

### 3.3 États

| Code | Libellé PlugAdmin | Transition entrante (UPDATE conditionnel sur l'état précédent) |
| --- | --- | --- |
| `DRAFT` | À préparer | création |
| `ASSIGNED` | Assignée | admin : assigner (ou nouveau test demandé) |
| `IN_PROGRESS` | En cours | joueur : « Je commence » |
| `SUBMITTED` | À valider | joueur : « Terminé » / « Bloqué » / « Problème rencontré » + retour court |
| `APPROVED` | Validée | admin avec le droit de validation, **jamais sur simple déclaration** |
| `REWARDED` | Récompensée | remise effective constatée (droit #422 `DELIVERED` ou pièces payées) |
| `BLOCKED` | Bloquée | admin : bloquer (bug à corriger avant de retester) |
| `CANCELLED` | Annulée | admin, avant `APPROVED` seulement |

`SUBMITTED → ASSIGNED` = « refaire le test » (nouvelle tentative numérotée, l'ancienne reste au
journal). Chaque transition est un `UPDATE … WHERE mission_id = ? AND status = ? AND version = ?` :
deux clics, deux administrateurs ou deux processus ne peuvent pas passer tous les deux.

### 3.4 Récompense unique — protocole d'idempotence (**[D3]**)

- **Objet / avantage du catalogue** : à l'approbation, déclaration d'un droit #422 de source
  `MISSION`, clé `mission:<mission_id>:<uuid>` (même transaction logique que `SUBMITTED → APPROVED`).
  Remise, reprise à la connexion, inventaire plein, crash : **déjà traités** par #422. La mission passe
  `REWARDED` quand le droit est `DELIVERED`.
- **LodyCoins** : ligne à clé unique (`mission:<mission_id>`) + crédit **dans la même transaction**,
  sur le modèle de `quest_reward_grants` ; jamais `economy.credit` nu.
- **XP RPG** : hors MVP (plafond anti-farm à arbitrer) ; `xp_grants` le rendra idempotent plus tard.
- Plafond par mission dans `config.yml` (via `ConfigFileCompleter`, jamais d'envoi du fichier) ;
  au-delà, refus avant toute écriture. Récompenses de type permission/OP/bypass **interdites**.
- Rien n'est remis tant que `entitlements.yml` est `enabled: false` : le droit reste `PENDING`,
  visible dans PlugAdmin, et part à l'activation.

### 3.5 En jeu (testeur, sans OP)

- `/missions` (et un rappel cliquable à la connexion tant qu'une mission est `ASSIGNED`) ouvre un
  Dialog Paper : titre, étapes numérotées, « ce qui devrait se passer », puis quatre boutons :
  **Je commence**, **Terminé**, **Bloqué**, **Problème rencontré**. Les trois derniers demandent une
  phrase courte (champ texte du Dialog, 256 caractères au plus, MiniMessage échappé).
- Aucun lien GitHub ou Discord n'est nécessaire ; aucun identifiant technique affiché.
- Phase 2 : onglet « Missions » dans le livre (`JournalTab`).

### 3.6 PlugAdmin « Tests joueurs »

- Tableau à colonnes = états du §3.3, filtres joueur / ticket / TC.
- Formulaire de création en une page : titre, étapes (une par ligne), résultat attendu, ticket et TC
  facultatifs, joueur (recherche #788), récompense (liste du catalogue ou montant), « publier sur
  GitHub » et « annoncer sur Discord » (cases, désactivées tant que l'intégration n'est pas configurée).
- Avertissement si une mission sur le même `tc_ref` est déjà `REWARDED` (« test déjà clos », #819).
- **Droits distincts** : `MISSION_READ`, `MISSION_WRITE` (créer, assigner, bloquer),
  `MISSION_APPROVE` (valider, demander un nouveau test). Anti-auto-validation : le validateur doit être
  différent du créateur, sauf rôle Propriétaire ; confirmation avant validation ; tout passe à
  `audit_log`.

### 3.7 GitHub (**[D4]**, désactivé par défaut)

- **Recommandation : un dépôt public dédié** (ex. `ReC82/LodyQuests-tests`) **avec des Issues**
  étiquetées `tests-in-game`, plutôt que le dépôt du code (public, bruit dans le backlog) ou les
  Discussions (désactivées, API GraphQL seulement, catégorie à créer).
- Contenu publié : numéro de mission, titre, étapes, résultat attendu, état public, lien vers le ticket
  du code. **Jamais** : pseudo d'un testeur assigné, UUID, âge, IP, inventaire, e-mail, récompense
  nominative.
- Référence stable : numéro d'issue mémorisé côté panel + marqueur caché `<!-- lq-mission:M-0007 -->`
  dans le corps ; avant toute création, recherche du marqueur → un retry ne crée jamais deux issues.
- Secret : jeton *fine-grained* limité à ce seul dépôt, droit `Issues: read/write` uniquement, dans un
  fichier d'environnement ignoré du panel (comme `~/.config/plugadmin/smtp.env`).
- Échec réseau → la mission continue en jeu, la publication reste « à republier » (boîte d'envoi avec
  `attempts` / `last_error`, bouton « Réessayer »).

### 3.8 Discord (**[D5]**, désactivé par défaut)

- **Webhook du salon `#missions-de-test`**, URL secrète dans le même fichier d'environnement.
- Envoi avec `?wait=true` → l'identifiant du message est mémorisé ; la clôture **modifie** ce message
  (`PATCH …/messages/<id>`) au lieu d'en poster un autre → pas de spam ni de doublon après un retry.
- `allowed_mentions` toujours explicite : aucune mention par défaut ; `@Testeur` seulement si la case
  est cochée **et** l'identifiant du rôle configuré ; jamais `@everyone`.
- Bouton/lien GitHub seulement après une publication GitHub réussie.
- Mission `ASSIGNED` : annonce anonyme (« une mission de test est en cours »), sans pseudo.
- Le rôle **Testeur** (lecture du salon, réactions/fils éventuels, aucun droit de modération) est
  **créé et attribué à la main par le propriétaire** au MVP : un webhook ne sait pas gérer de rôles.
  L'automatisation (bot + liaison Minecraft ↔ Discord avec consentement) reste en phase ultérieure.

## 4. Découpage proposé

| Lot | Contenu | Testable par | Dépendances |
| --- | --- | --- | --- |
| **1 — plugin** | V45 additive, `MissionService`, transitions conditionnelles, journal, source `MISSION` #422, pièces idempotentes, actions agent `mission.create/assign/list/review/block/cancel`, `/missions` Dialog + rappel connexion ; tests unitaires et d'intégration (double clic, deux validations, crash entre prise et remise, joueur hors ligne) | Minecraft (testeur) + agent | [D1] [D2] [D3] |
| **2 — PlugAdmin** | page « Tests joueurs » (tableau, création, fiche, validation), droits `MISSION_*`, anti-auto-validation, audit | PlugAdmin | lot 1 |
| **3 — GitHub** | adaptateur Issues derrière une interface, faux client en test, boîte d'envoi, reprise ; **désactivé** tant que [D4] n'est pas fourni | tests automatiques puis propriétaire | lot 2, [D4] |
| **4 — Discord** | adaptateur webhook, modification du message à la clôture, mentions contrôlées ; **désactivé** tant que [D5] n'est pas fourni | tests automatiques puis propriétaire | lot 3, [D5] |
| 5 — plus tard | missions publiques avec volontaires, onglet livre, XP, notification panel, automatisation du rôle | — | [D6] [D7] |

## 5. Décisions et paramètres attendus du propriétaire

- **[D1]** Mission stockée dans la base du plugin (recommandé) — ou dans la base du panel.
- **[D2]** Accord pour la migration **additive** V45 (`test_missions`, `test_mission_events`).
- **[D3]** Types de récompense du MVP : objet/avantage du catalogue + LodyCoins (recommandé), XP plus
  tard ; plafond par mission (proposition : 500 pièces) ; qui peut valider (proposition : Propriétaire
  et Administrateur, jamais le créateur sauf Propriétaire).
- **[D4]** Espace GitHub : dépôt dédié (nom ?) ou dépôt actuel avec label ; puis, le moment venu,
  un jeton fine-grained limité à ce dépôt (déposé par le propriétaire dans le fichier d'environnement,
  jamais transmis en clair dans un ticket).
- **[D5]** URL du webhook `#missions-de-test` et identifiant du rôle `Testeur` (créés à la main).
- **[D6]** Missions publiques (appel à volontaires) dans le MVP ou plus tard.
- **[D7]** Récompenses réelles : rester sur DEV avec `entitlements.yml` désactivé jusqu'à un accord
  explicite.
