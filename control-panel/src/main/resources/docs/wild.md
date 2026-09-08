---
title: Wild — accès, Rune / Pierre de rappel, tests
category: Wild
tags: [wild, rune, rappel, pierre, retour, portail, worldportal, hub, test]
order: 1
---

# Wild — accès et retour

Le **Wild** (`wild`) est le monde de risque. On y entre par un **World-Portal** depuis le Hub ;
on en revient par la **Rune de rappel** (ou, depuis le monde des claims, la **Pierre de retour**).

Référence : `docs/TRAVEL.md`.

## Entrer dans le Wild

- Par le **World-Portal** `world_hub → wild` : un panneau d'avertissement s'affiche à l'entrée
  (mécanique « avertissement avant entrée dans le Wild ») ; le joueur confirme pour être
  téléporté (stratégie d'arrivée `RANDOM_SAFE` autour du spawn du monde).
- Diagnostic des portails :

```
/rpgadmin worldportal list
/rpgadmin worldportal here                 # tous les portails dont la zone couvre ma position
/rpgadmin worldportal debug showall        # visualise les zones (particules), aucun bloc modifié
/rpgadmin worldportal info <id>
```

## Revenir au Hub

- **Rune de rappel** (`rpgquest:rune_rappel`) : remise à **chaque joueur dès sa première
  connexion**. Clic droit **dans le Wild** → canalisation (~10 s) → retour au Hub. Refus bref si
  utilisée hors du Wild. Objet permanent, jamais consommé, filet anti-perte (drop annulé,
  conservé à la mort).
- **Pierre de retour** (`rpgquest:pierre_retour`) : équivalent pour le monde **des claims** (pas
  le Wild). Donnée par le filet de sécurité du monde des claims.
- **Waystones** : structures générées paresseusement dans le Wild, découverte individuelle par
  joueur, retour au Hub par canalisation.

## Reconnexion depuis le Wild (issue #87)

Un joueur qui se **déconnecte dans le Wild se reconnecte dans le Wild**, à sa position (ou une
position sûre très proche). Il n'est renvoyé au Hub que si son monde précédent n'existe plus.
Log serveur à la connexion : `join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION`.
Le retour Hub « gratuit » par logout/login n'existe plus.

## Tests utiles

- **Non-OP** : un OP n'a pas l'avertissement d'entrée ni les gardes de monde.
- Vérifier que la Rune est bien reçue à la 1re connexion : `player.variable.get <joueur>
  RUNE_RAPPEL_GRANTED` (Control Panel).
- Entrer dans le Wild, relever `world` + `x/y/z` (F3), se déconnecter/reconnecter → doit revenir
  `wild`, même position, sans passer par le Hub (test #87).
- Ne pas mélanger un test de reconnexion avec un test de mort, de Rune, ou de combat.

## Config (redémarrage requis pour ces champs)

- `travel.wild-world` : nom du monde Wild (défaut `wild`).
- `travel.random-safe-arrival` : `min-radius` / `max-radius` / `max-attempts` autour du spawn du
  monde pour l'arrivée `RANDOM_SAFE`.
- `channelSeconds` de la Rune / Pierre : lus au démarrage.
