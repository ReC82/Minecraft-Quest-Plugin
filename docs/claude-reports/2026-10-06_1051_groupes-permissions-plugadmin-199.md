# #199 — Groupes multiples et permissions PlugAdmin

## Informations

* Date : 2026-10-06
* Issue : [#199](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/199)
* Branche : `feature/169-special-mobs-boss` (aucun merge)
* Commits : voir §7
* Début de la tâche : 2026-10-06 10:51:56 (heure locale réelle, première action)
* Fin de la tâche : 2026-10-06 11:35:00 (heure locale réelle)
* Durée totale : 00:43:04

**Aucun ticket GitHub n'a été modifié ni fermé. Aucun test manuel n'a été coché ni présenté comme
exécuté. Aucun mail, aucune annonce serveur. Lot entièrement côté panel : aucun changement du
plugin, donc aucun redémarrage Minecraft.**

---

## 1. Audit du modèle existant

| Élément | État constaté |
|---|---|
| `Permission` | énumération plate, sans notion de refus |
| `Role` | énumération **figée dans le code**, chaque rôle = un `EnumSet<Permission>` ; `has()` est un simple `contains` |
| `PermissionService.can(String roleName, …)` | prenait un **nom de rôle** ; 55 appels, tous avec `session.role()` |
| `Session` | portait un seul rôle, réaligné sur la base à **chaque requête** (`refreshRole`) |
| `PanelApp.currentSession` | re-contrôle par requête déjà en place : compte désactivé → session invalidée |
| `UserDirectory` | protection du dernier `OWNER` déjà implémentée (rôle non abaissable, compte non désactivable) ; `countActiveOwners()` existait |
| Audit | `AuditLog.record(actor, action, target, result, details, requestId)` |

**Deux constats ont décidé la conception.**

**(a) Il n'existe aucun refus explicite dans le modèle.** C'est ce qui rend la question « comment
combiner ? » sans objet : avec des ensembles et un `contains`, la seule combinaison cohérente est
l'**union**. C'est la réponse à l'instruction « privilégie l'union si le modèle existant le
permet » — il le permet, et rien d'autre ne serait cohérent.

**(b) Le re-contrôle par requête existait déjà.** C'était le bon endroit pour brancher la résolution
des groupes, et c'est ce qui rend la révocation immédiate *sans rien inventer* : il n'y a aucun
cache à invalider parce qu'il n'y a aucun cache.

---

## 2. Combinaison des permissions : la règle, et pourquoi elle est la bonne

**Droits effectifs = permissions du rôle ∪ permissions de chacun des groupes du compte.**

Conséquence assumée et écrite à l'écran : **un groupe ne peut jamais réduire les droits d'un
rôle.** Pour retirer un droit, on le retire du groupe, ou on retire le compte du groupe — un geste,
un effet.

**Je n'ai pas introduit de refus explicite**, et c'est un choix argumenté plutôt qu'une facilité :
un refus rendrait « retirer un droit » ambigu (faut-il le retirer de tous les groupes, ou ajouter un
refus qui écrase ?) et transformerait chaque écran en question de priorité. Les tests de « conflit »
demandés par le ticket vérifient donc exactement cela : qu'aucune combinaison ne peut faire perdre
un droit du rôle (`aGroupCannotTakeAwayWhatTheRoleGrants`,
`groupsAddToTheRoleAndNeverRemoveFromIt`, `aGroupNameMustBeValidAndUnique` pour les cas d'entrée).

---

## 3. Anti-élévation de privilège : une règle unique au lieu d'une liste de cas

L'exigence du ticket est précise : « la gestion des groupes ne doit pas permettre à un administrateur
de s'accorder indirectement des droits réservés au propriétaire », et `PLAYER_OP_WRITE` /
`ECONOMY_WRITE` doivent garder leurs restrictions.

J'ai écarté la solution par liste (« ces permissions-là sont réservées »), parce qu'une liste se
périme : la permission ajoutée demain y manquera. La règle retenue est générale :

> **On ne peut jamais accorder — ni retirer — une permission que l'on ne détient pas soi-même.**

Elle ferme d'un coup, et pour toute permission future :

- créer un groupe portant `PLAYER_OP_WRITE` depuis un compte ADMIN → refusé ;
- l'ajouter à un groupe existant → refusé, le groupe ne bouge pas ;
- **le retirer** d'un groupe réservé → refusé aussi (ce serait un moyen détourné de défaire une
  décision du propriétaire) ;
- renommer ou supprimer un groupe qu'on ne détient pas entièrement → refusé ;
- l'attribuer ou le retirer à un compte → refusé.

Et le pendant indispensable, testé lui aussi : un ADMIN peut parfaitement gérer un groupe portant
`ECONOMY_WRITE`, droit qu'il détient. L'anti-élévation ne bloque pas tout — elle bloque exactement
ce qu'il faut.

**Second verrou, volontairement redondant** : `USER_MANAGE` n'est détenue que par `OWNER`
aujourd'hui, donc seul un propriétaire atteint ces écrans. Le premier verrou survit à une future
décision d'accorder `USER_MANAGE` à un autre rôle.

**Dans l'interface**, une permission non détenue est affichée **désactivée avec son motif** plutôt
que masquée : la cacher laisserait croire qu'elle n'existe pas, alors que le refus est une décision.

---

## 4. Migration, et pourquoi aucun droit ne bouge

Trois `CREATE TABLE IF NOT EXISTS` **additifs** dans `control-panel.db` : `panel_group`,
`panel_group_permission`, `panel_user_group` (+ un index sur l'appartenance).

- **`panel_user` n'est pas touché.** Un compte garde exactement le rôle et les droits qu'il avait.
- **Les rôles ne sont pas convertis en groupes.** C'était tentant, et c'eût été une erreur : la
  conversion aurait transformé un ensemble figé dans le code en donnée éditable, c'est-à-dire
  exactement le risque d'« élargir » ou de « perdre » un droit que le ticket interdit.
- Une installation existante démarre avec **zéro groupe**, donc des droits effectifs **strictement
  égaux** à ceux du rôle (`withoutAnyGroupTheEffectiveRightsAreExactlyTheRoleRights`).
- Une permission disparue du code est **ignorée** à la lecture d'un groupe (elle n'accorde rien)
  plutôt que de faire échouer la page : un groupe doit rester lisible et modifiable après une
  évolution du catalogue.

**Accès propriétaire préservé** : un groupe ne peut qu'ajouter, donc il est *structurellement*
impossible de verrouiller un propriétaire en jouant sur les groupes — ni en le retirant d'un groupe,
ni en vidant un groupe, ni en le supprimant. Les protections du dernier `OWNER` restent celles de
`UserDirectory`, et restent nécessaires : c'est le **rôle** qui porte l'accès.

---

## 5. Révocation immédiate sur les sessions actives

`Session` porte désormais ses droits **effectifs**, recalculés à **chaque requête** par
`PanelApp.currentSession` (`refreshAuthz`). Retirer un groupe, vider ses permissions ou supprimer le
groupe agit donc sur une session **déjà ouverte**, à la requête suivante, sans reconnexion.

**Il n'existe volontairement aucun cache plus long qu'une requête.** C'est la décision la plus
importante de cette partie : un cache serait une seconde source de vérité à invalider, donc un bug
en attente. Les trois chemins de révocation sont vérifiés en HTTP réel, chacun avec **deux sessions
simultanées** (`GroupAdminHttpTest`).

---

## 6. Ce qui a été livré

| Élément | Détail |
|---|---|
| `PanelGroup` | nom unique (casse ignorée), description, ensemble de permissions |
| `EffectivePermissions` | union + **provenance** de chaque droit (rôle / nom de groupe) |
| `GroupRepository` + `SqliteGroupRepository` + `InMemoryGroupRepository` | remplacements de permissions et d'appartenances en **une transaction** : jamais un groupe à moitié vidé |
| `GroupDirectory` | toutes les règles : validation, unicité, anti-élévation, oubli d'un compte |
| `PermissionService` | `can(EffectivePermissions, …)` ; `canByRoleOnly(String, …)` conservée et **nommée pour qu'on ne s'en serve pas par accident** sur un chemin de requête |
| `Session#refreshAuthz` | droits effectifs par requête |
| `GroupsPages` | `/groups` (liste + création), `/groups/<id>` (détail, permissions, membres, suppression), bloc « Groupes + droits effectifs » sur la fiche d'un compte |
| Routes | `POST /groups/create|rename|permissions|delete`, `POST /users/groups` |
| Navigation | entrée « Groupes » dans la barre et tuile d'accueil, filtrées par `USER_MANAGE` |

**Sécurité de chaque route** : `USER_MANAGE` revérifiée **côté backend sur les droits effectifs**,
jeton CSRF sur tous les POST, suppression confirmée par **ressaisie du nom** du groupe.

**Audit** : `group.create`, `group.rename`, `group.permissions`, `group.delete`, `user.groups` —
avec l'avant/après, et **y compris les refus** (`DENIED`), car un refus est précisément ce qu'on veut
pouvoir relire après coup.

**Mobile** : les permissions et les appartenances sont des grilles de cases à cocher
(`row-cols-1 row-cols-sm-2 row-cols-lg-3`) avec libellés cliquables, et les deux seules tables sont
en `.table-responsive`. Aucun second style de mise en page n'a été introduit.

**Un détail qui aurait cassé la page silencieusement** : `Http.formBody` ne conserve **qu'une**
valeur par clé. Des cases à cocher homonymes auraient donc perdu toutes leurs valeurs sauf une.
D'où un nom distinct par case : `perm_<NOM>` et `group_<id>`.

---

## 7. Tests, commits et validation restante

### Suite

| Module | Tests | Ignorés | Échecs | Erreurs |
|---|---|---|---|---|
| control-panel | **638** | 1 | 0 | 0 |

595 → **638** (**+43**). Les modules `plugin` et `web-api` ne sont pas touchés par ce lot.

| Suite | Cas | Ce qu'elle verrouille |
|---|---|---|
| `EffectivePermissionsTest` | 9 | rôle seul = droits du rôle (la migration), groupes qui **ajoutent sans retirer**, union de plusieurs groupes, groupe vide sans effet, **aucun groupe ne peut retirer un droit du rôle**, provenances **multiples** pour un même droit, droit non accordé sans provenance, libellés de provenance, compte sans rôle |
| `GroupDirectoryTest` | 20 | cycle de vie, validation et unicité, suppression qui emporte les appartenances, appartenances multiples, retrait qui n'enlève **que** le bon groupe, et **8 cas d'anti-élévation** (créer / ajouter / retirer / renommer / supprimer / attribuer / retirer une attribution refusés sur un droit non détenu, acteur sans droit), plus le pendant : un ADMIN gère un groupe **dans la limite de ses propres droits** |
| `GroupAdminHttpTest` | 14 | bout en bout en HTTP avec **deux sessions simultanées** : migration, cycle de vie, doublon de nom, **révocation sur session active par les trois chemins**, **toutes les routes refusées côté backend**, non authentifié, CSRF absent, provenance affichée, appartenances multiples, dernier propriétaire, **audit des refus** |

**Un test existant a refusé mon code et avait raison** : `RolePermissionMatrixTest`,
`ActionsCenterTest` et `ContentEditorPagesTest` vérifient la matrice des **rôles** et passaient un
nom de rôle. Plutôt que d'affaiblir `PermissionService`, je leur ai donné le point d'entrée
explicite `canByRoleOnly` — leur objet est bien le rôle seul, et le nom dit maintenant qu'il ignore
les groupes.

**Un défaut de mon propre test, corrigé** : mon aide `postForm` lisait le jeton CSRF sur `/home`,
ce qui est impossible sans session. Le cas « appelant non authentifié » poste désormais directement,
et vérifie que la redirection vers la connexion arrive **avant** toute considération de jeton.

### Limites

- **La règle est l'union, sans refus explicite.** Un groupe ne peut donc pas restreindre un rôle. Si
  un besoin de restriction apparaît, ce sera un lot à part entière avec sa propre décision de
  priorité — l'ajouter en douce ici aurait rendu chaque écran ambigu.
- **Les rôles restent figés dans le code.** On ne peut pas créer un « rôle » depuis le panel, seulement
  un groupe. C'est volontaire : le rôle est le socle qui garantit qu'un compte ne change pas de
  droits parce qu'on a touché à la base.
- **La suppression d'un compte n'existe toujours pas** (désactivation seulement) ; `GroupDirectory`
  expose `forgetUser` pour le jour où elle arrivera.
- **Aucun lien avec OP Minecraft ni LuckPerms** : c'est l'objet de #200.

### Protocole manuel

**TC-252** dans `docs/MANUAL_TEST_PLAN.md` (30 étapes, 9 sections) : migration, cycle de vie,
plusieurs groupes et provenance, conflits, **révocation sur session active avec deux fenêtres**,
anti-élévation (dont le **forçage de formulaire**), routes directes, **mobile**, audit. Comptes de
test dédiés `tc252-lecteur` / `tc252-admin`, nettoyage décrit. **Aucune case cochée.**

### Commits et déploiement

Voir la ligne d'index de ce rapport et le `SERVER_CHANGELOG` pour les empreintes réellement
déployées. **Panel uniquement** : aucun JAR, aucun redémarrage Minecraft.
