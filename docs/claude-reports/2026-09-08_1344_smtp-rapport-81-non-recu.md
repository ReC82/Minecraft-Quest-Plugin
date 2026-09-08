# RPGQuest — Rapport Claude

## Informations
* Date : 2026-09-08
* Heure : 13:44 (locale, UTC sur cette machine)
* Sujet : Diagnostic + renvoi de l'email de fin de tâche #81 phase 1 non reçu ; durcissement de la procédure SMTP générique
* Statut : DONE
* Branche Git : `feat/control-panel-admin-tools`
* Commit actuel si disponible : `8dce999` (avant ce rapport)
* Début de la tâche : 2026-09-08 13:38:00 (heure locale réelle)
* Fin de la tâche : 2026-09-08 13:47:00
* Durée totale : 00:09:00

## Demande

L'email de fin de tâche du lot #81 phase 1 (rapport
`docs/claude-reports/2026-09-08_1325_npc-citizens-link-81.md`) n'est **pas
arrivé** chez l'owner, alors que les emails des tâches précédentes arrivaient
correctement.

Contraintes : ne toucher à **aucun code applicatif**, ne rien commiter de
fonctionnel, ne **rien redéployer** (ni AWS ni VeryGames), pas de merge.
Vérifier la procédure SMTP de fin de tâche, renvoyer l'email au destinataire
habituel avec le rapport Markdown joint, vérifier le retour SMTP réel / exit
code / logs, et confirmer explicitement destinataire, sujet, pièce jointe et
résultat SMTP. Si nécessaire, corriger uniquement le script / la procédure
SMTP **générique** pour éviter la récidive.

## Analyse

### Chaîne SMTP

- Config : `~/.config/plugadmin/smtp.env` (`0600`, hors dépôt) —
  `PLUGADMIN_SMTP_HOST=send.one.com`, `PORT=465` (SMTPS implicite),
  `FROM=plugadmin@lodywood.be`, `USERNAME=plugadmin@lodywood.be`,
  `TO=lloyd.helpdesk@gmail.com`.
- Script générique du dépôt : `scripts/plugadmin/send-mail.py` (introduit en
  `642b203`, issue #51) — **sans support de pièce jointe**.
- Les emails de fin de tâche des sessions précédentes étaient envoyés par des
  scripts **ad-hoc** recréés à chaque fois dans le scratchpad
  (`send_v2.py`, `send_npc_report.py`, …), qui ajoutent l'attachement mais
  **n'ajoutent ni `Date` ni `Message-ID`**. Le script utilisé pour #81
  (`send_81.py`) suit le même patron.

### Test de renvoi avec transcript SMTP complet

Renvoi effectué le 2026-09-08 13:41:41 UTC (script de diagnostic
`scratchpad/resend_81.py`, `smtplib` avec `set_debuglevel(2)`) :

```
ehlo            -> 250 mailrelay6.pub.mailoutpod2-cph3.one.com (… SIZE 104857600 …)
AUTH PLAIN      -> 235 2.7.0 Ok
MAIL FROM:<plugadmin@lodywood.be> size=39329 -> 250 2.1.0 Ok
RCPT TO:<lloyd.helpdesk@gmail.com>           -> 250 2.1.5 Ok
DATA            -> 354
(fin des données)-> 250 2.0.0 Ok: queued as 0552c5e2-ab8b-11f1-8ba2-fd5ae24138eb
QUIT            -> 221
```

`send_message()` : **aucun destinataire refusé** (`{}`). Exit **0**.

**Conclusion** : le chemin expéditeur (cette machine → relais `send.one.com`)
fonctionne parfaitement. `send.one.com` **accepte et met en file** le message
(id de file `0552c5e2-ab8b-11f1-8ba2-fd5ae24138eb`). Le premier envoi
(`send_81.py`, ~13:29) a lui aussi forcément reçu un « queued as » (le script
n'imprime « sent » qu'après un `send_message()` sans exception).

La non-livraison s'est donc produite **en aval** du relais one.com
(one.com → Gmail, ou filtrage Gmail), **hors de portée de diagnostic depuis ce
serveur** : pas de MTA local, pas de `/var/log/mail*`, rien de pertinent dans
`journalctl`.

### Cause la plus probable et actionnable

Le message partait **sans en-tête `Date` et sans `Message-ID`**
(`smtplib.send_message()` n'ajoute ni l'un ni l'autre ; les scripts ad-hoc non
plus). Un message sans `Message-ID` — et sans `Date` conforme — est
couramment **rejeté silencieusement ou classé indésirable** par Gmail. Les
envois précédents passaient sur la seule réputation de l'expéditeur : c'est
fragile, et cette fois c'est passé au travers. C'est le seul facteur qu'on
peut corriger de façon déterministe côté expéditeur.

Facteurs aggravants secondaires possibles (non prouvables d'ici) : sujet de
`send_81.py` contenant les caractères `<->` littéraux ; corps riche en tableau
Markdown / mots « FAILED », « SQL », « console ».

## Travail effectué

1. **Renvoi immédiat** de l'email de fin de tâche #81 phase 1, rapport joint —
   **deux** envois, tous deux acceptés par `send.one.com` :
   - via `scratchpad/resend_81.py` (en-têtes `Date` + `Message-ID` ajoutés,
     sujet nettoyé) → **queued as `0552c5e2-ab8b-11f1-8ba2-fd5ae24138eb`**,
     0 refusé, exit 0.
   - via la procédure générique corrigée `scripts/plugadmin/send-mail.py`
     `--attach …` → `Message-ID <178887505720.197768.7344099773233345583@lodywood.be>`,
     0 refusé, session SMTP OK (code 250), exit 0.
2. **Durcissement de `scripts/plugadmin/send-mail.py`** (outillage, aucun code
   applicatif) :
   - ajoute **toujours** les en-têtes `Date` (`formatdate(localtime=True)`) et
     `Message-ID` (`make_msgid` sur le domaine du `From`) ;
   - nouvelle option **`--attach PATH` répétable** ; type MIME deviné par
     extension (`.md` → `text/markdown`, `.txt`/`.log` → `text/plain`,
     `.json` → `application/json`, sinon `application/octet-stream`) — un
     rapport voyage donc en texte lisible, pas en octet-stream opaque ;
   - échoue (exit 3) si `send_message()` renvoie des destinataires refusés ;
   - imprime en cas de succès : destinataire, sujet, `Message-ID`, pièces
     jointes (nom, type, taille), ensemble des refusés (`{}`), état de la
     session SMTP + dernier code serveur.
   - Rétro-compatible : `--subject` / `--body-file` / `--to` / stdin
     inchangés ; aucun appelant automatisé (aucune unité systemd / cron ne
     référence ce script).
3. Compilation (`python3 -m py_compile`) et test à blanc de la détection MIME :
   OK.

**Recommandation de procédure** : les prochains emails de fin de tâche doivent
passer par `scripts/plugadmin/send-mail.py --attach <rapport>` (chemin unique,
audité, en-têtes corrects) plutôt que par un script ad-hoc recréé à chaque
session.

## Fichiers créés

- `docs/claude-reports/2026-09-08_1344_smtp-rapport-81-non-recu.md` (ce rapport)
- `/tmp/.../scratchpad/resend_81.py`, `resend`/`body_81_canonical.txt` (hors dépôt, non commités)

## Fichiers modifiés

- `scripts/plugadmin/send-mail.py` — en-têtes `Date` + `Message-ID`
  systématiques, support `--attach` répétable, sortie de contrôle enrichie,
  exit 3 sur destinataire refusé. **Outillage uniquement**, aucun code
  `com.lodygames.*` touché.

## Base de données / migrations

Aucune.

## Configuration / données

Aucun changement. `~/.config/plugadmin/smtp.env` lu, non modifié, jamais
affiché en clair.

## Tests automatiques

- `python3 -m py_compile scripts/plugadmin/send-mail.py` → OK.
- `send-mail.py --help` → les 4 options attendues.
- Détection MIME testée à blanc (`.md`/`.txt`/`.json`/`.bin`).
- Deux envois réels vers `lloyd.helpdesk@gmail.com` acceptés par
  `send.one.com` (voir « Logs / diagnostic »).
- Suites Gradle : **non exécutées** — aucune modification de code applicatif,
  aucun test Java concerné (demande explicite : ne pas toucher au code, ne pas
  redéployer).

## Tests manuels à effectuer

- **L'owner confirme la réception** d'au moins un des deux emails renvoyés
  (sujets « … (#81 phase 1) — renvoi » et « … rapport #81 phase 1 (renvoi via
  procedure SMTP corrigee) »), rapport `2026-09-08_1325_npc-citizens-link-81.md`
  joint. S'ils sont en indésirables : marquer « non spam » pour la réputation
  future de `plugadmin@lodywood.be`.
- Si toujours rien : demander à one.com le journal de remise sortant pour la
  file `0552c5e2-ab8b-11f1-8ba2-fd5ae24138eb` (seul point aveugle restant).

## Résultat attendu

- L'email de fin de tâche #81 phase 1, avec le rapport joint, arrive chez le
  destinataire habituel `lloyd.helpdesk@gmail.com`.
- Les futurs envois de rapport, passés par `send-mail.py --attach`, portent
  `Date` + `Message-ID` et ne sont plus silencieusement filtrés.

## Reset / retour à l'état initial

`git checkout scripts/plugadmin/send-mail.py` restaure la version `642b203`
(sans `--attach`, sans `Date`/`Message-ID`). Aucun autre effet à annuler.

## Déploiement VeryGames

### À transférer
Rien. Aucun déploiement dans cette tâche (demande explicite).

### Ne PAS transférer/altérer
Tout : plugin, Control Panel, `data.db`, config serveur, `smtp.env`.

### Redémarrage requis
Non.

### Migration automatique
Aucune.

## Rollback

`git revert` / `git checkout` du seul fichier `scripts/plugadmin/send-mail.py`.
Aucun artefact déployé, aucun service à redémarrer.

## Logs / diagnostic

Renvoi nº1 — `scratchpad/resend_81.py`, 2026-09-08 13:41:41–13:41:43 UTC :

```
AUTH PLAIN                                   -> 235 2.7.0 Ok
MAIL FROM:<plugadmin@lodywood.be> size=39329 -> 250 2.1.0 Ok
RCPT TO:<lloyd.helpdesk@gmail.com>           -> 250 2.1.5 Ok
DATA … .                                     -> 250 2.0.0 Ok: queued as
                                                0552c5e2-ab8b-11f1-8ba2-fd5ae24138eb
send_message() destinataires refusés         -> {}   (aucun)
EXIT=0
```

Renvoi nº2 — `scripts/plugadmin/send-mail.py --attach …`, ~13:44 UTC :

```
send-mail: envoyé à lloyd.helpdesk@gmail.com
  sujet       : RPGQuest — rapport #81 phase 1 (renvoi via procedure SMTP corrigee)
  message-id  : <178887505720.197768.7344099773233345583@lodywood.be>
  pièces      : 2026-09-08_1325_npc-citizens-link-81.md (text/markdown, 25654 o)
  refusés     : {} (aucun)
  session SMTP : OK (relais send.one.com, dernier code 250)
EXIT=0
```

Aucun MTA local, pas de `/var/log/mail*`, rien de pertinent dans `journalctl`
pour la remise en aval : la visibilité s'arrête au relais one.com.

## Documentation mise à jour

- Ce rapport + ligne d'index dans `docs/claude-reports/README.md`.
- Docstring de `scripts/plugadmin/send-mail.py` mis à jour (nouvelle option,
  rappel de la cause `Date`/`Message-ID`).

## Limitations / travail restant

- **Non-livraison du premier email non prouvée dans le détail** : sans accès
  au journal sortant de one.com ni à la boîte Gmail, la cause exacte
  (filtrage Gmail vs rejet one.com→Gmail) reste une inférence ; le correctif
  `Date`/`Message-ID` traite le facteur déterministe le plus probable.
- Pas de DKIM/SPF vérifiés ici (gérés côté one.com pour `lodywood.be`).
- Idéalement : convertir les futurs envois de fin de tâche pour qu'ils
  utilisent tous `send-mail.py --attach` (documenté dans `CLAUDE.md` /
  `.ai/` — non fait ici pour rester dans le périmètre « ne touche à rien
  d'autre »).

## Prochaine étape suggérée

1. L'owner confirme la réception d'un des deux renvois (et « non spam » si en
   indésirables).
2. Adopter `scripts/plugadmin/send-mail.py --attach <rapport>` comme **unique**
   voie d'envoi des rapports de fin de tâche.
