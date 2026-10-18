# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-18
* Heure : 18:02
* Sujet : #331 / #228 — lot 1 : audit mondes et portails dans PlugAdmin, maquettes, découpage en tickets
* Statut : DONE (décision propriétaire attendue)
* Branche Git : `docs/331-audit-mondes-portails` (depuis `chore/257-ci-github-actions` @ `06bb715`)
* Commit actuel si disponible : voir la PR de documentation
* Début de la tâche : 2026-10-18 18:02:19
* Fin de la tâche : 2026-10-18 18:13:10
* Durée totale : 00:10:51

## Demande
Demande du propriétaire du 18/10 (session interactive, transmise par le superviseur) : pouvoir créer des
mondes et gérer intégralement les portails depuis PlugAdmin, sans YAML ni commande, en réutilisant
l'outil d'emplacement #213, la Bibliothèque/Atelier IA (#94/#265), le cycle de vie des bâtiments (#234)
et le moteur de téléportation existant. **Lot 1 seulement** : audit + architecture + navigation +
maquettes + découpage en tickets préparés mais non mis en file. Lecture seule sur DEV.

## Analyse
Synthèse (détail complet dans `docs/design/331-mondes-portails/ETUDE.md`) :
- **Mondes** : `WorldService` RPGQuest ne crée que des mondes NORMAL (`/rpgadmin world create`) ;
  aucune action agent `world.*`, aucune page, aucune permission. Sur DEV, `wild` est géré par RPGQuest
  (`worlds.yml`), `claims`/`world_hub`/monde principal par **Multiverse-Core 5.7.3**. L'API Multiverse
  (JAR de DEV, `javap`) couvre création (dimension, type, graine, structures, `generatorSettings`, biome),
  chargement/déchargement/import et réglages par monde ; Paper 1.21.11 expose NORMAL/FLAT/LARGE_BIOMES/
  AMPLIFIED et NORMAL/NETHER/THE_END. Limite majeure : création **synchrone** (gel bref du serveur).
- **Portails** : deux moteurs. Les world-portals (`world-portals/*.yml`, génériques, stratégies
  WORLD_SPAWN/RANDOM_SAFE/CLAIM_HOME, sans condition ni message) portent `hub_to_wild` et
  `hub_to_claims` sur DEV ; les portails de zone (`/rpgadmin portal`, conditions niveau/quête/
  permission/coût/cooldown, points fixes) ne sont pas utilisés sur DEV. Les gardes Wild (avertissement
  Paper Dialog + « ne plus afficher ») et Claims (palier + moyen de retour) sont déclenchés par le nom
  du monde d'arrivée ; textes codés en dur.
- **Emplacements/bâtiments** : outil #213 = un point + une face, sans champ « usage » ; pose/libération/
  remplacement complets (#234) ; `StructureKind.PORTAL` existe dans les plans IA mais n'est pas conservé
  dans la Bibliothèque ; aucun contrat d'ancrages ; pas d'import .schem dans le panel (#245).
- Proposition : lien porté par le portail (`site:`), sans migration ; moteur unique = world-portals
  étendus ; Multiverse comme gestionnaire des nouveaux mondes (adaptateur isolé, softdepend).

## Travail effectué
- Lecture intégrale de la demande et des tickets #331, #228, #213, #94, #265, #234, #137, #532.
- Inventaire du code (trois explorations en lecture seule) : portails/téléportation, mondes,
  bâtiments/emplacements/Atelier, navigation et composants PlugAdmin.
- Relecture **en lecture seule** de DEV par FTP : liste des plugins, `Multiverse-Core/worlds.yml` et
  `config.yml`, `RPGQuest/worlds.yml`, `world-portals/hub_to_wild.yml`, `hub_to_claims.yml`, `spawn.yml`,
  clés de mondes de `config.yml`, contenu de `portals/`, `destinations/`, `buildings/`. Aucun envoi.
- Vérification des API réellement disponibles : `javap` sur `paper-api-1.21.11-R0.1-SNAPSHOT` et sur le
  JAR `multiverse-core-5.7.3.jar` de DEV (copie locale isolée, non exécutée).
- Étude `ETUDE.md` : existant, reste à faire, composants réutilisables, 10 limites techniques,
  navigation, modèle de liaison emplacement ↔ construction ↔ téléportation, découpage, décisions Q1–Q7.
- 6 maquettes HTML statiques (styles réels de PlugAdmin) et 12 captures Playwright Chromium 1280/390 :
  largeur de page = largeur d'écran, 0 erreur console.
- 11 sous-tickets GitHub créés et reliés : #560 (W1 mondes lecture), #561 (W2 réglages), #562 (W3
  création), #563 (W4 charger/décharger/importer), #564 (P1 portails lecture), #565 (P2 brouillon +
  emplacement + activation), #566 (P3 destinations), #567 (P4 conditions + simulation), #568 (P5
  construction/ancrages), #569 (P6 messages/avertissements), #570 (P7 recette). #137 et #245 réutilisés.
  **Aucun mis en file.**
- Commentaires de synthèse sur #331 et #228.

## Fichiers créés
- `docs/design/331-mondes-portails/ETUDE.md`
- `docs/design/331-mondes-portails/{mondes,monde-fiche,monde-creer,portails,portail-fiche,portail-creer}.html`
- `docs/design/331-mondes-portails/maquette.css`
- `docs/claude-reports/assets/331-mondes-portails/*.png` (12 captures)
- ce rapport

## Fichiers modifiés
- `docs/claude-reports/README.md` (index)
- `docs/SUIVI_VALIDATIONS_DEV.md` (ligne 6d + décision n° 11 ; `origin/main` ne contenait rien de plus
  récent à reporter)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune modification. DEV relu seulement.

## Tests automatiques
Aucun code modifié : pas de Gradle (PR documentation seule, sans CI d'après le filtre `paths`).
Contrôle des maquettes par Playwright (12 pages, aucun débordement horizontal, aucune erreur console).

## Tests manuels à effectuer
Aucun test en jeu (rien de livré en jeu). Revue propriétaire : regarder les captures
(`portail-fiche-1280.png`, `mondes-1280.png`, `monde-creer-390.png`…) ou ouvrir les HTML depuis le dépôt,
puis répondre sur #331 aux questions Q1–Q7.

## Résultat attendu
Le propriétaire valide (ou corrige) la navigation, le modèle de liaison et l'ordre des tickets ; ensuite
seulement #560 peut être mis en file.

## Reset / retour à l'état initial
Rien à annuler côté serveur. Les sous-tickets #560–#570 peuvent être fermés s'ils sont refusés.

## Déploiement VeryGames
### À transférer
Rien.
### Ne PAS transférer/altérer
`world-portals/`, `worlds.yml` (RPGQuest et Multiverse), mondes, bâtiments : intacts.
### Redémarrage requis
Non.
### Migration automatique
Non.

## Rollback
Sans objet (documentation seule).

## Logs / diagnostic
Copies locales des fichiers DEV relus et du JAR Multiverse : `/tmp/a331/` (temporaires, non commitées).

## Documentation mise à jour
Étude et maquettes ; registre de validation. `docs-site/worlds.html` et `docs/deployment/VERYGAMES.md`
(« softdepend Citizens uniquement ») sont signalés obsolètes dans #570, non corrigés ici.

## Limitations / travail restant
- Les réglages et la création de monde plat (couches/biome via `generatorSettings`) doivent être
  prouvés sur DEV avec un monde de test — uniquement après accord explicite du propriétaire.
- Les sous-tickets référencent W1…P7 entre eux ; la correspondance numéros ↔ codes est dans l'étude et
  dans les commentaires #331/#228.

## Prochaine étape suggérée
Réponse du propriétaire aux questions Q1–Q7, puis mise en file de #560 (W1, lecture seule des mondes)
et #564 (P1, lecture seule des portails).
