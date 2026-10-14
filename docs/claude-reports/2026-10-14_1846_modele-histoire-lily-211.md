# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-14
* Heure : 18:46
* Sujet : Issue #211 — modèle d'histoire pour IA externe et pack « Les souvenirs de Lily » importable en une confirmation
* Statut : DONE (livré et déployé sur PlugAdmin DEV ; parcours en jeu PENDING MANUAL VALIDATION)
* Branche Git : `feature/211-lily-story-template` (PR #384, fusionnée dans `chore/257-ci-github-actions`)
* Commit actuel si disponible : `e79afe8` (merge PR #384)
* Début de la tâche : 2026-10-14 18:14:42
* Fin de la tâche : 2026-10-14 18:47:24
* Durée totale : 00:32:42

## Demande
Session autonome lqorch, ticket #211 (priorité propriétaire du 14/10 17:49), lu en entier avec
#109 et #110 : bouton « Télécharger un modèle d'histoire » sur Import contenu, fiche narrative
Lily, pack JSON Lily valide (Lily + Julius, story de 5 quêtes, dialogues, liens), modèle vierge,
prompt prêt à copier, guide autonome pour une IA ne connaissant pas RPGQuest ; import en une
confirmation, sans publication implicite, sans création partielle ni doublon ; réutiliser le
pipeline existant ; Tom à 3 quêtes reste le test de non-régression ; aucune coordonnée inventée,
aucun lien vers un PNJ Citizens arbitraire ; aucun appel IA payant.

## Analyse
- L'import JSON existait déjà (#109 + correctif #303 : JSON, ids namespacés, tout-ou-rien avec
  retour arrière, création des PNJ logiques par `npc.definition.create`, pack Tom). Le contrat
  #110 (`schema.json`, gabarits, `contract.md`) est généré depuis `Descriptors`. Rien à refaire
  côté moteur d'import : la tranche manquante était le **kit auteur/IA**, le **pack Lily** et
  l'**aperçu éditorial** avant confirmation (l'analyse ne montrait que des statuts et diagnostics).
- Capacités réelles vérifiées dans le code : `DELIVER_ITEM_TO_NPC` + action `DELIVER_QUEST_ITEMS`
  + condition `HAS_PENDING_DELIVERY` (remise effective, #123/#318), `TALK_TO_NPC` (clic sur le PNJ,
  Citizens compris), condition `QUEST_AVAILABLE` (ne propose jamais une quête terminée ou
  verrouillée), `negate`. Un dialogue s'ouvre par **convention** `rpgquest:<id du PNJ>`
  (`DialogueCitizensNpcInteractListener`) ; `giver` ne sert qu'aux indices et au journal.
- **Sur DEV, `lily` et `julius` existent déjà** comme PNJ logiques liés à Citizens (#10 et #11),
  sans dialogue (relevé `npc.list` du 14/10 12:47). Le pack utilise ces mêmes ids : l'import les
  **réutilise sans les modifier** (SKIPPED) et les dialogues `rpgquest:lily`/`rpgquest:julius` s'y
  rattacheront à la publication. Le propriétaire a aussi un brouillon non commité
  `stories/lily_memories` + `quests/lily_pumpkin` : ids du pack choisis distincts
  (`lily_souvenirs`, `rpgquest:lily_*`) pour qu'aucune collision ne bloque l'import.
- Tarte : aucune recette personnalisée n'existe. Traduction honnête : Lily cuisine dans le récit ;
  le joueur remet les ingrédients (quête 3) puis 4 charbons et reparle à Lily (quête 4, étape
  `TALK_TO_NPC`) ; récompense 2 × `PUMPKIN_PIE` vanilla. L'alternative `CRAFT_ITEM PUMPKIN_PIE`
  est documentée. Pas de `REACH_LOCATION` (aucune coordonnée connue) : quête 5 = parler à Julius.

## Travail effectué
1. **Kit** (`control-panel/src/main/resources/content-packs/`) : `modele-histoire-lily.md`,
   `modele-histoire-vierge.md` (fiches d'auteur, « n'est PAS importable » en tête),
   `exemple-pack-lily.json`, `modele-pack-vierge.json` (squelette valide « À REMPLIR »),
   `prompt-ia.txt`, `guide-ia-base.md` (guide pédagogique : enveloppe, liens story↔quêtes↔
   dialogues↔personnages, ids, table « souhait de l'auteur → objectif », modèles de dialogue,
   objets Minecraft, checklist). Le guide servi = partie fixe + `ContentPackTemplates.aiDocumentation()`
   (contrat généré : il ne peut ni oublier ni inventer un type).
2. **`StoryKit`** : liste blanche des fichiers, archive `.zip` avec `LISEZ-MOI.txt`.
   Route `GET /content/story-kit` (archive) et `?file=<nom>` (404 hors liste), permission
   `CONTENT_IMPORT` ou `CONTENT_EXPORT`, audit `content.storykit.download`.
3. **Import contenu** : carte « Créer une histoire avec une IA externe » (6 étapes, bouton
   « Télécharger un modèle d'histoire », 6 liens unitaires, prompt en zone de texte sélectionnable,
   bouton « Analyser l'exemple Lily » qui n'écrit rien). Repliée dès qu'une analyse est affichée.
4. **Aperçu éditorial** (`ContentPackSummary`) dans l'analyse : story dans l'ordre, chaque quête
   (donneur, déblocage, objectifs avec quantités, récompenses), personnages avec leur sort (créé
   SANS placement / déjà présent et réutilisé), dialogues et quêtes qu'ils proposent ; YAML exact
   qui sera écrit pour chaque élément nouveau (replié).
5. **Correctif découvert au rendu local** : sans agent configuré (ou si l'action ne peut être
   enregistrée), la confirmation écrivait les fichiers puis levait une exception → **réponse vide**.
   L'échec est maintenant affiché sur la page et audité (`enqueueImportedNpcs`).
6. Documentation : fiche panel `content-packs.md` (nouvelle section + limites corrigées), bible,
   `current_state.md`, TC-312, `SERVER_CHANGELOG.md`, `docs/content-packs/README.md`.

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/StoryKit.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/content/ContentPackSummary.java`
- `control-panel/src/main/resources/content-packs/` (6 fichiers ci-dessus)
- `control-panel/src/test/java/.../content/LilyContentPackTest.java`, `StoryKitTest.java`
- `src/test/java/com/lodygames/rpgquest/content/pack/LilyStoryPackEngineTest.java`
- `docs/content-packs/README.md`, ce rapport

## Fichiers modifiés
- `control-panel/.../web/ContentImportPages.java`, `web/PanelApp.java`
- `control-panel/src/main/resources/docs/content-packs.md`
- `control-panel/src/test/.../web/ContentImportPageTest.java`, `ContentContractRoutesTest.java`,
  `support/TestConfig.java`
- `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/MANUAL_TEST_PLAN.md`,
  `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Le pack Lily n'a été **ni importé ni publié** sur DEV (la source du panel DEV est l'arbre
du propriétaire ; la confirmation n'a été testée que sur une copie locale isolée).

## Tests automatiques
- Panel ciblé (local) : `LilyContentPackTest` 8/8 (import en une confirmation, réimport sans
  doublon, PNJ DEV réutilisés, collision bloquante, retour arrière complet, aperçu, pack vierge
  valide), `StoryKitTest` 4/4, `ContentImportPageTest` 14/14, `ContentContractRoutesTest` 7/7,
  `TomContentPackTest` 5/5 (non-régression Tom), `ContentPackContractTest` 31/31,
  `ContentPackImportTest` 34/34, `DocsPagesTest` 8/8.
- Plugin ciblé : `LilyStoryPackEngineTest` 2/2 — les 5 quêtes et 2 dialogues du pack se chargent
  avec les **vrais** `QuestLoader` / `DialogueLoader` du moteur.
- CI GitHub (suites complètes plugin, panel, web-api, scripts, artefacts) : **verte** sur la PR
  #384 et sur le merge `e79afe8`.
- Rendu réel (panel local, Playwright Chromium) : bureau 1280 px et téléphone 390 px de la page
  vide, de l'analyse Lily, de la confirmation et du réimport ; téléchargement de l'archive depuis
  le téléphone (7 fichiers). Captures dans `/tmp/claude-211-panel/shots/` (hors dépôt).

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — `docs/MANUAL_TEST_PLAN.md` **TC-312** (40 min) : téléchargement
téléphone/bureau, analyse et confirmation Lily dans PlugAdmin DEV, édition d'une quantité et d'une
réplique, publication **des seuls 8 fichiers Lily**, parcours en jeu des 5 quêtes jusqu'au
dialogue de Julius, réimport, collision. Facultatif : faire produire un pack par une vraie IA
externe avec la fiche + guide + prompt. Tom : TC-298 toujours en attente.

## Résultat attendu
Sur Import contenu : télécharger le modèle (un .zip), le donner à une IA, déposer le .json rendu,
lire l'aperçu, confirmer une fois → story + 5 quêtes + 2 dialogues dans la source ; personnages
inconnus créés sans placement, personnages connus réutilisés ; rien n'est publié.

## Reset / retour à l'état initial
Après un essai : supprimer `quests/lily_{repas,fleurs,ingredients,tarte,julius}.yml`,
`stories/lily_souvenirs.yml`, `dialogues/lily.yml`, `dialogues/julius.yml` de la source (et, s'ils
ont été publiés, retirer ces contenus de DEV par le flux de publication habituel).

## Déploiement VeryGames
### À transférer
Rien côté VeryGames (aucun JAR). PlugAdmin redéployé sur l'hôte AWS : `scripts/plugadmin/deploy.sh
--wait-ci` depuis `e79afe8` ; `DEPLOYED` = `e79afe8`, CI verte ; jar servi contient `StoryKit`,
`ContentPackSummary` et `content-packs/*` ; `/content/story-kit` répond 303 → /login sur le port
8090 (une route absente répond 404).
### Ne PAS transférer/altérer
`config.yml`, `data.db`, contenus du propriétaire non commités (`lily_memories`, `lily_pumpkin`…).
### Redémarrage requis
Non pour Minecraft ; `plugadmin.service` redémarré par le script.
### Migration automatique
Aucune.

## Rollback
Restaurer `/opt/plugadmin/releases/20261014-184016` (sauvegarde faite par le script) et redémarrer
`plugadmin.service`.

## Logs / diagnostic
Audit panel : `content.storykit.download`, `content.import.analyze|confirm|write|npc`
(`FAILED` + motif si la création d'un PNJ échoue).

## Documentation mise à jour
Fiche « Content packs » du panel, `RPGQUEST_BIBLE.md` (section `/content/story-kit`, paragraphe
PNJ de l'import), `current_state.md`, TC-312, `SERVER_CHANGELOG.md`. Aucune page `docs-site/`
ne couvre PlugAdmin/import : rien à y aligner.

## Limitations / travail restant
- **Publier `rpgquest:lily` change le clic sur Lily** sur DEV (le dialogue remplace le
  comportement actuel) — c'est voulu par le pack, mais à décider par le propriétaire au moment de
  publier ; son brouillon `lily_pumpkin` reste distinct.
- Sans relevé `npc.list` récent, un PNJ déjà créé par un import précédent peut réapparaître
  « nouveau » au réimport et sa création être redemandée (comportement #303 inchangé ; le serveur
  refuse un id existant).
- Débordement horizontal de 23 px sur téléphone, **préexistant** et commun à toutes les pages
  (panneau de notifications hors écran), pas lié à ce lot.
- Le résumé `<summary>` de la carte affiche le triangle sur sa propre ligne (cosmétique).
- Pas de vérification authentifiée sur le panel DEV lui-même (compte jetable non créé) : le rendu a
  été vérifié sur une instance locale du même code.

## Prochaine étape suggérée
Le propriétaire exécute TC-312 (et TC-298 Tom). Ensuite, #109 complet (diff/brouillon génériques)
et #110 (packs massifs) peuvent s'appuyer sur ce kit.
