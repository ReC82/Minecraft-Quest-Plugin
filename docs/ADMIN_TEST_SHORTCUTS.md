# Raccourcis d'administration / test — quêtes & stories (issue #36)

Outils **DEV / admin uniquement** pour atteindre rapidement un état de progression
précis sans rejouer tout le gameplay. Ils **réutilisent les services métier**
(`QuestProgressEngine`, `StoryService`) — jamais d'écriture directe dans `data.db`.

> Ce ne sont **pas** des mécaniques joueur. Une validation finale de bout en bout
> (vrai gameplay) reste nécessaire avant de considérer un parcours comme validé.

------------------------------------------------------------------------

## Permissions

| Permission | Défaut | Couvre |
|---|---|---|
| `rpgquest.admin.world` | `op` | tout `/rpgadmin`, dont `quest …`, `story advance\|complete`, `player variable get` |
| `rpgquest.admin.debug` | `op` | **en plus**, `/rpgadmin player variable set` (écriture bas niveau) |

Aucune de ces commandes n'est accessible à un joueur normal. Chaque opération
sensible est **journalisée** côté serveur (`[admin] <exécutant> : <commande> <cible> …`).

------------------------------------------------------------------------

## Commandes

Toutes utilisables **depuis la console** comme en jeu (la cible est passée en
argument, jamais « le joueur qui tape »). Tab-complétion sur les sous-commandes,
les joueurs **en ligne**, les `quest-id` et les `storyId`.

### Quêtes

```
/rpgadmin quest start    <joueur> <quest-id> [force]
/rpgadmin quest complete <joueur> <quest-id>
/rpgadmin quest reset    <joueur> <quest-id>
```

| Commande | Effet | Cible | Récompenses |
|---|---|---|---|
| `quest start` | Démarre la quête comme une acceptation normale (`QuestProgressEngine.accept`). Prérequis **respectés** par défaut ; `force` les ignore explicitement. Refuse un id inconnu, ne duplique pas une quête déjà active. | **en ligne** | — (l'acceptation ne donne jamais de récompense) |
| `quest complete` | Complète la quête sans simuler les objectifs (`QuestProgressEngine.forceComplete`). | **en ligne** | **appliquées normalement**, dont les récompenses `VARIABLE` (ex. `CLAIM_TIER_1=true`), `EXPERIENCE`, `ITEM`, `COMMAND`. **Une seule fois** : une quête déjà `COMPLETED` renvoie « déjà terminée », rien n'est re-crédité. |
| `quest reset` | Supprime la ligne de progression + les compteurs d'objectifs → la quête redevient rejouable (`QuestProgressEngine.resetQuest`). | **en ligne ou hors ligne** | **N'annule PAS** les récompenses déjà accordées — voir « Limites » ci-dessous. |

### Stories

```
/rpgadmin story advance  <joueur> <storyId>
/rpgadmin story complete <joueur> <storyId>
```

(les `info` / `start` / `reset` / `resetwithquests` existants sont inchangés)

| Commande | Effet | Cible |
|---|---|---|
| `story advance` | Fait progresser la story d'**exactement une étape** : (1) story `NOT_STARTED` → démarrée à l'index 0 ; (2) `forceComplete` de la quête courante de la story (récompenses appliquées **une fois**) ; (3) l'index avance → soit la story devient `COMPLETED`, soit la **quête suivante est acceptée automatiquement**. Le message indique **quelle étape reste à tester manuellement**. | **en ligne** |
| `story complete` | Enchaîne `story advance` jusqu'au bout, une quête à la fois, **dans l'ordre**. Chaque quête voit ses récompenses appliquées **une seule fois**. Borné (jamais de boucle infinie). | **en ligne** |

`story advance`/`complete` **ne touchent jamais** une quête qui n'est pas
référencée par la story ciblée.

### Variables joueur

```
/rpgadmin player variable get <joueur> <clé>
/rpgadmin player variable set <joueur> <clé> <valeur>
```

| Commande | Effet | Permission |
|---|---|---|
| `player variable get` | Lit `player_variables` (lecture pure). Une clé absente est signalée comme telle (équivaut à non définie). | `rpgquest.admin.world` |
| `player variable set` | Écrit la clé. **Outil bas niveau** : avertissement affiché + opération journalisée (ancienne + nouvelle valeur). Ne reproduit **pas** une progression de quête/story à lui seul. | `rpgquest.admin.world` **et** `rpgquest.admin.debug` |

Clés proposées en tab-complétion (indicatif, la saisie libre reste acceptée) :
`CLAIM_TIER_1`, `tutorial_started`, `crystal_hunt_started`, `woodcutter_reputation`,
`RUNE_RAPPEL_GRANTED`.

------------------------------------------------------------------------

## Comportement des récompenses (résumé)

- `quest complete` / `story advance` / `story complete` **appliquent** les
  récompenses de chaque quête complétée, y compris les `VARIABLE`
  (`CLAIM_TIER_1=true` est posée à la complétion de `crystal_hunt`).
- **Aucun double gain** sur répétition accidentelle : `forceComplete` renvoie
  « déjà terminée » pour une quête déjà `COMPLETED` et ne re-crédite rien.
- L'acceptation (`quest start`, ou l'auto-accept de la quête suivante par
  `story advance`) ne distribue **jamais** de récompense.

------------------------------------------------------------------------

## Limites d'un reset ciblé (`quest reset`)

`quest reset` remet **uniquement** la ligne de progression + les compteurs
d'objectifs de la quête ciblée. Il **ne peut pas** annuler proprement, de façon
générique, des effets déjà persistés ailleurs :

| Déjà accordé par une complétion précédente | Annulé par `quest reset` ? |
|---|---|
| Progression de la quête (état, étape, compteurs) | ✅ oui |
| XP (`EXPERIENCE`) | ❌ non |
| Objets (`ITEM`, `COMMAND` type « give ») | ❌ non |
| Variables (`VARIABLE`, ex. `CLAIM_TIER_1`) | ❌ non |
| Effets d'une `COMMAND` arbitraire | ❌ non |
| Progression de Story qui référence cette quête | ❌ non (utiliser `/rpgadmin story reset` ou `resetwithquests`) |

Pour repartir d'un état réellement propre :

- **remise à zéro complète d'un joueur** : `/rpgadmin player resetnew <joueur> confirm` ;
- **annuler une variable précise** : `/rpgadmin player variable set <joueur> <clé> false`
  (pour `CLAIM_TIER_1`, `false` ou absente sont équivalents — voir
  `ClaimService.hasClaimTierOne`) ;
- **rejouer une story et ses quêtes** : `/rpgadmin story resetwithquests <joueur> <storyId>`.

------------------------------------------------------------------------

## Workflow : tester rapidement une étape du parcours principal

Contexte : parcours `premiers_pas → first_steps → crystal_hunt` (story
`main_story`), dont la dernière quête accorde `CLAIM_TIER_1`. Rappel : pour un
joueur normal, `main_story` n'est **pas** démarrée automatiquement — la chaîne se
joue via les PNJ (Guide, Libraire, **Garde**). Ces raccourcis permettent de sauter
les étapes déjà validées.

### A. Tester `crystal_hunt` / Jo / portail Claims sans refaire le début

```
/rpgadmin player resetnew Rondoudou9000 confirm
# … tester manuellement Guide + Libraire si besoin …
/rpgadmin quest complete Rondoudou9000 rpgquest:first_steps
# clic droit sur le Garde en jeu -> il doit proposer « La chasse aux cristaux »
/rpgadmin quest complete Rondoudou9000 rpgquest:crystal_hunt
/rpgadmin player variable get Rondoudou9000 CLAIM_TIER_1        # -> true
# … tester Jo (« réclamer mon acte »), le portail Hub -> claims, la Pierre de retour …
```

### B. Piloter via la story (démarre la story si besoin, avance étape par étape)

```
/rpgadmin player resetnew Rondoudou9000 confirm
/rpgadmin story advance Rondoudou9000 main_story
#  -> « premiers_pas » complétée ; à tester : « first_steps » (étape 2/3)
/rpgadmin story advance Rondoudou9000 main_story
#  -> « first_steps » complétée ; à tester : « crystal_hunt » (étape 3/3)
/rpgadmin story advance Rondoudou9000 main_story
#  -> « crystal_hunt » complétée ; Story TERMINÉE ; CLAIM_TIER_1 posée
```

### C. Aller directement à la fin d'une story

```
/rpgadmin story complete Rondoudou9000 main_story
#  -> premiers_pas, first_steps, crystal_hunt complétées dans l'ordre ; CLAIM_TIER_1 posée
```

------------------------------------------------------------------------

## Voir aussi

- `docs/ADMIN_PLAYER_RESET.md` — `/rpgadmin player resetnew`
- `docs/storylines.md` — modèle Story
- `docs/NPC_DIALOGUES_QUESTS_GUIDE.md` — PNJ obligatoires du parcours principal
- `docs/CLAIMS.md` — déblocage du premier claim (`CLAIM_TIER_1`)
