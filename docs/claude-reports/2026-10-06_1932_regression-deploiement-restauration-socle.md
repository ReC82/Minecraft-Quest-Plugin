# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-06
* Heure : 19:32 (heure locale, CEST)
* Sujet : Régression de déploiement — une branche obsolète a écrasé 49 commits déjà déployés ; cause, vérification des données, et restauration réunissant le socle et l'édition de dialogues
* Statut : DONE
* Branche Git : `feature/169-special-mobs-boss`
* Commit actuel si disponible : `e0c1203` (poussé) + le commit de ce rapport
* Début de la tâche : 2026-10-06 18:48:48
* Fin de la tâche : 2026-10-06 19:32:40
* Durée totale : 00:43:52

## Demande

Régression constatée après mon déploiement précédent : les entrées Boss/Mobs ont
disparu du panel. Vérifier en priorité si j'ai remplacé la version
`feature/169-special-mobs-boss` par une version `feat/control-panel-admin-tools`
n'intégrant pas les fonctionnalités déjà déployées. Comparer release précédente
et actuelle (navigation, routes Boss/Mobs, permissions owner, code, migrations),
vérifier que les données sont toujours présentes, contrôler aussi LuckPerms #200
(`4fc5985`) et le refresh `/ops` #95 (`1e0cbfc`). Établir la cause, puis
restaurer une version réunissant les fonctionnalités précédentes **et** la
nouvelle édition des dialogues — sans se contenter de rétablir les liens du menu.
Préserver données, clés/config et les 5 fichiers de contenu locaux. Construire
depuis un worktree propre, sans sous-agents, monitors ni Gradle parallèle.
Tester, pousser, déployer ; ne redémarrer Minecraft que si le plugin doit être
remplacé. Vérifier après déploiement Boss/Mobs avec owner, `/ops` et l'éditeur de
Jeff. Aucun ticket fermé ni test utilisateur coché.

## Analyse

### Cause — l'hypothèse de l'utilisateur est exacte

C'est **ma** régression. Le déploiement de ~18:09 CEST a livré
`feat/control-panel-admin-tools` @ `5f71819`. Cette branche a forké à `ce1233d`
et **n'a jamais reçu les 49 commits** accumulés sur
`feature/169-special-mobs-boss` @ `c253fd3`, qui était la ligne réellement
déployée. J'ai donc livré, plugin **et** panel, un état antérieur.

Ce n'est pas un problème de liens de menu : la route, le code, les permissions et
les services correspondants étaient absents de l'artefact.

| Vérification | Résultat |
|---|---|
| Route `/mobs` + `NavItem("Mobs spéciaux & boss")` | présentes dans 169, **totalement absentes** de ma branche |
| `Permission.MOB_READ` / `MOB_WRITE` / `MOB_TEST_SPAWN` | absentes de l'enum livrée, avec `CONTENT_DELETE`, `ECONOMY_READ/WRITE`, les permissions OP… |
| LuckPerms #200 (`4fc5985`), groupes #199 | absents du code livré |
| Refresh `/ops` #95 (`1e0cbfc`) | absent, comme toute la page `/ops` (#95, `c7962f4`) |
| Migrations | ma branche plafonne à **V23**, la base est en **V26** |
| Permissions du compte owner | intactes en base (`role = OWNER`) ; `Role.OWNER` vaut `EnumSet.allOf(Permission.class)`, donc `MOB_READ` disparaissait **avec le code** et revient avec lui |

Au total, 49 commits : #169/#171/#172/#190 (mobs et boss), #200, #199, #95, #16,
#140, #194, #196, #195, #179, #165, #131, #210, #12, #22, #168, #191,
#162/#163/#164, #192.

**Signal que j'avais vu sans l'exploiter.** Mon rapport précédent notait que le
JAR sauvegardé pesait 1 850 699 o contre 1 622 803 o pour le mien, et le
qualifiait de « constat brut, non expliqué ». C'était exactement le symptôme : je
livrais un artefact plus pauvre. J'aurais dû m'arrêter là, et faire l'audit Git
sur **toutes** les branches avant de déployer, pas seulement sur la mienne.

### Données — aucune perte, vérifié point par point

- **Schéma SQLite du jeu** : `SchemaMigrationRunner.run()` ignore toute migration
  dont la version est ≤ version appliquée, et n'écrit jamais une version
  inférieure. Un plugin V23 sur une base V26 **ne peut pas** rétrograder le
  schéma ; il ignore simplement les tables qu'il ne connaît pas.
- **Base du panel** (`/var/lib/plugadmin/control-panel.db`) : 3 comptes dont
  l'OWNER, 598 entrées d'audit, et **toutes** les tables de #199/#200 toujours
  présentes (`panel_group`, `panel_group_permission`, `panel_user_group`,
  `panel_group_mc_node`, `panel_user_minecraft`).
- **Les tables de groupes sont vides, et ce n'est pas moi.** L'audit log montre
  que l'owner a lui-même supprimé ses groupes de test — `tc252_ecriture` et
  `tc252_lecture` à 11:24 et 11:26 UTC, `tc253_builder` à 12:20 UTC — soit
  presque **4 heures avant** le déploiement fautif (16:09 UTC). Le panel régressé
  ne contient aucun code touchant ces tables : il ne pouvait pas les vider.
- **Profils de mobs** : stockés en fichiers YAML côté serveur
  (`SpecialMobDefinitionStore`), et **aucun** de mes déploiements n'a jamais
  transféré de fichier de contenu (jamais de `--also`, uniquement le JAR).
- **Écritures suspectes après la régression** : aucune. Les seules entrées
  d'audit postérieures à 16:09 UTC sont deux `login.success` de l'owner — lui
  constatant la panne.
- **Seule conséquence réelle** : pendant la fenêtre (~18:09 → ~19:30 CEST), les
  fonctions de ces 49 commits étaient indisponibles ; une quête terminée n'aurait
  pas crédité de récompense monétaire (#16 absent). 0 joueur connecté sur la
  quasi-totalité de la fenêtre.

### Ce que la comparaison des branches a aussi révélé

`feature/169-special-mobs-boss` contient **#195 — « couleurs et styles au clic,
sans écrire de MiniMessage »**, livré le 2026-10-05 : un composant partagé
`StyleField` (16 couleurs, gras/italique/souligné/barré, aperçu), **déjà appliqué
aux textes de nœuds de dialogue**, et portant déjà la règle « un texte
multi-styles n'est jamais aplati sans geste explicite ».

Mon rapport précédent affirmait que « le composant à pastilles n'était rendu par
aucun code Java ». C'était vrai **de ma branche**, et faux du produit déployé :
#195 l'avait précisément corrigé. J'ai donc reconstruit en parallèle un composant
qui existait déjà en mieux. La fusion supprime mon doublon.

## Travail effectué

### 1. Audit et diagnostic

`git fetch --all`, comparaison des tips, `merge-base`, puis inventaire des
commits manquants (`git log A..B`), des routes, de l'enum `Permission`, des
niveaux de migration, et interrogation directe de la base du panel et de l'audit
log pour statuer sur les données.

### 2. Restauration par fusion

Fusion de `feat/control-panel-admin-tools` dans
`feature/169-special-mobs-boss` (commit `dfe642b`), dans le worktree propre
`/srv/rpgquest/worktree-169`. Six conflits, dont trois résolutions non
mécaniques :

- **`StyleField` devient le composant de couleur du texte de choix.** Mon
  `miniMessageField` (Java) et mon module `initMiniMessageFields` (~117 lignes de
  JS, avec sa table de couleurs dupliquée) sont **supprimés**. Le texte d'un
  choix passe par `StyleField.render(...)`. C'est ce que la demande d'origine
  réclamait — « utiliser le sélecteur de couleur déjà employé ailleurs » — et
  cela respecte la règle du projet contre la duplication.
- **Collision silencieuse sur `option(...)`.** Les deux branches avaient ajouté
  un helper privé `option` à trois `String`, mais **dans un ordre de paramètres
  différent** (`(value, label, selected)` chez moi, `(value, current, label)`
  chez #169). Git fusionnait sans conflit ; seule la compilation l'a révélé
  (« already defined »). Si les signatures avaient différé d'un type, le code
  aurait compilé en inversant silencieusement libellés et sélection. J'ai
  conservé le helper préexistant, avec tous ses appelants, et réécrit mes six
  appels dans son ordre.
- **`/quests/edit`** : bandeau « source → publication → rechargement » de #131
  **et** garde-fou de relecture de #145, les deux conservés.
- Fiches de recette : TC-231 était déjà pris par les mobs ; la mienne devient
  **TC-254**. Index des rapports et changelog : les deux historiques conservés.

### 3. Alignement du test devenu faux

`DialoguesCatalogTest` affirmait les marqueurs `data-mm-*` de mon composant
supprimé. Il vérifie désormais le contrat réellement rendu : nom du champ soumis
(`choice_text`) et marqueurs `data-sf-store` / `data-sf-palette` /
`data-sf-preview` de `StyleField` (commit `e0c1203`).

## Fichiers créés

* `docs/claude-reports/2026-10-06_1932_regression-deploiement-restauration-socle.md` (ce rapport)

## Fichiers modifiés

Par la fusion et ses résolutions :

* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/AgentPages.java`
  (StyleField pour le texte de choix, suppression de `miniMessageField`, appels
  `option(...)` réordonnés)
* `control-panel/src/main/java/com/lodygames/rpgquest/panel/web/ContentEditorPages.java`
  (bandeau #131 + garde-fou #145)
* `control-panel/src/main/resources/assets/panel.js` (suppression du module
  dupliqué ; `initStyleFields` et les modules `/ops` conservés)
* `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/DialoguesCatalogTest.java`
* `docs/MANUAL_TEST_PLAN.md` (TC-231 → TC-254), `docs/claude-reports/README.md`,
  `docs/deployment/SERVER_CHANGELOG.md`

Tout le reste de la fusion est repris sans modification des deux côtés.

**Non modifiés** : `src/main/resources/quests/crystal_hunt.yml` et les quatre
fichiers de contenu non suivis de l'utilisateur, qui vivent dans le checkout
principal `/srv/rpgquest/repo` et n'ont jamais été touchés par ce travail (fait
dans un worktree distinct).

## Base de données / migrations

Aucune migration ajoutée. La base du jeu reste en **V26** ; le JAR restauré
connaît V26, donc le runner n'applique rien. La base du panel est inchangée.

## Configuration / données

Aucune clé, aucun secret, aucune configuration touchés. Aucun fichier de contenu
transféré au serveur.

## Tests automatiques

Sur le worktree propre `/srv/rpgquest/worktree-169`, `RPGQUEST_TEST_MAX_HEAP=768m` :

* `./gradlew test` + `./gradlew build` → **BUILD SUCCESSFUL**
* **1705 tests plugin, 0 échec, 37 ignorés**
* **675 tests panel, 0 échec, 1 ignoré**

Le premier passage avait 1 échec : `DialoguesCatalogTest`, sur les marqueurs de
mon composant supprimé — corrigé puis suite relancée intégralement.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION`. **Je ne me suis connecté à aucun compte du panel**
(je n'ai pas les identifiants de l'owner, et je n'ai pas créé de compte
privilégié pour l'occasion). Restent donc à vérifier en navigateur, avec la
session de l'owner :

1. **Boss/Mobs** : l'entrée « Mobs spéciaux & boss » réapparaît dans le menu, la
   page `/mobs` liste les profils existants, et le throttle Wild est celui
   configuré (fiches TC-231/TC-232/TC-239 déjà au plan).
2. **`/ops`** : la page s'affiche et le refresh n'efface plus la saisie ni
   n'empile le résultat (#95).
3. **Éditeur de Jeff** : sous le choix « Je vais m'en charger », le formulaire
   s'ouvre, les sélecteurs d'action de quête et de condition d'état sont
   pré-remplis, et le texte utilise le composant de couleur partagé (TC-254).
4. Accessoirement, LuckPerms #200 (TC-253) reste `PENDING`.

## Résultat attendu

Le panel et le plugin retrouvent l'intégralité des fonctionnalités déployées
avant ma régression, **plus** l'édition des choix de dialogue, avec un seul
composant de couleur partagé au lieu de deux.

## Reset / retour à l'état initial

Aucun reset nécessaire : aucune donnée n'a été perdue ni modifiée.

## Déploiement VeryGames

### À transférer

* **Plugin, VeryGames DEV** : `rpgquest-0.1.0-SNAPSHOT.jar` — 1 859 272 o,
  SHA-256 `2d728eb90c11992376e5759ee5c51724faf600f0e64e76d325941e8982037219`.
  Transféré, taille en ligne vérifiée identique.
* **Control Panel, AWS** : release `/opt/plugadmin/releases/20261006-192902`
  sauvegardée, service redémarré, `/health` → ONLINE.

Construit et transféré depuis le worktree propre, au commit poussé `e0c1203`.

### Ne PAS transférer/altérer

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les
mondes, et tous les fichiers de contenu (`dialogues/`, `quests/`, `stories/`,
profils de mobs). Aucun `--also` utilisé.

### Redémarrage requis

Oui — le JAR change, donc le redémarrage était la condition posée. 1 joueur était
connecté (`LoDyMcFly`) : **prévenu en jeu** par deux annonces RCON, puis
`scripts/verygames-restart.sh --timeout 240` (`save-all`, OFFLINE, ONLINE).

Vérifications : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 5 plugins
verts dont **LuckPerms** ; `rpgquest reload` → OK ; heartbeat agent
`uptime_seconds=45` (redémarrage réel), `world_hub`/`claims`/`wild` chargés ;
`/mobs` et `/ops` répondent **303** (route protégée) quand un chemin inconnu
répond 404 ; l'artefact panel installé contient `MOB_READ`, `CONTENT_DELETE`,
`ECONOMY_READ` et `StyleField`.

### Migration automatique

Aucune.

## Rollback

**Attention** : `scripts/rollback-verygames.sh --latest` restaurerait le JAR
**régressé** (`rpgquest-20261006T172836Z-predeploy.jar`, 1 622 803 o) — à ne pas
utiliser tel quel. Le dernier JAR sain d'avant la régression est
`rpgquest-20261006T160901Z-predeploy.jar` (1 850 699 o), à désigner
explicitement. Control Panel : `scripts/plugadmin/rollback.sh app`.

## Logs / diagnostic

Aucune instrumentation ajoutée. Preuves utilisées : `git log A..B`, l'enum
`Permission` de l'artefact déployé (lue dans le JAR installé), l'audit log du
panel, le heartbeat de l'agent, et les codes HTTP des routes.

## Documentation mise à jour

* `docs/deployment/SERVER_CHANGELOG.md` — entrée « restauration » : cause,
  vérification des données, SHA livrés et sauvegardés, redémarrage, mise en garde
  sur le rollback.
* `docs/MANUAL_TEST_PLAN.md` — ma fiche renumérotée TC-254 (TC-231 appartenant
  aux mobs).
* `docs/claude-reports/README.md` — index.

## Limitations / travail restant

* **Les trois vérifications demandées en navigateur restent à faire par
  l'utilisateur** (Boss/Mobs avec owner, `/ops`, éditeur de Jeff). Je n'ai pas de
  session owner et n'ai volontairement pas créé de compte privilégié pour en
  obtenir une : je ne déclare donc rien de validé de ce côté.
* L'énumération des profils de mobs n'est pas constatable par RCON ; seule la
  page `/mobs` la montre.
* Les branches `feat/control-panel-admin-tools` et
  `feature/169-special-mobs-boss` restent distinctes. La seconde contient
  désormais la première ; la première est en retard et **ne doit plus être
  déployée**.
* `CLAUDE.md` désigne encore `feature/23-mod-prototype` comme branche
  d'intégration (« mention temporaire »). C'est faux depuis longtemps : la ligne
  réellement déployée est `feature/169-special-mobs-boss`. Ne pas corriger en
  effet de bord — c'est une décision de workflow qui appartient à l'utilisateur,
  mais elle a directement contribué à cette régression.
* Le working tree principal reste sale (5 fichiers de contenu de l'utilisateur).

## Prochaine étape suggérée

Valider les trois points en navigateur, puis trancher la question de la branche
d'intégration : mettre `CLAUDE.md` à jour pour désigner explicitement la branche
réellement déployée, afin qu'aucune session future ne reparte d'une branche
latérale en croyant être à jour.
