# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-17
* Heure : 13:42
* Sujet : Issue #183 — XP RPG persistante et suppression de la progression de niveaux Minecraft (lot 1)
* Statut : DONE (livré et déployé sur DEV ; validation en jeu et deux décisions propriétaire restantes, ticket laissé ouvert)
* Branche Git : `feature/183-xp-rpg-sans-vanilla` (PR #507, fusionnée dans `chore/257-ci-github-actions`, merge `43f4596`) ; rapport sur `docs/183-rapport`
* Commit actuel si disponible : `43f4596`
* Début de la tâche : 2026-10-17 12:27:29
* Fin de la tâche : 2026-10-17 13:42:52
* Durée totale : 01:15:23

## Demande
Ticket #183 (session autonome lqorch) : progression XP/niveaux RPG propre au serveur, conservée après
mort/reconnexion/redémarrage ; supprimer la progression de niveaux Minecraft ; auditer orbes, morts,
récompenses, commandes ; ne pas convertir silencieusement les anciens niveaux ; inventorier
enchantement/enclume sans implémenter de remplacement ni de gratuité ; distinguer XP RPG et XP vanilla
dans les messages et le panel.

## Analyse
Audit de l'existant (aucun commentaire ni session précédente sur le ticket) :
- **Déjà livré** : `progression.ProgressionService` — XP RPG par UUID et par piste (`player_skills`,
  `xp_grants`, migration V8), octroi dédupliqué par id d'événement, courbe configurable
  (`base-xp`, `growth-factor`, `max-level`), cache chargé à la connexion. La mort ne touche jamais cette
  XP (aucun écouteur de mort côté progression). Le HUD `/hud` (#184) affiche déjà le niveau RPG.
- **Manquant** : la suppression de l'XP vanilla. `keep-vanilla-xp` (défaut `true`) ne coupait que les
  orbes de 4 écouteurs, et seulement si leur XP RPG était > 0 (combat : seulement avec un tueur joueur).
  Non couverts : mort des joueurs, fours, reproduction, fioles, commerce villageois, meule, profils de
  mob `xp-reward`. La récompense de quête `EXPERIENCE` donnait de l'XP vanilla (`giveExp`), alors que
  PlugAdmin la décrit déjà comme « Points d'XP RPGQuest ».

## Travail effectué
- `VanillaXpBlockListener` (priorité `HIGHEST`, aucune tâche répétitive ni scan) : orbes à 0 pour
  `EntityDeathEvent` (mobs et joueurs), `BlockBreakEvent`, `FurnaceExtractEvent`, `PlayerFishEvent`,
  `EntityBreedEvent`, `ExpBottleEvent` ; `PlayerExpChangeEvent` ramené à 0 (toute orbe restante).
  `/xp` admin volontairement hors périmètre. Les 4 coupures partielles des écouteurs d'XP ont été retirées
  (une seule règle).
- Nouvelle clé `progression.vanilla-xp-progression` (défaut `false`). L'ancienne `keep-vanilla-xp` est
  **retirée** du `config.yml` serveur par `ConfigFileCompleter` (`RETIRED_KEYS`). Décision technique : pas
  de `RetiredDefault true→false`, car un booléen ne distingue pas l'ancien défaut d'un choix, et un `true`
  remis à la main pour revenir en arrière aurait été rebasculé à chaque démarrage.
- Récompense de quête `EXPERIENCE` : nouveau port `quest.progress.ExperienceRewardSink` (le package quest
  ne dépend pas de la progression), implémenté par `progression.QuestExperienceRewards` → XP RPG `GLOBAL`,
  id d'événement `quest_reward:<jeton de complétion>#<rang>` (pas de double paiement). Chat `+ N XP RPG`
  (nouvelle clé `quest.reward-line-rpg-experience`). Avec `vanilla-xp-progression: true` : XP vanilla.
- Action bar : `+N XP RPG (Combat)`.
- Politique de migration : anciens niveaux vanilla **ni convertis ni effacés** ; XP RPG inchangée.
- Inventaire enchantement / enclume / meule / raccommodage documenté (PROGRESSION.md) : rien désactivé
  ni rendu gratuit ; coûts toujours en niveaux vanilla, donc accessibles seulement avec d'anciens niveaux ;
  raccommodage inerte (plus d'orbes).

## Fichiers créés
- `src/main/java/com/lodygames/rpgquest/progression/listener/VanillaXpBlockListener.java`
- `src/main/java/com/lodygames/rpgquest/progression/QuestExperienceRewards.java`
- `src/main/java/com/lodygames/rpgquest/quest/progress/ExperienceRewardSink.java`
- `src/test/java/com/lodygames/rpgquest/progression/listener/VanillaXpBlockListenerTest.java`
- `src/test/java/com/lodygames/rpgquest/progression/QuestExperienceRewardsTest.java`

## Fichiers modifiés
- `bootstrap/RPGQuestBootstrap.java` (branchement), `config/ConfigFileCompleter.java`, `config/ConfigValidator.java`,
  `progression/ProgressionService.java` (message), `progression/listener/{Combat,Mining,Farming,Fishing}XpListener.java`,
  `progression/listener/QuestCompletionXpListener.java` (javadoc), `quest/progress/QuestProgressEngine.java`
- `src/main/resources/config.yml`, `src/main/resources/messages.yml`
- `src/test/java/com/lodygames/rpgquest/config/ConfigFileCompleterTest.java`
- Docs : voir plus bas.

## Base de données / migrations
Aucune migration de schéma. Aucune donnée joueur modifiée. Les octrois RPG réutilisent `xp_grants`.

## Configuration / données
`progression.vanilla-xp-progression: false` ajoutée ; `progression.keep-vanilla-xp` retirée au démarrage
(vérifié sur le `config.yml` DEV retéléchargé après redémarrage).

## Tests automatiques
- Local ciblé (`RPGQUEST_TEST_MAX_HEAP=768m`) : `progression.*`, `config.*`, `QuestProgressEngineTest`,
  `SpecialMobServiceTest` → 177 puis 174 tests, 0 échec.
- Nouveaux : orbes coupées / laissées selon le réglage, anciens niveaux intacts ; EXPERIENCE → XP RPG une seule
  fois avec le même jeton, relue après déchargement/rechargement (reconnexion), aucun niveau vanilla ; clé
  retirée et choix `true` conservé sur deux démarrages.
- CI GitHub PR #507 : Tests plugin, control-panel, web-api, Scripts, Artefacts — tous verts.
- Le script de déploiement a relancé test + build : vert.

## Tests manuels à effectuer
TC-347 (docs/MANUAL_TEST_PLAN.md) — PENDING MANUAL VALIDATION. Aucun test en jeu n'a été fait.

## Résultat attendu
Plus aucune orbe ni gain d'XP vanilla en survie ; quêtes `EXPERIENCE` → `+ N XP RPG` ; niveau RPG conservé
après mort, reconnexion, redémarrage ; anciens niveaux vanilla toujours visibles et utilisables.

## Reset / retour à l'état initial
Écrire `progression.vanilla-xp-progression: true` dans le `config.yml` serveur puis `/rpgquest reload`.

## Déploiement VeryGames
Effectué le 2026-10-17 depuis `43f4596` avec `scripts/deploy-verygames.sh -y` : JAR 3 524 197 o,
SHA-256 `d92d6d1f…70a1`, identique après retéléchargement ; sauvegarde
`rpgquest-20261017T114059Z-predeploy.jar` (JAR #184, 3 520 199 o, `880aac0d…4eab1`). DEV redémarré
(`verygames-restart.sh`, seul LoDyMcFly en ligne), de nouveau ONLINE, commande `skills` enregistrée.
### À transférer
JAR seul.
### Ne PAS transférer/altérer
`config.yml` (complété automatiquement), `data.db`, PlugAdmin (inchangé).
### Redémarrage requis
Oui (fait).
### Migration automatique
Aucune de schéma ; complétion du `config.yml` uniquement.

## Rollback
`scripts/rollback-verygames.sh` avec la sauvegarde ci-dessus, ou le réglage `vanilla-xp-progression: true`.

## Logs / diagnostic
Au premier démarrage : ligne `config.yml : progression.keep-vanilla-xp (…) n'est plus lue par le plugin — clé retirée.`

## Documentation mise à jour
`docs/PROGRESSION.md`, `docs/RPGQUEST_BIBLE.md`, `docs-site/quests.html`, `docs/current_state.md`,
`docs/MANUAL_TEST_PLAN.md` (TC-347), `docs/deployment/SERVER_CHANGELOG.md`, `docs/SUIVI_VALIDATIONS_DEV.md`
(modifications de `main` reportées, lignes #127/#335 conservées, ligne #183 et décision n° 9 ajoutées).

## Limitations / travail restant
- **Décisions propriétaire** : effacer ou non les anciens niveaux vanilla ; avenir des enchantements / de
  l'enclume (désactivation, autre coût, métiers #182). Rien implémenté.
- La barre d'XP native n'est pas réutilisée pour le RPG (elle montre les anciens niveaux figés).
- `xp-reward` des profils de mob est désormais sans effet ; le champ reste dans PlugAdmin.
- Les guides de content pack (IA) décrivent toujours `EXPERIENCE` sans préciser « RPG ».

## Prochaine étape suggérée
Propriétaire : TC-347 en jeu, puis trancher les deux décisions sur #183.
