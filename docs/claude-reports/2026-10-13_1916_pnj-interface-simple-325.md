# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-13
* Heure : 19:16
* Sujet : #325 — refonte simple de l'interface PNJ de PlugAdmin
* Statut : DONE (développé, déployé DEV, vérifié automatiquement et visuellement) — validation en jeu par le propriétaire en attente
* Branche Git : `feature/325-pnj-interface-simple` (PR #326) + `fix/325-refresh-dialogues-after-npc-delete` (PR #327), fusionnées dans `chore/257-ci-github-actions`
* Commit actuel si disponible : `17e13db` (merge #327) ; code plugin déployé = `515aa9d`
* Début de la tâche : 2026-10-13 17:45:55
* Fin de la tâche : 2026-10-13 19:16:19
* Durée totale : 01:30:24

## Demande
Rendre la gestion des PNJ utilisable par un enfant de 13 ans : un seul parcours de création (personnage + fiche + apparence par défaut + dialogue « Salut ! » propre au PNJ + liaison), catalogue lisible, fiche allégée, formulaires simples (nom 0/48, apparence MineSkin conditionnelle, déplacement en mots, promenade, regard ≤ 64), suppression courte, diagnostic à part, apparence par défaut `https://minesk.in/f534ead9d5a3413e9bb51bce9b211995`. Tickets, documentation, vérification réelle ordinateur + téléphone, fusion et déploiement DEV autorisés.

## Analyse
- `/npcs` (≈ 2 000 lignes dans `AgentPages`) proposait deux créations concurrentes, des statistiques compactées, le registre canonique, et des paragraphes techniques sous chaque formulaire.
- Les protections moteur existaient déjà (`npc.citizens.provision` avec nettoyage, destination sûre, états explicites, double clé Citizens) : elles sont conservées, seule leur explication disparaît de l'écran.
- Le dialogue initial doit être **modifiable dans l'éditeur du panel** (qui travaille sur la source puis publie) : il est donc écrit dans la source, publié par le moteur #47 (`content.publish`, mêmes octets → état « Synchronisé »), puis le PNJ est créé ; l'agent exécute les actions dans l'ordre.
- Le refus de renommage `SKIN_SOURCE_UNKNOWN` bloquait les anciens PNJ joueurs : remplacé par l'apparence par défaut, enregistrée.
- Bootstrap ne sait pas grouper des volets imbriqués dans un accordéon : « un seul formulaire ouvert » est fait dans `panel.js`.
- Le `.btn` du panel écrase les variantes Bootstrap : les boutons utilisent les classes du panel (`btn secondary sm`).

## Travail effectué
**Plugin**
- `npc.citizens.provision` : `npc_id` et `dialogue_id` optionnels ; skin vide = apparence par défaut (`NpcIdentityService.DEFAULT_SKIN_URL`).
- `SkinPreservationPlanner` : `APPLY_DEFAULT_THEN_RENAME` remplace le refus ; la source par défaut est enregistrée après renommage réussi.
- `citizensRelocate` : orientation `null` = gardée ; une promenade active est recentrée sur la destination (`reanchorWander`, trajet en cours annulé) ; messages d'erreur concrets (« Cet endroit ne laisse pas assez de place au PNJ… »).
- `citizensWanderStop(returnHome)` : centre lu avant l'arrêt, destination contrôlée (sol + 2 blocs), sinon promenade arrêtée et message clair.
- Une promenade active garde son centre (jamais la position temporaire).

**Panel**
- `/npcs` réécrit : « Actualiser les PNJ » (`POST /npcs/refresh`, deux relevés, jamais en double), un seul « Créer un PNJ » (`POST /npcs/create` : nom, emplacement, apparence ; `NpcCreationPlanner` pour l'id libre `lucien_2` et le YAML initial ; jeton `request_token` anti-doublon ; bloc « création pas terminée » avec étapes faites / à faire et « Reprendre »), total simple + filtre « À vérifier » seulement si utile.
- « Rattacher à RPGQuest » (`POST /npcs/attach`) : définition + liaison (+ dialogue initial seulement si aucun dialogue de ce nom), jamais de recréation, apparence intacte.
- Fiche : en-tête nom / apparence / emplacement / activité ; problèmes en phrases simples avec bouton qui ouvre le bon formulaire ; boutons Modifier le nom, Apparence, Déplacer, Promenade, Regarder les joueurs, Dialogue et quêtes, Supprimer ; un seul formulaire ouvert ; identifiant, type, source d'apparence, départ de promenade, réglages avancés et lien FAQ dans « Informations avancées ».
- Formulaires : nom avec compteur 0/48 ; apparence « Lien MineSkin » par défaut, un seul champ visible, lien « Catalogue MineSkin » (nouvel onglet), refus « Le lien doit commencer par https:// » puis format MineSkin exigé ; déplacement monde lisible + X/Y/Z préremplis + « Regarde vers » (nord/sud/est/ouest) + « Tête » (horizontale/haut/bas) + angles précis repliés, « Sans changement » par défaut, case « Confirmer le déplacement » ; promenade inactive : case « Activer la promenade », rayon, amplitude, Confirmer (aucun champ d'ancre) ; active : « Arrêter la promenade et revenir au départ » ; regard : case reflétant l'état, portée ≤ 64, Confirmer (état envoyé, jamais une bascule).
- Dialogue et quêtes : « Modifier le dialogue » (éditeur), « Créer une quête qu'il propose » (`/quests/new?giver=<id>`), proposer une quête existante replié ; quêtes en liens titrés.
- Suppression : page courte (ce qui part, ce qui reste, quêtes à corriger avec liens, confirmation par l'identifiant, « Supprimer le PNJ » / « Annuler »), autres choix et détails repliés ; après suppression, lien « le supprimer aussi » pour un dialogue qui n'est à personne d'autre. PR #327 : la suppression rafraîchit aussi `dialogue.list` (défaut trouvé sur DEV).
- `/npcs/diagnostic` : explication, compteurs, messages bruts du serveur, rapprochement avec le jeu, registre des identifiants.
- Tickets : #325 créé ; commentaires de rattachement sur #197, #111, #112, #89.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/npc/NpcCreationPlanner.java`
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/npc/NpcCreationPlannerTest.java`
- ce rapport

## Fichiers modifiés
Plugin : `npc/CitizensBehaviourBridge.java`, `npc/NpcIdentityService.java`, `npc/SkinPreservationPlanner.java`, `web/agent/AgentActionExecutor.java`, `web/agent/AgentActions.java`, `web/agent/BukkitAgentActions.java` ; tests `SkinPreservationPlannerTest`, `NpcBehaviourActionTest`.
Panel : `agent/AgentActionCatalog.java`, `npc/NpcAppearance.java`, `web/AgentPages.java`, `web/ContentEditorPages.java`, `web/NpcDeletionPages.java`, `web/PanelApp.java`, `assets/panel.js`, `assets/plugadmin.css`, `docs/pnj-citizens.md` ; tests `NpcsCatalogTest` (36 tests de l'ancienne interface retirés, 15 ajoutés), `NpcAppearanceTest`, `NpcDeletionPageTest`, `BusinessPagesTest`, `DialogueSourceMergeTest`, `PanelHardeningMalformedAgentDataTest`.
Docs : `docs/RPGQUEST_BIBLE.md`, `docs-site/npc.html`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`.

## Base de données / migrations
Aucune migration. L'apparence par défaut passe par la table existante `npc_citizens_skins`.

## Configuration / données
Aucun fichier de configuration. Le dialogue initial d'un PNJ créé est `dialogues/<id>.yml` (source + DEV).

## Tests automatiques
- Ciblés locaux : plugin `SkinPreservationPlannerTest` 7/0, `AgentActionExecutorTest` 118/0, `NpcBehaviourActionTest` 21/0 ; panel `NpcsCatalogTest` 42/0, `NpcCreationPlannerTest` 4/0, groupe PNJ/catalogue/éditeur 229 (1 ignoré, 0 échec après correction), `AgentActionCatalogTest` 46/0, `NpcDeletionPageTest` 16/0, `CatalogResyncTest` 5/0.
- CI GitHub complète (plugin, panel, web-api, scripts) verte sur `971cfd4`, `515aa9d`, la branche du correctif et `17e13db`.

## Vérification visuelle (réellement effectuée)
- Instance locale du panel (port 8097, base et contenu copiés dans le scratchpad, relevés DEV réels), Chromium headless, 1280 px et 390 px : liste, création (compteur, champs conditionnels), fiche, Déplacer, Promenade inactive/active, Regarder, Apparence, Dialogue et quêtes, suppression bloquée et possible, diagnostic. Un seul formulaire ouvert vérifié par script (ouvrir Promenade ferme Déplacer). Création → 2 actions, renvoi du même jeton → rien de plus ; échec simulé → bloc « pas terminée » → « Reprendre » renvoie seulement la création ; rattachement de Stan → définition + liaison + dialogue initial.
- DEV réel (`https://plugadmin.lodylands.com`, compte temporaire supprimé ensuite), sur un PNJ jetable **« Test Claude »** : création (dialogue publié et confirmé par le moteur, Citizens #13 près du Guide, apparence par défaut), promenade ±3/±1, déplacement (« Le centre de sa promenade est maintenant sa nouvelle position »), arrêt + retour au départ, regard 64 activé puis désactivé, renommage (« relus identiques »), éditeur du dialogue ouvert, suppression (entité + définition, sauvegarde `npc-backups/20261013-170142-test_claude.yml`), puis suppression de son dialogue (source sauvegardée dans `/var/lib/plugadmin/content-backups/20261013-170238-dialogues-test_claude`, copie serveur supprimée). Aucun autre PNJ, monde ou contenu touché ; l'arbre de travail du propriétaire est identique à l'état initial.
- **Aucun test en jeu fait par Claude** (rendu du skin, déplacement visuel, regard).

## Tests manuels à effectuer
PENDING MANUAL VALIDATION (propriétaire, en jeu) :
1. Créer un PNJ : il apparaît près du Guide avec l'apparence par défaut, dit « Salut ! ».
2. Renommer un PNJ à skin personnalisé (apparence gardée) et un ancien PNJ joueur sans skin enregistré (prend l'apparence par défaut).
3. Promenade, déplacement pendant la promenade, « Arrêter la promenade et revenir au départ » : vérifier visuellement.
4. Regarder les joueurs on/off, portée.
5. Rattacher un personnage Citizens sans fiche (ex. Tania, Jo, Libraire en « Terminer le rattachement »).
6. Suppression avec quêtes liées (refus + liens) et dialogue partagé.
7. Affichage sur votre téléphone.

## Résultat attendu
Un parcours sans jargon ; les mêmes protections côté moteur.

## Reset / retour à l'état initial
Rien à réinitialiser : le PNJ et le dialogue de test ont été supprimés.

## Déploiement VeryGames
- Sauvegardes : `/srv/rpgquest/backups/20261013-predeploy-325/` (JAR en ligne `a15330ae…` 2 592 095 o, `data.db` intègre, `config.yml`, `messages.yml`, base du panel).
- Plugin : `deploy-verygames.sh -y --wait-ci` sur `515aa9d` (CI verte), JAR `93ef517e…` 2 594 884 o (plus gros que le précédent), retéléchargé et SHA identique ; redémarrage RCON à 0 joueur, ONLINE, agent reconnecté.
- Panel : `scripts/plugadmin/deploy.sh --wait-ci` sur `515aa9d` puis `17e13db` ; JAR servi `6e7c6c18…`, classe du correctif identique au build ; `/health` ONLINE (le KO du script est la course connue au démarrage).
- Aucune migration, aucun fichier de données transféré.

## Rollback
Plugin : remettre `backups/20261013-predeploy-325/rpgquest-0.1.0-SNAPSHOT.jar` puis redémarrer. Panel : `scripts/plugadmin/rollback.sh app`. Les dialogues `dialogues/<id>.yml` créés entre-temps restent compatibles.

## Logs / diagnostic
Résultats lisibles dans le journal d'actions du panel et sur `/npcs/diagnostic`.

## Documentation mise à jour
Page d'aide `pnj-citizens` (parcours simple, apparence par défaut, FAQ « Peut-on changer l'identifiant d'un PNJ ? », suppression), bible §5, `docs-site/npc.html`, `current_state.md`, `SERVER_CHANGELOG.md`.

## Limitations / travail restant
- Les noms de quêtes sur la page de suppression sont dérivés de l'identifiant (pas du titre).
- « Reprendre » rejoue les paramètres d'origine : si l'échec venait de l'absence de place près du Guide, il faut une nouvelle création avec une position précise.
- Une création abandonnée laisse son dialogue `<id>` dans la source : un nouveau PNJ du même nom reçoit alors `<id>_2`.
- #197 (aperçu image du skin), #198 (renommer l'identifiant) restent hors périmètre.

## Prochaine étape suggérée
Créer un PNJ → modifier son dialogue → créer et proposer une quête → publier → tester en jeu.
