# Coffres personnels (vaults) — issues #104, #105, #106

Stockage personnel du joueur, partagé entre tous les Hubs : `UUID joueur + vault_id → inventaire`.
Proche d'un Ender Chest, mais géré par LodyQuests et **distinct** de l'Ender Chest vanilla (qui
n'est ni remplacé ni détourné).

## État livré

- **Phase 1 (#104)** — socle : `PersonalVaultService#open(Player, VaultId)` (accessible via
  `RPGQuestBootstrap#personalVaultService()`), `PersonalVaultService#vaultIds(UUID)`. Aucune commande
  joueur, **aucun accès distant depuis le Wild**.
- **Phase 2 (#105)** — coffres physiques des Hubs : voir « Points d'accès physiques » ci-dessous.
- **Phase 3 (#106)** — capacité progressive : voir « Déblocage et capacité » ci-dessous.
- À venir : salles de préparation (#107). Aucune page PlugAdmin dédiée.

## Modèle

| Élément | Règle |
|---|---|
| Propriétaire | UUID canonique du joueur (jamais le pseudo). |
| `VaultId` | Identifiant logique `[a-z0-9_]{1,32}` ; `vault_01` (`VaultId.DEFAULT`), puis `vault_02`… selon les déblocages (#106). |
| Identité du contenu | Aucune coordonnée, aucun monde, aucun Hub : deux coffres physiques de deux Hubs liés au même `vault_id` montreront le même contenu. |
| Capacité | `personal-vaults.default-slots` (27 par défaut), agrandie par `VAULT_SLOTS_<ID>` (#106). Les rangées encore occupées au-delà de la capacité restent affichées (jamais tronqué, 54 au plus). |
| Format | `ItemStack[]` sérialisé par `backpack.ItemArraySerializer` (méta complète : nom, lore, enchantements, PDC), version du format dans `schema_version`. |

## Persistance

Table `personal_vaults` (schéma **V43**, SQLite et MariaDB — `BLOB` → `LONGBLOB`, clé
`(player_uuid, vault_id)` en `VARCHAR(191)`) :

```
player_uuid TEXT, vault_id TEXT, schema_version INTEGER, contents BLOB, updated_at TEXT
PRIMARY KEY (player_uuid, vault_id)
```

Une ligne n'existe qu'après la première fermeture du coffre. Repository : `PersonalVaultRepository`
(JDBC asynchrone sur l'exécuteur unique de `DatabaseManager`). La table est reprise par l'outil de
migration SQLite → MariaDB (#42, groupe « Coffres personnels ») ; aucune migration réelle n'est faite.

## Garanties anti-perte / anti-duplication

- **Un seul inventaire vivant** par (joueur, coffre) : ouvrir deux fois, ou depuis deux Hubs,
  réutilise la même ; deux ouvertures concurrentes n'en créent jamais deux.
- Sauvegarde à la **fermeture**, à la **déconnexion** (filet de sécurité si la fermeture n'a pas
  déjà sauvegardé, sans double écriture) et à l'**arrêt** du plugin (avant la fermeture de la base).
- Passer directement d'un coffre à un autre sauvegarde le premier.
- Tant que la base n'a pas confirmé une sauvegarde, l'instantané reste en mémoire et c'est lui que la
  réouverture suivante présente (jamais une version plus ancienne lue en base) ; en cas d'échec
  d'écriture il est réécrit à la fermeture suivante.
- Contenu illisible : ouverture **refusée** (`LOAD_FAILED`), ligne laissée intacte pour récupération
  par un administrateur — jamais écrasée par un coffre vide.
- Inventaire virtuel (aucun bloc) : aucun hopper ni autre joueur ne peut y accéder.

Limite connue (phase 1) : comme pour les backpacks, un crash brutal du serveur **pendant** qu'un
coffre est ouvert perd les mouvements faits depuis l'ouverture (rien n'est sauvegardé en continu).

## Points d'accès physiques (phase 2, issue #105)

Les blocs visibles d'une salle des coffres ne **contiennent** rien : ce sont des portes vers un
`vault_id`. Le modèle sépare strictement le binding du stockage :

```
vault_access_points : (world, x, y, z) → vault_id        (schéma V44, porte)
personal_vaults     : (player_uuid, vault_id) → contenu   (schéma V43, données joueur)
```

| Règle | Détail |
|---|---|
| Déclaration | `/rpgadmin vault bind [vault_id]` sur le bloc visé (défaut `vault_01`) ; `unbind [x y z]`, `info`, `list`. Droit : ombrelle `rpgquest.admin.world`. Aucune coordonnée en dur dans le code. |
| Blocs acceptés | coffre, coffre piégé, tonneau, coffre de l'Ender. Un coffre double est lié sur ses deux moitiés. |
| Refus | bloc non pris en charge ; conteneur **non vide** (son contenu physique ne serait plus jamais montré) ; `travel.wild-world` et `claims.world` (pas d'accès distant au stockage personnel). |
| Ouverture | Clic droit main principale : l'ouverture vanilla est **toujours** annulée (builder et OP compris) et remplacée par `open(joueur qui clique, vault_id)`. Main secondaire annulée sans ouvrir ; spectateur : rien. Traité en premier (priorité `LOWEST`) : le drapeau de zone `public-containers: false` ne bloque pas un point d'accès (ce n'est pas un conteneur public). |
| Multi-joueur | Deux joueurs sur le même bloc obtiennent deux inventaires distincts (clé = leur UUID). Un builder ne voit jamais le contenu d'un autre joueur. |
| Multi-Hub | Plusieurs positions peuvent viser le même `vault_id` : même contenu pour le même joueur, quel que soit le Hub. |
| Déplacer / reconfigurer | Lier la nouvelle position puis délier l'ancienne, ou relier vers un autre `vault_id` : `personal_vaults` n'est jamais lu ni écrit. |
| Coffre copié / posé par un joueur | Ignoré : seule une position déclarée compte (pas d'identité portée par le bloc, donc rien à copier). |
| Protections | Casse refusée à tous (délier d'abord) ; explosions (le bloc est retiré de la liste), pistons, feu et entités (endermen, wither…) bloqués ; poser un coffre accolé à un point lié (coffre double non lié) refusé. S'ajoute aux protections du monde Hub. |
| Performance | Cache mémoire chargé au démarrage ; aucun SQL par clic. Écritures (bind/unbind) asynchrones, cache mis à jour après confirmation de la base. |

Limites connues : si le chargement initial du registre échoue (erreur base au démarrage), les points
se comportent comme des coffres vanilla vides jusqu'au redémarrage suivant (erreur en console). Le
contenu *physique* d'un bloc lié n'est jamais montré ; il peut seulement être rempli par un hopper,
sans lien avec les données des joueurs.

## Déblocage et capacité (phase 3, issue #106)

Aucune mécanique de progression parallèle : les droits sont des **variables joueur**
(`player_variables`, comme `CLAIM_TIER_1`), lues par `storage.VaultEntitlements` à **chaque
ouverture** (aucun cache : un déblocage vaut aussitôt dans tous les Hubs, et survit à la reconnexion
et au redémarrage puisqu'il est en base).

| Réglage / variable | Effet |
|---|---|
| `personal-vaults.starter-vaults` (config.yml, défaut `[vault_01]`) | Coffres ouverts à tout joueur dès le départ. Liste vide = tout est à débloquer. |
| `personal-vaults.default-slots` (défaut `27`) | Taille d'un coffre sans extension : multiple de 9 entre 9 et 54, sinon la configuration est refusée. |
| `VAULT_UNLOCK_<ID>` = `true` | Ouvre le coffre `<id>` (`<ID>` en majuscules : `VAULT_UNLOCK_VAULT_02` pour `vault_02`). Toute autre valeur (dont `false`) le laisse verrouillé. |
| `VAULT_SLOTS_<ID>` = `9`…`54` (multiple de 9) | Taille du coffre `<id>` si elle dépasse `default-slots`. Une valeur illisible ou hors bornes n'accorde rien. |

Les valeurs livrées (un coffre de départ, 27 cases) sont des **valeurs de démonstration** : le nombre
de coffres et leur taille ne sont pas arbitrés.

**Obtenir un déblocage** — toute source capable de poser une variable, sans code nouveau :
récompense de quête `VARIABLE` (`key: VAULT_UNLOCK_VAULT_02`, `value: "true"`), action de dialogue
`SET_VARIABLE`, `/rpgadmin player variable set <joueur> VAULT_UNLOCK_VAULT_02 true` (droit
`rpgquest.admin.debug` en plus), ou l'outil « Écrire une variable » de PlugAdmin (qui refuse une
valeur mal typée pour ces clés). Un futur mode d'obtention (événement, saison, VIP) n'a qu'à poser la
même variable.

**Coffre verrouillé** — ouverture refusée (`OpenOutcome.LOCKED`), aucun inventaire, aucune lecture ni
écriture de `personal_vaults` ; message générique : « Ce coffre est encore verrouillé. Il se
débloquera au fil de ta progression dans LodyQuests. » (sans révéler la quête). Aucun visuel de coffre
verrouillé pour l'instant.

**Retrait d'un droit** — verrouiller (`false`) ou réduire la taille ne supprime jamais rien : le
contenu reste en base et réapparaît tel quel quand le droit revient. Après réduction de taille, les
rangées qui contiennent encore des objets restent visibles ; seules les rangées vides au-delà de la
capacité disparaissent. Un coffre déjà ouvert au moment du retrait reste ouvert jusqu'à sa fermeture.

Note : `/rpgadmin player resetnew|resetfull` efface les variables, donc les déblocages ; le contenu
des coffres personnels n'est pas touché par ces resets.

## Tests

`PersonalVaultServiceTest` (isolation entre joueurs, plusieurs `vault_id`, objets complexes avec
PDC, redémarrage, déconnexion, arrêt, ouvertures concurrentes, contenu illisible) et
`PersonalVaultRepositoryTest` (clé composite, upsert, survie à la réouverture de la base).
Phase 2 : `VaultAccessPointServiceTest` (ouverture du coffre de celui qui clique, deux joueurs sur le
même bloc, même `vault_id` dans deux Hubs, déplacement/reliaison sans toucher au contenu, coffre
vanilla inchangé, main secondaire et refus antérieur, survie au redémarrage, refus de liaison,
protections casse/explosion/coffre accolé, moitié d'un coffre double) et
`VaultAccessPointRepositoryTest`.
Phase 3 : `PersonalVaultServiceTest` (coffre hors liste de départ verrouillé puis ouvert par sa variable,
déblocage propre à un joueur et relu par une nouvelle instance, reverrouillage sans perte, capacité de
départ configurable, extension puis réduction sans troncature, valeur de taille invalide ignorée),
`VaultAccessPointServiceTest` (point d'accès vers un coffre verrouillé : message, rien d'ouvert),
`PersonalVaultConfigTest`, `VariableTypesTest`.
