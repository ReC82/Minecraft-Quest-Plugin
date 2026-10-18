# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-18
* Heure : 18:00
* Sujet : #541 (EPIC #540) — audit technique et maquettes UX de la bibliothèque de capacités de mobs et de la page « Capacités » PlugAdmin
* Statut : DONE pour l'audit et les maquettes ; **en attente de la décision du propriétaire** avant #542 (WAITING_OWNER)
* Branche Git : docs/541-audit-capacites (PR vers chore/257-ci-github-actions, documentation seule)
* Commit actuel si disponible : base `303231d` (chore/257-ci-github-actions)
* Début de la tâche : 2026-10-18 17:52:28
* Fin de la tâche : 2026-10-18 18:00:46
* Durée totale : 00:08:18

## Demande
Ticket #541 seulement : audit de l'existant (capacités, déclenchement, paramètres, PDC, tâches, nettoyage), proposition
d'architecture extensible (registre, plusieurs capacités par profil, déclencheurs, probabilités, délais, état « en
combat », protections, plafonds, nettoyage, compatibilité des profils sans réécriture), maquettes desktop et mobile de
la page Capacités et des cartes dans le profil (exemple Zombie des Éboulements + Enragé + Renforts), questions à
trancher, découpage #542→#553. Aucun code moteur, aucune capacité, aucun déploiement, aucune mise en file.

## Analyse
- Lu entièrement : #540, #541, #542 à #553, #170, #230, #173 (et leurs commentaires).
- **Le moteur a 5 capacités, pas 2** : STRONGER_EXPLOSION, EXPLOSIVE_ON_ATTACK, SPLIT_ON_HIT (anciennes) et ENRAGED,
  SUMMON_ON_DAMAGE (#173). Seules les deux dernières sont éditables dans le formulaire PlugAdmin.
- Ajouter une capacité touche aujourd'hui 8 endroits du plugin et 2 à 4 du panel (formulaire codé à la main).
- Écarts relevés (aucun corrigé, consignés pour #542) : doublons de type acceptés avec un comportement différent
  selon le type ; ordre des capacités modifié à l'enregistrement depuis le panel (sans perte) ; aucune notion commune
  de chance / délai / activation / « en combat » ; délai des renforts gardé en mémoire sans purge ; renforts et
  copies apparus sans consulter Hub / claims / zones ; Explosif au contact ne filtre ni PNJ ni créatif.
- **#230 n'est pas implémenté** (ni registre, ni politique de mutation, ni doc d'ajout) : son contrat générique est
  le socle de #542 ; ses effets de terrain contredisent la règle #540 et sont proposés pour plus tard.
- Faisabilité du gravier (Paper 1.21.11, API publique) : saut par vitesse, atterrissage par suivi borné,
  `FallingBlock` sans pose ni objet (`setCancelDrop`, `EntityChangeBlockEvent`), dégâts réglables, immunité du lanceur
  par PDC — méthodes vérifiées dans le JAR `paper-api-1.21.11` du projet (`javap`).

## Travail effectué
- Étude complète : `docs/design/541-capacites/ETUDE.md` (état des lieux, architecture, faisabilité, format YAML en
  3 options, décisions Q1–Q11, découpage).
- 4 maquettes HTML statiques avec la vraie coquille et les styles de PlugAdmin : catalogue, fiche « Saut et pluie de
  gravier », profil court avec 3 cartes de capacités (une en cours de réglage, une désactivée qui garde ses valeurs),
  écran « Ajouter une capacité ».
- 8 captures Playwright (Chromium headless) à 1280 et 390 px : aucun débordement horizontal, aucune erreur console.
- Registre `docs/SUIVI_VALIDATIONS_DEV.md` : ligne 6c (#541, décision propriétaire) et dépendance 10.

## Fichiers créés
- docs/design/541-capacites/ETUDE.md
- docs/design/541-capacites/{catalogue,fiche,profil,ajout}.html, maquette.css
- docs/claude-reports/assets/541-capacites/{catalogue,fiche,profil,ajout}-{1280,390}.png
- ce rapport

## Fichiers modifiés
- docs/SUIVI_VALIDATIONS_DEV.md
- docs/claude-reports/README.md (index)

## Base de données / migrations
Aucune.

## Configuration / données
Aucune. Rien lu ni écrit sur DEV.

## Tests automatiques
Aucun : aucun code modifié (PR documentation, sans CI selon le filtre `paths`). Rendu des maquettes vérifié par
Playwright (largeur de page = largeur d'écran aux deux tailles, 0 erreur console).

## Tests manuels à effectuer
Aucun test en jeu. Revue propriétaire : regarder les captures (commencer par `profil-1280.png`) ou ouvrir les HTML
depuis le dépôt, puis répondre à Q1–Q11 sur #541.

## Résultat attendu
Le propriétaire valide ou corrige la présentation et l'architecture ; #542 démarre ensuite avec ces décisions.

## Reset / retour à l'état initial
Rien à remettre.

## Déploiement VeryGames
### À transférer
Rien.
### Ne PAS transférer/altérer
Tout (aucun déploiement dans ce lot).
### Redémarrage requis
Non.
### Migration automatique
Aucune.

## Rollback
Sans objet (documentation seule).

## Logs / diagnostic
Néant.

## Documentation mise à jour
Étude et maquettes (nouvelles), registre de validation, index des rapports. Bible / current_state non modifiés :
aucun comportement n'a changé.

## Limitations / travail restant
- Les décisions Q1–Q11 sont des propositions, pas des règles adoptées.
- Le prototype de faisabilité du gravier reste à faire dans #544 (dégâts réels, pentes, plafonds bas).
- #170 et #230 devraient être mis à jour selon le découpage proposé une fois validé (rien modifié dans leur corps).

## Prochaine étape suggérée
Réponse du propriétaire sur #541 → #542 (socle registre + 5 types existants + compatibilité stricte). Ne pas mettre
#542+ en file avant.
