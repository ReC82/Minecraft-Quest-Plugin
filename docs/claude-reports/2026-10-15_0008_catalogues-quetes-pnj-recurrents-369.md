# RPGQuest — Rapport Claude

## Informations

* Date : 2026-10-15
* Heure : 00:08
* Sujet : Issue #369 — catalogues de quêtes secondaires attribuables aux modèles de PNJ récurrents (lot 1)
* Statut : DONE (lot 1 livré et déployé sur DEV ; validation en jeu PENDING)
* Branche Git : feature/369-catalogues-quetes-rencontres → PR #395 fusionnée dans chore/257-ci-github-actions
* Commit actuel si disponible : 4522183 (merge PR #395)
* Début de la tâche : 2026-10-14 22:51:11
* Fin de la tâche : 2026-10-15 00:07:40
* Durée totale : 01:16:29

## Demande

Session autonome lqorch (1bae8486) sur #369 : permettre de créer depuis PlugAdmin des catalogues de
quêtes secondaires réutilisables, de les attribuer à des modèles de PNJ récurrents (#13), avec une
proposition variée, stable et propre à chaque joueur, sans dupliquer les quêtes ni attribuer quoi
que ce soit sans acceptation.

## Analyse

Audit de l'existant #13 : modèles `encounters/*.yml` (`EncounterTemplate`, parseur, store, agent
`encounter.template.*`), dialogues `encounter_*.yml` à quête fixe (`QUEST_AVAILABLE` +
`START_QUEST`), délai `cooldown:` porté par `quest_progress.updated_at`, règle d'acceptation
unique `QuestProgressEngine#availability`, rendu de dialogue par `DialogueSessionEngine` (le
renderer ne relit jamais le nœud dans le fichier → nœuds synthétiques possibles).

Décisions :
- Catalogues en YAML (`quest-catalogs/*.yml`) édités via l'agent, même discipline que les modèles
  (le panel n'envoie que des champs ; parseur des fichiers = seule autorité). Pas de nouvelle source
  de vérité de quête : un catalogue ne contient que des références.
- Une seule nouvelle action de dialogue, `OFFER_CATALOG_QUEST`, qui rend un écran généré
  (Commencer / Plus tard / Refuser), plutôt que des conditions + placeholders dynamiques : surface
  minimale, réutilisable par n'importe quel dialogue.
- **Aucune migration** : l'offre est un tirage pondéré déterministe (rendez-vous pondéré, SHA-256)
  dont la graine est (joueur, npc_id, date de la dernière complétion d'une quête de ces catalogues).
  Elle est donc stable à la fermeture/réouverture, au relog, au changement d'occurrence et au
  redémarrage, et ne tourne qu'après une complétion. « Une quête à la fois » par PNJ.
- Les dialogues livrés `encounter_*.yml` ne sont **pas** modifiés (ils ne seraient de toute façon
  pas réécrits sur le serveur) : basculer un PNJ sur un catalogue est un choix d'administrateur.

## Travail effectué

Plugin :
- Package `encounter.catalog` : `QuestCatalog`, `QuestCatalogParser`, `QuestCatalogYaml`,
  `QuestCatalogLoader`, `QuestCatalogStore` (CREATE_NEW, écriture atomique, relecture, sauvegarde
  avant suppression), `YamlQuestCatalogEngine` (exemple `encounter_side_quests.yml` seedé une fois),
  `CatalogOfferSelector` (pur), `CatalogOfferService`.
- `EncounterTemplate.questCatalogs` + champ YAML `quest_catalogs` ; `withEnabled` pour que
  « Activer/Désactiver » conserve les catalogues.
- Action `OFFER_CATALOG_QUEST` (parseur, écrivain, vue catalogue, content pack + sérialiseur) ;
  `CatalogOfferNodes` + gestion dans `DialogueSessionEngine` (quête affichée mémorisée par joueur,
  acceptation unique via `accept` du moteur, anti-double-clic, message si l'offre est périmée).
- `QuestProgressEngine#completionTimes` (lecture asynchrone).
- Actions agent `quest.catalog.list|create|update|delete` (`QuestCatalogViews` : contrôles quête
  absente / non renouvelable / catalogue vide ou désactivé / sans modèle ; transverses : catalogue
  inconnu, dialogue sans offre, modèle actif sans catalogue). Avertissements à l'enregistrement d'un
  modèle (catalogue inconnu, dialogue sans offre).

PlugAdmin :
- Page `/quests/catalogs` (bouton depuis Quêtes et PNJ récurrents) : liste enrichie, contrôles,
  création, modification (lignes ordonnées, recherche par titre ou id via datalist, poids, active,
  retirer, 3 lignes vides), duplication, suppression confirmée avec dissociation explicite.
- Champ « Catalogues de quêtes » dans le formulaire des modèles ; descripteur « Proposer une quête
  du catalogue » (schéma content pack et aide IA dérivés) ; libellé lisible dans la vue dialogue.
- CSS `.qc-*` : grille par ligne, carte sous 620 px.
- Vérification visuelle locale (instance de test éphémère + Chromium headless) : 1280 px et 390 px,
  `scrollWidth` = largeur de la fenêtre (aucun défilement horizontal) ; un défaut d'alignement des
  lignes vides corrigé (commit 72654ed).

## Fichiers créés

- `src/main/java/com/lodygames/rpgquest/encounter/catalog/` (9 classes listées ci-dessus)
- `src/main/java/com/lodygames/rpgquest/dialogue/model/OfferCatalogQuestAction.java`
- `src/main/java/com/lodygames/rpgquest/dialogue/session/CatalogOfferNodes.java`
- `src/main/java/com/lodygames/rpgquest/web/agent/QuestCatalogViews.java`
- `src/main/resources/quest-catalogs/encounter_side_quests.yml`
- Tests : `encounter/catalog/CatalogOfferSelectorTest`, `encounter/catalog/QuestCatalogStoreTest`,
  `web/agent/QuestCatalogAgentActionsTest`
- Ce rapport

## Fichiers modifiés

Plugin : `EncounterTemplate`, `EncounterTemplateParser`, `EncounterTemplateYaml`, `ActionType`,
`DialogueAction`, `DialogueDefinitionParser`, `DialogueDefinitionWriter`, `DialogueSessionEngine`,
`QuestProgressEngine`, `ContentPackMapper`, `ContentPackSerializer`, `DialoguePackEntry`,
`AgentActionType`, `AgentActionExecutor`, `AgentActions`, `BukkitAgentActions`,
`EncounterCatalogViews`, `RPGQuestBootstrap` ; tests `DialogueSessionEngineTest` (+5),
`DialogueDefinitionParserTest` (+1), `EncounterTemplateParserTest` (+1).
Panel : `AgentActionCatalog`, `Descriptors`, `AgentPages`, `PanelApp`, `plugadmin.css`,
`docs/quetes.md` ; tests `NpcsCatalogTest` (+1, +1 assertion), `DialogueDescriptorsTest`.
Docs : `docs/ENCOUNTERS.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-314), `docs/deployment/SERVER_CHANGELOG.md`,
`docs-site/dialogues.html`, `docs-site/npc.html`.

## Base de données / migrations

Aucune. Schéma inchangé (toujours V37).

## Configuration / données

Nouveau dossier serveur `plugins/RPGQuest/quest-catalogs/` (créé au démarrage, exemple seedé une
fois, jamais réécrit) et `quest-catalog-backups/` (créé à la première suppression). Aucun modèle
ni dialogue existant réécrit ; aucun changement de `config.yml`.

## Tests automatiques

- Local, ciblés : `encounter.*`, `dialogue.*`, `QuestCatalogAgentActionsTest`,
  `EncounterAgentActionsTest` (183 tests, dont un premier échec réel corrigé dans le test : la
  complétion utilisée n'appartenait pas au catalogue) ; panel `NpcsCatalogTest` (53),
  `content.*`, `agent.*`, `ActionReturnPathTest`, `DocsPagesTest`, `DiagnosticHelpTest` — verts.
- CI GitHub PR #395 : Tests plugin, control-panel, web-api, Scripts, Artefacts, CI OK — **pass**.
- `deploy-verygames.sh` a relancé la suite plugin complète : verte.

## Tests manuels à effectuer

`PENDING MANUAL VALIDATION` — TC-314 de `docs/MANUAL_TEST_PLAN.md` (catalogue de 5 quêtes depuis
PlugAdmin, attribution à `forest_bandit`, ajout de l'action au dialogue `encounter_bandit`, offre
stable, deux joueurs, acceptation unique, recharge, « Rien pour le moment », quête absente,
suppression avec dissociation, mobile). Aucun test en jeu n'a été effectué.

## Résultat attendu

Tant qu'aucun catalogue n'est attribué, rien ne change en jeu. Après attribution + action de
dialogue, chaque joueur reçoit sa propre proposition, stable, et seule « Commencer (j'accepte) »
démarre une quête.

## Reset / retour à l'état initial

Retirer `quest_catalogs` du modèle (formulaire) et l'action du dialogue ; supprimer les
catalogues depuis la page (sauvegardés). Les progressions de quêtes ne dépendent pas du catalogue.

## Déploiement VeryGames

Effectué le 2026-10-15 vers 00:04 : `deploy-verygames.sh -y` depuis le worktree propre détaché sur
4522183 — « JAR en ligne : 2936640 octets (== local) », « Déploiement terminé » ; sauvegarde
`verygames-backups/rpgquest-20261014T220452Z-predeploy.jar` (2870704 octets, f7a951e). Redémarrage
`verygames-restart.sh` (0 joueur) → ONLINE, RPGQuest vert dans `/plugins`. Panel :
`scripts/plugadmin/deploy.sh` — sa sonde /health a échoué par course au démarrage (connu) ;
vérifié ensuite : service actif, /health 200, `/quests/catalogs` → 303 /login (route servie), CSS
`.qc-row` dans le JAR installé. Lecture réelle `quest.catalog.list` via l'agent DEV : SUCCESS,
`encounter_side_quests` chargé, 3 quêtes existantes (recharge 20 h), 3 modèles sans catalogue.

### À transférer

Le JAR (fait). Aucun fichier de contenu.

### Ne PAS transférer/altérer

`encounters/*.yml`, `dialogues/encounter_*.yml`, `config.yml`, `data.db`.

### Redémarrage requis

Oui (fait).

### Migration automatique

Aucune.

## Rollback

`scripts/rollback-verygames.sh --latest` + `scripts/plugadmin/rollback.sh app`. Avant un rollback,
retirer toute action `OFFER_CATALOG_QUEST` ajoutée à un dialogue : l'ancien JAR refuserait ce
dialogue (type d'action inconnu). Le champ `quest_catalogs` et le dossier `quest-catalogs/` sont
ignorés par l'ancien JAR.

## Logs / diagnostic

Démarrage : « Chargement des catalogues de quêtes : N catalogue(s), M erreur(s) » ; fichier refusé
journalisé `[quest-catalogs/<fichier>] motif` et affiché sur la page. Échec de calcul d'offre :
« Échec du calcul de l'offre de catalogue pour … » (le joueur voit « Rien pour le moment »).
Logs de déploiement : `~/deploy-logs/369-jar.log`, `~/deploy-logs/369-panel.log`.

## Documentation mise à jour

ENCOUNTERS.md (section « Catalogues de quêtes secondaires »), Bible (actions de dialogue + section
PNJ récurrents), current_state.md, MANUAL_TEST_PLAN (TC-314), SERVER_CHANGELOG, docs-site
(dialogues, npc), aide du panel `quetes.md`.

## Limitations / travail restant

- Mode « choix parmi N », conditions monde/biome/niveau par entrée : non faits (préparés par la
  structure, non simulés).
- La suppression d'une **quête** référencée par un catalogue n'est pas encore protégée dans
  l'analyse de suppression du panel (elle devient « quête absente », signalée, sans erreur en jeu).
- Abandonner une quête renouvelable déjà terminée puis reprise efface sa date de complétion :
  l'offre peut alors changer (aucun gain, documenté).
- Playwright du panel sur DEV avec un vrai compte non fait (rendu vérifié sur instance locale).

## Prochaine étape suggérée

Le propriétaire déroule TC-314 sur DEV. Ensuite, lot 2 éventuel : avertissement de suppression
d'une quête utilisée par un catalogue (ContentDeletionAnalyzer), bascule optionnelle des dialogues
livrés `encounter_*` sur `OFFER_CATALOG_QUEST`, mode « choix parmi N ».
