# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-20
* Heure : 19:28
* Sujet : #18 — préparer le test TC-345 (atelier d'artisan) : constats DEV, 3 recettes, PNJ d'accès, parcours propriétaire
* Statut : PARTIAL (accord du propriétaire attendu avant toute application sur DEV)
* Branche Git : `docs/18-preparation-tc345` (rapport, registre, TC-345, correction ECONOMY.md)
* Commit actuel si disponible : base `82c1e637` (`chore/257-ci-github-actions`)
* Début de la tâche : 2026-10-20 19:23:59
* Fin de la tâche : 2026-10-20 19:28:09
* Durée totale : 00:04:10

## Demande
Le superviseur demandait (demande du propriétaire du 20/10) :
1. vérifier en lecture seule les constats DEV : aucun prix, `artisan_demo` qui cite des recettes inexistantes, aucun dialogue qui l'ouvre ;
2. proposer 3 recettes (ingrédients seuls, monnaie seule, mixte) en réutilisant `artisan_demo` et Prix et coûts (#336) ;
3. proposer un PNJ et un dialogue d'accès ;
4. rédiger le parcours TC-345.

Tout cela sans rien publier sur DEV.

## Analyse
- **Lecture seule DEV.** J'ai lancé `price.catalog.status` et `merchant.catalog.status` (`created_by = claude-18`), et j'ai repris les relevés `dialogue.list`, `npc.list` et `npc.citizens.list` faits par #17 à 19:19. Résultats :
  - `prices.yml` absent, 0 prix ;
  - `artisan_demo` : `unavailableRefs` = ses 3 `demo_recette_*`, `usages: []` ;
  - aucun des 13 dialogues ne contient `OPEN_MERCHANT`.
  Les trois constats sont donc **exacts**.
- **Pas de défaut de code.** Les prix manquants sont déjà signalés dans PlugAdmin à deux endroits (`AgentPages`) : sur la fiche Marchands et dans la colonne « Prix du catalogue » de Prix et coûts. En jeu, ces offres apparaissent comme « Offre indisponible ».
- **Écart de documentation.** `docs/ECONOMY.md` disait encore « fabrication sans ingrédient » refusée. Ce n'est plus vrai depuis #18 : seule une recette sans ingrédient **ni** pièce est refusée. C'est corrigé.
- **PNJ.**
  - **Lucien** (Citizens n°14, `world_hub` ≈ 722 66 -719) est lié, mais son dialogue DEV n'est qu'un squelette (« Salut ! » / « Au revoir »), et la quête `test_lucien_artisanat` du propriétaire en fait l'artisan. **Recommandé.**
  - Le Libraire reste proposé pour `village_merchant` (#17).
  - Alternative : Stan n°7, libre, mais il faudrait 3 opérations.
- **Recettes.** J'ai gardé les 3 clés déjà citées par `artisan_demo` : BREAD ← 3 WHEAT (0), LANTERN (12), IRON_SWORD ← 2 IRON_INGOT + 1 STICK (5).
  - Aucun résultat n'est racheté par un marchand.
  - L'arbitrage du chargeur ne relève aucune boucle.
- **Inventaire plein.** La vérification simule le retrait des ingrédients. Le cas d'échec doit donc utiliser la lanterne : comme elle ne prend aucun ingrédient, aucune case n'est libérée.

## Travail effectué
- Diagnostic en lecture seule (DEV + code).
- `prices.yml` proposé et dialogue `lucien` proposé, validés par les vrais `PriceCatalogLoader` et `DialogueLoader` dans un test JUnit **temporaire**, supprimé ensuite et jamais commité :
  - prix : 0 erreur, 3 prix chargés, avertissement « démonstration » seulement ;
  - dialogue : 1 chargé, 0 problème.
- Commentaire #18 publié : constats, tableau des recettes, YAML exact, diff du dialogue, parcours en 9 étapes avec les messages attendus, reset, réponse attendue « OK Lucien ».
  https://github.com/ReC82/Minecraft-Quest-Plugin/issues/18#issuecomment-6296971971
- `docs/MANUAL_TEST_PLAN.md` TC-345 : ajout de la préparation proposée, de la consigne pour l'inventaire plein et de la vérification « Ouvert par : dialogue ».
- `docs/ECONOMY.md` : règle « pièces seules » corrigée.
- Registre ligne 11b (#18) : « Bloqué — accord propriétaire », prochaine action « OK Lucien ».
  - J'ai vérifié le registre de `origin/main` : son seul ajout (`92d2bf07`, #636) est déjà dans la branche d'intégration. Rien à reporter.

## Fichiers créés
- `docs/claude-reports/2026-10-20_1928_18-preparation-tc345-atelier.md`

## Fichiers modifiés
- `docs/SUIVI_VALIDATIONS_DEV.md`
- `docs/MANUAL_TEST_PLAN.md`
- `docs/ECONOMY.md`
- `docs/claude-reports/README.md`

## Base de données / migrations
Aucune.

## Configuration / données
Aucune donnée DEV modifiée : pas de prix, catalogue, dialogue, PNJ, solde ni progression. Seules deux actions agent de lecture ont été mises en file.

## Tests automatiques
- Test temporaire `TmpProposal18Test` (`./gradlew :test --tests …`, `RPGQUEST_TEST_MAX_HEAP=768m`) : vert, puis supprimé.
- Aucun code modifié. La PR ne touche que de la documentation et ne déclenche donc aucune CI.

## Tests manuels à effectuer
**PENDING MANUAL VALIDATION** — TC-345, après accord puis application de la préparation. Le parcours est dans le commentaire #18 du 20/10. Aucun test Minecraft n'a été fait par Claude.

## Résultat attendu
Après application :
- `artisan_demo` affiche ses 3 recettes ;
- Lucien propose « Ouvrir l'atelier » ;
- chaque recette consomme et débite exactement ce qui est décrit ;
- les échecs (ingrédients, solde, inventaire plein) ne prennent rien.

## Reset / retour à l'état initial
Rien à annuler aujourd'hui. Après application : désactiver les 3 prix `demo_recette_*` et retirer le choix de Lucien.

## Déploiement VeryGames
### À transférer
Rien. Après accord :
- 3 prix créés via PlugAdmin → Prix et coûts (le premier enregistrement crée `prices.yml`) ;
- `dialogues/lucien` publié via PlugAdmin. Pas de JAR, pas de FTP.
### Ne PAS transférer/altérer
Les autres catalogues, les prix existants (aucun aujourd'hui), les soldes, les progressions, et les autres contenus en attente du propriétaire.
### Redémarrage requis
Non : les prix et les dialogues se rechargent à l'écriture.
### Migration automatique
Aucune.

## Rollback
- Prix : désactivation (le moteur de prix restaure à l'octet près si le serveur refuse).
- Dialogue : republier le squelette actuel (sauvegarde du moteur de publication).

## Logs / diagnostic
`control-panel.db`, `agent_action.created_by = 'claude-18'`.

## Documentation mise à jour
Registre, TC-345, ECONOMY.md, index des rapports, commentaire #18.

## Limitations / travail restant
- Accord du propriétaire (Lucien / Stan / autres valeurs).
- Application.
- TC-345 en jeu.
- Décisions toujours ouvertes : lien avec `recipes/*.yml`, conditions de disponibilité, vraies valeurs.

## Prochaine étape suggérée
Sur « OK Lucien » :
1. créer les 3 prix via l'action `price.save` (formulaire PlugAdmin) ;
2. modifier `lucien` dans l'éditeur de dialogues et le publier ;
3. vérifier `merchant.catalog.status` : `unavailableRefs` vide, `usages` = `rpgquest:lucien`.
