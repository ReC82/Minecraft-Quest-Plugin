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
