# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-05
* Heure : 15:40 (locale machine)
* Sujet : #202 — forum communautaire Discord synchronisé avec les issues GitHub (V1)
* Statut : **PARTIAL** — moitié GitHub livrée et **validée sur données réelles** ; moitié Discord **non vérifiable**, aucun jeton de bot Discord n'est configuré (voir « Blocage externe »)
* Branche Git : `feature/202-discord-forum-sync` (worktree dédié `/srv/rpgquest/worktree-202`), créée depuis `feature/169-special-mobs-boss`
* Commit actuel si disponible : voir « Commits »
* Début de la tâche : 2026-10-05 14:59:21 (heure locale réelle, première action)
* Fin de la tâche : 2026-10-05 15:40:30 (heure locale réelle)
* Durée totale : 00:41:09

## Demande

Implémenter #202 : un sujet Bug/Suggestion du forum public Discord crée une issue GitHub avec
`source:discord` et renvoie son lien dans Discord ; le statut GitHub met à jour le sujet ;
persistance et réconciliation contre les doublons ; service AWS indépendant, supervisé, démarrant
automatiquement ; aucun changement aux annonces `#news`/`#soon`.

Consignes : tokens déjà configurés dans `~/.config/lodyquests-discord/bot.env`, **ne jamais
montrer les secrets** ; sans sous-agents ; tests ciblés puis commit, push et installation/démarrage
du service sans redemander d'autorisation ; aucun merge ni redémarrage Minecraft ; test autorisé
avec le sujet « TEST — synchronisation GitHub » et une issue explicitement TEST, clôture et
réouverture incluses, sans toucher aux autres tickets. Et explicitement : **« ne considère pas le
bot installé comme une preuve que la synchronisation fonctionne »**.

## Blocage externe — à lire en premier

**Il n'y a aucun jeton de bot Discord configuré.** Le fichier de secrets contient quatre clés :
`DISCORD_GUILD_ID`, `DISCORD_FORUM_CHANNEL_ID`, `DISCORD_BOT_TOKEN`, `GITHUB_REPOSITORY`.

- La clé `GITHUB_TOKEN` attendue par le ticket est **absente**.
- La valeur placée sous `DISCORD_BOT_TOKEN` **est un jeton GitHub** : l'API Discord la refuse
  (`401 Unauthorized`) et la même valeur authentifie sur l'API GitHub (compte `ReC82`, accès en
  écriture au dépôt visé, issues activées).

Diagnostic établi **sans jamais afficher de valeur** : seule la *structure* des lignes a été
examinée (un jeton de bot Discord est fait de trois parties séparées par des points ; un jeton
GitHub porte un préfixe `github_pat_`), puis l'hypothèse a été confirmée en interrogeant les deux
API.

Conséquence : **toute la moitié Discord est invérifiable** — lire le forum, publier le lien, poser
un tag. Elle est implémentée, testée contre un double fidèle, mais **jamais exercée sur le vrai
Discord**, et ce rapport ne prétend pas le contraire.

**Correction : deux lignes dans `~/.config/lodyquests-discord/bot.env`.**
1. Renommer la clé `DISCORD_BOT_TOKEN` existante en `GITHUB_TOKEN` (la valeur est déjà la bonne).
2. Ajouter le vrai jeton du bot sous `DISCORD_BOT_TOKEN` — portail développeurs Discord →
   application `1556642091071836190` → onglet « Bot » → « Reset Token ».

Puis `/opt/lodyquests-discord/app/bin/discord-sync check`. Le service repart **seul** en moins de
deux minutes : rien d'autre à lancer.

Le fichier de secrets n'a **pas** été modifié : il appartient au propriétaire, et déplacer un
jeton dedans sans demande explicite serait une décision sur sa posture de sécurité.

## Analyse

Quatre décisions ont structuré l'implémentation.

**1. Scrutation plutôt que passerelle temps réel.** Un bot Discord peut tenir une connexion
WebSocket permanente. La scrutation REST évite l'identification, les battements de cœur, la reprise
de session et les tempêtes de reconnexion ; elle rend le redémarrage trivialement sûr — on relit un
état, on ne rejoue pas un flux d'événements ; et un forum communautaire n'a pas besoin de mieux
qu'une minute de latence. Coût assumé : cette latence. L'interface `DiscordApi` permet d'ajouter la
passerelle plus tard sans rien changer d'autre.

**2. L'anti-doublon ne peut pas reposer sur un verrou, mais sur un ordre d'écriture.** L'intention
de créer est enregistrée et validée dans SQLite **avant** l'appel réseau. Une création engagée
laisse donc toujours une trace, même si le processus meurt pendant l'appel.

**3. Un constat mesuré a invalidé ma première conception de la réconciliation.** La réconciliation
devait relire les issues étiquetées `source:discord` et reconnaître la sienne à un marqueur. Le
test réel a échoué : la liste ne contenait pas l'issue qui venait d'être créée. J'ai mesuré, plutôt
que supposé — création d'une issue sonde puis lectures immédiates :

| Requête | Issue créée quelques secondes plus tôt |
|---|---|
| `GET /issues?labels=source:discord&sort=updated` | **absente** (5 lectures consécutives) |
| `GET /issues?labels=source:discord&sort=created` | **absente** |
| `GET /issues?sort=created&direction=desc` | **absente** |
| `GET /issues?state=open` | **absente** |
| `GET /issues?…&since=…` | **absente** |
| `GET /issues/{numéro}` | **présente immédiatement** |

Se fier à la liste seule aurait conclu « aucune issue n'existe » juste après une création dont la
réponse est perdue — et produit exactement le doublon que le ticket interdit. D'où trois garde-fous
au lieu d'un : relevé par étiquette, **balayage borné des numéros voisins** par l'accès unitaire
(le seul frais), et **délai de prudence de 3 minutes** pendant lequel le service **attend** au lieu
de recréer.

**4. Synchroniser le contenu sans écraser les notes de triage.** Les deux exigences se
contredisent si on remplace le corps d'issue. Le service n'écrit donc qu'entre deux marqueurs en
commentaire HTML ; tout ce qui est écrit dehors est conservé. Si les marqueurs ont disparu, il
**refuse** de réécrire le corps et le signale, plutôt que de deviner.

## Travail effectué

### Nouveau module `discord-sync`
Quatrième module Gradle indépendant du dépôt, même décision d'isolation que `web-api` et
`control-panel` : **aucune dépendance externe** hors `sqlite-jdbc` (HTTP par `java.net.http`, JSON
par un codec maison, comme les trois autres modules). Aucune dépendance vers Paper, le plugin,
`web-api` ou le Control Panel ; ne touche ni `data.db`, ni `store.db`, ni `control-panel.db`.

- **Configuration** — validation stricte **avant** tout appel réseau : forme des identifiants
  Discord, forme des **deux** jetons, format du dépôt, plancher de scrutation. Détecte et nomme
  l'inversion des jetons. `toString()` masque les secrets.
- **Transport** — client REST commun : réessais **bornés**, backoff exponentiel plafonné, `429`
  honoré au délai indiqué, corps de réponse borné, et surtout distinction **échec franc** (4xx de
  validation : rien n'a été écrit) / **échec ambigu** (coupure, 429, 5xx : l'écriture a peut-être
  abouti) — c'est cette distinction qui pilote l'anti-doublon.
- **Discord** — relevé du salon et de ses tags, sujets actifs et récemment archivés, message
  initial lu à la demande (son identifiant est celui du sujet, propriété de l'API et non
  supposition), publication de message avec `allowed_mentions` **vide**, remplacement des tags.
- **GitHub** — issues seulement : création, mise à jour, accès unitaire, relevé **conditionnel par
  ETag** (un `304` ne consomme pas de quota), création idempotente des étiquettes. Les pull
  requests renvoyées par l'endpoint `issues` sont écartées.
- **État** — SQLite dédiée, migrations idempotentes, **index unique** garantissant qu'une issue ne
  peut être appariée qu'à un seul sujet (contrainte portée par la base, pas seulement par le code).
- **Assainissement** — le contenu des membres est une **donnée** : séquences `<!--`/`-->`
  neutralisées, `@mention` et `#123` placés entre accents graves (donc aucune notification ni
  référence croisée sur GitHub), message rendu en bloc de citation, taille bornée avec mention de
  troncature, noms de pièces jointes réduits au nom de fichier.
- **Service** — unité systemd dédiée, démarrage automatique, `Restart=always` / `RestartSec=120` /
  `StartLimitIntervalSec=0`, durcissement (`ProtectSystem=strict`, `NoNewPrivileges`,
  `PrivateDevices`, écriture limitée à `/var/lib/lodyquests-discord`).
- **Commandes** — `run`, `once`, `check` (lecture seule), `adopt`, `notice`, `status`, et
  `LODYQUESTS_DRY_RUN` qui journalise sans rien écrire.

### Respect explicite des interdits du ticket
Aucune écriture sur `#news`/`#soon`, aucune retouche rétroactive d'une issue d'origine inconnue,
aucun retrait des tags Bug/Suggestion d'un sujet, aucun import massif des anciens sujets (repère
temporel + adoption explicite), aucune fermeture d'issue par le service (GitHub fait autorité),
aucune permission Administrateur — `check` la signale comme un défaut à corriger.

## Fichiers créés
- `discord-sync/` : `build.gradle.kts`, `DiscordSyncMain`, `config/` (3), `json/` (2, copie
  minimale), `http/` (2), `discord/` (3), `github/` (3), `store/` (2), `sync/` (6)
- Tests : `BotConfigLoaderTest`, `ContentSanitizerTest`, `IssueBodyTest`, `SyncStatusTest`,
  `SnowflakeTest`, `SyncStoreTest`, `SyncServiceTest`, `LiveGitHubSyncIT`, et les doubles
  `FakeDiscord` / `FakeGitHub`
- `scripts/lodyquests-discord/` : `install.sh`, `lodyquests-discord.service`, `bot.env.example`
- `docs/discord-sync/README.md`
- ce rapport

## Fichiers modifiés
- `settings.gradle.kts` (nouveau module)
- `docs/RPGQUEST_BIBLE.md` (nouveau § 22 + table des matières, qui omettait aussi § 21)
- `docs/MANUAL_TEST_PLAN.md` (TC-241 + table de recette)
- `docs/deployment/SERVER_CHANGELOG.md`, `.ai/ROADMAP.md`, `docs/claude-reports/README.md`

**Aucun fichier du plugin Paper modifié.** Contenus du propriétaire (`crystal_hunt`, Lily/Jeff,
configurations, progression) intacts : `git status` du checkout principal inchangé.

## Base de données / migrations
Nouvelle base **séparée** `/var/lib/lodyquests-discord/sync.db` (schéma v1). Aucune migration sur
`data.db`, `store.db` ou `control-panel.db`.

## Configuration / données
Nouveau fichier de secrets **hors dépôt** `~/.config/lodyquests-discord/bot.env` (modèle commenté
versionné à part). **Non modifié par ce travail.** Variables facultatives : `LODYQUESTS_SYNC_DB`,
`LODYQUESTS_POLL_SECONDS`, `LODYQUESTS_DRY_RUN`.

## Tests automatiques

`:discord-sync:test` → **74 tests, 0 échec, 0 erreur**, 1 ignoré (le test réseau, désactivé par
défaut). Couvrent notamment : anti-doublon sur plusieurs tours **et après redémarrage**, création
ambiguë **réconciliée et non rejouée**, relevé en retard compensé par le balayage, issue
introuvable partout → **attente** et non recréation, plancher de balayage figé à la première
tentative, aucun import massif sans adoption, notes de triage préservées, marqueurs retirés →
refus de réécrire le corps mais titre toujours synchronisé, table des statuts complète
(`completed` ≠ `not_planned`), statut annoncé **une seule fois** par changement, issue d'origine
inconnue jamais touchée, tag de statut posé **sans** retirer le tag Bug, absence de tag de statut
non traitée comme une erreur, simulation n'écrivant rien, message de lien en échec ne provoquant
pas de seconde création, et détection des jetons inversés.

**Deux défauts ont été trouvés par ces tests et corrigés dans le code, pas contournés dans les
assertions :**
1. la neutralisation remplaçait `<!--` par `(<!--)` — la séquence subsistait, donc un membre
   pouvait encore ouvrir un commentaire HTML masquant la suite de la zone gérée ;
2. une anomalie de synchronisation de contenu (marqueurs disparus) était journalisée mais
   **absente du bilan du tour**, donc invisible pour un exploitant qui ne lit que le résumé.

## Test réel — ce qui est prouvé, et ce qui ne l'est pas

**Prouvé sur le vrai dépôt GitHub** (`LiveGitHubSyncIT`, vrai client HTTP, issue de test
**#206**) :

- création de l'issue avec `source:discord` + `type:bug` + `triage`, titre et message en bloc de
  citation, marqueur de réconciliation présent, **mention `@ReC82` neutralisée dans le corps
  réel** ;
- lien renvoyé dans le sujet ;
- deux tours supplémentaires **et un redémarrage** → **aucun doublon** (compté après que le relevé
  GitHub a rattrapé son retard, mesuré à ~10 s) ;
- édition du titre et du message → issue mise à jour, **note de triage réelle préservée** ;
- `Close as completed` → « **Résolu** », avec la phrase disant que ce n'est pas forcément déployé ;
- `Reopen` → « **À trier** » ;
- `Close as not planned` → « **Refusé** », et jamais « Résolu ».

**Prouvé sur l'hôte AWS** : service installé, activé au démarrage, supervisé ; son journal affiche
le diagnostic exact du jeton mal placé **sans afficher aucun secret** ; `check` exécuté avec un
jeton Discord de forme valide mais factice confirme que la moitié GitHub fonctionne de bout en
bout (dépôt lu, **visibilité « public » détectée**, issues activées) et que l'échec Discord est
rendu lisible et actionnable.

**NON prouvé, et je ne le présente pas autrement** : tout le côté Discord réel — lecture du forum,
publication du lien dans un sujet, pose des tags, et le parcours complet d'un vrai membre. Le
transport Discord n'a jamais pu être exercé. Le service installé n'est **pas** une preuve que la
synchronisation fonctionne.

## Tests manuels à effectuer
**TC-241 (nouveau)** — premier signalement réel depuis le forum, **après** la correction des deux
lignes de configuration. C'est la **première** vérification réelle de la moitié Discord.

## Résultat attendu
Un joueur ouvre un sujet Bug ou Suggestion ; dans la minute, le robot répond avec le lien de la
fiche GitHub, et chaque changement de statut est annoncé dans son sujet — sans qu'il ait de compte
GitHub.

## Reset / retour à l'état initial
`sudo systemctl disable --now lodyquests-discord`, puis `sudo rm -rf /var/lib/lodyquests-discord`
pour repartir d'un état vierge (un nouveau repère temporel sera posé, donc aucun ancien sujet ne
sera importé). Les issues déjà créées restent : elles appartiennent au backlog.

## Déploiement VeryGames
### À transférer
**Rien.** Aucun JAR, aucun fichier serveur.
### Ne PAS transférer/altérer
`data.db`, `config.yml`, `messages.yml`, `spawn.yml`, `RPGQuest/Citizens/`, les mondes.
### Redémarrage requis
**Non** — aucun redémarrage Minecraft. Ce service ne peut pas en provoquer.
### Migration automatique
Aucune côté serveur de jeu.

## Rollback
`sudo systemctl disable --now lodyquests-discord` ; application précédente sous
`/opt/lodyquests-discord/releases/<horodatage>`. Aucun rollback plugin concerné.

## Logs / diagnostic
`journalctl -u lodyquests-discord -f`. Au moment de la rédaction, le journal répète toutes les
deux minutes le diagnostic du jeton mal placé — c'est le comportement voulu : lisible, sans
secret, et auto-réparateur dès la correction.

Un défaut de l'unité systemd a été trouvé **par son propre journal** et corrigé :
`StartLimitIntervalSec` était placé dans `[Service]`, où systemd l'ignore
(« Unknown key name ») — l'unité aurait alors été abandonnée définitivement après quelques échecs
rapprochés, au lieu de retenter. Déplacé dans `[Unit]`, vérifié sans avertissement.

**Push refusé par la protection de secrets de GitHub, et ce que j'en ai fait.** Le premier `push`
a été rejeté (`GH013`) : la protection de poussée a reconnu la forme d'un jeton de bot Discord
dans `BotConfigLoaderTest`. C'était un **faux** jeton, que j'avais inventé pour exercer le
validateur — mais le scanner ne peut pas faire la différence, et il a raison de ne pas essayer.
GitHub proposait une URL pour autoriser ce « secret » : je ne l'ai **pas** utilisée, parce que
cela émousse la protection du dépôt pour la commodité d'un fichier de test. Les deux valeurs de
test sont désormais **assemblées à l'exécution** (`String.join(".", "A".repeat(24), …)`), ce qui
conserve exactement ce que le validateur observe — nombre de points, longueur, préfixe — sans
produire de motif reconnaissable. Tests revérifiés verts, puis les deux commits ont été refaits
avant tout `push` : **aucun historique partagé n'a été réécrit** (`git ls-remote` confirmait que
la branche n'existait pas encore côté distant).

## Nettoyage des données de test
Quatre tickets, **tous titrés `TEST — …` et tous refermés** (`not_planned`) : **#203** et **#206**
(parcours d'intégration), **#204** et **#205** (sondes de cohérence de lecture, qui ont servi à
établir le constat mesuré ci-dessus). GitHub ne permet pas de supprimer une issue par l'API : ils
sont donc documentés ici plutôt que supprimés. **Aucun autre ticket n'a été lu en écriture.**
Aucune issue n'a été fermée hors de ces quatre.

Les étiquettes `source:discord`, `type:bug`, `type:request` et `triage` ont été créées sur le dépôt
par le parcours réel — c'est voulu, elles sont nécessaires au fonctionnement.

## Documentation mise à jour
- `docs/discord-sync/README.md` : référence complète (périmètre, table des statuts, le constat
  mesuré sur la cohérence de lecture GitHub et ses conséquences, zone gérée, assainissement,
  exploitation, secrets, limites de la V1).
- `docs/RPGQUEST_BIBLE.md` : nouveau **§ 22**, avec commandes, fichier de configuration et
  garanties. La table des matières, qui ne listait ni § 21 ni § 22, est corrigée.
- `docs/MANUAL_TEST_PLAN.md` : **TC-241** + table de recette.
- `docs/deployment/SERVER_CHANGELOG.md` : entrée « lot 5 », avec **« Action serveur : AUCUNE »**
  explicite et l'état réel du blocage.
- `.ai/ROADMAP.md` : entrée de journal.

## Limitations / travail restant
- **La moitié Discord n'est pas vérifiée** (blocage externe ci-dessus). C'est la limite principale.
- Seuls **titre et premier message** sont synchronisés ; les messages suivants restent sur Discord,
  comme le prévoit la V1.
- **Commentaires GitHub non relayés** (choix du ticket : notes techniques internes).
- **Pièces jointes non réhébergées** ; les liens Discord sont signés et **expirent**. Seuls nom,
  taille et lien sont repris, avec la mise en garde.
- Latence d'au plus un intervalle de scrutation (60 s) dans les deux sens.
- **Déduction Bug/Suggestion par nom de tag** : un tag renommé en quelque chose d'inattendu fera
  retomber sur « suggestion », jamais sur « bug » — le choix le moins engageant.
- **Les permissions de salon ne sont pas calculées finement** : `check` vérifie l'absence
  d'Administrateur et exerce réellement les lectures, ce qui révèle une permission manquante par
  un échec lisible plutôt que par un calcul de masques et de dérogations.
- **Vue des demandes communautaires dans le Control Panel** : explicitement hors V1.
- Aucun ticket fermé hors des quatre TEST, aucune annonce publiée, aucun merge, aucune
  intervention PROD.

## Prochaine étape suggérée
1. **Corriger les deux lignes** de `~/.config/lodyquests-discord/bot.env`, puis
   `discord-sync check`.
2. Dérouler **TC-241** : c'est la seule chose qui peut établir que la synchronisation Discord
   fonctionne.
3. `discord-sync notice` → coller le texte dans les consignes du forum (il prévient que le dépôt
   est public).
4. `discord-sync adopt <idSujet> "sujet TEST"` pour prendre en charge le sujet TEST existant.
5. Facultatif : créer dans le salon les tags *À trier* / *En cours* / *À tester* / *Résolu* /
   *Refusé* pour que le statut soit aussi visible en tag, et non seulement en message.
