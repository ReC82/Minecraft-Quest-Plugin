# Changelog serveur — actions VeryGames

Historique de tout changement nécessitant une action manuelle sur le
serveur VeryGames (remplacement de JAR, configuration, données, monde,
commande...). Complète [VERYGAMES.md](VERYGAMES.md) (procédures génériques)
avec un journal daté des actions réellement effectuées ou à effectuer.

**Toute modification qui a un impact sur le serveur de production doit
ajouter une entrée ici, dans la même branche/PR** — voir la règle dans
[PROJECT_RULES.md](../../PROJECT_RULES.md#déploiement-verygames).

## Modèle d'entrée

```md
## YYYY-MM-DD - Nom du changement

### Changement

Résumé fonctionnel.

### Action serveur

Indiquer précisément :
- remplacer uniquement le JAR RPGQuest ;
- modifier/copier un fichier de configuration ;
- migrer des données ;
- ajouter/modifier un monde ;
- ajouter/mettre à jour un plugin externe ;
- exécuter une commande ;
- ou "aucune action manuelle autre que remplacement du JAR".

### Sauvegarde préalable

Ce qu'il faut sauvegarder.

### Déploiement

Étapes exactes.

### Validation

Tests/logs/commandes à vérifier.

### Rollback

Procédure de retour arrière.
```

---

## 2026-08-15 - HubWorldRulesService (règles du monde Hub)

### Changement

Ajout du service `HubWorldRulesService` (+ `HubWorldProtectionListener`) :
applique automatiquement, par code, jour permanent et météo permanente sur
le monde Hub (nom lu depuis `hub.world` en config, `world_hub` par défaut
si aucune section `hub:` n'existe), ainsi que les protections associées
(dégâts joueurs annulés, PvP bloqué, casse/pose de bloc bloquée sauf
bypass admin `rpgquest.admin.world`, explosions sans destruction de bloc,
spawn naturel de mobs hostiles bloqué, claims interdits). Réappliqué au
démarrage et à chaque (re)chargement tardif du monde (ex. par
Multiverse-Core après RPGQuest).

### Action serveur

Remplacement du JAR RPGQuest uniquement — aucune action manuelle autre que
remplacement du JAR.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar`.
- `plugins/RPGQuest/data.db` (les règles ne touchent à aucune donnée
  persistante, mais suivre la procédure standard de
  [mise à jour du seul JAR](VERYGAMES.md#mise-à-jour-du-seul-jar-rpgquest-scénario-2)).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP).
   Ne toucher à aucun autre fichier (`config.yml` n'a pas besoin d'être
   modifié : `hub.world` est déjà `world_hub` par défaut si la section
   `hub:` est absente).
4. Redémarrage complet requis (un `/rpgquest reload` ne suffit pas : les
   règles sont appliquées au démarrage du service et au chargement du
   monde).

### Validation

- Log attendu au démarrage :
  `Règles du monde Hub appliquées : world_hub (jour et météo permanents).`
- Avec un joueur **non-OP** dans `world_hub` :
  - jour fixe (l'heure ne progresse pas) ;
  - météo claire en permanence ;
  - aucun dégât subi (chute, faim, feu, mob, PvP...) ;
  - casse/pose de bloc impossible.
- Avec un joueur **OP** (`rpgquest.admin.world`) : construction (casse/pose
  de bloc) toujours possible dans `world_hub` (bypass conservé).
- Aucun mob hostile n'apparaît par spawn naturel dans `world_hub`.
- `/claim create` refusé dans `world_hub`.

### Rollback

1. Arrêter le serveur.
2. Remettre en place l'ancien JAR RPGQuest sauvegardé.
3. Redémarrer et vérifier `/rpgquest version` (ancienne version affichée).

Aucune donnée n'a été migrée par ce changement : le rollback ne touche que
le JAR.

---

## 2026-09-05 - Preview / dry-run du reset joueur admin (issue #8)

### Changement

Ajout de la sous-commande `/rpgadmin player resetnew <joueur> preview` :
dry-run qui liste, catégorie par catégorie, ce qu'un reset réel effacerait
pour le joueur ciblé, **sans effectuer aucune écriture** (aucune
suppression en base, aucun marqueur `__pending_new_player_reset__`, aucune
invalidation de cache, aucun objet retiré de l'inventaire). En ligne ou
hors ligne. Le comportement de `resetnew <joueur> confirm` est **inchangé**
(seule une factorisation interne de `PlayerResetService.removeRpgItems` en
`countOrRemoveRpgItems`). Nouveaux points de lecture seule :
`PlayerVariableRepository#findAllForPlayer`, `WaystoneService#discoveryCount`,
`StoryService#progressRecords`. Voir
[docs/ADMIN_PLAYER_RESET.md](../ADMIN_PLAYER_RESET.md).

### Action serveur

Remplacement du JAR RPGQuest uniquement — aucune action manuelle autre que
remplacement du JAR.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar`.
- `plugins/RPGQuest/data.db` par précaution (aucune migration, aucun schéma
  modifié — suivre la procédure standard de
  [mise à jour du seul JAR](VERYGAMES.md#mise-à-jour-du-seul-jar-rpgquest-scénario-2)).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP).
   Ne toucher à aucun autre fichier (aucune nouvelle clé de config).
4. Redémarrer.

### Validation

- `/rpgadmin player resetnew <joueur> preview` affiche l'en-tête, la ligne
  `Dry-run : aucune donnée n'a été modifiée.`, une ligne par catégorie,
  puis le rappel `Pour exécuter réellement : … confirm`.
- Après un `preview`, `/rpgadmin player resetnew <joueur> confirm` (sur un
  joueur de test) montre toujours les mêmes données qu'avant le preview
  (le preview n'a rien altéré).
- Tab-complétion : `resetnew <joueur> <TAB>` propose `confirm` et `preview`.

### Rollback

1. Arrêter le serveur.
2. Remettre en place l'ancien JAR RPGQuest sauvegardé.
3. Redémarrer et vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR.

---

## 2026-09-05 - Guide « centre d'aide » + journal de quêtes en GUI (issue #11)

### Changement

- `dialogues/guide.yml` réécrit en **centre d'aide structuré** (nœud
  `help_menu` + sujets ; orientations textuelles). Livré dans le jar,
  **copie manuelle** par l'admin (seul `guard.yml` est copié
  automatiquement — inchangé).
- Nouveau dossier `hub-guides/` (registre `hub.HubGuideRegistry`) :
  structure multi-Hub, exemple `hub_depart.yml` auto-copié au premier
  démarrage. Nouveau diagnostic admin `/rpgadmin guide list|info <hub>`
  (lecture seule).
- Le journal de quêtes (`rpgquest:journal_quetes`, remis par le Libraire)
  ouvre désormais la **GUI `/quests`** au clic droit (avant : résumé chat).
  La GUI passe de **3 à 2 onglets** : « Quêtes en cours » / « Quêtes
  terminées ». L'onglet catalogue « Disponibles » est **supprimé** — le
  journal ne liste plus que les quêtes déjà acceptées.
- `dialogues/libraire.yml` : texte mis à jour (nœud `journal_lost` ajouté).
  `items/journal_quetes.yml` : lore mis à jour.
- Service `QuestJournalBookService` (résumé chat) supprimé.

### Action serveur

Remplacement du JAR RPGQuest uniquement.

**Fichiers de configuration existants** (`dialogues/guide.yml`,
`dialogues/libraire.yml`) sur un serveur déjà en service : ils ne sont
**pas** réécrits automatiquement (seuls les fichiers absents sont
recréés). Pour bénéficier du nouveau Guide « centre d'aide » et du nouveau
texte du Libraire, **remplacer manuellement** `plugins/RPGQuest/dialogues/guide.yml`
et `plugins/RPGQuest/dialogues/libraire.yml` par les versions du jar (ou du
dépôt : `src/main/resources/dialogues/`). Le dossier `hub-guides/` et son
exemple `hub_depart.yml` sont créés automatiquement au premier démarrage
sur la nouvelle version.

Aucune migration de base, aucune nouvelle clé `config.yml`
(`dialogue.allowed-commands` contient déjà `customitem` et `claim`).

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<version>.jar`.
- `plugins/RPGQuest/dialogues/guide.yml` et `libraire.yml` (avant
  remplacement manuel).
- `plugins/RPGQuest/data.db` par précaution (procédure standard « mise à
  jour du seul JAR »).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer `plugins/RPGQuest-*.jar`.
4. Remplacer manuellement `plugins/RPGQuest/dialogues/guide.yml` et
   `plugins/RPGQuest/dialogues/libraire.yml` par les versions du jar.
5. Redémarrer.

### Validation

- Log au démarrage : `Chargement des Guides de Hub : 1 chargé(s), 0 erreur(s).`
- `plugins/RPGQuest/hub-guides/hub_depart.yml` a été créé.
- `/rpgadmin guide list` affiche `hub_depart` ; `/rpgadmin guide info hub_depart`
  affiche accueil, spécialité et orientations.
- Parler au Guide → « Comment fonctionne le jeu ? » → menu d'aide, chaque
  sujet s'affiche et ramène au menu.
- Parler au Libraire sans journal → recevoir exactement un journal ; lui
  reparler → l'option a disparu (pas de second exemplaire).
- Clic droit sur le journal → GUI à deux onglets « en cours » / « terminées ».
- Accepter une quête via un PNJ → elle apparaît en « en cours » ; la
  terminer → elle passe en « terminées ». Une quête jamais acceptée
  n'apparaît nulle part.
- Aucun item de la GUI n'est récupérable (tout clic/drag annulé).

### Rollback

1. Arrêter le serveur.
2. Remettre l'ancien JAR **et** les anciens `dialogues/guide.yml` /
   `libraire.yml` sauvegardés.
3. Le dossier `hub-guides/` peut rester : il est simplement ignoré par
   l'ancienne version.
4. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR et deux fichiers de
dialogue.

---

## 2026-09-05 - Permissions granulaires par rôle / monde / action (issue #27)

### Changement

Découpage des permissions d'administration du monde et de construction.
Avant : `op` (ou `rpgquest.admin.world`) était nécessaire pour construire
dans le Hub, ce qui donnait aussi toutes les commandes `/rpgadmin` et le
contournement des claims joueurs. Après :

- construction par monde/Hub via des nœuds dédiés
  (`rpgquest.build.hub.<id>`, `rpgquest.build.hub.*`, `rpgquest.build.wild`,
  `rpgquest.build.world.<clé>`, `rpgquest.build.*`, `rpgquest.build.zone`) ;
- `/rpgadmin` : un nœud par sous-commande
  (`rpgquest.admin.flatten|zone|portal|mob|npc|spawn|worlds|waystone|story|player|guide`) ;
- claims : `rpgquest.claim.bypass` (contournement) et `rpgquest.claim.admin`
  (`/claim admin`) séparés du build ;
- **aucune** permission de build ne contourne un claim joueur.

Tous les nouveaux nœuds `/rpgadmin` et build sont **filles de
`rpgquest.admin.world`** (`default: op`) : un OP, ou un rôle serveur à qui
`rpgquest.admin.world` était déjà accordé, garde exactement le même accès —
**aucune régression, aucune action de migration de permissions requise**.

Nouvelle section `config.yml` → `permissions.build-areas` (mapping monde →
zone de build), **optionnelle** : vide par défaut, valeurs par défaut
déduites de `hub.world` / `travel.wild-world` / `claims.world`.

Documentation : nouveau `docs/PERMISSIONS.md`.

### Action serveur

- Remplacer uniquement le JAR RPGQuest.
- **Optionnel** : ajouter une section `permissions.build-areas` dans
  `plugins/RPGQuest/config.yml` uniquement si des Hubs supplémentaires
  existent (sinon ne rien changer — les défauts suffisent). Le `config.yml`
  embarqué du jar contient la section commentée en exemple ; un `config.yml`
  existant sans cette section reste valide.
- Créer les groupes/rôles voulus dans le gestionnaire de permissions
  (LuckPerms recommandé, non obligatoire) d'après `docs/PERMISSIONS.md` §4.
  Tant que ce n'est pas fait, seul un OP peut construire/administrer — état
  identique à aujourd'hui.
- Aucune migration de base de données, aucun nouveau monde, aucun plugin
  externe, aucune commande manuelle.

### Sauvegarde préalable

- `plugins/RPGQuest-*.jar` (JAR actuellement déployé).
- `plugins/RPGQuest/config.yml`.
- `plugins/RPGQuest/data.db` par précaution (procédure standard « mise à
  jour du seul JAR »).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer `plugins/RPGQuest-*.jar`.
4. (Optionnel) éditer `config.yml` → `permissions.build-areas` si Hubs
   multiples.
5. Redémarrer.
6. Créer/ajuster les groupes de permissions dans LuckPerms si souhaité.

### Validation

- `/rpgquest version` répond ; log de démarrage sans erreur de config.
- OP : `/rpgadmin flatten`, `/rpgadmin zone list`, casse/pose dans
  `world_hub`, casse dans un claim de `world_claim` → tout fonctionne
  comme avant.
- Joueur non-OP avec `rpgquest.build.hub.0` seul : peut casser/poser dans
  `world_hub`, **ne peut pas** casser dans `world_claim` (ni dans un claim,
  ni hors claim), ne peut pas lancer `/rpgadmin flatten`
  (« Permission manquante : rpgquest.admin.flatten »).
- Joueur non-OP avec `rpgquest.build.wild` : peut construire dans `wild`,
  **ne peut pas** contourner un claim.
- Joueur non-OP avec `rpgquest.admin.npc` : `/rpgadmin npc info` passe la
  vérification de permission ; `/rpgadmin flatten` est refusé.

### Rollback

1. Arrêter le serveur.
2. Remettre l'ancien JAR sauvegardé (et l'ancien `config.yml` si modifié).
3. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR (et éventuellement
`config.yml`). Les nœuds de permission ajoutés dans le gestionnaire externe
sont simplement ignorés par l'ancienne version.
