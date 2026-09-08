---
title: Déployer / rollback le Control Panel (AWS)
category: Déploiement / Rollback
tags: [aws, plugadmin, control-panel, deploy, rollback, deploiement, health, nginx]
order: 1
---

# Control Panel (PlugAdmin) — déploiement AWS

PlugAdmin tourne sur la **machine AWS** (celle du build) : service systemd `plugadmin`, backend
`127.0.0.1:8090`, public via nginx + Let's Encrypt sur `https://plugadmin.lodylands.com`.
Base propre `control-panel.db` (SQLite), **totalement séparée** de `data.db`.

Référence : `docs/control-panel/DEPLOYMENT_AWS.md`. `sudo` sans mot de passe est disponible.

## Déployer

```
scripts/plugadmin/deploy.sh
```

- `./gradlew :control-panel:installDist` → sauvegarde `/opt/plugadmin/app` sous
  `/opt/plugadmin/releases/<horodatage>` → déploie la nouvelle distribution → `systemctl restart
  plugadmin` → vérifie `/health`.
- `scripts/plugadmin/deploy.sh --no-build` : déploie la distribution déjà bâtie.
- Rétention : 5 releases max.

## Vérifier

```
curl -s https://plugadmin.lodylands.com/health          # {"panel":"ONLINE",...}
curl -s -o /dev/null -w '%{http_code}\n' https://plugadmin.lodylands.com/dashboard   # 303 (redirige vers /login)
systemctl status plugadmin --no-pager
journalctl -u plugadmin -n 50
```

- `/health` doit répondre `"panel":"ONLINE"`.
- Une route protégée en **anonyme** doit renvoyer **303** vers `/login` (jamais 200).
- Les autres vhosts nginx (`dig.lodygames.com`, `lodylands.com`) doivent rester **200**.

## Rollback

```
scripts/plugadmin/rollback.sh app         # restaure la release précédente + restart
scripts/plugadmin/rollback.sh disable     # arrête + désactive le service (fichiers en place)
scripts/plugadmin/rollback.sh nginx-off   # retire le vhost plugadmin de nginx (reload)
scripts/plugadmin/rollback.sh full        # disable service + retire le vhost nginx
```

`rollback.sh app` sélectionne la dernière release `YYYYMMDD-HHMMSS` (hors dossiers
`rolledback-*`) et relance le service. Aucune migration à défaire — `control-panel.db` est
compatible ascendant sur cette ligne de versions.

> [!NOTE]
> Le déploiement AWS **ne touche jamais** le serveur Minecraft VeryGames. Un changement
> uniquement Control Panel (pages, docs, catalogue d'actions) ne nécessite **aucun** redémarrage
> Minecraft. Ne redéployer / redémarrer VeryGames que si le **JAR RPGQuest ou l'agent** change.
