---
title: Déployer, redémarrer, rollback sur VeryGames DEV
category: VeryGames
tags: [verygames, deploiement, deploy, restart, rollback, jar, auto-reboot, dev]
order: 1
---

# VeryGames DEV — déploiement / redémarrage / rollback

Le serveur Minecraft tourne chez **VeryGames** (hébergeur mutualisé). Depuis la machine de build
(AWS), on pousse le **JAR RPGQuest** en FTP puis on redémarre en RCON. Aucun autre fichier n'est
transféré par défaut (le script refuse `data.db`, `config.yml`, mondes, `Citizens/`…).

Référence : `docs/deployment/VERYGAMES.md`. Secrets : `~/.config/rpgquest/verygames.env`.

## Déployer le JAR

```
scripts/deploy-verygames.sh -y
```

- Vérifie le working tree Git, lance `./gradlew test` + `./gradlew build`, fait un **backup daté**
  du JAR en ligne, puis transfère le nouveau JAR de façon atomique.
- Options utiles : `--dry-run` (tout vérifier sans rien transférer), `--check` (vérifie juste la
  config + la connexion FTP), `--also LOCAL:REMOTE` (fichier supplémentaire sous `RPGQuest/`).
- Backup écrit dans `~/.local/share/rpgquest/verygames-backups/rpgquest-<UTC>-predeploy.jar`
  (+ `.meta` avec la taille et le SHA-256).

## Redémarrer

```
scripts/verygames-restart.sh
```

`save-all` → `stop` (RCON) → VeryGames **relance automatiquement** le processus (~15–60 s) → le
script attend le retour `ONLINE`.

Vérifs après redémarrage :

```
scripts/verygames-rcon.py "list"
scripts/verygames-rcon.py "plugins"          # RPGQuest + Citizens + Multiverse + WorldEdit verts
scripts/verygames-rcon.py "rpgquest version"
```

Et côté PlugAdmin : heartbeat agent `ONLINE`, aucun `ERROR` dans `journalctl -u plugadmin`.

## Protection anti-boucle de VeryGames — IMPORTANT

VeryGames arrête **volontairement** le service après **10 redémarrages automatiques en moins de
30 minutes** :

```
10 auto-reboot in less than 30 minutes ... ouch. We exit now.
You can start the service again using the panel.
```

> [!WARNING]
> Si ce message apparaît, **le serveur ne redémarrera plus tout seul**. Il faut le **relancer
> manuellement depuis le panel VeryGames**.

Règles de travail :

- **Un seul** `scripts/verygames-restart.sh` par déploiement. Ne jamais l'enchaîner en boucle ni
  en « retry jusqu'à ONLINE ».
- Après un restart, **vérifier** que le serveur est bien revenu (`verygames-rcon.py "list"` +
  heartbeat agent). VeryGames peut prendre quelques minutes — **attendre**, ne pas ré-émettre
  `stop`.
- Si la protection se déclenche : arrêter tout redémarrage automatique, le signaler
  explicitement, et **demander un démarrage manuel via le panel VeryGames**.
- Planifier un déploiement pour ne nécessiter **qu'un seul** restart : grouper tous les
  changements de fichiers (JAR + `--also` + éditions FTP) **avant** l'unique redémarrage.

## Rollback

```
scripts/rollback-verygames.sh --list             # liste les backups disponibles
scripts/rollback-verygames.sh --latest           # restaure le backup « predeploy » le plus récent
scripts/rollback-verygames.sh --backup <fichier> # restaure un backup précis
scripts/verygames-restart.sh                      # UN SEUL restart pour appliquer
```

Aucune migration à défaire (le déploiement JAR n'en fait aucune). Un dialogue / une définition
édités en réel se restaurent en re-poussant le `.yml` d'origine sous `RPGQuest/` puis un restart.
