# Droits à récompense (entitlements) — spécification (#422)

Ticket enfant de #52 (étape 8 de la roadmap du portail, [WEB_PORTAL.md](WEB_PORTAL.md)). Ce document
est la **spécification** demandée par le ticket ; la tranche 1 décrite au §8 est livrée dans le plugin,
**désactivée par défaut**.

## 1. Vocabulaire

- **droit à récompense** (*reward entitlement*) : « tel joueur a droit à telle récompense, pour telle
  raison », déclaré **une fois** et remis **au plus une fois** par le plugin ;
- à ne pas confondre avec l'**avantage** persistant existant (`entitlement.EntitlementService`, table
  `player_entitlements`, palier de sac ou de boutique) : un avantage est un *état* (« palier MEDIUM ») ;
  un droit à récompense est un *événement de remise* (« 3 diamants pour le rang 1 »). Un droit peut
  accorder un avantage (§3, `perk`) : c'est alors `EntitlementService` qui l'applique ;
- **clé d'idempotence** : identifiant stable et obligatoire du droit. Deux déclarations de la même clé
  ne créent jamais deux droits.

## 2. Principes (repris de #52)

1. Le portail, un événement ou un administrateur **déclare** un droit `(source, référence, joueur UUID,
   récompense du catalogue)` ; **seul le plugin remet**, en jeu, sur le thread principal.
2. Le portail n'écrit **jamais** dans les tables de jeu et ne remet **jamais** d'objet. En V1, il n'a
   d'ailleurs aucun canal d'écriture vers le plugin (§7).
3. Aucun paiement, aucune boutique : la V1 est communautaire (concours, événements, administration).
4. Une remise est **tout ou rien** et réutilise la remise sécurisée commune #318
   (`item.delivery.ItemDelivery`) : inventaire plein = rien n'est remis, rien n'est jeté au sol, le droit
   reste en attente.
5. Jamais deux fois : livraison répétée, nouvelle tentative, crash, deux sessions du même joueur, deux
   processus sur la même base MariaDB.

## 3. Catalogue des récompenses — `plugins/RPGQuest/entitlements.yml`

Les événements (#57) désignent une récompense par une **clé interne** (`entitlement:` dans
`statistics.yml`). Le catalogue dit ce que cette clé remet.

```yaml
enabled: false            # interrupteur général ; absent = false
rewards:
  title_zombie_slayer:
    label: "Titre « Pourfendeur de zombies »"   # texte montré au joueur
    items:                                       # remis ensemble, tout ou rien
      - {item: DIAMOND, amount: 3}               # matériau Paper
      - {item: "rpgquest:return_stone", amount: 1}   # objet custom RPGQuest (PDC)
    perk: {key: title_zombie, tier: GOLD}        # facultatif : avantage via EntitlementService
```

Validation au chargement, fichier refusé **en entier** à la moindre erreur (comme `statistics.yml`) :
clé `[a-z0-9_]{1,48}`, `label` obligatoire, au moins un `items` ou un `perk`, `amount` 1–64 × 9 lignes
au plus, matériau existant et non-air, id custom namespacé (`rpgquest:…`) ; un id custom inconnu au
moment de la remise laisse le droit en attente avec l'erreur, il n'est jamais remplacé par autre chose.
Fichier absent ou invalide = fonction désactivée, plugin démarré normalement.

La récompense est **résolue à la remise**, pas figée à la déclaration : un administrateur peut corriger
une entrée du catalogue tant que le droit est en attente. Une clé inconnue du catalogue laisse le droit
`PENDING` avec l'erreur visible dans PlugAdmin.

## 4. Modèle de données — table `reward_entitlements` (schéma V41)

| Colonne | Rôle |
| --- | --- |
| `idempotency_key` | clé primaire, ≤ 160 caractères, `[A-Za-z0-9_:.-]` |
| `player_uuid` | destinataire (UUID, jamais le pseudo) |
| `source` | `EVENT`, `ADMIN`, `BOSS` (#176), `QUEST` (#177) (à venir : `SEASON`, `CONTEST`, `PROMO`, `VIP`) |
| `source_ref` | id de l'événement, référence administrateur… |
| `reward_key` | clé du catalogue |
| `label` | libellé figé à la déclaration (traçabilité) |
| `status` | `PENDING`, `DELIVERED`, `REVOKED` |
| `created_at`, `updated_at`, `delivered_at`, `revoked_at` | horodatages ISO-8601 UTC |
| `revoke_reason`, `last_error`, `attempts` | traçabilité des refus et des échecs |

Index `(status, created_at)` et `(player_uuid, status)`. Sans clé étrangère vers `player_profiles`
(comme `player_heart_grants`) : un profil recréé ne rouvre pas un droit consommé, un droit peut être
déclaré pour un joueur qui ne s'est pas encore reconnecté.

### Cycle de vie

```
            déclaration (INSERT OR IGNORE sur la clé)
                         │
                         ▼
   ┌──────────────── PENDING ────────────────┐
   │ révocation admin                        │ remise réussie
   ▼ (UPDATE … WHERE status = 'PENDING')     ▼ (UPDATE … WHERE status = 'PENDING')
REVOKED (terminal)                       DELIVERED (terminal)
```

- seules ces deux transitions existent, chacune par un `UPDATE` **conditionnel** dont le nombre de
  lignes dit qui a gagné : deux processus, deux sessions ou un double clic ne peuvent pas passer tous
  les deux ;
- un droit `DELIVERED` ne se révoque pas (les objets sont déjà dans un inventaire) ; un droit `REVOKED`
  ne se ré-active pas : on en déclare un autre, avec une autre clé ;
- un échec de remise ne change pas le statut : `attempts` + `last_error` seulement.

## 5. Remise — protocole

Le joueur doit être **en ligne** (les objets vont dans son inventaire). Toutes les 30 s, et 3 s après
chaque connexion, le service lit les droits `PENDING` des joueurs connectés (requête bornée, hors du
thread principal), puis pour chacun :

1. **thread principal** — garde mémoire par clé (un seul essai en vol par processus) ; récompense
   résolue dans le catalogue ; place vérifiée par `ItemDelivery.missingSlots` **sans rien modifier**.
   Pas la place → message « libère N emplacements » (au plus une fois toutes les 5 minutes par droit),
   le droit reste `PENDING` ;
2. **base** — `PENDING → DELIVERED` conditionnel (la « prise ») ; 0 ligne = déjà remis ou révoqué
   ailleurs, on s'arrête là ;
3. **thread principal** — `ItemDelivery.deliver` (tout ou rien). Réussite → message au joueur, puis
   avantage `perk` éventuel via `EntitlementService.grant` (idempotent : même palier = rien).
   Échec (joueur déconnecté ou inventaire changé pendant l'aller-retour base) → **compensation**
   `DELIVERED → PENDING` conditionnelle, `attempts + 1`, `last_error` : le droit sera retenté.

**Choix assumé — « jamais deux fois » plutôt que « jamais perdu »** : la prise précède la remise. Un
arrêt brutal du serveur **entre** l'étape 2 et l'étape 3 (un aller-retour de tick) laisse un droit
`DELIVERED` sans objet remis ; l'inverse (remettre puis marquer) donnerait deux fois après un crash.
C'est le même arbitrage que la fin de quête (#318). Le cas est visible (`delivered_at` sans message au
joueur) et se corrige en déclarant un nouveau droit (clé suffixée `:retry1`) depuis PlugAdmin.

## 6. Sources

### 6.1 Événements (#57) — lecture de l'archive figée

Le service ne lit **que** l'archive (`stat_period_archives`, `stat_period_results`), jamais le
classement live : une fois figé, le résultat ne bouge plus. Pour chaque événement **archivé**, non
annulé, présent dans `statistics.yml`, et pour chacune de ses `rewards` :

| Critère | Destinataires |
| --- | --- |
| `rank: N` | rang « compétition » ≤ N sur la **première métrique** de l'événement, ordre de sa règle de classement, ex-aequo inclus |
| `threshold: N` | valeur figée ≥ N |
| `community: true` | si la somme figée atteint `goal` : chaque contributeur (valeur > 0) |

Exclusions de modération de `statistics.yml` appliquées (comme aux classements) ; les profils
**privés** reçoivent leur récompense (la confidentialité règle la publication, pas le droit) ; les
valeurs nulles ne gagnent rien. Clé d'idempotence :
`event:<id événement>:<rank|threshold|community>:<N>:<clé catalogue>:<uuid>` — stable si le fichier
est réordonné, distincte si un même critère accorde deux récompenses. Re-lire l'archive (chaque minute,
au redémarrage) ne crée rien de plus.

### 6.2 Administration (PlugAdmin)

Action agent `entitlement.declare` : joueur connu, clé du catalogue, **référence** obligatoire
(→ clé `admin:<référence>:<uuid>`). Même référence deux fois = un seul droit.

### 6.3 Boss communautaires (#176)

Source `BOSS`, référence `<profil>@<uuid entité>`, clé `boss:<uuid entité>:<clé catalogue>:<uuid joueur>` :
un droit par participant éligible à la mort d'un boss communautaire et par clé `participation-rewards`
du profil. Règles d'éligibilité : docs/RPGQUEST_BIBLE.md §11. Déclaré seulement si `enabled: true` ; une
clé inconnue du catalogue est déclarée quand même (droit en attente avec l'erreur).

### 6.4 Tirages des tables aléatoires (#177)

Fin de quête : source `QUEST`, référence = id de la quête, clé
`quest:<jeton de complétion>:<table>.<entrée>:<uuid joueur>`, écrite dans la transaction de complétion.
Boss communautaire : source `BOSS`, clé `boss:<uuid entité>:<table>.<entrée>:<uuid joueur>`. Voir §10.

### 6.5 Plus tard (hors tranche 1)

Saisons (#54), concours, codes promotionnels, VIP : chacun = une nouvelle valeur de `source` et un
format de clé, **sans nouvelle table**. Un portail qui déclare (après #53, comptes liés) passera par
un canal authentifié vers PlugAdmin, puis l'action agent — jamais par du SQL.

## 7. Portail et confidentialité

- en V1, le portail **n'affiche pas** les droits individuels ; l'instantané (#421) n'en contient
  aucun. Il continue d'afficher les récompenses *annoncées* d'un événement (`rewards[].label`), jamais
  la clé interne ;
- plus tard, « mes récompenses » sur le profil privé d'un compte lié (#53) : statut seulement, pas
  d'action.

## 8. Tranche 1 (livrée, désactivée par défaut)

- schéma V41 `reward_entitlements` (SQLite et MariaDB) ;
- `entitlements.yml` (catalogue + `enabled`), relu à chaud chaque minute s'il change ;
- déclaration depuis l'archive des événements (§6.1) et depuis PlugAdmin (§6.2) ;
- remise sécurisée (§5) avec garde mémoire, prise conditionnelle et compensation ;
- actions agent `entitlement.list` (lecture), `entitlement.declare`, `entitlement.revoke` ;
- PlugAdmin : fiche joueur → onglet **Monnaie** → **Droits à récompense** — relevé du joueur (état
  réel, essais, dernière erreur, dates), déclaration depuis le catalogue réel du serveur, révocation
  d'un droit en attente (permissions monnaie, confirmation). Une vue globale multi-joueurs n'est pas
  faite : `entitlement.list` sait déjà lister sans filtre de joueur.

`enabled: false` (ou fichier absent) : aucune déclaration depuis les événements, aucune remise ; la
liste PlugAdmin reste consultable, la déclaration manuelle est refusée.

## 9. Tests

Automatisés : migration V41 (SQLite + traduction MariaDB), dépôt (déclaration idempotente, prise
conditionnelle concurrente, compensation, révocation, filtres), catalogue (validation), calcul des
destinataires depuis l'archive (rangs ex-aequo, seuil, objectif commun atteint/non atteint, exclusions),
actions agent (validation des paramètres).

`PENDING MANUAL VALIDATION` en jeu : inventaire plein puis libéré, déconnexion pendant la remise,
deux remises simultanées, redémarrage avec droits en attente, événement archivé réel.

## 10. Tables de récompenses aléatoires (#177)

Section `tables` de `entitlements.yml`, partagée entre quêtes et boss. Chaque entrée **désigne une
récompense du catalogue** (`rewards`) : la remise (objets, avantage, inventaire plein, au plus une fois)
reste celle des §3–5, rien n'est dupliqué.

```yaml
rewards:
  gem: {label: "Émeraudes", items: [{item: EMERALD, amount: 3}]}
  sharp_book:
    label: "Livre Tranchant III"
    items: [{item: ENCHANTED_BOOK, enchantments: {sharpness: 3}}]   # stocké dans le livre
  strength:
    label: "Potion de force"
    items: [{item: POTION, potion: strength}]
tables:
  butin_ogre:
    label: "Butin de l'ogre"
    mode: single            # obligatoire : single | independent
    enabled: true           # défaut true
    entries:
      - {id: gem,  reward: gem,        chance: 50}
      - {id: book, reward: sharp_book, chance: 12.5}
      - {id: pot,  reward: strength,   chance: 0.5}
```

### 10.1 Sémantique — toujours explicite

| Mode | Tirage | Somme des chances |
| --- | --- | --- |
| `independent` (« chances indépendantes ») | un tirage par entrée : plusieurs gains possibles, ou aucun | libre (peut dépasser 100 %) |
| `single` (« une récompense maximum ») | un seul tirage : au plus une entrée, le reste = aucun gain | ≤ 100 %, refusé sinon |

Aucun mode par défaut, aucune normalisation des pourcentages en poids relatifs : la chance affichée est
la chance réelle. Chances stockées en centièmes de pour cent (entiers, `12.5` → 1250) ; `12.345` est
refusé plutôt qu'arrondi. PlugAdmin affiche l'explication calculée (`single` : « aucun gain : 37,5 % » ;
`independent` : probabilité de n'avoir rien et nombre moyen de gains).

### 10.2 Tirage figé, jamais relancé

Le tirage est une fonction pure (SHA-256) d'une **graine stable** : `quest:<jeton de complétion>:<table>:<joueur>`
ou `boss:<entité>:<table>:<joueur>`. Le jeton de complétion et l'UUID d'entité sont aléatoires : un joueur
ne peut pas prévoir son tirage. Un nouvel essai (déclaration de boss rejouée, reprise) recalcule
exactement les mêmes gains, et chaque gain est un droit à clé d'idempotence stable : jamais de double gain.
Pour une quête, les gains sont écrits **dans la même transaction** que la complétion
(`QuestProgressRepository#completeQuest`) : un arrêt brutal ne peut ni perdre le tirage ni le refaire.
Limite : une table modifiée **entre** deux essais d'une même déclaration de boss (fenêtre de 10 s au plus)
pourrait donner un gain différent ; chaque gain reste tracé avec la version de table qui l'a produit.

### 10.3 Traçabilité

Libellé figé du droit : `<libellé de la récompense> — table <id> v<version> (<chance>)`. `version` = 8
caractères hexadécimaux du SHA-256 du mode et des entrées (id, récompense, chance) : changer le nom de la
table ne la change pas, changer une chance la change. Journal serveur : `Quête … : tirage « id » vXXXX pour
<uuid> → [entrées]` et, pour un boss, une ligne `tirage :` par participant.

### 10.4 Objets enrichis du catalogue

`enchantments: {clé: niveau}` (clé Minecraft, `minecraft:` facultatif, niveau 1–10, 8 au plus, pas sur un
objet custom) ; sur `ENCHANTED_BOOK` l'enchantement est **stocké** dans le livre. Appliquer un enchantement
à l'équipement porté par le joueur n'existe **pas** (aucune cible implicite). `potion: <type Minecraft>` sur
`POTION`, `SPLASH_POTION`, `LINGERING_POTION`, `TIPPED_ARROW`. Les clés sont résolues à la remise : inconnue
= droit en attente avec l'erreur.

### 10.5 Hors de cette tranche

Édition graphique des tables dans PlugAdmin (création, duplication, activation), sélecteur avec recherche
dans les éditeurs de quête et de boss, XP / argent comme entrée de table, boss de quête (#171).

