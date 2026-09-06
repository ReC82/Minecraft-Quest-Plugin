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

## 2026-09-06 - Scripts de déploiement / rollback VeryGames (issue #10)

### Changement

Ajout de scripts versionnés pour automatiser l'*exécution* d'un déploiement
JAR vers VeryGames (le déclenchement reste manuel) :

- `scripts/deploy-verygames.sh` — vérifie le working tree Git, affiche
  branche+commit, `./gradlew test` puis `build`, contrôle la présence du
  JAR, télécharge la version en ligne dans un backup daté local, transfère
  le nouveau JAR de façon atomique (nom temporaire puis `RNFR`/`RNTO`).
  N'adresse **que** le chemin distant du JAR : `data.db`, `config.yml`,
  `messages.yml`, mondes et autres plugins ne sont jamais touchés.
- `scripts/rollback-verygames.sh` — restaure un backup daté (le plus récent
  ou un fichier précis), après avoir sauvegardé la version courante ; refuse
  d'agir sans backup valide.
- `scripts/lib/verygames-common.sh`, `scripts/verygames.env.example`.

Transfert via `curl` en FTP (port 21). AUTH TLS tentée par défaut
(`VERYGAMES_FTP_TLS=auto`).

### Action serveur

**Aucune action sur le serveur de production pour ce changement en
lui-même** : il n'ajoute que des fichiers dans le dépôt (scripts + doc) et
ne déploie rien. Aucun redémarrage, aucune migration, aucun nouveau JAR.

Action requise **côté poste de déploiement AWS** (une fois, hors serveur
de jeu) avant le premier usage des scripts :

```bash
mkdir -p ~/.config/rpgquest
chmod 700 ~/.config/rpgquest
cp scripts/verygames.env.example ~/.config/rpgquest/verygames.env
chmod 600 ~/.config/rpgquest/verygames.env
# renseigner VERYGAMES_FTP_HOST et VERYGAMES_FTP_PASS (panel VeryGames, compte awsplugin)
```

Le fichier `~/.config/rpgquest/verygames.env` **ne doit jamais être
committé** (il est hors dépôt ; `.gitignore` couvre aussi
`scripts/verygames.env` par sécurité).

### Sauvegarde préalable

Sans objet (aucune modification serveur). Les futurs déploiements via le
script créent eux-mêmes un backup daté du JAR en ligne dans
`~/.local/share/rpgquest/verygames-backups/` avant tout remplacement.

### Déploiement

Le prochain déploiement RPGQuest pourra utiliser
`scripts/deploy-verygames.sh` (voir
[VERYGAMES.md § Déploiement automatisé](VERYGAMES.md#déploiement-automatisé-scriptsdeploy-verygamessh)).
Aucun déploiement n'est effectué par cette entrée.

### Validation

Sur le poste AWS, sans toucher au serveur :

```bash
scripts/deploy-verygames.sh --dry-run          # git + test + build + plan FTP, aucune connexion
scripts/deploy-verygames.sh --check            # valide la config, teste la connexion si identifiants présents
scripts/rollback-verygames.sh --list           # liste des backups (vide au départ)
```

### Rollback

Retirer les scripts (`git revert` du commit) suffit — rien n'a été déployé.
Le fichier d'identifiants local peut être conservé ou supprimé
(`rm ~/.config/rpgquest/verygames.env`).

---

## 2026-09-06 - Audit TLS FTPS VeryGames + support chaîne CA (issue #10)

### Changement

Suite au test réseau concluant (`curl -v ftp://si-16041.dg.vg:21/` → bannière
ProFTPD), audit du comportement TLS des scripts de l'issue #10 avec les vrais
identifiants, en **listing non destructif uniquement** (aucun transfert).

Constats :

- **FTP en clair : refusé.** `USER` → `530` avant même `PASS`. VeryGames
  impose **AUTH TLS explicite** (FTPS) sur le port 21.
- **FTPS : accepté.** Handshake TLS 1.3 OK, SAN `*.dg.vg` correspond à
  `si-16041.dg.vg`.
- **Chaîne de certificat incomplète (côté serveur).** ProFTPD ne renvoie que
  le certificat feuille ; les intermédiaires Let's Encrypt (`YE1`, puis
  `Root YE` cross-signé par `ISRG Root X2` déjà de confiance) ne sont pas
  envoyés → `curl`/`openssl` : *« unable to get local issuer certificate »*,
  bien que le certificat soit valide. **Résolu sans `--insecure`** : nouveau
  `scripts/verygames-fetch-ca.sh` reconstruit les intermédiaires depuis les
  URL AIA de Let's Encrypt, vérifie la chaîne complète contre le magasin
  système, écrit un PEM public ; nouvelle variable `VERYGAMES_FTP_CA_EXTRA`
  fusionnée au magasin système par les scripts.
- **Login FTP : le nom nu `awsplugin` est refusé (`530`).** Le serveur
  multi-clients attend `<slot>.<sous-compte>` — ici `si-16041.awsplugin`
  (déterminé par une commande `USER` seule, sans mot de passe). Avec ce
  login, le serveur répond `331 Password required` ; le mot de passe
  actuellement dans le fichier local n'est pas (encore) le bon → à récupérer
  dans le panel VeryGames.

Modifs scripts : défaut `VERYGAMES_FTP_TLS=require` (au lieu de `auto`) ;
`auto` = alias de `require` (plus jamais l'option opportuniste `--ssl`) ;
diagnostics curl parlants (`60` = chaîne CA, `67` = login refusé + format du
login attendu, etc.) ; `--check` liste le dossier distant et signale la
présence/absence du JAR.

### Action serveur

**Aucune action sur le serveur de production.** Audit en lecture seule, aucun
fichier transféré, aucun redémarrage.

Actions requises **côté poste AWS** avant le premier déploiement réel :

```bash
scripts/verygames-fetch-ca.sh          # génère ~/.config/rpgquest/verygames-ca.pem
# éditer ~/.config/rpgquest/verygames.env :
#   VERYGAMES_FTP_USER=si-16041.awsplugin        (login EXACT du panel, préfixé)
#   VERYGAMES_FTP_TLS=require
#   VERYGAMES_FTP_CA_EXTRA=~/.config/rpgquest/verygames-ca.pem
#   VERYGAMES_FTP_PASS=<mot de passe EXACT du sous-compte, depuis le panel>
scripts/deploy-verygames.sh --check
```

### Sauvegarde préalable

Sans objet (aucune modification serveur).

### Déploiement

Aucun. Le déploiement JAR reste soumis à autorisation explicite.

### Validation

`scripts/deploy-verygames.sh --check` doit afficher « Connexion + TLS OK »
puis le contenu de `plugins/` et la présence/absence du JAR RPGQuest.

### Rollback

`git revert` du commit. Le fichier `~/.config/rpgquest/verygames-ca.pem` peut
être supprimé (`rm`), il ne contient que des certificats publics.

### Remarque VeryGames

La cause racine du problème TLS est une **chaîne de certificat incomplète
servie par ProFTPD côté VeryGames**. Le correctif propre à terme est côté
hébergeur (inclure les intermédiaires dans `TLSCertificateChainFile`). En
attendant, `VERYGAMES_FTP_CA_EXTRA` traite le problème côté client sans
jamais désactiver la vérification.
