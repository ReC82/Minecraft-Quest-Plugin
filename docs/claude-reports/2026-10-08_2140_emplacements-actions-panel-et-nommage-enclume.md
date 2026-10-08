# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-08
* Heure : 21:40 (CEST)
* Sujet : #227 — actions de « Bâtiments → Emplacements » qui renvoyaient sur « Agents », et création en jeu en deux temps avec nommage dans une enclume vanilla
* Statut : DONE
* Branche Git : `fix/227-building-sites-actions` (poussée, **jamais fusionnée**)
* Commit actuel si disponible : `b0ca263` (+ un commit de documentation de clôture)
* Début de la tâche : 2026-10-08 20:28:09
* Fin de la tâche : 2026-10-08 21:40:41
* Durée totale : 01:12:32

---

## Demande

Deux sujets dans un lot, à la suite de la validation manuelle de #213 (emplacements de
construction, livrés plus tôt le même soir).

**1. Issue #227** — *« actions du panel redirigent vers Agents et ne s'appliquent pas »*. Toutes les
actions de la page Emplacements (renommer, décrire, orienter, supprimer, rafraîchir) renvoyaient sur
`/agents?agent=rpgquest-dev`. Consigne explicite : **« Ne corrige PAS uniquement la redirection »**,
auditer le chemin complet (formulaire → POST → handler → action agent → résultat → relecture →
retour), **« Aucune action BuildingSite ne doit utiliser /agents comme parcours normal »**, et
**« Ajouter des tests HTTP qui reproduisent EXACTEMENT les formulaires réels, pas uniquement les
services sous-jacents »**.

**2. Création en jeu améliorée** — outil → clic → ancre/orientation temporaires → formulaire de nom
→ confirmation → création persistante. **« Si le joueur annule/ferme : AUCUN BuildingSite ne doit
être créé. »** Pour la saisie du nom : **« Étudier en priorité une GUI ENCLUME vanilla »**,
préférence **« GUI enclume > autre GUI vanilla > chat »**, **« utiliser uniquement API publique
Paper/Bukkit compatible avec la version installée. Pas de NMS. Auditer l'API réellement disponible
avant implémentation. »** Demande en attente non persistante, TTL 60 s max, nom obligatoire 1–64
caractères Unicode, **« Ne pas dériver l'ID technique du nom humain »**, avertissement de voisinage
**« si cela reste simple à implémenter »** mais **« ne bloque pas systématiquement »**.

Gouvernance : commit, push, déploiement DEV (JAR + panel + redémarrage si nécessaire), **« Ne merge
rien. Préserver tous les fichiers locaux non suivis. Ne toucher à aucun bâtiment/schematic : ce lot
ne place toujours AUCUNE construction. Un seul Gradle à la fois. Build final complet depuis worktree
propre. »** Préparer TC-269 ou mettre à jour TC-268. **« Ne commence pas ce lot suivant maintenant »**
(bibliothèque de schematics / `BuildingDefinition`).

---

## Analyse

### #227 : la cause, et une correction du ticket

La cause est une **liste blanche écrite à la main**. Après une action agent, `/agents/action`
renvoie l'utilisateur sur la page d'origine, filtrée par `PanelApp#safeReturnPath` — un filtre qui
existe pour empêcher une redirection ouverte vers un site tiers. Cette liste énumérait
`/home`, `/quests`, `/stories`, `/dialogues`, `/npcs`, `/diagnostics`… et **pas**
`/buildings/sites`, livrée le même jour. Toute action de la page retombait donc sur le `fallback`,
c'est-à-dire `/agents`.

**Mais la seconde moitié du titre de l'issue — « et ne s'appliquent pas » — est fausse.** C'est
établi par deux preuves indépendantes :

1. le **journal d'actions du serveur DEV** montre chaque `building.site.*` en `SUCCESS` avec son
   compte rendu : « renommé “Test hutte” », « orienté vers le nord », « supprimé », puis
   `building.site.list` → « 0 emplacement(s) » ;
2. la **sauvegarde de `data.db` prise avant ce déploiement** contient **4 emplacements pour 5
   identifiants alloués** — donc `buildsite_0001` a bien été supprimé par le panel, et son
   identifiant n'a pas été recyclé.

Le défaut était **entièrement dans le retour**. Mais comme le bouton « Rafraîchir » souffrait du
même défaut, il était impossible de revenir constater le résultat — ce qui se lit, très
raisonnablement, comme « rien n'est enregistré ». **Il n'y a rien à réparer en base.**

Corriger la seule redirection de #213 aurait laissé le défaut **armé pour la page suivante** : le
vrai problème est qu'une page pouvait être ajoutée à la navigation sans être ajoutée à cette seconde
liste, sans que rien ne le signale.

### Pourquoi les tests existants n'avaient rien vu

Les tests de `/buildings/sites` livrés avec #213 **écrivaient le corps de la requête à la main**.
Un corps écrit à la main contient exactement ce que le test croit nécessaire : il portait donc un
`return` correct **même quand la page, elle, en émettait un que le serveur refusait**. Le test
validait le service sous-jacent, pas le parcours de l'utilisateur. C'est précisément ce que le
prompt demandait de corriger.

### L'enclume : audit de l'API avant d'écrire

Le prompt exigeait d'auditer l'API réellement disponible. Fait **au `javap` sur le JAR Paper
réellement installé**, avant d'écrire la moindre ligne. Tout le nécessaire est public :

| Besoin | API publique |
|---|---|
| ouvrir la fenêtre | `HumanEntity#openAnvil(Location, boolean force)` |
| lire le texte tapé | `AnvilInventory#getRenameText()` |
| forcer un résultat cliquable | `PrepareAnvilEvent#setResult(ItemStack)` |
| annuler le coût en niveaux | `AnvilView#setRepairCost(int)` / `setMaximumRepairCost(int)` |

**Aucun NMS, aucune réflexion CraftBukkit.** Les deux dernières lignes ne sont pas cosmétiques :
sans elles, vanilla ne propose un résultat que si le nom diffère de l'original, **et réclame des
niveaux** — le bouton de validation serait inerte ou payant, et le joueur ne comprendrait pas
pourquoi. MockBukkit expose `AnvilInventoryMock#setRenameText`, donc l'orchestration est testable ;
seul le rendu chez le client ne l'est pas.

### Ce que les données réelles ont justifié

La sauvegarde de `data.db` montre que les **quatre** emplacements du DEV tiennent **dans un cube
d'un bloc de côté** (737/66, 736/66, 736/67, 737/67) et portent **tous** le nom par défaut
« Nouvel emplacement ». C'est le missclick que ce lot supprime, observé dans la vraie vie et non
supposé. Ils ont été **laissés intacts** : rien n'autorisait à les supprimer.

---

## Travail effectué

### #227 — retour dérivé de la navigation

`PanelApp.RETURN_PATHS` est désormais **construit depuis `Layout.nav()`** (plus `/content/import`
et `/ai/studio`, qui ne sont pas dans le menu). Toute page atteignable par la navigation est un
retour accepté **par construction** : ajouter une page ne demande plus de penser à une seconde
liste. Le filtre conserve son rôle anti-redirection ouverte — une cible hors du panel
(`https://…`, `//…`, `javascript:`, chemin inconnu, chaîne vide) retombe toujours sur `/agents`.

**Preuve que le garde-fou attrape bien #227** : le correctif a été temporairement remplacé par
l'ancienne liste en dur, puis les tests relancés. `ActionReturnPathTest` a échoué sur 2 tests sur 4,
et les 6 tests de formulaire réel ont tous échoué avec le symptôme **exact**
(`/agents?agent=rpgquest-dev&…`). Le correctif a ensuite été restauré et revérifié.

### Création en jeu en deux temps

Le clic droit **n'écrit plus rien**. Il calcule l'ancre et l'orientation, les retient dans
`PendingBuildingSiteRegistry` (**TTL 60 s, purement mémoire, non persistant**) et ouvre une enclume.

| Geste du joueur | Ce qui est écrit |
|---|---|
| clic droit sur un bloc | **rien** |
| nom validé sur le résultat | l'emplacement, avec son nom |
| fenêtre fermée (Échap, inventaire) | **rien**, et c'est annoncé |
| 60 s sans valider | **rien** |
| déconnexion pendant la saisie | **rien** |

**Aucun identifiant n'est consommé avant l'écriture** : l'allocateur `AUTOINCREMENT` n'est appelé
qu'à la création réelle.

### Deux pièges traités

1. **Valider implique de fermer la fenêtre**, donc `InventoryCloseEvent` part *aussi* après un
   succès. Sans précaution, chaque création s'annoncerait « annulée » juste après avoir réussi. La
   demande est **retirée du registre** (`take`, atomique) au moment de confirmer : la fermeture qui
   suit ne trouve plus rien et se tait. C'est la même mécanique qui rend un **double clic** sur le
   résultat inoffensif — le second ne trouve plus de demande.
2. **L'anti-rebond de 500 ms a été déplacé** de l'écriture vers le chemin du clic (`acceptClick`).
   Laissé dans `create`, il aurait **avalé en silence** la confirmation d'un joueur validant son nom
   moins de 500 ms après avoir cliqué. Attrapé au raisonnement, pas par le compilateur ; verrouillé
   par le test `writingIsNeverDebounced`.

### Anti-missclick

- **Même ancre** → la fenêtre **ne s'ouvre même pas** (inutile de faire taper un nom pour annoncer
  ensuite qu'il n'y a rien à créer), et la règle est **revérifiée à la confirmation** : un autre
  administrateur a pu marquer ce bloc pendant la saisie.
- **Voisin immédiat** (les 26 cases du cube autour, même monde) → **avertissement** nommant
  l'emplacement concerné, invitant à fermer si c'était un clic de travers. **Jamais un refus**, et
  **aucune distance minimale inventée**, conformément au prompt.

### Le nom

Obligatoire, trimé, **1 à 64 caractères**, Unicode accepté. Un nom vide ou trop long est **refusé
sans fermer la fenêtre** : le joueur corrige sur place au lieu de retourner cliquer dans le monde.
**Jamais tronqué en silence.** Et **jamais source de l'identifiant technique** : `buildsite_0001`
reste attribué par l'allocateur, donc renommer ne casse aucune référence.

### Code mort supprimé

`BuildingSiteToolListener#rootName`, devenu inutile quand `announce()` a quitté ce listener.

---

## Fichiers créés

| Fichier | Rôle |
|---|---|
| `src/main/java/com/lodygames/rpgquest/building/PendingBuildingSite.java` | la demande en attente (joueur, monde, ancre, orientation, bloc cliqué, ouverture, expiration) |
| `src/main/java/com/lodygames/rpgquest/building/PendingBuildingSiteRegistry.java` | registre mémoire, horloge injectée, expiration à la lecture, `take` atomique, purge |
| `src/main/java/com/lodygames/rpgquest/building/BuildingSiteName.java` | validation du nom (fonction pure) |
| `src/main/java/com/lodygames/rpgquest/building/BuildingSiteNamePrompt.java` | ouverture de l'enclume, objet-marqueur PDC, lecture du texte tapé |
| `src/main/java/com/lodygames/rpgquest/building/BuildingSiteNameListener.java` | orchestration : `PrepareAnvil`, clic sur le résultat, fermeture, déconnexion |
| `src/test/java/com/lodygames/rpgquest/building/PendingBuildingSiteRegistryTest.java` | 16 tests |
| `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/ActionReturnPathTest.java` | 4 tests, dont le garde-fou qui parcourt la navigation réelle |

## Fichiers modifiés

| Fichier | Changement |
|---|---|
| `control-panel/.../panel/web/PanelApp.java` | **le correctif #227** : `RETURN_PATHS` dérivé de `Layout.nav()` |
| `control-panel/.../panel/web/BuildingSitesPageTest.java` | +8 tests qui **extraient et soumettent les formulaires rendus** |
| `src/.../building/BuildingSiteService.java` | `confirm(...)`, `adjacentTo(...)`, `acceptClick(...)` ; `create` ne débounce plus ; `CreateOutcome` + `EXPIRED`/`INVALID_NAME` |
| `src/.../building/BuildingSiteToolListener.java` | le clic prépare et ouvre l'enclume au lieu d'écrire ; code mort retiré |
| `src/.../building/model/BuildingSite.java` | `created(...)` prend le nom |
| `src/.../bootstrap/RPGQuestBootstrap.java` | registre, second écouteur, tâche de purge (toutes les 30 s) |
| `src/test/.../building/BuildingSiteServiceTest.java` | 43 tests (anti-rebond réécrit, `confirm`/`adjacentTo` couverts) |
| `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md` | parcours en deux temps, API auditée, pièges, cause réelle de #227 |
| `docs/deployment/SERVER_CHANGELOG.md` | entrée du lot + déploiement réellement effectué |
| `docs/MANUAL_TEST_PLAN.md` | **TC-268 corrigé** + **TC-269 ajouté** |
| `control-panel/src/main/resources/docs/batiments-emplacements.md` | marche à suivre réécrite (enclume, matrice d'annulation) |
| `.ai/ROADMAP.md` | entrée du lot |

---

## Base de données / migrations

**Aucune migration dans ce lot.** `V28` (tables `building_sites` et `building_site_ids`) a été
appliquée par le lot précédent, et vérifiée ici sur la base réelle **avant et après** déploiement :
`user_version = 28` dans les deux cas, 35 tables, `integrity_check = ok`.

`PendingBuildingSiteRegistry` est **purement mémoire** : aucune table, aucune colonne, aucune
écriture. Un redémarrage pendant une saisie de nom perd la demande — c'est le comportement voulu.

## Configuration / données

Aucun changement de configuration. Aucune donnée existante lue, réécrite ni corrigée. Les **4
emplacements** présents sur le DEV ont été laissés **intacts**, avec leurs noms, positions et
orientations.

---

## Tests automatiques

`./gradlew clean build` depuis un **worktree propre** sur `b0ca263`, **un seul Gradle à la fois** :

```
BUILD SUCCESSFUL in 34m 54s
```

| Module | Tests | Échecs | Ignorés |
|---|---|---|---|
| plugin | **1987** (+31) | 0 | 37 |
| control-panel | **1045** (+12) | 0 | 1 |
| web-api | **30** | 0 | 0 |
| **Total** | **3062** | **0** | **38** |

**Aucun test existant assoupli.**

Ce que les nouveaux tests couvrent réellement : expiration (avant/après l'échéance, sans la tâche de
purge), consommation atomique (double clic), annulation, purge, TTL par défaut sur valeur nulle ou
négative, confirmation refusée pour demande expirée / nom invalide / doublon d'ancre, voisinage,
**le fait qu'une écriture n'est jamais anti-rebondie**, et les retours du panel sur les formulaires
réellement rendus (succès, refus, double soumission, bouton Rafraîchir).

**Vérification négative effectuée** : avec l'ancienne liste en dur réintroduite, 2 tests sur 4 de
`ActionReturnPathTest` et les 6 tests de formulaire réel échouent avec le symptôme exact de #227.

## Tests manuels à effectuer

**TC-269** *(nouveau, ~6 min, `PENDING MANUAL VALIDATION`)* — les quatre façons d'annuler, l'absence
d'identifiant gaspillé, le nom refusé qui laisse la fenêtre ouverte, l'avertissement de voisinage
qui ne bloque pas, et le retour du panel sur sa page pour les cinq actions.

**TC-268** *(corrigé, ~5 min, `PENDING MANUAL VALIDATION`)* — ses étapes de marquage décrivaient un
clic qui créait immédiatement : ce n'est plus le produit. Il **n'avait jamais été exécuté**, donc il
a été corrigé plutôt que doublé — un TC périmé est pire que pas de TC.

Ce qui n'est **pas** vérifiable depuis la machine de build : le **rendu de l'enclume chez le
client**. Aucune commande RCON ne simule un clic droit sur un bloc ni l'ouverture d'une fenêtre
d'inventaire.

## Résultat attendu

En jeu : clic droit avec l'outil → l'enclume s'ouvre et le chat annonce la position préparée ; nom
tapé puis clic sur le résultat → message vert avec nom, identifiant, monde, position, orientation.
Fermer, laisser expirer ou se déconnecter → **aucun emplacement, aucun identifiant consommé**.

Dans le panel : les cinq actions de *Bâtiments → Emplacements* reviennent **sur cette page**, avec
leur bandeau de suivi — y compris en cas de refus, et y compris « Rafraîchir ».

## Reset / retour à l'état initial

Rien à réinitialiser : le lot n'introduit aucune donnée. Un emplacement créé par erreur pendant un
test se supprime depuis la fiche du panel (idempotent, aucun bloc touché). Les demandes en attente
disparaissent d'elles-mêmes au bout de 60 secondes, et à chaque redémarrage.

---

## Déploiement VeryGames

**Effectué sur le DEV le 2026-10-08 de 21:35 à 21:40 (CEST)**, depuis un worktree propre sur
`b0ca263`, après vérification que la branche est un **superset** de toutes les lignes déployées
récentes.

### À transférer

- `build/libs/rpgquest-0.1.0-SNAPSHOT.jar` → racine des plugins (**2 042 328 o**, SHA-256
  `6407fc8b…`, vérifié **identique** en ligne).
- Le **Control Panel** est un déploiement **séparé** : `scripts/plugadmin/deploy.sh`
  (`PANEL_DEPLOY_EXIT=0`, `/health` → `ONLINE`). Il porte le correctif #227 — sans lui, les actions
  continueraient de renvoyer sur « Agents ».

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes, les autres
plugins. **Aucun bâtiment, aucun schematic, aucun bloc** : ce lot ne place toujours rien. Les 4
emplacements existants ne doivent pas être supprimés.

### Redémarrage requis

**Oui, et un seul.** Les deux écouteurs et la tâche de purge sont enregistrés au démarrage du
plugin. `LoDyMcFly` était connecté : **prévenu en jeu** avant l'arrêt, et le script a exécuté
`save-all`. Après redémarrage : RPGQuest **en vert**, Citizens en vert.

### Migration automatique

**Aucune.** `user_version` vaut **28 avant et après**, 35 tables dans les deux cas, et les 4
emplacements comme les 5 identifiants alloués sont intacts.

## Rollback

- **Plugin** : `scripts/rollback-verygames.sh --latest` restaure
  `rpgquest-20261008T193720Z-predeploy.jar` (2 027 547 o, SHA-256 `96fd7ac9…` — le JAR de #213), puis
  redémarrer. Le clic droit redevient créateur immédiat ; les emplacements créés entre-temps
  **restent**, aucune donnée n'étant propre au nouveau parcours.
- **Panel** : `scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`. **Attention :
  cela réarme #227** — les actions de la page Emplacements renverraient de nouveau sur « Agents »
  tout en continuant de s'appliquer.
- Sauvegarde de données disponible : `data-20261008T193630Z-predeploy.db`, **relue et vérifiée**
  (V28, `integrity_check = ok`, 35 tables). La sauvegarde précédente n'a pas été écrasée.

## Logs / diagnostic

Vérifié par RCON après redémarrage : `plugins` → RPGQuest et Citizens **en vert** ;
`rpgadmin buildsite list` → refus attendu « cette commande doit être exécutée par un joueur en jeu »
(la commande est donc bien enregistrée).

Vérifié sur ce qui est **servi** par le panel, et non seulement sur ce qui a été bâti : `javap` sur
le JAR de `/opt/plugadmin/app` montre `safeReturnPath` qui lit `Set.contains(RETURN_PATHS)` — et
**non plus un `switch`** — ainsi que la méthode `returnPaths()` ; `GET /buildings/sites` répond
**303** vers `/login`, donc la route est enregistrée (un 404 aurait signalé un JAR périmé).

## Documentation mise à jour

`docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-268 corrigé, TC-269 ajouté), `.ai/ROADMAP.md`, et la fiche du centre
d'aide `control-panel/src/main/resources/docs/batiments-emplacements.md`.

---

## Limitations / travail restant

- **#227 reste OUVERTE.** La moitié « ne s'appliquent pas » de son titre est réfutée par les données,
  mais le parcours en jeu et le retour du panel réclament **TC-269**. Rien n'a été fermé.
- **Aucune prévisualisation de l'ancre** avant validation du nom : l'enclume occupe l'écran. La
  position préparée est annoncée dans le chat, faute de mieux.
- **Le titre de la fenêtre d'enclume n'est pas garanti.** Le prompt demandait « Nom de
  l'emplacement » ; `openAnvil` ouvre la fenêtre vanilla, dont le libellé vient du client. La
  consigne est donc donnée dans le chat, qui est le seul endroit sûr sans quitter l'API publique.
- L'**avertissement de voisinage est textuel**, pas visuel.
- `PendingBuildingSiteRegistry` est **mémoire seule** : un redémarrage pendant une saisie perd la
  demande. C'est le comportement voulu, pas une limite à corriger.
- Les messages de résultat sont envoyés depuis la continuation asynchrone de la base, comme le
  faisait #213 et comme le fait `SkillsCommand` (`Audience#sendMessage` de Paper est sûr hors thread
  principal). Choix **cohérent et délibéré**, mais le dépôt contient les deux styles : d'autres
  chemins repassent explicitement par le thread principal.
- Hors périmètre, non commencé, le prompt l'interdisant explicitement : bibliothèque de schematics,
  `BuildingDefinition`/`BuildingPlacement`, placement WorldEdit, génération IA de bâtiment.
- Dette rappelée : `MiniYaml` ne gère pas les scalaires repliés (`dialogues/guard.yml`) ;
  `RestartServiceTest` reste sensible au temps réel.

## Prochaine étape suggérée

**TC-269 puis TC-268** (~11 minutes à deux, un vrai client Minecraft requis). Ce sont les deux seuls
éléments qui peuvent établir que l'enclume s'ouvre, que le clic sur le résultat crée, et que la
fermeture ne crée rien.

Ensuite seulement, le lot suivant de #213 : **affecter un `.schem` à un emplacement**. L'identifiant
stable et jamais recyclé est déjà prêt pour cela.
