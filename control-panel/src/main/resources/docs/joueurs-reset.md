---
title: Reset d'un joueur (deux portées)
category: Joueurs / Reset
tags: [reset, resetnew, resetfull, joueur, player, confirm, preview, op, deop, onboarding, kit, inventaire]
order: 1
---

# Reset d'un joueur

Remet l'état **RPGQuest** d'**un seul** joueur dans l'équivalent d'un joueur qui n'a jamais joué,
pour rejouer tout l'onboarding : Story → `CLAIM_TIER_1` → Jo → Acte de propriété → claim → Wild →
Waystones / Rune de rappel.

Référence : `docs/ADMIN_PLAYER_RESET.md`.

## Deux portées, et la différence est l'inventaire

Depuis l'issue #235, il y a **deux** resets au lieu d'un seul, parce qu'un seul produisait un état
incohérent.

| | Reset **progression** | Reset **nouveau joueur complet** |
|---|---|---|
| Données RPGQuest | réinitialisées | réinitialisées |
| Droit au kit de départ | rétabli | rétabli |
| Palier de kit | retour au palier 1 | retour au palier 1 |
| Inventaire Minecraft | **conservé** (seuls les objets RPGQuest sont retirés) | **vidé** |
| Armure, main secondaire, curseur | conservés | vidés |
| Coffre de l'Ender | conservé | vidé |
| Permission panel | `ACTION_PLAYER_RESET` | `ACTION_PLAYER_RESET_FULL` |

> [!WARNING]
> **Le reset de progression rétablit le droit au kit sans retirer le kit déjà reçu.** Le joueur peut
> donc en demander un **second**. Ce n'est pas un défaut : les outils du kit sont des objets
> **vanilla** (`WOODEN_PICKAXE`…), rigoureusement indiscernables de ceux que le joueur a fabriqués.
> Les retirer « intelligemment » détruirait ses propres outils. Pour un état réellement neuf,
> utilisez le **reset complet**.

## Commande (console ou en jeu)

```
/rpgadmin player resetnew <joueur>             # avertissement, NE FAIT RIEN
/rpgadmin player resetnew <joueur> preview     # dry-run : liste ce qui serait effacé
/rpgadmin player resetnew <joueur> confirm     # exécute — inventaire CONSERVÉ

/rpgadmin player resetfull <joueur> preview    # dry-run de la portée complète
/rpgadmin player resetfull <joueur> confirm    # exécute — inventaire, équipement et Ender VIDÉS
```

- Le mot **`confirm`** en dernier argument est **obligatoire** — sans lui, la commande ne fait
  qu'afficher la liste de ce qui serait effacé.
- **Permission** : `rpgquest.admin.world` (comme toutes les sous-commandes `/rpgadmin`).
- **Cible** : toujours **un seul** joueur, par son pseudo. En ligne **ou** hors ligne.

## Console vs en jeu

| | Console serveur | En jeu (joueur OP / `rpgquest.admin.world`) |
|---|---|---|
| `/rpgadmin player resetnew …` | oui (préfixe sans `/` selon la console) | oui |
| `/rpgadmin player resetfull …` | oui | oui |
| Cible d'un autre joueur | oui | oui |
| Se cibler soi-même | non applicable | oui |

`resetnew` et `resetfull`, comme `/rpgadmin story …` et `/rpgadmin player variable …`, sont
**utilisables depuis la console** (ne demandent pas que l'exécutant soit en jeu).

## Preview / dry-run

`preview` affiche, catégorie par catégorie, ce qu'un reset réel effacerait, **sans aucune
écriture**. Catégories : Quêtes · Stories · Variables / unlocks · `CLAIM_TIER_1` · **Droit au kit de
départ** · **Palier de kit** · Progression RPG · Waystones · Cooldowns de portails · Cooldowns de
voyage par objet (Rune…) · Claim principal · Inventaire.

L'aperçu est propre à la portée demandée : celui du reset complet compte **tout** l'inventaire
(Ender inclus) et annonce le vidage, celui du reset de progression ne compte que les objets
RPGQuest et rappelle qu'un kit déjà reçu restera en place.

- Joueur **hors ligne** : l'inventaire est marqué « non applicable » — il est nettoyé
  automatiquement à la prochaine connexion, **avant** la redistribution du kit de départ.
- Une catégorie déjà vide est affichée comme « rien à réinitialiser ».

## Online / offline (reset réel)

| | En ligne | Hors ligne |
|---|---|---|
| Suppressions en base | immédiates | immédiates |
| Caches mémoire | invalidés/rechargés tout de suite | rechargés au prochain login |
| Inventaire | appliqué immédiatement | différé (marqueur `__pending_new_player_reset__`, qui porte la **portée** et est consommé au login) |

## Depuis le Control Panel

Page **Joueurs** → bouton **Resets joueur (2 portées)**. Chaque portée a son propre aperçu et sa
propre confirmation, et chacune énumère ce qu'elle **conserve** autant que ce qu'elle efface :

- `player.resetnew.preview` → `player.resetnew.confirm` (inventaire conservé) ;
- `player.resetfull.preview` → `player.resetfull.confirm` (inventaire vidé).

L'agent exige `confirm=true` en plus de la case à cocher du formulaire, dans les deux cas. Voir aussi
`player.variable.get` / `player.variable.set` pour inspecter/forcer une variable
(`CLAIM_TIER_1`, `tutorial_started`, `RUNE_RAPPEL_GRANTED`…).

## OP / deop pour les tests

Pour tester l'onboarding **comme un vrai joueur**, il faut un compte **non-OP** : un compte OP
contourne les gardes de monde (`rpgquest.admin.world`) et ne reçoit ni la Pierre de retour du
filet de sécurité des claims, ni le blocage d'entrée du Wild.

```
deop <pseudo>     # depuis la console, pour tester non-OP
op <pseudo>       # rendre les droits admin ensuite
```

> [!NOTE]
> Aucune des deux portées ne touche **jamais** `data.db` entier ni un autre joueur : elles
> suppriment uniquement les lignes du joueur ciblé. Les récompenses déjà distribuées ne sont pas
> « reprises » — seul l'état de progression est remis à zéro.
