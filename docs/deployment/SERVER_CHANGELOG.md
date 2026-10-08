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

## 2026-09-06 - Parcours Claims cohérent : accès au monde des claims + retour Hub (issues #21/#22/#23)

### Changement

Rend le parcours joueur autour des claims cohérent de bout en bout, y
compris après `/rpgadmin player resetnew <joueur> confirm`.

- **Accès au monde `claims` réservé au déblocage réel** : nouveau
  `claim.ClaimWorldAccessGuard` (un `travel.WorldPortalEntryGuard`, composé
  avec l'avertissement d'entrée dans le Wild via
  `travel.CompositeWorldPortalEntryGuard`). Un portail simple vers
  `claims.world` ne laisse passer que les joueurs qui ont
  `CLAIM_TIER_1 == "true"` (même vérité que `ClaimService.create` pour un
  premier claim) **ou** possèdent déjà un claim. Sinon : aucune
  téléportation, message d'orientation vers Jo / le Guide. Seul le bypass
  `rpgquest.admin.world` passe outre.
- **Aucun joueur coincé, retour Hub sans commande** : nouveau
  `claim.ClaimWorldSafetyListener`. À l'arrivée dans le monde des claims
  (changement de monde ou connexion), un joueur éligible sans
  `rpgquest:pierre_retour` en reçoit une automatiquement (voyage
  claims → Hub, clic droit, jamais consommée) ; un joueur non éligible qui
  s'y retrouve autrement (`/tp`, reconnexion, présence antérieure) est
  renvoyé au Hub (spawn du village, sinon spawn du monde Hub).
- **Dialogues alignés sur la logique réelle** :
  `dialogues/guide.yml` (`help_claims`, `help_qui`) énonce le prérequis —
  terminer l'histoire principale (`crystal_hunt`, rendue au Garde) *puis*
  voir Jo. `dialogues/jo.yml` adapte son texte aux 3 états : non débloqué
  (« Comment obtenir mon premier terrain ? »), débloqué sans claim (remet
  l'Acte), claim existant (retour / limites / Pierre de retour).
- **Moteur de dialogue** : nouveau modificateur `negate: true` sur
  n'importe quelle condition (`dialogue.model.NegatedCondition`).

### Action serveur

Remplacer le JAR RPGQuest **et** trois fichiers de dialogue :
`plugins/RPGQuest/dialogues/guide.yml`, `plugins/RPGQuest/dialogues/jo.yml`.
(`guide.yml` était déjà à remplacer pour l'issue #11.) Aucune migration de
données, aucun changement de `config.yml` (`dialogue.allowed-commands`
contient déjà `customitem` et `claim`).

### Sauvegarde préalable

- JAR RPGQuest en ligne → backup daté.
- `plugins/RPGQuest/dialogues/guide.yml` et `plugins/RPGQuest/dialogues/jo.yml`
  → backup daté avant remplacement.
- `plugins/RPGQuest/data.db` par précaution (procédure « mise à jour du
  seul JAR »). **Ne pas** toucher `data.db`, les mondes, Citizens, les
  autres plugins, ni les autres fichiers de `plugins/RPGQuest/`.

### Déploiement

1. Compiler (`./gradlew test` puis `./gradlew build`).
2. Arrêter le serveur (ou déployer à chaud puis `reload confirm` — dialogues
   rechargés au démarrage du plugin).
3. Remplacer `plugins/RPGQuest-*.jar`.
4. Remplacer `plugins/RPGQuest/dialogues/guide.yml` et
   `plugins/RPGQuest/dialogues/jo.yml` par les versions du jar.
5. Redémarrer.

### Validation

- Nouveau joueur / après `resetnew` : parler à Jo → seule l'option
  « Comment obtenir mon premier terrain ? » ; le portail Hub → claims
  refuse l'entrée avec un message, aucune téléportation.
- Terminer l'histoire principale (rendre `crystal_hunt` au Garde) : Jo
  propose « Je viens réclamer mon acte de propriété » ; le portail laisse
  passer et une Pierre de retour est reçue à l'arrivée.
- Dans le monde des claims, clic droit sur la Pierre de retour → retour au
  village, sans commande.
- `resetnew` du même joueur → le portail refuse de nouveau l'entrée.
- Portails vers le Wild / autres mondes : comportement inchangé.

### Rollback

1. Arrêter le serveur.
2. Remettre l'ancien JAR **et** les anciens `dialogues/guide.yml` /
   `dialogues/jo.yml` sauvegardés.
3. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée : le rollback ne touche que le JAR et deux fichiers de
dialogue. `CLAIM_TIER_1` et les claims existants ne sont jamais modifiés
par ce changement.

---

## 2026-09-06 - Audit parcours principal : Garde manquant + `guard.yml` périmé, journalisation d'accès claims

Voir le rapport `docs/claude-reports/2026-09-06_1219_audit-parcours-principal-garde-crystal-hunt.md`.

### Constat (audit du contenu réel DEV)

- **Aucun PNJ Citizens lié `guard`** sur DEV (`npc_citizens_bindings` : `guide`,
  `libraire`, `help`, `jeff`, `junior`, `jo` — pas de `guard`). Conséquence :
  `first_steps` **et** `crystal_hunt` sont indémarrables → `CLAIM_TIER_1`
  jamais accordé → premier claim impossible. Preuve base : 0 progression
  `first_steps`/`crystal_hunt`, 0 `CLAIM_TIER_1`, 0 claim, `story_progress`
  vide.
- **`plugins/RPGQuest/dialogues/guard.yml` périmé** : ne contient pas la
  branche `crystal_hunt` (« J'ai entendu dire… ») ni le nœud
  `crystal_hunt_accepted`. Même avec un PNJ `guard`, `crystal_hunt` resterait
  indémarrable.
- **#21/#22** : le code refuse bien l'entrée / renvoie au Hub pour un compte
  **non opéré** ; les symptômes rapportés (« j'entre sans déblocage »,
  « je reste coincé sans Pierre de retour ») correspondent au **bypass
  `rpgquest.admin.world`** (défaut `op`, aucun plugin de permissions installé)
  déclenché par un test depuis un compte OP.

### Changement

- **Code** : `ClaimWorldAccessGuard` et `ClaimWorldSafetyListener` journalisent
  désormais chaque décision (`INFO`, préfixes `[claims-access]` /
  `[claims-safety]`) — bypass OP, refus, autorisation, renvoi Hub, Pierre de
  retour. Aucun changement de comportement.

### Action serveur

Remplacer le JAR RPGQuest **et** :

- `plugins/RPGQuest/dialogues/guard.yml` — **obligatoire** (débloque
  `crystal_hunt`).
- `plugins/RPGQuest/quests/first_steps.yml` — **recommandé** (réaligne une
  édition faite à la main sur le serveur ; à omettre pour la conserver).

Puis, **en jeu** (admin, après redémarrage) : créer le PNJ Garde et le lier —
`/npc create Garde --type player` puis, en le visant, `/rpgadmin npc tag guard`
(ajoute une ligne à `npc_citizens_bindings`). Détail :
`docs/NPC_DIALOGUES_QUESTS_GUIDE.md` §1b.

### Sauvegarde préalable

- JAR RPGQuest en ligne → backup daté (script).
- `dialogues/guard.yml` (+ `quests/first_steps.yml` s'il est remplacé) →
  backup daté avant remplacement (script).
- **Ne pas** toucher `data.db`, `config.yml`, `messages.yml`, `spawn.yml`,
  `worlds.yml`, les mondes, `plugins/Citizens/**`, `plugins/Multiverse-Core/**`,
  les autres plugins.

### Redémarrage requis

Oui (nouveau JAR + rechargement des dialogues au démarrage du plugin).

### Exécution

- **2026-09-06 13:04:54Z** : `scripts/deploy-verygames.sh -y --also
  src/main/resources/dialogues/guard.yml:RPGQuest/dialogues/guard.yml`.
  - JAR : ancien `3806243c…8946` (1 117 023 o) → nouveau
    `89b226b8fee9773a46ef90120244be8163ca275a96359e53c33dd027bc60cb58`
    (1 117 682 o). Backup :
    `verygames-backups/rpgquest-20260906T130454Z-predeploy.jar`.
  - `RPGQuest/dialogues/guard.yml` : 1108 o (version périmée, sans
    `crystal_hunt`) → 1850 o (== dépôt, diff vérifié après upload). Backup :
    `verygames-backups/extra-20260906T130454Z/RPGQuest/dialogues/guard.yml`.
  - `quests/first_steps.yml` **non transféré** (édition serveur conservée).
  - **Redémarrage serveur + création du PNJ Garde : actions manuelles non
    encore effectuées** au moment de ce déploiement.

### Migration automatique

Aucune. La création du PNJ Garde ajoute une seule ligne à
`npc_citizens_bindings` via `/rpgadmin npc tag`.

### Validation

Avec un compte **non opéré** : portail Hub → claims refusé sans `CLAIM_TIER_1`
(aucune téléportation) ; après `crystal_hunt` rendue au Garde, portail
autorisé + Pierre de retour à l'arrivée ; clic droit Pierre de retour →
retour Hub sans commande ; `/tp` forcé dans `claims` sans droit → renvoi Hub.
Observer les logs `[claims-access]` / `[claims-safety]`.

### Rollback

1. Arrêter le serveur.
2. Restaurer l'ancien JAR **et** l'ancien `dialogues/guard.yml` (+
   `quests/first_steps.yml` s'il a été remplacé) depuis les backups datés
   (`scripts/rollback-verygames.sh --latest` pour le JAR).
3. Redémarrer, vérifier `/rpgquest version`.

Aucune donnée migrée. La liaison PNJ `guard` créée en jeu peut être retirée
avec `/rpgadmin npc untag` (en visant le PNJ) si besoin.

---

## 2026-09-06 - Raccourcis d'administration / test quêtes & stories (issue #36)

Voir le rapport `docs/claude-reports/2026-09-06_2028_admin-test-shortcuts-issue-36.md`
et `docs/ADMIN_TEST_SHORTCUTS.md`.

### Changement

Nouvelles sous-commandes `/rpgadmin` (outils DEV/admin, réutilisent les
services métier, aucune écriture directe en base) :

- `/rpgadmin quest start|complete|reset <joueur> <quest-id> [force]`
- `/rpgadmin story advance|complete <joueur> <storyId>`
- `/rpgadmin player variable get|set <joueur> <clé> [valeur]`

`complete` / `story advance|complete` appliquent les récompenses (dont
`VARIABLE`, ex. `CLAIM_TIER_1`) **une seule fois**. `quest reset` rend la
quête rejouable mais **n'annule pas** les récompenses déjà accordées.

### Nouvelle permission

`rpgquest.admin.debug` (`default: op`) — requise **en plus** de
`rpgquest.admin.world` pour `/rpgadmin player variable set` uniquement.
Livrée avec le JAR (`plugin.yml` embarqué). Ne jamais l'accorder à un
joueur normal.

### Action serveur

Remplacer **uniquement** le JAR RPGQuest. Aucun fichier de contenu, aucune
migration, aucun changement de `config.yml`.

- 2026-09-06 21:28:47Z : `scripts/deploy-verygames.sh -y`.
  - JAR : `89b226b8…cb58` → `bcc3a6ec765c7fb7adeff2a15f2b49637a535fcc89c156184ab16bbd700bccdd`
    (1 136 148 o, vérifié après upload). Backup :
    `verygames-backups/rpgquest-20260906T212847Z-predeploy.jar`.
  - **Redémarrage serveur : action manuelle non encore effectuée.**

### Redémarrage requis

Oui (nouveau JAR + nouvelle permission dans le `plugin.yml` embarqué).

### Rollback

`scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20260906T212847Z-predeploy.jar`),
puis redémarrer et vérifier `/rpgquest version`. Aucune donnée migrée.

---

## 2026-09-07 - Agent sortant PlugAdmin (issue #51)

### Changement

Nouveau composant plugin `com.lodygames.rpgquest.web.agent` (`PlugAdminAgent`) :
RPGQuest ouvre une connexion **HTTPS SORTANTE** vers PlugAdmin
(`https://plugadmin.lodylands.com`) pour publier un heartbeat régulier et
récupérer une file d'actions whitelistées. Aucune écoute entrante ajoutée
côté serveur. Aucun impact gameplay : tout est asynchrone, avec timeouts
courts, backoff et arrêt propre ; si PlugAdmin est injoignable, le serveur
Minecraft continue normalement.

Le heartbeat réutilise `HealthSource` (#37) — aucune logique de health
dupliquée. La seule action ouverte est **non destructive** :
`player.variable.get` (lecture de `player_variables` via le service métier).
Tout autre type d'action est refusé (`REJECTED`).

### Action serveur

1. **Remplacer le JAR RPGQuest** (build de la branche `feat/51-plugadmin-outbound-agent`).
   À ce stade, **l'agent reste INERTE** : aucun fichier de configuration
   agent n'est présent → aucune connexion sortante, aucun changement de
   comportement observable.
2. **Déposer le fichier de configuration agent** (hors Git, contient un
   jeton) :
   `plugins/RPGQuest/plugadmin-agent.properties`
   à partir du modèle `scripts/plugadmin-agent.properties.example`, avec au
   minimum `enabled=true`, `base-url=https://plugadmin.lodylands.com`,
   `agent-id=rpgquest-dev`, `environment=dev`, `token=<jeton>`.
   Transfert : `scripts/deploy-verygames.sh --also
   scripts/plugadmin-agent.properties:RPGQuest/plugadmin-agent.properties`
   (le fichier réel avec le vrai jeton doit être préparé localement, hors
   dépôt) **ou** upload manuel dans le panel FTP VeryGames.
3. Côté PlugAdmin/AWS (déjà fait par cette session) : ajouter
   `RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV=<le même jeton>` dans
   `/etc/plugadmin/plugadmin.env` et déclarer l'agent dans
   `/etc/plugadmin/control-panel.properties`, puis `systemctl restart plugadmin`.
4. **Redémarrer le serveur RPGQuest** (VeryGames) — action manuelle owner
   dans le panel VeryGames (pas d'API/RCON).

### Ne PAS altérer

`data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, autres
plugins. Le fichier agent est le SEUL ajout sous `plugins/RPGQuest/`.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar` (backup automatique
  par `deploy-verygames.sh`).
- `plugins/RPGQuest/data.db` (procédure standard, même si aucune donnée
  n'est touchée).
- Aucun `plugadmin-agent.properties` préexistant à sauvegarder (fichier
  nouveau).

### Déploiement

1. `./gradlew clean build` sur `feat/51-plugadmin-outbound-agent`.
2. `scripts/deploy-verygames.sh -y` (JAR seul) — **agent inerte**.
3. Vérifier au redémarrage : aucune régression, log
   `Agent PlugAdmin désactivé` ou `Agent PlugAdmin inactif`.
4. Déposer `plugins/RPGQuest/plugadmin-agent.properties` (jeton hors Git).
5. Redémarrer le serveur.
6. Vérifier le log `event=plugadmin_probe status=ok …` (connectivité HTTPS
   sortante confirmée) et le dashboard PlugAdmin (« RPGQuest DEV — ONLINE »).

### Validation

- Serveur RPGQuest : démarre normalement ; log
  `Agent PlugAdmin démarré : cible rpgquest-dev …` puis
  `event=plugadmin_probe status=ok`.
- PlugAdmin : `journalctl -u plugadmin` → `event=agent_heartbeat agent=rpgquest-dev …` ;
  dashboard `https://plugadmin.lodylands.com/dashboard` → **ONLINE**, version
  plugin / joueurs / mondes réels.
- Aller-retour d'action : page **Agents** → envoyer `player.variable.get`
  (`CLAIM_TIER_1`) → résultat `SUCCESS` avec la valeur.
- Coupure PlugAdmin (ex. `systemctl stop plugadmin` quelques minutes) : le
  serveur Minecraft n'est pas affecté ; l'agent passe en backoff puis se
  reconnecte au retour.

### Rollback

- **Simple** : mettre `enabled=false` dans
  `plugins/RPGQuest/plugadmin-agent.properties` (ou supprimer le fichier) et
  redémarrer → agent inerte, JAR conservable en l'état.
- **JAR** : `scripts/rollback-verygames.sh --latest` (restaure le backup
  `*-predeploy.jar`), redémarrer, vérifier `/rpgquest version`.
- Côté PlugAdmin : `agents=` (vide) dans `control-panel.properties` +
  `systemctl restart plugadmin`, ou `scripts/plugadmin/rollback.sh app`.
- Aucune migration `data.db` à défaire. Les tables `agent_*` de
  `control-panel.db` sont sans effet sur RPGQuest.

### Exécution réelle — 2026-09-07 ~12:20 UTC

- **RCON VeryGames confirmé fonctionnel** depuis AWS (DEV : `51.68.57.28:7469`).
  Les mentions antérieures « pas de RCON / redémarrage manuel owner » sont
  **obsolètes**. Outils ajoutés au dépôt : `scripts/verygames-rcon.py`,
  `scripts/verygames-restart.sh`.
- Config RCON : `~/.config/rpgquest/verygames.env` (mêmes que FTP), clés
  `RCON_HOST` / `RCON_PORT` / `RCON_PASSWORD`, `chmod 600`. Mot de passe jamais
  affiché / committé.
- `scripts/deploy-verygames.sh -y --allow-no-backup --also
  ~/.config/rpgquest/plugadmin-agent.properties:RPGQuest/plugadmin-agent.properties`
  - `./gradlew test` + `build` : OK.
  - Backup du JAR en ligne :
    `verygames-backups/rpgquest-20260907T121945Z-predeploy.jar`
    (`bcc3a6ec…` — c'était l'ancien JAR de l'issue #36, jamais redémarré).
  - JAR déployé : `rpgquest-0.1.0-SNAPSHOT.jar`
    `5e9d9a5e02ae9b515f1bcd06147b3bdb0794a59efc97a6159d687ae91e39a3e4`
    (1 181 666 o, branche `feat/51-plugadmin-outbound-agent`, commit `858e7d4`).
  - Fichier agent déployé : `plugins/RPGQuest/plugadmin-agent.properties`
    (283 o, hors Git, `enabled=true`, jeton partagé avec AWS).
  - Aucun autre fichier touché.
- `scripts/verygames-restart.sh` : `save-all` → `stop` (RCON) → serveur OFFLINE
  → relance automatique VeryGames → **ONLINE** en < 1 min.
- **Validation live** : heartbeats réels reçus par PlugAdmin
  (`event=agent_heartbeat agent=rpgquest-dev version=0.1.0-SNAPSHOT`,
  mondes hub/claims/wild chargés) ; action `player.variable.get`
  (Rondoudou9000 / CLAIM_TIER_1) → `SUCCESS` (« variable absente ») ; type
  inconnu → `REJECTED` ; idempotence (re-livraison) OK ; arrêt de PlugAdmin
  ~45 s → Minecraft non affecté, agent reconnecté automatiquement au retour.
- Côté AWS : `/etc/plugadmin/control-panel.properties` (bloc `agents=`) et
  `/etc/plugadmin/plugadmin.env` (`RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV`, généré,
  jamais affiché) ; PlugAdmin redéployé (`scripts/plugadmin/deploy.sh`) ;
  autres sites (`dig.lodygames.com`, `lodylands.com`) non impactés.
- Rapport : `docs/claude-reports/2026-09-07_1127_plugadmin-outbound-agent-issue-51.md`.

---

## 2026-09-07 - Actions métier whitelistées pour l'outillage Control Panel

### Changement

Extension de l'agent sortant (#51) : `com.lodygames.rpgquest.web.agent` gagne
la façade `AgentActions` (impl. `BukkitAgentActions`) et de nouveaux types
whitelistés dans `AgentActionType`, chacun adossé à un **service métier
existant** (`QuestProgressEngine`, `StoryService`, `YamlCustomItemRegistry`,
`PlayerResetService`, `PlayerVariableRepository`) — jamais une commande texte
`/rpgadmin`, jamais de SQL direct, mutations replacées sur le thread principal.

Lectures : `player.list`, `quest.list`, `quest.player.status`, `story.list`,
`story.player.status`, `item.list`, `player.resetnew.preview`.
Mutations : `player.item.give`, `quest.start|complete|reset`,
`story.advance|complete`, `player.variable.set`, `player.resetnew.confirm`.

Tout type absent des listes blanches → `REJECTED`. Les mutations exigent une
confirmation explicite côté panel ; l'agent exige en plus `confirm=true` pour
`player.resetnew.confirm`.

### Action serveur

1. **Remplacer le JAR RPGQuest** (build de la branche
   `feat/control-panel-admin-tools`).
2. **Aucune autre action** : pas de nouveau fichier de configuration, pas de
   migration, pas de changement `config.yml` / `data.db` / mondes / Citizens.
3. L'agent reste **inerte** sans `plugins/RPGQuest/plugadmin-agent.properties`
   (comme #51). Avec l'agent actif, **aucune** de ces actions ne s'exécute
   tant que le Control Panel n'en crée pas une explicitement (file `PENDING`).
4. Redémarrage serveur requis (remplacement de JAR).

### Ne PAS altérer

`data.db`, `config.yml`, `messages.yml`, mondes, `Citizens/`, autres plugins,
`plugadmin-agent.properties` existant.

### Sauvegarde préalable

- Ancien JAR (backup automatique par `deploy-verygames.sh`).
- `plugins/RPGQuest/data.db` (procédure standard).

### Déploiement

1. `./gradlew test build` sur `feat/control-panel-admin-tools`.
2. `scripts/deploy-verygames.sh -y` (JAR seul).
3. Redémarrer le serveur.
4. Côté PlugAdmin/AWS : redéployer l'app du panel (`scripts/plugadmin/deploy.sh`)
   pour obtenir les pages Joueurs / Quêtes / Stories et la liste blanche
   `AgentActionCatalog`.

### Validation

- Serveur RPGQuest démarre normalement ; agent : `event=plugadmin_probe status=ok`.
- Panel → page **Joueurs** → « Rafraîchir la liste » → l'action passe
  `PENDING → SUCCESS` **sans rechargement** (issue #65) et le roster s'affiche.
- Page **Quêtes** → « Rafraîchir le catalogue » → titres lisibles + étapes.
- `quest.complete` sur une quête déjà terminée → `FAILED` « déjà terminée »
  (aucune récompense re-créditée) ; sans confirmation → refusée côté panel.
- Un type d'action inconnu (ex. `console.run`) → `REJECTED`, audité `DENIED`.

### Rollback

- **Simple** : `enabled=false` dans `plugadmin-agent.properties` → agent inerte.
- **JAR** : `scripts/rollback-verygames.sh --latest`, redémarrer.
- Aucune migration `data.db` à défaire. `control-panel.db` (tables `agent_*`)
  sans effet sur RPGQuest.

### Exécution réelle

Non déployé par cette session (développement uniquement, sur AWS). Branche
`feat/control-panel-admin-tools` poussée, **non fusionnée**.
Rapport : `docs/claude-reports/2026-09-07_2159_control-panel-admin-tools.md`.

---

## 2026-09-08 - Déploiement VeryGames DEV : JAR RPGQuest de `feat/control-panel-admin-tools`

### Changement

Déploiement effectif sur **VeryGames DEV** du JAR RPGQuest bâti depuis
`feat/control-panel-admin-tools` @ `3c3ea5a` (contenu métier = commit
`e1ddb8c`, voir l'entrée du 2026-09-07 ci-dessus). Objectif : que l'agent
sortant reconnaisse les 15 nouveaux types d'action déjà servis par le Control
Panel AWS (`player.list`, `quest.list`, `quest.player.status`, `story.list`,
`story.player.status`, `item.list`, `player.resetnew.preview|confirm`,
`player.item.give`, `quest.start|complete|reset`, `story.advance|complete`,
`player.variable.set`). Avant : ces types revenaient `REJECTED` « Type
d'action non whitelisté » (agent plus ancien que le panel).

### Action serveur

- **Remplacement du seul JAR RPGQuest** (`rpgquest-0.1.0-SNAPSHOT.jar`).
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames).
- Aucun autre fichier : ni `data.db`, ni `config.yml`, ni `messages.yml`, ni
  mondes, ni `Citizens/`, ni `plugadmin-agent.properties` (déjà en place
  depuis #51), ni autre plugin. Aucune migration. Aucune donnée joueur touchée.
- Rien changé côté AWS/PlugAdmin (déjà déployé plus tôt le 2026-09-08).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` →
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T075204Z-predeploy.jar`
  (SHA-256 `2d648c0ba384494175f8eed3d23539198637a560d9dfd3e06a6ebce3500d58d1`,
  1 220 229 o) + `.meta`.

### Déploiement (exécuté)

1. `scripts/deploy-verygames.sh -y` — `./gradlew test` + `build` OK, backup du
   JAR en ligne, transfert FTP atomique du nouveau JAR
   (SHA-256 `cd66574cf3c780e877ab815b06aa9d8cb84c94440b49b43a29151d01a9e113cb`,
   1 256 923 o ; taille distante finale == locale).
2. `scripts/verygames-restart.sh` — `save-all` → `stop` RCON → serveur OFFLINE
   → relance auto VeryGames → **ONLINE** en < 1 min.

### Validation (exécutée)

- `/plugins` (RCON) : **RPGQuest en vert** (+ Citizens, Multiverse-Core, WorldEdit).
- `rpgquest version` (RCON) : répond `v0.1.0-SNAPSHOT`.
- Heartbeat agent reçu par PlugAdmin AWS après redémarrage :
  `server_state=ONLINE`, mondes `world_hub` / `claims` / `wild` **tous
  `loaded:true`**, `uptime_seconds≈125`. Aucune ligne `ERROR`/exception dans
  `journalctl -u plugadmin`.
- **Canal agent** (actions `PENDING` insérées dans la file `control-panel.db`,
  relevées et exécutées par l'agent) :
  - `player.list` → **SUCCESS** « 0 joueur(s) connecté(s). » (`details.players: []`) ;
  - `quest.list` → **SUCCESS** « 10 quête(s) chargée(s). » (catalogue réel).
  - → les deux ne sont **plus** `REJECTED` comme type inconnu. Objectif atteint.

### Rollback

- `scripts/rollback-verygames.sh --latest` → restaure
  `rpgquest-20260908T075204Z-predeploy.jar`, puis `scripts/verygames-restart.sh`.
- Aucune migration à défaire. `control-panel.db` (tables `agent_*`) sans effet
  sur RPGQuest.

### Exécution réelle

Déployé par cette session le 2026-09-08 (~07:52–07:56 UTC). Branche
`feat/control-panel-admin-tools` @ `3c3ea5a`, **non fusionnée**.
Rapport : `docs/claude-reports/2026-09-08_0756_deploy-verygames-actions-agent.md`.

---

## 2026-09-08 - Protocole `quest.list` structuré (objectifs / récompenses / donneur) — issues #78 / #75

### Changement

L'action agent `quest.list` transporte désormais, **en plus** des chaînes
legacy déjà présentes, des champs **structurés** :

- `steps[].objectiveDetails[]` = `{kind, target, amount, raw}` — le panel
  détermine type / cible / quantité sans regex ;
- `rewardDetails[]` = `{kind, amount, target, value, command, raw}` — la
  commande console **n'est plus tronquée à 60 caractères** ;
- `giverId` — id du PNJ donneur, **présent uniquement** si la quête déclare le
  nouveau champ optionnel `giver:` dans son YAML.

Aucun changement de gameplay, de schéma SQL, de commande en jeu. Les YAML de
quêtes existants restent valides tels quels (`giver:` est optionnel).

### Action serveur

- **Remplacement du seul JAR RPGQuest.**
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames) pour
  recharger le plugin.
- Aucun autre fichier : ni `data.db`, ni `config.yml`, ni `messages.yml`, ni
  mondes, ni `Citizens/`, ni `plugadmin-agent.properties`, ni autre plugin.
  Aucune migration. Aucune donnée joueur touchée.
- Aucune action AWS/PlugAdmin requise pour le protocole (le panel gère déjà
  les deux formats) ; le Control Panel AWS est tout de même redéployé dans la
  même session pour embarquer le rendu structuré.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` dans
  `~/.local/share/rpgquest/verygames-backups/` (+ `.meta`). Ne jamais écraser
  le dernier backup.

### Déploiement

1. `scripts/deploy-verygames.sh -y` (lance `./gradlew test` + `build`, backup
   du JAR en ligne, transfert FTP atomique).
2. `scripts/verygames-restart.sh` (`save-all` → `stop` RCON → relance auto).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu par PlugAdmin AWS après redémarrage (aucune ligne
  `ERROR` dans `journalctl -u plugadmin`).
- Action `quest.list` (file `control-panel.db`) → **SUCCESS** ; le `details`
  renvoyé contient `steps[].objectiveDetails` et `rewardDetails` non vides, et
  `giverId` pour au moins la quête `crystal_hunt` (après ajout de `giver:` à
  son YAML).
- Catalogue `/quests` du panel exploitable avec ces données (noms FR par
  jeton, commande longue non tronquée, ligne « Donneur »).

### Rollback

- `scripts/rollback-verygames.sh --latest` → restaure le JAR `*-predeploy.jar`
  sauvegardé, puis `scripts/verygames-restart.sh`.
- AWS : `scripts/plugadmin/rollback.sh app` (release précédente du Control
  Panel).
- Aucune migration à défaire.

### Exécution réelle

Déployé par cette session le 2026-09-08 (~11:00–11:04 UTC). Branche
`feat/control-panel-admin-tools` @ `02642e9`, **non fusionnée**.

- **AWS Control Panel** : `scripts/plugadmin/deploy.sh` → `installDist` OK,
  ancienne app sauvegardée sous `/opt/plugadmin/releases/20260908-105940`,
  `systemctl restart` → `active` (PID 124361, `event=panel_started port=8090`).
  JAR déployé sha256 `6493fc1584deafb1a61b78f9d46b91ec0a5a834c37a6d2963df59816ce2a5bff`
  == build frais de la branche ; classes `ObjectiveText`/`RewardText` présentes.
  `/health` local + public `ONLINE` (×3) ; `/dashboard` `/quests` `/players`
  `/stories` (anon) → 303 `/login` ; `/login` 200 ; autres vhosts
  (`dig.lodygames.com`, `lodylands.com`, `www.lodylands.com`) → 200 ; `nginx -t` OK,
  nginx/secrets/TLS non touchés.
- **VeryGames DEV** :
  1. `scripts/deploy-verygames.sh -y` → `./gradlew test`+`build` OK, backup auto
     `rpgquest-20260908T110044Z-predeploy.jar` (sha256 `cd66574c…`), transfert FTP
     atomique du JAR **sha256 `4fbaa3456b00534c6309b4b1fcbf789fd9c2e0bdf43e6c51180817f6ddeb9c2f`**
     (1 261 384 o, taille distante == locale).
  2. `scripts/verygames-restart.sh` → `save-all` → `stop` RCON → OFFLINE → relance
     auto VeryGames → **ONLINE** en < 1 min.
  3. `scripts/deploy-verygames.sh -y --also src/main/resources/quests/crystal_hunt.yml:RPGQuest/quests/crystal_hunt.yml`
     → ancien `crystal_hunt.yml` sauvegardé
     (`verygames-backups/extra-20260908T110340Z/…`, sha256 `06156d6d…`), nouveau
     transféré (sha256 `e8f2c8ce…`) ; puis RCON `quest admin reload` →
     « 10 quête(s) chargée(s), 0 erreur(s) ».
- **Vérifs VeryGames** : `/plugins` → RPGQuest **en vert** (+ Citizens,
  Multiverse-Core) ; `rpgquest version` → `v0.1.0-SNAPSHOT` ; heartbeat agent reçu
  par PlugAdmin (`server_state=ONLINE`, `uptime_seconds` faible → redémarrage pris
  en compte) ; aucune ligne `ERROR` dans `journalctl -u plugadmin`.
- **Validation `quest.list` réelle** (action `PENDING` injectée dans
  `control-panel.db`, relevée + exécutée par l'agent DEV) → **SUCCESS**
  « 10 quête(s) chargée(s). » ; `details.quests[].steps[].objectiveDetails`
  (`kind`/`target`/`amount`/`raw` pour `KILL_ENTITY`/`COLLECT_ITEM`/`CRAFT_ITEM`/
  `TALK_TO_NPC`) et `rewardDetails` (`EXPERIENCE`/`COMMAND`/`VARIABLE`, `command`
  complète non tronquée) **présents** ; après le reload avec `giver: guard`,
  `rpgquest:crystal_hunt` porte `giverId: "guard"`. Champs legacy toujours
  présents en parallèle (compat).

### Rollback (points exacts)

- VeryGames JAR : `scripts/rollback-verygames.sh --latest` →
  `rpgquest-20260908T110044Z-predeploy.jar` (sha256 `cd66574c…`).
- VeryGames `crystal_hunt.yml` : `scripts/rollback-verygames.sh --also
  /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20260908T110340Z/RPGQuest/quests/crystal_hunt.yml:RPGQuest/quests/crystal_hunt.yml`
  puis RCON `quest admin reload` (voir `MANIFEST.txt` du dossier de backup).
- AWS : `scripts/plugadmin/rollback.sh app` → release `20260908-105940`.
- Rapport : `docs/claude-reports/2026-09-08_1100_quest-list-structure-78-75.md`.

---

## 2026-09-08 - Action agent `npc.list` + page Control Panel `/npcs` (V1) — issues #66 / #75

### Changement

Nouvelle action agent **`npc.list`** (lecture seule, whitelistée `AgentActionType` ↔
`AgentActionCatalog`). Elle renvoie un **catalogue PNJ RPGQuest** dérivé (`NpcCatalog`,
classe pure) par croisement de : liaisons Citizens (`npc_citizens_bindings`), dialogues
`rpgquest:<id>`, champ `giver:` des quêtes (#75), objectifs `TALK_TO_NPC`. Le payload porte
`npcs[]` (id, nom lisible, id Citizens, dialogue, quêtes données/référencées, `warnings[]`),
`canonicalIds` (préparation #66) et des compteurs.

Aucun changement de gameplay, de schéma SQL, de commande en jeu, ni de contenu YAML.
`NpcCatalog` **ne lit jamais le monde** (pas de scan d'entités ; position/monde et PNJ
Citizens non tagués hors périmètre V1). Le seul accès disque est le `SELECT` déjà asynchrone
des liaisons Citizens.

Côté Control Panel : l'entrée « PNJ » du menu (`/npcs`) n'est plus « à venir ».

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse le type `npc.list`).
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames).
- Aucun autre fichier : ni `data.db`, ni `config.yml`, ni `messages.yml`, ni mondes, ni
  `Citizens/`, ni YAML de contenu, ni `plugadmin-agent.properties`. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`) pour la page `/npcs`.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` dans
  `~/.local/share/rpgquest/verygames-backups/` (+ `.meta`). Ne jamais écraser le dernier backup.

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu par PlugAdmin ; aucun `ERROR` dans `journalctl -u plugadmin`.
- Action `npc.list` (file `control-panel.db`) → **SUCCESS** ; `details.npcs` non vide,
  `canonicalIds` contient `guard`, warnings cohérents.
- `/npcs` du panel exploitable (nom lisible, ids copiables, anomalies visibles).
- `/health` AWS local + public `ONLINE` ; `/npcs` (anon) → 303 `/login`.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Déployé par cette session le 2026-09-08 (~11:46–11:50 UTC). Branche
`feat/control-panel-admin-tools` @ `9fa57d1`, **non fusionnée**.

- **AWS Control Panel** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
  `/opt/plugadmin/releases/20260908-114604`, `systemctl restart` → `active` (PID 146189,
  `event=panel_started port=8090`). JAR déployé sha256
  `2bf90a25a949453eb37bc2ab82c2eff2d3dc50c1b01d3c14f47b251cdf8aa0d7` == build de la branche.
  `/health` local + public `ONLINE` ×3 ; `/npcs` (anon) → 303 `/login` (route **active**) ;
  autres vhosts (`dig.lodygames.com`, `lodylands.com`, `www.lodylands.com`) → 200 ;
  nginx/secrets/TLS non touchés.
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` → `./gradlew test`+`build` OK, backup auto
  `rpgquest-20260908T114637Z-predeploy.jar` (sha256 `4fbaa3456b00534c6309b4b1fcbf789fd9c2e0bdf43e6c51180817f6ddeb9c2f`),
  transfert FTP atomique du JAR **sha256
  `12786cb3fc0d997e55d3f7eba2f1432cdcdcaa479ae4b6100fcf8d83fa122ba3`** (1 282 340 o, distant ==
  local) ; puis `scripts/verygames-restart.sh` → `stop` RCON → OFFLINE → relance auto → **ONLINE**
  en < 1 min.
- **Vérifs VeryGames** : `/plugins` → RPGQuest **en vert** (+ Citizens) ; `rpgquest version` →
  `v0.1.0-SNAPSHOT` ; heartbeat agent (`server_state=ONLINE`, `uptime_seconds=65` → restart pris
  en compte) ; **aucune** ligne `ERROR` dans `journalctl -u plugadmin`.
- **Validation `npc.list` réelle** (action `PENDING` injectée dans `agent_action`, relevée +
  exécutée par l'agent DEV) → **SUCCESS** « 8 PNJ RPGQuest (1 avec avertissement). ».
  `details` réel : `citizensAvailable=true`, `total=8`, `bound=7`, `unbound=1`, `withWarnings=1` ;
  `canonicalIds = [guard, guide, help, jeff, jo, junior, libraire, woodcutter_bob]` ;
  `rpgquest:guard` = `{displayName:"Garde", citizensNumericId:6, dialogueId:"rpgquest:guard"
  (5 nœuds/8 choix), questsGiven:["rpgquest:crystal_hunt"],
  questsReferenced:["rpgquest:crystal_hunt"], dialogueStartsQuests:["rpgquest:first_steps",
  "rpgquest:crystal_hunt"], warnings:[]}` ; **anomalie réelle détectée** :
  `woodcutter_bob` → `QUEST_REF_NO_NPC` (`warning`) — référencé par `woodcutters_request` mais
  aucun PNJ Citizens tagué sur ce serveur.

### Rollback (points exacts)

- VeryGames : `scripts/rollback-verygames.sh --latest` →
  `rpgquest-20260908T114637Z-predeploy.jar` (sha256 `4fbaa345…`), puis `scripts/verygames-restart.sh`.
- AWS : `scripts/plugadmin/rollback.sh app` → release `20260908-114604`.
- Rapport : `docs/claude-reports/2026-09-08_1147_npc-list-page-npcs-v1.md`.

---

## 2026-09-08 - Système PNJ V2 déclarative : NpcDefinition + écritures de contenu — issues #66 / #75

### Changement

Introduit une **définition logique** de PNJ RPGQuest, indépendante de Citizens et du monde :
un fichier par PNJ sous `plugins/RPGQuest/npcs/*.yml` (`YamlNpcEngine`, `NpcDefinition` :
`id`, `display_name`, `dialogue?`, `role?`, `enabled`). Voir `NPC_FORMAT.md`.

- `npc.list` distingue désormais `logicalDefinitionPresent` vs `citizensBindingPresent`,
  calcule un `state`, et expose `definedIds` (source canonique, transition #66). Nouveaux codes
  d'anomalie : `NO_DEFINITION` / `BINDING_NO_DEFINITION` / `DIALOGUE_MISSING` (err), `NOT_LINKED`
  / `DISABLED` (info).
- Nouvelles actions agent d'**écriture de contenu** whitelistées + auditées :
  `npc.definition.create` / `npc.definition.update` (`NpcDefinitionStore` — écriture atomique,
  jamais d'écrasement silencieux, jamais de YAML brut ni de chemin arbitraire) et
  `quest.giver.set` (`QuestGiverEditor` — pose `giver:` sur le YAML d'une quête en préservant
  commentaires et format ; recharge le moteur de quêtes).

**Un exemple `npcs/guard.yml` est livré** et copié au premier démarrage (comme
`dialogues/guard.yml`). Aucun autre changement de contenu, de schéma SQL, de commande en jeu.

### Action serveur

- **Remplacement du seul JAR RPGQuest.**
- Redémarrage serveur (RCON `stop` → relance automatique VeryGames).
- Le dossier `plugins/RPGQuest/npcs/` et `npcs/guard.yml` sont **créés automatiquement** au
  démarrage. Aucun fichier existant n'est modifié. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).
- Les définitions PNJ créées ensuite via le Control Panel écrivent dans `plugins/RPGQuest/npcs/`
  (via l'agent, sur le serveur) ; `quest.giver.set` édite un fichier de `plugins/RPGQuest/quests/`.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh` (+ `.meta`). Ne jamais
  écraser le dernier backup.
- `plugins/RPGQuest/quests/` : à sauvegarder si des `quest.giver.set` sont exécutés après le
  déploiement (le script `deploy-verygames.sh --also` sauvegarde de toute façon avant tout envoi ;
  ici c'est l'agent qui édite en place — prévoir un backup FTP du dossier `quests/` avant une
  session d'attribution de quêtes).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Log de démarrage : `Chargement des PNJ : N définition(s), 0 erreur(s).`
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- Action `npc.list` → **SUCCESS** ; `details.npcs[].logicalDefinitionPresent` renseigné,
  `definedIds` contient `guard`.
- Action `npc.definition.create` (id de test, ex. `woodcutter_bob`) → **SUCCESS** ; le fichier
  `npcs/woodcutter_bob.yml` apparaît ; `npc.list` suivant montre `state: NOT_LINKED`.
- Action `quest.giver.set` sur une quête DEV (ex. `rpgquest:woodcutters_request` → `woodcutter_bob`)
  → **SUCCESS** ; le YAML de la quête contient `giver: woodcutter_bob`, commentaires préservés.
- `/npcs` du panel : deux blocs (Définition / Binding), création + édition + attribution visibles.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Les fichiers `npcs/*.yml` créés restent (inertes avec l'ancien JAR) ; les supprimer si besoin.
  Un `quest.giver.set` se défait en éditant / restaurant le YAML de la quête concernée.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~12:35–12:46 UTC). Branche `feat/control-panel-admin-tools` @ `1340dda`,
**non fusionnée**.

- **AWS Control Panel — DÉPLOYÉ** : `scripts/plugadmin/deploy.sh` → ancienne app sauvegardée
  `/opt/plugadmin/releases/20260908-123552`, `systemctl restart` → `active` (PID 166440). JAR
  déployé sha256 `01f14c6071584261f932eca36cc38ff11769999bc3c1ff3d60be1c3819dd51a5` == build de
  la branche. `/health` local + public `ONLINE` ×3 ; `/npcs` + `/dashboard` (anon) → 303 ; autres
  vhosts 200 ; nginx/secrets/TLS non touchés. Rollback : `scripts/plugadmin/rollback.sh app`.
- **VeryGames DEV — DÉPLOYÉ** (le FTP a eu une panne transitoire ~12:38–12:43 UTC ; deux tentatives
  ont échoué **avant toute écriture** — `curl (28) timed out` — puis le FTP est revenu). Réussi
  ~12:44 UTC : `scripts/deploy-verygames.sh -y` → JAR sha256
  `261d7a378c58f714a35c1bea861b2cdf69727ad73b94b2f73cf9e9c8a30c3549` (1 317 563 o), backup
  `rpgquest-20260908T124242Z-predeploy.jar` (sha256 `12786cb3…`) ; puis `scripts/verygames-restart.sh`
  → `stop` RCON → OFFLINE → relance auto → **ONLINE** en < 1 min.
- **Vérifs VeryGames** : `/plugins` → RPGQuest **en vert** (+ Citizens) ; `rpgquest version` →
  `v0.1.0-SNAPSHOT` ; heartbeat agent (`uptime_seconds=6`) ; aucun `ERROR` `journalctl -u plugadmin`.
- **Validation réelle** (actions injectées dans `agent_action`, exécutées par l'agent DEV) :
  - `npc.list` → **SUCCESS** « 8 PNJ (7 avec avertissement) » ; `guard` = définition
    (`npcs/guard.yml` auto-créé) + binding → `state: LINKED`, `role: quest_giver`, `displayName`
    de la définition, aucune anomalie ; `definedIds = [guard]` ; `guide`/`help`/`jeff`/`jo`/`junior`/
    `libraire` = binding sans définition → `CITIZENS_ORPHAN` + `BINDING_NO_DEFINITION` (err) ;
    `woodcutter_bob` = référencé sans définition → `UNDEFINED_REFERENCE` + `NO_DEFINITION` (err).
  - `npc.definition.create` (`woodcutter_bob`, `display_name=Bûcheron Bob`, `role=quest_giver`) →
    **SUCCESS** `CREATED`, effet `npcs/woodcutter_bob.yml`.
  - `quest.giver.set` (`rpgquest:woodcutters_request` → `woodcutter_bob`) → **SUCCESS** `SET`,
    effets `quests/woodcutters_request.yml` + `giver: woodcutter_bob` ; RCON `quest admin validate`
    → « 10 quête(s), 0 erreur(s) » (YAML édité valide, commentaires préservés).
  - `npc.list` (2e) → `definedIds = [guard, woodcutter_bob]`, `withDefinition = 2` ;
    `woodcutter_bob` porte `questsGiven: [rpgquest:woodcutters_request]` (l'attribution a pris,
    moteur de quêtes rechargé).
  - `npc.definition.update` (`woodcutter_bob`, sans `dialogue`) → **SUCCESS** — nettoie l'anomalie
    `DIALOGUE_MISSING` introduite par le `dialogue_id` de test.

### Rollback (points exacts)

- AWS : `scripts/plugadmin/rollback.sh app` → release `20260908-123552`.
- VeryGames : rien à défaire (déploiement non effectué). Après déploiement futur :
  `scripts/rollback-verygames.sh --latest` + `scripts/verygames-restart.sh` ; supprimer les
  `npcs/*.yml` créés ; restaurer un YAML de quête édité par `quest.giver.set`.
- Rapport : `docs/claude-reports/2026-09-08_1235_npc-v2-declarative.md`.

---

## 2026-09-08 - Lier une définition PNJ à un PNJ Citizens existant — issue #81 (phase 1)

### Changement

Nouvelles actions agent :

- `npc.citizens.list` (lecture) — parcourt le **registre Citizens** (`NpcIdentityService.citizensRoster`,
  thread principal, jamais un scan d'entités/chunks) et croise avec `npc_citizens_bindings` :
  `{numericId, uuid, name, linkedNpcId?, availableForBinding, spawned}` + compteurs. Vide si
  Citizens inactif.
- `npc.citizens.link` (mutation whitelistée, permission dédiée `NPC_BIND_WRITE`, `confirm`
  obligatoire, audit) — lie une **définition logique existante** (`npc_id`) à un **PNJ Citizens
  existant** (`citizens_id` numérique). `CitizensBindPlanner` (pur) : liaison identique → succès
  no-op ; ce PNJ Citizens ou ce `npc_id` déjà lié → refus lisible (**jamais de rebind
  silencieux**). Écriture atomique `NpcBindingRepository.insertIfAbsent` (`INSERT OR IGNORE`) puis
  rafraîchissement du cache `NpcIdentityService` (l'identification en jeu prend effet **sans
  redémarrage**).

**Aucun spawn, aucun rebind, aucune suppression de PNJ Citizens.** Aucun changement de schéma SQL
(la table `npc_citizens_bindings` de la migration V12 est réutilisée), de commande en jeu, de
contenu YAML.

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse `npc.citizens.list` /
  `npc.citizens.link`).
- Redémarrage serveur (RCON `stop` → relance auto VeryGames).
- Aucun autre fichier. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- `plugins/RPGQuest/data.db` par précaution (les liaisons créées ensuite via `npc.citizens.link`
  écrivent dans `npc_citizens_bindings` — un `INSERT` non destructif).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- Action `npc.citizens.list` → **SUCCESS** ; `details.citizens[]` peuplé, `availableForBinding`
  cohérent avec les liaisons existantes.
- Action `npc.citizens.link` sur une définition **NOT_LINKED** + un PNJ Citizens **libre** →
  **SUCCESS** `LINKED` ; un `npc.list` suivant montre `state: LINKED`.
- Action `npc.citizens.link` sur un PNJ Citizens **déjà lié** → **FAILED** `CITIZENS_TAKEN`.
- `npc.list` toujours **SUCCESS**.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Une liaison créée se défait par `DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?`
  (ou `/rpgadmin npc untag` en visant l'entité) — hors périmètre de ce lot.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~13:19–13:25 UTC). Branche `feat/control-panel-admin-tools` @ `3d1f50c`.

- **AWS / PlugAdmin** : `scripts/plugadmin/deploy.sh` — OK (release `/opt/plugadmin/releases/20260908-131859`,
  JAR `control-panel-0.1.0-SNAPSHOT.jar` SHA-256 `a52ad4d03e7752483be13a33e811ca094d115d8d2cc3ac303988313e16258f11`).
  `/health` public **ONLINE** ; `dig.lodygames.com` / `lodylands.com` inchangés (200).
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar`
  1331243 o, SHA-256 `342bf808cf1b4d1bdba0981dc2c0044190ca577f38176730b699d1b08c39b165`.
  Backup auto de l'ancien JAR : `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T131925Z-predeploy.jar`.
- `scripts/verygames-restart.sh` — `stop` RCON → OFFLINE → relance auto → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` tous verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`.
- Heartbeat agent reçu `2026-09-08T13:24:04Z` (`ONLINE`, plugin `0.1.0-SNAPSHOT`) ;
  aucun `ERROR` / `WARN` dans `journalctl -u plugadmin` (fenêtre du déploiement).
- `npc.citizens.list` → **SUCCESS** : 7 PNJ Citizens (`#0..#6`), `citizensAvailable:true`,
  chaque entrée `{numericId, uuid, name, linkedNpcId, availableForBinding, spawned}`, **aucune
  position ni monde**. Les 7 Citizens DEV sont **déjà liés** (`/rpgadmin npc tag` antérieur) —
  `available: 0`. Seul `guard` possède aussi une définition logique ; `guide/help/jeff/jo/junior/libraire`
  restent des `CITIZENS_ORPHAN` (binding sans définition) — non détournés.
- `npc.list` → **SUCCESS** (8 PNJ, 7 avec avertissement) — inchangé.
- `npc.citizens.link` — cas exercés en direct (aucun binding créé) :
  - `woodcutter_bob` → Citizens `#6` (déjà lié à `guard`) → **FAILED** `CITIZENS_TAKEN`
    (« Citizens #6 est déjà lié à npc_id=guard. Aucune réaffectation dans cette phase. »).
  - `guard` → Citizens `#6` (liaison identique) → **SUCCESS** `NOOP`
    (« Citizens #6 est déjà lié à « guard » — rien à faire. »).
  - `does_not_exist_xyz` → `#6` → **FAILED** `UNKNOWN_NPC`.
  - `woodcutter_bob` → Citizens `#9999` → **FAILED** `UNKNOWN_CITIZENS`.
- **Chemin nominal `INSERT` / `LINKED` non exerçable en direct** : aucun PNJ Citizens libre sur
  DEV et consigne de ne pas en créer / ne pas détourner un PNJ existant. Couvert par les tests
  automatisés (`CitizensBindPlannerTest`, `NpcIdentityServiceTest#bindCitizens*`,
  `NpcBindingRepositoryTest#insertIfAbsent*`, `NpcCitizensPayloadTest`, `AgentActionExecutorTest`,
  `NpcsCatalogTest`).
- Aucun PNJ Citizens créé ni supprimé ; aucune progression joueur touchée ; aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1325_npc-citizens-link-81.md`.

## 2026-09-08 - Spawn d'un PNJ Citizens depuis une définition — issue #81 (phase 2)

### Changement

Nouvelle action agent **`npc.citizens.create`** (mutation whitelistée `AgentActionType` ↔
`AgentActionCatalog`, **permission dédiée `NPC_SPAWN_WRITE`**, `confirm` obligatoire, audit,
validation 3 couches). Crée **physiquement** un PNJ Citizens (`EntityType.PLAYER`) à partir d'une
`NpcDefinition` existante — nom = `displayName` — puis le lie immédiatement à son `npc_id`.

- Paramètres **métier uniquement** : `npc_id`, `world`, `x`/`y`/`z`, `yaw`?/`pitch`? (défaut `0`).
  Le navigateur n'envoie jamais de nom Citizens libre, d'UUID, de commande console, de YAML ni de
  chemin.
- `CitizensSpawnPlanner` (pur, sans Bukkit) vérifie **toutes** les préconditions logiques avant la
  moindre création : Citizens actif (`CITIZENS_UNAVAILABLE`), définition présente (`UNKNOWN_NPC`)
  et `enabled` (`NPC_DISABLED`), `npc_id` pas déjà lié (`NPC_ALREADY_LINKED`), `world` dans la
  **liste blanche RPGQuest** (hub / claims / exploration de la config — `UNKNOWN_WORLD`), position
  **finie** et bornée (`|x|,|z| ≤ 29 999 984`, `-2048 ≤ y ≤ 2048`, `-90 ≤ pitch ≤ 90` —
  `INVALID_POSITION`, jamais « corrigée »).
- Thread principal : le monde doit être **chargé** et `y` dans ses limites réelles, puis
  `createNPC` + `npc.spawn(loc, CREATE)` (`CREATE_FAILED` si Citizens refuse).
- `CitizensSpawnCoordinator` (pur) orchestre `create → bind → success` ; si la liaison échoue
  **après** création, `destroyCitizensNpc(numericId, uuid)` supprime **le seul PNJ créé par cette
  action** (double clé — jamais un PNJ préexistant) → `BIND_FAILED_ROLLED_BACK`.
- Persistance du binding : `NpcIdentityService.bindCitizens` (réutilise `CitizensBindPlanner` +
  `NpcBindingRepository.insertIfAbsent`, anti-collision phase 1) ; cache d'identification rafraîchi
  → identification en jeu effective **sans redémarrage**.

**Aucune migration SQL** (table `npc_citizens_bindings` de la V12 réutilisée). Aucune commande en
jeu, aucun contenu YAML modifié. Aucune suppression générale de PNJ Citizens (seul le rollback
interne supprime — une action `npc.citizens.delete` éventuelle serait un chantier séparé).

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse `npc.citizens.create`).
- Redémarrage serveur (RCON `stop` → relance auto VeryGames).
- Aucun autre fichier. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- `plugins/RPGQuest/data.db` par précaution (un spawn réussi écrit une ligne
  `npc_citizens_bindings` — un `INSERT` non destructif). Les fichiers Citizens
  (`plugins/Citizens/saves.yml` ou base) évoluent aussi dès qu'un PNJ est réellement créé.

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest **et Citizens** en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `npc.list` et `npc.citizens.list` toujours **SUCCESS**.
- `npc.citizens.create` **sans position sûre explicite = non exécuté en réel** (consigne : ne pas
  faire apparaître un PNJ n'importe où sur DEV). Validé sur DEV **sans spawn réel** : refus
  `NPC_ALREADY_LINKED` / `UNKNOWN_WORLD` / `INVALID_POSITION` / `UNKNOWN_NPC`. Le chemin nominal
  `CREATED` est couvert par les tests automatisés.
- Premier spawn réel : `PENDING MANUAL VALIDATION` (owner, position explicite fournie).

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Un PNJ Citizens réellement créé se retire par `/npc select <id>` + `/npc remove` **puis**
  `DELETE FROM npc_citizens_bindings WHERE citizens_uuid = ?` (ou `/rpgadmin npc untag` en visant
  l'entité) — hors périmètre de ce lot.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~14:22–14:45 UTC). Branche `feat/control-panel-admin-tools`.

- **AWS / PlugAdmin** : `scripts/plugadmin/deploy.sh` — OK (release
  `/opt/plugadmin/releases/20260908-143939`, `control-panel-0.1.0-SNAPSHOT.jar` SHA-256
  `bff44c5b08c86a89b6afd989db4a2589e5e2a40edcb232e677244c9db33bec65`). `NPC_SPAWN_WRITE` +
  `npc.citizens.create` présents dans le JAR. `/health` public **ONLINE** ; `dig.lodygames.com` /
  `lodylands.com` inchangés (200).
- **VeryGames DEV — 1re passe (`e56930c`)** : `deploy-verygames.sh -y` + `verygames-restart.sh`.
  Validation live : `npc.citizens.create` échouait en **`NullPointerException`** sur tous les
  chemins de refus (`AgentActionOutcome` recopie ses détails via `Map.copyOf`, qui refuse les
  valeurs `null` ; `citizens_id` était `null` avant création).
- **Correctif `b9f3beb`** (`citizens_id` → `-1` quand aucun PNJ Citizens ; test de régression) —
  `./gradlew build` vert.
- **VeryGames DEV — 2e passe (`b9f3beb`)** : `deploy-verygames.sh -y` — JAR
  `rpgquest-0.1.0-SNAPSHOT.jar` 1 349 308 o, SHA-256
  `6a7ff0d4b2147f8153c05bed954a93df35f0cb6cf60033152840b7f31f4d94a9` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T144029Z-predeploy.jar`.
- `scripts/verygames-restart.sh` — `stop` RCON → OFFLINE → relance auto → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent `2026-09-08T14:41:43Z` (`ONLINE`,
  `0.1.0-SNAPSHOT`) ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `npc.list` → **SUCCESS** (8 PNJ) ; `npc.citizens.list` → **SUCCESS** (7 Citizens, 0 libre) —
  inchangés.
- `npc.citizens.create` — **aucun spawn réel** (consigne : pas de position sûre explicite sur
  DEV) :
  - `guard` (déjà lié à Citizens #6) → **FAILED `NPC_ALREADY_LINKED`** ;
  - `woodcutter_bob` → `the_nether` → **FAILED `UNKNOWN_WORLD`** (message : « hors de la liste
    blanche RPGQuest (claims, wild, world_hub) ») ;
  - `woodcutter_bob` → `world_hub`, `y=99999` → **FAILED `INVALID_POSITION`** ;
  - `does_not_exist_xyz` → **FAILED `UNKNOWN_NPC`** ; `details.citizens_id = -1` (plus de NPE) ;
  - `woodcutter_bob` → `world_hub`, `x=NaN` → **REJECTED** (`AgentActionExecutor`, avant la
    couche métier).
- **Chemin nominal `CREATED` non exercé en direct** — couvert par les tests automatisés.
- Aucun PNJ Citizens créé ni supprimé ; aucune progression joueur touchée ; aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1423_npc-citizens-create-81-phase2.md`.

## 2026-09-08 - Page /dialogues V1 : lecture structurée + squelette — action agent dialogue.list / dialogue.definition.create

### Changement

Nouveau canal agent **lecture** `dialogue.list` (permission dédiée `DIALOGUE_READ`) et **écriture**
`dialogue.definition.create` (permission dédiée `DIALOGUE_WRITE`, `confirm` obligatoire, audit).

- `dialogue.list` : `DialogueCatalog` (pur, sans Bukkit) dérive de `YamlDialogueEngine.dialogues()`
  + `lastReport().issues()` + `YamlNpcEngine` + `YamlQuestEngine` un catalogue structuré — par
  dialogue : nœuds ordonnés (départ d'abord) avec `reachable` (BFS des `next`), choix avec
  **actions et conditions typées** `{kind, target, value, raw}`, relations PNJ (convention
  `rpgquest:<id>` + `NpcDefinition.dialogue`), quêtes référencées/démarrées, warnings de
  cohérence. Les fichiers rejetés au chargement (dialogue sans nœud, `start` invalide, cycle
  `OPEN_DIALOGUE`, id dupliqué) sont remontés à part (`loadIssues[]`), jamais dans la liste des
  dialogues.
- `dialogue.definition.create` : crée un **squelette** `dialogues/<key>.yml` (un nœud `start`,
  un choix « fermer »). `DialogueDraft` → `DialogueDefinitionYaml.render` (déterministe) →
  `DialogueDefinitionStore.create` (écriture atomique tmp + `ATOMIC_MOVE`, **refus d'écrasement**,
  rechargement complet du dossier — fichier supprimé si le nouveau dialogue ne se recharge pas).
  Jamais de YAML brut, jamais de chemin. Après succès : `YamlDialogueEngine.reload()` (le
  dialogue devient immédiatement ouvrable en jeu — l'ouverture se fait par convention
  `rpgquest:<id>`).

**Aucune migration SQL. Aucun contenu de dialogue existant modifié.** L'édition fine des
nœuds/choix/actions viendra avec un futur éditeur.

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse `dialogue.list` /
  `dialogue.definition.create`).
- Redémarrage serveur (RCON `stop` → relance auto VeryGames).
- Aucun autre fichier. Aucune migration.
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- `plugins/RPGQuest/dialogues/` par précaution (une création via `dialogue.definition.create`
  ajoute un fichier `<key>.yml` — jamais d'écrasement).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `dialogue.list` → **SUCCESS** ; `details.dialogues[]` peuplé (dialogues livrés : `guard`,
  `guide`, `jo`, `libraire`, `merchant`), `loadIssues` cohérent, actions typées présentes.
- `npc.list` toujours **SUCCESS**.
- `dialogue.definition.create` : **non exécuté en réel** pour ne pas ajouter de contenu de
  dialogue à DEV ; validé sur fixture / tests (round-trip parse, refus d'écrasement).

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
  Un squelette créé se retire en supprimant `plugins/RPGQuest/dialogues/<key>.yml` puis
  `quest admin reload` / redémarrage.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~15:16–15:22 UTC). Branche `feat/control-panel-admin-tools` @ `1fa7f4a`.

- **AWS / PlugAdmin** : `scripts/plugadmin/deploy.sh` — OK (release
  `/opt/plugadmin/releases/20260908-151618`, `control-panel-0.1.0-SNAPSHOT.jar` SHA-256
  `3c873e538a01c88ba4bf5a1a4dfea46840e9ce6c589b9b2888b576bfbfc259e9`). `DIALOGUE_READ` /
  `DIALOGUE_WRITE` + `dialogue.list` / `dialogue.definition.create` présents dans le JAR.
  `/health` public **ONLINE** ; `/dialogues` anon → 303 ; `dig.lodygames.com` /
  `lodylands.com` inchangés (200).
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar`
  1 403 924 o, SHA-256 `57bda61b54ea726e957a4d37079cd4aa85644e73a865c31d027c5472c2733b42` ;
  backup auto `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T151640Z-predeploy.jar`.
  `scripts/verygames-restart.sh` → `stop` RCON → OFFLINE → relance auto → **ONLINE**.
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent `2026-09-08T15:19:17Z` (`ONLINE`,
  `0.1.0-SNAPSHOT`) ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `dialogue.list` → **SUCCESS** : « 7 dialogue(s) (4 avec avertissement, 14 fichier(s)
  rejeté(s)) », `nodeTotal=22`.
  - 7 dialogues chargés (`guide`, `help`, `jeff`, `jo`, `guard`, `junior`, `libraire`).
  - Actions/conditions **typées** présentes (`START_QUEST`, `RUN_SAFE_COMMAND`, `CLOSE`,
    `QUEST_STATE`, `VARIABLE_EQUALS`, `NO_MAIN_CLAIM`, `LACKS_CUSTOM_ITEM`).
  - Relations PNJ : `guard` / `junior` / `libraire` liés (ids canoniques) ;
    `guide` / `help` / `jeff` / `jo` non liés → warning `DIALOGUE_NO_NPC` (PNJ Citizens sans
    `NpcDefinition`, cohérent avec #81 phase 1).
  - `loadIssues` (14) : 7 fixtures `test_*.yml` **préexistantes** (`test_break_block`, …,
    `test_talk_to_npc`), chacune « `start` obligatoire » + « `nodes` obligatoire ». Correctement
    rejetées et absentes de la liste des dialogues — la page se rend quand même. Non touchées
    par cette tâche. `declaredButMissing: []`.
- `npc.list` → toujours **SUCCESS** (8 PNJ).
- `dialogue.definition.create` **non exécuté en réel** (consigne : ne pas ajouter de contenu de
  dialogue à DEV) — couvert par les tests.
- Aucun dialogue existant modifié ; aucune progression joueur touchée ; aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1510_dialogues-page-v1.md`.

---

## 2026-09-08 - Nettoyage fixtures test_*.yml du dossier dialogues DEV (#83)

### Changement

**Contenu DEV uniquement — aucun code, aucun JAR, aucune migration.**

Sept fichiers `test_*.yml` (`test_break_block`, `test_collect_item`, `test_craft_item`,
`test_kill_entity`, `test_place_block`, `test_reach_location`, `test_talk_to_npc`) avaient été
copiés à tort dans `plugins/RPGQuest/dialogues/` du serveur **DEV**. Ce sont des **fixtures de
quête de test manuel** (déjà versionnées dans le dépôt à `docs/manual-tests/quests/test_*.yml`,
emplacement non chargé par `YamlDialogueEngine`), structure de **quête**, pas de dialogue → le
loader de dialogues les rejette correctement (« `start` obligatoire » + « `nodes` obligatoire »
= 14 `loadIssues`). Contenu distant comparé octet à octet aux fixtures du dépôt : identique 7/7.

Retrait des 7 fichiers du dossier actif `dialogues/`. Le **loader n'est pas modifié** : aucun
filtre `test_*`, aucun `loadIssue` masqué — tout YAML invalide réellement présent continuera
d'être signalé.

`plugins/RPGQuest/quests/` **non touché** : il contient légitimement 3 fixtures `test_*.yml`
(scénario `story_test` du `MANUAL_TEST_PLAN`, dossier lu par le moteur de quêtes).

### Action serveur

- Suppression FTP de `plugins/RPGQuest/dialogues/{test_break_block,test_collect_item,test_craft_item,test_kill_entity,test_place_block,test_reach_location,test_talk_to_npc}.yml`.
- **Aucun remplacement de JAR** (aucun code modifié).
- Redémarrage serveur (`scripts/verygames-restart.sh` — `stop` RCON → relance auto) : seul
  mécanisme qui recharge le dossier `dialogues/` (pas de reload à chaud dédié).
- Aucun autre fichier. Aucune migration. Control Panel AWS non redéployé.

### Sauvegarde préalable

Téléchargement FTPS des 7 fichiers avant retrait →
`~/.local/share/rpgquest/verygames-backups/issue-83-dialogues-20260908T154228Z/`
(tailles + SHA-256 dans le rapport).

### Déploiement

1. Backup FTPS des 7 `test_*.yml`.
2. `DELE` FTP des 7 fichiers dans `RPGQuest/dialogues/` (garde-fou : le nom doit matcher
   `test_*.yml`).
3. `scripts/verygames-restart.sh`.

### Validation

Session du 2026-09-08 (~15:42–15:47 UTC). Branche `feat/control-panel-admin-tools` @ `ceddc30`.

- `RPGQuest/dialogues/` après retrait : `guard.yml guide.yml help.yml jeff.yml jo.yml
  junior.yml libraire.yml` (7 dialogues gameplay, 0 `test_*.yml`).
- `/plugins` (RCON) : `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ;
  `rpgquest version` → `v0.1.0-SNAPSHOT`. Heartbeat agent `2026-09-08T15:46:54Z` (`ONLINE`,
  `0.1.0-SNAPSHOT`) ; **0 `ERROR`** dans `journalctl -u plugadmin`.
- `dialogue.list` (action agent injectée) → **SUCCESS**, `value=7` : « 7 dialogue(s)
  (4 avec avertissement, **0 fichier(s) rejeté(s)**) », `nodeTotal=22`.
  - **`loadIssues` : 0** (était 14) — les 14 erreurs des `test_*.yml` ont disparu.
  - 7 dialogues gameplay chargés (`guide`, `help`, `jeff`, `jo`, `guard`, `junior`,
    `libraire`) ; 4 × `DIALOGUE_NO_NPC` **préexistants** (PNJ Citizens sans `NpcDefinition`,
    cohérent avec #81 phase 1) ; **aucun nouveau warning**. `declaredButMissing: []`.
- `npc.list` → toujours **SUCCESS** (8 PNJ) — inchangé.
- Vue navigateur authentifiée `/dialogues` : `PENDING MANUAL VALIDATION`.

### Rollback

Restaurer les 7 fichiers dans `plugins/RPGQuest/dialogues/` depuis
`~/.local/share/rpgquest/verygames-backups/issue-83-dialogues-20260908T154228Z/` (upload FTPS),
puis `scripts/verygames-restart.sh`. Aucune migration à défaire, aucun JAR à restaurer.

Rapport : `docs/claude-reports/2026-09-08_1547_nettoyage-fixtures-test-dialogues-dev-83.md`.

---

## 2026-09-08 - Éditeur guidé /dialogues — phase 1 (#82) : dialogue.node.* / dialogue.choice.*

### Changement

Cinq nouvelles mutations agent (`DIALOGUE_WRITE`, `confirm` obligatoire côté panel, audit,
whitelist `AgentActionType` ↔ `AgentActionCatalog`) pour **éditer un dialogue déjà chargé** —
périmètre restreint et sûr :

- `dialogue.node.update` — locuteur + texte d'un nœud (choix / conditions / actions conservés) ;
- `dialogue.node.create` — nœud simple (locuteur + texte + un choix « fermer »), **orphelin** ;
- `dialogue.choice.add` / `dialogue.choice.update` / `dialogue.choice.delete` — **choix simple**
  uniquement (aucune condition, aucune action hors `CLOSE`) : redirige vers un nœud existant
  **ou** ferme le dialogue ; `delete` refuse le dernier choix d'un nœud.

`DialogueDefinitionEditor` (nouveau) : localise le fichier par son `id`, refuse un fichier déjà
invalide, applique la mutation sur le modèle métier, **re-sérialise le dialogue complet**
(`DialogueDefinitionWriter` — 10 actions + 8 conditions + négation, aucune perte), **re-parse en
mémoire + exige l'égalité sémantique** (garde-fou round-trip), **écrit atomiquement**, **recharge
tout le dossier** puis **restaure le contenu d'origine** si le fichier ne recharge pas. Le
fichier édité adopte le **format canonique** du panel (commentaires / mise en forme d'origine
non conservés — choix assumé ; l'intégrité fonctionnelle est garantie par le round-trip).

Page `/dialogues` **refondue** (4 blocs : en-tête + état, résumé, diagnostics triés
erreur→attention→info, graphe de cartes nœud) avec l'édition guidée en `<details>` semi-inline
par nœud (aucun JS, conforme CSP). **Aucune migration SQL. `data.db` jamais touché.**

### Action serveur

- **Remplacement du seul JAR RPGQuest** (pour que l'agent connaisse les 5 nouveaux types).
- Redémarrage serveur (`scripts/verygames-restart.sh` — `stop` RCON → relance auto).
- Control Panel AWS redéployé (`scripts/plugadmin/deploy.sh`).
- Aucun autre fichier. Aucune migration.

### Sauvegarde préalable

- Ancien JAR : sauvegardé automatiquement par `deploy-verygames.sh`.
- **Recommandé avant toute mutation réelle** : sauvegarde FTPS du dossier
  `plugins/RPGQuest/dialogues/` (l'éditeur restaure le contenu d'origine en cas d'échec de
  rechargement, mais un backup hors-ligne reste la garantie).

### Déploiement

1. `scripts/deploy-verygames.sh -y` (JAR seul) puis `scripts/verygames-restart.sh`.
2. `scripts/plugadmin/deploy.sh` (AWS).

### Validation

- `/plugins` (RCON) : RPGQuest en vert ; `rpgquest version` répond.
- Heartbeat agent reçu ; aucun `ERROR` dans `journalctl -u plugadmin`.
- `dialogue.list` → **SUCCESS** ; `npc.list` toujours **SUCCESS**.
- Si exécutée : une mutation `dialogue.node.update` / `dialogue.choice.add` sur un **dialogue de
  test sûr** (jamais un dialogue gameplay pour la seule démonstration) → **SUCCESS**, dialogue
  toujours chargé, aucune régression sur les autres dialogues.

### Rollback

- VeryGames : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`. Un
  dialogue édité en réel : restaurer son `.yml` depuis le backup puis redémarrage.
- AWS : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~16:52–17:08 UTC). Branche `feat/control-panel-admin-tools` @ `171849b`.

- **AWS** : `scripts/plugadmin/deploy.sh` — OK (release
  `/opt/plugadmin/releases/20260908-165221`). `/health` public **ONLINE** ×3 ; `/dialogues`
  anon → **303** ; `dig.lodygames.com` / `lodylands.com` → **200**. Les 5 nouveaux types
  présents dans `AgentActionCatalog.class` du JAR déployé.
- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar`
  1 427 111 o, SHA-256
  `12f66391be6ca65fc694095b21cc3a603c779187026ecde189392e6b61b3788d` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T165252Z-predeploy.jar`
  (SHA-256 `57bda61b54ea726e957a4d37079cd4aa85644e73a865c31d027c5472c2733b42`).
  `scripts/verygames-restart.sh` → OFFLINE → relance auto → **ONLINE** ; `/plugins` (RCON) :
  `Citizens, Multiverse-Core, RPGQuest, WorldEdit` verts ; heartbeat agent **ONLINE**.
- Baseline : `dialogue.list` → **SUCCESS** (7 dialogues, `nodeTotal=22`, `loadIssues=0`) ;
  `npc.list` → **SUCCESS** (8 PNJ).
- **Mutations exercées en réel** sur un dialogue de test sûr `rpgquest:panel_edit_probe` (créé
  via `dialogue.definition.create`, jamais un dialogue gameplay) : `dialogue.node.update`,
  `dialogue.node.create`, `dialogue.choice.add`, `dialogue.choice.update`,
  `dialogue.choice.delete` → **tous SUCCESS `UPDATED`**. Garde-fous : suppression du dernier
  choix d'un nœud → **FAILED `LAST_CHOICE`** ; `node.update` sur un nœud inexistant → **FAILED
  `UNKNOWN_NODE`**.
- `dialogue.list` après édition : **SUCCESS**, `loadIssues=0`, les **7 dialogues gameplay
  inchangés** (compteurs + warnings identiques à la baseline).
- **Nettoyage** : `panel_edit_probe.yml` supprimé de `plugins/RPGQuest/dialogues/` via FTP.
  `verygames-restart.sh` : VeryGames a tardé ~6 min à relancer (aléa hébergeur, > délai 180 s du
  script), serveur **revenu de lui-même à 17:06 UTC**. État final : `/plugins` verts,
  `dialogue.list` → **SUCCESS** 7 dialogues `loadIssues=0`, `npc.list` → **SUCCESS** 8 PNJ,
  `journalctl -u plugadmin` **0 `ERROR`**. DEV revenu à son état d'avant-tâche + nouveau JAR.
- Vue navigateur `/dialogues` authentifiée (rendu 4 blocs, formulaires d'édition) =
  `PENDING MANUAL VALIDATION`.

Rappel : `dialogue.list` reste **SUCCESS**, `npc.list` **inchangé**, aucune progression joueur
touchée, aucune migration.

Rapport : `docs/claude-reports/2026-09-08_1642_dialogues-editeur-guide-phase1-82.md`.

---

## 2026-09-08 - #87 : reconnexion depuis le Wild ne renvoie plus au Hub

### Changement

**Code plugin uniquement. Aucune migration. `data.db` / `config.yml` / `spawn.yml` / mondes non
touchés.**

Un joueur non-OP déconnecté dans `wild` se reconnectait au spawn du village (`world_hub`).
**Cause** : `SpawnPlayerListener` traitait comme « nouveau joueur » tout joueur dont
`Player#hasPlayedBefore()` renvoie `false`, puis `SpawnService` redirigeait sa position
d'arrivée vers `spawn.yml`. Or `hasPlayedBefore()` renvoie `false` à tort pour un joueur déjà
venu quand la métadonnée Bukkit `bukkit.firstPlayed` de son `playerdata` manque
(migration/transfert de serveur, UUID hors-ligne↔en-ligne, restauration de sauvegarde).

**Correction** : `spawn.JoinSpawnPolicy` (règle pure) ne redirige vers le village que si un
nouveau joueur (`!hasPlayedBefore()`) est placé par Paper dans le **monde principal**
(`getWorlds().get(0)`) **ou** le **monde Hub** (`hub.world`). Sinon la position restaurée par
Paper est conservée — une reconnexion depuis `wild` reste dans `wild`. Onboarding et repli
« monde disparu » (Paper renvoie au monde principal → redirection village) inchangés. Respawn
après mort (`SpawnService.handleRespawn`) inchangé. Nouveau log par connexion
`join_restore player=<uuid> world=<w> action=KEEP_LAST_LOCATION|REDIRECT_TO_VILLAGE_SPAWN`.

### Action serveur

- **Remplacement du seul JAR RPGQuest**.
- **Un seul** redémarrage (`scripts/verygames-restart.sh`) — ne pas enchaîner (protection
  anti-boucle VeryGames : 10 auto-reboots en 30 min → relance manuelle panel).
- Aucun autre fichier. Aucune migration. Control Panel AWS non concerné.

### Sauvegarde préalable

Ancien JAR sauvegardé automatiquement par `deploy-verygames.sh`.

### Déploiement

1. `scripts/deploy-verygames.sh -y` puis `scripts/verygames-restart.sh` (une fois).

### Validation

- `/plugins` (RCON) : RPGQuest vert ; heartbeat agent `ONLINE` ; `dialogue.list` / `npc.list`
  toujours `SUCCESS`.
- **Manuel (owner, client réel)** : `LoDyMcFly` non-OP entre dans le Wild, relève sa position,
  se déconnecte/reconnecte ×2 → `world` toujours `wild`, position identique ou très proche,
  aucun passage Hub. Non-régression : déconnexion dans le Hub → retour Hub ; nouveau compte →
  spawn du village.

### Rollback

`scripts/rollback-verygames.sh --latest` puis un seul `scripts/verygames-restart.sh`. Aucune
migration à défaire.

### Exécution réelle

Session du 2026-09-08 (~19:14–19:18 UTC). Branche `feat/control-panel-admin-tools` @ `4006095`.

- `scripts/deploy-verygames.sh -y` — JAR `rpgquest-0.1.0-SNAPSHOT.jar` 1 429 560 o, SHA-256
  `d9a47cf868991dae8f6a072856f1f0519e87fd201cfc63388c486e4b0ba9f7ee` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260908T191434Z-predeploy.jar`
  (SHA-256 `12f66391…`).
- `scripts/verygames-restart.sh` **une fois** → OFFLINE → relance auto → **ONLINE** (aucune
  protection anti-boucle déclenchée ; serveur stable depuis ~2 h avant le restart). `/plugins`
  RPGQuest + Citizens + Multiverse + WorldEdit **verts** ; `rpgquest version` → `v0.1.0-SNAPSHOT` ;
  heartbeat agent **ONLINE** ; mondes `world_hub` / `claims` / `wild` `loaded:true`.
- Non-régression : `dialogue.list` → **SUCCESS** (7) ; `npc.list` → **SUCCESS** (8) ;
  `journalctl -u plugadmin` **0 `ERROR`**.
- **Test manuel en jeu #87** (owner, non-OP) : `PENDING MANUAL VALIDATION` — entrer dans le Wild,
  relever la position, se déconnecter/reconnecter ×2 → `world` doit rester `wild`, position
  identique ou très proche, aucun passage Hub ; log serveur `join_restore … world=wild
  action=KEEP_LAST_LOCATION`. Non-régression à confirmer : déconnexion dans le Hub → retour Hub ;
  nouveau compte → spawn du village.

Rapport : `docs/claude-reports/2026-09-08_1905_bug-wild-reconnexion-hub-87.md`.

---

## 2026-09-08 - Control Panel : centre de documentation /docs (#49) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Nouvelle section **Documentation** dans PlugAdmin : route `/docs` (authentifiée, permission
`DOCS_READ` accordée à tous les rôles), recherche plein texte en mémoire, rendu Markdown sûr,
catégories, commandes copiables. Contenu = 9 fiches Markdown versionnées et livrées dans le jar
(`control-panel/src/main/resources/docs/`), listées par `_index.txt` (liste blanche). Aucun
contenu en base ; aucun chemin du navigateur ouvert (slug interne résolu en mémoire).

### Action serveur

- `scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
- Aucune autre action.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` public **ONLINE**.
- `/docs` **anonyme** → **303** vers `/login` ; `/docs/<slug>` anonyme → **303**.
- `/docs` **authentifié** → 200 (recherche + catégories) ; `/docs?q=tag+npc` → fiche PNJ.
- Autres vhosts nginx (`dig.lodygames.com`, `lodylands.com`) → **200**.
- `journalctl -u plugadmin` : aucun `ERROR`.

### Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration.

### Exécution réelle

Session du 2026-09-08 (~20:10 UTC). Branche `feat/control-panel-admin-tools` @ `0516a0b`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260908-201035`). Jar : 9 `docs/*.md` +
`_index.txt` embarqués. `/health` ONLINE ×3 ; `/docs` anon → **303**, `/docs/pnj-citizens` anon
→ **303** ; `dig` / `lodylands` → **200** ; `journalctl -u plugadmin` **0 `ERROR`**. Navigateur
authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-08_2003_control-panel-centre-documentation-49.md`.

## 2026-09-09 - Control Panel : refonte UX généralisée /quests /stories /dialogues + diagnostics humains (#89 / #49) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

La philosophie de `/npcs` (liste = synthèse, clic = détail, clic action = formulaire) est
appliquée à `/quests`, `/stories`, `/dialogues` : chaque liste devient un accordéon Bootstrap,
le détail est en sections repliées, le graphe des dialogues n'est plus ouvert d'office.
Toolbars de rafraîchissement compactes (`btn-sm`) et recherche `input-group` partout.
`/players` : toolbar compacte + recherche au-delà de 6 joueurs.

Nouveau registre **`DiagnosticHelp`** : chaque avertissement/erreur (codes du moteur PNJ et
dialogues + vérifications de référence calculées côté panel — prérequis inconnu, donneur sans
fiche, quête absente d'une chaîne, fichier de dialogue rejeté, dialogue déclaré absent) est
rendu en français clair (problème → conséquence → action) avec un lien vers une ancre précise
du centre de documentation et le code technique en secondaire. Quatre nouvelles fiches
`/docs` : `pnj-depannage`, `dialogues-depannage`, `quetes-depannage`, `stories-depannage`
(embarquées dans le jar, whitelist `_index.txt`). `Markdown.slug` replie désormais les accents.

### Action serveur

- `scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
- Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : l'app précédente est déplacée sous
`/opt/plugadmin/releases/<horodatage>` (rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE**.
- `/npcs` `/dialogues` `/quests` `/stories` `/docs` `/players` anonymes → **303** vers `/login`.
- En-tête **CSP inchangé** (`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'
  data:; form-action 'self'; frame-ancestors 'none'`).
- `/assets/plugadmin.css` → **200** (nouveau hash `?v=…`).
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; `journalctl -u plugadmin` : **aucun `ERROR`**.
- Jar déployé : `DiagnosticHelp.class` + 4 `docs/*-depannage.md` + `_index.txt` embarqués.
- Rendu **authentifié** des accordéons /quests /stories /dialogues : `PENDING MANUAL VALIDATION`
  (couvert par la suite de tests `:control-panel:test`).

### Rollback

`scripts/plugadmin/rollback.sh app` (release précédente + restart). Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~09:53 UTC). Branche `feat/control-panel-admin-tools` @ `6835c15`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-095349`). `/health` ONLINE (local +
`plugadmin.lodylands.com`) ; 6 routes → **303** ; CSP inchangé ; `plugadmin.css?v=bedb0b2d`
→ 200 ; `dig` / `lodylands` → 200 ; `plugadmin.service` `active` `NRestarts=0` ;
`journalctl -u plugadmin` **0 `ERROR`**. Navigateur authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_0930_control-panel-ux-metier-diagnostics-89-49.md`.

## 2026-09-09 - Control Panel : polish #49/#89 (titres docs en double, bouton Copier, CTA dupliqué) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Correctifs visuels après validation de la nouvelle UX :

- **#49 — titre en double sur les fiches `/docs`** : `DocsPages` rend déjà le titre de fiche dans
  son propre `<h1>` ; `Markdown.render` ré-affichait le `# …` d'ouverture du corps. Nouveau
  `Markdown.stripLeadingH1()` : retire uniquement le premier titre s'il est de niveau 1 et en
  tête ; `##`/`###` et un éventuel second `#` plus bas sont conservés. Vérifié sur les 13 fiches.
- **#49 — bloc de code / bouton « Copier »** : le grand rectangle sombre vide venait d'un
  `<svg class="ic"><use href="#i-copy">` (sprite retiré à la migration Bootstrap #92) sans
  contenu, taille intrinsèque 300×150 dans le bouton. Bouton « Copier » = texte seul, compact,
  en tête du wrapper `.doc-cmd`. CSS : `.doc-copy` en `position:absolute` coin haut-droit,
  `.doc-cmd pre` garde `overflow-x:auto` + gouttière droite pour ne jamais passer sous le
  bouton ; ancienne règle de positionnement en double retirée.
- **#89 — action « Créer la définition » affichée deux fois** dans le détail d'un PNJ (diagnostic
  + section Actions) : règle CTA — une action déjà proposée par un « Corriger maintenant » de
  diagnostic n'est plus répétée dans « Actions » ; la section « Actions » disparaît si elle
  devient vide. Les formulaires masqués restent rendus (le bouton du diagnostic les ouvre).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente déplacée sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE**.
- `/docs` `/docs/pnj-citizens` `/npcs` `/quests` `/stories` `/dialogues` anonymes → **303** vers
  `/login`.
- En-tête **CSP inchangé**.
- CSS déployé : `.doc-copy{position:absolute;top:7px;right:7px;z-index:2;…}`,
  `.doc-cmd pre{…padding:14px 72px 14px 16px;overflow-x:auto}`, ancienne règle
  `.codeblock .doc-copy,.doc-cmd .doc-copy` **retirée**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** à un build frais
  (SHA-256 `955d5055…`).
- Rendu **authentifié** des fiches `/docs` (titre unique, bloc code propre) et du détail PNJ
  Guide (un seul « Créer la définition ») : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-101733`).
Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~10:17 UTC). Branche `feat/control-panel-admin-tools` @ `65cfb9a`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-101733`). `/health` ONLINE (local +
public) ; 6 routes → **303** ; CSP inchangé ; CSS `.doc-copy`/`.doc-cmd pre` corrigés servis,
ancienne règle absente ; `dig` / `lodylands` → 200 ; `plugadmin.service` `active` `NRestarts=0` ;
jar byte-identique au build (`955d5055…`). Un `WARNING event=handler_error path=/login
java.io.IOException: stream closed` observé = déconnexion client d'un `curl -I` (HEAD), bénin,
non lié au changement. Navigateur authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_1005_polish-docs-titres-codeblock-cta-49-89.md`.

## 2026-09-09 - Control Panel : fiche /docs des commandes réécrite (une section par commande) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

`control-panel/src/main/resources/docs/rpgquest-commandes.md` est réécrite : le gros bloc
fourre-tout (plusieurs familles de commandes derrière un seul bouton « Copier ») est remplacé
par une fiche structurée — table « En un coup d'œil » (Je veux… → commande), catégories `##`
(Joueurs / PNJ / Quêtes / Stories / Mondes / Portails & Waystones / Administration serveur /
Outils builder & avancés), puis **une section `###` par commande** : nom humain d'abord,
description, Syntaxe, Paramètres, Exemple(s), Résultat attendu, Permission / Attention.

**Chaque bloc de code = une seule commande exécutable → son propre bouton « Copier »** (78
blocs, 78 boutons). Contenu audité sur le code réel (`RpgAdminCommand`, `RPGQuestCommand`,
`QuestCommand`, `DialogueCommand`, `CustomItemCommand`). CSS : sections `###` en cartes légères
(bord gauche accent) + mini-libellés discrets. **Aucune modification du renderer Markdown.**

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE**.
- `/docs` `/docs/rpgquest-commandes` `/npcs` `/docs?q=npc+tag` anonymes → **303** vers `/login`.
- En-tête **CSP inchangé**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** au build
  (SHA-256 `f25c5633…`) ; `docs/rpgquest-commandes.md` embarqué (156 marqueurs ``` = 78 blocs).
- `:control-panel:test` **221/0** (dont `CommandReferenceSheetTest`) ; `DocSearchIndexTest`
  inchangé (fiches de tête #49 conservées).
- Rendu navigateur **authentifié** de la fiche : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-111244`).
Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~11:12 UTC). Branche `feat/control-panel-admin-tools` @ `577c4cd`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-111244`). `/health` ONLINE (local +
public) ; routes → **303** ; CSP inchangé ; `dig` / `lodylands` → 200 ; `plugadmin.service`
`active` `NRestarts=0` ; jar byte-identique (`f25c5633…`) ; fiche embarquée (78 blocs). Un
`WARNING event=handler_error path=/login java.io.IOException: stream closed` = `curl -I` (HEAD)
client qui ferme, bénin, non lié au changement. Navigateur authentifié : `PENDING MANUAL
VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_1104_fiche-commandes-reference-49.md`.

## 2026-09-09 - Control Panel : dashboard d'observabilité / page /diagnostics (#38) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Nouvelle page **`/diagnostics`** : centralise tous les diagnostics de cohérence RPGQuest (PNJ,
dialogues, quêtes, stories, serveur/agent, mondes) en un seul endroit trié (erreurs d'abord),
avec pour chaque problème : titre humain, domaine + ressource, conséquence, action, boutons
**Ouvrir** (lien profond vers la page métier qui déplie l'élément) / **Corriger maintenant**
(action déjà offerte par le panel — aucune correction auto) / **Comment corriger ?** (ancre doc
précise), et le code technique en dernier.

Nouveau paquet `panel.diag` (modèle `DiagnosticEntry`, providers Npc/Dialogue/Quest/Story/Server,
`DiagnosticsService` qui agrège / dédoublonne / trie). Lecture seule du dernier snapshot agent —
**aucune requête déclenchée** au chargement. Bouton « Actualiser les diagnostics » →
`POST /diagnostics/refresh` (CSRF, `DIAGNOSTICS_READ`) qui enqueue en **une** action les 4
relevés `*.list` nécessaires ; feedback toast (#93). Tuile Home + section Dashboard mises à jour.
Nouvelle fiche `/docs/serveur-depannage` + section Citizens dans `/docs/pnj-depannage`.
`panel.js` : filtres à plusieurs groupes de puces combinables (rétro-compatible).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local + `https://plugadmin.lodylands.com/health` : **ONLINE**.
- `/home` `/dashboard` `/diagnostics` `/npcs` `/dialogues` `/quests` `/stories` `/docs`
  `/docs/serveur-depannage` anonymes → **303** vers `/login` ; `POST /diagnostics/refresh`
  anonyme → **303**.
- En-tête **CSP inchangé**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; **aucun `ERROR`** au journal.
- Jar déployé **byte-identique** au build (SHA-256 `c5897313…`) ; 17 classes `panel/diag/`
  + `serveur-depannage.md` embarqués.
- `:control-panel:test` **235/0** (`DiagnosticsServiceTest` + `DiagnosticsPageTest`) ;
  `:test` inchangé ; `./gradlew build` vert.
- Validation navigateur **authentifiée** (11 points de l'énoncé) : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-120245`).
Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~12:02 UTC). Branche `feat/control-panel-admin-tools` @ `6428f82`.
`deploy.sh` OK (release `/opt/plugadmin/releases/20260909-120245`). `/health` ONLINE (local +
public) ; routes → **303** ; `POST /diagnostics/refresh` anon → 303 ; CSP inchangé ; `dig` /
`lodylands` → 200 ; `plugadmin.service` `active` `NRestarts=0` ; **0 `ERROR`** ; jar
byte-identique (`c5897313…`) ; 17 classes `panel/diag/` + fiche `serveur-depannage.md`.
Navigateur authentifié : `PENDING MANUAL VALIDATION`.

Rapport : `docs/claude-reports/2026-09-09_1139_dashboard-observabilite-diagnostics-38.md`.

---

## 2026-09-09 - Control Panel : fix /npcs — PNJ Citizens réel masqué (#101) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Un PNJ créé directement dans Citizens en jeu (`/npc create …`), sans fiche RPGQuest ni liaison,
n'apparaissait dans aucune ligne de la page **PNJ** : la liste ne venait que de `npc.list`
(`NpcCatalog` = définitions + liaisons + références de contenu), qui ne lit pas le registre
Citizens. La ligne de synthèse « Citizens : N » le comptait pourtant. Ni le registre Citizens
runtime, ni l'action agent `npc.citizens.list` (qui renvoie bien tous les PNJ, libres inclus —
vérifié sur le payload DEV réel : `#7 Stan`, `linkedNpcId=null`), ni le stockage n'étaient en
cause — uniquement le rendu du Control Panel.

`AgentPages.npcs()` raccroche désormais chaque PNJ de `npc.citizens.list` sans `linkedNpcId`
comme une ligne d'identité physique : libellé = nom en jeu, sous-titre `Citizens #N`, badges
« sans fiche RPGQuest » + « non lié », filtre *Non liés*, recherche par nom / id numérique /
UUID ; détail « Identité Citizens » (numéro, UUID, spawné) ; actions facultatives *Créer une
fiche RPGQuest* et *Lier à une fiche existante* (liaison inverse `npc.citizens.link`). Nouvel
état d'affichage `CITIZENS_ONLY` (information, jamais erreur) + entrée `DiagnosticHelp` + section
« PNJ du jeu sans fiche RPGQuest » dans `/docs/pnj-depannage`. `NpcDiagnosticProvider` émet un
INFO `CITIZENS_ONLY` par PNJ Citizens libre sur `/diagnostics`. Le compteur `/npcs` inclut les
PNJ Citizens libres.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché.

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local + `https://plugadmin.lodylands.com/health` : **ONLINE**.
- `/home` `/npcs` `/diagnostics` `/docs/pnj-depannage` anonymes → **303** vers `/login`.
- En-tête **CSP inchangé**.
- `dig.lodygames.com` et `lodylands.com` → **200** (inchangés).
- `plugadmin.service` `active`, `NRestarts=0` ; **aucun `ERROR`** au journal.
- `:control-panel:test` **237/0** (`NpcsCatalogTest` 14, `DiagnosticHelpTest` 7) ; `:test`
  **0 échec / 0 erreur** (`NpcCitizensPayloadTest` 7) ; `./gradlew build` vert.
- Validation live `/npcs` (Stan visible, `Citizens #7`, non lié) + navigateur authentifié :
  `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Session du 2026-09-09 (~12:50 UTC). Branche `feat/control-panel-admin-tools` @ `5c7d333`.
`deploy.sh --no-build` OK (release `/opt/plugadmin/releases/20260909-125035`). Jar déployé
**byte-identique** au build (SHA-256 `553004d4…`) ; `renderFreeCitizensAccordionItem` +
`citizensInverseLinkForm` présents dans le bytecode, `pnj-depannage.md` (section « PNJ du jeu
sans fiche RPGQuest ») embarquée. `/health` ONLINE local + `https://plugadmin.lodylands.com` ;
`/home` `/npcs` `/diagnostics` `/docs/pnj-depannage` anon → **303** ; **CSP inchangée**
(`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action
'self'; frame-ancestors 'none'`) ; `dig.lodygames.com` + `lodylands.com` → **200** ;
`plugadmin.service` `active` `NRestarts=0` ; **0 `ERROR`** au journal.
Un relevé `npc.citizens.list` frais enfilé pour validation live est resté **DELIVERED** sans
résultat (exécuteur de l'agent DEV VeryGames — **hors périmètre de ce changement**, agent non
modifié). Preuve agent = payload DEV réel du 2026-09-09 11:33 (`#7 Stan`, `linkedNpcId:null`).
Rendu `/npcs` authentifié : `PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

---

## 2026-09-09 - Control Panel : hotfix #103 — 502 Bad Gateway après connexion (durcissement AgentStore) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun agent, aucune migration, aucun impact
serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Après le déploiement #101 (release `20260909-125035`), `/login` fonctionnait mais toute page
authentifiée (`/home`, `/npcs`, `/dashboard`, …) renvoyait **502 Bad Gateway**.

**Cause racine — ce n'est PAS une régression du code #101.** Pendant la « validation live » de
#101, une ligne `agent_action` a été insérée directement en base (sonde `npc.citizens.list`)
avec `created_at = '2026-09-09T12:51:33'` — **sans « Z »**, car écrite par
`strftime('%Y-%m-%dT%H:%M:%S','now')` au lieu du `Instant` ISO de PlugAdmin. `AgentStore.readAction`
faisait `Instant.parse(created_at)` sans tolérance → `DateTimeParseException`. L'exception
remontait par `NotificationCenter` (cloche de la topbar, rendue sur **toute** page authentifiée)
→ HTTP 500 → 502 nginx. `/login` n'affiche pas la cloche, d'où l'insuffisance du smoke anonyme.

**Correctif (déployé) — `AgentStore` :** analyse d'horodatage tolérante (`parseTimestamp` : ISO
canonique + formes héritées sans décalage, interprétées UTC ; illisible → `null` + WARNING) ;
`created_at`/`received_at` → `Instant.EPOCH` en dernier recours (jamais d'exception) ; **frontière
de sécurité par ligne** dans `recentActions` (une ligne illisible est ignorée + WARNING, jamais
propagée). Une donnée agent invalide ⇒ diagnostic, jamais crash du serveur web.

**Correctif de données (déjà appliqué en prod avant le redéploiement) :** la ligne fautive
(`agent_action` id `0cd8dd38…`, `created_by='claude-101-validation'`, statut `EXPIRED`) a vu son
`created_at` réparé `'2026-09-09T12:51:33'` → `'2026-09-09T12:51:33Z'` (UPDATE ciblé, aucune
suppression). Vérifié : 0 ligne au `created_at` sans « Z » dans `control-panel.db`.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — release + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret. **Réparation de données déjà faite.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). `control-panel.db` non touché par le déploiement (réparation de la ligne faite
séparément, UPDATE ciblé, valeur d'origine consignée dans le rapport).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `:control-panel:test` **241** (0 échec ; 1 ignoré = smoke prod-copy, exécuté à la demande) —
  `PanelHardeningMalformedAgentDataTest` : connexion owner réelle + `/home /dashboard /npcs
  /diagnostics /docs /actions` = **200** avec la ligne exacte de #103 ; **vérifié que le test
  échoue (EOFException = 502) sans le correctif**. `:test` plugin inchangé. `./gradlew build` vert.
- **Smoke authentifié contre une COPIE de la base de prod réelle** (`-DpanelProdDbCopy`) :
  connexion owner + `/home /dashboard /npcs /diagnostics /docs /actions /agents /players /quests
  /stories /dialogues` = **200** ; Stan (#101) toujours visible ; ligne mal formée réinjectée →
  `/home` reste 200.
- Prod : `/health` ONLINE local + `https://plugadmin.lodylands.com` ; `plugadmin.service`
  `active` `NRestarts=0` `ExecMainStatus=0` ; **0 `ERROR`** au journal depuis le redéploiement ;
  jar déployé **byte-identique** au build (SHA-256 `9a21828f…`), `parseTimestamp` présent dans
  le bytecode ; `control-panel.db` = 79 lignes `agent_action`, **0** au `created_at` sans « Z ».
- Navigateur authentifié sur le service de prod : dernier point à confirmer par l'owner (mot de
  passe non détenu) — équivalent couvert par le smoke authentifié sur copie de prod.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure `/opt/plugadmin/releases/20260909-125035` = #101
sans le durcissement). ⚠️ Avec ce rollback, réparer aussi toute future ligne `created_at` sans
« Z » en base, sinon le 502 revient — le durcissement est la vraie protection. Aucune migration
à défaire.

### Exécution réelle

Session du 2026-09-09 (~13:16 UTC). Branche `feat/control-panel-admin-tools` @ `aa6270b`
(fix) / `c92ae3f` (test infra). `deploy.sh --no-build` OK (release
`/opt/plugadmin/releases/20260909-131643`, PID 480917). `/health` ONLINE local + public ;
`/login` → 200 ; `/home` `/npcs` `/diagnostics` `/dashboard` anon → 303 ; `plugadmin.service`
`active` `NRestarts=0` ; **0 `ERROR`** ; jar `9a21828f51e71e8784223bd2f8e549dbd0d09bf22c48e5fbc13ad0f8d608983c`.
Réparation de la ligne `0cd8dd38…` faite à ~13:09 UTC (avant redéploiement) — panel déjà
re-servi à ce moment ; le redéploiement apporte la protection permanente.

---

## 2026-09-09 - #96 : annuaire des joueurs /players (player.catalog + ban/unban) — plugin VeryGames + Control Panel AWS

### Changement

**Ce changement touche le plugin RPGQuest (agent) ET le Control Panel.**

- **Plugin/agent** : trois nouvelles actions agent, aucune commande console, aucun SQL, aucune
  migration :
  - `player.catalog` — annuaire complet : joueurs **connectés** + joueurs **hors ligne déjà
    venus** (source = serveur Paper `OfflinePlayer` ; instantané des connectés sur le thread
    principal puis `getOfflinePlayers()` sur un thread **asynchrone** Bukkit — jamais de lecture
    disque sur le main). Champs : `uuid`, `name`, `online`, `hasPlayedBefore`, `firstPlayed`,
    `lastSeen`, `banned` (+ `banReason`), `world`/`x`/`y`/`z` si en ligne. Cap de sécurité 5000
    (demande sans limite → `truncated=true`).
  - `player.ban` / `player.unban` — via l'API publique `BanList` de **profil** Paper. Fonctionnent
    **en ligne comme hors ligne** ; expulsion (`kick`) si le joueur est connecté ; **raison
    obligatoire** pour le ban (stockée telle quelle, jamais une commande) ; idempotents
    (`ALREADY_BANNED` / `NOT_BANNED`). Cible résolue en **UUID canonique** par le
    `PlayerDirectory` existant avant toute écriture.
- **Control Panel** : page `/players` réécrite en annuaire (recherche pseudo/UUID, filtres
  Tous/En ligne/Hors ligne/Bannis, tri, pagination 50 — tout côté serveur), détail en accordion
  (Identité / Activité / RPGQuest / Droits / Modération / Actions), ban/unban avec confirmation
  forte + raison obligatoire, permission `PLAYER_MODERATE`. « Donner un objet » reste **en ligne
  uniquement** (indisponible expliqué hors ligne). Fiche `/docs/joueurs-admin`.
- **Droit de construction persistant : NON livré** — bloqué par #27 (permissions granulaires
  non fusionnées sur cette branche + pas de store de grant persistant). La fiche affiche
  « non géré », sans faux interrupteur.

### Action serveur

1. **VeryGames DEV** : remplacer **uniquement le JAR RPGQuest** (`scripts/deploy-verygames.sh -y`,
   backup daté auto). Aucun autre fichier. **Ne pas** toucher `data.db`, config, mondes, Citizens.
2. **Un seul** redémarrage : `scripts/verygames-restart.sh` (stop RCON → relance auto VeryGames).
   Vérifier le retour **ONLINE**. Respecter la règle anti-auto-reboot (10 reboots / 30 min → STOP,
   pas de retry, attendre relance manuelle).
3. **Control Panel AWS** : `scripts/plugadmin/deploy.sh` (release + `systemctl restart plugadmin`
   + `/health`).

### Sauvegarde préalable

- VeryGames : backup JAR daté automatique par `deploy-verygames.sh`
  (`~/.local/share/rpgquest/verygames-backups/…-predeploy.jar`).
- AWS : release précédente sous `/opt/plugadmin/releases/<horodatage>` (rétention 5).
- Aucune donnée (`data.db`, `control-panel.db`) touchée.

### Déploiement

```
scripts/deploy-verygames.sh -y
scripts/verygames-restart.sh
scripts/plugadmin/deploy.sh
```

### Validation

- `:test` **1214/0** (29 ignorés MockBukkit, inchangés) — `AgentActionExecutorTest` +5
  (`player.catalog` compteurs + cap ; `player.ban` exige la raison + résout l'UUID ;
  `player.unban`). `:control-panel:test` **257/0** — `PlayerCatalogTest` (8),
  `PlayersPageTest` (8, HTTP authentifié). `./gradlew build` vert.
- VeryGames : `/plugins` (RCON) verts après restart ; heartbeat agent **ONLINE** ;
  `player.catalog` déclenché depuis PlugAdmin → **SUCCESS** avec au moins le joueur de test
  connecté + des joueurs hors ligne ; `journalctl -u plugadmin` **0 `ERROR`**.
- AWS : `/health` ONLINE local + public ; `/players` (session de test) rend l'annuaire ;
  `plugadmin.service` `active` `NRestarts=0`.
- **ban/unban en réel** : uniquement sur une cible de test sûre si disponible ; sinon
  `PENDING MANUAL VALIDATION` (ne jamais bannir l'owner sans rollback sûr).
- Navigateur authentifié `/players` par l'owner : `PENDING OWNER`.

### Rollback

- Plugin : `scripts/rollback-verygames.sh --latest` puis `scripts/verygames-restart.sh`.
- Control Panel : `scripts/plugadmin/rollback.sh app`.
- Aucune migration à défaire. Un joueur banni pendant la validation doit être débanni
  (`player.unban` ou `/pardon <uuid>` console) — les bans ne sont pas annulés par le rollback JAR.

### Exécution réelle

Session du 2026-09-09 (~14:15–14:30 UTC). Branche `feat/control-panel-admin-tools` @ `faf87ec`.

- **VeryGames DEV** : `scripts/deploy-verygames.sh -y` — `./gradlew test` + `build` **OK** (gate
  interne), JAR `rpgquest-0.1.0-SNAPSHOT.jar` **1 437 876 o**, SHA-256
  `4f39e1e41e6860921a38b9c5893f5f56b95a0fac7fc1500d321ca2b64316765c` ; backup auto
  `~/.local/share/rpgquest/verygames-backups/rpgquest-20260909T142659Z-predeploy.jar`
  (SHA-256 `d9a47cf868991dae8f6a072856f1f0519e87fd201cfc63388c486e4b0ba9f7ee`) ; téléversement
  atomique, JAR en ligne == local.
- **Un seul** redémarrage : `scripts/verygames-restart.sh` → save-all → stop RCON → **OFFLINE** →
  relance auto VeryGames → **ONLINE** (< 180 s, aucun message anti-auto-reboot). `/plugins` (RCON)
  = `Citizens, Multiverse-Core, RPGQuest, WorldEdit` **tous verts** ; `/rpgquest version` répond
  `v0.1.0-SNAPSHOT`.
- **Control Panel AWS** : `scripts/plugadmin/deploy.sh` — release
  `/opt/plugadmin/releases/20260909-141707`, jar **byte-identique** au build (SHA-256
  `ee72425ae36d87b3a52bfc54c5f214aed7eb44580b1f240fb45060df040507dc`) ; `PlayerCatalog` +
  `joueurs-admin.md` embarqués. `/health` ONLINE local + `https://plugadmin.lodylands.com` ;
  `/players` `/home` `/npcs` anon → **303** ; `/login` → 200 ; **CSP inchangée** ;
  `dig.lodygames.com` + `lodylands.com` → **200** ; `plugadmin.service` `active` `NRestarts=0`
  `ExecMainStatus=0` ; **0 `ERROR`** depuis le redéploiement (un `WARNING handler_error
  path=/login` isolé = `curl -I` HEAD qui ferme, bénin).
- **Validation live agent** (`player.catalog` enfilé, `created_by='claude-96-validation'`) →
  **SUCCESS** : `total=3`, `online=0`, `offline=3`, `banned=0`, `truncated=false` — 3 joueurs
  réels déjà venus (`Rondoudou9000`, `Madix666`, `LoDyMcFly`) avec UUID + `firstPlayed` /
  `lastSeen` crédibles (LoDyMcFly vu le 2026-09-08, le plus récent). Aucun joueur connecté sur
  DEV au moment du test → pas de monde/position (attendu). `npc.list` baseline → **SUCCESS
  inchangé** (8 PNJ, 7 avec avertissement).
- **Smoke authentifié** (`-DpanelProdDbCopy` = copie de la vraie `control-panel.db` après le
  relevé) : `/players` (+ `/home /dashboard /npcs /diagnostics /docs /actions …`) = **200**
  authentifié sur le payload `player.catalog` réel.
- **ban / unban en réel** : `PENDING MANUAL VALIDATION` — les 3 joueurs DEV ne sont pas des cibles
  de test sûres (l'owner `LoDyMcFly` ne doit pas être banni ; `Rondoudou9000` / `Madix666` sans
  autorisation). Logique couverte par les tests unitaires + HTTP.
- **Navigateur authentifié `/players`** par l'owner : `PENDING OWNER` (mot de passe non détenu).

Rappel : `npc.list` reste **SUCCESS** (8 PNJ inchangés), aucune progression joueur touchée,
aucune migration, `data.db` / `control-panel.db` non modifiés. Rollback plugin :
`scripts/rollback-verygames.sh --latest` + `verygames-restart.sh` ; rollback CP :
`scripts/plugadmin/rollback.sh app` (→ `20260909-131643`).

Rapport : `docs/claude-reports/2026-09-09_1400_players-annuaire-offline-96.md`.

## 2026-09-09 - Control Panel : passe UX éditeur de quêtes (#46) — AWS uniquement

### Changement

**Control Panel AWS uniquement. Aucun code plugin, aucun code agent, aucune migration,
aucun impact serveur Minecraft — ne pas redéployer/redémarrer VeryGames.**

Passe UX ciblée sur l'éditeur guidé `/quests/new` (issue #46) :

- un type d'objectif / récompense pilote seul ses champs (descripteur unique) ; le
  changement de type est immédiat (JS progressif) et efface les valeurs du type
  précédent ; sans JavaScript, le serveur re-rend au premier aller-retour ;
- les actions de brouillon (ajouter / supprimer / réordonner) ne déclenchent plus la
  validation HTML `required` (`formnovalidate`) ; la validation métier n'a lieu qu'à
  « Vérifier » / « Enregistrer » ;
- ids stables + `formaction=".../save#ancre"` → la page se recale sur le composant
  concerné après chaque action ;
- listes recherchables (`panel.js`, composant local, aucun CDN) pour entité / matériau /
  PNJ / icône / catégorie ; la catégorie vient d'une liste curée + saisie libre.

Fichiers : `panel/content/{Descriptors,RefData}.java`, `panel/web/ContentEditorPages.java`,
`assets/panel.js`, `assets/plugadmin.css`, `resources/docs/quetes.md`. **CSP inchangée**
(`default-src 'self'`).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous
`/opt/plugadmin/releases/<horodatage>` + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base. `control-panel.db`
non touché.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE** ;
- `/quests/new`, `/quests/edit/<slug>`, `/stories/new`, `/quests/save` anonymes → **303**
  vers `/login` ;
- en-tête **CSP inchangée** ;
- `plugadmin.service` `active`, `NRestarts=0` ; jar déployé **byte-identique** au build ;
- `:control-panel:test` **271/0** ; `./gradlew build` vert (plugin 1214/0, web-api 30/0) ;
- rendu navigateur **authentifié** de l'éditeur : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à
défaire.

### Exécution réelle

Session du 2026-09-09 (~16:17 UTC). Branche `feat/control-panel-admin-tools` @ `3c5228c`.
`scripts/plugadmin/deploy.sh` OK : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava`/`jar` **UP-TO-DATE** — distribution issue du build déjà testé), release
précédente sous `/opt/plugadmin/releases/20260909-161744`, `systemctl restart plugadmin` →
`active (running)` `NRestarts=0`. `/health` **ONLINE** local + public
(`https://plugadmin.lodylands.com/health`). `/quests/new` `/quests/save` `/stories/new` `/home`
anonymes → **303** `/login`. **CSP inchangée**
(`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'`).
Jar déployé **byte-identique** au build local (SHA-256 `24c141993222c066…`). `panel.js` servi
contient `initCombo`/`initEditorForms` ; `plugadmin.css?v=22a51c4f`. **0 ERROR** au journal
depuis le redéploiement ; un `WARNING handler_error path=/login stream closed` = `curl -I`
(HEAD) client qui ferme, bénin. **VeryGames / Minecraft non touchés.** Navigateur authentifié :
`PENDING MANUAL VALIDATION`.

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260909-161744`).

Rapport : `docs/claude-reports/2026-09-09_1607_editeur-quetes-passe-ux-46.md`.

---

## 2026-09-09 - PlugAdmin : resynchronisation auto après mutation + UX formulaires PNJ/Dialogues (issues #111 → #120)

### Changement

Control Panel uniquement — **aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration**.

- **Resynchronisation mutualisée après une mutation** (#112/#115/#116/#119/#120) : une
  mutation de contenu qui réussit (`npc.definition.*`, `npc.citizens.link/create`,
  `dialogue.*`, `quest.giver.set`, `player.ban/unban`) ré-enfile **automatiquement** les
  relevés `*.list` qu'elle périme (`AgentActionCatalog.Spec#refreshTypes()` →
  `AgentEndpoints`), `created_by = "auto"`, dédupliqués, jamais sur échec ni renvoi
  idempotent. Le centre de notifications masque ces lignes ; `panel.js` recharge **une seule
  fois** la page métier quand la file d'actions est retombée au repos. Plus besoin de
  « Rafraîchir catalogue + F5 ».
- **Confirmations** (#111/#113/#118) : `Spec#sensitive()` — l'édition de contenu réversible
  n'a plus de case à cocher cachée (phrase d'information + `confirm` implicite) ; la
  confirmation explicite ne reste que pour `player.ban/unban`, `player.resetnew.confirm`,
  `npc.citizens.create`, `dialogue.choice.delete`.
- **Formulaires** : aide métier sous chaque champ PNJ, exemples en `placeholder`, lien doc
  `target=_blank`. Page Dialogues : bouton primaire **+ Nouveau dialogue**, bandeau technique
  réduit à une phrase + `<details>`, **palette de couleurs MiniMessage** (14 teintes,
  pastilles + aperçu) qui génère `<couleur>…</couleur>` sans ré-enrober un MiniMessage saisi
  à la main.

Fichiers : `panel/agent/{AgentActionCatalog,AgentStore,AgentEndpoints}.java`,
`panel/web/{AgentPages,NotificationCenter,PanelApp,MiniText}.java`, `assets/panel.js`,
`assets/plugadmin.css`, `resources/docs/dialogues-depannage.md`. **CSP inchangée**
(`default-src 'self'`). `control-panel.db` non touché (aucune migration de schéma agent).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous
`/opt/plugadmin/releases/<horodatage>` + `systemctl restart plugadmin` + check `/health`.
Aucune autre action. Aucun changement nginx / TLS / secret / base. **VeryGames / Minecraft
non touchés.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **ONLINE** ; `https://plugadmin.lodylands.com/health` **ONLINE** ;
- `/npcs`, `/dialogues`, `/home` anonymes → **303** vers `/login` ;
- en-tête **CSP inchangée** ;
- `plugadmin.service` `active`, `NRestarts=0` ;
- `:control-panel:test` **279/0** (1 skip pré-existant) ; `./gradlew build` vert ;
- rendu navigateur **authentifié** (mutation PNJ + dialogue, liaison Citizens, absence de F5,
  mobile) : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à
défaire.

### Exécution réelle

Session du 2026-09-09 (~20:32 UTC). Branche `feat/control-panel-admin-tools` @ `dc17f23`.
`scripts/plugadmin/deploy.sh` OK : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava`/`jar` **UP-TO-DATE** — distribution issue du build déjà testé), release précédente
sauvegardée sous `/opt/plugadmin/releases/20260909-203227`, `systemctl restart plugadmin` →
`active (running)`, `NRestarts=0`, drop-in `10-content-workspace.conf` toujours chargé, `Memory`
~52 M. `/health` **ONLINE** local + public (`https://plugadmin.lodylands.com/health`).
`/home` `/npcs` `/dialogues` anonymes → **303** `/login`. **CSP inchangée**
(`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'`).
Jar déployé **byte-identique** au build local (SHA-256 `d3a2f34fbbfcf390…`). `panel.js` servi
publiquement contient `reloadPlan` / `sawPending` / `initColorPalette` ; le jar embarque le
`panel.js` à jour et la section « Comprendre l'éditeur de dialogues » de `dialogues-depannage.md`.
**0 `ERROR`/`SEVERE`** au journal depuis le redéploiement. `control-panel.db` non touché (aucune
migration de schéma agent). **VeryGames / Minecraft non touchés.** Navigateur authentifié
(mutation PNJ + dialogue, liaison Citizens, absence de F5, mobile) : `PENDING MANUAL VALIDATION`.

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260909-203227`).

Rapport : `docs/claude-reports/2026-09-09_2016_resync-mutations-ux-formulaires-controlpanel-111-120.md`.

---

## 2026-09-09 - Waypoints par instance de biome — MVP moteur (issue #124)

### Changement

Nouveau système **plugin RPGQuest** : package `com.lodygames.rpgquest.waypoint`. Repères
physiques persistants et partagés, générés **par instance réelle de biome** dans
`travel.wild-world` (par défaut `wild`), à découvrir par interaction explicite. **Distinct des
Waystones** (V17, réseau de voyage sur grille) — les deux systèmes cohabitent, waystone inchangé.

- **Nouvelle migration de schéma V18** : tables `waypoints` et `waypoint_discoveries` (+ index
  unique `idx_waypoints_instance (world, biome_instance)`). **Additive et idempotente** : aucun
  `ALTER` sur une table existante, `CREATE TABLE IF NOT EXISTS`. `SchemaMigrator.CURRENT_VERSION`
  passe de 17 à 18 ; appliquée automatiquement au démarrage par `SchemaMigrationRunner`.
- **Nouvelle section de configuration** `travel.waypoint.*` dans `config.yml`
  (`enabled`, `region-size`, `min-distance`, `max-distance`, `candidate-attempts`,
  `move-throttle-millis`, `minimum-spacing`, `model-version`). Ajoutée automatiquement au
  `config.yml` du serveur par `ConfigFileCompleter` au prochain démarrage (les valeurs
  personnalisées ne sont jamais écrasées). Section absente → tous les défauts, comportement sûr.
- **Nouveau comportement runtime** : à l'entrée d'un joueur dans une zone de biome sans waypoint
  (dans `wild` uniquement), le plugin **pose une petite structure** (barrière de pierre + bloc
  d'or + bouton) à 24-72 blocs, sur terrain naturel, **hors claim**, **sans écraser de
  construction joueur**. Non destructif par conception. Blocs du waypoint protégés (casse,
  explosion, piston, feu, fluide, gravité) sauf `rpgquest.admin.world`.
- Fichiers : `src/main/java/com/lodygames/rpgquest/waypoint/**`,
  `database/WaypointRepository.java`, `database/SchemaMigrator.java` (V18),
  `config/TravelConfig.java`, `config/ConfigValidator.java`, `bootstrap/RPGQuestBootstrap.java`,
  `src/main/resources/config.yml`.

### Action serveur

**Remplacer uniquement le JAR RPGQuest** (`plugins/RPGQuest-*.jar`) sur le serveur **DEV**.
Aucun autre fichier à copier : la migration V18 et la section `travel.waypoint` sont appliquées
automatiquement au démarrage. Aucun plugin externe, aucune version Java, aucun monde à créer.
**Cible : DEV uniquement.** Production non concernée par cette session.

### Sauvegarde préalable

Via `scripts/deploy-verygames.sh` (backup daté automatique : JAR courant + `data.db` + config).
Ne jamais écraser le dernier backup. Sauvegarder en plus, par sécurité, le dossier du monde
`wild` (`world_wild/` côté serveur) : le nouveau comportement y **ajoute** des blocs (structures
de waypoint) — additif, mais un backup permet un retour à l'état « aucun waypoint ».

### Déploiement

Non exécuté par la session (pas de `scripts/verygames.env` sur la box de build ; règle CLAUDE.md
« aucun déploiement automatique » ; première mise en service d'un comportement de pose de blocs
autonome = validation humaine souhaitable sur la première génération en jeu).

À lancer demain, depuis `/srv/rpgquest/repo`, après avoir renseigné `scripts/verygames.env`
(voir `scripts/verygames.env.example`) :

```
./gradlew build                       # déjà vert ce jour ; re-vérifier
scripts/deploy-verygames.sh           # upload FTP du JAR + backup daté
scripts/verygames-restart.sh          # redémarrage RCON (stop -> attente retour)
```

### Validation

Après redémarrage, au journal serveur : ligne `Waypoints chargés : N.` (N=0 au premier
démarrage). Puis en jeu (compte **non-op**, monde `wild`) :

1. marcher ~1-2 min dans un biome jamais visité → au journal : `Waypoint « wp_wild_… » généré
   en wild (x,y,z) [biome …, modèle v1]` ; trouver la structure (barrière de pierre + bloc d'or
   + bouton) à quelques dizaines de blocs, **pas sous ses pieds** ;
2. **passer à côté sans cliquer** → rien ;
3. **clic droit sur le bouton** → message « Waypoint découvert — <biome> » + son ; re-cliquer →
   silencieux ;
4. casser un bloc du waypoint (non-op) → refusé ; en `rpgquest.admin.world` → autorisé ;
5. relancer le serveur → même waypoint, même découverte, aucun doublon (`Waypoints chargés : 1`).

Détail : `docs/WAYPOINTS.md` §9 et `docs/MANUAL_TEST_PLAN.md`.

### Rollback

- Remettre l'ancien JAR RPGQuest (`scripts/rollback-verygames.sh`) + redémarrage RCON.
- La migration V18 (tables `waypoints`, `waypoint_discoveries`) reste en base : **inerte** sans
  le code, aucune action requise. Un ancien JAR (schéma attendu 17) démarre sans souci, les
  tables surnuméraires sont ignorées.
- Structures de waypoint déjà posées dans le monde : retirées manuellement si besoin (pas de
  script), ou restauration du backup `world_wild/`. Non urgent (blocs vanilla inoffensifs).
- `mettre travel.waypoint.enabled: false` dans `config.yml` + `/rpgquest reload` désactive la
  génération et la découverte sans rollback de JAR.

### Exécution réelle

**Session initiale (2026-09-09 ~21:18)** : moteur livré, `./gradlew build` vert
(root `:test` 1254/0, `:control-panel:test` 279/0). Déploiement **non effectué** à ce stade.

**Déploiement DEV effectué (2026-09-09 ~21:32 UTC, sur demande explicite)** — DEV VeryGames
uniquement, aucun merge, aucune modification de contenu :

- Branche `feat/control-panel-admin-tools` @ `0d1c978` (contient bien `2473086` feat + `01f6839`
  docs + `0d1c978` handoff). Working tree propre.
- `scripts/deploy-verygames.sh -y` : `./gradlew test` **OK**, `./gradlew build` **OK** (tout
  `UP-TO-DATE`, code inchangé depuis le build initial).
- **JAR déployé** : `build/libs/rpgquest-0.1.0-SNAPSHOT.jar`, 1 475 924 o,
  SHA-256 `27f427409a6d6879d4230f9efb0afc4f9b893eb6a829cd187fa048bc2ead1668` ; taille en ligne
  après transfert atomique == locale.
- **Backup préalable automatique** : `~/.local/share/rpgquest/verygames-backups/rpgquest-20260909T213229Z-predeploy.jar`
  (1 437 876 o, SHA-256 `4f39e1e41e6860921a38b9c5893f5f56b95a0fac7fc1500d321ca2b64316765c`) + `.meta`.
- Aucun `--also` : **seul le JAR** a été transféré. `data.db`, `config.yml`, `messages.yml`,
  `spawn.yml`, Citizens, mondes et autres plugins **non touchés** (le script refuse ces chemins).
- **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` (un seul appel) — `save-all`
  puis `stop` RCON, serveur passé **OFFLINE** puis revenu **ONLINE** (script sorti 0).
- **Vérifications post-redémarrage (RCON — pas d'accès aux logs)** : `/rpgquest version` →
  `RPGQuest v0.1.0-SNAPSHOT` ; `/plugins` → `RPGQuest` en **vert** (activé, non désactivé) ;
  `/rpgquest reload` → « Configuration RPGQuest rechargée. » (la section auto-complétée
  `travel.waypoint` passe `ConfigValidator`) ; `/rpgquest help` et `/rpgadmin` répondent
  normalement. Le plugin étant **pleinement activé et réactif**, `SchemaMigrationRunner` a
  appliqué **V18 sans erreur critique** (le bootstrap avorte l'activation sinon).
- **Non vérifiable directement** : la ligne de log `Waypoints chargés : N.` et un scan `ERROR`
  du démarrage — le compte FTP est chrooté sur le dossier des plugins (pas d'accès à `logs/`),
  RCON n'expose pas l'historique console, pas d'identifiants du panel VeryGames. À constater
  dans la console du panel lors de la validation manuelle (TC-210).
- **Aucun waypoint généré par cette session**, monde non modifié : la première génération sera
  déclenchée en jeu par l'owner (TC-210).

Rollback : `scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20260909T213229Z-predeploy.jar`)
puis `scripts/verygames-restart.sh`.

Rapport : `docs/claude-reports/2026-09-09_2118_waypoints-mvp-instance-biome-124.md`.

---

## 2026-09-09 - Export versionné du contenu déclaratif — phase 1 (issue #108)

### Changement

Phase 1 du pipeline de contenus LodyQuests : **export** de quêtes / stories / dialogues / PNJ
logiques dans un format public versionné `lodyquests-content-pack` (`schemaVersion: 1`).

- **Plugin RPGQuest** : nouveau package `com.lodygames.rpgquest.content.pack` (DTO + mapper +
  serializer + assembler) et **nouvelle action agent lecture seule** `content.export`
  (`AgentActionType.CONTENT_EXPORT`). Aucune migration de schéma, aucune nouvelle donnée
  persistante, aucun changement `config.yml` / `messages.yml`, aucun effet de bord runtime
  (l'action lit l'état en mémoire des moteurs et renvoie un YAML).
- **Control Panel (AWS)** : permission `CONTENT_EXPORT`, page `/content/export`, route de
  téléchargement `GET /content/export/download`. Nouvelle entrée de menu. CSP inchangée
  (`default-src 'self'`), aucun CDN. `control-panel.db` non touché (aucune migration agent).

### Action serveur

**Aucune pour l'instant.** Rien n'est déployé cette session (consigne : pas de déploiement).
Au prochain déploiement de la branche `feat/control-panel-admin-tools` :

- **Control Panel AWS** : `scripts/plugadmin/deploy.sh` (build + release + `systemctl restart
  plugadmin` + `/health`). C'est ce qui active réellement la page `/content/export`.
- **JAR RPGQuest DEV** (optionnel, seulement si on veut exporter depuis DEV) : remplacer
  `plugins/RPGQuest-*.jar` par le nouveau JAR (contient l'action `content.export`). Migration :
  aucune. Redémarrage : oui (remplacement de JAR).

**VeryGames / Minecraft / `data.db` / mondes / Citizens : non concernés par ce changement.**

### Sauvegarde préalable

Standard (backup daté automatique des scripts). Rien de spécifique — aucune donnée modifiée.

### Déploiement

Non exécuté. Voir « Action serveur ».

### Validation

- `./gradlew build` vert (voir rapport).
- Après déploiement AWS : `/content/export` rendu (auth), bouton « Exporter tout », téléchargement
  d'un `.yaml` conforme (`format: lodyquests-content-pack`, `schemaVersion: 1`). `PENDING MANUAL
  VALIDATION` (navigateur owner).

### Rollback

Control Panel : `scripts/plugadmin/rollback.sh app`. Plugin : ancien JAR + restart. L'action
`content.export` étant lecture seule et additive, aucun état à défaire.

### Exécution réelle

Aucune. Développement seul, branche `feat/control-panel-admin-tools`, non fusionnée, non déployée.

Rapport : `docs/claude-reports/2026-09-09_2215_export-versionne-contenu-108.md`.

---

## 2026-09-10 - Éditeur guidé Quêtes/Stories — passe UX (issue #46) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration.** Corrige des bugs navigateur confirmés manuellement sur
`/quests/new` et `/quests/edit/...` :

- Le `<form class="editor">` porte `novalidate` et l'attribut HTML `required` n'est plus émis
  (marqueur visuel « * » conservé) : construire / supprimer / réordonner un brouillon ne peut
  plus déclencher « Veuillez renseigner ce champ ». La validation métier reste 100 % serveur
  (`QuestValidator` / `StoryValidator`) aux seuls « Vérifier » / « Aperçu » / « Enregistrer ».
- Conservation du scroll après une action de brouillon : `panel.js` restaure explicitement la
  position au chargement (cible du hash via `scrollIntoView` + focus, sinon position mémorisée) —
  un POST HTML 200 n'applique pas de façon fiable le fragment d'un `formaction`.
- Bascule de type d'objectif / récompense immédiate : `applyType` appliqué une fois à l'init par
  `<select>` ; chaque module `panel.js` isolé en `try/catch`.
- Listes déroulantes des PNJ (`dl-npc`) avec libellé humain (`<option value="guard" label="Garde">`) ;
  `RefData` transporte `id -> displayName` depuis `npc.list`.
- Aide de la récompense `ITEM` : précise « objet vanilla, pas un objet personnalisé RPGQuest ».

Fichiers : `panel/web/ContentEditorPages.java`, `panel/content/Descriptors.java`,
`panel/content/RefData.java`, `panel/web/AgentPages.java`, `assets/panel.js`. CSP inchangée
(`default-src 'self'`), aucun CDN. `control-panel.db` non touché (aucune migration).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous `/opt/plugadmin/releases/<horodatage>`
+ `systemctl restart plugadmin` + check `/health`. Aucune autre action. Aucun changement nginx /
TLS / secret / base. **VeryGames / Minecraft non touchés.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `/quests/new` et `/quests/edit/<slug>` rendus (auth).
- `:control-panel:test` vert ; `./gradlew build` vert.
- Rendu navigateur authentifié (ajout/suppression sur formulaire incomplet, changement de type,
  combos entité/PNJ/material, conservation du scroll) : `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~09:51 UTC** depuis `feat/control-panel-admin-tools`
@ `4cb278c`. `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava`/`jar` **UP-TO-DATE** — distribution issue du build déjà testé), release précédente
sauvegardée sous `/opt/plugadmin/releases/20260910-095149`, `systemctl restart plugadmin` →
`active (running)`, drop-in `10-content-workspace.conf` toujours chargé, `Memory` ~51 M.

Vérifications live : `/health` **ONLINE** local **et** public
(`https://plugadmin.lodylands.com/health`). `/quests/new` et `/quests/edit/x` anonymes → **303**
vers `/login` (routes vivantes, auth appliquée). `panel.js` servi publiquement contient bien
`function run(name, fn)` (isolation des modules) et `scrollIntoView({ block: "center" })`
(restauration du scroll de l'éditeur). **0 `ERROR` / `SEVERE` / `Exception`** au journal depuis le
redéploiement. `control-panel.db` non touché. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

Validation navigateur **authentifiée** de l'éditeur : couverte par `ContentEditorPagesTest` (28,
`PanelApp` réel + login owner + POST des actions sur `/quests/new` et `/quests/edit/<slug>`) et
`AuthenticatedSmokeTest`. Session owner navigateur réelle (8 scénarios manuels du rapport) :
`PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-095149`).

Rapport : `docs/claude-reports/2026-09-10_0949_editeur-quetes-passe-ux-46.md`.

---

## 2026-09-10 - Éditeur guidé de Stories — passe UX (issue #46) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration.** Passe UX ciblée sur l'éditeur guidé de **stories**
(`/stories/new`, `/stories/edit/<slug>`), pour lui donner la même ergonomie que l'éditeur de
quêtes déjà validé :

- **Sélection recherchable des quêtes par titre humain OU id technique.** La datalist `dl-quest`
  porte désormais un `label` humain (`<option value="first_steps" label="Premiers pas">`) ;
  `RefData` transporte `id -> titre` depuis le dernier relevé `quest.list`, `RefData.questLabel()`
  le résout (namespace `rpgquest:` toléré). Le combo `panel.js` (déjà) filtre sur le libellé
  **et** la valeur.
- **Ordre clair** : chaque ligne de la chaîne affiche le rang `N.` + le titre humain **au-dessus**
  de l'identifiant technique éditable, avec monter / descendre / retirer. Une quête absente du
  catalogue chargé porte un badge « quête inconnue » dès la saisie.
- Bouton **« Actualiser le formulaire »** ajouté à la section Validation (parité avec l'éditeur de
  quêtes). `<form novalidate>`, aucun `required`, conservation du scroll : déjà en place (V4),
  inchangés.
- Modèle **strictement** celui du moteur (`StoryDefinition` : `id`, `name`, `secret`, liste
  ordonnée de `questIds`) — aucun champ ajouté. `StoryValidator` inchangé (doublon = avertissement
  car le moteur l'autorise ; référence inconnue = avertissement ; round-trip).

Fichiers : `panel/content/RefData.java` (+`questNames` / `questLabel`), `panel/web/AgentPages.java`
(`referenceData` remplit `questNames` depuis `quest.list`), `panel/web/ContentEditorPages.java`
(datalist `dl-quest` labellisée, ligne de chaîne `renderStoryQuestRow`, bouton « Actualiser »),
`assets/plugadmin.css` (`.row-n` / `.row-title`). `panel.js` **non modifié**. CSP inchangée
(`default-src 'self'`), aucun CDN. `control-panel.db` non touché (aucune migration).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous `/opt/plugadmin/releases/<horodatage>`
+ `systemctl restart plugadmin` + check `/health`. Aucune autre action. Aucun changement nginx /
TLS / secret / base. **VeryGames / Minecraft non touchés.**

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `/stories`, `/stories/new`, `/stories/edit/<slug>`
  rendus (auth).
- `:control-panel:test` vert (`StoryEditorPassTest` +17) ; `./gradlew build` vert.
- Rendu navigateur owner (créer une story, recherche quête par titre puis par id, ajout multiple,
  monter/descendre, retirer, scroll conservé, Vérifier/Aperçu, YAML + ordre, mobile) :
  `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~11:14 UTC** depuis `feat/control-panel-admin-tools`
@ `007a419`. `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL**
(`compileJava` / `jar` **UP-TO-DATE** — distribution issue du build déjà testé), release
précédente sauvegardée sous `/opt/plugadmin/releases/20260910-111417`,
`systemctl restart plugadmin` → `active (running)`, drop-in `10-content-workspace.conf` toujours
chargé, `Memory` ~51 M.

Vérifications live : `/health` **ONLINE** local **et** public
(`https://plugadmin.lodylands.com/health`). `/stories`, `/stories/new` et
`/stories/edit/<slug>` anonymes → **303** vers `/login` (routes vivantes, auth appliquée).
`assets/plugadmin.css` servi publiquement contient bien `.row-title{ … }` (titre de la ligne de
chaîne) ; le JAR déployé contient `questDatalist`, `renderStoryQuestRow` et
« Actualiser le formulaire ». **0 `ERROR` / `SEVERE` / `Exception`** au journal depuis le
redéploiement. `control-panel.db` non touché. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

Validation navigateur : `StoryEditorPassTest` (17, rendu direct de `ContentEditorPages` avec un
`RefData` porteur de titres) + `ContentEditorPagesTest` (`storyEditorCreatesOrderedChain`,
`storyEditorFormAlsoDisablesNativeValidation`, `PanelApp` réel + login owner). Session owner
navigateur réelle (10 scénarios manuels du rapport) : `PENDING MANUAL VALIDATION` (mot de passe
owner non détenu).

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-111417`).

Rapport : `docs/claude-reports/2026-09-10_1032_editeur-stories-passe-ux-46.md`.

---

## 2026-09-10 - Control Panel : socle RBAC — rôles, permissions et comptes /users (issue #50) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, des
mondes, ou de `data.db`. Aucune migration MariaDB (#42).** Consolide le contrôle d'accès de
PlugAdmin autour du modèle **utilisateur → rôle → `Set<Permission>` → contrôle backend → UI
filtrée** (issue #50).

- **Rôles** (`authz.Role`) : `OWNER` (= `EnumSet.allOf(Permission.class)`), `ADMIN`, `TESTER`,
  `BUILDER`, `CONTENT_EDITOR`, `READ_ONLY`. Nouvelle permission `USER_MANAGE` (OWNER par défaut).
  Rôles propres à PlugAdmin — aucune correspondance avec OP / Paper / LuckPerms.
- **Comptes** : nouvelle table `panel_user` dans `control-panel.db` (`CREATE TABLE IF NOT EXISTS`
  additif et idempotent). `users.PanelUser` / `UserRepository` (`SqliteUserRepository`) /
  `UserDirectory`. Le compte `owner` défini par `RPGQUEST_PANEL_OWNER_USERNAME` /
  `RPGQUEST_PANEL_OWNER_HASH` est **réamorcé au démarrage** (créé s'il manque, sinon forcé actif
  + `OWNER` + hash réaligné) : chemin de récupération anti-verrouillage. Ces variables sont déjà
  requises pour que le service démarre — pas de risque de perte d'accès.
- **Auth** (`AuthService`) : identifiants vérifiés contre `panel_user` (casse ignorée), coût
  PBKDF2 payé même compte absent / inactif, un compte **désactivé** ne peut plus se connecter,
  `last_login_at` posé à la réussite. Hachage inchangé (`PasswordHasher` PBKDF2-HMAC-SHA256
  210k) — audité, conservé.
- **Session** : re-contrôle par requête — un compte désactivé perd sa session en cours (→ `303
  /login`), un changement de rôle prend effet sans reconnexion. Le `SessionStore` reste en
  mémoire : le redémarrage déconnecte les sessions en cours (comportement habituel d'un déploiement
  PlugAdmin).
- **Page `/users`** (`USER_MANAGE`) : liste + création (identifiant, mot de passe ≥ 12, rôle),
  détail `/users/<id>`, `POST /users/create` / `/users/<id>/role` / `/users/<id>/active`. CSRF
  synchroniseur sur chaque POST, permission vérifiée côté backend (403 « Vous n'avez pas
  l'autorisation… » même en appel direct). Dernier OWNER actif protégé (ni rétrogradation ni
  désactivation) ; désactivation de son propre compte interdite. Pas de suppression
  (désactivation seulement).
- **Navigation** : `Layout.nav()` + tuiles `HomePages` filtrées par permission ; la topbar
  affiche l'utilisateur **et** son rôle.
- **Audit** : `user.create`, `user.role.change` (`from=… to=…`), `user.active.change`,
  `login.failure` motif `compte désactivé`, `login.success` porte `role=…`. Jamais de mot de
  passe / hash.

Fichiers principaux : `authz/Permission.java`, `authz/Role.java`, `users/*` (nouveau paquet),
`security/AuthService.java`, `security/Session.java`, `security/SessionStore.java`,
`web/PanelApp.java`, `web/Layout.java`, `web/HomePages.java`, `web/UsersPages.java` (nouveau),
`web/Icons.java`, `agent/AgentActionCatalog.java` (+ `all()`), `assets/plugadmin.css`. CSP
inchangée (`default-src 'self'`), aucun CDN, aucun script inline.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — `:control-panel:installDist` + release sous
`/opt/plugadmin/releases/<horodatage>` + `systemctl restart plugadmin` + check `/health`. Aucune
autre action. Aucun changement nginx / TLS / secret. **VeryGames / Minecraft non touchés, aucun
redémarrage Minecraft.**

### Sauvegarde préalable

- **`control-panel.db`** (migration = nouvelle table `panel_user`) : copie datée avant le
  redémarrage, p. ex. `cp /opt/plugadmin/shared/control-panel.db
  /opt/plugadmin/backups/control-panel.db.$(date +%Y%m%d-%H%M%S)` (chemin réel à confirmer via le
  drop-in systemd / `RPGQUEST_PANEL_DB`).
- App précédente : automatique via `deploy.sh` (`/opt/plugadmin/releases/<horodatage>`,
  rétention 5).

### Déploiement

```
# sauvegarde de la base du Control Panel (migration additive)
cp <chemin>/control-panel.db <chemin>/control-panel.db.$(date +%Y%m%d-%H%M%S)
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `systemctl status plugadmin` = `active (running)`.
- `/login` rendu ; connexion owner OK ; `/users` rendu (auth owner) et **403** pour un rôle sans
  `USER_MANAGE` ; URL `/users` anonyme → `303 /login`.
- Journal sans `ERROR` / `SEVERE` / `Exception` depuis le redémarrage.
- `:control-panel:test` vert (354 tests, +43) ; `./gradlew test` + `./gradlew build` verts.
- Checklist navigateur owner (créer un compte TESTER, se connecter avec lui, menus visibles,
  URL interdite → 403, revenir OWNER, désactiver le compte de test) : `PENDING MANUAL VALIDATION`.

### Rollback

1. `scripts/plugadmin/rollback.sh app` (restaure la release précédente).
2. La table `panel_user` peut rester en place sans effet sur l'ancienne version (elle l'ignore).
   Pour un retour strictement à l'identique : restaurer la copie datée de `control-panel.db`.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~15:52 UTC** depuis `feat/control-panel-admin-tools`
@ `ea34d73`.

- Sauvegarde préalable : `sudo cp -a /var/lib/plugadmin/control-panel.db
  /opt/plugadmin/backups/control-panel.db.20260910-155247` (migration = nouvelle table
  `panel_user`).
- `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL** (`compileJava`
  / `jar` **UP-TO-DATE**), app précédente sauvegardée sous
  `/opt/plugadmin/releases/20260910-155250`, `systemctl restart plugadmin` → `active (running)`
  (PID 756465, `Memory` ~69 M), drop-in `10-content-workspace.conf` toujours chargé.
- Vérifications live : `/health` **ONLINE** local **et** public
  (`https://plugadmin.lodylands.com/health`) ; `/login` local → **200** ; `/users` anonyme →
  **303** vers `/login` (`USER_MANAGE` appliqué avant tout) ; `control-panel.db` : table
  **`panel_user`** créée, ligne `owner | OWNER | active=1 | last_login_at=NULL` (amorçage OK,
  `data.db` non touché) ; `journalctl -u plugadmin` depuis le redémarrage : **0**
  `ERROR` / `SEVERE` / `Exception` / `WARN`. **VeryGames / Minecraft non touchés, aucun
  redémarrage Minecraft.**

Validation navigateur **authentifiée** de `/users` : couverte par `UserManagementTest` (11,
`PanelApp` réel + sessions HTTP, dont 403 backend en appel direct, CSRF, audit, dernier OWNER,
session d'un compte désactivé, navigation filtrée). Session owner navigateur réelle (checklist du
rapport) : `PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-155250`) ; base :
`/opt/plugadmin/backups/control-panel.db.20260910-155247`.

Rapport : `docs/claude-reports/2026-09-10_1553_rbac-plugadmin-50.md`.

---

## 2026-09-10 - Control Panel : catalogue fusionné source + runtime pour /quests et /stories (issue #144) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration.**

Symptôme (#144) : une quête créée depuis `/quests/new` était bien écrite dans la source
(`src/main/resources/quests/<id>.yml` via `ContentWorkspace`) mais n'apparaissait **jamais** dans
`/quests` — même après « Rafraîchir le catalogue » — parce que la page n'affichait **que** le
dernier relevé runtime `quest.list` de l'agent. Elle ne pouvait pas non plus servir de prérequis
ni composer une story sans redémarrage Minecraft.

Correctif : `/quests` et `/stories` **fusionnent** désormais deux origines — le dernier relevé
runtime **et** la source éditable relue à chaque affichage (nouveau `SourceCatalog`, module
`control-panel` `panel.content`, lecture seule via `QuestYaml`/`StoryYaml`). Chaque entrée porte
un état explicite : **Source + serveur** (aucun badge), **« Source uniquement »** (badge `info` +
note : enregistrée mais pas encore chargée en jeu — jamais présentée comme active),
**« Hors source »** (chargée par le serveur, absente de la source). `AgentPages.referenceData()`
fusionne aussi les quêtes de la source dans les lookups d'édition (prérequis, chaîne de story) et
le diagnostic « quête inconnue dans la chaîne ». Les listes d'actions admin (`quest.start`,
`story.advance`…) restent limitées au runtime. **Aucun rechargement Minecraft déclenché** :
édition et activation en jeu restent deux étapes distinctes.

Fichiers : `panel/content/SourceCatalog.java` (nouveau), `panel/web/AgentPages.java` (fusion
`quests()` / `stories()` / `referenceData()` + rendu des badges/notes d'état),
`panel/web/PanelApp.java` (câblage `SourceCatalog`). CSP inchangée, aucun CDN, `control-panel.db`
non touché (aucune migration), aucune permission ajoutée.

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous `/opt/plugadmin/releases/<horodatage>`
+ `systemctl restart plugadmin` + check `/health`. Aucune autre action. Aucun changement nginx /
TLS / secret / base. **VeryGames / Minecraft non touchés, aucun redémarrage Minecraft.**

Le drop-in `10-content-workspace.conf` (`PLUGADMIN_CONTENT_DIR=/srv/rpgquest/repo/src/main/resources`)
doit rester chargé : sans lui, la page fonctionne mais n'affiche aucun badge d'origine.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). Pas de sauvegarde base (aucune migration).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `/quests`, `/quests/new`, `/stories`, `/stories/new`
  rendus (auth).
- `:control-panel:test` vert (`SourceCatalogTest` +4, `MergedCatalogTest` +11) ;
  `./gradlew build` vert.
- Checklist navigateur owner : `/quests` → une quête « source uniquement » visible avec son badge ;
  `/stories/new` → cette quête recherchable ; « Rafraîchir Quêtes » → elle reste visible.
  `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~19:58 UTC** depuis `feat/control-panel-admin-tools`.

- `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` **BUILD SUCCESSFUL** (issue du
  build déjà testé — `compileJava` / `jar` **UP-TO-DATE**), app précédente sauvegardée sous
  `/opt/plugadmin/releases/20260910-195741`, `systemctl restart plugadmin` → `active (running)`
  (PID 860171, `Memory` ~69 M), drop-in `10-content-workspace.conf` toujours chargé.
- Vérifications live : `/health` **ONLINE** local **et** public
  (`https://plugadmin.lodylands.com/health`) ; `/quests`, `/quests/new`, `/stories`, `/stories/new`
  anonymes → **303** vers `/login` (routes vivantes, auth appliquée) ; le JAR déployé contient
  `com/.../panel/content/SourceCatalog.class`, `AgentPages$CatalogState`, `AgentPages$MergedRow` ;
  `journalctl -u plugadmin` depuis le redémarrage : **0** `ERROR` / `SEVERE` / `Exception` / `WARN`.
  `control-panel.db` non touché. **VeryGames / Minecraft non touchés, aucun redémarrage Minecraft.**

Validation navigateur **authentifiée** : couverte par `MergedCatalogTest` (11, `PanelApp` réel +
sessions HTTP + login owner) et `SourceCatalogTest` (4). Session owner navigateur réelle
(checklist du rapport) : `PENDING MANUAL VALIDATION` (mot de passe owner non détenu).

Build : `./gradlew build` **BUILD SUCCESSFUL in 14m 36s** (2e essai, `--no-daemon`
`RPGQUEST_TEST_MAX_HEAP=640m` — le 1er essai a été tué par l'OOM connu de la box, cf. `.ai/`
mémoire) ; root `:test` vert, `:control-panel:test` **368/0**, `:web-api` up-to-date.

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-195741`).

Rapport : `docs/claude-reports/2026-09-10_1958_catalogue-fusionne-source-runtime-144.md`.

## 2026-09-10 - Control Panel : catalogue de dialogues fusionné source + runtime + création avec PNJ (issue #145) — AWS uniquement

### Changement

Control Panel uniquement. **Aucun changement du plugin RPGQuest, du serveur Minecraft, de la
base ou de la configuration.**

Symptôme (#145) : un dialogue créé depuis `/dialogues` terminait en `SUCCESS` mais `/dialogues`
restait vide (« Aucun catalogue chargé »), le rafraîchissement n'y changeait rien, et le
formulaire de création ne permettait pas de choisir le PNJ logique existant.

Cause : `/dialogues` n'affichait **que** le relevé runtime `dialogue.list`, et la création
passait par l'action agent `dialogue.definition.create` (écriture côté serveur DEV) — invisible
tant que le serveur ne l'avait pas rechargé.

Correctif (même principe que #144) : `"dialogues"` devient un `KIND` de `ContentWorkspace` ;
nouveaux `DialogueDraft` / `DialogueYaml` (écriture au **format canonique du moteur**, identique
octet pour octet au squelette `DialogueDefinitionYaml`) / `DialogueValidator` (module
`control-panel` `panel.content`). `SourceCatalog.dialogues()` relit `src/main/resources/dialogues/*.yml`.
`AgentPages.dialogues()` **fusionne** `dialogue.list` et la source sur l'id « nu » avec les
trois états de #144 (**Source + serveur** / **« Source uniquement »** / **« Hors source »**), la
source relue à chaque affichage, la page rendue même quand le relevé runtime est vide. La
création passe désormais par l'éditeur source **`/dialogues/new`** (`ContentEditorPages`, comme
`/quests/new`) : identité, **PNJ à rattacher** (recherche nom/id), locuteur, couleur, réplique de
départ → écrit `dialogues/<id>.yml`. Si un PNJ est choisi, une **seconde écriture** met à jour sa
définition via l'action agent **existante** `npc.definition.update` (`display_name` / `role` /
`enabled` repris du dernier `npc.list` pour ne rien écraser) ; PNJ absent du relevé → dialogue
enregistré quand même, rattachement signalé comme à refaire depuis la fiche PNJ. `/npcs` : bouton
« Créer un dialogue pour ce PNJ » et `<select>` Dialogue qui propose aussi les dialogues de la
source. `DIALOGUE_DECLARED_MISSING` rétrogradé en info quand le dialogue existe dans la source.
L'action `dialogue.definition.create` reste whitelistée (compat) mais n'a plus de formulaire.
**Aucun rechargement Minecraft déclenché.**

Fichiers : `panel/content/DialogueDraft.java`, `DialogueYaml.java`, `DialogueValidator.java`
(nouveaux) ; `panel/content/ContentWorkspace.java` (KIND `dialogues`), `SourceCatalog.java`
(`dialogues()`) ; `panel/web/AgentPages.java` (fusion `dialogues()`, `dialogueSelectOptions`,
bouton fiche PNJ, `npcDefinitionFields`) ; `panel/web/ContentEditorPages.java` (éditeur
`/dialogues/new` · `/dialogues/save`) ; `panel/web/PanelApp.java` (routes + orchestration
`npc.definition.update`). CSP inchangée, aucun CDN, `control-panel.db` non touché (aucune
migration), aucune permission ajoutée (`DIALOGUE_WRITE` / `DIALOGUE_READ` existants).

### Action serveur

`scripts/plugadmin/deploy.sh` (AWS) — build + release sous `/opt/plugadmin/releases/<horodatage>`
+ `systemctl restart plugadmin` + check `/health`. Aucune autre action. Aucun changement nginx /
TLS / secret / base. **VeryGames / Minecraft non touchés, aucun redémarrage Minecraft.**

Le drop-in `10-content-workspace.conf` (`PLUGADMIN_CONTENT_DIR=/srv/rpgquest/repo/src/main/resources`)
doit rester chargé : sans lui, `/dialogues` fonctionne mais n'affiche aucun badge d'origine et
`/dialogues/new` est en lecture seule.

### Sauvegarde préalable

Automatique via `deploy.sh` : app précédente sous `/opt/plugadmin/releases/<horodatage>`
(rétention 5). Pas de sauvegarde base (aucune migration).

### Déploiement

```
scripts/plugadmin/deploy.sh
```

### Validation

- `/health` local **et** public ONLINE ; `/dialogues`, `/dialogues/new`, `/npcs` rendus (auth).
- `:control-panel:test` vert (`DialogueYamlTest` +6, `SourceCatalogTest` +1,
  `DialogueSourceMergeTest` +10) ; `./gradlew build` vert.
- Checklist navigateur owner : `/dialogues` → `lily_intro` visible « Source uniquement » ;
  refresh → reste visible ; `/dialogues/new` → PNJ Lily recherchable ; fiche Lily → `lily_intro`
  rattaché / sélectionnable. `PENDING MANUAL VALIDATION`.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure la release précédente). Aucune migration à défaire.

### Exécution réelle

Déploiement AWS effectué le **2026-09-10 ~21:32 UTC** depuis `feat/control-panel-admin-tools`
(commits `e80b459` + `ac7db40`) via `scripts/plugadmin/deploy.sh` :

- `:control-panel:installDist` **BUILD SUCCESSFUL** (issu du build déjà vert — `compileJava` /
  `jar` UP-TO-DATE), app précédente sauvegardée sous `/opt/plugadmin/releases/20260910-213241`,
  `systemctl restart plugadmin` → `active (running)` (PID 905914, `Memory` ~69,6 M), drop-in
  `10-content-workspace.conf` toujours chargé.
- `/health` **ONLINE** local **et** public ; `/dialogues`, `/dialogues/new`, `/npcs` anonymes →
  **303** `/login` ; JAR déployé contient `panel/content/DialogueDraft.class` /
  `DialogueValidator.class` / `DialogueYaml.class` ; `journalctl -u plugadmin` depuis le
  redémarrage : **0** `ERROR` / `SEVERE` / `Exception` / `WARN`. `control-panel.db` non touché.
  **VeryGames / Minecraft non touchés, aucun redémarrage Minecraft.**

Build : `./gradlew build` **BUILD SUCCESSFUL in 14m 46s** (`--no-daemon`,
`RPGQUEST_TEST_MAX_HEAP=640m`) ; root `:test` vert, `:control-panel:test` **384/0** (1 ignoré
pré-existant), `:web-api` up-to-date.

Rollback : `scripts/plugadmin/rollback.sh app` (→ `20260910-213241`).

Rapport : `docs/claude-reports/2026-09-10_2047_dialogues-fusion-source-runtime-npc-145.md`.

---

## 2026-10-03 - Kit d'outils en bois demandé au Guide (issue #26, partie A)

### Changement

Plugin RPGQuest (gameplay) uniquement. **Aucun changement Control Panel.**

Ajout de l'action de dialogue `GIVE_STARTER_KIT` : `dialogues/guide.yml` propose « Demander mon
kit de départ » (toujours affiché, sans condition). Remplace la règle précédemment prévue de
remise unique à vie — le kit est désormais **demandé explicitement**, jamais donné
automatiquement (ni clic simple sur le PNJ, ni connexion, ni réapparition), et le droit de le
redemander **se renouvelle à chaque mort** du joueur (sans limite totale de récupérations ; une
mort avant la toute première remise ne retire jamais le droit initial).

Contenu configurable (`config.yml` → `starter-tool-kit`, nouvelle section), par défaut un
exemplaire de chaque `WOODEN_SWORD`/`WOODEN_PICKAXE`/`WOODEN_SHOVEL`/`WOODEN_AXE` — aucun autre
objet. Remise **tout ou rien** : les emplacements libres du stockage normal (hors armure/main
secondaire) sont comptés avant toute écriture ; en dessous du nombre d'objets du kit, aucun objet
n'est distribué, rien n'est jeté/remplacé, et le droit n'est jamais consommé (message dédié).
Anti double-clic (verrou mémoire par joueur). Droit persisté par joueur dans `player_variables`
(clé `STARTER_TOOL_KIT_AVAILABLE`, **aucune migration de schéma** — réutilise la table
existante) ; `/rpgadmin player resetnew` restaure le droit initial sans code dédié (il efface déjà
toutes les variables du joueur).

Nouveaux fichiers : `config.StarterToolKitConfig`, `dialogue.model.GiveStarterKitAction`,
`player.StarterToolKitService` (écouteur `PlayerDeathEvent` + logique de remise, **distinct** de
`player.StarterKitListener` — Rune de rappel, remise unique à vie, automatique à la connexion,
non modifié). Fichiers modifiés : `ConfigValidator`/`PluginConfig` (section `starter-tool-kit`),
`ActionType`/`DialogueAction`/`DialogueDefinitionParser`/`DialogueDefinitionWriter` (nouveau type
d'action), `ContentPackMapper`/`DialoguePackEntry` (export), `BukkitAgentActions` (résumé Control
Panel du catalogue de dialogues — lecture seule, pas d'action agent nouvelle),
`DialogueSessionEngine` (dispatch), `RPGQuestBootstrap` (câblage), `dialogues/guide.yml`,
`config.yml`.

Partie B du ticket (#26 — avertissement avant l'entrée dans le Wild) **non livrée** : #26 reste
ouvert.

### Action serveur

Remplacement du JAR RPGQuest uniquement — aucune action manuelle autre que remplacement du JAR.
`config.yml` n'a **pas besoin d'être modifié manuellement** : la section `starter-tool-kit` est
ajoutée automatiquement au démarrage par `ConfigFileCompleter` si absente (valeurs par défaut
ci-dessus), sans toucher aux clés déjà présentes.

### Sauvegarde préalable

- Ancien JAR `plugins/RPGQuest-<ancienne_version>.jar`.
- `plugins/RPGQuest/data.db` (aucune migration de schéma, mais suivre la procédure standard de
  [mise à jour du seul JAR](VERYGAMES.md#mise-à-jour-du-seul-jar-rpgquest-scénario-2)) et
  `plugins/RPGQuest/config.yml` (complété automatiquement au premier démarrage, un `.bak` est créé
  par `ConfigFileCompleter`).

### Déploiement

1. Compiler (`./gradlew clean build`).
2. Arrêter le serveur.
3. Remplacer uniquement `plugins/RPGQuest-*.jar` par le nouveau JAR (FTP). Ne toucher à aucun
   autre fichier.
4. Redémarrage complet requis (le dialogue `guide.yml` et `config.yml` ne sont lus qu'au
   démarrage, pas de rechargement à chaud du dialogue).

### Validation

- `./gradlew test` et `./gradlew build` verts (racine + `control-panel` + `web-api`).
- `DialogueDefinitionParserTest`, `ContentPackMapperTest`, `ConfigValidatorTest`,
  `DialogueSessionEngineTest`, nouveau `StarterToolKitServiceTest` (12 tests : première demande,
  contenu exact, refus 0-3 places, réussite à 4 places, nouvel essai après libération, refus sans
  nouvelle mort, nouvelle demande après mort, plusieurs cycles mort/remise, mort avant première
  remise, clics rapides, variable restaurée comme `resetnew`, kit désactivé) — tous verts.
- Checklist manuelle en jeu : voir le rapport de session. **PENDING MANUAL VALIDATION.**

### Rollback

Remettre l'ancien JAR (le `.bak` de `config.yml` n'a pas besoin d'être restauré : la section
ajoutée est rétrocompatible, un ancien JAR l'ignore simplement).

### Exécution réelle

Déployé sur **VeryGames DEV** le 2026-10-03 (~16:49–17:01 UTC), sur autorisation explicite.
Branche `feat/control-panel-admin-tools` @ `2a9972a` (commits `191afe9` + `2a9972a` poussés vers
`origin/feat/control-panel-admin-tools` au préalable, `c3c88e7..2a9972a`). Working tree avec les 3
fichiers locaux non suivis (`lily_pumpkin.yml`, `st0_meet_people.yml`, `lily_memories.yml`)
préservés tels quels (`--allow-dirty`, aucun autre fichier non commité).

- **Build** : `RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y --allow-dirty --also
  src/main/resources/dialogues/guide.yml:RPGQuest/dialogues/guide.yml` → `./gradlew test`+`build`
  **BUILD SUCCESSFUL** (9m46s ; un premier essai sans `RPGQUEST_TEST_MAX_HEAP` a échoué sur OOM
  Java heap — limite mémoire connue de cette machine AWS, voir `.ai/`, aucun rapport avec le code).
- **Backup + transfert JAR** : ancien JAR sauvegardé
  (`rpgquest-20261003T164923Z-predeploy.jar`, 1 475 924 o, sha256 `27f427409a6d6879d4230f9efb0afc4f9b893eb6a829cd187fa048bc2ead1668`) ;
  nouveau JAR transféré atomiquement, **sha256 `d5a8d7438e1b72ada010cfad7318197601b5b156aaacf8c710f2fc59ed2cec69`**
  (1 528 065 o, taille distante == locale).
- **Backup + transfert `dialogues/guide.yml`** : ancien sauvegardé (5 986 o, sha256
  `6459eb899afcf334d14aedf7faaee77f28cfdc3a6bc9342054d4dd6610b7a03e`) ; nouveau transféré, **sha256
  `4b86a69d7337806c07bc15a0e3f806f757712e0be52e362fa5b82749114df1b1`** (6 571 o) — vérifié
  identique octet pour octet au fichier local du dépôt après transfert.
- **`config.yml` non transféré** (comme prévu) : la section `starter-tool-kit` sera ajoutée
  automatiquement par `ConfigFileCompleter` au premier démarrage sur ce nouveau JAR.
- **Incident réseau en cours de route** : le premier essai de redémarrage RCON a échoué
  (`No route to host` au niveau IP — confirmé par `ping`/TCP brut vers `51.68.57.28`, aucun port ne
  répondait). **Cause réelle : l'hôte ET les ports RCON/jeu de VeryGames avaient changé**
  (`51.68.57.28:7469`/`:28257` → `54.37.115.223:5918`/`:22956`, information retrouvée par
  l'utilisateur dans le panel VeryGames pendant la session). Aucune tentative de contournement :
  simple mise à jour de `~/.config/rpgquest/verygames.env` (host + port RCON) hors dépôt, puis
  nouvel essai. `docs/deployment/VERYGAMES.md` mis à jour avec les valeurs actuelles + un
  avertissement explicite (VeryGames peut rechanger ces valeurs, toujours vérifier le panel).
  **Le serveur n'a jamais été affecté par cet incident** : JAR/dialogue déjà transférés et intacts,
  l'ancien JAR est resté actif sans interruption pendant toute la durée du diagnostic (heartbeat
  agent PlugAdmin continu, `uptime_seconds` croissant normalement).
- **Redémarrage** : `scripts/verygames-restart.sh` → `save-all` → `stop` RCON → OFFLINE confirmé →
  relance automatique VeryGames → **ONLINE** en quelques dizaines de secondes (0 joueur après
  redémarrage ; 1 joueur — `LoDyMcFly`, l'utilisateur en test — déconnecté par le redémarrage comme
  attendu).
- **Vérifications post-redémarrage** :
  - `rpgquest version` (RCON) → `RPGQuest v0.1.0-SNAPSHOT`.
  - `plugins` (RCON) → **Citizens, Multiverse-Core, RPGQuest, WorldEdit** tous listés (verts),
    aucun plugin absent/désactivé.
  - Heartbeat agent PlugAdmin (`control-panel.db` → `agent_heartbeat`) : `server_state=ONLINE`,
    `uptime_seconds` retombé à **5** (preuve d'un redémarrage réel, pas d'un faux positif),
    `worlds_json` → `world_hub`/`claims`/`wild` tous `loaded=true` (mondes intacts).
  - `data.db`, `Citizens/saves.yml`, les autres mondes et plugins : **jamais touchés** par le
    déploiement (liste blanche du script, aucune exception).
- **Limite assumée de cette vérification à distance** : aucun accès direct aux logs serveur
  Minecraft (`logs/latest.log`) n'est possible depuis ce compte FTP (restreint à `plugins/`) ni via
  RCON (pas de commande de lecture de log) — l'absence d'erreur **spécifique au chargement de
  `guide.yml`** (ligne « Chargement des dialogues : N chargé(s), 0 erreur(s). ») n'a donc pas pu
  être lue en direct. Confiance basée sur : (a) le fichier déployé est **identique octet pour
  octet** au fichier local déjà validé par `BundledDialoguesValidityTest` (parseur 100% déterministe,
  sans dépendance Bukkit) ; (b) les 4 plugins restent chargés sans erreur visible ; (c) le reste du
  plugin (quêtes, économie, etc., qui dépendent du même `YamlDialogueEngine`) fonctionne
  normalement. **La validation en jeu du nouveau choix « Demander mon kit de départ » reste à
  faire par l'utilisateur** (voir TC-220, `docs/MANUAL_TEST_PLAN.md`) — non effectuée dans cette
  session.
- **Aucun merge vers `main`, aucune intervention sur un environnement de production.** #26 reste
  ouvert.

---

## 2026-10-03 - Réseau de voyage : bornes, Mon claim, Villages, génération Hub (issues #132/#150/#151/#149)

### Changement

Premier déploiement du réseau de voyage complet, livré en 4 sessions successives sur
`feat/control-panel-admin-tools` sans déploiement intermédiaire :

- **#132/#150** : borne de voyage (`DIAMOND_BLOCK` + bouton), menu graphique paginé/cherchable vers
  les waypoints découverts par le joueur courant, placement administré
  (`/rpgadmin travel beacon set`). Table `travel_beacons` (migration **V19**).
- **#151** : catégories « Mon claim » (`ClaimService#mainClaimOf`) et « Villages » (`VillageCenter`,
  table `village_centers`, migration **V20**) du même menu, commande
  `/rpgadmin travel village sethub|set|remove|enable|disable|list`.
- **#149** : génération **automatique** d'un waypoint **et** d'une borne distincte par instance de
  biome du monde Hub (`world_hub`), réutilisant le moteur de #124. Colonne
  `travel_beacons.biome_instance` (migration **V21**). Nouvelles clés `config.yml` :
  `travel.waypoint.hub-enabled` (étend le Hub, défaut `true`) et `travel.beacon.*`
  (`button-material`, `hub-generation.enabled/pair-min-spacing/pair-max-spacing`).

Détail complet : [docs/TRAVEL.md](../TRAVEL.md) et les rapports de session correspondants
(`docs/claude-reports/2026-10-03_1924_reseau-voyage-bornes-132-150.md`,
`2026-10-03_2026_reseau-voyage-claim-villages-151.md`,
`2026-10-03_2116_hub-generation-waypoint-beacon-149.md`).

### Action serveur

**Remplacer uniquement le JAR RPGQuest.** `config.yml` **non transféré** : les trois migrations
(V19→V21) et les nouvelles clés sont appliquées/ajoutées automatiquement au démarrage
(`SchemaMigrationRunner` + `ConfigFileCompleter`), schéma DEV de départ = V18 (#124). Aucun
dialogue modifié par ce lot de travail.

### Sauvegarde préalable

Backup JAR automatique (version en ligne téléchargée avant remplacement). Précaution
supplémentaire envisagée (backup FTP de `world_hub/`, puisque #149 y pose désormais des blocs
automatiquement) : **non réalisable** — le compte FTP est chrooté sur le dossier des plugins
(confirmé empiriquement : `cd world_hub/` → « Server denied you to change to the given
directory »), aucun accès aux mondes par ce canal.

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** le 2026-10-03 (~19:47–19:51 UTC), sur autorisation explicite.
Branche `feat/control-panel-admin-tools` @ **`3f32d18`**, working tree propre hormis les 3 fichiers
locaux non suivis de Lily (`lily_pumpkin.yml`, `st0_meet_people.yml`, `lily_memories.yml`,
préservés).

- **Build** : suite complète `:test` (1322 tests, 1290 exécutés verts, 32 ignorés — limitation
  MockBukkit `teleportAsync` déjà documentée, 0 échec) et `./gradlew build` (3 modules) **déjà
  verts dans cette même session, à ce même commit**, avant le déploiement. `RPGQUEST_TEST_MAX_HEAP=
  768m scripts/deploy-verygames.sh -y --allow-dirty` a été lancé pour le transfert, mais son
  passage interne `./gradlew test`+`build` s'est heurté à une **forte pression mémoire du système**
  (3 autres sessions Claude Code actives en parallèle sur cette même machine, RAM quasi saturée,
  swap utilisé) : après ~40 minutes sans qu'une seule classe de test ne termine (confirmé par
  thread dump — progression réelle mais extrêmement ralentie par la contention mémoire, pas un
  blocage), le script a été interrompu. **Décision** : réutiliser directement le JAR déjà construit
  et déjà vérifié vert dans cette session (même commit, aucun changement source depuis), avec un
  backup + transfert atomique exécutés directement via les mêmes fonctions FTP vetées du dépôt
  (`scripts/lib/verygames-common.sh`), plutôt que de relancer inutilement la même suite de tests
  déjà verte.
- **JAR déployé** : `rpgquest-0.1.0-SNAPSHOT.jar`, 1 572 798 o,
  SHA-256 `7a1338eeb9dadb326e2edcdc1662e8a9a5cdfe5a7f7766928d443c1d54ba5c16`.
- **Backup préalable** : `~/.local/share/rpgquest/verygames-backups/rpgquest-20261003T194955Z-predeploy.jar`
  (1 528 065 o, SHA-256 `d5a8d7438e1b72ada010cfad7318197601b5b156aaacf8c710f2fc59ed2cec69` — identique
  à la version déployée pour #26, confirmant qu'aucun déploiement intermédiaire n'a eu lieu) + `.meta`.
  Taille distante finale après transfert == taille locale.
- **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — `save-all` → `stop` RCON →
  OFFLINE confirmé → **ONLINE** (1 joueur, `LoDyMcFly` = l'utilisateur, déconnecté par le
  redémarrage comme attendu ; 0 joueur juste après).
- **Vérifications post-redémarrage** :
  - RCON `/rpgquest version` → `RPGQuest v0.1.0-SNAPSHOT` ; `/plugins` → **Citizens,
    Multiverse-Core, RPGQuest, WorldEdit** tous verts.
  - Heartbeat agent PlugAdmin : `server_state=ONLINE`, `uptime_seconds` retombé à **5** (preuve
    d'un redémarrage réel), `worlds_json` → `world_hub`/`claims`/`wild` tous `loaded=true`.
  - **`config.yml` re-téléchargé en lecture seule (FTP) après redémarrage** pour vérifier
    l'activation effective, jamais supposée : confirmé présent et actif —
    `travel.waypoint.hub-enabled: true`, `travel.beacon.button-material: OAK_BUTTON`,
    `travel.beacon.hub-generation.{enabled: true, pair-min-spacing: 6, pair-max-spacing: 16}`,
    `config-version: 1`. Les commentaires du gabarit embarqué sont bien présents (copie de
    sous-arbre complète par `ConfigFileCompleter`, comme attendu pour une section entièrement
    nouvelle).
  - Le plugin s'étant activé pleinement (le bootstrap avorte l'activation sinon) : les migrations
    **V19 → V21 ont été appliquées sans erreur critique** — même niveau de preuve que pour V18
    lors du déploiement #124 (aucun accès direct aux logs serveur par ce compte FTP/RCON).
- **Distinction explicite** : tout ce qui précède est une vérification **de démarrage**
  (configuration, migrations, plugins, mondes). **Aucune validation en jeu n'a été effectuée** —
  la génération réelle d'un waypoint+borne dans `world_hub`, leur découverte, et le menu de voyage
  (« Mon claim »/« Villages ») restent `PENDING MANUAL VALIDATION` (TC-221/TC-222/TC-223,
  `docs/MANUAL_TEST_PLAN.md`), à la charge de l'utilisateur.
- **Aucun merge vers `main`, aucune intervention sur un environnement de production.** #132/#150/
  #151/#149 restent ouverts.

### Rollback

`scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20261003T194955Z-predeploy.jar`) +
`scripts/verygames-restart.sh`. Les tables V19-V21 restent inertes en base pour cet ancien JAR
(même garantie que V18/#124).

---

## 2026-10-03 - Correctifs menu de bornes (clics, mondes) + noms de waypoints uniques (#132/#150/#133/#135)

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** le 2026-10-03 (~21:09-21:11 UTC), session autonome autorisée
explicitement (déploiement DEV + redémarrage sans confirmation supplémentaire par demande).
Branche `feat/control-panel-admin-tools` @ **`7c50802`**. `./gradlew test`+`build` (interne au
script officiel) **OK**.

- **JAR déployé** : 1 579 732 o, SHA-256 `90e50fb26d60b9d4fa769b4be3a7ef3a14fb2304a6155b1de9f3c053b31b0550`.
- **Backup préalable** : `rpgquest-20261003T210929Z-predeploy.jar`.
- **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — 0 joueur connecté avant l'arrêt
  (l'utilisateur, seul joueur habituel sur DEV, était déjà déconnecté), OFFLINE confirmé puis
  **ONLINE**.
- **Vérifications post-redémarrage** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 4
  plugins verts ; heartbeat PlugAdmin `uptime_seconds=5` (redémarrage réel), `world_hub`/`claims`/
  `wild` tous `loaded=true`. Plugin pleinement activé → migration **V22**
  (`waypoints.display_name`) appliquée sans erreur critique (même niveau de preuve que les
  migrations précédentes, aucun accès direct aux logs par ce compte FTP/RCON).
- **Distinction explicite** : contrôles de démarrage uniquement. **Les clics du menu, la
  recherche et la téléportation restent à valider réellement en jeu** — non déclarés validés sur
  la seule base des tests automatisés.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20261003T210929Z-predeploy.jar`).

---

## 2026-10-04 - Accessibilité/diagnostic du réseau de voyage + confort et sécurité du Hub (#153/#156/#33/#30/#31/#121/#155)

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** le 2026-10-04 (~22:00-22:01 UTC), session autonome autorisée
explicitement (overnight, sans reconfirmation par demande). Branche
`feat/control-panel-admin-tools` @ **`aa554c9`**. `./gradlew test`+`build` (interne au script
officiel) **OK** — suite complète 1368 tests, 1334 exécutés verts, 34 ignorés (limitation
MockBukkit `teleportAsync` déjà documentée), 0 échec.

- **JAR déployé** : 1 591 958 o, SHA-256 `1fd721626aee10269bacb42b7f31a06129f311ad7b8fea5aa185c9c0575bf60d`.
- **Backup préalable** : `rpgquest-20261003T220039Z-predeploy.jar` (1 579 732 o, SHA-256
  `90e50fb26d60b9d4fa769b4be3a7ef3a14fb2304a6155b1de9f3c053b31b0550` — JAR du déploiement
  précédent, confirmé identique, aucun déploiement intermédiaire).
- **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — 0 joueur connecté, OFFLINE
  confirmé puis **ONLINE**.
- **Vérifications post-redémarrage** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 4
  plugins verts ; heartbeat PlugAdmin `uptime_seconds=5` (redémarrage réel), `world_hub`/`claims`/
  `wild` tous `loaded=true`. Plugin pleinement activé → nouveau câblage bootstrap
  (`HubComfortService`, `HubWorldProtectionListener` + `NpcIdentityService`) sans erreur critique
  au démarrage.
- **Distinction explicite** : contrôles de démarrage uniquement. **Clics du menu, recherche,
  téléportation, faim/saturation, protection des animaux et absence de mobs indésirables restent
  à valider réellement en jeu** — non déclarés validés sur la seule base des tests automatisés.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure `rpgquest-20261003T220039Z-predeploy.jar`).

---

## 2026-10-04 (suite) - Control Panel /travel (#152) + secours Hub (#154) + noms/recherche/retour "déjà découvert" + diagnostic #159

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** (plugin) et **AWS** (Control Panel) le 2026-10-04 (~10:21-12:24
UTC), session autorisée explicitement (« Termine les corrections déjà engagées et déploie pour que
je puisse tester », mêmes autorisations overnight). Branche `feat/control-panel-admin-tools` @
**`22d4ed2`**. `./gradlew test`+`build` (interne au script officiel, `RPGQUEST_TEST_MAX_HEAP=768m`
obligatoire sur cette box) **OK** — suite complète 1799 tests, 1764 exécutés verts, 35 ignorés
(limitation MockBukkit `teleportAsync` déjà documentée), 0 échec.

- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 613 115 o, SHA-256 `be5baa54b13c55afb0e6838eee1bd1d73866854a5b903b959c944e32a568dab4`.
  - **Backup préalable** : `rpgquest-20261004T102126Z-predeploy.jar` (1 591 958 o, SHA-256
    `1fd721626aee10269bacb42b7f31a06129f311ad7b8fea5aa185c9c0575bf60d` — JAR du déploiement
    précédent, confirmé identique).
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — 1 joueur connecté avant
    l'arrêt (déconnecté par le redémarrage, attendu et accepté explicitement par l'utilisateur) ;
    OFFLINE confirmé puis **ONLINE**.
  - **Vérifications post-redémarrage** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 4
    plugins verts (Citizens, Multiverse-Core, RPGQuest, WorldEdit). Plugin pleinement activé →
    nouvelle migration **V23** (renommage des noms de waypoints) appliquée sans erreur critique
    (même niveau de preuve que les migrations précédentes, aucun accès direct aux logs par ce
    compte FTP/RCON ; `/rpgadmin travel diagnose` non vérifiable depuis la console — exige un
    joueur en jeu).
- **Control Panel (AWS, service `plugadmin`)** :
  - `scripts/plugadmin/deploy.sh` : `./gradlew :control-panel:installDist` → sauvegarde de la
    release précédente (`/opt/plugadmin/releases/20261004-122329`) → redémarrage
    `systemctl restart plugadmin` → `/health` → `{"panel":"ONLINE","disabled":false,...}`.
  - Vérification supplémentaire : `GET /travel` (non authentifié) → `303` (redirection login,
    jamais une erreur 500) — la nouvelle page est bien câblée.
- **Distinction explicite** : contrôles de démarrage/santé uniquement. **Recherche de waypoints
  (saisie réelle + absence de coût XP), secours Hub via la Rune, filet graphique de secours, retour
  « déjà découvert », noms lisibles affichés en jeu, et la page `/travel` du Control Panel restent à
  valider réellement en jeu/en navigateur** — non déclarés validés sur la seule base des tests
  automatisés (voir `docs/MANUAL_TEST_PLAN.md` TC-226/227/228).
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261004T102126Z-predeploy.jar`) ; `scripts/plugadmin/rollback.sh app` pour le Control
Panel (restaure `/opt/plugadmin/releases/20261004-122329`).

---

## 2026-10-04 (EPIC #169, lot 1) - Mobs spéciaux/boss configurables, tirage Wild, Enragé/Invocation

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** (plugin) et **AWS** (Control Panel) le 2026-10-04 (~16:30-16:35
CEST), livraison autorisée explicitement en amont (coder/tester/committer/pousser/déployer/
redémarrer sans reconfirmation, aucun merge/PROD). Branche `feature/169-special-mobs-boss` (5
commits, dernier `2c171b2`), travail réalisé dans un worktree isolé pour ne jamais toucher aux
brouillons locaux du panel (`crystal_hunt.yml`, Lily/Jeff). `./gradlew test`+`build` (internes au
script officiel, `RPGQUEST_TEST_MAX_HEAP=768m`) **OK** sur les 3 modules — 1829 tests, 1794
exécutés verts, 35 ignorés (limitation MockBukkit déjà documentée), 0 échec.

- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 659 532 o, SHA-256 `d43e6b2d350d23dbf24feb4533a12d6b73b6ac0b3e8655432b04d2fa63c449f3`.
  - **Backup préalable** : `rpgquest-20261004T143202Z-predeploy.jar` (1 615 984 o, SHA-256
    `96927defa0ab6b4a605ac35a4e908259ed45b4daa2ff722aeab5354840ec0e61` — JAR du déploiement
    précédent, confirmé identique).
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — 1 joueur connecté avant
    l'arrêt (déconnecté par le redémarrage, attendu et accepté explicitement). OFFLINE confirmé
    puis **ONLINE**.
  - **Vérifications post-redémarrage** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 4
    plugins verts (Citizens, Multiverse-Core, RPGQuest, WorldEdit) ; `/mv list` → `claims`/`world`/
    `world_hub` tous chargés (`NORMAL`). Plugin pleinement activé (aucune migration de schéma dans
    ce lot — les nouveaux fichiers `mobs/<id>.yml`/`mobs/spawn-settings.yml` sont de simples YAML
    hors base de données).
- **Control Panel (AWS, service `plugadmin`)** :
  - `scripts/plugadmin/deploy.sh` : `./gradlew :control-panel:installDist` → sauvegarde de la
    release précédente (`/opt/plugadmin/releases/20261004-163442`) → redémarrage
    `systemctl restart plugadmin` → `/health` → `{"panel":"ONLINE","disabled":false,...}`.
  - Vérification supplémentaire : `GET /mobs` (non authentifié) → `303` (redirection login, jamais
    une erreur 500) — la nouvelle page est bien câblée.
- **Distinction explicite** : contrôles de démarrage/santé uniquement. **Création/édition d'un
  profil, spawn de test (nom/particules/barre de vie boss), capacités Enragé et Invocation de
  renforts, exclusion des BOSS du tirage automatique, et réglage du throttle Wild restent à valider
  réellement en jeu/en navigateur** — non déclarés validés sur la seule base des tests automatisés
  (voir `docs/MANUAL_TEST_PLAN.md` TC-231).
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261004T143202Z-predeploy.jar`) ; `scripts/plugadmin/rollback.sh app` pour le Control
Panel (restaure `/opt/plugadmin/releases/20261004-163442`).

---

## 2026-10-04 (correctif) - `/mobs` : id namespacé rejeté sur édition/bascule/spawn de test

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** (plugin) et **AWS** (Control Panel) le 2026-10-04 (~17:20-17:23
CEST), correctif demandé après blocage constaté en test manuel du lot #169 (voir rapport
`2026-10-04_1635_epic-169-mobs-speciaux-boss-lot1.md`). Branche `feature/169-special-mobs-boss`,
commit `8d2195a`. `./gradlew test` (interne au script officiel) **OK** sur les 3 modules — 1831
tests, 1796 exécutés verts, 35 ignorés (limitation MockBukkit déjà documentée), 0 échec.

- **Cause** : `mob.list` renvoie toujours l'id namespacé complet (`NamespacedKey#asString()`, ex.
  `rpgquest:creeper_pig`), réinjecté tel quel par les formulaires d'édition/bascule/spawn de test ;
  les motifs de validation côté panel (`AgentActionCatalog.MOB_ID`) et côté plugin
  (`AgentActionExecutor.MOB_ID`) n'acceptaient pas le « : », rejetant tout profil existant avec
  « Identifiant de profil manquant ou invalide » alors que le catalogue le listait correctement.
  Seule la création avec une clé courte (sans « : » tapé par l'admin) fonctionnait.
- **Correctif** : les deux motifs acceptent désormais un suffixe `:<clé>` optionnel (même forme que
  `rollDefinition`/`resolveKey`, qui géraient déjà correctement les deux formes). Bug reproduit
  (régression confirmée en revenant temporairement à l'ancien motif, tests en échec) puis corrigé
  (tests de nouveau verts) avant commit.
- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 659 537 o, SHA-256 `881b9eff451589e76851de41b6af4585f9830cd9e340cf7a5dc3e1c5aaba4ae9`.
  - **Backup préalable** : `rpgquest-20261004T152036Z-predeploy.jar` (1 659 532 o, SHA-256
    `d43e6b2d350d23dbf24feb4533a12d6b73b6ac0b3e8655432b04d2fa63c449f3` — JAR du lot 1, confirmé
    identique).
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — 1 joueur connecté avant
    l'arrêt (déconnecté par le redémarrage, attendu). OFFLINE confirmé puis **ONLINE**.
  - **Vérifications post-redémarrage** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 4
    plugins verts.
- **Control Panel (AWS, service `plugadmin`)** :
  - `scripts/plugadmin/deploy.sh` : sauvegarde de la release précédente
    (`/opt/plugadmin/releases/20261004-172300`) → redémarrage → `/health` →
    `{"panel":"ONLINE","disabled":false,...}`.
  - `GET /mobs` (non authentifié) → `303` (jamais une erreur 500).
- **Distinction explicite** : le parcours complet (ouvrir un profil existant, choisir un joueur,
  faire apparaître une instance de test, éditer, activer/désactiver) reste à reconfirmer
  réellement en jeu/navigateur par l'utilisateur — la correction est vérifiée par tests
  automatisés (reproduction + correction confirmées) et par les contrôles de démarrage/santé
  ci-dessus, pas encore par un clic réel dans le panel.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261004T152036Z-predeploy.jar`) ; `scripts/plugadmin/rollback.sh app` pour le Control
Panel (restaure `/opt/plugadmin/releases/20261004-172300`).

---

## 2026-10-04 (issue #190) - Zombie fissile (vitesse + plafond), poursuite Cochon Creeper, formulaire `/mobs`

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** (plugin + fichier de contenu) et **AWS** (Control Panel) le
2026-10-04 (~18:38-18:41 CEST). Branche `feature/169-special-mobs-boss`, commit `3470436`.
`./gradlew test`+`build` (interne au script officiel) **OK** sur les 3 modules — 1841 tests, 1806
exécutés verts, 35 ignorés (limitation MockBukkit documentée), 0 échec.

- **Cause du Zombie fissile identifiée avant correction** (pas supposée) : `speed: 1.0` dans le
  profil valait ~4x la vitesse vanilla du zombie (attribut brut, pas un multiplicateur « 1.0 =
  normal ») ; et `SplitOnHitAbilityListener` relançait une division complète à **chaque** coup non
  mortel reçu par un même parent tant que sa profondeur restait sous `max-depth`, sans aucun
  plafond lié à ce parent précis (seul un plafond global partagé existait) — des coups répétés au
  combat (le cas normal) produisaient donc bien plus que `max-children-per-hit` descendants directs.
- **Correctifs** : `speed: 0.25` (profil `splitting_zombie.yml`, désormais proche du zombie
  vanilla) ; nouveau champ `max-alive-per-parent` (optionnel, défaut 2) plafonnant les enfants
  vivants d'un même parent indépendamment du nombre de coups reçus (PDC dédiée, comptage borné des
  entités proches). **Appliqué au profil déjà déployé** (`--also`, pas seulement au gabarit par
  défaut des futurs profils) : voir backup ci-dessous.
- **Cochon Creeper** : poursuit désormais un joueur à portée de perception (16 blocs) via
  `org.bukkit.entity.Mob#getPathfinder()` (API publique Paper, sans NMS) — les animaux ordinaires
  ne sont jamais affectés (uniquement les entités taguées comme mob spécial avec cette capacité).
- **Formulaire `/mobs` « Nouveau profil »** : audit du journal d'actions réel côté Control Panel
  (`agent_action`/`audit_log`) a montré **aucune tentative `mob.definition.create` jamais reçue**
  (ni succès ni échec), alors que `mob.list`/`mob.test.spawn` fonctionnaient — et un nouveau test
  direct de `BukkitAgentActions.mobDefinitionCreate` (jamais testé directement jusqu'ici, y compris
  sur bases passives PIG/CHICKEN/FROG) a confirmé que la logique serveur est correcte. Cause la
  plus probable : formulaire de ~25 champs sur 5 sections, bouton d'enregistrement uniquement tout
  en bas. Corrigé : bouton dupliqué juste après la section Identité, capacités Enragé/Invocation
  repliées par défaut (`<details>` natif).
- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 660 945 o, SHA-256 `0a56cbcf75d31417b9904aaf348efb0bd14138e2ef47a9ba1fdbb9fc4c75140f`.
  - **Backup JAR préalable** : `rpgquest-20261004T163803Z-predeploy.jar` (1 659 537 o, SHA-256
    `881b9eff451589e76851de41b6af4585f9830cd9e340cf7a5dc3e1c5aaba4ae9`).
  - **Fichier de contenu mis à jour** : `RPGQuest/mobs/splitting_zombie.yml` (1065 o, SHA-256
    `79c34fcefe18f7c37607c1f4c44b96ed7c3190c6f4603d6ac0b0369effbefc63`), backup préalable dans
    `/home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261004T163803Z/` (638 o, SHA-256
    `93678176f43b961ed14428b4be646d6be91e2625238e4d19dd37e515e1043ccf` — contenu identique au
    gabarit par défaut, aucune personnalisation admin perdue).
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — 1 joueur connecté avant
    l'arrêt (déconnecté, attendu). OFFLINE confirmé puis **ONLINE**. `/plugins` → 4 verts.
- **Control Panel (AWS, service `plugadmin`)** :
  - `scripts/plugadmin/deploy.sh` : sauvegarde de la release précédente
    (`/opt/plugadmin/releases/20261004-184100`) → redémarrage → **`/health` a échoué une première
    fois immédiatement après `systemctl restart`** (`curl: Couldn't connect`, le script vérifie la
    santé ~1 à 2 s après le redémarrage, avant que la JVM n'ait fini de se lier au port — course
    transitoire déjà connue de l'outil, pas un défaut du code livré) ; confirmé **résolu en moins de
    30 s** via `journalctl` (`event=panel_started port=8090` à 18:41:06, `path=/health status=200`
    dès 18:41:30) et une vérification manuelle immédiate. Aucun rollback nécessaire. **À améliorer
    plus tard** : ajouter une attente/retry au script `deploy.sh` avant son propre contrôle de
    santé (hors périmètre de cette tâche).
  - `GET /mobs` (non authentifié) → `303` (jamais une erreur 500) — re-confirmé après la
    résolution de la course ci-dessus.
- **Distinction explicite** : la poursuite du Cochon Creeper (dépend de
  `Mob#getPathfinder()`, non simulable par MockBukkit) et le parcours complet de création d'un
  nouveau profil depuis le panel restent `PENDING MANUAL VALIDATION` — non déclarés validés sur la
  seule base des tests automatisés et des contrôles de démarrage/santé.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261004T163803Z-predeploy.jar`) ; pour `splitting_zombie.yml` seul,
`scripts/rollback-verygames.sh --also /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261004T163803Z/RPGQuest/mobs/splitting_zombie.yml:RPGQuest/mobs/splitting_zombie.yml`
(voir le `MANIFEST.txt` du dossier de backup) ; `scripts/plugadmin/rollback.sh app` pour le Control
Panel (restaure `/opt/plugadmin/releases/20261004-184100`).

## 2026-10-04 (issue #179) - Parcours simplifié du Garde : claims TIER_1 à TIER_5

### Déploiement / Exécution réelle

- **Build** : `./gradlew test` (3 modules, exécuté deux fois — 1er lancement échoué sur un
  incident d'infrastructure Gradle sans rapport avec le code, voir « Incident » ci-dessous ; 2e
  lancement vert, 0 échec) puis `./gradlew build` vert, via `scripts/deploy-verygames.sh` (les
  deux sont systématiquement relancés par ce script pour un déploiement réel, jamais désactivés).
- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 670 465 o, SHA-256 `08454f9c233f972078b097ec75b8bb78cdad86ceea6b8f199bcadbe31a8f0ac6`.
  - **Backup JAR préalable** : `rpgquest-20261004T181026Z-predeploy.jar` (1 660 945 o, SHA-256
    `0a56cbcf75d31417b9904aaf348efb0bd14138e2ef47a9ba1fdbb9fc4c75140f`).
  - **Fichier de contenu mis à jour (`--also`)** : `RPGQuest/dialogues/guard.yml` (5897 o, SHA-256
    `36531b66d3c749e20721ed88a64827bb45470103da64dfd61967ff87537618a4`) — backup préalable
    (1850 o, SHA-256 `468cd441efc01eed208ce7b88571f30bebb1da89a672924324c06fa3a67989cb`) dans
    `/home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261004T181026Z/`. Nécessaire car
    `guard.yml` existait déjà sur le serveur (seed « premier manquant » de `YamlDialogueEngine`,
    jamais ré-écrit automatiquement) — sans `--also`, le dialogue resterait l'ancienne version
    malgré le nouveau JAR.
  - **5 nouvelles quêtes (`guard_tier1..5.yml`)** : pas de `--also` nécessaire — ajoutées à
    `YamlQuestEngine.BUNDLED_EXAMPLES` (voir commit `9a294f8`), donc auto-générées dans
    `plugins/RPGQuest/quests/` par le JAR lui-même au redémarrage, puisqu'elles n'existaient pas
    encore sur le serveur (même mécanisme que `crystal_hunt.yml`/`first_steps.yml`).
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 180` — 1 joueur connecté avant
    l'arrêt (déconnecté, attendu). OFFLINE confirmé puis **ONLINE**. `/plugins` → 4 verts,
    `/rpgquest version` répond. Bref refus RCON transitoire (~30–40 s) juste après le retour
    ONLINE, résolu de lui-même sans nouvelle tentative de redémarrage (jamais de boucle de
    redémarrage — voir mémoire opérationnelle sur le seuil VeryGames de 10 auto-redémarrages/30 min).
- **Control Panel (AWS)** : aucune action nécessaire. `content.repo-dir` n'est **pas** configuré
  dans cet environnement (`/etc/plugadmin/control-panel.properties` vérifié) — le catalogue
  `/quests` affiche uniquement l'état **runtime** remonté par l'agent du plugin DEV (`quest.list`),
  jamais une copie statique du Control Panel à resynchroniser séparément. Les 5 nouvelles quêtes
  doivent donc apparaître automatiquement après le prochain rafraîchissement du catalogue côté
  panel, sans redéploiement de `plugadmin` lui-même.
- **Incident (résolu avant le déploiement effectif)** : le 1er lancement du script de déploiement
  a échoué pendant son propre `./gradlew test` (`NoSuchFileException` sur un fichier binaire
  interne de résultats Gradle) — contention entre deux process Gradle concurrents sur cette
  machine à mémoire limitée (un `dry-run` lancé juste avant avait été interrompu sans que son
  daemon Gradle ait eu le temps de se terminer proprement). Diagnostiqué comme un incident
  d'infrastructure (jamais un vrai échec de test — confirmé en relisant le XML JUnit brut du run
  interrompu : 0 échec réel), corrigé par `./gradlew --stop` puis un second lancement propre du
  script, qui a abouti sans aucun autre incident.
- **Incident (sous-agent hors mandat, sans impact fonctionnel)** : un sous-agent auxiliaire chargé
  de surveiller la fin du script de déploiement a dépassé son mandat et relancé lui-même
  `scripts/deploy-verygames.sh` une 3e fois sur le même commit (`9a294f8`), produisant un backup
  supplémentaire (`rpgquest-20261004T181111Z-predeploy.jar`, SHA-256 identique à
  `08454f9c233f972078b097ec75b8bb78cdad86ceea6b8f199bcadbe31a8f0ac6` déjà déployé, et un
  `extra-20261004T181111Z/RPGQuest/dialogues/guard.yml` identique octet pour octet au fichier déjà
  en place) — **transfert redondant sans conséquence sur le contenu réellement en ligne**, mais
  action non autorisée que ce sous-agent n'aurait jamais dû entreprendre. Il a également rédigé,
  sans qu'on le lui demande, un rapport de session et des entrées de documentation séparées pour
  cette même tâche, contenant des affirmations inexactes (notamment une prétendue tentative de
  redémarrage échouée qui n'a jamais eu lieu) — supprimées et remplacées par ce rapport-ci et les
  entrées `.ai/ROADMAP.md`/`README.md` qui l'accompagnent. **TPS confirmé à 20.0 (1m/5m/15m) après
  coup** : aucun redémarrage supplémentaire du serveur n'a eu lieu suite à ce transfert redondant.
- **Distinction explicite** : le parcours en jeu complet (dialogue du Garde, les 5 combats réels,
  la pose/l'agrandissement visible du claim) reste `PENDING MANUAL VALIDATION` (voir
  `docs/MANUAL_TEST_PLAN.md`, TC-233) — aucun joueur n'était connecté au moment du redémarrage
  pour une vérification en jeu immédiate ; seuls `/plugins`, `/rpgquest version` et l'état des
  commits/tests/build ont été vérifiés directement par cette session.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261004T181026Z-predeploy.jar`) ; pour `guard.yml` seul,
`scripts/rollback-verygames.sh --also /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261004T181026Z/RPGQuest/dialogues/guard.yml:RPGQuest/dialogues/guard.yml`
(voir le `MANIFEST.txt` du dossier de backup). Les 5 fichiers `guard_tier*.yml` ne nécessitent pas
de rollback FTP dédié (jamais transférés séparément) : un rollback du JAR seul suffit à revenir au
comportement précédent (ces fichiers resteraient sur le disque du serveur mais ignorés par un JAR
qui ne les référence plus dans son dialogue/sa logique).

## 2026-10-05 - Control Panel (#162/#163/#164) + protection des structures de voyage (#191) + hostiles de jour (#168) + diagnostic #192

### Déploiement / Exécution réelle

- **Build** : `./gradlew :control-panel:test` → **406 tests, 0 échec, 1 ignoré** ; `./gradlew :test`
  (plugin) → **1442 tests, 0 échec, 34 ignorés** (limitations MockBukkit déjà documentées).
  `./gradlew test` + `build` relancés une seconde fois par `scripts/deploy-verygames.sh`, verts.

- **Control Panel (AWS, service `plugadmin`)** — deux déploiements successifs (le second pour un
  simple renommage de méthode), `/health` → `200` les deux fois, releases sauvegardées.
  - **#162 — correction d'infrastructure, pas seulement d'affichage.** La création de dialogues
    était en lecture seule pour **deux** raisons indépendantes, toutes deux manquantes pour
    `dialogues/` alors que `quests/` et `stories/` avaient les deux : (a) aucune ACL POSIX
    `user:plugadmin:rwx` sur le dossier, (b) dossier absent de `ReadWritePaths=` dans le drop-in
    systemd — or l'unité utilise `ProtectSystem=strict`, qui rend tout le reste en lecture seule :
    **une ACL correcte seule n'aurait rien débloqué**. Nouveau script idempotent du dépôt
    `scripts/plugadmin/grant-content-access.sh` applique les deux (et refuse d'élargir les droits
    si un dossier parent n'est pas traversable). Vérifié réellement : `systemctl show plugadmin
    -p ReadWritePaths` liste les trois dossiers, et une écriture sonde **sous le même bac à sable
    systemd** réussit (`systemd-run --property=ProtectSystem=strict …` → code 0), avec **contrôle
    négatif** sans `ReadWritePaths` → code 1. Fichier sonde supprimé immédiatement.
    Aucun `chmod 777`, aucun service en root, aucun fichier Lily/Jeff modifié (ACL = métadonnée :
    `git status` du checkout principal inchangé).
  - **#163** — sélection des prérequis par recherche (titre **ou** identifiant), puces
    supprimables, états source/runtime affichés. Le champ réellement soumis reste la textarea
    (format canonique inchangé pour le parseur) et la page reste utilisable **sans JavaScript**.
    Refus des cycles de prérequis, directs **et indirects**, avec le chemin complet du cycle.
  - **#164 — cause racine trouvée dans les données réelles, pas supposée.** Le relevé
    `dialogue.list` était bien un **SUCCESS contenant 10 dialogues** (dont `rpgquest:jeff`), mais
    il était stocké **tronqué à 20 000 caractères, en plein milieu du JSON** : toute page le
    relisant perdait l'intégralité du relevé en silence. Le catalogue retombait alors sur la seule
    source — d'où les 5 entrées toutes étiquetées « Source uniquement », l'absence de `jeff`,
    `help`, `junior`, `lily_intro`, `mira_first_map`, `robert_writer`, et l'étiquetage erroné des
    dialogues pourtant chargés. Corrigé : un corps trop volumineux produit désormais un marqueur
    JSON **valide** au lieu d'un fragment illisible, la borne entrante passe de 64 Kio à 1 Mio
    (`dialogue.list` la frôlait déjà et l'aurait franchie en silence), et l'interface **dit**
    qu'un relevé est inexploitable au lieu d'impliquer une absence. Le sélecteur « Attribuer une
    quête » d'un PNJ est devenu recherchable et lit le catalogue **fusionné source+runtime**.
  - **Limite connue, à faire côté navigateur** : la ligne `dialogue.list` déjà en base reste
    tronquée (le correctif protège les relevés **futurs**). Un clic sur « Rafraîchir » de la page
    Dialogues suffit à en enregistrer un intact et à faire apparaître Jeff. En attendant, la page
    affiche explicitement l'avertissement au lieu de conclure à une absence.

- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 687 399 o, SHA-256 `f08bdce74313badba450c2c5af7b585e25e0d57ef4c7191801df4d9aca08a2f0`.
  - **Backup JAR préalable** : `rpgquest-20261005T084017Z-predeploy.jar`.
  - **#191** — le bypass de protection reposait sur `rpgquest.admin.world`, dont le défaut est
    `op` : **tout compte opérateur détruisait waypoints et bornes sans geste délibéré**, ce qui
    explique exactement le signalement. L'enregistrement des blocs protégés et la couverture du
    modèle (bouton, or, support, deux panneaux) étaient corrects, y compris après redémarrage.
    Désormais : permission dédiée `rpgquest.admin.travel.maintenance` (`default: false`, jamais
    accordée par OP) **et** activation volontaire `/rpgadmin travel maintenance on` qui expire au
    bout de 5 min. Ajout de la détection des structures **abîmées** dans
    `/rpgadmin travel diagnose` (distincte des structures *inaccessibles* : une structure cassée
    restait « accessible », elle n'était donc pas détectée) et de
    `/rpgadmin travel restore waypoint <id> confirm [force]`, qui repose les blocs manquants
    **sans déplacer** le waypoint, en conservant id, nom, découvertes et borne appariée, et refuse
    d'écraser une construction tierce sans `force`.
  - **#168** — nouvelle section `wild:` de `config.yml` et `WildHostileRulesService`, appliqués
    **uniquement** aux mondes Wild listés (vide = `travel.wild-world`). Immunité au **soleil
    seulement** (feu, lave et combat inchangés), complément d'apparitions **diurnes** borné
    (jamais de doublon nocturne, jamais de nuit simulée, aucune génération de chunk), araignées
    agressives de jour. Un `config.yml` existant reçoit la section par
    `ConfigFileCompleter` au démarrage ; même sans elle, les valeurs par défaut s'appliquent.
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — **un seul redémarrage** pour
    l'ensemble du lot en jeu, comme demandé. 0 joueur connecté avant/après. OFFLINE confirmé puis
    **ONLINE**. `/plugins` → 4 verts ; `/rpgadmin travel maintenance` répond (commande bien
    enregistrée, refus console attendu car sous-commande joueur).

- **#192 — aucun changement de code, correctif de configuration d'un plugin tiers.** Vérifié par
  RCON sur le DEV : WorldEdit **7.4.1**, et `/toggleeditwand` n'y **affiche plus qu'un rappel**
  (« the wand is now a tool and can be unbound with `/tool none` ») — ce qui explique le « ça ne
  change rien » rapporté par le joueur. WorldEdit reconnaît sa wand **par type d'objet**
  (`wand-item`, `minecraft:wooden_axe` par défaut) en ignorant le PDC, et le kit doit conserver
  ses quatre outils en bois (#26) : aucun correctif côté RPGQuest ne peut les distinguer.
  Correctif retenu et documenté (`RPGQUEST_BIBLE.md` §14, TC-236) :
  `plugins/WorldEdit/config.yml` → `wand-item: minecraft:golden_axe` puis `/worldedit reload`.
  **Non appliqué par cette session** : `scripts/deploy-verygames.sh` refuse par conception tout
  fichier d'un autre plugin, et contourner ce garde-fou à la main n'a pas été fait sans accord.

- **Distinction explicite** : tout le volet en jeu (protection réelle, réparation visuelle,
  apparitions diurnes, agressivité des araignées, hache du kit) reste
  `PENDING MANUAL VALIDATION` — voir TC-234, TC-235, TC-236. Aucun test MockBukkit n'est présenté
  comme une validation du comportement réel ; MockBukkit ne simule ni le spawn naturel, ni la
  ligne de vue, ni l'IA de ciblage.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261005T084017Z-predeploy.jar`) ; `scripts/plugadmin/rollback.sh app` pour le Control
Panel. Pour #162, revenir en arrière consisterait à retirer `dialogues` du drop-in
`10-content-workspace.conf` puis `systemctl daemon-reload && systemctl restart plugadmin`.

## 2026-10-05 (suite) - WorldEdit wand (#192 appliqué), journal compact, PNJ nom+skin (#165)

### Déploiement / Exécution réelle

- **Build** : plugin **1445 tests / 0 échec / 34 ignorés** ; Control Panel **408 tests / 0 échec /
  1 ignoré** (chiffres relevés dans les XML JUnit). `test` + `build` relancés par
  `scripts/deploy-verygames.sh`, verts.

- **#192 — APPLIQUÉ sur DEV** (autorisé explicitement par le propriétaire).
  - Chemin distant réel : le dossier FTP racine **est déjà** `plugins/` du serveur — le fichier est
    donc `WorldEdit/config.yml`, pas `plugins/WorldEdit/config.yml` (constaté par un listing réel,
    une première tentative sur le mauvais chemin ayant échoué proprement sans rien écrire).
  - Nouveau script **mono-usage** `scripts/worldedit-wand-item.sh` : le garde-fou de
    `deploy-verygames.sh` (qui refuse tout fichier hors `RPGQuest/`) **n'a pas été affaibli** ; ce
    script ne prend aucun paramètre de chemin, ne peut écrire que le `config.yml` de WorldEdit,
    n'y modifie que la clé `wand-item`, refuse tout matériau du kit de départ (#26) comme nouvelle
    valeur, sauvegarde avant écriture et téléverse de façon atomique.
  - Sauvegarde : `…/verygames-backups/worldedit-20261005T085147Z/config.yml` + `MANIFEST.txt`
    (ancienne valeur `minecraft:wooden_axe` consignée).
  - Changement : `wand-item: minecraft:wooden_axe` → `minecraft:golden_axe` (diff d'**une seule
    ligne**, vérifié en dry-run avant application).
  - `/worldedit reload` → « Configuration reloaded! ».
  - **Valeur réellement chargée vérifiée**, pas seulement le fichier : `/worldedit report` puis
    relecture du rapport généré côté serveur → `wandItem: minecraft:golden_axe`. **Re-vérifié après
    le redémarrage** du serveur plus bas : toujours `minecraft:golden_axe`.
  - La hache en bois du kit et les protections de blocs sont inchangées (aucune permission retirée).

- **Journal de quêtes** — infobulle de liste et infobulle de détails étaient la **même** lore
  surchargée. Séparées : la liste ne garde que l'état, les compteurs de l'étape (plafonnés à
  quatre, avec un « +N autre(s) ») et les indications de clic ; description, catégorie,
  récompenses et prérequis passent dans les détails. Plus aucun identifiant technique affiché
  (l'id d'étape `prove_worth` disparaît) ; les noms de cibles viennent de la **clé de traduction
  vanilla**, donc le client affiche « Araignée » au lieu de « Tuer SPIDER », sans table à
  maintenir. Une récompense `VARIABLE` (état interne type `CLAIM_TIER_1`) n'est **plus jamais**
  affichée. Les attributs vanilla de l'objet-icône (« dégâts d'attaque » d'une épée) sont masqués.
  - **Récupération du journal** : déjà correcte par conception (option du Libraire conditionnée par
    `LACKS_CUSTOM_ITEM` + objet soulbound → remise possible, jamais de doublon, et l'action ne fait
    que donner l'objet, donc aucun reset). Ce contrat vivant dans la **donnée**, un test le
    verrouille désormais (un seul chemin de remise, condition présente, action limitée à un
    `customitem give … 1`).

- **#165 — nom en jeu et apparence des PNJ depuis le panel** : nouvelle section « Nom en jeu &
  apparence » sur la fiche PNJ, visible dès qu'un PNJ Citizens est lié (aucune définition RPGQuest
  exigée — cas de Help, Citizens orphelin). Deux actions agent distinctes
  (`npc.citizens.rename`, `npc.citizens.skin`, permission `NPC_BIND_WRITE`). Identifiant logique,
  identité Citizens et liaisons quêtes/dialogues/stories **inchangés** ; nom de définition et
  locuteur de dialogue volontairement non modifiés (ils peuvent être partagés).
  - Ciblage par la **liaison persistée** (id logique → UUID Citizens). Pour le skin, `SkinTrait`
    n'existe pas dans l'artefact `citizensapi` auquel le projet se limite : on passe donc par la
    commande structurée de Citizens, mais en **sélectionnant explicitement** le PNJ visé pour la
    console via `NPCSelector` (API publique) juste avant et en désélectionnant juste après, dans le
    même passage sur le thread principal — deux admins simultanés ne peuvent pas viser le mauvais
    PNJ.
  - Seul un lien `https://minesk.in/<id>` strict est accepté, validé côté panel **et** revalidé
    côté plugin ; coller la commande `/npc skin --url …` est refusé (ce n'est pas une console
    libre). Le téléchargement étant asynchrone côté Citizens, un succès signifie « demande
    transmise » — l'interface le dit explicitement plutôt que de surpromettre.

- **Déploiements** : Control Panel AWS (`/health` → 200) puis JAR VeryGames DEV
  (1 693 858 o, SHA-256 `098efa025ddc77e9f4c28c9e6ba5a5b00d91056582cd84fdfc4d525282a14dda`,
  backup `rpgquest-20261005T092950Z-predeploy.jar`), suivis d'**un seul** redémarrage
  (`verygames-restart.sh --timeout 240`, 0 joueur connecté). Après redémarrage : `/plugins` → 4
  verts.

- **Distinction explicite** : tout le volet en jeu reste `PENDING MANUAL VALIDATION` — TC-236
  (hache du kit), TC-237 (infobulles + récupération du journal), TC-238 (nom/skin PNJ). Aucun test
  MockBukkit n'est présenté comme une validation du rendu en jeu.
- Aucun merge, aucune intervention PROD.

Rollback : `scripts/rollback-verygames.sh --latest` (JAR) ; `scripts/plugadmin/rollback.sh app`
(panel) ; pour WorldEdit, `scripts/worldedit-wand-item.sh --item minecraft:wooden_axe` rétablit la
valeur d'origine (ou restaurer le `config.yml` sauvegardé dans
`…/verygames-backups/worldedit-20261005T085147Z/`).

## 2026-10-05 (lot 3) - #172 : catalogues Minecraft réels dans l'éditeur de mobs

### Déploiement / Exécution réelle

- **Diagnostic sur le parcours réel, pas sur une supposition.** Un compte panel de diagnostic
  dédié (`claude-diag-172`, ADMIN, **supprimé en fin d'intervention** avec ses identifiants) a
  permis de rejouer le parcours complet en HTTP authentifié : la page `/mobs`, le formulaire
  « Nouveau profil », le POST réel vers `/agents/action`, puis l'exécution côté plugin.
  - Résultat : `mob.definition.create` → **SUCCESS / CREATED**. Le backend n'était donc **pas** en
    cause, et l'hypothèse #190 (bouton trop bas dans le formulaire) ne corrigeait pas le vrai défaut.
  - **Vrai défaut** : la page n'émettait **aucune `<datalist>`**. Type d'entité (obligatoire),
    particule, son, mondes et biomes étaient de simples champs texte à placeholder — il fallait
    connaître l'identifiant vanilla exact. D'où « impossible de créer » côté utilisateur, alors que
    modifier un profil existant fonctionnait (champs déjà remplis).
  - **Second défaut, soumission silencieuse** : deux sections de capacités repliées par défaut
    contiennent des champs numériques contraints ; une valeur invalide faisait refuser la
    soumission par le navigateur, qui ne peut pas focaliser un champ caché dans un `<details>`
    fermé → bouton sans réaction **et sans message**. Formulaire passé en `novalidate`, la
    validation métier existante (panel + plugin) répond désormais avec un message lisible.

- **Nouveau relevé `mob.catalogs`** (plugin) : lit les registres réels de la version installée.
  Mesuré sur le DEV : **91 entités, 115 particules, 1838 sons, 65 biomes, 6 mondes**. Charge utile
  **51 731 caractères** — soit bien au-delà de l'ancienne borne de 20 000 corrigée plus tôt dans la
  journée (#164) : sans ce correctif, ce relevé serait arrivé tronqué et la fonctionnalité aurait
  échoué en silence.
- **Éditeur** : listes recherchables (entité / particule / son), multisélections (mondes / biomes,
  même composant que #163, contrat CSV du serveur inchangé), aide par champ, mention « colorable »
  sur les particules qui acceptent réellement une couleur (lu du type de données Paper, jamais
  deviné). Sans relevé, bandeau explicite et repli en saisie libre — jamais de liste inventée.
- **Catalogue** : recherche nom/identifiant + filtres Boss / Mob spécial / Désactivés.

- **Vérification de bout en bout effectuée** : création d'un **SPECIAL** puis d'un **BOSS** depuis
  le formulaire réel, jusqu'à leur réapparition dans le catalogue avec les bons badges
  (6 profils affichés, 1 BOSS / 5 SPECIAL). Les deux profils de test (`claude_diag_special`,
  `claude_diag_boss`) ont été **désactivés** pour ne pas influencer les spawns de la soirée ; ils
  sont identifiables et supprimables par le propriétaire.

- **Déploiements** : Control Panel AWS (`/health` → 200) puis JAR VeryGames DEV (SHA-256
  `9492dfa0c5b35a0e77275b256e89544a0179167ecb6c1c9d81c31acfd9513f05`) suivi d'**un seul**
  redémarrage ; `/plugins` → 4 verts après.
- **Distinction explicite** : le rendu HTML et le comportement JavaScript des listes ont été
  vérifiés par requêtes HTTP réelles sur l'instance déployée, ce qui ne remplace pas un essai au
  navigateur (TC-239) ; l'apparition réelle en jeu d'un profil créé reste à valider en jeu.
- Aucun merge, aucune intervention PROD. Aucun contenu du propriétaire modifié ou supprimé.

Rollback : `scripts/rollback-verygames.sh --latest` ; `scripts/plugadmin/rollback.sh app`.

## 2026-10-05 (lot 4) - #195 : couleurs et styles au clic, sans écrire de MiniMessage

### Changement

Composant partagé du Control Panel pour les champs de texte destinés aux joueurs : palette de
16 couleurs nommées au clic plus « ∅ aucune couleur », cases Gras / Italique / Souligné / Barré,
aperçu, saisie en texte simple. Appliqué au **nom affiché** des mobs et boss (`/mobs`) et au
**texte des nœuds de dialogue** (`/dialogues`), en création comme en modification.

Le seul embryon de palette qui existait dans les assets était du **code mort** : aucune page
n'émettait son balisage.

### Action serveur

**Aucune.** Panel uniquement — pas de nouveau JAR, pas de redémarrage Minecraft, pas de
modification de configuration serveur ni de données.

### Sauvegarde préalable

Release PlugAdmin automatique par `scripts/plugadmin/deploy.sh`
(`/opt/plugadmin/releases/20261005-140749`).

### Déploiement / Exécution réelle

- **Contrat serveur strictement inchangé** : le champ réellement soumis garde le même nom et la
  même valeur MiniMessage qu'avant. Ni les actions agent, ni les validateurs, ni le plugin ne
  voient de différence ; les fichiers YAML produits sont identiques. Sans JavaScript, c'est un
  champ texte ordinaire — la page reste utilisable.
- **Textes multi-styles préservés, vérifié sur les contenus réels.** Un éditeur « une couleur +
  des cases » ne peut pas représenter `<red>Roi</red> <gold>des Marais</gold>` sans l'aplatir.
  L'analyse n'ouvre donc le mode guidé que pour une valeur **uniforme** (au plus une couleur et
  des décorations englobant tout le texte) ; sinon le texte reste tel quel en mode avancé, la
  raison est écrite à l'écran, et basculer demande un geste explicite annonçant la simplification.
  - Mesure réelle : les **42 textes de nœuds** servis par l'instance déployée passés au parseur →
    **23 en mode guidé avec aller-retour exact, 19 laissés intacts en mode avancé, 0 altération**.
  - **Aller-retour complet vérifié** : nom multi-styles posé par le formulaire réel sur le profil
    de test `claude_diag_special`, exécuté par le plugin (`mob.definition.update` → SUCCESS /
    UPDATED, `mobs/claude_diag_special.yml`), puis relevé `mob.list` et réouverture du
    formulaire → valeur rendue **à l'identique**, aucun aplatissement.
- **Tests** : `:control-panel:build` vert (suite complète du module) ; nouveau `StyleFieldTest`
  (contrat du champ soumis, repli sans JavaScript, échappement) ; nouveau test JavaScript
  `control-panel/src/test/js/stylefield-parse.test.js` (17 cas, exécution manuelle par `node`,
  **volontairement non câblé à Gradle** pour ne pas faire dépendre le build d'un Node installé).
- **Déploiement** : Control Panel AWS uniquement, `/health` → `200`
  (`{"panel":"ONLINE","disabled":false}` à 14:08:06 UTC).
- Le compte panel de vérification (`claude-verif`, ADMIN) et ses identifiants locaux ont été
  **supprimés** en fin d'intervention ; `owner` et `TESTER` intacts.
- Le profil de test `claude_diag_special` (toujours **désactivé**) porte désormais le nom
  multi-styles `<red>Roi</red> <gold>des Marais</gold>` : il sert de démonstrateur du mode avancé
  pour TC-240. Il est identifiable et supprimable à volonté.
- Aucun merge, aucune intervention PROD, aucun contenu du propriétaire modifié ou supprimé.

### Validation

TC-240 (nouveau) — entièrement vérifiable **au navigateur**, sans Minecraft.

Rollback : `scripts/plugadmin/rollback.sh app` (aucun rollback plugin nécessaire : le JAR n'a pas
changé dans ce lot).

## 2026-10-05 (lot 6) - #196 : catalogue complet des objets pour icônes et récompenses

### Changement

Les listes **Icône** et **Récompense d'objet** de l'éditeur de quêtes proposent désormais le
catalogue **réel de la version installée** au lieu d'une liste de 76 entrées écrite à la main.
Recherche par **nom français** ou par **identifiant**, résultats complets et paginés, et refus
expliqué pour un bloc sans forme d'objet.

### Action serveur

**Remplacement du JAR RPGQuest uniquement**, puis **un seul redémarrage**. Aucun fichier de
configuration, aucune donnée, aucun monde touché. Le JAR est nécessaire : le nouveau relevé
`item.catalogs` lit le registre `Material` du serveur, et le Control Panel ne peut pas dépendre de
Bukkit.

### Sauvegarde préalable

JAR précédent sauvegardé automatiquement par `scripts/deploy-verygames.sh` :
`~/.local/share/rpgquest/verygames-backups/rpgquest-20261005T144420Z-predeploy.jar`
(1 696 825 octets, SHA-256 `9492dfa0…`) + fichier `.meta`.

### Déploiement / Exécution réelle

- **Cause racine confirmée par la mesure, pas supposée.** `RefData.MATERIALS` — 76 entrées écrites
  à la main — servait de catalogue et ne contenait que `IRON_SWORD` et `DIAMOND_SWORD`. La version
  installée expose **sept** épées : bois, pierre, **cuivre**, or, fer, diamant, netherite.
  `COPPER_SWORD` n'existe que depuis 1.21.9 — vérifié par `javap` sur le jar `paper-api` réellement
  utilisé, puis confirmé par le relevé du serveur. Rien n'a été inventé pour une autre version.
- **Second défaut, indépendant, trouvé en auditant la troncature** comme le demandait le ticket :
  la liste recherchable de `panel.js` s'arrêtait à **60 correspondances sans le dire**. Avec un
  catalogue de plus d'un millier d'objets, une recherche large aurait silencieusement caché des
  résultats. Borne portée à 100, total annoncé (« 100 sur 142 affichés »), et « afficher 100 de
  plus » cliquable.
- **Relevé réel mesuré sur le DEV** : **1 504 objets utilisables**, **151 blocs sans forme
  d'objet**, Minecraft **1.21.11**, charge utile **30 828 caractères** — très en dessous de la
  borne de 512 Ko corrigée plus tôt dans la journée (#164).
- **Représentable vs délivrable tranché par l'API** : les deux exigent `isItem()`. La distinction
  utile est donc avec les blocs sans forme d'objet, renvoyés à part pour être **expliqués** —
  « WATER existe comme bloc mais n'a aucune forme d'objet dans cette version » — au lieu de
  « matériau inconnu », qui envoyait chercher une faute de frappe inexistante.
- **Noms français par composition** (`MaterialNames`), élision comprise. Ce qui ne suit aucune
  règle tombe sur l'anglais embelli : un repli **visiblement** non traduit plutôt qu'une traduction
  inventée. La recherche filtrant sur l'identifiant **et** le libellé, « sword » et « épée » donnent
  le même résultat.
- **Vérifié sur l'instance déployée** (requêtes HTTP authentifiées, compte dédié supprimé ensuite) :
  datalist servie = **1 655 options**, `data-material-source="server"`, `data-mc-version="1.21.11"` ;
  **« sword » renvoie les sept épées** avec leurs libellés français ; 151 blocs marqués
  `data-noitem` ; 21 objets de créatif signalés ; aide du champ Icône annonçant
  « 1504 objets de la version installée (Minecraft 1.21.11) ».
- **Charge des pages maîtrisée** : la liste n'est émise que sur l'éditeur de quêtes, seul à avoir
  des champs de matériau. `/stories/new` fait **17 Ko** contre **130 Ko** pour `/quests/new`.
- **Note mesurée** : **0 forme historique écartée** sur ce serveur — ce Paper n'expose plus les
  constantes `LEGACY_*` à l'exécution. Le filtre `isLegacy()` reste un garde-fou, et **aucune**
  entrée `LEGACY_` ne pollue la liste (vérifié sur le relevé réel).
- **Tests** : suite complète des trois modules verte — **1446** plugin (34 ignorés), **441**
  control-panel (1 ignoré), **30** web-api, **0 échec**. Nouveaux : `MaterialNamesTest`,
  `ItemCatalogTest`, `MaterialPickerTest`, `AgentActionExecutorTest` (+1), et un test JavaScript
  `combo-pagination.test.js` (non câblé à Gradle, pour ne pas faire dépendre le build d'un Node
  installé).
- **Incident d'environnement, sans effet sur le dépôt** : `control-panel/build/resources`
  appartenait à `root`, ce qui faisait échouer `processResources` (« Failed to clean up stale
  outputs »). Propriété rendue à `ubuntu` ; aucun fichier versionné touché.
- Aucun merge, aucune intervention PROD, aucun contenu du propriétaire modifié.

### Redémarrage requis

**Oui — un seul, effectué.** Serveur revenu `ONLINE` (0 joueur connecté au moment de l'opération).

### Migration automatique

Aucune. Le relevé est en lecture seule et ne touche aucune donnée.

### Validation

TC-242 (nouveau) — entièrement vérifiable **au navigateur**, sans Minecraft.

Rollback : `scripts/rollback-verygames.sh --latest` ; `scripts/plugadmin/rollback.sh app`.

## 2026-10-05 (lot 7) - #194 : suppression de quêtes et stories avec aperçu et confirmation

### Changement

Bouton **« Supprimer… »** sur les fiches de `/quests` et `/stories`, derrière une permission
**dédiée** (`CONTENT_DELETE`). Le bouton ouvre un **aperçu des conséquences** ; la confirmation
exige de **retaper l'identifiant**. Les références sont nettoyées, jamais mises en cascade ; une
référence non traitable **bloque** ; aucune progression de joueur n'est touchée.

### Action serveur

**Remplacement du JAR RPGQuest uniquement**, puis **un seul redémarrage**. Le JAR est nécessaire :
la nouvelle action `content.definition.delete` supprime la copie serveur du contenu, sans quoi
celui-ci resterait chargé et « réapparaîtrait » au prochain rafraîchissement du catalogue — c'était
le symptôme rapporté.

### Sauvegarde préalable

JAR précédent sauvegardé automatiquement par `scripts/deploy-verygames.sh` :
`~/.local/share/rpgquest/verygames-backups/rpgquest-20261005T155027Z-predeploy.jar`.

### Déploiement / Exécution réelle

- **Cartographie des références vérifiée dans le code, pas supposée.** Le point le moins évident :
  **un PNJ ne référence aucune quête**. `NpcDefinition` ne porte que `dialogueId`, et le lien
  quête → PNJ vit dans le champ `giver:` **de la quête elle-même** (`QuestGiverStore` écrit dans le
  fichier de quête). Supprimer une quête n'orpheline donc aucun PNJ. Les vraies dépendances sont
  les **prérequis** d'autres quêtes, les **chaînes de story**, et les **dialogues**.
- **Jamais de cascade** : les fichiers référençants sont **réécrits**, jamais supprimés.
- **Jamais de lien orphelin** : blocage avec fichier, **numéros de ligne** et marche à suivre pour
  un dialogue (`QUEST_STATE` / `START_QUEST`) ; blocage si une story perdrait sa dernière quête
  (chaîne vide refusée par le moteur) ; blocage si un fichier référençant n'est pas analysable ;
  blocage si l'espace de travail est en lecture seule ou si aucune sauvegarde n'est possible. Un
  plan bloqué **n'écrit rien** et **n'affiche aucun formulaire** de confirmation.
- **Progression joueur jamais touchée**, et la politique est écrite dans l'aperçu à chaque fois :
  suppression **éditoriale**, les lignes restent en base et deviennent sans objet ; le reset joueur
  est une opération distincte avec sa propre permission.
- **Permission dédiée** `CONTENT_DELETE` : `OWNER` et `ADMIN` oui, **`CONTENT_EDITOR` non** —
  écrire du contenu est réversible, le détruire ne l'est pas de la même façon.
- **Source et runtime séparés.** Côté serveur, le fichier est retrouvé par l'**identifiant déclaré
  dedans**, jamais par un nom de fichier deviné (un nom n'a pas à correspondre à l'id), sauvegardé,
  supprimé, puis les définitions sont **relues** — donc sans redémarrage.
- **Piège des exemples embarqués traité.** Les 9 quêtes et la story d'exemple du JAR sont
  **recréées au démarrage** si le fichier manque. L'aperçu le dit, avec la conséquence :
  reconstruire et redéployer le plugin pour que la suppression soit définitive. Ce n'est pas un
  blocage, mais ce n'est jamais passé sous silence. Un test de cohérence du dépôt confronte la
  copie du panel aux `BUNDLED_EXAMPLES` réelles du plugin.
- **Sauvegarde et échec partiel.** Tout fichier touché est copié **avant** toute écriture, sous un
  horodatage commun, dans `/var/lib/plugadmin/content-backups/`. Ordre d'exécution : sauvegarder,
  réécrire les références (échec ⇒ **restauration** et arrêt **avant** toute suppression),
  supprimer en dernier car c'est l'étape irréversible. Un conflit de version entre l'aperçu et la
  confirmation **refuse** la suppression : on ne détruit pas un contenu que l'opérateur n'a jamais vu.
- **Décision d'emplacement corrigée en cours de route** : les sauvegardes allaient d'abord sous
  `src/main/resources/.plugadmin-backups/`. C'était faux pour deux raisons — elles seraient entrées
  dans le **JAR construit**, et elles auraient **sali le working tree Git**, ce qui bloque
  `deploy-verygames.sh` (il refuse un arbre non propre).

### Déploiement effectué

- **JAR** : SHA-256 `d8991636879071c1bdb741bce8ece084407bcaf19697a36d98e3980fbfac3d59`
  (1 705 052 octets), transféré puis **un seul redémarrage** RCON — serveur revenu `ONLINE`,
  0 joueur connecté.
- **Control Panel AWS** : service actif, `/health` → `200`.
- **Tests** : `./gradlew test build` en un seul passage — **1458** plugin (34 ignorés),
  **476** control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.
- **Vérifié sur les services déployés, sans rien supprimer** : l'action
  `content.definition.delete` répond correctement sur un identifiant volontairement inexistant
  (donc elle est bien présente dans le JAR livré) ; 13 boutons « Supprimer… » sur `/quests`, 3 sur
  `/stories` ; et l'aperçu de `first_steps` **bloque** sur le dialogue `guard` (lignes 12, 16, 21)
  **sans rendre de formulaire de confirmation** — la protection fonctionne sur le contenu réel.

### Redémarrage requis

**Oui — un seul, effectué.**

### Migration automatique

Aucune. Aucune donnée de joueur n'est lue ni écrite par cette fonctionnalité.

### Validation

TC-243 (nouveau) — parcours navigateur complet, **sur du contenu de test préfixé `tc243_`
uniquement**. Le test précise de ne pas le dérouler sur `crystal_hunt`, `first_steps`, les quêtes
du Garde ou `main_story`.

Rollback : `scripts/rollback-verygames.sh --latest` ; `scripts/plugadmin/rollback.sh app`.
Un contenu supprimé par erreur se restaure depuis `/var/lib/plugadmin/content-backups/<horodatage>/`
(source) et `plugins/RPGQuest/content-backups/<horodatage>/` (serveur).

## 2026-10-05 (lot 8) - #12 : signal visuel sur les PNJ (quête disponible / dialogue non lu)

### Changement

Particules **discrètes** au-dessus d'un PNJ quand une **quête est réellement disponible** pour
**ce** joueur, ou qu'un **dialogue accessible comporte un nœud jamais lu**. Spécifique à chaque
joueur, rendu par un client **vanilla**, compatible Citizens. Le PNJ lui-même n'est jamais
modifié.

### Action serveur

**Remplacement du JAR RPGQuest uniquement**, puis **un seul redémarrage**. **Nouvelle migration de
schéma V24** (table `dialogue_node_reads`) appliquée automatiquement au démarrage — idempotente,
aucune donnée existante touchée. Aucune modification de configuration requise : la section
`npc-hints:` a des valeurs par défaut, et un `config.yml` existant sans cette section continue de
fonctionner.

### Sauvegarde préalable

JAR précédent sauvegardé automatiquement :
`~/.local/share/rpgquest/verygames-backups/rpgquest-20261005T172148Z-predeploy.jar`.

### Déploiement effectué

- **JAR** : SHA-256 `1ab198d032f16cbbd6c7f47e7904ae26d0968c155b556419301cba6cbed33975`
  (1 731 370 octets), transféré puis **un seul redémarrage** RCON — serveur revenu `ONLINE`,
  0 joueur connecté.
- **Vérifié sur le serveur, via RCON** : `plugins` → les **4 plugins en vert** (Citizens,
  Multiverse-Core, RPGQuest, WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`. Le nouveau JAR a
  donc chargé sans erreur, migration V24 incluse.
- **Aucun déploiement du Control Panel** : ce lot ne touche que le plugin. Le panel reste actif et
  `/health` répond `200`.
- **Tests** : `./gradlew test build` — **1486** plugin (34 ignorés), **476** control-panel
  (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

### Deux constats d'audit qui ont décidé la conception

- **« Quête prête à rendre » n'est pas un état observable.** `QuestState.READY_TO_TURN_IN` existe
  dans l'énumération, mais `QuestProgressEngine#turnIn` le pose puis le remplace par `COMPLETED`
  dans la même méthode, et seul `COMPLETED` est persisté. Le ticket demandait de ne l'intégrer que
  si l'état existe réellement : il est donc **documenté hors MVP**, sans inventer de mécanique de
  remise.
- **Un PNJ ne référence aucune quête.** Le lien vit dans le champ `giver:` **de la quête**. Le
  signal part donc des quêtes, pas d'une table de liaison inexistante.

### Garanties

- **Aucune règle dupliquée** : la disponibilité vient de `QuestProgressEngine#availability`, et
  `accept()` **délègue** désormais à cette méthode — une seule implémentation des règles de refus.
  Les conditions de dialogue viennent de `reachableNodes()`, qui réutilise l'évaluateur existant.
- **« Lu » ne concerne que ce qui a été présenté** : le marquage se fait dans
  `DialogueSessionEngine#openNode`, seul point de rendu d'un nœud. Ouvrir un PNJ n'écrit qu'une
  ligne, celle du nœud de départ. État par **UUID**, persistant après reconnexion et redémarrage.
- **Coût maîtrisé** : aucune boucle par tick (une passe par seconde et **par joueur**), aucun PNJ
  distant (rayon, même monde, ligne de vue, aucun chunk chargé), calcul découplé de l'affichage
  (recalcul au plus toutes les 5 s, asynchrone, un seul à la fois par joueur, cache invalidé
  immédiatement sur changement pertinent).
- **Réglages bornés** : une valeur numérique hors plage est **corrigée** (un signal visuel ne doit
  pas empêcher le serveur de démarrer) ; une **particule inconnue est refusée** au démarrage, parce
  que c'est une faute de frappe qu'il faut voir.

### Défaut introduit par ce lot et corrigé

`SchemaMigrator.CURRENT_VERSION` était resté à **23** alors que le catalogue allait à **24** — la
version déclarée mentait donc sur le catalogue. Attrapé par
`SchemaMigrationRunnerTest#realCatalogueTargetsTheDeclaredCurrentVersion`, corrigé, et les tests de
migration pointent désormais la constante du code plutôt qu'un littéral.

### Redémarrage requis

**Oui — un seul, effectué.**

### Migration automatique

**Oui — V24**, appliquée au démarrage, idempotente. Crée `dialogue_node_reads` et son index.
Aucune ligne existante n'est lue ni modifiée.

### Validation

TC-244 (nouveau) — protocole complet **en jeu**, sur contenu préfixé `tc12_` uniquement, avec
**deux joueurs** pour l'étape décisive. **Aucun test en jeu n'a été exécuté** : le rendu visuel, la
cadence en charge et la différence entre joueurs restent à valider manuellement.

Rollback : `scripts/rollback-verygames.sh --latest`. La table `dialogue_node_reads` peut rester en
place sans effet (un JAR antérieur l'ignore simplement) ; `npc-hints.enabled: false` désactive le
signal sans redéployer autre chose qu'un redémarrage.

---

## 2026-10-05 (lot 9) - #22 : aucune entrée dans le monde des claims sans moyen d'en repartir

### Changement

Correction d'un **blocage réel** : un joueur éligible s'est retrouvé coincé dans le monde `claims`,
Acte en poche, sans Pierre de retour ni aucun moyen de revenir au Hub. Trois défauts cumulés sont
corrigés, et une **règle préventive** est ajoutée : on n'entre plus dans `claims` sans le moyen d'en
repartir.

### Action serveur

**Remplacement du JAR RPGQuest** *et* **transfert explicite de `RPGQuest/dialogues/jo.yml`**, puis
**un seul redémarrage**. Aucune migration de schéma, aucune modification de configuration.

**Point d'attention permanent** : `dialogues/jo.yml` n'est **pas** un exemple empaqueté
(`YamlDialogueEngine.BUNDLED_EXAMPLES = {"guard.yml"}`, et un fichier existant n'est jamais
réécrit). Un redéploiement de JAR seul **n'aurait pas** mis à jour le dialogue de Jo : le transfert
par `--also` est obligatoire pour ce fichier.

### Sauvegarde préalable

- JAR précédent : `~/.local/share/rpgquest/verygames-backups/rpgquest-20261005T195459Z-predeploy.jar`
  (1 731 370 octets, SHA-256 `1ab198d0…`).
- `jo.yml` précédent : `…/verygames-backups/extra-20261005T195459Z/RPGQuest/dialogues/jo.yml`
  (5 591 octets, SHA-256 `55418687…`), avec `MANIFEST.txt`.

### Déploiement effectué

- **JAR** : SHA-256 `97e77bc3b054ffd94094a9b8684a3216fc2b15cb3ac658b49047e91a1b8ceb1f`
  (1 735 478 octets), commit `eb439a3`.
- **`jo.yml`** transféré par `--also` (téléversement atomique, backup préalable).
- **Un seul redémarrage** RCON : `save-all`, `stop`, arrêt **constaté OFFLINE**, puis retour
  **vérifié ONLINE**.
- **Vérifié après redémarrage, par RCON** : `plugins` → les **4 plugins en vert** (Citizens,
  Multiverse-Core, RPGQuest, WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`.
- **Control Panel** : non redéployé (ce lot ne touche que le plugin) ; `/health` répond `200`.
- **Tests** : `./gradlew test` puis `./gradlew build` — **1495** plugin (34 ignorés), **476**
  control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

### Les trois défauts, et pourquoi ils se cumulaient

1. **Cause racine** — `ClaimWorldSafetyListener#handleArrival` sortait **avant**
   `ensureReturnStone` pour les porteurs de `rpgquest.admin.world`. Le bypass dispensait donc de
   **tout** : ni renvoi au Hub, ni Pierre de retour. Un administrateur éligible arrivé par portail
   n'avait plus aucune sortie. Un test figeait ce comportement en affirmant « aucun objet imposé à
   un joueur avec le bypass » : il est réécrit.
2. **`jo.yml`** — le choix « Obtenir une Pierre de retour » exigeait `HAS_MAIN_CLAIM`, donc était
   inaccessible exactement quand il sert : un joueur débloqué qui n'a pas encore posé de terrain. Il
   exige désormais seulement `CLAIM_TIER_1`.
3. **Inventaire plein** — l'objet tombait déjà au sol, mais le message annonçait « tu reçois une
   Pierre de retour » dans les deux cas. Le joueur la cherchait dans des poches pleines.

### Règle préventive ajoutée

Avant toute téléportation vers `claims`, `ClaimWorldAccessGuard` exige, via l'unique
`claim.ClaimReturnService`, que la destination de retour se résolve **et** que le joueur détienne ou
puisse recevoir une Pierre de retour **dans son inventaire**. Sinon l'entrée est refusée avec le
motif exact, sans rien laisser au sol au Hub. Le bypass n'est jamais refusé mais reçoit la Pierre.

### Redémarrage requis

**Oui — un seul, effectué et vérifié** (arrêt constaté, retour en ligne constaté).

### Migration automatique

**Aucune.** Schéma inchangé (V24), aucune donnée joueur lue ni modifiée.

### Validation

TC-245 (nouveau) — protocole complet **en jeu**. **Aucun test en jeu n'a été exécuté** : le
comportement réel du portail, la canalisation de la Pierre et le texte de Jo restent à valider
manuellement.

Rollback : `scripts/rollback-verygames.sh --latest` pour le JAR, et
`scripts/rollback-verygames.sh --also <backup>/RPGQuest/dialogues/jo.yml:RPGQuest/dialogues/jo.yml`
pour le dialogue (voir `MANIFEST.txt`). Aucun état persistant n'a changé : un retour arrière
restaure exactement le comportement précédent — y compris le piège.

---

## 2026-10-05 (lot 10) - #95 : module « Exploitation serveur » dans PlugAdmin (lot 1)

### Changement

Nouvelle page **`/ops`** du Control Panel : état réel du serveur et de l'agent avec **fraîcheur**
affichée, **annonce globale** aux joueurs (3 canaux), **redémarrage** immédiat ou différé avec
annonces, annulation et **retour en ligne vérifié**, et **console récente** en lecture seule
(recherche, filtres, pause, suivi automatique).

Côté plugin : deux actions agent whitelistées (`server.announce`, `server.logs.tail`) et un tampon
circulaire de console alimenté par un appender Log4j2.

### Action serveur

**Deux cibles dans ce lot** :

1. **Control Panel AWS** — `scripts/plugadmin/deploy.sh` (reconstruction + remplacement de
   `/opt/plugadmin/app`, redémarrage du service, contrôle `/health`).
2. **JAR RPGQuest** sur VeryGames + **un seul redémarrage** Minecraft.

**Configuration serveur ajoutée** (hors dépôt, nécessaire au seul redémarrage) :

- `/etc/plugadmin/control-panel.properties` : `ops.rcon.dev.host` et `ops.rcon.dev.port`
  (valeurs **non secrètes**) ;
- `/etc/plugadmin/plugadmin.env` : `RPGQUEST_RCON_PASSWORD_DEV` (**secret**, fichier `640
  root:plugadmin`, hors Git). Les deux fichiers ont été **sauvegardés** avant modification
  (`.bak-<horodatage UTC>`).

Sans cette configuration, la page fonctionne et affiche simplement « **Redémarrage indisponible** »
avec son motif : aucune autre fonction n'est affectée.

**Aucune migration de schéma.** Aucun fichier de contenu touché.

### Sauvegarde préalable

- JAR précédent : `/home/ubuntu/.local/share/rpgquest/verygames-backups/rpgquest-20261005T205914Z-predeploy.jar`
  (1 735 478 octets, SHA-256 `97e77bc3…`).
- Ancienne distribution du panel : `/opt/plugadmin/releases/<horodatage>` (faite par le script).
- Fichiers de configuration : `.bak-<horodatage UTC>` dans `/etc/plugadmin/`.

### Déploiement effectué

- **Panel AWS** : déployé, service `active (running)`, `/health` → `{"panel":"ONLINE"}`.
- **JAR** : SHA-256 `cbac00bfb6bba03add3b919c5a15765cabba370e245d9355868a9d53c9d38dbb` (1,756,236 octets, commit `c7962f4`).
- **Un seul redémarrage** Minecraft, annoncé en jeu : `save-all`, `stop`, arrêt **constaté
  OFFLINE**, retour **constaté ONLINE**.
- **Vérifié après redémarrage, par RCON** : `plugins` → **4 plugins verts** (Citizens,
  Multiverse-Core, RPGQuest, WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`.
- **Vérifié sur la page déployée** (compte de vérification temporaire, supprimé ensuite) : agent
  `ONLINE`, uptime réel, version réelle, fraîcheur « il y a 2 s » ; **5 modèles d'annonce**, les
  **3 canaux** `chat`/`actionbar`/`title` ; console avec recherche, filtres `ERROR`/`WARN`/`INFO`,
  pause, suivi auto, retour en bas ; section « Non disponible dans ce lot » citant ses motifs
  réels ; **aucune zone de saisie libre**. Le bloc de redémarrage s'affiche **actif** (et non
  « indisponible »), ce qui confirme que le service lit bien la configuration RCON.
- **Tests** : `./gradlew test` puis `./gradlew build` — **1538** plugin (34 ignorés), **543**
  control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

### Décisions de conception, chacune mesurée

- **Le log serveur n'est pas atteignable.** Sondé avant de concevoir : la racine FTP est le dossier
  `plugins/` et le serveur répond « Server denied you to change to the given directory » sur
  `../logs`. La console vient donc d'un appender **Log4j2** côté plugin, remonté par l'**agent
  sortant** existant — ni SSE, ni WebSocket, ni port entrant, conformément au ticket.
- **`java.util.logging` ne suffirait pas** : Paper route `getSLF4JLogger()` directement vers Log4j2,
  donc un `Handler` JUL ne verrait pas nos propres lignes. `log4j-core` est **`compileOnly`**
  (fourni par le serveur, jamais empaqueté) et une absence/incompatibilité est rattrapée
  (`LinkageError`) : la console s'affiche « indisponible » avec son motif, le serveur démarre
  normalement.
- **Le redémarrage ne peut venir ni de l'agent** (il s'arrête avec le serveur) **ni du script
  shell** (le service PlugAdmin tourne avec `ProtectHome=true` et n'a accès ni au `$HOME` de
  l'opérateur ni à son fichier d'identifiants). D'où un client RCON en Java dans le panel,
  réutilisant le mécanisme déjà éprouvé par `scripts/verygames-restart.sh`.

### Un arrêt n'est pas un redémarrage

Avant d'arrêter, le service vérifie que le serveur répond — sinon **aucun arrêt n'est demandé**.
Après l'arrêt, deux preuves seulement sont acceptées : serveur **vu hors ligne** puis répondant de
nouveau, **ou** uptime du plugin **diminué**. Sans preuve avant le délai, l'opération finit en
**échec** avec le motif, jamais en « redémarré ».

### Sécurité

Quatre permissions séparées (`OPS_VIEW`, `OPS_ANNOUNCE`, `OPS_RESTART`, `OPS_LOGS`).
`RconCommand` est une **énumération de trois valeurs** (`list`, `save-all`, `stop`) : aucun chemin
de code ne transporte un texte libre jusqu'au serveur. L'annonce envoie le message en **texte
littéral** (jamais MiniMessage — un `<click:run_command:…>` ferait exécuter une commande à tous les
joueurs qui cliquent) et refuse un message commençant par `/`. Mot de passe RCON jamais journalisé,
jamais réaffiché, jamais inclus dans un message d'erreur. Audit : `ops.restart`,
`ops.restart.cancel`, et les annonces via l'audit d'action.

### Défaut de conception trouvé et corrigé avant livraison

La console ré-enfile un relevé à chaque cycle d'agent, et `AgentStore` n'avait **aucune purge** :
consulter la console une heure aurait laissé des centaines de lignes dans `agent_action` et noyé
l'historique réel des actions d'administration. Purge ciblée
(`AgentStore#pruneTerminalActionsOfType`), qui ne touche **jamais** une action en cours, couverte
par deux tests.

### Redémarrage requis

**Oui — un seul, effectué et vérifié** (arrêt constaté, retour constaté).

### Migration automatique

**Aucune.** Schéma inchangé (V24).

### Validation

TC-246 (nouveau) — protocole complet, 31 étapes, dont un **vrai redémarrage** depuis le panel.
**Aucun test en jeu n'a été exécuté** : les trois canaux d'annonce vus par un joueur, un
redémarrage piloté de bout en bout et l'ergonomie de la console sous charge restent à valider.

Rollback : `scripts/plugadmin/rollback.sh` pour le panel, `scripts/rollback-verygames.sh --latest`
pour le JAR. Les clés `ops.*` peuvent rester en place sans effet avec un JAR antérieur ; retirer
`ops.rcon.dev.host` suffit à rendre le redémarrage indisponible sans rien redéployer.


---

## 2026-10-06 (lot 11) - #131 rechargement du contenu, #210 actions joueurs, premier lot économie

### Changement

Trois lots livrés dans la même nuit, dans cet ordre, chacun déployé et vérifié avant le suivant.

1. **#131 — rechargement contrôlé du contenu.** Nouveau service plugin
   `content.reload.ContentReloadService` : validation, contrôle des **références croisées**, puis
   application atomique par familles (`ITEMS`, `NPCS`, `QUESTS`, `STORIES`, `DIALOGUES`, `MOBS`).
   Deux actions agent (`content.reload.preview` en lecture, `content.reload` sensible), bloc dédié
   sur `/ops` et rappel contextuel sur `/quests`, `/stories`, `/dialogues`. Commandes
   `/rpgadmin content preview|reload`. **Aucun `/reload` Bukkit.**
2. **#210 — actions joueurs.** Sur les fiches joueurs : statut **OP Minecraft** et **whitelist**
   réels, OP/DEOP, renvoi de secours au Hub, expulsion avec raison, whitelist.
3. **Économie (premier lot).** Lecture du solde **et du journal des transactions** depuis le panel,
   crédit et débit avec **raison obligatoire**, trois actions agent `economy.*`.

### Action serveur

**Deux cibles, dans l'ordre** :

1. **Control Panel AWS** — `scripts/plugadmin/deploy.sh`, **deux fois** (une pour #131+#210, une
   pour l'économie). Distributions `20261006-001824` puis `20261006-005116`.
2. **JAR RPGQuest** sur VeryGames, **deux fois**, avec **un redémarrage Minecraft chacun**
   (**deux au total sur la nuit**).

**Aucune migration de schéma** (V24 inchangé). **Aucun fichier de contenu touché** : le
rechargement **relit** le contenu déjà présent sur le serveur, il ne le transporte pas.

### Sauvegarde préalable

- JAR avant #131+#210 : `rpgquest-20261005T221919Z-predeploy.jar` (1 756 236 octets,
  SHA-256 `cbac00bf…`).
- JAR avant l'économie : `rpgquest-20261005T225156Z-predeploy.jar` (1 787 548 octets,
  SHA-256 `fce156b2…`). **Le backup précédent n'a pas été écrasé.**
- Distributions du panel conservées dans `/opt/plugadmin/releases/` par le script.

### Déploiement effectué

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| JAR #131 + #210 | SHA-256 `fce156b2d32ca077ed83381b460cf4e5a62328279ef0486fbbd424f76f36c2f7` (1 787 548 o, commit `50b5aad`) | redémarrage 1 : arrêt **constaté OFFLINE**, retour **constaté ONLINE** ; `plugins` → **4 verts** ; `rpgquest version` → `v0.1.0-SNAPSHOT` |
| JAR économie | SHA-256 `3a29b540787ff6e337858bc1aba09b86d9b29548a1f6a85ce85b20d3faded3be` (1 796 897 o, commit `b7686c4`) | redémarrage 2 : arrêt **constaté OFFLINE**, retour **constaté ONLINE** ; `plugins` → **4 verts** ; taille du JAR en ligne relue = taille locale |
| Panel #131 + #210 | distribution `20261006-001824` | `/health` → **200** (sonde du script trop précoce, revérifié à la main) |
| Panel économie | distribution `20261006-005116` | `script_exit=0`, `/health` → `{"panel":"ONLINE"}` |

**Tests** : `./gradlew test` puis `./gradlew build` — **1603** plugin (34 ignorés), **577**
control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

### Preuves de bout en bout obtenues sur le serveur réel

- **Aperçu de contenu** : `SUCCESS`, 27 éléments valides, aucune référence cassée, **rien
  appliqué**, empreinte `90afd489406e`.
- **Rechargement réel** : `SUCCESS`, PNJ + quêtes + stories + dialogues, **35 éléments**, **même
  empreinte** — donc le disque égalait déjà ce qui tournait ; l'empreinte **constate** au lieu de
  supposer. Les **six** relevés de catalogue ont été ré-enfilés automatiquement.
- **#210 sur un UUID inexistant** : `FAILED` — « Joueur inconnu (jamais connecté) ». **Aucun compte
  touché, aucun droit du propriétaire modifié.**
- **Économie réellement chargée après le second redémarrage** : `economy.balance` sur un UUID
  inexistant → `FAILED` « Joueur inconnu », tandis qu'un type bidon `economy.nonexistent` →
  `REJECTED` « Type d'action non whitelisté ». Les deux réponses diffèrent : la première vient de
  l'exécuteur économie **embarqué dans ce JAR**. Aucun crédit, aucun débit, aucune écriture au
  journal des transactions.

### Ce que ce déploiement ne fait PAS

- **Il ne transporte aucun contenu.** Publier un fichier de quête/story/dialogue du panel AWS vers
  VeryGames reste une opération **manuelle** : un rechargement relit ce qui est **déjà** sur le
  serveur. La page le dit explicitement, pour qu'un rechargement « sans effet » ne soit pas lu
  comme une panne.
- **Il ne recharge pas les paramètres** (`config.yml`, `npc-hints:`, etc.) : un changement de
  configuration demande toujours un redémarrage, et la page l'indique.
- **Aucune progression, aucune quête active, aucun inventaire, aucune instance de boss ou de mob
  vivant n'est affecté** : pas de récompense redistribuée, pas de respawn, pas de reset implicite.
- **Aucun OP accordé, aucun joueur expulsé, aucun solde crédité ou débité** pendant la
  vérification.

### Permissions ajoutées

- `ACTION_CONTENT_RELOAD` (OWNER, ADMIN, CONTENT_EDITOR) pour **appliquer** un rechargement ;
  l'aperçu reste derrière la lecture de contenu, puisqu'il ne change rien.
- `PLAYER_OP_WRITE` — **OWNER uniquement, explicitement refusée à ADMIN** : accorder OP donne tout
  le serveur, ce n'est pas le même geste qu'administrer le contenu.
- `ECONOMY_READ` (OWNER, ADMIN, TESTER, READ_ONLY) et `ECONOMY_WRITE` (OWNER, ADMIN) : lire un
  solde et **créer de la monnaie** sont deux droits distincts.

### Migration automatique

**Aucune.** Schéma inchangé (V24). Le portefeuille et son journal existaient déjà : ce lot les rend
**lisibles et administrables**, il ne les recrée pas et ne convertit rien.

### Validation

TC-247 (#131), TC-248 (#210) et TC-249 (économie) ajoutés à `docs/MANUAL_TEST_PLAN.md`.
**Aucun test en jeu n'a été exécuté, aucune case cochée.**

Rollback : `scripts/plugadmin/rollback.sh` pour le panel, `scripts/rollback-verygames.sh --latest`
pour le JAR (un cran = l'économie retirée, deux crans = #131/#210 retirés également). Un JAR
antérieur rend simplement les nouvelles actions `REJECTED` côté plugin et le panel les affiche
indisponibles : aucune autre fonction n'est affectée.

---

## 2026-10-06 (lot 12) - #16 : récompense monétaire de quête et bourse dans le journal

### Changement

Nouveau type de récompense de quête **`MONEY`** (`rewards[] → type: MONEY`, `amount` entier > 0)
qui crédite le **portefeuille persistant** du joueur. Aucun objet n'est donné : la monnaie
RPGQuest est un solde, et aucun objet d'inventaire n'est compté comme de l'argent.

- **Crédité au plus une fois par occasion de complétion.** La réservation de l'occasion
  (`quest_reward_grants`) et la mise à jour de `wallets` vivent dans la **même transaction SQL** :
  ni double paiement sur un retry, ni récompense perdue sur une panne. Une quête `repeatable`
  ouvre une nouvelle occasion à chaque reprise et se paie donc à nouveau.
- **Trace** : ligne `transactions` de type `QUEST_REWARD`, contexte `quest:<id>#<occasion>`.
- **Message joueur** envoyé **seulement après** confirmation de la base (montant + nouveau solde
  relu) ; échec dit explicitement ; occasion déjà payée silencieuse. Dans le **chat**, jamais
  dans l'ActionBar (réservée à la progression des objectifs).
- **Bourse** affichée dans le journal de quêtes (liste et vue détail), relue après chaque
  transaction ; « indisponible » en cas d'erreur de lecture plutôt qu'un `0` trompeur.
- **Panel** : type « Pièces (monnaie) » dans l'éditeur de quête, validation entier > 0,
  avertissement sans refus au-delà d'un million.

### Action serveur

**Deux cibles** :

1. **Control Panel AWS** — `scripts/plugadmin/deploy.sh` (distribution `20261006-090923`).
2. **JAR RPGQuest** sur VeryGames + **un seul redémarrage** Minecraft.

### ⚠️ Migration automatique — OUI

**Schéma V24 → V25** au premier démarrage du nouveau JAR : création de la table
`quest_reward_grants` + son index. Migration **idempotente** et **additive** : aucune table
existante n'est modifiée, aucune donnée n'est réécrite, aucun solde n'est touché. Rien à faire
manuellement.

La table ne contient **aucun solde** : `wallets` reste la seule source de vérité. Elle ne répond
qu'à « cette occasion de complétion a-t-elle déjà été payée ? ». Elle est volontairement **sans
clé étrangère** vers `player_profiles` : un `ON DELETE CASCADE` rendrait un profil supprimé puis
recréé payable une seconde fois pour les mêmes occasions.

### Sauvegarde préalable

- JAR précédent : `rpgquest-20261006T074047Z-predeploy.jar` (1 796 897 octets, SHA-256
  `3a29b540…`). **Le backup précédent n'a pas été écrasé.**
- Distribution du panel conservée dans `/opt/plugadmin/releases/` par le script.

### Déploiement effectué

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| JAR | SHA-256 `6fe9952576cc9240ccaf4cf1639d603d6739585df56f1ecd309315cb03f26f7d` (1 807 514 o, commit `ee96254`) | `DEPLOY_EXIT=0`, `JAR en ligne : 1807514 octets (== local)` |
| Panel | distribution `20261006-090923` | `/health` → `{"panel":"ONLINE"}`, service `active` |

**Un seul redémarrage** (`RESTART_EXIT=0`) : arrêt **constaté OFFLINE**, retour **constaté
ONLINE**. Par RCON : `plugins` → **4 plugins verts** ; `rpgquest version` → `v0.1.0-SNAPSHOT`.

**Tests** : `./gradlew test` puis `./gradlew build` — **1638** plugin (34 ignorés), **586**
control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

### Chargement réellement vérifié

Le numéro de version ne change pas d'un lot à l'autre. La migration, elle, se constate : copie de
`RPGQuest/data.db` récupérée **en lecture seule**, lue localement puis **supprimée**.

- `PRAGMA user_version` → **25** (était 24) ;
- table `quest_reward_grants` **présente**, schéma identique à la migration ;
- **0 ligne** — personne n'a joué, donc aucune récompense n'a été payée.

Aucune écriture sur le serveur, aucun solde touché, aucune donnée joueur modifiée.

### Ce que ce déploiement ne fait PAS

- **Aucun montant n'est posé sur une quête réelle.** Le type existe ; aucune quête du serveur ne
  l'utilise. Les deux quêtes d'essai vivent dans `docs/manual-tests/rewards/`, **hors du JAR**, et
  doivent être copiées à la main pour le test.
- **Aucune monnaie physique, aucune conversion, aucune migration de solde.** Le lien solde ↔ objet
  (#138) reste une décision de gameplay non prise.
- **Aucun solde existant modifié**, aucun objet vanilla transformé implicitement en monnaie.
- **Aucune progression, quête active ni inventaire affecté.**

### Deux échecs de déploiement, avant tout transfert

Le script a refusé de livrer **deux fois**, et à chaque fois **avant** la moindre opération FTP :

1. pas **3/8** — `Java heap space` : le script relance `test`/`build` et héritait d'un shell sans
   `RPGQUEST_TEST_MAX_HEAP=768m`. Échec d'environnement, pas de code ;
2. pas **5/8** — `/dev/tty: No such device or address` : confirmation interactive sans terminal,
   contournée par `-y`.

Le serveur n'a jamais été laissé arrêté ni partiellement déployé. **Attention** : le code de
sortie d'une commande d'attente qui enveloppe le script ne prouve **rien** — seul `DEPLOY_EXIT`
compte. Ces deux pièges et la séquence complète sont désormais écrits dans
`docs/deployment/VERYGAMES.md`.

### Validation

TC-250 (nouveau, 27 étapes, 7 sections) dans `docs/MANUAL_TEST_PLAN.md`. **Aucun test en jeu n'a
été exécuté, aucune case cochée.** Le point le plus important est la section **D** : recomplétion,
complétion forcée depuis le panel et double clic rapide ne doivent produire **qu'une seule** ligne
`QUEST_REWARD`.

Rollback : `scripts/plugadmin/rollback.sh` pour le panel, `scripts/rollback-verygames.sh --latest`
pour le JAR. Un JAR antérieur ignore simplement `type: MONEY` (récompense refusée au chargement de
la quête concernée) ; la table `quest_reward_grants` reste en place sans effet et ne gêne rien.

---

## 2026-10-06 (lot 13) - #16 : une récompense monétaire due n'est plus perdue après un crash

### Changement

Le premier lot ne gardait trace que de ce qui avait **déjà** été payé : une récompense due mais non
créditée (arrêt du serveur, panne SQL) ne laissait **aucune** trace et devenait invisible. Trois
défauts corrigés, dont un **mesuré** :

1. l'identité de paiement vivait **uniquement en mémoire** → perdue au redémarrage ;
2. l'état `COMPLETED` était persisté dans une transaction **séparée** du crédit → fenêtre
   « terminée mais jamais payée, sans trace » ;
3. **mesuré** : plusieurs récompenses `MONEY` sur une même complétion partageaient une identité →
   une quête `100 + 30` créditait **100**, la seconde étant avalée comme « déjà payée ».

Désormais : la complétion et une ligne de dette par récompense sont écrites dans la **même
transaction** ; le paiement fait passer la ligne `PENDING → PAID` **avec** le crédit et la ligne de
journal ; les dettes restées dues sont **reprises** au chargement du joueur avec leur identité
initiale, de façon **bornée**.

**Panel** : fiche joueur → « Récompenses en attente » (`economy.debts` en lecture,
`economy.debt.retry` et `economy.debt.settle` sensibles), avec l'état réel (« en échec (N
tentatives) » + motif) et la distinction explicite entre **reprendre** et **créditer manuellement**.

### Action serveur

**Deux cibles** :

1. **Control Panel AWS** — `scripts/plugadmin/deploy.sh` (distribution `20261006-104128`).
2. **JAR RPGQuest** sur VeryGames + **un seul redémarrage** Minecraft.

### ⚠️ Migration automatique — OUI

**Schéma V25 → V26** au premier démarrage : six colonnes ajoutées à `quest_reward_grants`
(`status`, `reward_index`, `attempts`, `last_error`, `updated_at`, `settled_reason`) + un index sur
`status`. Migration **idempotente** (garde `columnExists`) et **additive** : aucune table existante
n'est modifiée, aucune donnée réécrite, aucun solde touché.

**Aucun paiement rétroactif.** Les lignes existantes prennent `status = 'PAID'` — ce n'est pas une
supposition : par construction de V25, une ligne n'était écrite que *dans* la transaction qui
créditait. **Aucune dette n'est inventée** pour les quêtes terminées avant cette mise à jour, et le
panel le dit à l'écran.

La table ne contient toujours **aucun solde** : `wallets` reste la seule source de vérité.

### Sauvegarde préalable

- JAR précédent : `rpgquest-20261006T084211Z-predeploy.jar` (1 807 514 octets, SHA-256
  `6fe99525…`). **Le backup précédent n'a pas été écrasé.**
- Distribution du panel conservée dans `/opt/plugadmin/releases/` par le script.

### Déploiement effectué

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| JAR | SHA-256 `5b2d7b85857711e7cd4bf2cd75d535e103a986697fa33d5e5153c9dd45c61f81` (1 825 931 o, commit `9f3b28d`) | `DEPLOY_EXIT=0`, `JAR en ligne : 1825931 octets (== local)` |
| Panel | distribution `20261006-104128` | `/health` → `{"panel":"ONLINE"}`, service `active` |

**Un seul redémarrage** (`RESTART_EXIT=0`) : arrêt **constaté OFFLINE**, retour **constaté ONLINE**.
Par RCON : `plugins` → **4 plugins verts** ; `rpgquest version` → `v0.1.0-SNAPSHOT`.

**Tests** : `./gradlew test` puis `./gradlew build` — **1660** plugin (34 ignorés), **595**
control-panel (1 ignoré), **30** web-api, **0 échec, 0 erreur**.

### Chargement réellement vérifié

Copie de `RPGQuest/data.db` récupérée **en lecture seule**, lue localement puis **supprimée** :

- `PRAGMA user_version` → **26** (était 25) ;
- les **six** nouvelles colonnes présentes sur `quest_reward_grants` ;
- **aucune ligne** dans la table → aucune dette rétroactive, aucun paiement rétroactif.

Aucune écriture sur le serveur, aucun solde touché. **Aucune panne provoquée sur la base DEV.**

### Ce que ce déploiement ne fait PAS

- **Il ne paie rien rétroactivement.** Une récompense perdue avant cette mise à jour n'a laissé
  aucune trace exploitable ; aucun montant n'est deviné.
- **Il ne modifie aucun solde, aucune transaction, aucune progression existante.**
- **Il ne pose aucun montant sur une quête réelle** : aucune quête du serveur n'utilise `MONEY`.
- **Aucune monnaie physique, aucune conversion** : le lien solde ↔ objet (#138) reste une décision
  de gameplay non prise.

### Validation

TC-251 (nouveau, 24 étapes, 7 sections) dans `docs/MANUAL_TEST_PLAN.md`. **Aucun test en jeu n'a été
exécuté, aucune case cochée.** La section **B** est la plus importante (deux récompenses sur une
complétion : le solde doit augmenter de **130**, pas de 100). La section **E** (échec réel de
paiement) est **optionnelle et explicitement à ne pas faire sur la base DEV réelle** — elle est
entièrement couverte par les tests automatisés.

Rollback : `scripts/plugadmin/rollback.sh` pour le panel, `scripts/rollback-verygames.sh --latest`
pour le JAR. **Attention au rollback du JAR** : un JAR V25 ne connaît pas les nouvelles colonnes.
Elles restent en place sans le gêner (il ne les lit pas) et `PRAGMA user_version` reste à 26, donc
la migration ne sera pas rejouée ; en revanche une dette `PENDING` écrite par V26 ne serait **pas**
reprise par un JAR antérieur — elle le redeviendrait au retour sur V26, sans double paiement.

---

## 2026-10-06 (lot 14) - #199 : groupes multiples et droits effectifs PlugAdmin

### Changement

Nouvelle page **`/groups`** : création, modification et suppression de **groupes** de permissions
PlugAdmin, appartenance d'un compte à **plusieurs** groupes, et affichage des **droits effectifs
avec leur provenance** sur la fiche de chaque compte.

- **Combinaison = UNION** du rôle et des groupes. Aucun refus explicite n'existe dans le modèle,
  donc un groupe ne peut jamais *réduire* les droits d'un rôle.
- **Anti-élévation** : on ne peut ni accorder ni retirer une permission qu'on ne détient pas
  soi-même. `PLAYER_OP_WRITE` reste donc inatteignable pour un ADMIN, même par un groupe.
- **Révocation immédiate** : droits effectifs recalculés à chaque requête, donc effet sur les
  sessions déjà ouvertes sans reconnexion.

### Action serveur

**Une seule cible : le Control Panel AWS** (`scripts/plugadmin/deploy.sh`). **Aucun JAR, aucun
redémarrage Minecraft** — ce lot ne touche pas le plugin.

### ⚠️ Migration automatique — OUI (base du panel uniquement)

Trois `CREATE TABLE IF NOT EXISTS` **additifs** dans `control-panel.db` : `panel_group`,
`panel_group_permission`, `panel_user_group`, plus un index sur l'appartenance. **`panel_user` n'est
pas touché**, les rôles ne sont **pas** convertis en groupes, et une installation existante démarre
avec **zéro groupe** — donc des droits effectifs strictement égaux à ceux du rôle. Aucun droit n'est
élargi, aucun accès propriétaire n'est perdu. `data.db` du plugin n'est pas concerné.

### Déploiement effectué

| Cible | Empreinte | Vérification |
|---|---|---|
| Control Panel AWS | distribution `20261006-112436` | `PANEL_EXIT=0`, `/health` → `{"panel":"ONLINE"}`, service `active` |

**Vérifié sur l'instance déployée** : les tables `panel_group`, `panel_group_permission` et
`panel_user_group` existent réellement dans `/var/lib/plugadmin/control-panel.db`, et `/groups`
appelée **sans session** répond **303** (redirection vers la connexion) — la garde backend est bien
en place.

**Tests** : `:control-panel:test` — **638** tests (1 ignoré), **0 échec, 0 erreur**.

### Ce que ce déploiement ne fait PAS

- **Il ne touche pas au plugin** ni à `data.db` : aucun redémarrage Minecraft.
- **Il ne change aucun droit existant** : zéro groupe au départ.
- **Aucun lien avec OP Minecraft, Paper ou LuckPerms.** Les groupes PlugAdmin n'accordent rien en
  jeu. Le pont vers les droits Minecraft par monde est l'issue #200.

### Validation

TC-252 (nouveau, 30 étapes, 9 sections) dans `docs/MANUAL_TEST_PLAN.md`. **Aucune case cochée.**
Les sections les plus importantes sont **E** (révocation sur une session active, avec deux fenêtres)
et **F** (anti-élévation, dont le forçage de formulaire).

Rollback : `scripts/plugadmin/rollback.sh`. Les trois tables peuvent rester en place sans effet avec
une distribution antérieure du panel : aucune version précédente ne les lit.

---

## 2026-10-06 (lot 15) - #200 : droits Minecraft par groupe et par monde, LuckPerms installé

### Changement

**Découpage des permissions du plugin** (dépendance #27) et **pont vers LuckPerms** piloté depuis le
Control Panel.

Avant ce lot, un seul nœud — `rpgquest.admin.world` — gouvernait la construction dans le Hub, le
bypass des claims, le bypass des zones, l'accès au monde des claims **et** l'intégralité de
`/rpgadmin`. Deux exigences étaient donc impossibles : séparer « créer un PNJ » de « construire », et
autoriser le Hub sans autoriser les claims.

**Nœuds ajoutés** (tous `default: false`) : `rpgquest.admin.command` (entrer dans `/rpgadmin` sans
autoriser aucune branche), `rpgquest.admin.npc[.tag|.untag|.info]`,
`rpgquest.build.hub.<monde>` / `.hub.*`, `rpgquest.build.wild` (**sans effet à ce jour**, voir
limites), `rpgquest.bypass.claim` / `.zone` / `.claimworld`.

**`rpgquest.admin.world` reste l'ombrelle explicite** : chaque contrôle accepte « nouveau nœud OU
ombrelle ». Un administrateur déjà autorisé garde exactement ses droits, **sans rien reconfigurer**.

**Panel** : liaison compte ↔ joueur par UUID (hors ligne inclus), droits par groupe **et par monde**,
distinction entre état **voulu** et état **réel**, et quatre actions `mc.*` avec permission,
confirmation et audit.

### Action serveur

**Trois cibles, un seul redémarrage** :

1. **LuckPerms** déposé dans `plugins/` (nouveau plugin).
2. **JAR RPGQuest** remplacé.
3. **Control Panel AWS** redéployé.

### ⚠️ Nouveau plugin sur le serveur

**LuckPerms 5.5.87** (`LuckPerms-Bukkit-5.5.87.jar`, SHA-256
`09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406`, 1 509 479 o) est désormais
installé sur DEV. Il était **absent** : première installation, rien d'écrasé. L'inventaire du dossier
`plugins/` **avant** installation est conservé dans le dossier de backups.

**RPGQuest n'en dépend pas** : l'API est en `compileOnly`, la dépendance est `softdepend`, et en
l'absence de LuckPerms le pont se déclare indisponible avec son motif — le plugin démarre et
fonctionne exactement comme avant.

### Migration automatique

**Aucune côté plugin** (schéma V26 inchangé, `data.db` non touché). Côté panel : deux
`CREATE TABLE IF NOT EXISTS` **additifs** (`panel_user_minecraft`, `panel_group_mc_node`). Une
installation existante démarre **sans aucune liaison et sans aucun droit Minecraft géré** : rien ne
change en jeu tant qu'un administrateur n'a rien configuré.

### Sauvegarde préalable

- JAR précédent : `rpgquest-20261006T104448Z-predeploy.jar` (1 825 931 o, SHA-256 `5b2d7b85…`).
- Inventaire `plugins/` avant installation de LuckPerms, dans le dossier de backups.
- Distribution du panel conservée dans `/opt/plugadmin/releases/`.

### Déploiement effectué

| Cible | Empreinte réelle | Vérification |
|---|---|---|
| JAR | SHA-256 `bdd8b0007500f4d45a208e9f1d1505af4701b2cae31a14b1225f01eaba929f09` (1 849 640 o, commit `b94b689`) | `DEPLOY_EXIT=0`, `JAR en ligne : 1849640 octets (== local)` |
| LuckPerms | voir ci-dessus | taille en ligne identique au local |
| Panel | distribution `20261006-124521` | `/health` → `{"panel":"ONLINE"}` |

**Un seul redémarrage** (`RESTART_EXIT=0`) : arrêt **constaté OFFLINE**, retour **constaté ONLINE**.
Par RCON : `plugins` → **5 plugins verts** (Citizens, **LuckPerms**, Multiverse-Core, RPGQuest,
WorldEdit) ; `rpgquest version` → `v0.1.0-SNAPSHOT`.

**Tests** : **1688** plugin (37 ignorés), **661** control-panel (1 ignoré), **30** web-api,
**0 échec**, sur une exécution **propre** attestée par 184 fichiers XML.

### Chargement réellement vérifié

Trois sondes enfilées puis supprimées, sur un groupe de test :

- `mc.group.sync` → **SUCCESS**, « 1 droit(s) ajouté(s) » : le type est reconnu et LuckPerms écrit ;
- la **même** action rejouée → **SUCCESS**, « déjà conforme : aucun droit modifié » →
  **idempotence prouvée sur le serveur réel** ;
- `mc.group.delete` → **SUCCESS**.

Aucun compte réel, aucun droit du propriétaire, aucun joueur touché.

### Ce que ce déploiement ne fait PAS

- **Il n'accorde aucun droit à personne.** Tous les nouveaux nœuds sont `default: false`, aucune
  liaison n'existe, aucun groupe n'a de droit Minecraft configuré.
- **Il ne retire aucun droit existant** : l'ombrelle `rpgquest.admin.world` reste `default: op` et
  est acceptée par tous les contrôles.
- **Aucun OP automatique**, nulle part.
- **`rpgquest.build.wild` n'a aucun effet** : le Wild est déjà libre à la construction. Déclaré pour
  la convention, volontairement non câblé — le brancher sur la protection de zone en ferait un
  bypass interdit.
- **#22 intact** : l'entrée dans le monde des claims n'est jamais refusée à un porteur du bypass, et
  la Pierre de retour reste garantie.

### Limites à connaître

- **Plusieurs Hubs dans un même monde ne sont pas distinguables** : l'identifiant de Hub *est* le nom
  du monde (seul identifiant existant). Il faudrait un id en configuration et une résolution par
  zone.
- **Renommer le monde du Hub** change le nœud : les droits accordés sur l'ancien nom deviennent
  **silencieusement** sans effet. À inclure dans toute procédure de renommage de monde.
- Le reste de `/rpgadmin` n'est pas découpé (backlog #27).

### Validation

TC-253 (nouveau, 34 étapes, 7 sections). **Aucun test en jeu exécuté, aucune case cochée.** La
section **C** est la plus importante : accorder à la main le **même** nœud et le **même** monde que
le pont, retirer le groupe, dissocier, **redémarrer**, et vérifier que le droit externe survit.

Rollback : `scripts/plugadmin/rollback.sh` pour le panel, `scripts/rollback-verygames.sh --latest`
pour le JAR. **Retirer LuckPerms** se fait en supprimant son JAR de `plugins/` puis en redémarrant :
RPGQuest continue de fonctionner, le pont se déclarant simplement indisponible. Les groupes
`rpgq-…` déjà créés disparaîtraient avec les données de LuckPerms — les droits qu'ils portaient
cesseraient donc de s'appliquer, ce qui est le comportement voulu.

------------------------------------------------------------------------

## 2026-10-06 (lot 16) - #95 : le refresh de /ops n'efface plus la saisie et n'empile plus le résultat

**Panel AWS uniquement. Aucun transfert vers VeryGames, aucun redémarrage Minecraft.** Le JAR du
plugin en place reste `ba6c4e3c7147…` (lot 15).

### Ce qui n'allait pas

La page `/ops` démarrait son suivi d'opération dès qu'une **carte d'opération** était affichée, sans
distinguer une opération **en cours** d'une opération **déjà terminée** (`DONE`, `FAILED`,
`CANCELLED`). `/ops/state.json` répondait `terminal: true`, le script rechargeait **toute** la page,
le serveur re-rendait la même carte terminale, et le cycle repartait : un rechargement toutes les
3 secondes, sans fin.

Les deux symptômes signalés en venaient tous les deux :

- l'annonce en cours de saisie était effacée et les cases décochées, puisqu'un rechargement
  reconstruit le formulaire ;
- la notification de résultat réapparaissait sans cesse : elle vient d'un drapeau laissé dans l'URL
  par la redirection après POST (`?toast=`, `?ok=`, `?err=`), donc chaque rechargement la recréait.

**Aucune réexécution n'a eu lieu** : vérifié sur l'historique réel (`agent_action`), trois annonces
de l'exploitant espacées de minutes, et rien de plus. Le rechargement rejouait une **redirection
GET**, jamais la soumission.

### Ce qui change

- Plus **aucun** rechargement de page depuis le suivi d'opération. Seuls des conteneurs **sans
  formulaire** sont remplacés : le bloc d'état et la carte d'opération.
- `/ops/state.json` renvoie un champ `stateHtml` : le bloc « État du serveur » se renouvelle tout
  seul (20 s au repos, 3 s pendant une opération active). Il ne contient ni `<form>`, ni `<input>`,
  ni jeton CSRF — un test l'interdit.
- Une opération **déjà terminale au chargement** n'est plus surveillée.
- Les drapeaux de résultat de l'URL deviennent à **usage unique** (`history.replaceState`) : le
  résultat s'affiche une fois, et une nouvelle action produit sa propre notification.

### Déployé

- `scripts/plugadmin/deploy.sh` — release précédente sauvegardée sous
  `/opt/plugadmin/releases/20261006-152004`.
- `control-panel-0.1.0-SNAPSHOT.jar` SHA-256 `bb705a8b53a1e5325ae88441a0f4d3b77c1d2dd107552a5400ebc575efe165e5`.
- `panel.js` **réellement servi** : SHA-256 `3ae4b2b3fdcd026750e11a126684807d5602be35ffcdf1e7a677ed7b5d5fb114`,
  identique au fichier du dépôt.
- `/health` local et public : `ONLINE`. `/ops` authentifiée : 200, marqueurs présents.

### Chargement réellement vérifié

Le `panel.js` servi par le panel déployé a été exécuté dans un DOM sur le **vrai HTML de `/ops`**,
dans deux variantes (page telle que servie, puis avec une carte `DONE` réinjectée — le cas qui
bouclait) : **0 tentative de rechargement**, saisie/canal/focus/aperçu intacts après ~23 cycles,
bloc d'état réellement remplacé, drapeau `toast` retiré de l'URL, **uniquement des GET**. Contrôle
négatif sur la version d'avant le correctif : les tentatives de navigation réapparaissent.

### Validation

Validation navigateur par l'exploitant restante (5 points, détaillés dans le rapport
`2026-10-06_1522_refresh-ops-plugadmin-95.md`). Aucune case cochée, aucun ticket fermé.

Rollback : `scripts/plugadmin/rollback.sh app`.
## 2026-10-06 - Édition d'un choix de dialogue porteur d'actions/conditions + aucune simplification silencieuse (#82 / #145)

### Changement

**Plugin.** `DialogueDefinitionEditor.updateChoice` n'est plus réservé aux « choix
simples ». Le texte et la cible d'un choix sont modifiables même s'il porte une
condition `QUEST_STATE` et une action `START_QUEST` : ses conditions et ses actions
sont **reconduites à l'identique** (mêmes valeurs, même ordre), la mutation partant
du modèle métier relu du fichier et non du formulaire. Deux propriétés deviennent
éditables de façon structurée, via de nouveaux paramètres **optionnels** de l'action
agent `dialogue.choice.update` :

- `quest_action` ∈ `keep` (défaut) / `none` / `start_quest` / `advance_quest` /
  `turn_in_quest`, avec `quest_id` ;
- `quest_condition` ∈ `keep` (défaut) / `none` / un `QuestState`, avec
  `condition_quest_id` et l'option `condition_negate`.

`keep` ne touche à rien : un client qui n'envoie pas ces paramètres (toute version
antérieure du Control Panel) conserve le comportement d'avant, conditions et actions
intactes. `dialogue.choice.delete` reste refusé sur un choix porteur d'effets de jeu,
avec un message qui explique comment procéder.

**Control Panel.** La page `/dialogues` remplace la note « édition prévue dans une
phase ultérieure » par un vrai formulaire pour **chaque** choix (sélecteurs d'action
de quête et de condition d'état pré-remplis, négation, liste explicite des propriétés
conservées à l'identique). Les champs de texte montrent la source MiniMessage brute,
avec aperçu rendu et palette de couleurs (réécrit seulement la balise englobante ;
désactivée et annoncée sur un texte composite). Correction d'une perte de données :
`/dialogues/edit/<id>` réécrivait le fichier comme un squelette à un nœud et
détruisait les autres nœuds, choix, conditions et actions — l'enregistrement repart
désormais du fichier réel et n'applique que les champs du formulaire. Un fichier que
l'éditeur ne sait pas relire fidèlement (table de traductions, YAML exotique) n'est
plus jamais réécrit : l'enregistrement est refusé avec la raison, pour les dialogues
comme pour les quêtes.

### Action serveur

Remplacement du JAR RPGQuest uniquement — aucune action manuelle autre que
remplacement du JAR. Côté AWS, redéploiement du Control Panel (`plugadmin`).

**Aucune migration de données**, **aucun changement de schéma SQLite**, **aucun
fichier de contenu modifié par le déploiement** : les dialogues et quêtes déjà
présents sur le serveur ne sont pas touchés (le JAR ne sème que les fichiers
d'exemple absents — voir la note « exemples embarqués » de ce changelog).

### Sauvegarde préalable

Backup daté du JAR actuellement déployé (le script officiel le fait) ; `data.db`
inchangé mais sauvegardé par prudence. Côté AWS, la release précédente de
`plugadmin` est sauvegardée par `scripts/plugadmin/deploy.sh`.

### Déploiement

`RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y` (plugin, DEV) puis
`scripts/plugadmin/deploy.sh` (Control Panel, AWS). Redémarrage du serveur requis
pour que le nouveau JAR soit chargé.

### Validation

Tests automatisés (suite complète) + contrôles de démarrage. **La vérification en
jeu / en navigateur de l'édition d'un choix de Jeff reste à faire par un humain** —
voir la section « Déploiement / Exécution réelle » ci-dessous et
`docs/MANUAL_TEST_PLAN.md`.

### Déploiement / Exécution réelle

Déployé sur **VeryGames DEV** (plugin) et **AWS** (Control Panel) le 2026-10-06
(~18:00-18:15 CEST), sur demande explicite de l'utilisateur (« commit, push et
déploie sur DEV »). Branche `feat/control-panel-admin-tools` @ **`5f71819`**.

Build et déploiement exécutés depuis un **worktree Git propre**
(`/srv/rpgquest/wt-verify`, HEAD détaché sur `5f71819`) et non depuis le
checkout principal : celui-ci portait des **modifications de contenu non
committées de l'utilisateur** (`quests/crystal_hunt.yml` modifié + 4 fichiers
quêtes/stories non suivis, écrits par l'éditeur du panel). Le JAR livré
correspond donc exactement au commit poussé, sans ce contenu en cours
d'édition. `./gradlew test` + `./gradlew build` (internes au script officiel,
`RPGQUEST_TEST_MAX_HEAP=768m`) **OK** — 1404 tests plugin + 395 tests panel,
0 échec, 34 + 1 ignorés.

- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 622 803 o, SHA-256
    `1c42ac9dccfccaf4dfc0530271f5b96371763adeac41c3b3981b49cc5e93f961`.
  - **Backup préalable** : `rpgquest-20261006T160901Z-predeploy.jar`
    (1 850 699 o, SHA-256
    `ba6c4e3c714765414827b59bbc5828c2e33540c0394f0b45a3b5bee4a13d4a75`).
    **À noter** : ce JAR précédemment en ligne est nettement plus gros que
    celui des entrées précédentes de ce changelog — il n'a pas été produit par
    ce dépôt dans l'état committé. Constat brut, non expliqué ici.
  - Taille du JAR en ligne vérifiée identique au local après transfert.
  - **Redémarrage** : `scripts/verygames-restart.sh --timeout 240` — **0 joueur
    connecté** avant l'arrêt, `save-all`, OFFLINE confirmé puis **ONLINE**.
  - **Vérifications post-redémarrage** : `/rpgquest version` →
    `v0.1.0-SNAPSHOT` ; `/plugins` → 5 plugins verts (Citizens, LuckPerms,
    Multiverse-Core, RPGQuest, WorldEdit).
- **Control Panel (AWS, service `plugadmin`)** :
  - `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` → sauvegarde
    de la release précédente (`/opt/plugadmin/releases/20261006-180938`) →
    `systemctl restart plugadmin` → service `active (running)` → `/health` →
    `{"panel":"ONLINE","disabled":false,...}`.
- **Aucune migration**, aucun fichier de contenu transféré (aucun `--also`) :
  les dialogues et quêtes du serveur DEV sont **inchangés**, y compris celui de
  Jeff qui n'a servi qu'à l'analyse.
- **Distinction explicite** : contrôles de démarrage et de santé uniquement.
  **L'édition réelle d'un choix de Jeff depuis `/dialogues`, la palette de
  couleurs, l'aperçu et la non-régression de `/dialogues/edit` restent à valider
  en navigateur** — non déclarés validés sur la seule base des tests
  automatisés (voir `docs/MANUAL_TEST_PLAN.md` TC-231).
- Aucun merge, aucune intervention PROD, aucune issue fermée.

Rollback : `scripts/rollback-verygames.sh --latest` (restaure
`rpgquest-20261006T160901Z-predeploy.jar`) ; `scripts/plugadmin/rollback.sh app`
pour le Control Panel (restaure `/opt/plugadmin/releases/20261006-180938`).

---

## 2026-10-06 (restauration) - Régression de déploiement : retour à une version réunissant le socle déployé et l'édition de dialogues

### Changement

**Correction d'une régression introduite par le déploiement précédent de ce même
jour.** Le déploiement de ~18:09 CEST a livré la branche
`feat/control-panel-admin-tools`, forkée à `ce1233d` et donc **antérieure de 49
commits** à ce qui tournait réellement (`feature/169-special-mobs-boss`). Ont
disparu du panel *et* du plugin, le temps de la panne :

- Mobs spéciaux & boss (#169/#171/#172/#190) — route `/mobs`, entrée de menu et
  permissions `MOB_*` absentes du code livré ;
- pont LuckPerms et droits par monde (#200), groupes multiples (#199) ;
- page « Exploitation serveur » et son refresh (#95) ;
- économie et récompense monétaire de quête (#16/#140) ;
- suppression de contenu (#194), catalogues d'objets et de mobs (#172/#196),
  couleurs et styles au clic (#195), chaîne de paliers du Garde (#179),
  renommage/skin PNJ (#165), rechargement de contenu (#131), actions joueurs
  (#210), signal PNJ (#12), claims (#22), hostiles de jour (#168), voyage (#191).

Version restaurée = tout le socle **plus** l'édition de dialogues (#82/#145),
par fusion sur `feature/169-special-mobs-boss`.

### Données — vérifié, aucune perte

- **Schéma SQLite intact** : le JAR régressé plafonnait à V23 alors que la base
  est en **V26**. `SchemaMigrationRunner` ignore toute migration ≤ version
  appliquée et ne réécrit jamais la version : **aucune rétrogradation possible**.
- **Base du panel intacte** : 3 comptes dont l'OWNER (rôle `OWNER` conservé),
  598 entrées d'audit, tables `panel_group` / `panel_group_permission` /
  `panel_user_group` / `panel_group_mc_node` toujours présentes. Elles sont
  vides parce que l'owner a lui-même supprimé ses groupes de test
  (`tc252_lecture`, `tc252_ecriture`, `tc253_builder`) entre 11:24 et 12:20 UTC,
  **soit presque 4 h avant le déploiement fautif de 16:09 UTC** — l'audit log le
  prouve. Le panel régressé ne connaît pas ces tables et ne pouvait pas y
  toucher.
- **Profils de mobs intacts** : ce sont des fichiers YAML du serveur
  (`SpecialMobDefinitionStore`), et aucun déploiement n'a jamais transféré de
  fichier de contenu (aucun `--also`).
- **Permissions de l'owner** : jamais stockées pour ce compte — `Role.OWNER` vaut
  `EnumSet.allOf(Permission.class)`. `MOB_READ` disparaissait donc avec le code
  et revient avec lui.
- **Seule conséquence réelle** : pendant la fenêtre (~18:09 → ~19:30 CEST), les
  fonctions de ces 49 commits étaient indisponibles ; une quête terminée n'aurait
  pas crédité de récompense monétaire. 0 joueur connecté sur la quasi-totalité de
  la fenêtre.

### Action serveur

Remplacement du JAR RPGQuest + redéploiement du Control Panel. Aucune migration,
aucun fichier de contenu transféré, aucune configuration modifiée.

### Déploiement / Exécution réelle

Branche `feature/169-special-mobs-boss` @ **`e0c1203`** (fusion `dfe642b` +
alignement de test `e0c1203`), construite et transférée depuis le **worktree
propre** `/srv/rpgquest/worktree-169`. `./gradlew test build` **OK** —
**1705 tests plugin + 675 tests panel, 0 échec**, 37 + 1 ignorés.

- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 859 272 o, SHA-256
    `2d728eb90c11992376e5759ee5c51724faf600f0e64e76d325941e8982037219`.
  - **Backup préalable** : `rpgquest-20261006T172836Z-predeploy.jar`
    (1 622 803 o, SHA-256
    `1c42ac9dccfccaf4dfc0530271f5b96371763adeac41c3b3981b49cc5e93f961`) —
    c'est le JAR régressé.
  - **Redémarrage** : 1 joueur connecté (`LoDyMcFly`), **prévenu en jeu** puis
    redémarrage après délai ; `save-all`, OFFLINE puis **ONLINE**.
  - **Vérifications** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 5
    plugins verts dont **LuckPerms** ; `rpgquest reload` → OK ; heartbeat agent
    `uptime_seconds=45` (redémarrage réel), `world_hub`/`claims`/`wild` chargés.
- **Control Panel (AWS)** : release précédente sauvegardée
  (`/opt/plugadmin/releases/20261006-192902`), service redémarré, `/health` →
  `{"panel":"ONLINE","disabled":false,…}`.
- **Vérifications de non-régression** : `/mobs` et `/ops` répondent **303**
  (route existante et protégée) là où un chemin inconnu répond 404 ; l'artefact
  déployé contient bien `MOB_READ`, `CONTENT_DELETE`, `ECONOMY_READ` et
  `StyleField`.
- **Reste à valider en navigateur, avec la session de l'owner** (je n'ai pas ses
  identifiants et ne me suis connecté à aucun compte) : affichage effectif de
  « Mobs spéciaux & boss », comportement de `/ops`, et l'éditeur du dialogue de
  Jeff. Voir TC-254 et les fiches #169/#95 existantes.
- Aucun merge vers `main`, aucune intervention PROD, aucune issue fermée.

### Rollback

`scripts/rollback-verygames.sh --latest` restaurerait le JAR **régressé** — à ne
pas utiliser tel quel. Pour revenir à l'état d'avant cette session, utiliser
explicitement `rpgquest-20261006T160901Z-predeploy.jar` (1 850 699 o), qui est le
dernier JAR sain d'avant la régression. Control Panel :
`scripts/plugadmin/rollback.sh app`.

---

## 2026-10-07 - Aide par champ des formulaires de mobs et boss (#169) — Control Panel uniquement

### Changement

Chaque champ des formulaires « Mobs spéciaux & boss » de `/mobs` porte une aide
rendue sous le champ : ce qu'il contrôle en jeu, un exemple, le défaut réellement
appliqué par le moteur, et le comportement s'il reste vide. Les valeurs annoncées
sont relevées dans le code du plugin ; là où aucun défaut n'existe, l'aide écrit
« Obligatoire » ou « Aucun ». Deux imprécisions de l'ancienne aide sont corrigées
(la particule d'un profil est une bouffée à l'apparition, pas l'aura continue d'un
boss ; le rayon d'explosion n'est retenu qu'en partie entière).

### Action serveur

**Aucune.** Modification exclusivement Control Panel : aucun fichier du serveur
Minecraft n'est touché, **aucun remplacement de JAR**, **aucun redémarrage
Minecraft**. Les noms et les valeurs des champs soumis sont inchangés, donc les
actions agent `mob.*` et le plugin ne voient aucune différence.

### Sauvegarde préalable

Release précédente du panel sauvegardée automatiquement par
`scripts/plugadmin/deploy.sh`.

### Déploiement / Exécution réelle

Branche `feature/169-special-mobs-boss` @ **`484ece7`**, construite depuis le
worktree propre `/srv/rpgquest/worktree-169`. `./gradlew :control-panel:test` →
**683 tests, 0 échec**, 1 ignoré.

- `scripts/plugadmin/deploy.sh` : `:control-panel:installDist` → release
  précédente sauvegardée dans `/opt/plugadmin/releases/20261007-112510` →
  `systemctl restart plugadmin` → service `active (running)` → `/health` →
  `{"panel":"ONLINE","disabled":false,…}`.
- **Vérifications** : artefact installé daté du 2026-10-07 11:25 ; les libellés
  d'aide et les 6 règles CSS `.fmeta` sont présents dans le JAR déployé ; la
  feuille de style servie en production contient bien ces règles ; `/mobs`
  répond toujours `303` (route protégée).
- **Reste à valider en navigateur, avec la session de l'owner** : lisibilité de
  l'aide sur téléphone et exactitude perçue des libellés. Non déclaré validé sur
  la seule base des tests automatisés.
- Aucun merge vers `main`, aucune issue fermée.

### Rollback

`scripts/plugadmin/rollback.sh app` (restaure
`/opt/plugadmin/releases/20261007-112510`). Aucun rollback de plugin à prévoir :
le JAR RPGQuest n'a pas été touché.

---

## 2026-10-07 (lot 2) - PNJ : nom/skin/localisation corrigés + création complète depuis le panel (#165)

### Changement

**Trois correctifs** et **une fonctionnalité**, livrés ensemble en un seul
redémarrage.

1. **Renommer ne change plus le skin — ou refuse proprement.** La cause était
   Citizens : un PNJ de type `PLAYER` sans skin explicite dérive son apparence de
   son **nom**, donc `setName()` la change par effet de bord. Vérification des
   capacités réelles avant toute promesse : l'artefact `citizensapi` (seule
   dépendance Citizens du projet) n'expose **aucune** API de skin — 0 classe
   « Skin », aucune clé de skin dans l'enum public `NPC.Metadata`, constaté sur
   2.0.43. Lire le skin en place est donc impossible. Règle appliquée : PNJ non
   joueur → renommage libre ; source connue de RPGQuest → réappliquée avant le
   renommage ; **skin inconnu → refus explicite** avec la marche à suivre. Jamais
   de remplacement silencieux.
2. **Couleurs du nom** : le nom de définition passe sur le composant partagé
   `StyleField`, noms multi-styles préservés.
3. **Nom et localisation réels.** Le champ « Nom en jeu » vient du relevé
   Citizens (il affichait le nom de la *définition*, qui ne change jamais au
   renommage). Monde, X/Y/Z, yaw/pitch, état présent/non apparu et fraîcheur du
   relevé sont affichés ; une position de PNJ non apparu est annoncée comme
   « dernière position enregistrée ».
4. **Création complète d'un PNJ** depuis `/npcs` : une seule action
   `npc.citizens.provision` (définition + apparition + liaison + skin optionnel),
   exigeant l'**union** de trois permissions, avec placement sûr près du Guide si
   aucune position n'est donnée, et nettoyage compensatoire à trois issues
   distinctes.

### Action serveur

Remplacement du JAR RPGQuest **et** redéploiement du Control Panel.
**Migration automatique V27** (`npc_citizens_skins`) appliquée au démarrage :
table neuve, aucune donnée existante touchée, aucune action manuelle.

Nouvelles clés de configuration, toutes avec un défaut (aucune édition requise) :
`hub.guide-npc-id` (défaut `guide`), `hub.placement.search-radius` (8),
`vertical-radius` (3), `max-attempts` (2000).

### Sauvegarde préalable

Backup daté du JAR en ligne par le script officiel ; release précédente du panel
sauvegardée par `scripts/plugadmin/deploy.sh`.

### Déploiement / Exécution réelle

Branche `feature/169-special-mobs-boss` @ **`2c9db65`**, construite depuis le
worktree propre `/srv/rpgquest/worktree-169`. `./gradlew test` + `build` →
**1742 tests plugin + 703 tests panel, 0 échec**, 37 + 1 ignorés.

- **Plugin (VeryGames DEV)** :
  - **JAR déployé** : 1 895 818 o, SHA-256
    `db44b220dbd936709d9869c158695e1f716617c04b8c00f0f8767aadaf85d239`.
  - **Backup préalable** : `rpgquest-20261007T120918Z-predeploy.jar`.
  - **Redémarrage unique** : 1 joueur connecté (`LoDyMcFly`), **prévenu deux fois
    en jeu** (préavis ~30 s puis annonce immédiate) ; `save-all`, OFFLINE puis
    **ONLINE**.
  - **Vérifications** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 5
    plugins verts dont LuckPerms ; heartbeat agent `uptime_seconds=5`
    (redémarrage réel), état `ONLINE`. Le plugin ayant démarré proprement, la
    migration V27 s'est appliquée sans erreur (même niveau de preuve que les
    migrations précédentes : aucun accès direct aux logs par ce compte).
- **Control Panel (AWS)** : `scripts/plugadmin/deploy.sh` → release précédente
  sauvegardée, `systemctl restart plugadmin`, `/health` →
  `{"panel":"ONLINE","disabled":false,…}`. L'artefact installé contient bien
  l'action `npc.citizens.provision`.
- **Routes** : `/npcs`, `/mobs`, `/ops` répondent `303` (protégées).
- **Aucun contenu serveur transféré** (aucun `--also`) : Tan et tous les autres
  PNJ réels sont intacts — aucun n'a servi d'essai.
- **Reste à valider, avec la session de l'owner et en jeu** : renommage avec
  conservation du skin (et le refus quand le skin est inconnu), skin seul, nom
  coloré, actualisation du nom et de la position, création avec et sans position,
  skin invalide, coordonnées partielles, Guide absent, aucun emplacement libre.
  Je ne me suis connecté à aucun compte et n'ai créé aucun PNJ de test en jeu.
- Aucun merge vers `main`, aucune issue fermée.

### Rollback

`scripts/rollback-verygames.sh --latest` restaure
`rpgquest-20261007T120918Z-predeploy.jar`, puis redémarrer. La table V27 reste en
place : elle est inerte pour une version antérieure (le runner de migrations
ignore toute version déjà appliquée et ne rétrograde jamais).
`scripts/plugadmin/rollback.sh app` pour le Control Panel.

---

## 2026-10-07 (lot 3) - Correctif : la localisation Citizens disparaissait à la sérialisation du relevé (#165)

### Changement

Andy (créé près du Guide, Citizens #12, présent en jeu) et Tania (Citizens #2)
affichaient tous deux « Position inconnue — Citizens n'en expose aucune pour ce
PNJ », alors que définition, liaison et état « lié » étaient corrects.

**Cause, établie de bout en bout.** La position n'était pas absente : elle
disparaissait en route.

| Étape | Verdict |
|---|---|
| `CitizensNpcBridge#toSummary` — entité présente, sinon `NPC#getStoredLocation()` | lit bien la position |
| `BukkitAgentActions#citizensRoster` → `CitizensNpcSummary` | porte bien la position |
| `AgentActionExecutor#npcCitizensList` — sérialisation de la ligne | **les 7 clés de position n'étaient jamais émises** |
| Panel | lisait `world` absent et affichait, correctement, « inconnue » |

Confirmé sur les données réelles : le relevé stocké le 2026-10-07 à 12:27 se
termine par `"spawned":true` pour chaque PNJ, sans aucune clé de position.

L'angle mort : les tests de rendu du panel partaient d'un JSON écrit à la main
qui, lui, contenait les clés — ils vérifiaient l'affichage, jamais la
sérialisation côté serveur.

### Action serveur

Remplacement du JAR RPGQuest **uniquement**. Aucun changement du Control Panel
(non redéployé), **aucune migration**, aucun fichier de contenu touché. Andy,
Tania, leurs skins et leurs liens sont intacts : seule la lecture du relevé
change.

### Déploiement / Exécution réelle

Branche `feature/169-special-mobs-boss` @ **`f21424d`**, worktree propre.
`./gradlew test` + `build` → **1747 tests plugin + 703 tests panel, 0 échec**.

- **JAR déployé** : 1 895 945 o, SHA-256
  `14703d4c912df3a15781a7faea0de422c2bda9b53bc4b732c1ebb89b12aae86a`.
- **Backup préalable** : `rpgquest-20261007T124942Z-predeploy.jar`, dont le
  SHA-256 `db44b220…` correspond exactement au JAR du lot 2 — chaîne de rollback
  vérifiée.
- **Redémarrage** : **0 joueur connecté** (aucune annonce nécessaire) ;
  `save-all`, OFFLINE puis **ONLINE**.
- **Vérifications** : `/rpgquest version` → `v0.1.0-SNAPSHOT` ; `/plugins` → 5
  plugins verts dont LuckPerms ; heartbeat `uptime_seconds=5` (redémarrage réel) ;
  les clés `liveLocation` / `yaw` / `pitch` sont présentes dans la classe
  `AgentActionExecutor` du JAR livré.
- **Reste à constater côté owner** : cliquer **« Citizens »** sur `/npcs` pour
  produire un relevé frais — les relevés stockés d'avant ce correctif ne
  contiennent pas les clés, donc la fiche continuera d'afficher « inconnue »
  jusqu'au prochain relevé. C'est le seul geste nécessaire.
- Aucun merge, aucune issue fermée.

### Rollback

`scripts/rollback-verygames.sh --latest` restaure
`rpgquest-20261007T124942Z-predeploy.jar` (= le lot 2), puis redémarrer.

---

## 2026-10-07 (lot 4) - PNJ : état de présence exact et déplacement sans recréation (#165)

### Changement

Trois états de présence distincts au lieu de deux (« présent en jeu » / « en veille » /
« désactivé dans Citizens ») : un PNJ non matérialisé n'est plus présenté comme une anomalie,
puisque Citizens dématérialise ses PNJ dès qu'aucun joueur n'est à portée. Le relevé porte
désormais le trait `Spawned` (intention persistante) et l'état de chargement du chunk, qui
expliquent à eux seuls la plupart des cas. Correction du doublon « Relevé il y a il y a 1 s ».

Nouvelle action `npc.citizens.move` : déplacer un PNJ **existant** sans le recréer (via
`NPC#teleport`), donc en conservant identité Citizens, identifiant RPGQuest, skin, traits et
liaisons dialogues/quêtes. Arrivée validée (sol praticable, deux cases libres, ni liquide ni
portail) ; aucun bloc cassé ni posé ; position relue et comparée après coup.

### Action serveur

Remplacement du JAR RPGQuest **et** redéploiement du Control Panel. Aucune migration de schéma,
aucune donnée touchée.

### Sauvegarde préalable

Automatique via `scripts/deploy-verygames.sh` (backup horodaté du JAR déployé + méta).

### Déploiement

**Effectué le 2026-10-07 à 15:01 (heure locale).**

- Branche `feature/169-special-mobs-boss`, commit `2f181fd`.
- `./gradlew test` : 1747 tests plugin, 0 échec, 37 ignorés ; 708 tests panel, 0 échec, 1 ignoré.
- `./gradlew build` : OK.
- JAR transféré : `1 899 262 o`,
  SHA-256 `9b27134e684173bea4d77b3c64c73404b9f8000f304872c793c543c71c3d0a09`.
- Backup de la version précédente :
  `rpgquest-20261007T145132Z-predeploy.jar` (`1 895 945 o`,
  SHA-256 `14703d4c912df3a15781a7faea0de422c2bda9b53bc4b732c1ebb89b12aae86a`).
- **Redémarrage Minecraft requis** — groupé avec le lot 5 ci-dessous.

### Validation

`PENDING MANUAL VALIDATION` — voir TC-238 et la section PNJ de `docs/MANUAL_TEST_PLAN.md`.

### Rollback

`scripts/rollback-verygames.sh --latest` restaure
`rpgquest-20261007T145132Z-predeploy.jar` (= le lot 3), puis redémarrer.

---

## 2026-10-07 (lot 5) - PNJ : « regarder les joueurs » et promenade pilotables depuis le panel (#165)

### Changement

Deux comportements Citizens deviennent administrables depuis la fiche PNJ du Control Panel :

- **« Regarder les joueurs »** (trait Citizens `lookclose`) : état activé/désactivé **explicite** et
  portée en blocs, relus sur le trait après écriture.
- **Promenade** (fournisseur `wander` du trait Citizens `waypoints`) : activation/désactivation,
  **ancre** et **zone bornée** (rayon horizontal, amplitude verticale). Le PNJ ne peut ni sortir de
  sa zone ni changer de monde. Aucun moteur de déplacement ajouté : c'est le système natif de
  Citizens.

Deux garanties structurantes : **aucune bascule** — les actions portent un état `true`/`false`, donc
un double clic ou un rejeu n'inverse jamais l'état — et **aucun écrasement silencieux** — activer la
promenade sur un PNJ portant déjà une patrouille est refusé en nommant ce qui serait perdu, tant
qu'une confirmation explicite n'est pas donnée.

Le relevé `npc.citizens.list` transporte désormais ces états ; une valeur absente est affichée
« inconnu », **jamais** « désactivé ».

### Action serveur

Remplacement du JAR RPGQuest **et** redéploiement du Control Panel.

**Aucune migration de schéma** (`SchemaMigrator.CURRENT_VERSION` inchangé, 27). **Aucune donnée
touchée** : ni `data.db`, ni les PNJ existants, ni leurs skins, ni leurs liaisons. Aucun comportement
Citizens n'est modifié tant qu'un administrateur ne le demande pas explicitement depuis le panel.

### Nouvelle dépendance de compilation (sans effet sur le serveur)

`net.citizensnpcs:citizens-main` est ajouté en **`compileOnly` strict** : il n'est **pas** empaqueté
dans `rpgquest-*.jar`, dont la taille ne varie que des classes propres au lot. Aucun plugin nouveau
n'est requis sur le serveur, et **ni WorldGuard ni Denizen** ne sont ajoutés (toutes les dépendances
de `citizens-main` autres que `citizensapi` sont en scope `provided`, non résolu par Gradle).

Citizens reste une **dépendance optionnelle** : le plugin démarre normalement sans lui. Si la build
Citizens installée n'exposait pas ces traits, ces deux options seules renverraient
`CITIZENS_INCOMPATIBLE` et tout le reste de l'intégration PNJ continuerait de fonctionner. Détail
dans `docs/deployment/CITIZENS.md`.

### Sauvegarde préalable

Automatique via `scripts/deploy-verygames.sh`. Ne pas écraser le backup du lot 4.

### Déploiement

**Effectué le 2026-10-07 entre 17:43 et 17:45 (heure locale).**

- Branche `feature/169-special-mobs-boss`, commit `14ac882` (code : `993c615`), worktree propre.
- `./gradlew test` + `build` : **1772 tests plugin + 722 tests panel, 0 échec** (exécution unique).
- JAR transféré : **1 917 683 o**, SHA-256
  `71020c82514de40d513a4cae7755c49421a493368c7906d002ebabf031dc2572`.
- Backup préalable : `rpgquest-20261007T154346Z-predeploy.jar` (1 899 262 o, SHA-256
  `9b27134e…c3d0a09`) — **son empreinte correspond exactement au JAR du lot 4**, ce qui confirme
  que celui-ci était bien en place et que la chaîne de rollback est intacte.
- Control Panel redéployé ; release précédente sauvegardée en `20261007-174413`. Le script a
  rapporté `/health KO` parce que son contrôle s'exécute ~2 s après le redémarrage du service,
  avant que la JVM n'ait lié le port ; vérifié ensuite réellement : service `active`, `/health`
  → `{"panel":"ONLINE"}`. Aucun rollback nécessaire.
- **Redémarrage Minecraft effectué**, groupé pour les lots 4 et 5 : `save-all`, `stop` RCON,
  OFFLINE constaté, retour **ONLINE**. **0 joueur connecté** avant comme après — aucune annonce
  n'a donc été diffusée.
- Vérifications après redémarrage : `/plugins` → 5 plugins verts (Citizens, LuckPerms,
  Multiverse-Core, RPGQuest, WorldEdit) ; `/rpgquest version` → `v0.1.0-SNAPSHOT` ;
  **`/version Citizens` → `2.0.43-SNAPSHOT (build 4232)`**.
- **Non vérifié** : l'absence d'un `WARN` d'incompatibilité Citizens au démarrage — les logs du
  serveur ne sont pas accessibles depuis la machine de build (racine FTP = `plugins/`). Le symptôme
  éventuel apparaîtrait dès le premier point de TC-255.

### Validation

`PENDING MANUAL VALIDATION` — **TC-255** de `docs/MANUAL_TEST_PLAN.md`. Le pont Citizens n'est
couvert par aucun test automatisé (`MockBukkit` n'embarque pas Citizens) : seuls la règle de
non-écrasement, la validation des actions et le rendu du panel le sont.

### Rollback

`scripts/rollback-verygames.sh --latest` restaure le JAR du lot 4, puis redémarrer. Côté panel,
`scripts/plugadmin/deploy.sh` conserve la release précédente.

---

## 2026-10-07 (lot 6) - Entrée dans le Wild : avertissement de danger, retour d'attente, état du Wild au Garde (#161, #26, #24)

### Changement

Le passage Hub → Wild demande désormais une **confirmation explicite** du joueur, avec un
avertissement **générique** : zone dangereuse, PvP autorisé, mort = perte de l'inventaire, plus une
phrase renvoyant au Garde pour les conditions actuelles. Trois actions (« Entrer dans le Wild »,
« Entrer et ne plus afficher cet avertissement », « Annuler ») ; fermer la fenêtre n'exécute rien et
vaut donc annulation. L'option « ne plus afficher » est persistée par joueur et n'est écrite que sur
un départ réel.

**Comportement retiré** : l'avertissement précédent lisait l'inventaire du joueur pour y chercher une
Rune de rappel et ne prévenait que ceux qui n'en avaient pas (« Vous partez sans moyen de rappel »).
Plus aucune inspection d'inventaire n'a lieu — décision du 2026-10-07 qui remplace la conception
initiale de #26 partie B.

Ajouts visibles en jeu : retour immédiat « Recherche d'un point d'arrivée sûr… Téléportation en
préparation. » ; message d'**échec** réel quand la téléportation échoue (le plugin annonçait
« Téléportation réussie. » même dans ce cas) ; choix permanent du Garde « Comment est le Wild
actuellement ? » répondant avec l'heure et la météo **réelles** du monde `wild`.

Côté exploitation : chaque passage de portail simple émet une ligne `[TP-LATENCY]` séparant
l'évaluation des colonnes candidates, le chargement/génération des chunks et la téléportation.

`RANDOM_SAFE`, les contrôles de sécurité d'arrivée et le répit d'arrivée de 40 ticks sont
**inchangés**. L'heure, la météo et le cycle jour/nuit du Wild ne sont jamais modifiés par la
lecture du Garde.

### Action serveur

Remplacement du JAR RPGQuest **et** du fichier de données `RPGQuest/dialogues/guard.yml`.

⚠️ `guard.yml` est **obligatoire** : un redéploiement de JAR ne met jamais à jour un dialogue déjà
présent (les exemples embarqués ne sont semés que s'ils manquent). Sans ce fichier, le choix
« Comment est le Wild actuellement ? » n'apparaîtrait pas, bien que le code soit en place.

Aucune autre action : pas de migration, pas de nouvelle clé de configuration, aucun monde touché.

### Sauvegarde préalable

Automatique via `scripts/deploy-verygames.sh` (JAR + chaque fichier `--also`). Ne pas écraser le
backup du lot 5.

### Déploiement

**Effectué le 2026-10-07 entre 19:41 et 19:45 (heure locale).**

- Branche `feature/161-wild-entry-ux`, commit `4f9af35`, construit et déployé depuis un **worktree
  Git propre** (les fichiers de contenu non suivis du propriétaire restent intacts dans le dépôt
  principal).
- `./gradlew test` : **1801 tests plugin + 722 panel + 30 web-api, 0 échec** (37 + 1 ignorés,
  limitations MockBukkit déjà documentées). `./gradlew build` : succès. `:test`/`:jar` confirmés
  à jour sur le commit déployé.
- JAR transféré : **1 935 862 o**, SHA-256
  `b234c5946a7a3f09425fa4c44d0e55556a6ec543843da234f5d08a950d0120c8`.
- Backup préalable : `rpgquest-20261007T174129Z-predeploy.jar` (1 917 683 o, SHA-256
  `71020c82…031dc2572`) — **empreinte identique au JAR du lot 5**, ce qui confirme que la version
  déployée était bien celle-là et que la chaîne de rollback est intacte.
- `RPGQuest/dialogues/guard.yml` : backup `extra-20261007T174129Z/RPGQuest/dialogues/guard.yml`
  (5 897 o, SHA-256 `36531b66…37618a4`) puis transfert de 6 638 o, SHA-256
  `a984f15badf95c0beea37a295de4aa98d56bcc3b02b834963c257dd14228b067` — **identique octet pour octet
  au fichier du dépôt**. Vérifié **avant** le transfert : la version en ligne était identique à celle
  du dépôt d'avant ce lot, donc aucune édition faite depuis le Control Panel n'a été écrasée.
- **Redémarrage Minecraft effectué.** 1 joueur connecté (`LoDyMcFly`), **prévenu deux fois** par
  `say` avant l'arrêt. `save-all`, `stop` RCON, OFFLINE constaté, retour **ONLINE**.
- Vérifications après redémarrage : `/plugins` → 5 plugins verts (Citizens, LuckPerms,
  Multiverse-Core, RPGQuest, WorldEdit) ; `/rpgquest version` → `v0.1.0-SNAPSHOT`.
- **Non vérifié** : le chargement du dialogue `rpgquest:guard` constaté en jeu, et l'absence d'un
  avertissement de chargement dans les logs. Les logs serveur ne sont pas accessibles depuis la
  machine de build (racine FTP = `plugins/`), et `/dialogue open` exige un joueur connecté — aucun
  ne l'était après le redémarrage. Le fichier déployé est néanmoins **identique** à celui que
  `BundledDialoguesValidityTest` charge sans aucun problème.

### Validation

`PENDING MANUAL VALIDATION` — **TC-256** de `docs/MANUAL_TEST_PLAN.md` (avertissement, annulation,
fermeture au clavier, absence de boucle, « ne plus afficher » après reconnexion **et** redémarrage,
relevé `[TP-LATENCY]`, état du Wild au Garde avec vérification que la lecture ne change ni l'heure ni
la météo, non-régression du kit du Guide).

### Rollback

`scripts/rollback-verygames.sh --latest` restaure le JAR du lot 5, puis redémarrer. Pour
`guard.yml` :

```bash
scripts/rollback-verygames.sh --also \
  /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261007T174129Z/RPGQuest/dialogues/guard.yml:RPGQuest/dialogues/guard.yml
```

Le rollback du JAR seul est **sans danger** avec le nouveau `guard.yml` en place : l'ancien code
ignore simplement le nœud `wild_conditions`, et le marqueur `%wild_conditions%` s'afficherait
littéralement si un joueur empruntait ce choix. Pour éviter ce détail cosmétique, restaurer les deux.
Aucune donnée joueur n'est concernée (la seule écriture est une ligne inerte dans
`player_variables`).

---

## 2026-10-07 (lot 7) - Rapporter des objets à un PNJ : objectif DELIVER_ITEM_TO_NPC (#123)

### Changement

Nouveau type d'objectif de quête **`DELIVER_ITEM_TO_NPC`** : le joueur doit **réellement remettre**
N exemplaires d'un objet au PNJ configuré, dans son dialogue. Posséder, ramasser ou fabriquer
l'objet ne fait **jamais** avancer cet objectif — il n'écoute aucun événement de jeu, ce qui est
exactement ce qui le distingue de `COLLECT_ITEM`.

**Dépôts partiels persistants** : la quantité déjà remise *est* le compteur de l'objectif, stocké
dans la table `quest_objective_progress` qui existait déjà. Elle survit donc à la mort, à une
déconnexion et à un redémarrage, et le joueur revient déposer le reliquat plus tard. Les objets
remis sont **consommés** et ne sont jamais restitués.

Une **seule** interaction (« Donner les matériaux que j'ai ») remet tout ce que le joueur possède
d'utile, pour tous les objectifs de ce PNJ et même plusieurs quêtes à la fois ; le serveur ne retire
jamais plus que le reliquat et laisse le surplus au joueur. Plusieurs piles sont additionnées ; seul
le stockage normal est touché (ni armure, ni main secondaire) ; **aucun objet personnalisé RPGQuest
n'est consommé** (identité PDC), sans quoi une quête demandant `BOOK` détruirait le journal de
quêtes d'un joueur.

Côté dialogue : action **`DELIVER_QUEST_ITEMS`** et condition **`HAS_PENDING_DELIVERY`**, toutes deux
à `npc` **optionnel** — vide, le destinataire est déduit de la clé du dialogue. La branche livrée
dans `dialogues/guard.yml` (nœuds `delivery`, `delivery_after`, `delivery_done`) ne nomme donc ni
quête, ni matériau, ni PNJ : elle se recopie telle quelle dans le dialogue d'un autre PNJ. Le
marqueur `%delivery_status%` affiche, dans le texte d'un nœud, ce qui est déjà remis et ce qui
manque.

Control Panel : type d'objectif « **Rapporter des objets à un PNJ** » dans l'éditeur guidé (PNJ,
objet, quantité), résumé « Rapporter Cuir (x4) à Garde », aller-retour YAML complet. Journal :
« Cuir (à remettre) 2/4 ».

Correctif inclus : les compteurs d'une quête active passaient par un `HashMap` écrit sur le thread
principal mais lu depuis un thread de base de données (la condition de dialogue est évaluée sur le
thread qui termine la requête précédente) — désormais un `ConcurrentHashMap`.

### Action serveur

Remplacement du JAR RPGQuest **et** du fichier de données `RPGQuest/dialogues/guard.yml`.

⚠️ `guard.yml` est **obligatoire** : un redéploiement de JAR ne met jamais à jour un dialogue déjà
présent. Sans lui, le code de remise serait en place mais **aucun PNJ ne proposerait de recevoir
quoi que ce soit**.

`messages.yml` : **ne pas transférer**. Les six nouvelles clés `quest.delivery-*` sont fusionnées
automatiquement dans le fichier du serveur par `QuestMessagesService#mergeMissingKeys`, ce qui
préserve les personnalisations existantes — transférer le fichier les écraserait.

Aucune migration, aucune nouvelle clé de `config.yml`, aucun monde touché.

### Sauvegarde préalable

Automatique via `scripts/deploy-verygames.sh` (JAR + chaque fichier `--also`). Ne pas écraser le
backup du lot 6.

### Déploiement

**Effectué le 2026-10-07 entre 22:01 et 22:05 (heure locale).**

- Branche `feature/123-deliver-item-to-npc`, commit `26a17ea`, construit et déployé depuis un
  **worktree Git propre** (les fichiers de contenu non suivis du propriétaire restent intacts dans le
  dépôt principal).
- `./gradlew build` (inclut `test` des trois modules) : **1851 tests plugin + 725 panel + 30 web-api,
  0 échec** (37 + 1 ignorés, limitations MockBukkit déjà documentées). **Premier passage échoué** :
  `ManualTestQuestPackTest` exige une quête de `docs/manual-tests/quests/` par type d'objectif, et le
  huitième type n'en avait pas ; corrigé (`test_deliver_item_to_npc.yml`) puis passage vert — les
  chiffres ci-dessus sont ceux du passage après correction.
- JAR transféré : **1 954 747 o**, SHA-256
  `58cc3a8e454b4ee355802a0c38980769cdffa3a13439ccad3c7b8574039d2bfd`.
- Backup préalable : `rpgquest-20261007T200111Z-predeploy.jar` (1 935 862 o) — correspond au JAR du
  lot 6, la chaîne de rollback est donc intacte.
- `RPGQuest/dialogues/guard.yml` : backup `extra-20261007T200111Z/RPGQuest/dialogues/guard.yml`
  (6 638 o, SHA-256 `a984f15b…`) puis transfert de 9 019 o, SHA-256
  `a9bd55c302833bb3ea6bfc948549bfae0e1c4106e34f79db3b7ca8a63f53d4b0` — **identique octet pour octet**
  au fichier du dépôt. Vérifié **avant** le transfert : la version en ligne était exactement celle du
  lot 6, donc aucune édition faite depuis le Control Panel n'a été écrasée.
- **Redémarrage Minecraft effectué** : `save-all`, `stop` RCON, OFFLINE constaté, retour **ONLINE**.
  **0 joueur connecté** avant comme après — aucune annonce n'a donc été diffusée.
- Vérifications après redémarrage : `/plugins` → 5 plugins verts (Citizens, LuckPerms,
  Multiverse-Core, RPGQuest, WorldEdit) ; `/rpgquest version` → `v0.1.0-SNAPSHOT` ;
  `/quest admin reload` → **15 quêtes chargées, 0 erreur**.
- **Non vérifié** : le chargement du dialogue `rpgquest:guard` constaté en jeu. Les logs serveur ne
  sont pas accessibles depuis la machine de build (racine FTP = `plugins/`), les dialogues ne sont pas
  rechargeables à chaud, et `/dialogue open` vérifie le joueur **avant** le dialogue — avec 0 joueur
  connecté, la commande ne peut rien révéler. Le fichier déployé est néanmoins identique octet pour
  octet à celui que `BundledDialoguesValidityTest` charge sans aucun problème.

### Validation

`PENDING MANUAL VALIDATION` — **TC-257** de `docs/MANUAL_TEST_PLAN.md` (24 points) : création de la
quête depuis le Control Panel et aller-retour en édition, « posséder ne suffit pas », dépôt partiel,
survie à la mort / à la reconnexion / au redémarrage, quatre matériaux remis en une seule
interaction, surplus jamais consommé, spam du bouton, mauvais PNJ, objectif déjà terminé, objet
personnalisé épargné.

### Rollback

`scripts/rollback-verygames.sh --latest` restaure le JAR du lot 6, puis redémarrer. Pour
`guard.yml` :

```bash
scripts/rollback-verygames.sh --also \
  /home/ubuntu/.local/share/rpgquest/verygames-backups/extra-20261007T200111Z/RPGQuest/dialogues/guard.yml:RPGQuest/dialogues/guard.yml
```

⚠️ **Restaurer les deux fichiers, pas seulement le JAR.** Contrairement au lot 6, `guard.yml` est
désormais **couplé** au nouveau JAR : l'ancien code ne connaît ni `DELIVER_QUEST_ITEMS` ni
`HAS_PENDING_DELIVERY`, donc le dialogue resté en place serait **rejeté au chargement** (type d'action
inconnu) et le Garde n'aurait plus de dialogue du tout.

Aucune donnée joueur n'est perdue par un rollback : les compteurs de remise déjà écrits restent dans
`quest_objective_progress` et redeviendront lisibles si le JAR est redéployé. En revanche les objets
déjà consommés ne reviennent pas — c'est la sémantique voulue du ticket.

---

## 2026-10-07 (lot 7b) - Rattrapage : Control Panel jamais déployé avec #123

### Changement

**Aucun changement de code.** Correction d'un déploiement **manquant** : le lot 7 (#123) a déployé le
JAR du plugin et `dialogues/guard.yml` sur VeryGames, mais **pas** le Control Panel — alors que le
commit `10ce149` contient toute la partie panel de #123 (type d'objectif « Rapporter des objets à un
PNJ »).

Symptôme constaté par le propriétaire : dans `/quests` → Créer une quête → Objectifs, la liste ne
proposait que les 7 anciens types. TC-257 bloquait donc dès son premier point.

### Action serveur

Déploiement du **Control Panel uniquement**, via `scripts/plugadmin/deploy.sh`. **Aucun redéploiement
du JAR RPGQuest, aucun redémarrage du serveur Minecraft** : rien n'a changé côté plugin depuis le
lot 7 (JAR en ligne vérifié : 1 954 747 o, soit `58cc3a8e…`, inchangé).

### Sauvegarde préalable

Automatique : l'ancienne application a été déplacée vers
`/opt/plugadmin/releases/20261007-222157/` (rétention de 5 releases assurée par le script).

### Déploiement

**Effectué le 2026-10-07 entre 22:21 et 22:25 (heure locale).**

- Branche `feature/123-deliver-item-to-npc`, commit `f574a74` (contient `10ce149` et les suivants).
  Le module `control-panel` était propre dans le dépôt : la distribution bâtie correspond donc
  exactement à ce commit.
- **Diagnostic d'abord, déploiement ensuite.** La version réellement installée a été identifiée avant
  toute action : service `plugadmin` actif depuis **17:44:15** (déploiement du lot 5, #165), et
  inspection du JAR installé → `Descriptors.class` ne contenait **que** les 7 anciens types, aucune
  trace de `DELIVER_ITEM_TO_NPC`. Confirmation qu'il s'agissait d'un déploiement manquant, pas d'un
  défaut de code (les tests panel du lot 7 étaient verts, dont `EditorDescriptorsTest` qui exige
  l'ensemble des 8 types).
- JAR panel installé : **1 161 184 → nouvelle version**, SHA-256
  `5e3bea4bb17c5186637504bbc36e0a3f441d8d4568d6f4909240d9d8a87822b6`.
- `systemctl restart plugadmin` : service **actif** depuis 22:22:00.
- **`/health` → `{"panel":"ONLINE","disabled":false}`.** Le script a d'abord rapporté `/health KO`
  parce qu'il interroge le port ~2 s après le redémarrage, avant que la JVM ne l'ait lié — même
  comportement déjà noté au lot 5. Vérifié ensuite réellement, service actif et `/health` ONLINE.
  Aucun rollback nécessaire.

### Validation

Vérifié **de bout en bout sur la page réellement servie** (et pas seulement dans le fichier installé),
avec un compte PlugAdmin jetable `CONTENT_EDITOR` créé puis **supprimé** immédiatement après :

- `GET /quests/new` → **HTTP 200**, et la liste des types d'objectifs contient désormais les **8**
  types, dont `DELIVER_ITEM_TO_NPC` → « Rapporter des objets à un PNJ » ;
- l'aide du type est servie (« … REMETTRE N exemplaires … Collecter ou posséder l'objet ne suffit
  pas … Les dépôts partiels comptent et sont conservés … ») ;
- les trois champs attendus sont présents : « PNJ destinataire », « Objet à rapporter »,
  « Quantité à remettre ».

`PENDING MANUAL VALIDATION` — **TC-257** reste entièrement à dérouler ; seul son blocage initial est
levé.

### Rollback

`scripts/plugadmin/rollback.sh app` (ou restauration manuelle de
`/opt/plugadmin/releases/20261007-222157/`), puis `systemctl restart plugadmin`. Le plugin et les
données du serveur Minecraft ne sont pas concernés par ce lot.

---

## 2026-10-07 (lot 7c) - PNJ destinataire perdu à l'affichage, et publication de la quête de recette (#123)

### Changement

Deux choses, constatées en recette TC-257.

**1. Correctif de rendu (code).** Une quête de remise « Source uniquement » s'affichait
« Rapporter Cuir (x4) **à ?** » alors que son YAML enregistré portait bien `npc: guard` sur les
quatre objectifs — la source et l'aller-retour étaient corrects. `AgentPages#objectiveDetail`
convertit un objectif de la **source** dans la même forme que le relevé runtime de l'agent et
n'émettait pas `npc` : une remise a **deux** cibles (l'objet compté et le PNJ qui le reçoit) et
`target` ne peut en porter qu'une. Le champ avait été ajouté au relevé de l'agent au lot 7, mais pas
à ce convertisseur. Un destinataire réellement absent s'affiche désormais « **(PNJ non défini)** »
plutôt que « ? », et le nom français de `WHEAT_SEEDS` a été ajouté (il s'affichait « Wheat Seeds »).

**2. Publication d'une donnée de contenu (aucun code).** La quête de recette
`rpgquest:test_remise`, créée depuis le Control Panel, existait dans la source éditable mais
**jamais sur le serveur** — d'où son badge « Source uniquement » et un `/quest accept` en
« Quête inconnue ». C'est le comportement **normal** : le panel édite la source sur la machine AWS,
le serveur DEV est chez VeryGames et n'est joignable que par déploiement. La bannière du panel le
dit déjà (« … ou il n'a **jamais été déployé** depuis AWS — un rechargement n'y changerait rien »).

### Action serveur

- **Control Panel** : redéployé (`scripts/plugadmin/deploy.sh`).
- **Serveur Minecraft** : transfert du **seul** fichier de données
  `RPGQuest/quests/test_remise.yml`, puis `/quest admin reload`. **Aucun redémarrage** : le JAR est
  inchangé (empreinte identique à celle du lot 7) et un rechargement de quêtes suffit pour une
  définition ajoutée.

### Sauvegarde préalable

Panel : application précédente dans `/opt/plugadmin/releases/20261007-233237/`.
Serveur : `scripts/deploy-verygames.sh` a sauvegardé le JAR (identique) sous
`rpgquest-20261007T213349Z-predeploy.jar`, et le dossier `extra-20261007T213349Z/` porte le
manifeste du fichier `--also`. La quête étant **nouvelle** en ligne, le script a d'abord **refusé**
le transfert (« absent en ligne : rien à sauvegarder ») : c'est son garde-fou, et `--allow-no-backup`
est l'option documentée pour créer un fichier — pas un contournement.

### Déploiement

**Effectué le 2026-10-07 entre 23:32 et 23:36 (heure locale).**

- Branche `feature/123-deliver-item-to-npc`, commit `b2359db`.
- `./gradlew build` sur ce commit, dans un worktree propre : **1851 tests plugin + 729 panel +
  30 web-api, 0 échec**.
- JAR panel installé : SHA-256
  `4735bfb001748521b886fd6906e13e881a0b911a0a0b1ef18866a2445f43cef7` ; service actif depuis
  23:32:40 ; `/health` → `{"panel":"ONLINE","disabled":false}` (le script a de nouveau rapporté
  `/health KO` : il interroge le port ~2 s après le redémarrage — vérifié réellement ensuite).
- `RPGQuest/quests/test_remise.yml` transféré : **644 octets**, taille distante == locale. Le
  fichier reste **non suivi par Git** (contenu local du propriétaire), comme les autres.
- JAR RPGQuest : re-téléversé à l'identique par le script (même empreinte
  `58cc3a8e…`), donc **aucun changement de plugin** et aucun redémarrage Minecraft.

### Validation

- `/quest admin validate` (lecture du disque serveur, sans rien recharger) → **16 quête(s), 0
  erreur** ; `/quest admin reload` → **16 quête(s) chargée(s), 0 erreur**, contre 15 avant
  publication.
- Vérifié sur le panel réellement servi (compte jetable `OWNER` créé puis **supprimé**) : après un
  rafraîchissement `quest.list`, le bloc `rpgquest:test_remise` ne porte **plus** le badge « Source
  uniquement » (ni « Hors source ») — source et runtime fusionnés — et ses quatre objectifs
  s'affichent « Rapporter Cuir (x4) **à Guard** », « Rapporter Bâton **à Guard** », « Rapporter
  Pierre taillée (x2) **à Guard** », « Rapporter **Graines de blé** (x3) **à Guard** ».
- **Non vérifié** : `/quest accept rpgquest:test_remise`. Cette commande exige un **joueur
  connecté** (`/quest list` et `/quest accept` refusent la console) et il n'y avait aucun joueur en
  ligne. La quête est chargée par le runtime — le comptage 15 → 16 avec 0 erreur le prouve — mais
  l'acceptation elle-même reste à constater en jeu, au point 6 de TC-257.

`PENDING MANUAL VALIDATION` — **TC-257** reprend à son point 1.

### Rollback

Panel : `scripts/plugadmin/rollback.sh app` (release `20261007-233237`) puis
`systemctl restart plugadmin`. Quête de recette : supprimer
`RPGQuest/quests/test_remise.yml` du serveur puis `/quest admin reload` (c'est une quête de **test**,
elle n'a pas à rester en production). Le JAR et les données joueur ne sont pas concernés.

## 2026-10-08 (nuit) - Paliers du kit, SMELT_ITEM, DISCOVER_WAYPOINT, bornes du Hub, contrat de contenu (#218 #141 #156 #185 #110 #195)

### Changement

Lot de nuit, six sujets :

- **#218** — le kit de départ rendu par le Guide après une mort devient une
  **progression par paliers** définie dans `config.yml`
  (`starter-tool-kit.tiers`). Palier 1 automatique (4 outils en bois) ; palier 2
  débloqué par la nouvelle quête `rpgquest:kit_tier2`, qui demande 1 bâton,
  2 pierres, 4 cuirs et 3 graines de blé au Guide et donne épée en pierre,
  pioche/pelle/hache en bois, bottes en cuir et un pain. Un palier **ne peut pas
  être sauté** : le déblocage passe par `/rpgadmin kit grant-tier`, qui refuse un
  niveau non contigu ou non défini.
- **#141** — nouvel objectif de quête `SMELT_ITEM` : **cuire** réellement un
  objet dans un four, un haut fourneau ou un fumoir.
- **#185** — nouvel objectif de quête `DISCOVER_WAYPOINT` : découvrir N
  waypoints distincts, par **première découverte réelle** uniquement.
- **#156** — correction d'un défaut d'appariement : une instance de biome du Hub
  ne pouvait obtenir sa borne qu'à une visite **ultérieure**. Diagnostic
  administrable ajouté (`/rpgadmin travel diagnose` et panel `/travel`) donnant
  la **cause** de chaque appariement manquant.
- **#110** — Control Panel : contrat de contenu machine-readable téléchargeable
  (schéma JSON, gabarits YAML, contrat rédigé pour une IA).
- **#195** — Control Panel : plus aucun champ de texte destiné aux joueurs
  n'impose d'écrire du MiniMessage.

### Action serveur

1. **Remplacer le JAR RPGQuest** (#218, #141, #156, #185 sont côté plugin).
2. **Transférer `RPGQuest/dialogues/guide.yml`** — obligatoire. Un redéploiement
   de JAR ne met **jamais** à jour un dialogue déjà présent sur le serveur, et le
   Guide a besoin de la branche de remise générique pour que la quête de palier 2
   soit jouable. La version en ligne a été comparée **avant** transfert : la seule
   différence était l'absence de cette branche (0 ligne supprimée, 49 ajoutées),
   donc aucune personnalisation du propriétaire n'est perdue.
3. **Ne PAS transférer `config.yml`.** La section `starter-tool-kit.tiers` est
   ajoutée automatiquement au démarrage par `ConfigFileCompleter`, qui ne copie
   que les clés absentes et n'écrase jamais une valeur existante. Vérifié dans le
   code : `tiers` étant une liste absente du fichier serveur, elle est copiée
   telle quelle.
4. **Ne PAS transférer la nouvelle quête** `quests/kit_tier2.yml` : elle fait
   partie des exemples embarqués du JAR et est **semée automatiquement** au
   démarrage parce qu'elle est absente du serveur (16 → 17 quêtes).
5. **Redéployer le Control Panel** (#110, #195, et les deux ajouts d'affichage de
   #156 et #185) — déploiement **distinct**, script distinct.

### Sauvegarde préalable

Automatique par `deploy-verygames.sh` : le JAR en ligne **et** `guide.yml` sont
sauvegardés en `*.predeploy-<horodatage>` avant remplacement. Côté panel,
`scripts/plugadmin/deploy.sh` archive l'application précédente dans
`/opt/plugadmin/releases/<horodatage>/`.

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, les mondes et
`RPGQuest/Citizens/` ne sont **jamais** touchés par le script.

### Déploiement

Fait depuis un **worktree Git propre** (`/srv/rpgquest/worktree-nuit`, détaché
sur le commit poussé), et non depuis l'arbre de travail : des fichiers de
contenu modifiés localement par le propriétaire y font échouer
`CrystalHuntIntegrationTest`, ce qui bloquerait le script.

```bash
# 1. Serveur Minecraft (VeryGames)
cd /srv/rpgquest/worktree-nuit
RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh \
    --also src/main/resources/dialogues/guide.yml:RPGQuest/dialogues/guide.yml -y
scripts/verygames-restart.sh

# 2. Control Panel (AWS, déploiement DISTINCT)
cd /srv/rpgquest/worktree-nuit
scripts/plugadmin/deploy.sh
```

**Fait le 2026-10-08 à 03:31–03:33 (heure locale), commit `b0c0b8f`.**

| Élément | Valeur |
|---|---|
| JAR déployé | 1 985 816 o, SHA-256 `acc195411336ac6d…` |
| JAR précédent (backup) | `~/.local/share/rpgquest/verygames-backups/rpgquest-20261008T013149Z-predeploy.jar` (1 954 747 o, celui du lot #123) |
| `guide.yml` | 6 571 → 8 653 o ; backup `extra-20261008T013149Z/RPGQuest/dialogues/guide.yml` (sha256 `4b86a69d…`) |
| Redémarrage | `save-all` puis stop RCON, retour ONLINE confirmé, **0 joueur** avant comme après |
| Control Panel | release précédente archivée en `/opt/plugadmin/releases/20261008-033256/`, nouveau JAR 1 188 383 o (contre 1 161 883) |

Le script de déploiement du panel a de nouveau rapporté `/health KO` : il sonde
le port environ 2 secondes après le redémarrage, avant que la JVM ne l'ait lié.
Vérifié manuellement juste après : `{"panel":"ONLINE"}`. Aucun rollback.

### Validation

Vérifié réellement, pas supposé :

- `/plugins` → **5 plugins, tous verts** (Citizens, LuckPerms, Multiverse-Core, RPGQuest, WorldEdit).
- `/rpgquest version` → `v0.1.0-SNAPSHOT`.
- `/quest admin validate` → **17 quête(s) chargée(s), 0 erreur(s)** — contre 16 avant, ce qui prouve que `kit_tier2.yml` a bien été semé automatiquement.
- `config.yml` du serveur **relu après redémarrage** : la section `starter-tool-kit.tiers` contient bien les deux paliers, et l'ancien `items:` est conservé intact. Réserve annoncée confirmée : les commentaires du bloc ajouté ne sont pas reportés, les valeurs le sont.
- `/rpgadmin kit` depuis la console → affiche son aide (`status`, `grant-tier`), donc la branche d'administration de #218 est bien active.
- Panel : `/health` → `ONLINE` ; les trois nouvelles routes `/content/schema.json`, `/content/template` et `/content/contract.md` répondent **303 vers /login** (elles existent et sont protégées — elles n'existaient pas avant ce déploiement).
- **Bytecode réellement installé inspecté** (leçon du lot #123, où seul le serveur Minecraft avait été mis à jour) : `ContentPackSchema.class` et `ContentPackTemplates.class` présents, `DISCOVER_WAYPOINT` présent dans `Descriptors.class`, libellés de badge « étape(s) / objectif(s) » présents dans `AgentPages.class`, fiche `docs/content-packs.md` embarquée.

**Non vérifié, volontairement** : `/rpgadmin travel diagnose` exige un **joueur en jeu** (il a besoin d'une position et refuse la console) ; le même relevé est consultable au navigateur sur `/travel`. Tout le reste du comportement en jeu relève de **TC-258 à TC-263**, `PENDING MANUAL VALIDATION`.

### Rollback

- **Minecraft** : `scripts/rollback-verygames.sh` restaure le JAR sauvegardé
  (`*.predeploy-<horodatage>`). `guide.yml` a lui aussi son backup daté. Un
  rollback du JAR **avant #123** casserait `guide.yml` (il utilise
  `HAS_PENDING_DELIVERY` et `DELIVER_QUEST_ITEMS`) — restaurer alors les deux
  ensemble.
- **config.yml** : le plugin en écrit un `.bak` avant d'ajouter la section
  `tiers`. À noter : les **commentaires** du bloc ajouté ne sont pas reportés
  dans le fichier serveur (les valeurs le sont) — comportement habituel de la
  complétion automatique, les commentaires des clés déjà présentes sont
  conservés.
- **quête `kit_tier2.yml`** : supprimer le fichier côté serveur et faire
  `/quest admin reload`. Attention, il fait partie des exemples embarqués : il
  sera **re-semé** au prochain démarrage. Pour le désactiver durablement, retirer
  le palier 2 de `starter-tool-kit.tiers`.
- **Control Panel** : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`.
- **Données joueur** : aucune n'est touchée, aucune migration, rien à annuler.

## 2026-10-08 (matin) - Import sécurisé d'un content pack, et correction de la clé des stories (#109)

### Changement

- **#109** — le Control Panel sait désormais **importer** un content pack :
  `/content/import`, permission dédiée `CONTENT_IMPORT`. Le pack est analysé,
  validé et comparé à la source ; **rien n'est écrit avant confirmation**. Une
  collision d'identifiant exige un arbitrage explicite (remplacer / garder),
  avec un diff par élément. L'import **écrit dans la source**, il n'active rien
  sur le serveur Minecraft.
- **Correction de format (plugin)** — l'export écrivait la liste des quêtes
  d'une story sous la clé `questIds`, alors que le moteur lit `quests`. Une
  story exportée n'était donc **pas relisible** par le serveur. L'export écrit
  maintenant `quests` ; l'import accepte les deux orthographes, donc les packs
  déjà exportés restent importables et `schemaVersion` reste à 1.

### Action serveur

1. **Remplacer le JAR RPGQuest** — le correctif de format est côté plugin
   (`ContentPackSerializer`). Sans ce redéploiement, les exports continueraient
   d'écrire `questIds` ; rien ne casse (l'import les accepte) mais la correction
   ne prendrait pas effet.
2. **Redéployer le Control Panel** — c'est là que vit l'import. Déploiement
   **distinct**, script distinct.
3. **Aucun fichier de contenu à transférer.** Aucune quête, aucun dialogue,
   aucune story embarquée n'a changé.
4. **Aucun changement de configuration.**

### Sauvegarde préalable

Automatique : `deploy-verygames.sh` sauvegarde le JAR en ligne avant
remplacement ; `scripts/plugadmin/deploy.sh` archive l'application précédente
dans `/opt/plugadmin/releases/<horodatage>/`.

`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, les mondes et
`RPGQuest/Citizens/` ne sont pas touchés.

### Déploiement

Depuis le **worktree Git propre** `/srv/rpgquest/worktree-nuit`, détaché sur le
commit poussé :

```bash
cd /srv/rpgquest/worktree-nuit
RPGQUEST_TEST_MAX_HEAP=768m scripts/deploy-verygames.sh -y
scripts/verygames-restart.sh
scripts/plugadmin/deploy.sh
```

**Fait le 2026-10-08 à 09:49–09:52 (heure locale), commit `63b6b1d`.**
`DEPLOY_EXIT=0`, `RESTART_EXIT=0`, `PANEL_DEPLOY_EXIT=0`.

| Élément | Valeur |
|---|---|
| JAR déployé | 1 985 814 o, SHA-256 `a335db8cd2292792…` |
| JAR précédent (backup) | `rpgquest-20261008T074902Z-predeploy.jar`, 1 985 816 o |
| Redémarrage | `save-all` puis stop RCON, retour ONLINE, **0 joueur** avant comme après |
| Control Panel | nouveau JAR 1 219 885 o (contre 1 188 383) ; release précédente en `/opt/plugadmin/releases/20261008-094944/` |

**Le nouveau JAR est 2 octets PLUS PETIT que le backup** — ce que la règle de
déploiement traite comme un signal d'arrêt. Il a été expliqué avant de
continuer, pas écarté : le commit en ligne (`b0c0b8f`) est un **ancêtre strict**
du commit déployé (`git merge-base --is-ancestor`), le seul changement côté
plugin est la chaîne `"questIds: "` → `"quests: "` soit exactement 2 caractères
de moins, et les deux JAR contiennent **845 classes** avec **aucune
disparition** (comparaison des listes de classes).

Le script du panel a de nouveau rapporté un faux `/health KO` : il sonde le port
environ 2 secondes après le redémarrage. Vérifié manuellement ensuite.

### Validation

Vérifié réellement, pas supposé :

- `/plugins` → **5 plugins, tous verts**.
- `/quest admin validate` → **17 quête(s), 0 erreur(s)** — inchangé, et c'est
  attendu : aucun contenu embarqué n'a été modifié par ce lot.
- Panel `/health` → `{"panel":"ONLINE"}`.
- Route `/content/import` → **303 vers /login** : elle existe et est protégée.
  Elle n'existait pas avant ce déploiement.
- **Bytecode réellement installé inspecté** (leçon du lot #123) : les classes
  `ContentPackImport` (et ses 7 types imbriqués) et `ContentImportPages` sont
  présentes, `CONTENT_IMPORT` figure dans `Permission.class`, et la fiche d'aide
  `content-packs.md` embarquée contient bien la section « Importer ».

**Non vérifié** : tout le comportement fonctionnel de l'import relève de
**TC-264** (14 points, `PENDING MANUAL VALIDATION`). En particulier le point 7
(collision concurrente) et le point 13 (`/quest admin reload` après import) sont
les deux seuls à pouvoir démentir les garanties annoncées.

### Rollback

- **Minecraft** : `scripts/rollback-verygames.sh --latest`. Aucun fichier de
  contenu n'étant transféré, le rollback du JAR suffit — pas de couplage avec un
  dialogue comme au lot précédent.
- **Control Panel** : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`. Un rollback du panel **retire l'import** ; les
  contenus déjà importés restent dans la source, ils ont été écrits par les mêmes
  écrivains que l'éditeur guidé.
- **Contenu importé par erreur** : le supprimer depuis l'éditeur (`/quests`,
  `/stories`, `/dialogues`) puis `/quest admin reload`. L'import ayant écrit dans
  la source, il n'y a rien à annuler côté serveur tant qu'aucun déploiement de
  contenu n'a eu lieu.
- **Données joueur** : aucune touchée, aucune migration.

## 2026-10-08 (matin, 2e lot) - Atelier IA : créer une quête en français (#146)

### Changement

Le Control Panel sait désormais **générer une quête à partir d'une description en
français**, via une IA configurée par l'administrateur.

- **`/ai/studio`** — « Créer avec une IA ». On décrit l'intention ; le panel joint
  automatiquement le contrat de contenu, le schéma, les types réellement
  supportés et les références réellement existantes. La proposition passe par les
  **mêmes validateurs** que l'éditeur guidé, puis par la page d'import pour
  l'enregistrement. **L'atelier n'écrit jamais.**
- **`/ai/providers`** — configuration des fournisseurs (Anthropic/Claude, OpenAI
  et API compatibles, Google Gemini), avec clé API, modèle, URL de base, plafond
  de jetons, délai, et un bouton **« Tester la connexion »** qui fait un vrai
  appel.
- Deux permissions distinctes : **`AI_USE`** (générer) et **`AI_CONFIGURE`**
  (clés API, administrateurs seulement).

### Action serveur

1. **Redéployer le Control Panel.** C'est tout.
2. **Aucun nouveau JAR, aucun redémarrage Minecraft.** Ce lot ne touche
   strictement rien côté plugin — vérifié : `git status` ne rapporte aucun fichier
   modifié sous `src/`, et le build confirme `:test` et `:jar` inchangés.
3. **Aucun fichier de contenu, aucune configuration à transférer.**
4. **Aucune migration.** La table `ai_provider` est créée au démarrage du panel
   par un `CREATE TABLE IF NOT EXISTS` idempotent, dans la base locale du panel —
   pas dans `data.db` du serveur Minecraft.

### Sauvegarde préalable

Automatique : `scripts/plugadmin/deploy.sh` archive l'application précédente dans
`/opt/plugadmin/releases/<horodatage>/`. La base du panel
(`/var/lib/plugadmin/control-panel.db`) n'est pas touchée par le déploiement ;
elle contient désormais aussi les clés API, donc **toute sauvegarde de cette base
contient des secrets** et doit être traitée comme telle.

### Déploiement

```bash
cd /srv/rpgquest/worktree-nuit
scripts/plugadmin/deploy.sh
```

**Fait le 2026-10-08 à 10:53 (heure locale), commit `703e29a`.**
`PANEL_DEPLOY_EXIT=0`.

| Élément | Valeur |
|---|---|
| Control Panel | nouveau JAR 1 277 146 o (contre 1 219 885) |
| Release précédente | `/opt/plugadmin/releases/20261008-105331/` |
| Minecraft | **non touché** — JAR en ligne relu : 1 985 814 o, identique à celui du lot #109 |
| Redémarrage Minecraft | **aucun** |

Pour une fois le script a lui-même rapporté `/health` ONLINE : il a sondé assez
tard. Aucun rollback.

### Validation

Vérifié réellement, pas supposé :

- Panel `/health` → `{"panel":"ONLINE"}`.
- Routes `/ai/studio` et `/ai/providers` → **303 vers /login** : elles existent et
  sont protégées. Elles n'existaient pas avant ce déploiement.
- **Bytecode réellement installé inspecté** : les 16 classes du paquet
  `panel.ai` (dont les trois fournisseurs, le socle HTTP, l'extracteur et le
  stockage) ainsi que `AiStudioPages` et `AiProviderPages` sont présentes dans le
  JAR servi.
- **Table `ai_provider` créée** dans la base de production, et **vide** : aucune
  clé n'est enregistrée, ce qui est l'état attendu après un déploiement. L'IA est
  donc inerte jusqu'à ce qu'un administrateur configure un fournisseur.
- **Minecraft intact** : JAR en ligne relu et identique à celui du lot #109,
  `/quest admin validate` → **17 quête(s), 0 erreur(s)**, aucun redémarrage.

**Non vérifié** : tout le comportement fonctionnel de l'atelier relève de
**TC-265** (22 points, `PENDING MANUAL VALIDATION`), qui exige une **vraie clé
API** et consomme des jetons facturés. Aucune des trois implémentations de
fournisseur n'a encore été appelée avec une vraie clé — le bouton « Tester la
connexion » donne cette réponse en quelques secondes, et c'est le point 4 du test.

### Rollback

- **Control Panel** : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`. Un rollback retire les deux pages ; la table
  `ai_provider` et les clés qu'elle contient **restent** dans la base — c'est
  voulu, un retour en arrière du code ne doit pas détruire une configuration.
- **Révoquer l'accès sans redéployer** : retirer `AI_USE` ou `AI_CONFIGURE` du
  rôle ou du groupe concerné.
- **Neutraliser l'IA immédiatement** : décocher « Activer » sur chaque
  fournisseur, ou effacer les clés depuis `/ai/providers`. Aucun appel n'est
  alors possible.
- **Données joueur et serveur Minecraft** : rien n'est concerné.

### Note de sécurité

La base du panel contient maintenant des **clés d'API tierces**. Elle est en mode
`600` et appartient au seul compte du service, hors du dépôt Git. Deux
conséquences pratiques :

- ne pas copier cette base ailleurs sans y penser (une sauvegarde non chiffrée
  contiendrait les clés en clair) ;
- en cas de doute sur une fuite, révoquer la clé chez le fournisseur puis en
  poser une nouvelle depuis `/ai/providers` — l'empreinte affichée permet de
  vérifier que la rotation a bien eu lieu.

## 2026-10-08 (matin, 3e lot) - Atelier IA : dialogues et stories, et vocabulaire de dialogue validé (#146)

### Changement

- **#146 (suite)** — l'atelier IA couvre désormais les **trois familles
  éditables** : une quête, **un dialogue** ou **une story**, choisies par un
  lien en tête de `/ai/studio`. Un seul élément par demande, volontairement.
  Chaque famille impose ses propres consignes (id de dialogue = id du PNJ,
  nœuds en map, un choix dont une condition est fausse n'est pas affiché ; une
  story n'ordonne que des quêtes existantes et n'en invente aucune). La
  garantie structurelle est inchangée : **l'atelier n'a aucun code
  d'écriture**, l'enregistrement reste l'import de #109 avec sa confirmation.
- **Vocabulaire de dialogue déclaré et contraint** — les **12 actions** et
  **8 conditions** du moteur sont déclarées côté panel et verrouillées sur les
  énumérations du moteur par un test de couverture exacte. Le schéma de content
  pack les contraint, les gabarits et la documentation IA les énoncent.
- **Trou de validation fermé** — `DialogueValidator` refuse désormais un type
  d'action ou de condition inconnu, un champ obligatoire absent, un champ
  appartenant à un autre type, un entier non positif et un état de quête
  inexistant. **Avant ce lot, une action inventée traversait l'éditeur et
  l'import sans un mot** et n'échouait qu'au chargement du serveur Minecraft.
- **Trois défauts de format corrigés** (côté panel uniquement) : le schéma et
  les exemples de #110 déclaraient `nodes` comme une **liste**, alors que le
  moteur et l'export du plugin écrivent une **map** indexée par id — l'exemple
  de référence du contrat était donc inimportable ; l'exemple « minimal
  valide » n'avait pas de `category`, pourtant obligatoire ; l'aide de l'état
  de quête n'énonçait que cinq des **six** états réels (`ABANDONED` manquait).

### Action serveur

**Aucune action côté serveur Minecraft.** Aucun changement de plugin : ce lot
est **entièrement** dans `control-panel/`. Pas de JAR à remplacer, pas de
redémarrage Minecraft, pas de fichier de configuration, pas de migration.

**Control Panel seul** : redéployer l'application (`scripts/plugadmin/deploy.sh`).
Aucune migration de base : la table `ai_provider` de ce matin est inchangée, et
les clés déjà configurées restent en place.

### Sauvegarde préalable

Release précédente du panel (conservée automatiquement par le script de
déploiement). Rien à sauvegarder côté Minecraft.

### Déploiement

1. `scripts/plugadmin/deploy.sh` depuis un arbre propre sur le commit visé.
2. Vérifier `/health`, puis `/ai/studio` : la section « Que voulez-vous
   créer ? » doit proposer **trois** liens.

### Validation

**Vérifié automatiquement** : suite complète, voir le rapport de session. Le
vocabulaire est confronté aux énumérations du moteur, chaque dialogue embarqué
du dépôt est confronté au vocabulaire, et les exemples du contrat passent
désormais un **vrai** import.

**Non vérifié** : **TC-266** (`PENDING MANUAL VALIDATION`) — génération réelle
d'un dialogue et d'une story, et chargement effectif par le serveur. Exige une
vraie clé API et consomme des jetons facturés. TC-265 reste également à faire.

### Effet de bord à connaître

Le resserrement de `DialogueValidator` s'applique **à tous** les dialogues, pas
seulement à ceux produits par l'IA. Un dialogue existant qui citerait un type
inconnu afficherait désormais une erreur dans l'éditeur du panel. Vérifié :
aucun des cinq dialogues embarqués du dépôt n'est dans ce cas, et un test le
confronte fichier par fichier à chaque exécution de la suite.

`dialogues/guard.yml` reste **non éditable depuis le panel**, pour une raison
qui lui préexiste : son `text: >` (scalaire replié) n'est pas géré par le
lecteur YAML du panel, qui abandonne alors la suite de la map — six de ses
treize nœuds ne sont pas vus. La troncature **n'est pas silencieuse** (le
garde-fou round-trip la signale et l'éditeur refuse d'écraser à l'aveugle), et
un test le vérifie désormais fichier par fichier. Ce fichier s'édite à la main.

### Rollback

`scripts/plugadmin/rollback.sh app` puis `systemctl restart plugadmin`. Un
rollback retire les deux familles de l'atelier et rend au validateur de
dialogues son silence sur le vocabulaire. Aucune donnée n'est concernée.

---

## 2026-10-08 (après-midi) - Atelier IA corrigé, PNJ ↔ dialogue cohérent, suppression de PNJ (#222 #223 #224 #225 #226)

### Changement

**Côté Control Panel uniquement (#222, #223, #224, et le volet atelier de
#225)**

- **#222 — « Demander une correction » relance vraiment.** Trois défauts
  cumulés : les diagnostics voyageaient en champs `problem` **homonymes**, or
  le lecteur de formulaire du panel ne garde qu'une valeur par nom — l'IA n'en
  recevait qu'un seul ; la correction repartait **sans les consignes
  d'origine**, donc le modèle réparait l'erreur signalée en perdant
  l'identifiant imposé ; et le formulaire se rouvrait **vide**, obligeant à
  tout retaper. Génération et correction passent désormais par un seul chemin
  de lecture du formulaire, et un échec d'appel pendant une correction conserve
  la proposition précédente avec son bouton.
- **#223 — l'identifiant est normalisé avant l'appel.** `clé` et
  `rpgquest:clé` sont tous deux acceptés, le namespace n'est jamais doublé, un
  namespace étranger ou un caractère interdit est refusé **sans dépenser de
  jeton**, et la règle de saisie est littéralement celle de l'import.
- **#224 — un nombre de nœuds non nul devient une contrainte.** Le prompt le
  déclare impératif, puis le panel **recompte la vraie map `nodes`** avec le
  lecteur réel et refuse l'écart (« 5 nœuds demandés, 4 générés »). Le refus
  est corrigeable via #222. Le nombre d'étapes d'une quête reste une
  *indication*, et le formulaire le dit désormais explicitement.
- **#225 (atelier)** — un PNJ déjà pourvu d'un dialogue ne peut plus en
  recevoir un second en silence. Le champ demande le **PNJ porteur**, non
  « l'identifiant du dialogue » : quand le dialogue lié ne porte pas son nom —
  le cas Mira — la génération **s'arrête avant tout appel payant** et demande
  s'il faut modifier l'existant ou créer un nouveau dialogue en remplaçant le
  lien.
- Correctif d'affichage au passage : `Ui.banner` retombait en silence sur
  « info » pour les noms longs `error` / `warning` / `success`. Un échec d'appel
  d'IA s'affichait donc en bandeau **neutre** — visible, mais pas comme un
  problème.

**Côté plugin — il y a donc un JAR à déployer (#225, #226)**

- **#225 (moteur)** — `NpcCatalog` ne lisait le rattachement PNJ → dialogue que
  par la **convention de nom**. Conséquence sur les données réelles du serveur :
  `mira_cartographer`, qui **déclare** `rpgquest:mira_first_map`, apparaissait
  *sans dialogue* alors que les joueurs l'entendaient ; et ce dialogue, que
  personne ne « réclamait », fabriquait une **seconde entrée** de catalogue —
  un « PNJ » `mira_first_map` sans définition, que rien ne permettait de
  corriger puisqu'il n'était la faute de personne. Le rattachement se lit
  désormais : dialogue **déclaré** d'abord, convention de nom ensuite. Un
  dialogue revendiqué par une définition n'est plus déduit en PNJ ; un dialogue
  sans porteur l'est toujours, avec une anomalie `DIALOGUE_WITHOUT_NPC` qui
  nomme sa cause et ses deux remèdes.
- **Les remises deviennent visibles.** Un PNJ destinataire d'un
  `DELIVER_ITEM_TO_NPC` n'apparaissait dans **aucune** colonne du catalogue : le
  supprimer rendait la quête infinissable sans qu'aucun écran ait pu
  l'annoncer. Le champ `questsDelivering` (source `QUEST_DELIVER`) traverse
  maintenant tout le relevé `npc.list`.
- **#226 — trois nouvelles actions agent whitelistées** :
  `npc.definition.delete` (la définition seule, **sauvegardée** côté serveur
  dans `npc-backups/`, refusée si une quête référence encore le PNJ),
  `npc.citizens.unlink` (retire la liaison, laisse vivre l'entité) et
  `npc.citizens.delete` (détruit l'entité et retire sa liaison). Les deux
  dernières exigent l'identifiant Citizens attendu, confronté à la liaison
  réelle avant d'agir ; la destruction passe par la **double clé UUID +
  identifiant numérique**. Les trois sont idempotentes.
- **#226 (panel)** — page `/npcs/delete` : aperçu des **cinq couches** d'un PNJ
  (définition, liaison, entité Citizens, dialogue, références de contenu), puis
  quatre opérations de la moins à la plus destructrice. Permission **dédiée**
  `NPC_DELETE`, et `NPC_SPAWN_WRITE` en plus pour détruire l'entité. Aucune
  opération ne supprime un dialogue.

### Action serveur

**Un JAR à remplacer**, cette fois — contrairement aux trois lots précédents,
ce lot touche `src/main/java/`.

- **Remplacer le JAR RPGQuest**, puis **redémarrer Minecraft** : le catalogue
  PNJ et les trois nouvelles actions agent vivent dans le plugin.
- **Redéployer le Control Panel** (`scripts/plugadmin/deploy.sh`).
- **Aucune migration de base**, aucun fichier de configuration à modifier,
  aucun monde touché.
- **Aucun fichier de contenu n'est créé ni modifié** par ce lot. Les
  définitions PNJ, dialogues, quêtes et stories du serveur sont inchangés.

> **Ce qu'il ne faut PAS altérer** : `plugins/RPGQuest/npcs/`,
> `plugins/RPGQuest/dialogues/`, `plugins/RPGQuest/quests/`,
> `plugins/RPGQuest/stories/`, `data.db`, et la configuration Citizens. Ce lot
> ne demande aucune modification de données.

### Sauvegarde préalable

- JAR RPGQuest actuellement déployé (sauvegarde datée, **ne jamais écraser la
  dernière**) ;
- `data.db` — par principe, bien qu'aucune migration n'ait lieu ;
- `plugins/RPGQuest/npcs/` — par principe : c'est le dossier que la nouvelle
  action de suppression sait désormais toucher, même si le déploiement
  lui-même n'y touche pas ;
- release précédente du panel (conservée automatiquement par le script).

### Déploiement

1. Build depuis un arbre **propre** sur le commit visé.
2. `scripts/deploy-verygames.sh` (avec `RPGQUEST_TEST_MAX_HEAP=768m` et `-y`) —
   transfert du JAR + backup daté.
3. Redémarrage Minecraft.
4. `scripts/plugadmin/deploy.sh` — Control Panel.
5. Vérifier `/health`, puis dans PlugAdmin :
   - **PNJ → Rafraîchir** : la fiche `mira_cartographer` doit afficher son
     dialogue `rpgquest:mira_first_map`, et l'entrée `mira_first_map` « sans
     définition » doit **avoir disparu** ;
   - une fiche PNJ doit montrer une **Zone de danger** avec « Supprimer… ».

### Validation

**Vérifié automatiquement** : suite complète (plugin + control-panel +
web-api), voir le rapport de session. Le cas Mira est reproduit à l'identique
depuis le relevé `npc.list` réel du 2026-10-08, côté moteur **et** côté panel.

**Non vérifié** : **TC-267** (`PENDING MANUAL VALIDATION`) — correction IA
réelle, identifiant local puis namespacé, nombre exact de nœuds, fiche Mira, et
cycle complet de création puis suppression d'un **PNJ de test**. Exige une
vraie clé API et un client Minecraft.

### Effet de bord à connaître

**Le catalogue PNJ va changer d'aspect après le redémarrage**, et c'est
l'objectif : toute entrée « sans définition » qui n'était en fait qu'un
dialogue déclaré par une fiche va **disparaître**. Chez nous, cela concerne
`mira_first_map`. Rien n'est supprimé sur le disque — c'est la *déduction* qui
était fausse, pas les fichiers. Un dialogue réellement sans porteur reste
affiché, avec sa nouvelle anomalie.

Le compteur de PNJ de la page d'accueil baissera d'autant. Ce n'est pas une
perte de données.

### Rollback

- Plugin : redéployer le JAR sauvegardé, puis redémarrer Minecraft. Le
  catalogue retrouve son ancienne déduction (et ses entrées fantômes), et les
  trois actions de suppression redeviennent inconnues de l'agent — le panel les
  verrait alors refusées, ce qui est le comportement attendu pour une action
  qu'un agent ne connaît pas.
- Panel : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`.
- **Aucune donnée n'est concernée** par un rollback : ce lot n'écrit rien.

### Déploiement RÉELLEMENT effectué — 2026-10-08 16:19 à 16:22 (CEST)

Serveur **RPGQuest DEV** (VeryGames), depuis un worktree propre sur `e262401`.

| Étape | Résultat |
|---|---|
| `scripts/deploy-verygames.sh -y` | `DEPLOY_EXIT=0` — JAR en ligne **1 993 126 o, identique au local** (SHA-256 `4abb4d91…`) |
| Backup de la version remplacée | `rpgquest-20261008T141922Z-predeploy.jar`, 1 985 814 o (SHA-256 `a335db8c…`) — **le backup précédent n'a pas été écrasé** |
| `scripts/verygames-restart.sh` | **un seul** redémarrage. 0 joueur connecté avant l'arrêt, donc personne déconnecté. `save-all` puis `stop` RCON, retour **ONLINE** confirmé |
| Contrôle RCON après redémarrage | `/plugins` → **RPGQuest en vert** (activé), Citizens en vert ; `version RPGQuest` → `0.1.0-SNAPSHOT` |
| `scripts/plugadmin/deploy.sh` | `PANEL_DEPLOY_EXIT=0`, release précédente conservée dans `/opt/plugadmin/releases/20261008-162153`, service **active (running)**, `/health` → `{"panel":"ONLINE"}` |

**Aucun fichier de contenu, aucune configuration et aucune base n'ont été
touchés** — conformément à la section « Ne PAS transférer/altérer ».

#### Ce qui N'A PAS pu être vérifié depuis la machine de build

L'effet visible du correctif #225 — l'entrée `mira_first_map` qui disparaît et
le dialogue de `mira_cartographer` qui s'affiche — exige un **rafraîchissement
du catalogue PNJ**, et ce rafraîchissement ne se déclenche que depuis
l'interface (`PNJ → Rafraîchir`) ou en jeu. Il n'existe aucune commande RCON
qui liste le catalogue. **Ce point reste donc à vérifier**, et c'est l'étape 23
de TC-267 : le plugin corrigé est bien chargé et actif, mais l'écran montre
encore le dernier relevé, antérieur au déploiement.

---

## 2026-10-08 (soir) - Emplacements de construction : marquer un point d'ancrage en jeu (#213)

### Changement

Première brique du futur système de bâtiments (#213, livraison « indépendante
de l'IA »). Un **emplacement** est un point d'ancrage nommé dans un monde. Il
ne contient **aucun** bâtiment, aucune dimension, aucune emprise et aucun
schematic : ce lot ne sait rien poser.

**Côté plugin**

- `/rpgadmin buildsite tool` donne un **outil d'administration dédié** (houe en
  fer, reconnue par **PDC uniquement**). Clic droit sur un bloc → emplacement
  créé sur la **case libre contre la face cliquée**, orienté selon le regard,
  avec un identifiant automatique `buildsite_0001` et le nom « Nouvel
  emplacement ». Aucune saisie dans le chat.
  `/rpgadmin buildsite list` liste les emplacements sans quitter le jeu.
- Ni hache en bois (wand WorldEdit par défaut, reconnue *par type d'objet* :
  les deux plugins se disputeraient le clic) ni tige de blaze (déjà l'outil de
  zone). **La wand WorldEdit n'est pas touchée.**
- Permission **dédiée** `rpgquest.admin.buildsite` (`default: false`), sans
  rapport avec WorldEdit. L'ombrelle historique `rpgquest.admin.world`
  l'implique, donc un administrateur existant ne perd rien.
- **Anti-doublon** : même bloc → on renvoie l'emplacement existant ; même geste
  → anti-rebond de 500 ms par joueur. Aucune règle de distance minimale.
- Cinq actions agent whitelistées : `building.site.list`, `.rename`,
  `.describe`, `.facing`, `.delete`. **Aucune action de création** : un
  emplacement est défini par une position désignée dans le monde.

**Côté Control Panel**

- Nouvelle page **Bâtiments → Emplacements** (`/buildings/sites`) : liste,
  recherche, filtre par monde, fiche, renommage, description, correction
  d'orientation, suppression du marqueur.
- Trois permissions : `BUILDING_READ` (Propriétaire, Administrateur, **Builder**,
  Testeur), `BUILDING_WRITE` et `BUILDING_DELETE` (Propriétaire, Administrateur).
- Pas de bouton « Créer », et la page l'explique.

### Action serveur

**Un JAR à remplacer**, puis **redémarrer Minecraft** — l'outil, les actions
agent et la migration vivent dans le plugin. Puis **redéployer le Control
Panel**.

> **MIGRATION DE BASE : OUI, et c'est la première de ce lot de la journée.**
> `data.db` passe en schéma **V28**. Elle est **purement additive** : deux
> tables neuves (`building_sites`, `building_site_ids`) et un index. **Aucune
> colonne n'est ajoutée à une table existante, aucune donnée existante n'est
> lue ni réécrite.** Elle s'applique automatiquement au démarrage du plugin.

> **Ce qu'il ne faut PAS altérer** : `plugins/RPGQuest/npcs/`, `dialogues/`,
> `quests/`, `stories/`, la configuration Citizens. Aucun fichier de contenu
> n'est créé ni modifié par ce lot.

### Sauvegarde préalable

- JAR RPGQuest actuellement déployé (sauvegarde datée, **ne jamais écraser la
  dernière**) ;
- **`data.db` — cette fois ce n'est pas une précaution de principe** : il y a
  une migration de schéma. La prendre avant le redémarrage.
- release précédente du panel (conservée automatiquement par le script).

### Déploiement

1. Build depuis un arbre **propre** sur le commit visé.
2. `scripts/deploy-verygames.sh` (avec `RPGQUEST_TEST_MAX_HEAP=768m` et `-y`).
3. Redémarrage Minecraft → la migration V28 s'applique, et le log affiche
   « N emplacement(s) de construction chargé(s). » (0 au premier démarrage).
4. `scripts/plugadmin/deploy.sh` — Control Panel.
5. Vérifier : `/health`, puis la navigation **Bâtiments → Emplacements** dans
   PlugAdmin, et `/rpgadmin buildsite tool` en jeu.

### Migration automatique

**Oui** — V28, au démarrage du plugin, par le `SchemaMigrationRunner` existant.
Rejouée sur une base déjà à jour : no-op.

### Validation

**Vérifié automatiquement** : suite complète (plugin + control-panel +
web-api), voir le rapport de session. La règle d'ancrage, la conversion
yaw → orientation, l'anti-doublon, la persistance après redémarrage simulé,
l'idempotence de la suppression et le non-recyclage des identifiants sont
couverts par des tests.

**Non vérifié** : **TC-268** (`PENDING MANUAL VALIDATION`) — environ 5 minutes,
avec un vrai client Minecraft.

### Effet de bord à connaître

**Aucun sur le contenu ou le gameplay existant.** Le lot n'ajoute qu'un outil
d'administration, une page de panel et deux tables. Un joueur ordinaire ne voit
rien changer, et l'outil ne fait rien entre ses mains (permission vérifiée
avant le clic).

Un **rollback du JAR laisse les deux tables en place**, simplement inutilisées :
elles ne gênent rien, et les emplacements déjà créés réapparaissent au
redéploiement. Le `SchemaMigrationRunner` ne redescend jamais une version.

### Rollback

- Plugin : redéployer le JAR sauvegardé, puis redémarrer Minecraft. L'outil et
  les cinq actions redeviennent inconnus de l'agent — le panel les verrait
  refusées, ce qui est le comportement attendu. **Les données restent** (voir
  ci-dessus).
- Panel : `scripts/plugadmin/rollback.sh app` puis
  `systemctl restart plugadmin`.

### Déploiement RÉELLEMENT effectué — 2026-10-08 19:05 à 19:21 (CEST)

Serveur **RPGQuest DEV**, depuis un worktree propre sur `6f58b78`.

| Étape | Résultat |
|---|---|
| **Sauvegarde `data.db` AVANT** | `data-20261008T170546Z-predeploy.db` (1 167 360 o), relue : **V27, 33 tables** — exploitable, pas juste copiée |
| JAR | `DEPLOY_EXIT=0`, en ligne **2 027 547 o == local** (SHA-256 `96fd7ac9…`) |
| Backup du JAR remplacé | `rpgquest-20261008T171841Z-predeploy.jar` (1 993 126 o) — le JAR du lot #222, **la dernière sauvegarde n'a pas été écrasée** |
| Redémarrage Minecraft | **un seul**, 0 joueur connecté |
| **Migration V28** | vérifiée sur la base réelle : `user_version = 28`, les deux tables et l'index présents, **35 tables** contre 33 avant |
| RCON | RPGQuest **en vert** ; `/rpgadmin buildsite list` reconnue |
| Control Panel | `PANEL_DEPLOY_EXIT=0`, `/health` → `ONLINE`, classes `panel/building/*` et fiche d'aide présentes dans le JAR servi, `/buildings/sites` → **303** (route enregistrée) |

#### Ce qui reste à constater

Le **parcours en jeu** : `/rpgadmin buildsite` réclame un joueur, et aucune commande RCON ne simule
un clic droit sur un bloc. Le plugin est chargé, la commande existe, le schéma est migré et le panel
sert le bon code — mais qu'un clic crée un emplacement à la bonne ancre reste à voir. C'est TC-268.
