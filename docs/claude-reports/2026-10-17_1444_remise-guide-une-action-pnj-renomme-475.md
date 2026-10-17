# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 14:44
* Sujet : #475 (retour propriétaire du 17/10) — remise au Guide en une action, bilan dans le dialogue, renommage de PNJ propagé, infobulle du journal repliée
* Statut : DONE (livré et déployé sur DEV ; validation en jeu TC-348 en attente, ticket laissé ouvert)
* Branche Git : `fix/475-remise-dialogue-pnj-renomme` (PR #509, fusionnée dans `chore/257-ci-github-actions`), rapport : `docs/475-rapport-remise-renommage`
* Commit actuel si disponible : merge `d0c8bdd` (commits `80d9f7a`, `4ae99ba`, `4806cd2`)
* Début de la tâche : 2026-10-17 13:44:46
* Fin de la tâche : 2026-10-17 14:45:29
* Durée totale : 01:00:43

## Demande

Retour de validation du propriétaire du 17/10 sur #475 (tests suspendus). En jeu, sur DEV, avec LoDyMcFly :
« Explorateur » visible avec son badge, mais :
1. parcours de remise en trois écrans (« J'ai des matériaux » → « Donner mes matériaux » → « Donner encore »)
   à remplacer par **une** action « Donner les matériaux » ;
2. sans matériau utile, le refus n'apparaissait que dans le chat, derrière la fenêtre : le dire **dans le
   dialogue** et ne proposer aucun bouton de remise ;
3. après une remise, bilan clair dans le dialogue (remis, restant, fin / amélioration), bouton recalculé ;
4. le Guide renommé **Elias** restait « Guide » (titre du dialogue, « Je suis le Guide », description de
   quête) : corriger le **mécanisme partagé** (nom résolu depuis l'id du PNJ au rendu), sans remplacement
   aveugle du mot « guide », et republier les textes en dur par le circuit officiel ;
5. infobulle du journal qui débordait horizontalement.
Préserver IDs, inventaires, dépôts, paliers ; aucun reset de LoDyMcFly ; ne pas redemander le dépôt
partiel (déjà validé).

## Analyse

* **DEV, lecture seule (FTP)** : `dialogues/guide.yml`, `dialogues/libraire.yml`, `quests/kit_tier2..5.yml`
  identiques octet pour octet au dépôt (`91511e8`) ; `npcs/guide.yml` → `display_name:
  "<dark_green><bold>Elias</bold></dark_green>"`. Le renommage est donc bien fait côté PNJ ; seuls les
  textes écrivant « Guide » en dur restaient faux. Les objectifs disaient déjà « Elias » parce qu'ils
  passent par `QuestProgressEngine#npcNameResolver` (#70/#475).
* **Remise** : `HAS_PENDING_DELIVERY` est vraie dès qu'une quête attend quelque chose de ce PNJ, quel que
  soit l'inventaire ; le bouton de remise était donc toujours cliquable, et `deliverTo` ne répondait
  « rien d'utile » que dans le chat. Le dialogue n'avait aucune notion de « le joueur porte-t-il un
  matériau encore utile ? ».
* **Renommage** : aucun mécanisme pour citer un PNJ par son id dans un texte de dialogue ou de quête
  (`%city_name:%` existait pour les villes, #180). Le `speaker` n'était pas substitué. Deux messages Java
  disaient aussi « Guide » en dur : amélioration du kit (`RpgAdminCommand`, reçu à chaque palier) et refus
  d'accès aux claims (`ClaimWorldAccessGuard`).
* **Journal** : la description de détail était **une seule** ligne de lore ; le client ne replie jamais
  une ligne de lore.
* Mêmes motifs « J'ai des matériaux / Donner encore » dans `guard.yml` (modifié par le propriétaire sur
  DEV), `lily.yml` (contenu du propriétaire) et les deux rencontres : **non republiés** (contenus du
  propriétaire), mais la règle moteur ci-dessous s'y applique déjà.

## Travail effectué

**Moteur (partagé, vaut pour tous les dialogues publiés sans toucher à leur YAML)**
* `QuestProgressEngine#hasDeliverableItems(Player, npcId)` : vrai seulement si un objectif de remise de ce
  PNJ est **encore incomplet** et que le joueur en porte le matériau (même règle d'éligibilité que le
  retrait `QuestItemWithdrawal`). Un objectif déjà rempli ne compte plus.
* `DialogueSessionEngine` : un choix portant `DELIVER_QUEST_ITEMS` n'est visible/cliquable que si
  `hasDeliverableItems` est vrai. Au clic, si le choix n'a plus cours (inventaire vidé entre l'affichage
  et le clic…), rien n'est exécuté et **le même nœud est réaffiché, recalculé** (avant : fenêtre fermée
  sans réponse). Double-clic : gardes existantes (#72, #123) inchangées.
* `DeliveryOutcome.completedQuests` : quêtes que la remise vient de terminer (récompenses remises ;
  `READY_TO_TURN_IN` faute de place n'est pas annoncée finie).
* `DeliveryStatusText` : `%delivery_status%` rend désormais « Tu viens de me remettre : 2 × Bâton… » (ou
  « Tu n'as rien d'utile à me remettre pour l'instant. »), l'état réel (remis/demandé), « Tu as sur toi des
  matériaux que j'attends. » / « Tu n'as sur toi aucun des matériaux qui manquent. », puis la fin :
  « Amélioration du kit obtenue : palier N. Ton équipement de secours s'améliorera dès ta prochaine
  demande. » (quête de palier, d'après `starter-tool-kit.tiers[].unlock-quest`) ou « Quête terminée :
  <titre> ». Nouveau `%delivery_hint%` (vide si le PNJ n'attend rien) pour un texte d'accueil.
* `npc.NpcNameTokens` : `%npc_name:<id>%` → nom affiché **actuel** (balises retirées, inséré échappé),
  jeton laissé visible si PNJ inconnu. Valeurs de dialogue `%npc_name%` (PNJ du dialogue) et
  `%npc_name:<id>%`. `DialogueTextPlaceholders` substitue aussi le **locuteur** ; les nœuds d'offre de
  catalogue (#369) passent par les mêmes valeurs. Journal : titre et description résolus.
* `RpgAdminCommand` (kit grant-tier) et `ClaimWorldAccessGuard` : nom affiché du PNJ `hub.guide-npc-id`
  au lieu de « Guide ».
* `ui.LoreWrap` : description du journal repliée à 40 caractères visibles, balises rouvertes à chaque
  ligne, `<newline>` respecté, mot trop long conservé entier.

**Contenus (republiés sur DEV, diff = celui du dépôt, sauvegarde datée)**
* `dialogues/guide.yml` : accueil « Je suis %npc_name%, le guide du village : … » + `%delivery_hint%` ;
  choix unique **« Donner les matériaux »** (`HAS_PENDING_DELIVERY` + `DELIVER_QUEST_ITEMS` →
  `delivery_result`) à l'accueil et dans `kit_progress` ; nœuds `delivery`, `delivery_after`,
  `delivery_done` remplacés par `delivery_result` (`%delivery_status%`, « Donner les matériaux » seulement
  s'il reste un matériau utile, « Comment améliorer mon kit ? », « ← Autres questions », « Fermer ») ; tous
  les locuteurs `"%npc_name%"` ; « reviens me voir, moi, %npc_name% ». Le mot générique « guide » reste.
* `dialogues/libraire.yml` : « Ah, c'est %npc_name:guide% qui t'envoie ? ».
* `quests/kit_tier2..5.yml` : « Rapporte à %npc_name:guide% de quoi… ». IDs, objectifs, récompenses,
  prérequis et `giver` inchangés.

## Fichiers créés
* `src/main/java/com/lodygames/rpgquest/npc/NpcNameTokens.java`
* `src/main/java/com/lodygames/rpgquest/ui/LoreWrap.java`
* `src/test/java/com/lodygames/rpgquest/npc/NpcNameTokensTest.java`
* `src/test/java/com/lodygames/rpgquest/ui/LoreWrapTest.java`
* ce rapport

## Fichiers modifiés
* Code : `QuestProgressEngine`, `DeliveryOutcome`, `DeliveryStatusText`, `DialogueSessionEngine`,
  `DialogueTextPlaceholders`, `QuestJournalService`, `RPGQuestBootstrap`, `RpgAdminCommand`,
  `ClaimWorldAccessGuard`.
* Contenus : `dialogues/guide.yml`, `dialogues/libraire.yml`, `quests/kit_tier2..5.yml`.
* Tests : `DialogueSessionEngineTest`, `DialogueTextPlaceholdersTest`, `BundledDialoguesValidityTest`,
  `DeliveryStatusTextTest`, `QuestItemDeliveryTest`, `QuestJournalKitQuestTest`.
* Docs : `docs/RPGQUEST_BIBLE.md`, `docs-site/dialogues.html`, `QUEST_FORMAT.md`, `docs/current_state.md`,
  `docs/MANUAL_TEST_PLAN.md` (TC-348, note sur TC-335), `docs/deployment/SERVER_CHANGELOG.md`,
  `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`.

## Base de données / migrations
Aucune. Aucune donnée joueur lue en écriture ni modifiée ; dépôts, paliers et inventaires de LoDyMcFly
intacts (aucun reset, aucune quête de substitution).

## Configuration / données
Aucune clé de configuration. Seuls les 6 contenus ci-dessus ont été remplacés sur DEV.

## Tests automatiques
* Nouveaux : zéro matériau (texte + aucun bouton), matériau inutile, objectif déjà rempli alors que le
  joueur en porte encore, remise partielle (bilan, restant, bouton masqué ensuite), remise finale (fin
  annoncée, `completedQuests`), inventaire vidé entre affichage et clic (rien retiré, nœud réaffiché),
  renommage + « rechargement » (locuteur, texte, jeton d'un autre PNJ, PNJ inconnu, échappement
  MiniMessage), journal sur la vraie `kit_tier2` avec le Guide renommé Elias (description « Rapporte à
  Elias… » repliée, lignes ≤ 48 caractères, « à remettre à Elias »), repli `LoreWrap`, contenu livré du
  Guide (action unique, plus d'écrans redondants, aucun nom en dur). Le double-clic reste couvert par
  `aDoubleClickOnTheDeliveryChoiceNeverWithdrawsTwice`.
* Un test existant adapté au nouveau comportement voulu
  (`theDeliveryChoiceIsOnlyVisibleWhileSomethingIsStillExpected` : bouton absent sans cuir, présent avec).
* Local (`RPGQUEST_TEST_MAX_HEAP=768m`) : lots `dialogue.*`, `quest.progress.*`, `ui.*`, `npc.*`,
  `content.*`, `player.Starter*`/`Kit*`, `claim.ClaimWorldAccess*`, `YamlQuestEngineTest` — tous verts.
* CI GitHub PR #509 : deux exécutions vertes (plugin, control-panel, web-api, scripts, artefacts) ; CI
  verte sur le merge `d0c8bdd` (garde du script de déploiement).

## Tests manuels à effectuer
**TC-348 — PENDING MANUAL VALIDATION** (`docs/MANUAL_TEST_PLAN.md`). Point de reprise exact pour le
propriétaire, compte LoDyMcFly tel quel (« Explorateur » palier 3 active, dépôts conservés) :
1. parler au Guide **sans** bâton/pierre/cuir/blé → fenêtre « Elias », « Je suis Elias, le guide du
   village », compteurs, « Tu n'as sur toi aucun des matériaux qui manquent. », **aucun** bouton de remise ;
2. avec un matériau inutile ou déjà complet → pareil ;
3. avec une partie d'un matériau attendu → un seul bouton « Donner les matériaux » → **une** fenêtre de
   bilan ;
4. remise finale → « Amélioration du kit obtenue : palier 3… » dans la fenêtre, quête dans « Terminées » ;
5. journal → détails : description « Rapporte à Elias… » repliée, lisible en entier ;
6. Libraire : « Ah, c'est Elias qui t'envoie ? ».
Le dépôt partiel (déjà validé) n'est pas à refaire pour lui-même.

## Résultat attendu
Voir TC-348 ci-dessus. Un futur renommage du Guide dans PlugAdmin se propage sans republier aucun contenu.

## Reset / retour à l'état initial
Aucun reset nécessaire ni effectué.

## Déploiement VeryGames
Effectué le 2026-10-17 vers 14:39 (heure locale), `scripts/deploy-verygames.sh -y --wait-ci` + 6 `--also`,
commit `d0c8bdd` (CI verte). JAR 3 534 224 o, SHA-256 `e2b9348fc948555be2b700946a2527877bb7f5a90aea7f343dc2c7dbc57aeef0`,
JAR distant retéléchargé : SHA identique. Sauvegarde du JAR précédent :
`rpgquest-20261017T123927Z-predeploy.jar` (3 524 197 o, `43f4596`, #183). Sauvegardes des contenus :
`extra-20261017T123927Z/` (+ `MANIFEST.txt`). Redémarrage : `scripts/verygames-restart.sh` (0 joueur avant,
retour ONLINE avec LoDyMcFly connecté).

Vérification en lecture seule après redémarrage : les 6 fichiers distants sont identiques au dépôt ; relevé
agent `dialogue.list` (13 dialogues, 0 rejeté) : `guide` charge `greeting` + `delivery_result`, « Donner les
matériaux », `%npc_name%`, `%delivery_hint%`, plus de « J'ai des matériaux » ni « Donner encore » ;
`libraire` porte `%npc_name:guide%` ; `quest.list` : 29 quêtes, `kit_tier2..5` chargées avec leurs
objectifs inchangés. `npcs/guide.yml` (Elias) non touché.

### À transférer
Fait : JAR + `RPGQuest/dialogues/guide.yml`, `RPGQuest/dialogues/libraire.yml`,
`RPGQuest/quests/kit_tier2.yml` … `kit_tier5.yml`.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `npcs/*.yml` (dont `guide.yml` = Elias), `dialogues/guard.yml` et `lily.yml`
(contenus du propriétaire), tout autre contenu. PlugAdmin inchangé (aucun redéploiement).
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune.

## Rollback
`scripts/rollback-verygames.sh --latest` (JAR `43f4596`) et, pour chaque contenu,
`scripts/rollback-verygames.sh --also ~/.local/share/rpgquest/verygames-backups/extra-20261017T123927Z/RPGQuest/<chemin>:RPGQuest/<chemin>`,
puis redémarrage. L'ancien JAR afficherait les jetons `%npc_name…%` tels quels : restaurer les contenus avec
le JAR. Les dépôts restent acquis dans `data.db`.

## Logs / diagnostic
Journal de déploiement : `/srv/rpgquest/build-logs/deploy-475-20261017T143205.log`. Relevés agent
`claude-475` (`dialogue.list`, `quest.list`) dans la base PlugAdmin.

## Documentation mise à jour
Bible (règle « remise jamais proposée à vide », `%delivery_hint%`, `%npc_name%`, `%npc_name:<id>%`, bilan
`%delivery_status%`), docs-site `dialogues.html`, `QUEST_FORMAT.md`, `current_state.md`, plan de test
(TC-348 + note TC-335), `SERVER_CHANGELOG.md`, registre `docs/SUIVI_VALIDATIONS_DEV.md` (#475 « Partiel »).

## Limitations / travail restant
* `guard.yml`, `lily.yml`, `encounter_smith.yml`, `encounter_merchant.yml` gardent leur parcours « … →
  Donner encore » (contenus du propriétaire / non demandés) ; grâce à la règle moteur, leurs boutons de
  remise sont déjà masqués quand rien d'utile n'est porté et le bilan s'affiche. Les simplifier comme le
  Guide = décision du propriétaire.
* Les titres de quête dans les messages du chat (démarrage/fin) ne résolvent pas `%npc_name:%` (aucun titre
  n'en contient aujourd'hui) ; le journal et les offres le font.
* Validation en jeu TC-348 non faite (PENDING MANUAL VALIDATION). Ticket #475 laissé ouvert.

## Prochaine étape suggérée
Le propriétaire joue TC-348 avec LoDyMcFly. Si un écran diffère, joindre l'heure et une capture.
