# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-24
* Heure : 19:05
* Sujet : #283 lot B — audit de bout en bout des images de référence de l'Atelier IA (navigateur → nginx → PlugAdmin → stockage → fournisseur)
* Statut : DONE (audit sans écart reproduit ; test de non-régression ajouté) — ticket laissé ouvert, WAITING_OWNER (TC-406)
* Branche Git : `test/283-references-entre-etapes` (PR #827) ; rapport `docs/283-rapport-lot-b`
* Commit actuel si disponible : merge `2c5978f6` (PR #827)
* Début de la tâche : 2026-10-24 18:56:56
* Fin de la tâche : 2026-10-24 19:16:06
* Durée totale : 00:19:10

## Demande
Mission backlog du 24/10, lot B (spec `2026-10-24-reprise-backlog-lots.md`) : auditer le chemin complet
des images de référence, vérifier concrètement les 413, limites de taille et de format, la transmission
**effective** des pixels au modèle (preuve technique), la conservation des références entre les étapes et le
parcours « Recommencer depuis ma description ». Partir de la livraison du 22/10 (`0106726`, nginx 5 Mo) ;
ne corriger que les écarts reproduits.

## Analyse

### 1. nginx (vhost PlugAdmin, lecture seule)
`/etc/nginx/sites-enabled/plugadmin` actif : `client_max_body_size 256k` global ; `location = /buildings/workshop/image`
à `5m` avec `error_page 413 = @workshop_image_too_large` → `303` vers le projet avec message. Inchangé depuis le 22/10.
Sauvegarde du 22/10 présente (`/var/backups/nginx-plugadmin.20261022T194428.bak`). Aucune modification nginx aujourd'hui.

### 2. Domaine public (curl, sans compte, aucun enregistrement possible)
| Envoi vers `https://plugadmin.lodylands.com/buildings/workshop/image?id=TESTID&draft=D1` | Résultat |
|---|---|
| 3,0 Mo | `303 → /login` : nginx laisse passer, PlugAdmin répond (session absente) |
| 6,7 Mo | `303 → /buildings/workshop/project?id=TESTID&draft=D1&err=Image trop lourde : 4 Mio…` : pas de page 413 brute, retour sur le projet |
Le parcours authentifié (3,0 Mio ajoutée, 4,5 Mio refusée par PlugAdmin) avait été vérifié le 22/10 ; l'image
`planche_1536x1024.png` (SHA-256 `80d9e5f4…`) est toujours stockée sur le projet « TEST #283 images de référence ».

### 3. Stockage (copie `.backup` du `control-panel.db` de DEV, lecture seule)
7 images dans `building_project_image` sur 4 projets (TEST #283, Église du hub, TEST #747, TEST #747c), toutes
PNG 1536×1024, avec SHA-256 et taille enregistrés. Aucune image orpheline.

### 4. Transmission réelle au fournisseur — preuve technique, sans nouvel appel payant
Le journal IA (#739, table `ai_journal_event`) conserve les 44 requêtes réelles émises sur ces 4 projets.
- **Côté panel** : chaque requête émise **après** l'ajout d'une image (32/32) contient dans son squelette
  des blocs `image_url` remplacés par `⟦image I1 : image/png, 1815587 octets, sha256 21a65754… — pixels transmis⟧`
  (et I2 `42cd6060…`), SHA-256 identiques aux images stockées. Les 9 + 3 requêtes sans image sont toutes
  **antérieures** à l'ajout des images sur leur projet. Toutes ont reçu `HTTP 200` d'OpenAI (`gpt-4o-2024-08-06`).
- **Côté fournisseur** (usage `prompt_tokens` renvoyé par OpenAI) : sur les 12 appels sans image, le ratio est
  stable à **3,31–3,33 caractères/jeton**. Avec images, les jetons d'entrée dépassent le texte seul de :
  - 1 image : 9580 − 28118/3,32 ≈ **1 111** ; 8742 − 25428/3,32 ≈ **1 083** ; 8754 − 25508/3,32 ≈ **1 071** jetons ;
  - formule OpenAI « high detail » pour 1536×1024 (ramenée à 1152×768, 3×2 tuiles) : 85 + 6×170 = **1 105** jetons.
  - 2 images : excès ≈ 2 000–2 200 jetons (attendu 2 210).
  Le fournisseur a donc **facturé et traité les pixels** : un texte ou une URL ne produirait pas cet excès,
  qui suit exactement le nombre d'images et leur taille.
- Conclusion : la transmission multimodale réelle est prouvée par les appels déjà faits par le propriétaire
  et les essais #747 ; l'enveloppe du lot A n'a pas été utilisée (0 appel IA dans cette session).

### 5. Conservation des références entre les étapes
- Code : `PanelApp.referencesOf` relit les images du projet à chaque geste manuel (conception, plan,
  amélioration, recommencer) ; `BuildingDesignPipeline` les charge au démarrage de la conception automatique
  et les joint à chaque passe payante.
- Journal DEV : séquences CONCEPTION → PROPOSITION → CORRECTION ×3 (TEST #747c) et PROPOSITION/CORRECTION
  alternées ×22 (Église du hub) : les deux images partent à chaque appel.
- **Écart de tests (pas de comportement)** : aucun test ne vérifiait les images sur « Améliorer avec l'IA »
  ni sur « Recommencer depuis ma description » (le test de recommencer ne joignait pas d'image).

### 6. « Recommencer depuis ma description »
`planRestart` exige la case de confirmation (appel facturé), puis passe par `planPropose` : nouvelle version
sans parent, aucun plan ni erreur transmis, anciennes versions conservées, images jointes et conservées.

**Aucun écart de comportement reproduit.**

## Travail effectué
- Test de non-régression `WorkshopFlowTest.referenceImagesAreKeptAndSentAsPixelsAtEveryManualStepIncludingRestart` :
  image ajoutée → plan (pixels), « Améliorer avec l'IA » (pixels), « Recommencer depuis ma description »
  (pixels, aucun plan précédent, version sans parent), image toujours attachée (`1 / 4`).
- Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne #283 mise à jour (preuve de transmission, recette OK/KO).

## Fichiers créés
- `docs/claude-reports/2026-10-24_1905_283-lot-b-audit-images-reference.md`

## Fichiers modifiés
- `control-panel/src/test/java/com/lodygames/rpgquest/panel/web/WorkshopFlowTest.java` (PR #827)
- `docs/SUIVI_VALIDATIONS_DEV.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Lecture seule d'une copie `.backup` de la base PlugAdmin de DEV (dans `/tmp`, supprimée en fin de session).

## Configuration / données
Aucune. nginx lu, non modifié. Aucun projet, image ou version modifié.

## Tests automatiques
- `./gradlew :control-panel:test --tests '*WorkshopFlowTest'` (RPGQUEST_TEST_MAX_HEAP=768m) : **52/52** dont le nouveau test.
- CI GitHub de la PR #827 : verte (Artefacts, Scripts, Tests control-panel / plugin / web-api, CI OK), fusionnée dans `chore/257-ci-github-actions` par le merge `2c5978f6`

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` — TC-406 (recette < 5 min, OK/KO) :
1. Atelier → « TEST #283 images de référence » → « Images de référence » → ajouter une vraie image (≤ 4 Mio) → **OK** si vignette visible et toujours là après actualisation.
2. Ajouter un fichier > 5 Mo → **OK** si message « Image trop lourde : 4 Mio au plus… » sur le même projet (pas de page nginx).
3. Facultatif, facturé (1 appel) : « Recommencer depuis ma description » (case cochée) → « Télécharger le journal IA » → **OK** si la dernière requête cite `⟦image I1 … pixels transmis⟧`.

## Résultat attendu
Upload ≤ 4 Mio accepté ; > 4 Mio refusé proprement sur le projet ; pixels joints à chaque appel IA du projet.

## Reset / retour à l'état initial
Rien à réinitialiser : aucun état modifié.

## Déploiement VeryGames
Aucun : changement de test uniquement.
### À transférer
Rien.
### Ne PAS transférer/altérer
Le vhost nginx PlugAdmin, les images et projets existants.
### Redémarrage requis
Non.
### Migration automatique
Non.

## Rollback
Sans objet (aucun déploiement).

## Logs / diagnostic
Requêtes sur le journal : `ai_journal_event` (kind `REQUEST`, motif `pixels transmis`) et `RESPONSE`
(`prompt_tokens`) d'une copie `.backup` de `/var/lib/plugadmin/control-panel.db`.

## Documentation mise à jour
Registre de validation DEV ; index des rapports.

## Limitations / travail restant
- Les autres routes PlugAdmin restent à 256k côté nginx (vérifié : 300 Ko vers `/content/import` → 413 brut).
  Hors périmètre de l'autorisation nginx de #283 (route d'upload d'image seule) ; noté, non corrigé.
- La preuve de transmission repose sur OpenAI (seul fournisseur utilisé sur DEV) ; Anthropic est couvert par
  `ProviderImagePayloadTest` uniquement.

## Prochaine étape suggérée
Recette TC-406 par le propriétaire (OK/KO). Décider si l'import de pack doit être relevé côté nginx (ticket séparé).
