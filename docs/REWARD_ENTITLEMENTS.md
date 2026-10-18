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
    enabled: true                                # facultatif (#474) ; false = plus promise à personne
```

Ce fichier n'a plus à être écrit à la main : PlugAdmin → **Récompenses** le crée, l'active et en édite les
récompenses et les tables (§10.8).

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
du profil. Règles d'éligibilité : docs/RPGQUEST_BIBLE.md §11. Déclaré seulement si `enabled: true` (sinon
chaque joueur éligible est averti que les récompenses sont désactivées, #177) ; une clé inconnue du catalogue
est déclarée quand même (droit en attente avec l'erreur).

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

### 10.5 Catalogue ou table désactivés — une seule règle (tranche 2)

| Situation | Quête « Tirage au sort » | Boss `table:<id>` |
| --- | --- | --- |
| `enabled: false`, fichier absent ou refusé | aucun tirage ; résumé de fin : « récompenses aléatoires désactivées sur le serveur » ; garanties de la quête remises | rien n'est tiré ni déclaré (ni clés garanties) ; chaque joueur éligible : « les récompenses sont désactivées sur le serveur » |
| table inconnue ou `enabled: false` | aucun tirage ; « Tirage « id » indisponible » | aucun tirage pour cette table ; « Tirage « id » indisponible » ; clés garanties déclarées |

Jamais de gain inventé, jamais de promesse silencieuse ; le journal serveur et les « Victoires récentes »
le tracent. (Tranche 1 enregistrait les gains de quête même désactivé : abandonné pour cette règle commune.)

### 10.6 Édition depuis PlugAdmin (tranche 2)

Page **Récompenses** (`/rewards`, anciennement « Récompenses aléatoires »). Actions agent :

| Action | Paramètres | Effet |
| --- | --- | --- |
| `reward.catalog.status` | — | fichier présent / accepté / activé, erreurs, récompenses (`key`, `label`, `summary`), tables (chances lisibles et `chanceValue` éditable) |
| `reward.table.save` | `table_id`, `label`, `mode`, `enabled`, `entries` = `id=récompense@chance;…`, `create`, `expected_version` facultatif | crée (`EXISTS` si déjà là) ou remplace (`NOT_FOUND`, `STALE` si la version a changé, `UNCHANGED` sans écriture) |
| `reward.table.duplicate` | `table_id`, `new_id`, `new_label` facultatif | copie **désactivée**, mêmes entrées (donc même version) |
| `reward.table.toggle` | `table_id`, `enabled` | seul l'interrupteur change, la version non |

Garanties (`RewardTableEditor`, mêmes que l'écriture des profils de mob #461) : fichier actuel absent
(`NO_CATALOG`) ou refusé (`CURRENT_INVALID`) → rien n'est écrit ; seule `tables.<id>` est remplacée
(commentaires et ordre conservés, `rewards` et `enabled` jamais touchés) ; le texte final est relu par le
vrai chargeur et doit redonner exactement la table voulue sans rien changer d'autre (`INVALID` sinon) ;
écriture atomique ; rechargement immédiat (`reloadNow`) et, s'il n'aboutit pas, ancien fichier remis à
l'octet près (`ROLLED_BACK`). Identifiants `y/n/yes/no/on/off/true/false/null` refusés (YAML les lit comme
oui/non). Une ligne de table ajoutée reçoit un identifiant stable dérivé de la récompense ; une ligne
existante garde le sien (il fait partie de la clé d'idempotence des gains).

### 10.7 Hors de ces tranches

XP / argent comme entrée de table, suppression d'une table ou d'une récompense (les désactiver), boss de
quête (#171), usages des récompenses par les événements de `statistics.yml` dans PlugAdmin (non listés).

### 10.8 Catalogue administré depuis PlugAdmin (#474)

Plus aucune manipulation de fichier : la page **Récompenses** (`/rewards`, droit « Économie — lecture »,
écritures « Économie — écriture ») couvre tout `entitlements.yml`.

| Action | Paramètres | Effet |
| --- | --- | --- |
| `reward.catalog.init` | — (case de confirmation côté panel) | crée le fichier **désactivé et vide** (`enabled: false`, `rewards: {}`, `tables: {}`, en-tête de commentaires) ; `EXISTS` si un fichier existe déjà, même refusé — jamais écrasé ; aperçu exact (`initialText`) dans `reward.catalog.status` tant qu'il manque |
| `reward.catalog.toggle` | `enabled` (confirmation côté panel) | seul l'interrupteur général change |
| `reward.definition.save` | `reward_key`, `label`, `enabled`, `items` = `ITEM@quantité[+ench=clé:niveau,…][+potion=type];…`, `perk_key` / `perk_tier` facultatifs, `create`, `expected_version` | crée (`EXISTS`) ou remplace (`NOT_FOUND`, `STALE`, `UNCHANGED`) une récompense ; objet custom, enchantement ou potion inconnus du serveur refusés (`INVALID`) avant toute écriture |
| `reward.definition.duplicate` | `reward_key`, `new_key`, `new_label` facultatif | copie **désactivée**, même contenu |
| `reward.definition.toggle` | `reward_key`, `enabled` | seul `enabled` de la récompense change ; le message cite les tables et boss concernés |

`reward.catalog.status` donne en plus, par récompense : `enabled`, `version` (empreinte nom + contenu +
interrupteur, contrôle de formulaire périmé), lignes d'objets éditables, avantage, **usages** (tables qui la
tirent, boss qui la déclarent) ; par table : **usages** (quêtes « Tirage au sort », boss `table:<id>`) ; et les
objets custom, enchantements et potions connus du serveur (listes de l'éditeur).

Garanties (`EntitlementsFile`, partagé avec l'éditeur de tables, un seul verrou) : fichier actuel absent
(`NO_CATALOG`) ou refusé (`CURRENT_INVALID`) → rien n'est écrit ; seule la clé visée est remplacée (ordre,
commentaires, autres récompenses et tables conservés) ; le texte final relu par le vrai chargeur doit redonner
**exactement** le catalogue attendu (sinon `INVALID`, rien d'écrit) ; écriture atomique (création : fichier
complet puis lien vers le nom définitif, refusée si un fichier est apparu) ; rechargement immédiat et, s'il ne
redonne pas ce catalogue, ancien fichier remis à l'octet près — ou retiré s'il venait d'être créé
(`ROLLED_BACK`). Nouvelles clés : `[a-z0-9_-]` (pas de point : YAML lirait deux niveaux), mots oui/non refusés.

**Récompense désactivée** (`enabled: false`) : plus jamais promise. Un tirage qui tombe sur elle ne donne
rien (le tirage lui-même ne change pas, les autres entrées gardent leurs chances) ; un boss ne la déclare plus
(trace « désactivée dans le catalogue » dans ses victoires récentes) ; une déclaration administrateur est
refusée ; un événement archivé ne la déclare pas et sera relu jusqu'à sa réactivation (clés idempotentes).
Les droits **déjà ouverts** restent remis (le contenu est résolu à la remise). Aucune suppression : désactiver
ne casse aucune référence. Interrupteur général : voir §10.5 (désactivé = aucun tirage ni nouveau droit, droits
en attente conservés jusqu'à la réactivation).

**Saisie guidée (retour propriétaire du 18/10).** Le relevé `reward.catalog.status` ajoute `enchantmentInfo` :
pour chaque enchantement du registre Paper, `key`, `maxLevel`, `items` (objets acceptés, `getSupportedItems`) et
`conflicts` (`conflictsWith`). PlugAdmin en tire le sélecteur (nom français tenu dans `EnchantmentNames`, repli
sur l'identifiant embelli pour un enchantement inconnu, niveau I à `maxLevel`, avertissements « ne s'applique
pas à … » / « incompatible avec … ») ; un plugin plus ancien sans ce champ garde la simple liste d'identifiants.
Ces indications ne changent pas la validation d'écriture : le serveur accepte toujours un niveau 1 à 10 et
refuse un enchantement inconnu. Formulaires `reward.*` : champ `submit_token` à usage unique (un renvoi retombe
sur l'action déjà créée) ; refus de `reward.definition.save` → formulaire réaffiché (`?refused=<action>` ou
session), comme les tables (#177).

