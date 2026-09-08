---
title: Reset d'un joueur (« nouveau joueur »)
category: Joueurs / Reset
tags: [reset, resetnew, joueur, player, confirm, preview, op, deop, onboarding]
order: 1
---

# Reset d'un joueur

Remet l'état **RPGQuest** d'**un seul** joueur dans l'équivalent d'un joueur qui n'a jamais joué,
pour rejouer tout l'onboarding : Story → `CLAIM_TIER_1` → Jo → Acte de propriété → claim → Wild →
Waystones / Rune de rappel.

Référence : `docs/ADMIN_PLAYER_RESET.md`.

## Commande (console ou en jeu)

```
/rpgadmin player resetnew <joueur>            # affiche un avertissement, NE FAIT RIEN
/rpgadmin player resetnew <joueur> preview    # dry-run : liste ce qui serait effacé
/rpgadmin player resetnew <joueur> confirm    # exécute le reset
```

- Le mot **`confirm`** en dernier argument est **obligatoire** — sans lui, la commande ne fait
  qu'afficher la liste de ce qui serait effacé.
- **Permission** : `rpgquest.admin.world` (comme toutes les sous-commandes `/rpgadmin`).
- **Cible** : toujours **un seul** joueur, par son pseudo. En ligne **ou** hors ligne.

## Console vs en jeu

| | Console serveur | En jeu (joueur OP / `rpgquest.admin.world`) |
|---|---|---|
| `/rpgadmin player resetnew …` | oui (préfixe sans `/` selon la console) | oui |
| Cible d'un autre joueur | oui | oui |
| Se cibler soi-même | non applicable | oui |

`resetnew`, comme `/rpgadmin story …` et `/rpgadmin player variable …`, est **utilisable depuis
la console** (ne demande pas que l'exécutant soit en jeu).

## Preview / dry-run

`preview` affiche, catégorie par catégorie, ce qu'un reset réel effacerait, **sans aucune
écriture**. Catégories : Quêtes · Stories · Variables / unlocks · `CLAIM_TIER_1` · Progression
RPG · Waystones · Cooldowns de portails · Cooldowns de voyage par objet (Rune…) · Claim principal
· Inventaire (objets RPGQuest).

- Joueur **hors ligne** : l'inventaire est marqué « non applicable » — il est nettoyé
  automatiquement à la prochaine connexion, **avant** la redistribution du kit de départ.
- Une catégorie déjà vide est affichée comme « rien à réinitialiser ».

## Online / offline (reset réel)

| | En ligne | Hors ligne |
|---|---|---|
| Suppressions en base | immédiates | immédiates |
| Caches mémoire | invalidés/rechargés tout de suite | rechargés au prochain login |
| Inventaire (objets RPGQuest) | retirés immédiatement | différés (marqueur `__pending_new_player_reset__`, consommé au login) |

## Depuis le Control Panel

Page **Joueurs** : `player.resetnew.preview` (dry-run) puis `player.resetnew.confirm`
(l'agent exige `confirm=true` en plus de la confirmation du formulaire). Voir aussi
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
> `/rpgadmin player resetnew` ne touche **jamais** `data.db` entier ni un autre joueur : il
> supprime uniquement les lignes du joueur ciblé. Les récompenses déjà distribuées ne sont pas
> « reprises » — seul l'état de progression est remis à zéro.
