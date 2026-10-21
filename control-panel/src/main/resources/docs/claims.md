---
title: Claims — obtenir un acte, tester, commandes admin
category: Claims
tags: [claim, claims, acte, deed, jo, tier1, test, wand, admin, resettier1, teleport, plugadmin]
order: 1
---

# Claims — parcours et tests

Deux façons d'obtenir un claim :

1. **Claim libre** — `/claim wand` puis `/claim create <id>` (tout joueur avec `rpgquest.claim`,
   hors zones protégées et hors monde Hub).
2. **Premier claim principal 5×5** — débloqué par la Story principale (`CLAIM_TIER_1`) puis
   l'**Acte de propriété** remis par le PNJ **Jo** au village.

Référence : `docs/CLAIMS.md`.

## Parcours « premier claim » (à tester non-OP)

1. Compléter la Story principale → variable `CLAIM_TIER_1` posée.
2. Parler à **Jo** (village) → il remet l'**Acte de propriété** (`rpgquest:acte_propriete`,
   objet permanent, jamais consommé, filet anti-perte).
3. Clic droit avec l'Acte à l'endroit voulu → crée le claim 5×5 centré sur le joueur.
4. Le monde des claims devient accessible ; le filet de sécurité donne une **Pierre de retour**
   (`rpgquest:pierre_retour`) pour revenir au Hub sans commande.

## Commandes joueur

```
/claim wand
/claim create <id>
/claim info
/claim list
/claim delete
/claim trust <joueur>
/claim untrust <joueur>
/claim flag redstone <true|false>
```

## Commandes admin (`rpgquest.admin.world`)

```
/claim admin resettier1 <joueur>     # remet CLAIM_TIER_1 + supprime le claim principal (test/QA)
/claim admin tp <joueur>             # téléporte un joueur à son claim principal (debug)
```

Voir aussi le reset joueur complet : fiche **Reset d'un joueur** (`/rpgadmin player resetnew`).

## Principes de test

- Toujours avec un compte **non-OP** : un OP contourne les protections de claim et le filet de
  sécurité du monde des claims (pas de Pierre de retour donnée).
- Vérifier l'état réel : `player.variable.get <joueur> CLAIM_TIER_1` (Control Panel, page
  Joueurs) et `/claim list` en jeu.
- Pour rejouer depuis zéro : `/rpgadmin player resetnew <joueur> confirm` (efface aussi le claim
  principal et `CLAIM_TIER_1`).

> [!NOTE]
> `rpgquest.admin.world` exempte l'acteur direct des protections d'un claim (utile pour réparer),
> jamais les autres joueurs. Le monde Hub interdit toute création de claim.

## Dans PlugAdmin : page Claims (issue #637)

**RPGQuest → Claims** liste tous les claims (bouton « Rafraîchir les claims », action
`claim.list`) : propriétaire, claim principal, monde, centre, taille, palier. Rechercher un
pseudo ou un UUID ; « Tous ses claims » depuis une fiche, ou la carte « Claims » de la fiche
joueur. Fonctionne aussi pour un joueur hors ligne.

**M'y téléporter** (fiche d'un claim) déplace **uniquement votre personnage lié** (page
Utilisateurs), jamais le propriétaire. Conditions :

1. votre personnage est connecté en jeu ;
2. il possède un claim, a débloqué `CLAIM_TIER_1`, ou vous avez tapé `/rpgadmin bypass on` —
   sinon le monde des claims vous renverrait au Hub, et la demande est refusée d'avance ;
3. le claim a une position sûre (sol solide, deux blocs d'air, ni lave ni vide).

Le résultat (« Dernière demande ») ne devient un succès qu'une fois votre présence sur le claim
relue par le serveur. La téléportation ne donne aucun droit sur le claim : pour vérifier les
protections, rester mode bypass **désactivé** (`/rpgadmin bypass off`) une fois arrivé.
