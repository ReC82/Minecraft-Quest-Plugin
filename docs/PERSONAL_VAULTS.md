# Coffres personnels (vaults) — socle, issue #104

Stockage personnel du joueur, partagé entre tous les Hubs : `UUID joueur + vault_id → inventaire`.
Proche d'un Ender Chest, mais géré par LodyQuests et **distinct** de l'Ender Chest vanilla (qui
n'est ni remplacé ni détourné).

## État livré

- **Phase 1 (#104)** — socle : `PersonalVaultService#open(Player, VaultId)` (accessible via
  `RPGQuestBootstrap#personalVaultService()`), `PersonalVaultService#vaultIds(UUID)`. Aucune commande
  joueur, **aucun accès distant depuis le Wild**.
- **Phase 2 (#105)** — coffres physiques des Hubs : voir « Points d'accès physiques » ci-dessous.
- À venir : capacité progressive (#106), salles de préparation (#107). Aucune page PlugAdmin.

## Modèle

| Élément | Règle |
|---|---|
| Propriétaire | UUID canonique du joueur (jamais le pseudo). |
| `VaultId` | Identifiant logique `[a-z0-9_]{1,32}` ; `vault_01` (`VaultId.DEFAULT`) est le seul utilisé par le MVP, plusieurs coffres par joueur sont prévus (`vault_02`…). |
| Identité du contenu | Aucune coordonnée, aucun monde, aucun Hub : deux coffres physiques de deux Hubs liés au même `vault_id` montreront le même contenu. |
| Capacité | 27 cases (3 rangées, `PersonalVaultService.DEFAULT_SLOTS`). Un contenu persisté plus grand garde sa taille (jamais tronqué, 54 au plus). |
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

## Tests

`PersonalVaultServiceTest` (isolation entre joueurs, plusieurs `vault_id`, objets complexes avec
PDC, redémarrage, déconnexion, arrêt, ouvertures concurrentes, contenu illisible) et
`PersonalVaultRepositoryTest` (clé composite, upsert, survie à la réouverture de la base).
Phase 2 : `VaultAccessPointServiceTest` (ouverture du coffre de celui qui clique, deux joueurs sur le
même bloc, même `vault_id` dans deux Hubs, déplacement/reliaison sans toucher au contenu, coffre
vanilla inchangé, main secondaire et refus antérieur, survie au redémarrage, refus de liaison,
protections casse/explosion/coffre accolé, moitié d'un coffre double) et
`VaultAccessPointRepositoryTest`.
