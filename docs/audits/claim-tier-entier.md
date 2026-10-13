# Passer de `CLAIM_TIER_<n>` à un entier `CLAIM_TIER` — préparation (issue #319)

**Statut : préparé, rien n'est migré.** Aucune donnée joueur n'est modifiée par ce document ni par le
code livré avec lui. La migration demande la validation du propriétaire (et n'est pas une migration
de schéma : ce sont des lignes de `player_variables`).

## Aujourd'hui

Une variable par palier, valeur exacte `"true"` (toute autre valeur = palier non débloqué) :

| Où | Rôle | Lecture / écriture |
|---|---|---|
| `claim.ClaimService.hasClaimTierOne` | accès au premier claim | lit `CLAIM_TIER_1 == "true"` |
| `claim.ClaimService.highestEntitledTier` | palier le plus haut (pose au bon palier) | lit `CLAIM_TIER_5` → `_1` |
| `claim.ClaimService` (reset palier 1, `/claim admin resettier1`) | remise à zéro | écrit `CLAIM_TIER_1 = "false"` |
| `claim.ClaimWorldAccessGuard`, `ClaimWorldSafetyListener` | accès au monde Claims, Pierre de retour | via `hasClaimTierOne` |
| `player.PlayerResetService` | resets joueur | efface toutes les variables (aperçu : « Déblocage CLAIM_TIER_1 ») |
| `quests/guard_tier1..5.yml`, `crystal_hunt.yml` | récompense `VARIABLE` | écrit `CLAIM_TIER_<n> = "true"` |
| `dialogues/jo.yml` (5 conditions), `guide.yml` | conditions `VARIABLE_EQUALS` | lit `CLAIM_TIER_1` |
| `admin.RpgAdminCommand` (`player variable`, complétion) | outil admin | lit / écrit librement |
| PlugAdmin « Lire / Écrire une variable » | outil de test | depuis #319 : `true` ou `false` seulement |

## Cible proposée

`CLAIM_TIER` entier : absent ou `0` = aucun palier, `1`…`5` = palier courant.

## Plan sans perte (à valider avant exécution)

1. **Lecture rétrocompatible** (sans écrire) : palier = max(`CLAIM_TIER` entier valide, plus haut
   `n` tel que `CLAIM_TIER_<n> == "true"`). Les deux formes donnent le même résultat ; un entier
   invalide est ignoré, jamais pris pour 0.
2. **Écriture double** pendant la transition : chaque récompense ou commande qui débloque un palier
   écrit `CLAIM_TIER` (seulement s'il augmente) **et** `CLAIM_TIER_<n> = "true"`. Une écriture ne
   fait jamais baisser le palier, sauf la remise à zéro explicite (qui écrit `0` et `false`).
3. **Contenu** : nouvelle condition `VARIABLE_AT_LEAST` (ou `CLAIM_TIER_AT_LEAST`) pour Jo et les
   quêtes ; les conditions `CLAIM_TIER_1 == "true"` restent valides tant que l'écriture double dure.
4. **Conversion des lignes existantes** (une fois 1–3 déployés) : tâche idempotente qui calcule le
   palier par la règle 1 et écrit `CLAIM_TIER` ; sauvegarde de `data.db` avant, journal ligne par
   ligne, relançable sans effet.
5. **Retour arrière** : tant que l'écriture double existe, revenir au JAR précédent ne perd rien
   (les `CLAIM_TIER_<n>` sont toujours à jour).
6. Seulement ensuite : retirer l'écriture des anciennes clés.

## Tests à prévoir

Absent, `false`, `true` sur chaque palier ; entier invalide (`lasagne`, `-1`, `9`) ; conflit
(`CLAIM_TIER = 1` mais `CLAIM_TIER_3 = "true"` → 3) ; reset ; redémarrage entre deux étapes ;
JAR précédent après conversion.
