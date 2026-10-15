# RPGQuest — Rapport Claude

## Informations
* Date : 2026-10-15
* Heure : 15:00
* Sujet : Issue #52 — socle d'hébergement AWS du portail public LodyQuests, cadrage et roadmap à jour
* Statut : DONE (ticket laissé ouvert : validation navigateur et décisions #55 du propriétaire)
* Branche Git : `feature/52-web-portal-hosting` (PR #423 vers `chore/257-ci-github-actions`)
* Commit actuel si disponible : voir la PR (code `30f6bfd`, docs ensuite)
* Début de la tâche : 2026-10-15 14:42:02
* Fin de la tâche : 2026-10-15 15:09:59
* Durée totale : 00:27:57

## Demande
Session autonome lqorch sur #52 : poser le socle d'hébergement du portail public sur l'instance AWS
(service, nginx, health, déploiement, rollback, docs), en réutilisant le portail existant (`web-api`,
PR #312), avec la même méthode que PlugAdmin et strictement séparé de lui ; tenir le cadrage et la
roadmap à jour ; créer les tickets enfants manquants. Contraintes : serveur partagé (site nginx dédié
seulement, `nginx -t` avant reload, jamais de restart), 3,8 Go de RAM (plafond mémoire), aucun pseudo
publié avant #55, certificat seulement une fois le DNS effectif, jamais de contournement TLS.

## Analyse
- `web-api/` existait (site public, API à jeton, boutique sandbox de l'étape 22) mais : écoute sur
  toutes les interfaces, pas de `/health`, boutique toujours active, pages `/joueurs` et
  `/leaderboards` publiant des pseudos dès qu'un instantané est présent, et limite de fréquence par
  IP qui, derrière nginx, deviendrait un seul compteur partagé (toutes les requêtes viennent de
  127.0.0.1).
- DNS : le résolveur local disait NXDOMAIN, mais les serveurs faisant autorité (one.com) et les
  résolveurs publics répondaient déjà `3.226.216.90` (cette instance) : l'enregistrement A a été
  créé par le propriétaire entre-temps.
- Ports occupés : 8080, 8090 (PlugAdmin), 8091, 8100… ; 8092 libre.
- Aucun canal n'achemine l'instantané du serveur Minecraft (VeryGames) vers AWS : manque identifié,
  ticket #421 créé.

## Travail effectué
1. **web-api** (commits `1fe5d36`, `bf24182`) :
   - `GET /health` non authentifié : `{"status":"UP","snapshot":"MISSING|STALE|FRESH","playerDataPublic":…,"store":…}`, aucune donnée joueur ;
   - nouvelles clés, toutes à défaut sûr : `bind-address` (127.0.0.1), `store-enabled` (false : ni
     routes boutique ni `store.db`), `player-data-public` (false : `/joueurs` et `/leaderboards`
     affichent « pas encore publiée » sans lire l'instantané), `trust-proxy-headers` (false ; lit
     `X-Real-IP` seulement pour une connexion venue de la boucle locale, IP littérale validée) ;
   - booléen invalide → arrêt au chargement avec message clair ;
   - requêtes `HEAD` : en-têtes sans corps (supprime un avertissement JDK à chaque sonde) ;
   - constructeur historique de `WebApiConfig` conservé (tests existants inchangés).
2. **scripts/web-portal/** (commit `30f6bfd`) : `paths.sh` (emplacements uniques),
   `lodyquests-portal.service` (utilisateur `lodyquests-web`, tas 96 Mo, `MemoryHigh=200M`,
   `MemoryMax=256M`, `CPUQuota=50%`, `StartLimitBurst`, durcissement systemd),
   `nginx-lodyquests-portal.conf` (`/api/` → 404, CSP et en-têtes de sécurité, `limit_req`),
   `install.sh` idempotent, `deploy.sh` (validation #257 comme PlugAdmin, `DEPLOYED`, retour
   automatique si `/health` échoue), `rollback.sh` (`app`, `nginx-off`, `disable`, `full`, rien de
   supprimé), `web-api.properties.example`, `portal.env.example` (aucun secret requis).
3. **Installation réelle sur AWS** : `sudo scripts/web-portal/install.sh` → service actif, `/health`
   UP, vhost installé, `nginx -t` OK, reload (pas de restart).
4. **Déploiement et rollback éprouvés** : `deploy.sh` (tests web-api locaux, CI absente), puis
   `rollback.sh app` (version précédente remise, health OK), puis `deploy.sh --no-build` (retour au
   commit courant, marqueur de validation réutilisé).
5. **TLS** : premier `certbot --nginx` à 14:46 → échec NXDOMAIN côté validateurs secondaires (cache
   négatif, TTL SOA 600 s) ; certbot a tout annulé, vhost vérifié identique, `nginx -t` OK. Un seul
   nouvel essai à 14:58 → certificat ECDSA émis (échéance 13/01/2027), redirection 80 → 443.
6. **Tickets enfants créés** : #421 (acheminer l'instantané VeryGames → AWS) et #422
   (entitlements, dernier ticket enfant initial de l'EPIC jamais créé).
7. **Docs** : runbook, roadmap réordonnée par dépendances, clés de configuration, bible §1,
   changelog serveur, état actuel.

## Fichiers créés
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/api/HealthHandler.java`
- `web-api/src/main/java/com/lodygames/rpgquest/webapi/site/UnpublishedPageHandler.java`
- `web-api/src/test/java/com/lodygames/rpgquest/webapi/PublicHostingTest.java`
- `scripts/web-portal/{paths.sh,install.sh,deploy.sh,rollback.sh,lodyquests-portal.service,nginx-lodyquests-portal.conf,web-api.properties.example,portal.env.example}`
- `docs/deployment/WEB_PORTAL_AWS.md`
- ce rapport

## Fichiers modifiés
- `web-api/.../WebApiConfig.java`, `WebApiConfigLoader.java`, `WebApiMain.java`, `HttpServerBootstrap.java`, `http/RequestPipeline.java`, `http/HttpResponses.java`
- `docs/WEB_PORTAL.md`, `docs/WEB_API.md`, `docs/RPGQUEST_BIBLE.md`, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`, `docs/claude-reports/README.md`

## Base de données / migrations
Aucune. Aucun accès à `data.db`, aucune écriture SQL, aucune bascule MariaDB. Avec
`store-enabled=false`, le portail n'ouvre même pas `store.db`.

## Configuration / données
Sur AWS, hors Git : `/etc/lodyquests-portal/web-api.properties` (copie de l'exemple :
`player-data-public=false`, `store-enabled=false`, `bind-address=127.0.0.1`, port 8092,
`trust-proxy-headers=true`) et `/etc/lodyquests-portal/portal.env` (aucun secret ; pas de jeton
d'API → `/api/*` en 401, et 404 côté nginx). Serveur Minecraft : rien (`web-export` reste désactivé).

## Tests automatiques
- `./gradlew :web-api:test` : 42 tests, 0 échec (`HttpServerBootstrapTest` 17, `StoreHttpTest` 11,
  `JsonTest` 7, `PublicHostingTest` 7 nouveaux : défauts sûrs, booléen invalide refusé, `/health`
  sans pseudo, aucune page publique ne contient le pseudo d'un instantané présent, routes boutique
  absentes, limite de fréquence par visiteur derrière proxy + repli sur en-tête invalide, `HEAD`).
- `shellcheck` sur les scripts (seuls restent les avertissements déjà acceptés côté PlugAdmin :
  `eval` du `--dry-run`, `ls | grep`).
- CI GitHub de la PR #423 (run 38738834910, commit f02a296) : verte — plugin, control-panel, web-api, scripts, artefacts.

## Tests manuels à effectuer
`PENDING MANUAL VALIDATION` (navigateur, propriétaire) — détail dans
`docs/deployment/WEB_PORTAL_AWS.md` § Vérifier : cadenas valide sur https://lodyquests.lodylands.com,
pages Accueil/Statut/Classements/Joueurs/Wiki/Confidentialité, « pas encore publiée » sur
Classements et Joueurs, aucun pseudo, `/store` et `/api/status` introuvables, affichage téléphone,
PlugAdmin inchangé. Minecraft : rien à tester pour ce lot (plugin non modifié).

## Résultat attendu
`curl -s https://lodyquests.lodylands.com/health` → `{"status":"UP","snapshot":"MISSING","playerDataPublic":false,"store":false}`
(MISSING est normal tant que #421 et la réactivation de `web-export` ne sont pas faits).

## Reset / retour à l'état initial
`scripts/web-portal/rollback.sh full` : service arrêté et désactivé, vhost retiré (reload après
`nginx -t`), fichiers conservés. Certificat : `sudo certbot delete --cert-name lodyquests.lodylands.com`.

## Déploiement VeryGames
### À transférer
Rien : ce lot ne concerne que l'instance AWS.
### Ne PAS transférer/altérer
Le JAR, `config.yml` (`web-export.enabled` reste `false`), `data.db`.
### Redémarrage requis
Non.
### Migration automatique
Aucune.

## Rollback
Voir ci-dessus ; `rollback.sh app` remet la version précédente de l'app
(`/opt/lodyquests-portal/releases/`), testé pendant la session.

## Logs / diagnostic
`journalctl -u lodyquests-portal` (méthode, chemin, IP, statut, durée — jamais de jeton) ;
`/var/log/nginx/lodyquests_portal_{access,error}.log` ; `systemctl show lodyquests-portal -p MemoryCurrent`
(≈ 29 Mo au repos mesurés).

## Documentation mise à jour
`docs/deployment/WEB_PORTAL_AWS.md` (nouveau), `docs/WEB_PORTAL.md` (cadrage + roadmap), `docs/WEB_API.md`,
`docs/RPGQUEST_BIBLE.md` §1, `docs/current_state.md`, `docs/deployment/SERVER_CHANGELOG.md`.
Pas de page docs-site : la section 1 de la bible n'en a pas (cf. §19).

## Limitations / travail restant
- Le portail n'a aucune donnée tant que #421 n'est pas fait ;
- publication des pseudos : décision du propriétaire (#55), puis `player-data-public=true` ;
- les codes HTTP des autres sites ont été relevés après l'installation (200/303/401 cohérents avec
  leur fonctionnement), pas avant : la non-régression repose sur le fait qu'aucun de leurs fichiers
  n'a été modifié et sur `nginx -t` ;
- la PR #423 doit être fusionnée (CI verte) pour que la branche d'intégration porte ce code.

## Prochaine étape suggérée
Validation navigateur par le propriétaire, décision #55 (visibilité par défaut, mineurs), puis #421.
