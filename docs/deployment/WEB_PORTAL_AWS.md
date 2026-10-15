# Portail public LodyQuests — hébergement AWS (issue #52)

Runbook du portail public **https://lodyquests.lodylands.com**. Même méthode que PlugAdmin
(service systemd dédié, configuration et secrets hors Git, `/health`, sauvegarde et rollback,
validation CI #257), mais **strictement séparé** de lui.

## Ce qui tourne

| Élément | Valeur |
| --- | --- |
| Code | module `web-api/` (`./gradlew :web-api:installDist`) |
| Service | `lodyquests-portal.service` — utilisateur système `lodyquests-web` (pas `plugadmin`) |
| Écoute | `127.0.0.1:8092` uniquement ; le public passe par nginx |
| Application | `/opt/lodyquests-portal/app` (+ fichier `DEPLOYED` : commit, branche, date, validation) |
| Versions précédentes | `/opt/lodyquests-portal/releases/<AAAAMMJJ-HHMMSS>` (5 gardées) |
| Données | `/var/lib/lodyquests-portal` (seul dossier inscriptible) |
| Configuration | `/etc/lodyquests-portal/web-api.properties` (non secrète) |
| Secrets | `/etc/lodyquests-portal/portal.env` (root:lodyquests-web, 640) — **vide** aujourd'hui (futur : `RPGQUEST_PORTAL_LINK_TOKEN`, #53) |
| Comptes joueurs (#53) | `accounts-enabled=false` : pas de `accounts.db`. Activés : `/var/lib/lodyquests-portal/accounts.db`, copiée à chaque déploiement (service arrêté) dans `/opt/lodyquests-portal/backups/accounts-<AAAAMMJJ-HHMMSS>.db` (10 gardées) |
| nginx | `/etc/nginx/sites-available/lodyquests-portal` (+ lien dans `sites-enabled/`) |
| Journaux | `journalctl -u lodyquests-portal` ; nginx : `/var/log/nginx/lodyquests_portal_*.log` |
| Mémoire | tas JVM 96 Mo, `MemoryMax=256M` (≈ 30 Mo au repos), `CPUQuota=50%` |

Les fichiers versionnés correspondants sont dans `scripts/web-portal/`.

### Séparation d'avec PlugAdmin

Autre utilisateur, autre service, autre port, autre vhost, autres secrets. Le portail n'a **aucune
action d'administration**. Ses comptes joueurs (#53, désactivés aujourd'hui) vivent dans sa propre
base, avec son propre cookie, et ne donnent aucun droit ni sur le jeu ni sur PlugAdmin.
L'API `/api/*` (jeton `RPGQUEST_WEB_API_TOKEN`) est fermée deux fois : aucun jeton n'est configuré
(401) et nginx répond 404 sur `/api/` côté public. Seule exception, `location = /api/link/confirm`
(POST, jeton dédié `RPGQUEST_PORTAL_LINK_TOKEN`, 401 sans lui, 503 tant que les comptes sont
fermés) : le serveur Minecraft y envoie la preuve d'un `/lien <code>`.

### Confidentialité (#55)

`player-data-public=false` : `/joueurs` et `/leaderboards` affichent « pas encore publiée » sans
lire l'instantané. `web-export` est désactivé côté serveur Minecraft et aucun canal n'achemine
l'instantané jusqu'à AWS (#421) : `/health` dit donc `"snapshot":"MISSING"` et le site affiche son
mode dégradé — **c'est l'état attendu**. Passer `player-data-public` à `true` n'est permis que sur
décision écrite du propriétaire, après #55.

`store-enabled=false` : la boutique historique (mission étape 22) n'est pas servie, aucun
`store.db` n'est ouvert. Aucun paiement en V1.

## Installer (une fois) ou réparer

```bash
./gradlew :web-api:installDist
sudo scripts/web-portal/install.sh            # --dry-run pour voir sans rien faire
```

Idempotent. Ne touche à aucun autre site ni service ; `nginx -t` avant tout `reload`, jamais de
`restart` nginx ; n'écrase jamais `portal.env`, `web-api.properties` ni un vhost déjà modifié par
certbot. Si `nginx -t` échoue, le lien du portail est retiré et nginx n'est pas rechargé.

## Certificat TLS

Uniquement pour ce domaine, et seulement quand le DNS public le résout vers l'instance :

```bash
dig +short lodyquests.lodylands.com @8.8.8.8      # doit répondre 3.226.216.90
sudo certbot --nginx -d lodyquests.lodylands.com --non-interactive --agree-tos --key-type ecdsa --redirect
```

Ne jamais contourner TLS. Let's Encrypt limite à 5 validations ratées par heure : en cas de
`NXDOMAIN`, attendre la fin du cache négatif (TTL du SOA one.com : 10 minutes) avant **un** nouvel
essai, jamais en boucle. Le vhost d'avant certbot est sauvegardé dans
`/opt/lodyquests-portal/releases/nginx-lodyquests-portal.pre-certbot.bak`.

## Mettre à jour le code

```bash
scripts/web-portal/deploy.sh                 # jamais sous sudo (gh doit rester authentifié)
```

Refuse un code `web-api/` non commité ou une CI rouge ; sans CI, lance `:web-api:test` en local
(ou réutilise le marqueur de `scripts/dev/check.sh`). Sauvegarde l'app courante, installe, arrête
le service, copie `accounts.db` s'il existe, redémarre, sonde `/health` ; **si `/health` échoue, remet automatiquement la version précédente**.

## Revenir en arrière

```bash
scripts/web-portal/rollback.sh app        # version précédente de l'app
scripts/web-portal/rollback.sh nginx-off  # retire le site de nginx, garde le service
scripts/web-portal/rollback.sh disable    # arrête et désactive le service
scripts/web-portal/rollback.sh full       # les deux : état d'avant #52 ; rien n'est supprimé
```

## Vérifier

Depuis l'instance (même sans DNS ni TLS) :

```bash
curl -s http://127.0.0.1:8092/health
# {"status":"UP","snapshot":"MISSING","playerDataPublic":false,"store":false}
curl -s -o /dev/null -w '%{http_code}\n' --resolve lodyquests.lodylands.com:80:127.0.0.1 http://lodyquests.lodylands.com/
systemctl show lodyquests-portal -p MemoryCurrent -p ActiveState
```

### Publier une actualité (#56)

```bash
sudo cp ma-nouvelle.md /var/lib/lodyquests-portal/news/        # format : WEB_PORTAL.md §8
sudo nano /var/lib/lodyquests-portal/news/bienvenue.md         # « status: draft » -> « published »
curl -s http://127.0.0.1:8092/health                           # "news": nombre d'actualités visibles
journalctl -u lodyquests-portal --since -5min | grep 'Actualité ignorée'   # fichier refusé ?
```

Visible en 30 s au plus, sans redémarrage. Retirer : repasser `status: draft` ou supprimer le
fichier. Adresse et version du serveur sur `/infos` : clés `server-address` et `minecraft-version`
de `/etc/lodyquests-portal/web-api.properties`, puis `sudo systemctl restart lodyquests-portal`.

Depuis un navigateur (`PENDING MANUAL VALIDATION` tant que le propriétaire ne l'a pas fait) :

1. `https://lodyquests.lodylands.com/` : cadenas valide, page d'accueil LodyQuests, bandeau
   « Serveur hors-ligne — données indisponibles » (attendu : aucun instantané) ;
2. menu Statut, Classements, Joueurs, Wiki, Confidentialité : chaque page s'ouvre ; Classements
   et Joueurs disent « pas encore publiée » ; **aucun pseudo nulle part** ;
3. `https://lodyquests.lodylands.com/store` et `/api/status` : « introuvable » (404) ;
4. sur téléphone : le menu passe à la ligne, aucun défilement horizontal ;
5. `https://plugadmin.lodylands.com` fonctionne toujours, avec sa propre connexion.

V1 publique (#56) — `PENDING MANUAL VALIDATION` :

6. `/` : présentation, carte « Le serveur » (« Hors ligne » tant que #421 n'achemine pas
   l'instantané), « Saison et événements », « Dernières actualités », « Comment rejoindre » ;
7. `/actualites` : « Aucune actualité publiée » ; après avoir publié `bienvenue.md` (ci-dessus),
   elle apparaît sur l'accueil et `/actualites/bienvenue` s'ouvre ; repassée en brouillon → 404 ;
8. `/classements`, `/joueurs` : « pas encore publiée », aucun pseudo ; `/infos` : adresse
   « communiquée dès l'ouverture » tant que `server-address` est vide ;
9. sur téléphone : menu sur deux lignes, cartes empilées, aucun défilement horizontal ;
10. `/robots.txt` et `/sitemap.xml` répondent ; aucun `/joueurs/…` dans le sitemap.

### Comptes et liaison Minecraft (#53) — `PENDING MANUAL VALIDATION`

Aujourd'hui (comptes désactivés) : `https://lodyquests.lodylands.com/compte` affiche « pas encore
ouverts », sans formulaire ; en jeu, `/lien ABCD2345` répond « La liaison avec le portail n'est pas
activée sur ce serveur. » ; `curl -s -o /dev/null -w '%{http_code}\n' -X POST
https://lodyquests.lodylands.com/api/link/confirm` → `401`.

Après activation (WEB_PORTAL.md §2, « Mise en route ») :

1. `/compte` → créer un compte (pays configuré, âge au-dessus du seuil) → message « Compte créé » ;
   se connecter → « Connecté en tant que … », « Aucun compte Minecraft relié » ;
2. « Obtenir un code de liaison » → la page affiche `/lien XXXXXXXX` et l'heure limite ;
3. en jeu : `/lien XXXXXXXX` → « C'est fait : ton compte Minecraft est relié… » ; recharger
   `/compte` → « Relié à <pseudo> depuis le … » et « liaison avec <pseudo> » dans l'historique ;
4. retaper le même code (ou depuis un autre joueur) → « Code invalide ou expiré » ; un code
   attendu plus de 10 minutes → même message ;
5. un second compte web qui tente de lier le même joueur → « Ton compte Minecraft est déjà relié… » ;
6. « Délier » avec un mauvais mot de passe → refus ; avec le bon → « délié », ligne
   « dissociation de <pseudo> » ;
7. « Se déconnecter », puis bouton Précédent du navigateur → plus connecté ;
8. `journalctl -u lodyquests-portal` et `latest.log` : aucun code ni jeton.

Côté Minecraft, hors comptes : rien à vérifier pour ce lot — le plugin n'est pas modifié et `web-export` reste
désactivé. La première vérification en jeu viendra avec #421 (`/health` passant à `FRESH`).
