# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-24
* Heure : 17:49
* Sujet : #747 (P0) — lot A du 24/10 : mémoire et exigences de l'Atelier IA, construction avec avertissements, essai supervisé (≤ 20 appels)
* Statut : WAITING_OWNER (livré et déployé sur DEV ; essai réel concluant sur deux projets de test ; validations TC-428 / TC-421 et choix du modèle par le propriétaire)
* Branche Git : `fix/747-avertissements-exigences` (PR #816), `fix/747-toit-mesure` (PR #818), `fix/747-motif-conformite` (PR #820), `fix/747-toit-conception` (PR #821) ; intégration `chore/257-ci-github-actions`
* Commit actuel si disponible : JAR DEV `e9b4376a` ; PlugAdmin DEV `0209cda8`
* Début de la tâche : 2026-10-24 14:20:44
* Fin de la tâche : 2026-10-24 17:49:37
* Durée totale : 03:28:53

## Demande
Spec `2026-10-24-reprise-backlog-lots.md`, lot A, et commentaires du propriétaire sur #747 (23/10 15:23 et 16:01) :
conserver les exigences à chaque itération, comparer les versions, ne pas détruire l'acquis, mesurer la conformité
architecturale ; « Enregistrer malgré les avertissements » une grille complète, contrôlé côté serveur, sur geste du
propriétaire ; traiter la contradiction de la cabane (pièce vide puis mobilier) ; tentative supervisée jusqu'à
20 appels, arrêt sans progrès, modèle le moins cher qui progresse, projets de TEST uniquement.

## État de départ (vérifié)
DEV : JAR `c42c6bf0`, PlugAdmin `92a18285` ; seule de la documentation depuis. Meilleure version de l'église de
test V-8776ad09 : un défaut `UNSUPPORTED_BLOCK` (lanterne sous la cloche), non enregistrable. Dernière V-007bcdcc
`PLAN_INVALID`. Seul fournisseur configuré : OpenAI, modèle par défaut `gpt-4o`, 4 000 jetons, 90 s.

## Travail effectué
**Plugin (PR #816)**
- `AcceptableDefects` : défauts contournables (décor sans support, zones sombres, fenêtres, toit/enveloppe, accès,
  étages) et leur effet en jeu ; impossibilités jamais contournables (`PLAN_INVALID`, bloc/op inconnu, hors volume,
  limites, `EMPTY_STRUCTURE`, `BROKEN_ASSEMBLY`, passage de portail).
- `building.generate` reçoit `accept_defects` : rien n'est écrit si les codes ne sont pas **exactement** ceux de
  l'audit du plan reçu (`WARNINGS_CHANGED`) ou si l'un est une impossibilité (`DEFECT_NOT_ACCEPTABLE`). Succès
  `GENERATED_WITH_WARNINGS`, description « ENREGISTRÉ AVEC AVERTISSEMENTS ACCEPTÉS : … », effets listés ; l'aperçu de
  pose le rappelle. L'audit expose `acceptable` (code → effet).

**PlugAdmin (PR #816, #818, #820, #821)**
- « Enregistrer malgré les avertissements » (carte Vérifier) : défauts + effets, case obligatoire, vérification à
  jour de la même révision exigée ; « Enregistré avec avertissements », jamais « conforme ».
- **Exigences actualisées** : les consignes d'« Améliorer » sont rangées au journal (`REQUIREMENT_UPDATE`) et
  repartent avec chaque appel suivant, **prioritaires** sur description et conception (la plus récente l'emporte) ;
  closes par une modification de la description ; conformité lue sur description + consignes ; une version
  acceptée est reprise comme « demande de modifications », plus comme « proposition refusée ».
- **Essai avec un autre modèle** : modèle et plafond de sortie pour un seul appel, journalisés (`trial`), réglages
  enregistrés inchangés ; identifiant mal formé refusé sans appel.
- Mesures nouvelles : toiture en pente (plan + grille, ou `VOLUME … toit:` dans la conception), torches murales,
  « table de fabrication », escalier signalé dans un bâtiment de plain-pied ; prompt : `walls` sous un toit de
  plain-pied, torche murale expliquée.
- Version acceptée mais description non réalisée : « Améliorer » s'ouvre et part sans consigne (écarts mesurés).
- Réparation moteur comparée à la version que l'IA devait améliorer ; un plan **vide** envoyé n'est plus enregistré.
- nginx PlugAdmin : 150 s pour `/buildings/workshop/project` (504 avec un modèle qui raisonne) — modèle du dépôt et
  vhost en service (sauvegarde `/etc/nginx/sites-available/.plugadmin.bak-747-20261024-155119`, `nginx -t`, reload).

## Essai supervisé (projets de TEST uniquement ; Église du hub et Cabane du Hub non touchées)
| # | Projet | Geste | Modèle | Départ | Résultat | Jetons entrée / sortie | Coût estimé |
|---|---|---|---|---|---|---|---|
| 1 | TEST #747c église | Améliorer (réparation) | gpt-5-mini | V-8776ad09 (1 défaut) | V-6890d271 `PLAN_INVALID` (palier) → moteur **V-25f197ff conforme**, 14/14 exigences mesurables (bougies ajoutées ; tour, cloche dans la tour, allée, nef, tour, 2 toits conservés) | 16 642 / 7 982 | 0,020 $ |
| 2 | TEST #747d cabane (nouveau, brief de la cabane copié) | Conception avant plan | gpt-5-mini | — | **délai 90 s dépassé**, rien enregistré | non remonté | ≤ 0,034 $ si facturé |
| 3 | 747d | Conception avant plan | gpt-4.1 | — | conception v1 | 1 850 / 1 314 | 0,014 $ |
| 4 | 747d | Créer un plan | gpt-4.1 | conception | V-b731686b `PLAN_INVALID` (toit hors volume) → moteur V-95d6ee8c (`UNSUPPORTED_BLOCK`) | 8 475 / 580 | 0,022 $ |
| 5 | 747d | Améliorer + consigne réelle du propriétaire (mobilier, seuil, torches murales, pas d'étage) | gpt-4.1 | V-95d6ee8c | bloc EXIGENCES ACTUALISÉES envoyé, mode amélioration automatique ; V-fbdecabb → moteur V-e9035550 conforme : lit rouge, coffre, établi, four ; **torches au sol, escalier ajouté** | 10 722 / 701 | 0,027 $ |
| 6 | 747d | Améliorer **sans consigne** (écarts mesurés) | gpt-4.1 | V-e9035550 | consigne n° 5 renvoyée ; **V-695f06d4 conforme sans réparation** : `walls` au lieu de `shell`, escalier retiré, `wall_torch`, mobilier et toit gardés ; conformité complète | 10 935 / 677 | 0,027 $ |

Total : **6 appels sur 20** (1 échec), ≈ **0,11 $** mesurés + ≤ 0,03 $ pour l'appel expiré (tarifs publics OpenAI :
gpt-5-mini 0,25 $ / 2 $ ; gpt-4.1 2 $ / 8 $ par million — pas une facture). Aucune réponse 429 ni refus ; quotas
fournisseur non exposés par le journal. Aucune régression d'une erreur résolue ; arrêt sur objectif atteint
(chaque projet a une version conforme qui garde ses exigences), pas sur la limite.

## Constats
- gpt-4o « défaisait » les corrections ; **gpt-5-mini** a corrigé l'église en un appel, moins cher que gpt-4o, mais
  répond en ~70 s (504 nginx corrigé ; une conception longue dépasse les 90 s du fournisseur). **gpt-4.1** (moins
  cher que gpt-4o) a suffi à la cabane, en 3 à 7 s par appel. Aucun réglage global modifié : décision du propriétaire.
- Mon premier envoi scripté ne lisait pas la zone de texte et a envoyé un plan vide : révision 2 **vide** de
  V-8776ad09. Restaurée (révision 4, empreinte identique à la révision 1 ; une révision 3 ne diffère que d'un saut
  de ligne final). Le trou du panel est corrigé (plan vide refusé).
- J'ai d'abord cru que gpt-5-mini avait supprimé les toits (diff tronqué) : le test sur la grille réelle a montré
  qu'il les avait déplacés. L'exigence « toit » a été gardée (aucun contrôle n'existait), le constat corrigé.
- La réparation moteur peut ajouter un escalier vers le dessus d'un `shell` dans un bâtiment de plain-pied : c'est
  désormais signalé et transmis ; le moteur, lui, ne connaît pas la description (inchangé).

## Fichiers créés
Plugin : `AcceptableDefects.java`, `AcceptableDefects747Test.java`, fixtures `eglise_747_{8776ad09,007bcdcc}.yml`.
Panel : `building/AcceptableDefects.java`, `ai/RequirementUpdates.java`, tests `RequirementUpdates747Test`,
`RoofRequirement747Test`, `Cabane747Test`, fixtures `ai/eglise747/{plan,preview}_{8776ad09,25f197ff}`,
`ai/cabane747/*`. Ce rapport.

## Fichiers modifiés
Plugin : `BuildingGenerator`, `BuildingPlacementService`, `AgentActions`, `BukkitAgentActions`,
`AgentActionExecutor`. Panel : `AgentActionCatalog`, `AiBuildingStudio`, `AiJournalStore`, `AiProviderSettings`,
`BuildingAiJournal`, `BuildingPromptBuilder`, `BriefRequirements`, `CompiledMeasures`, `ArchitecturalDesign`,
`PanelApp`, `WorkshopPages`, `WorkshopFlowTest`. `scripts/plugadmin/nginx-plugadmin.conf`. Docs : Bible,
`BATIMENTS_PARCOURS.md`, `MANUAL_TEST_PLAN.md` (TC-428), `SERVER_CHANGELOG.md`, `SUIVI_VALIDATIONS_DEV.md`, index.

## Base de données / migrations
Aucune migration. Écritures DEV : deux projets de test (« TEST #747c église » : V-6890d271, V-25f197ff, révisions
2–4 de V-8776ad09 ; « TEST #747d cabane », nouveau : 6 versions, 1 conception), journal IA, actions d'audit.
Compte temporaire `claude-747d` créé puis supprimé. Aucun bâtiment enregistré ni posé, aucun bloc.

## Configuration / données
Réglages IA inchangés (`gpt-4o` configuré). nginx PlugAdmin : délai de lecture 150 s sur une seule route.

## Tests automatiques
`AcceptableDefects747Test` (6, plugin), `RequirementUpdates747Test` (5), `RoofRequirement747Test` (5),
`Cabane747Test` (4), `WorkshopFlowTest` (51, dont +6) et suites `Eglise747*`, `BuildingDesignPipelineTest`,
`BuildingCorrectionLoopTest` verts localement ; CI GitHub verte sur les 4 PR et leurs fusions.

## Tests manuels à effectuer
TC-428 (`PENDING MANUAL VALIDATION`) ; TC-421, TC-419, TC-408 toujours en attente. Aucun test en jeu par Claude.

## Résultat attendu
Le propriétaire peut enregistrer et poser V-8776ad09 avec son défaut accepté ; V-007bcdcc reste bloquée ; deux
versions conformes (V-25f197ff église, V-695f06d4 cabane) sont prêtes à être jugées à l'œil.

## Reset / retour à l'état initial
Projets de test retirables depuis l'Atelier. nginx : remettre la sauvegarde du vhost puis `systemctl reload nginx`.

## Déploiement VeryGames
### À transférer
Fait : JAR `e9b4376a` via `scripts/deploy-verygames.sh -y` (CI verte, sauvegarde
`rpgquest-20261024T133722Z-predeploy.jar`), SHA-256 `efe11346…` retéléchargé identique, DEV redémarré (seul le
propriétaire connecté). PlugAdmin `e9b4376a`, `83c6c2f8`, `36369d0c`, `0209cda8` (`deploy.sh --wait-ci`,
`/health` ONLINE, classes vérifiées dans le JAR servi).
### Ne PAS transférer/altérer
`data.db`, configs, mondes, contenus.
### Redémarrage requis
Fait (JAR).
### Migration automatique
Aucune.

## Rollback
JAR : `scripts/rollback-verygames.sh` avec `rpgquest-20261024T133722Z-predeploy.jar`, puis redémarrage. PlugAdmin :
`scripts/plugadmin/rollback.sh app`. nginx : sauvegarde ci-dessus.

## Logs / diagnostic
`~/.local/state/lodyquests-build/747d-*.log`.

## Documentation mise à jour
Bible (Atelier), `BATIMENTS_PARCOURS.md`, TC-428, changelog serveur, registre, index.

## Limitations / travail restant
- Choix du modèle par le propriétaire (aucun changement fait) : gpt-4.1 (rapide, moins cher que gpt-4o) ou
  gpt-5-mini (meilleur sur l'église, ~70 s : porter le délai du fournisseur à 150 s).
- Autel, estrade, porche, croix, charpente restent « à apprécier visuellement ».
- Les exigences actualisées vivent dans le journal IA, borné par sujet : au-delà de sa rotation, les plus
  anciennes consignes disparaissent (modifier la description les consolide).
- « Enregistrer malgré les avertissements » n'a pas été cliqué par Claude (geste du propriétaire).

## Prochaine étape suggérée
Propriétaire : TC-428 (enregistrer V-8776ad09 avec avertissement, poser, retirer), puis juger à l'œil V-25f197ff
et V-695f06d4, et choisir le modèle.
