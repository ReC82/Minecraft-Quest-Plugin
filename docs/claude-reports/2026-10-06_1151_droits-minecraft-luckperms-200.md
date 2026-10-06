# #200 — Liaison UUID et droits Minecraft par groupe et par monde (avec dépendance #27)

## Informations

* Date : 2026-10-06
* Issues : [#200](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/200), avec le découpage
  minimal de [#27](https://github.com/ReC82/Minecraft-Quest-Plugin/issues/27) comme dépendance
  autorisée
* Branche : `feature/169-special-mobs-boss` (aucun merge)
* Commits : `b94b689` + le commit de clôture documentaire
* Début de la tâche : 2026-10-06 11:51 (heure locale réelle, après stabilisation de #199)
* Fin de la tâche : 2026-10-06 12:50:00 (heure locale réelle)
* Durée totale : 00:59:00

**Aucun ticket GitHub n'a été modifié ni fermé. Aucun test manuel n'a été coché ni présenté comme
exécuté. Aucun mail, aucune annonce serveur.**

---

## 1. Diagnostic : pourquoi #27 était une dépendance et non un confort

Un **seul** nœud, `rpgquest.admin.world`, gouvernait tout :

| Ce que le ticket demande de séparer | Nœud avant ce lot |
|---|---|
| Construction dans le Hub | `rpgquest.admin.world` (`HubWorldProtectionListener`) |
| Protection des claims d'autrui | `rpgquest.admin.world` (`ClaimProtectionListener`) |
| Règles du monde des claims | `rpgquest.admin.world` (`ClaimsWorldRulesListener`) |
| Accès au monde des claims (#22) | `rpgquest.admin.world` (`ClaimWorldAccessGuard`) |
| Protection des zones | `rpgquest.admin.world` (`ZoneProtectionListener`) |
| **Tout `/rpgadmin`**, marquage de PNJ compris | `rpgquest.admin.world` |

Deux exigences étaient donc **littéralement impossibles** à satisfaire, pas seulement difficiles :

- « créer un PNJ doit être un droit distinct du build » — c'était le même nœud ;
- « autoriser la construction dans le Hub ne doit pas autoriser la construction dans Claims » — un
  contexte de monde LuckPerms aurait bien séparé par monde, mais ce nœud porte aussi le bypass de
  zone **et l'intégralité des commandes d'administration** : accorder « build dans le Hub » aurait
  livré toute la surface d'administration dans ce monde.

**Deux parcours de PNJ, et c'est important.** `/rpgadmin npc` n'offre que `tag`, `untag`, `info` :
`tag` **marque** une entité d'un identifiant RPGQuest stable, il ne **crée aucun PNJ Citizens**. La
création et la gestion réelles passent par les actions `npc.citizens.create|link|rename|skin` du
panel (permissions **PlugAdmin** `NPC_WRITE` / `NPC_BIND_WRITE` / `NPC_SPAWN_WRITE`) ou par les
commandes de Citizens, qui ont leurs propres nœuds. Un « éditeur PNJ » a donc besoin d'un nœud
Minecraft **et** de permissions panel — deux systèmes distincts, et les confondre mènerait à croire
un éditeur incapable de créer.

**LuckPerms** n'était pas installé sur DEV (4 plugins).

---

## 2. Nœuds retenus

| Nœud | Effet | Défaut |
|---|---|---|
| `rpgquest.admin.world` | **ombrelle historique** : implique tout | `op` (inchangé) |
| `rpgquest.admin.command` | **entrer** dans `/rpgadmin`, sans autoriser aucune branche | `false` |
| `rpgquest.admin.npc` | parent des trois sous-actions PNJ | `false` |
| `rpgquest.admin.npc.tag` / `.untag` / `.info` | les sous-actions réellement disponibles | `false` |
| `rpgquest.build.hub.<monde>` | construire dans ce Hub | — (dynamique) |
| `rpgquest.build.hub.*` | construire dans tous les Hubs | `false` |
| `rpgquest.build.wild` | déclaré, **sans effet à ce jour** | `false` |
| `rpgquest.bypass.claim` | contourner les claims d'autrui | `false` |
| `rpgquest.bypass.zone` | contourner les zones | `false` |
| `rpgquest.bypass.claimworld` | accès/règles du monde des claims (#22) | `false` |

**Tous les nouveaux nœuds sont à `default: false`** : aucun élargissement implicite, et les droits du
propriétaire sont inchangés puisque l'ombrelle reste `default: op`. Chaque contrôle accepte
« nouveau nœud **ou** ombrelle », donc un administrateur déjà autorisé n'a **rien** à reconfigurer.

### `rpgquest.build.wild` : déclaré et sans effet, dit franchement

Le Wild n'a **aucune** restriction de construction : un joueur ordinaire y construit déjà librement.
Ce nœud n'est donc câblé dans **aucun** listener — et c'est volontaire. Le brancher sur la
protection de zone en ferait un bypass de zone, ce que le ticket interdit explicitement. Il existe
pour la convention et pour un éventuel verrou futur. Un test fige qu'il n'autorise rien dans le Hub.

### Convention `rpgquest.build.hub.<monde>` — et l'écart avec le `<id>` de #27

**L'« id » retenu est le nom du monde**, lu dans la configuration réelle (`hub.world`, défaut
`world_hub`). C'est le seul identifiant de Hub qui existe aujourd'hui, et le seul discriminant dont
le listener dispose au moment de décider (`player.getWorld().getName()`). Inventer un identifiant
logique distinct aurait créé une seconde source de vérité pour une distinction que rien ne sait
encore faire.

**Limites assumées, écrites dans le code :**

- plusieurs Hubs dans des **mondes différents** : fonctionne, un nœud par monde + le joker ;
- plusieurs Hubs dans le **même monde** : **non distinguables**. Le nœud porterait le même nom, et
  autoriser l'un autoriserait l'autre. Il faudrait un identifiant de Hub en configuration et une
  résolution par zone dans le listener — un lot à part entière ;
- **renommer le monde du Hub** change le nœud : les droits accordés sur l'ancien nom deviennent
  **silencieusement** sans effet. À faire figurer dans toute procédure de renommage de monde.

---

## 3. Le défaut que ta remarque a corrigé : provenance structurelle

Ma première version du pont reconnaissait « ses » droits à un **préfixe de nœud**
(`rpgquest.build.`, `rpgquest.bypass.`…). **C'est faux, et le défaut était sérieux** : un
administrateur qui accorde à la main `rpgquest.build.hub.world_hub` dans le contexte
`world=world_hub` pose un nœud **identique** à celui du pont, contexte compris. Une synchronisation
ne le trouvant pas dans l'état voulu l'aurait **supprimé**, détruisant une décision externe sans
aucun signal.

La provenance est donc désormais **structurelle** :

| Où vit quoi | Qui y touche |
|---|---|
| Groupe LuckPerms `rpgq-<id du groupe panel>` | le pont (ses droits gérés, avec contexte `world=`) |
| Nœud d'héritage `group.rpgq-…` sur l'utilisateur | le pont |
| **Nœud de permission posé directement sur l'utilisateur** | **jamais le pont** — même nom, même monde compris |
| Appartenance à un groupe LuckPerms externe (`vip`, `staff`…) | jamais le pont |

Un droit externe survit donc **par construction** à un retrait de groupe, à une dissociation et à un
redémarrage : il n'est pas au même endroit. Ce n'est plus une question de vigilance.

Deux détails qui comptent : le nom du groupe LuckPerms dérive de l'**identifiant** du groupe panel
et non de son libellé — renommer dans le panel n'orpheline rien et ne fait perdre aucun droit — et
le libellé est poussé comme **nom d'affichage** pour qu'un administrateur reconnaisse l'origine dans
LuckPerms. L'ombrelle `rpgquest.admin.world` et `rpgquest.admin.debug` sont **explicitement non
distribuables** par le pont : elles restent à attribuer à la main, en pleine conscience.

---

## 4. Ce qui a été livré

### Plugin

- `RpgPermissions` — point unique de nommage, avec la règle « nouveau nœud ou ombrelle ».
- Les **cinq** listeners réels recâblés : Hub (par monde), zones, claims d'autrui, règles du monde
  des claims, et le garde d'accès #22.
- `/rpgadmin` : entrée séparée des branches. La branche `npc` a ses nœuds ; **toutes les autres
  exigent encore l'ombrelle**. Conséquence voulue : un éditeur PNJ entre dans la commande et ne peut
  exécuter ni reset joueur, ni économie, ni rien d'autre.
- `LuckPermsBridge` + `BridgeGroupNaming` + `ManagedNodePolicy` + `ManagedNode` — API en
  `compileOnly`, `softdepend`, et **le plugin fonctionne sans LuckPerms** : toute absence devient un
  état réel avec son motif, jamais un faux succès.
- Quatre actions agent : `mc.rights.read`, `mc.group.sync`, `mc.group.delete`, `mc.rights.sync`.

### Panel

- **Liaison UUID** : `panel_user_minecraft`, joueur **hors ligne** inclus (volontaire : préparer les
  droits d'un builder avant son arrivée), **conflit impossible** par deux contraintes d'unicité en
  base, modification explicite, dissociation confirmée par **ressaisie de l'UUID**. La page dit que
  le **pseudonyme ne prouve rien** et qu'il s'agit d'une liaison **administrative** — aucune
  auto-liaison publique, qui exigerait une preuve de possession et reste un chantier distinct.
- **Droits par groupe et par monde** : `panel_group_mc_node`, catalogue **fermé** (`McRight`), un
  droit de Hub par monde **connu du relevé** — jamais un monde deviné. Un droit de construction
  **sans monde est refusé** : sinon il s'appliquerait partout et « par monde » ne voudrait rien dire.
- **Voulu vs réel** : la fiche d'un compte affiche ce que le panel veut (union de ses groupes) **et**
  ce que le serveur porte (dernier relevé, avec le groupe du pont d'origine). Si aucun relevé
  n'existe, c'est dit — jamais l'état voulu présenté comme appliqué.
- Toutes les routes derrière `USER_MANAGE` sur les droits **effectifs**, CSRF, et audit de chaque
  mutation **y compris refusée**.
- La dissociation prévient explicitement que les droits en jeu **sont toujours là** jusqu'à une
  synchronisation : ne pas le dire aurait été un faux sentiment de sécurité.

---

## 5. Tests

### Suite complète — verte, sur une exécution **propre**

| Module | Tests | Ignorés | Échecs | Erreurs |
|---|---|---|---|---|
| plugin | **1688** | 37 | 0 | 0 |
| control-panel | **661** | 1 | 0 | 0 |
| web-api | **30** | 0 | 0 | 0 |
| **Total** | **2379** | 38 | **0** | **0** |

`./gradlew test` (29 min 16 s) puis `./gradlew build` : tous deux `BUILD SUCCESSFUL`.
**184 fichiers XML** écrits — c'est la preuve que la suite s'est déroulée entièrement.

### Une erreur de méthode de ma part, et sa correction

Une exécution antérieure a rapporté **7 échecs** (4 Hub, 2 PvP, 1 commande admin). J'ai lancé des
compilations Gradle **pendant** son déroulement, ce qui remplace les fichiers de classes sous les
pieds de la tâche de test. Une seconde exécution, également perturbée, n'a produit que **2** fichiers
XML et aucun nom de test : inexploitable.

Les deux logs sont conservés tels quels (`NON-CONCLUANT-t200b.log`) et **aucune conclusion n'en a
été tirée**. Seule l'exécution propre ci-dessus compte, et aucun de ces 7 échecs n'y réapparaît.

### Couverture

| Suite | Cas | Ce qu'elle verrouille |
|---|---|---|
| `HubWorldProtectionListenerTest` | +6 | **builder sans OP** autorisé dans son Hub, **refusé dans un autre Hub**, joker testé explicitement (Bukkit ne développe pas `.*`), aucun bypass combat, ombrelle qui marche encore, et `build.wild` qui n'autorise rien dans le Hub |
| `RpgAdminTestShortcutsCommandTest` | +6 | branche PNJ accessible à un éditeur, **8 autres branches refusées** (dont `player resetnew`), nœud d'entrée qui n'autorise rien, refus nommant le nœud manquant, nœud précis par sous-action, ombrelle |
| `BridgeProvenanceTest` | 16 | nommage stable des groupes du pont, **un groupe externe accordant exactement les mêmes droits n'est jamais reconnu comme le nôtre**, ombrelle et `admin.debug` non distribuables, nœuds externes refusés **avec motif**, **le monde fait partie de l'identité du droit**, et absence de LuckPerms qui ne produit jamais un faux succès |
| `McBridgeDirectoryTest` | 22 | liaison par UUID sur vraie base SQLite, joueur hors ligne, UUID invalide, **conflit impossible**, modification, dissociation, droits par monde, **build sans monde refusé**, ombrelle et externes refusés, remplacement exact, **même nœud dans deux mondes = deux droits**, union des groupes, **survie à la réouverture de la base** |
| `AgentActionExecutorTest` | +5 | les quatre actions du pont, dont une synchronisation sans effet rapportée comme **échec** |

---

## 6. Déploiement

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| **JAR VeryGames DEV** | SHA-256 `bdd8b0007500f4d45a208e9f1d1505af4701b2cae31a14b1225f01eaba929f09` (1 849 640 o, commit `b94b689`) | `DEPLOY_EXIT=0`, `JAR en ligne : 1849640 octets (== local)` |
| **LuckPerms** | `LuckPerms-Bukkit-5.5.87.jar`, SHA-256 `09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406` (1 509 479 o) | taille en ligne **identique** au local |
| **Control Panel AWS** | distribution `20261006-124521` | `PANEL_EXIT=0`, `/health` → `{"panel":"ONLINE"}` |

**Sauvegardes préalables** : JAR précédent `rpgquest-20261006T104448Z-predeploy.jar`
(1 825 931 o, SHA-256 `5b2d7b85…`) ; inventaire du dossier `plugins/` **avant** l'installation de
LuckPerms conservé dans le dossier de backups. LuckPerms était **absent** : première installation,
rien à écraser.

**Un seul redémarrage** (`RESTART_EXIT=0`) pour le JAR **et** LuckPerms : arrêt **constaté OFFLINE**,
retour **constaté ONLINE**. Par RCON : `plugins` → **5 plugins verts** (Citizens, **LuckPerms**,
Multiverse-Core, RPGQuest, WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`.

### Chargement réellement vérifié, de bout en bout

Le numéro de version ne change pas d'un lot à l'autre, et les commandes `lp` ne renvoient rien
d'exploitable par RCON. J'ai donc fait répondre la chaîne complète : une action `mc.group.sync`
enfilée pour un groupe de sonde.

| Sonde | Réponse réelle du serveur |
|---|---|
| `mc.group.sync` (1 droit, monde `world_hub`) | **SUCCESS** — « 1 droit(s) ajouté(s), 0 retiré(s) » : le type est reconnu, LuckPerms a été écrit |
| la **même** action rejouée | **SUCCESS** — « Groupe déjà conforme : aucun droit modifié » → **idempotence prouvée sur le serveur réel** |
| `mc.group.delete` | **SUCCESS** — « ses membres perdent exactement les droits qu'il portait, et rien d'autre » |

Groupe de sonde supprimé, lignes d'action retirées de la base du panel. **Aucun compte, aucun droit
du propriétaire, aucun joueur réel touché.**

---

## 7. Limites et validation restante

### Limites

- **`rpgquest.build.wild` n'a aucun effet** : le Wild est déjà libre. Déclaré pour la convention,
  non câblé pour ne pas créer un bypass interdit.
- **Plusieurs Hubs dans un même monde ne sont pas distinguables** (voir §2).
- **Le reste de `/rpgadmin` n'est pas découpé** : seules les branches `npc` ont des nœuds dédiés, le
  reste exige l'ombrelle. C'est le périmètre minimal demandé ; le découpage fin appartient au
  backlog #27.
- **Aucune auto-liaison publique** : un pseudonyme ne prouve rien, et le portail de vérification
  n'est pas dans ce lot.
- **La collision avec un droit externe réel et sa persistance après redémarrage ne sont pas
  automatisées** : elles exigent un vrai LuckPerms, absent au test (API en `compileOnly`). La
  garantie est structurelle et testée sur sa logique ; la vérification en conditions réelles est
  **TC-253 section C**, et je ne l'annonce **pas** comme validée.
- **Aucun OP automatique** nulle part, conformément à la consigne.

### Protocole manuel

**TC-253** dans `docs/MANUAL_TEST_PLAN.md` — 34 étapes, 7 sections. **Aucune case cochée.**

- **A** — builder de Hub **sans OP** : build autorisé dans son Hub, refusé dans les claims d'autrui,
  dans une zone protégée, sur un waypoint, et aucune commande admin (10 étapes) ;
- **B** — éditeur PNJ sans build : branche PNJ seule, resets/économie/flatten refusés, et le rappel
  que `npc tag` **ne crée pas** un PNJ Citizens (6 étapes) ;
- **C** — **collision avec un droit externe identique** : accorder le même nœud et le même monde à
  la main, retirer le groupe, dissocier, **redémarrer**, et vérifier que le droit externe survit
  (8 étapes) ;
- **D** — idempotence et distinction voulu/réel (3 étapes) ;
- **E** — pont indisponible : **aucun faux succès** (2 étapes) ;
- **F** — compatibilité des administrateurs existants et **#22 intact** (2 étapes) ;
- **G** — permissions panel et audit, dont 403 sur routes directes (3 étapes).

Nettoyage décrit, y compris la vérification qu'il ne reste **aucun** nœud `rpgq-` sur le compte de
test.

### État final

- **Minecraft DEV** : **ONLINE**, **5 plugins verts** dont LuckPerms, JAR `bdd8b000…` chargé.
- **Panel AWS** : `active`, `/health` → 200, distribution `20261006-124521`.
- **Redémarrages Minecraft** : **un seul**, groupé pour le JAR et LuckPerms.
- **Dépôt** : arbre propre, branche poussée, tests et build verts.
