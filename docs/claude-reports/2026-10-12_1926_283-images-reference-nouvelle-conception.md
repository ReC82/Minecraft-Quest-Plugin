# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-12
* Heure : 19:26
* Sujet : #283 — images de référence transmises à l'IA, « Recommencer depuis ma description »
* Statut : PARTIAL (code, tests, CI et fusion dans la ligne d'intégration faits ; validation manuelle TC-292 et déploiement en attente ; comparaison côte à côte de plusieurs propositions non traitée)
* Branche Git : `feature/283-reference-images-fresh-design` → fusionnée dans `chore/257-ci-github-actions` (PR #286, merge `449b12c`)
* Commit actuel si disponible : `449b12c` (correctif de course `bc44a52`)
* Début de la tâche : 2026-10-12 19:02:30
* Fin de la tâche : 2026-10-12 19:26:00
* Durée totale : 00:23:30

## Demande
Ticket #283, à traiter de façon autonome après #282 : pouvoir **repartir de zéro** depuis la description (nouvelle génération indépendante, sans hériter du YAML ni des erreurs, sans rien supprimer) et **joindre des images de référence** réellement transmises au modèle (pixels, pas une URL), avec contrôles MIME, taille, nombre, droits, stockage sûr, suppression, dégradation claire si le fournisseur ne les accepte pas ; images = référence de style, pas conversion 1:1. Aucun appel payant en dev/CI.

## Analyse
- Les fournisseurs (`OpenAiProvider`, `AnthropicProvider`, `GeminiProvider`) n'envoyaient que du texte ; `AiProvider.Request` n'avait pas de pièce jointe.
- Le panel ne savait lire que des formulaires `application/x-www-form-urlencoded` ; la CSP interdit tout script qui encoderait un fichier côté navigateur → il fallait lire un `multipart/form-data` en octets côté serveur.
- « Créer un plan avec l'IA » n'était proposé qu'à l'étape « Proposer » ; ensuite seule la correction (qui hérite du plan) était possible.

## Travail effectué
- `AiProvider.Request` porte des `Image(mime, data)` ; chaque fournisseur envoie les **pixels** en base64 dans son format natif : OpenAI `image_url` avec URL `data:`, Anthropic bloc `image` source `base64` avant le texte, Gemini `inlineData`. Sans image, le corps est **identique** à l'ancien (testé).
- `BuildingPromptBuilder.withReferences` : consigne « références de style », dimensions et blocs vanilla maintenus, équivalent le plus proche si l'image dépasse le vocabulaire.
- `ReferenceImageStore` (`building_project_image`) : 4 images, 4 Mio, type par signature d'octets (PNG/JPEG/WebP/GIF ; HTML ou SVG déguisés refusés), SHA-256 anti-doublon, nom nettoyé, retrait = `DELETE`, lecture uniquement pour le projet propriétaire.
- `Multipart` : lecteur RFC 7578 binaire, minimal, refus de tout ce qui n'est pas compris.
- Route `/buildings/workshop/image` : POST multipart (CSRF, permission Atelier, audit `building.project.image.add`), GET servi avec le type détecté, `nosniff`, 404 hors du projet ; retrait par `_action=image_remove` (audit).
- Les images partent avec **chaque** appel du projet : proposition, correction, recommencement, conception automatique (toutes passes, critique comprise).
- « Recommencer depuis ma description » (`_action=restart`) : confirmation cochée, jeton à usage unique, version **sans parent** ; rien n'est supprimé ni écrasé. Conception automatique : case « Repartir de zéro » (`building_design_run.fresh`, colonne ajoutée sans perte si la table #282 existe déjà).
- **Correctif trouvé par la CI** (`bc44a52`) : l'arrêt du panel n'attendait pas la session de conception en cours, qui pouvait écrire en base après l'arrêt. `shutdown()` marque la session interrompue et attend sa fin (10 s max).

## Fichiers créés
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/building/ReferenceImageStore.java`
- `control-panel/src/main/java/com/lodygames/rpgquest/panel/http/Multipart.java`
- Tests : `ReferenceImageStoreTest`, `MultipartTest`, `ProviderImagePayloadTest`

## Fichiers modifiés
- `ai/{AiProvider,OpenAiProvider,AnthropicProvider,GeminiProvider,AiBuildingStudio,BuildingPromptBuilder,BuildingDesignPipeline}.java`, `building/BuildingDesignRunStore.java`, `web/{PanelApp,WorkshopPages}.java`
- Tests : `WorkshopFlowTest` (+2), `BuildingDesignPipelineTest` (+2)
- Docs : Bible (« Images de référence et nouvelle conception indépendante »), `current_state.md`, `MANUAL_TEST_PLAN.md` (TC-292), `SERVER_CHANGELOG.md`

## Base de données / migrations
`control-panel.db` : table `building_project_image` (création au démarrage) ; colonne `building_design_run.fresh` (ajout idempotent). `data.db` : rien.

## Configuration / données
Aucune. Aucune image ni donnée réelle manipulée ; aucun appel payant.

## Tests automatiques
- Locaux ciblés : `*WorkshopFlowTest*`, `*ReferenceImageStoreTest*`, `*MultipartTest*`, `*ProviderImagePayloadTest*`, `*BuildingCorrectionLoopTest*`, puis `*.ai.*` + `*Building*` (4 min) — verts.
- GitHub Actions : premier run PR **rouge** (course décrite ci-dessus, `WorkshopFlowTest.anAutomaticDesign…`), corrigée ; run PR 38283546904 sur `bc44a52` **vert** (plugin, panel, web-api).

## Tests manuels à effectuer
TC-292 — PENDING MANUAL VALIDATION (1 appel facturé).

## Résultat attendu
Un administrateur joint une photo de bibliothèque médiévale, la voit en vignette, peut la retirer ; toute demande IA du projet reçoit l'image ; il peut repartir de sa description sans toucher au YAML ni perdre ses versions.

## Reset / retour à l'état initial
Revenir avant `449b12c` ; `DELETE FROM building_project_image` efface toutes les images (aucune dépendance).

## Déploiement VeryGames
**Non effectué.** Panel seul.
### À transférer
PlugAdmin depuis `449b12c` (inclut #282 : le JAR #282 reste à déployer séparément).
### Ne PAS transférer/altérer
Rien côté Minecraft.
### Redémarrage requis
Service panel uniquement.
### Migration automatique
Oui (table + colonne, idempotent).
## Rollback
Panel sauvegardé ; table et colonne ignorées par l'ancien panel.
## Logs / diagnostic
Audit : `building.project.image.add` (avec mime et taille, jamais les octets), `building.project.image.remove`.
## Documentation mise à jour
Bible, current_state, TC-292, SERVER_CHANGELOG.
## Limitations / travail restant
- **Comparer plusieurs propositions en aperçu 3D côte à côte** (demandé par #283) : non fait ; les versions restent comparables une à une via la liste des versions.
- Pas de redimensionnement côté panel (aucune bibliothèque d'image) : une image de plus de 4 Mio est refusée avec la consigne de la réduire.
- Compatibilité modèle : non vérifiable sans appel réel — gpt-4o accepte les images ; un modèle texte seul fera échouer l'appel avec l'erreur du fournisseur affichée.
- Effet réel des images sur la qualité : à mesurer pendant les essais OpenAI (TC-291/292).
## Prochaine étape suggérée
#284 (suppression sûre depuis la Bibliothèque), puis, après feu vert, déploiement JAR + panel et essais réels.
