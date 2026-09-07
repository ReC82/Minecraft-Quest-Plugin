# `/rpgadmin player resetnew` — reset admin « nouveau joueur »

Outil d'administration/test pour remettre l'état **RPGQuest** d'**un seul** joueur dans
l'équivalent fonctionnel d'un joueur qui n'a jamais joué sur le serveur — afin de pouvoir refaire
tout le parcours d'onboarding : **Story → `CLAIM_TIER_1` → Jo → Acte de propriété → création du
claim → Wild → Waystones / Rune de rappel**.

Implémentation : `player.PlayerResetService` (+ `player.NewPlayerResetJoinListener` pour le
nettoyage d'inventaire différé). Réutilise les resets déjà existants
(`QuestProgressEngine#resetAllQuests`, `StoryService#reset("all")`,
`WaystoneService#resetDiscoveries`, `ClaimService#resetTierOneClaimForTesting`) et n'ajoute que les
suppressions par joueur qui manquaient (variables, progression RPG, cooldowns persistants).

## Commande

```
/rpgadmin player resetnew <joueur>            # affiche un avertissement, ne fait rien
/rpgadmin player resetnew <joueur> preview    # dry-run : liste ce qui serait effacé, sans rien modifier
/rpgadmin player resetnew <joueur> confirm    # exécute le reset
```

- **Permission** : `rpgquest.admin.world` (la même que toutes les sous-commandes `/rpgadmin`).
- **Protection anti-erreur** : le mot `confirm` en dernier argument est **obligatoire** (même
  esprit que `/rpgadmin flatten confirm`). Sans lui, la commande affiche uniquement la liste de ce
  qui serait effacé.
- **Console** : utilisable depuis la console du serveur (comme `/rpgadmin story ...`).
- **Cible** : toujours **un seul** joueur, désigné par son pseudo.

## Preview / dry-run — `preview`

`/rpgadmin player resetnew <joueur> preview` affiche, catégorie par catégorie, ce qu'un reset réel
effacerait pour ce joueur, **sans effectuer aucune écriture** : aucune suppression en base, aucun
marqueur `__pending_new_player_reset__` posé, aucun cache mémoire invalidé, aucun objet retiré de
l'inventaire. Utile pour vérifier la cible avant de lancer un `confirm`.

- **En ligne ou hors ligne** : toutes les catégories persistantes sont lues dans les deux cas.
  L'**inventaire** n'est comptabilisé que si le joueur est **en ligne** ; hors ligne, la catégorie
  est affichée comme « non applicable » (l'inventaire sera nettoyé au prochain login).
- Une catégorie **déjà vide** est affichée comme « rien à réinitialiser » plutôt que masquée.
- Implémentation : `PlayerResetService#previewReset(UUID)` → `ResetPreview` (liste de
  `ResetCategory` : `label`, `count`, `detail` ; `count == -1` = non inspectable, `count == 0` =
  inspectée mais vide). Réutilise la logique de collecte existante (mêmes services que le reset
  réel) via des lectures pures : `QuestProgressEngine#allStates`, `StoryService#progressRecords`,
  `PlayerVariableRepository#findAllForPlayer`, `ProgressionRepository#findAll`,
  `PortalCooldownRepository#allForPlayer`, `ItemTravelCooldownRepository#allForPlayer`,
  `WaystoneService#discoveryCount`, `ClaimService#claimsOwnedBy` / `#hasClaimTierOne`,
  `PlayerResetService#countRpgItems`.

Catégories affichées : Quêtes · Stories · Variables / unlocks · Déblocage `CLAIM_TIER_1` ·
Progression RPG · Découvertes de Waystones · Cooldowns de portails · Cooldowns de voyage par objet
(Rune…) · Claim principal · Inventaire (objets RPGQuest).

## Online / offline

| | Joueur **en ligne** | Joueur **hors ligne** |
|---|---|---|
| Suppressions en base | Immédiates | Immédiates |
| Caches mémoire (progression, cooldowns, quête suivie) | Invalidés/rechargés immédiatement | Rien à invalider ; rechargés au prochain login |
| Inventaire (objets RPGQuest) | Retirés **immédiatement** | **Différés** : nettoyés automatiquement à la prochaine connexion, **avant** la redistribution du kit de départ (marqueur persistant `__pending_new_player_reset__`, consommé par `NewPlayerResetJoinListener` en priorité `LOWEST`) |

L'inventaire **vanilla** n'est jamais vidé : seuls les objets identifiés comme objets personnalisés
RPGQuest (par PDC via `YamlCustomItemRegistry`, **jamais** par matériau) sont retirés.

## Données supprimées (RPGQuest, ce joueur uniquement)

- **Quêtes** : actives, progression d'objectifs, terminées, et la quête suivie
  (`quest_progress`, `quest_objective_progress`, variable `__tracked_quest__` + bossbar de suivi).
- **Stories** : toute progression (`story_progress`, mode `all`).
- **Variables joueur** : **toutes** (`player_variables`) — dont `CLAIM_TIER_1`, `RUNE_RAPPEL_GRANTED`
  (marqueur de kit de départ) et tout autre unlock.
- **Progression RPG** : niveaux / XP RPG (`player_skills`) et journal de déduplication des octrois
  (`xp_grants`).
- **Découvertes de Waystones** : `waystone_discoveries` de ce joueur.
- **Cooldowns persistants** : portails (`portal_cooldowns`) et voyage par objet / Rune de rappel
  (`item_travel_cooldowns`).
- **Claim principal** : la ligne `claims` du joueur (id `main_<uuid>`) et ses membres/trust
  (`claim_members`, cascade). La **zone physique construite n'est pas touchée** — après reset,
  l'ancienne zone n'est simplement plus un claim (plus de protection, plus de `mainClaimOf`).
- **Inventaire** : objets personnalisés RPGQuest (voir tableau online/offline).

## Données volontairement conservées

- **Compte Minecraft / UUID / `player_profiles` / playerdata vanilla** — jamais touchés.
- **Économie** : portefeuille et transactions (`wallets`, `transactions`) — hors parcours
  d'onboarding ; remettre à zéro via `/money` si besoin.
- **Backpacks / entitlements** (`player_entitlements`, `backpacks`, `backpack_overflow`,
  `backpack_audit`) — souvent liés à des achats ; gérer via `/backpack` si besoin.
- **Annonces de marché en cours** (`market_listings`) — contiennent de vrais objets en dépôt.
- **Blocs construits**, **Waystones globales déjà générées** (`waystones`), **mondes**, **PNJ
  Citizens**, **définitions de quêtes/Stories**, **portails**, **zones** — jamais modifiés.
- `data.db` n'est **jamais** wipé ; **aucun autre joueur** n'est modifié.

## Comportement du claim

Le claim disparaît **en tant que donnée** : plus de protection, plus de « rentrer chez soi » via
Jo, `CLAIM_TIER_1` re-verrouillé. Les blocs posés dans l'ancienne zone restent tels quels. Le
joueur peut immédiatement recommencer : obtenir `CLAIM_TIER_1` par la Story, récupérer l'Acte
auprès de Jo, et reposer un claim (y compris au même endroit).

## Ce qui se passe à la reconnexion

1. `NewPlayerResetJoinListener` (priorité `LOWEST`) voit le marqueur `__pending_new_player_reset__`
   (uniquement si le reset a été fait **hors ligne**), retire les objets RPGQuest de l'inventaire,
   efface le marqueur.
2. `StarterKitListener` (priorité `NORMAL`) voit que `RUNE_RAPPEL_GRANTED` est absent → redonne une
   Rune de rappel de départ.
3. Les moteurs (`QuestProgressEngine`, `StoryService`, `ProgressionService`, `PortalService`,
   `ItemTravelService`, `WaystoneService`) rechargent un état vide pour ce joueur.
4. Le Guide propose de nouveau la quête d'introduction ; Jo proposera l'Acte une fois `CLAIM_TIER_1`
   re-débloqué.

## Exemple exact — LoDyMcFly

```
/rpgadmin player resetnew LoDyMcFly
```
→ affiche l'avertissement listant ce qui sera effacé.

```
/rpgadmin player resetnew LoDyMcFly preview
```
→ dry-run : affiche l'en-tête (`en ligne`/`hors ligne`), la ligne
`Dry-run : aucune donnée n'a été modifiée.`, une ligne par catégorie (nombre + détail, ou
« rien à réinitialiser », ou « non applicable »), le rappel des données conservées, et enfin
`Pour exécuter réellement : /rpgadmin player resetnew LoDyMcFly confirm`. **Aucune donnée touchée.**

```
/rpgadmin player resetnew LoDyMcFly confirm
```
→ exécute le reset et affiche un résumé admin :
- `Reset « nouveau joueur » effectué pour LoDyMcFly (<uuid>)`
- liste des systèmes réinitialisés ;
- si en ligne : `Inventaire : N objet(s) RPGQuest retiré(s) maintenant (inventaire vanilla intact).`
- si hors ligne : `l'inventaire RPGQuest sera nettoyé automatiquement à sa prochaine connexion (avant le kit de départ).`
- rappel des données conservées volontairement.

LoDyMcFly peut ensuite se (re)connecter et recommencer le parcours depuis le tout début.

## Limitations

- **Répit de premier login vanilla** (`PlayerSpawnLocationEvent` / redirection vers le spawn du
  Hub, gérée par `SpawnService`) : elle ne se redéclenche pas, car ce reset ne touche pas au
  playerdata vanilla (`hasPlayedBefore()` reste vrai). L'important pour le parcours — quête
  d'introduction, `CLAIM_TIER_1`, kit de départ, dialogues — est bien remis à zéro.
- **Économie / backpacks / entitlements / annonces de marché** ne sont pas réinitialisés (voir
  « Données volontairement conservées »).
- Reset fait **hors ligne** : le nettoyage d'inventaire est différé au prochain login ; tant que le
  joueur ne s'est pas reconnecté, ses objets RPGQuest sont encore là (le résumé admin le dit
  explicitement — aucune donnée n'est présentée comme nettoyée alors qu'elle ne l'est pas).
- Les cooldowns de canalisation **en cours** (non persistés) d'un joueur en ligne ne sont pas
  interrompus par le reset ; ils se terminent/s'annulent normalement.
