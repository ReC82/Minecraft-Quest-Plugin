#!/usr/bin/env python3
"""LodyQuests — superviseur de sessions Claude Code (développement autonome entre sessions).

Une session Claude n'est qu'un ouvrier : elle traite UN ticket puis se termine. Le superviseur,
lui, survit aux sessions. Il garde l'état dans une base SQLite, choisit le travail suivant,
lance `claude -p` en mode non interactif avec un contexte reconstruit, surveille son activité,
classe la façon dont elle s'est terminée, et enchaîne.

Commandes :
  lqorch status            état du superviseur, session en cours, plafonds locaux, quota observé
  lqorch config [set NOM VALEUR | unset NOM]   garde-fous (sessions/jour, budget, seuils de quota…)
  lqorch reload            relire le code installé, entre deux sessions (sans couper la session)
  lqorch queue             prochains tickets
  lqorch pause | resume    ne plus démarrer / reprendre
  lqorch stop [--now]      arrêter (après la session en cours, ou tout de suite)
  lqorch skip N [raison]   mettre le ticket N de côté (BLOCKED) et passer au suivant
  lqorch add N [--prio P] [--deps 35,321] [--note "…"]   ajouter / remettre en file
  lqorch set N champ valeur                               corriger un champ d'un ticket
  lqorch log [N]           derniers événements
  lqorch auto [hold N [raison] | release N]   file automatique agent:auto (#836) : examen à blanc,
                           retenir un ticket (jamais sélectionné seul) ou le libérer
  lqorch sessions          dernières sessions
  lqorch run               boucle du superviseur (service systemd)

Aucune dépendance hors bibliothèque standard. Aucun secret n'est lu ni écrit ici : Claude Code
et `gh` utilisent leurs propres identifiants.
"""

import argparse
import fcntl
import hashlib
import json
import os
import queue
import re
import threading
import signal
import sqlite3
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

STATE_DIR = Path(os.environ.get("LQ_STATE_DIR", Path.home() / ".local/state/lodyquests-orch"))
DB_PATH = STATE_DIR / "state.db"
LOG_DIR = STATE_DIR / "sessions"
LOCK_PATH = STATE_DIR / "supervisor.lock"
REPO = os.environ.get("LQ_REPO", "ReC82/Minecraft-Quest-Plugin")
# Les sessions démarrent dans le dépôt principal (CLAUDE.md et mémoire du projet y sont rattachés)
# mais font TOUT leur travail Git dans une copie dédiée, jamais dans l'arbre du propriétaire.
LAUNCH_DIR = Path(os.environ.get("LQ_LAUNCH_DIR", "/srv/rpgquest/repo"))
WORKDIR = Path(os.environ.get("LQ_WORKDIR", "/srv/rpgquest/wt-orch"))
CLAUDE = os.environ.get("LQ_CLAUDE", str(Path.home() / ".local/bin/claude"))
PROMPT_FILE = Path(os.environ.get("LQ_PROMPT", Path(__file__).with_name("session_prompt.md")))

GH = os.environ.get("LQ_GH", "gh")

POLL_SECONDS = int(os.environ.get("LQ_POLL", 30))
TRIAGE_EVERY_SECONDS = int(os.environ.get("LQ_TRIAGE_EVERY", 6 * 3600))
BACKOFF_SCALE = float(os.environ.get("LQ_BACKOFF_SCALE", 1))
QUOTA_MARGIN_SECONDS = int(os.environ.get("LQ_QUOTA_MARGIN", 120))

# Garde-fous réglables sans toucher au code. Priorité : `lqorch config set` (base d'état) >
# variable d'environnement > défaut ci-dessous. Relus à chaque tour de boucle : un changement
# s'applique à la frontière de session suivante, jamais à une session déjà lancée.
# nom : (variable d'environnement, défaut, minimum, maximum, description)
SETTINGS = {
    "max_sessions_per_day": ("LQ_MAX_SESSIONS_PER_DAY", 24, 0, 500,
                             "Sessions lancées au plus par jour UTC. 0 = sans plafond, accepté seulement si un "
                             "garde-fou de budget ou de quota fournisseur est actif."),
    "max_cost_usd_per_day": ("LQ_MAX_COST_USD_PER_DAY", 0.0, 0.0, 10000.0,
                             "Plafond du coût estimé du jour (total_cost_usd de Claude Code, prix catalogue : "
                             "une estimation, pas une facture). 0 = désactivé."),
    "max_five_hour_utilization": ("LQ_MAX_FIVE_HOUR_UTILIZATION", 0.9, 0.05, 1.0,
                                  "Aucune nouvelle session si la fenêtre fournisseur de 5 h annoncée par Claude Code "
                                  "atteint ce taux (1 = désactivé)."),
    "max_seven_day_utilization": ("LQ_MAX_SEVEN_DAY_UTILIZATION", 0.8, 0.05, 1.0,
                                  "Idem pour la fenêtre de 7 jours ; laisse une marge pour l'usage interactif."),
    "min_seconds_between_sessions": ("LQ_MIN_GAP", 60, 30, 3600,
                                     "Écart minimal entre deux démarrages de session (anti-boucle)."),
    "max_consecutive_failures": ("LQ_MAX_CONSECUTIVE_FAILURES", 4, 1, 50,
                                 "Disjoncteur : après N sessions consécutives sans rapport, le superviseur se met "
                                 "en pause (lqorch resume pour réarmer)."),
    "max_attempts": ("LQ_MAX_ATTEMPTS", 3, 1, 10, "Sessions sans rapport tolérées par ticket avant FAILED."),
    "session_max_seconds": ("LQ_SESSION_MAX", 4 * 3600, 600, 12 * 3600, "Durée maximale d'une session."),
    "session_idle_seconds": ("LQ_SESSION_IDLE", 75 * 60, 300, 4 * 3600,
                             "Session coupée après ce délai sans aucune sortie."),
    "auto_select": ("LQ_AUTO_SELECT", 1, 0, 1,
                    "File vide : choisir seul le prochain ticket ouvert labellisé agent:auto, par priority:P0→P3 "
                    "(#836). 0 = désactivé."),
    "auto_comment": ("LQ_AUTO_COMMENT", 1, 0, 1,
                     "Signaler par un commentaire GitHub chaque prise en charge automatique. 0 = journal local seul."),
}

STATUSES = ("READY", "RUNNING", "TESTING", "BLOCKED", "WAITING_OWNER", "DONE", "FAILED")
# Pseudo-ticket : une session qui relit le backlog et remplit la file quand elle est vide.
TRIAGE = 0


def now():
    return datetime.now(timezone.utc)


def iso(dt=None):
    return (dt or now()).strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------- base

def db():
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH, timeout=30)
    con.row_factory = sqlite3.Row
    con.executescript("""
    PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS tasks(
        issue INTEGER PRIMARY KEY, title TEXT, status TEXT NOT NULL DEFAULT 'READY',
        priority INTEGER NOT NULL DEFAULT 50, deps TEXT DEFAULT '', branch TEXT DEFAULT '',
        pr TEXT DEFAULT '', commit_sha TEXT DEFAULT '', summary TEXT DEFAULT '', tests TEXT DEFAULT '',
        blockers TEXT DEFAULT '', next_action TEXT DEFAULT '', note TEXT DEFAULT '',
        attempts INTEGER NOT NULL DEFAULT 0, last_session TEXT DEFAULT '', last_activity TEXT DEFAULT '',
        not_before TEXT DEFAULT '', updated_at TEXT);
    CREATE TABLE IF NOT EXISTS sessions(
        id TEXT PRIMARY KEY, issue INTEGER, started_at TEXT, ended_at TEXT, pid INTEGER,
        outcome TEXT, exit_code INTEGER, detail TEXT, log_path TEXT, cost_usd REAL);
    CREATE TABLE IF NOT EXISTS events(
        id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, issue INTEGER, kind TEXT, message TEXT);
    CREATE TABLE IF NOT EXISTS control(key TEXT PRIMARY KEY, value TEXT);
    """)
    cols = {r["name"] for r in con.execute("PRAGMA table_info(tasks)")}
    # #836 : colonnes additives ; une base plus ancienne est complétée sans perte.
    if "auto_hold" not in cols:
        con.execute("ALTER TABLE tasks ADD COLUMN auto_hold INTEGER NOT NULL DEFAULT 0")
    if "auto_seen_at" not in cols:
        con.execute("ALTER TABLE tasks ADD COLUMN auto_seen_at TEXT DEFAULT ''")
    con.commit()
    return con


def ctl_get(con, key, default=""):
    r = con.execute("SELECT value FROM control WHERE key=?", (key,)).fetchone()
    return r["value"] if r else default


def ctl_set(con, key, value):
    con.execute("INSERT INTO control(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, str(value)))
    con.commit()


def parse_setting(name, raw):
    """Valeur typée et bornée, ou ValueError avec un message lisible."""
    _, default, low, high, _ = SETTINGS[name]
    try:
        value = type(default)(raw)
    except (TypeError, ValueError):
        raise ValueError(f"{name} attend un nombre {'entier' if isinstance(default, int) else 'décimal'}.") from None
    if not low <= value <= high:
        raise ValueError(f"{name} doit être compris entre {low} et {high}.")
    return value


def setting(con, name):
    """Rend (valeur, origine). Une valeur invalide est ignorée au profit de la source suivante."""
    env, default, *_ = SETTINGS[name]
    for raw, origin in ((ctl_get(con, "cfg." + name, None), "lqorch config"), (os.environ.get(env), f"env {env}")):
        if raw is not None and raw != "":
            try:
                return parse_setting(name, raw), origin
            except ValueError:
                continue
    return default, "défaut du code"


def policy_error(values):
    """Un mode sans plafond de sessions n'est permis que si un autre garde-fou borne la dépense."""
    if values["max_sessions_per_day"] == 0 and values["max_cost_usd_per_day"] <= 0 \
            and values["max_five_hour_utilization"] >= 1 and values["max_seven_day_utilization"] >= 1:
        return ("Sans plafond de sessions, il faut au moins un garde-fou : max_cost_usd_per_day > 0 "
                "ou un seuil d'utilisation fournisseur < 1.")
    return ""


def settings(con):
    return {name: setting(con, name)[0] for name in SETTINGS}


def event(con, issue, kind, message):
    con.execute("INSERT INTO events(at,issue,kind,message) VALUES(?,?,?,?)", (iso(), issue, kind, message[:2000]))
    con.commit()


def task(con, issue):
    return con.execute("SELECT * FROM tasks WHERE issue=?", (issue,)).fetchone()


def update_task(con, issue, **fields):
    fields["updated_at"] = iso()
    cols = ", ".join(f"{k}=?" for k in fields)
    con.execute(f"UPDATE tasks SET {cols} WHERE issue=?", (*fields.values(), issue))
    con.commit()


# ---------------------------------------------------------------- GitHub (lecture seule ici)

def gh_json(path):
    try:
        out = subprocess.run([GH, "api", path], capture_output=True, text=True, timeout=60)
        return json.loads(out.stdout) if out.returncode == 0 else None
    except (subprocess.SubprocessError, json.JSONDecodeError, OSError):
        return None


def issue_state(n):
    data = gh_json(f"repos/{REPO}/issues/{n}")
    if not data:
        return None, ""
    return data.get("state"), data.get("title", "")


def deps_satisfied(con, deps):
    """Une dépendance est levée si le ticket est fermé, ou traité ici (DONE / WAITING_OWNER)."""
    missing = []
    for raw in [d.strip().lstrip("#") for d in (deps or "").split(",") if d.strip()]:
        if not raw.isdigit():
            continue
        n = int(raw)
        t = task(con, n)
        if t and t["status"] in ("DONE", "WAITING_OWNER"):
            continue
        state, _ = issue_state(n)
        if state != "closed":
            missing.append(n)
    return missing


# ---------------------------------------------------------------- sélection

def next_task(con):
    """Le prochain travail : une session interrompue d'abord, puis le READY le plus prioritaire."""
    rows = con.execute("""SELECT * FROM tasks WHERE status IN ('RUNNING','TESTING','READY')
                          ORDER BY CASE status WHEN 'READY' THEN 1 ELSE 0 END, priority, issue""").fetchall()
    for t in rows:
        if t["not_before"] and t["not_before"] > iso():
            continue
        if t["issue"] != TRIAGE:
            state, title = issue_state(t["issue"])
            if state == "closed":
                update_task(con, t["issue"], status="DONE", next_action="Ticket fermé sur GitHub.")
                event(con, t["issue"], "closed", "Ticket fermé sur GitHub : retiré de la file.")
                continue
            if title and not t["title"]:
                update_task(con, t["issue"], title=title)
        missing = deps_satisfied(con, t["deps"])
        if missing:
            update_task(con, t["issue"], blockers=f"Dépendances ouvertes : {missing}")
            continue
        return task(con, t["issue"])
    return None


# ---------------------------------------------------------------- file automatique (#836)
#
# Quand la file locale est vide, le superviseur cherche lui-même un ticket ouvert labellisé
# `agent:auto`. Le label rend un ticket ÉLIGIBLE ; il n'autorise rien de plus (ni déploiement, ni
# appel payant, ni opération réelle) : les restrictions du ticket et du prompt de session restent
# entières. Un ticket déjà traité ici (DONE / WAITING_OWNER / BLOCKED) n'est repris que si une
# activité GitHub nouvelle, extérieure aux sessions (décision ou retour du propriétaire), est
# apparue depuis sa dernière prise en compte : jamais en boucle sur son propre rapport.

AUTO_LABEL = "agent:auto"
PRIORITY_LABELS = {"priority:P0": 0, "priority:P1": 1, "priority:P2": 2, "priority:P3": 3}
EXCLUDE_LABELS = {"blocked", "duplicate", "invalid", "wontfix", "in-progress"}
# Les sessions et le superviseur terminent leurs commentaires GitHub par ce marqueur.
SESSION_MARKER = "<!-- lqorch"
# Un commentaire non marqué posté pendant une session DU MÊME TICKET (ou juste après) vient de cette
# session. Une session ne masque jamais les commentaires des autres tickets : le propriétaire écrit
# souvent pendant qu'une autre session tourne (29/10 : décisions #734/#813 pendant la session #881).
SESSION_MARGIN_SECONDS = 300
DEPS_LINE = re.compile(r"^[\s>*_-]*(?:\*\*)?\s*(?:dépend(?:ances?)?(?:\s+de)?|depends\s+on|bloqué\s+par"
                       r"|blocked\s+by|prérequis)\b.*$", re.IGNORECASE | re.MULTILINE)


def gh_list(path):
    """Toutes les pages d'une liste GitHub, ou None si une page est illisible."""
    items, page = [], 1
    sep = "&" if "?" in path else "?"
    while True:
        data = gh_json(f"{path}{sep}per_page=100&page={page}")
        if not isinstance(data, list):
            return None
        items += data
        if len(data) < 100 or page >= 10:
            return items
        page += 1


def parse_iso(ts):
    return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def body_deps(body):
    """Tickets cités sur une ligne « Dépend de / Bloqué par / Prérequis / Depends on » du ticket."""
    found = []
    for line in DEPS_LINE.findall(body or ""):
        found += [n for n in re.findall(r"#(\d+)", line) if n not in found]
    return found


def merged_deps(local, body):
    out = [d.strip().lstrip("#") for d in (local or "").split(",") if d.strip()]
    return ",".join(out + [n for n in body_deps(body) if n not in out])


def session_windows(con, issue):
    margin = SESSION_MARGIN_SECONDS
    return [(s["started_at"], iso(datetime.fromtimestamp(parse_iso(s["ended_at"] or iso()).timestamp() + margin,
                                                         timezone.utc)))
            for s in con.execute("SELECT started_at, ended_at FROM sessions WHERE issue=? AND started_at IS NOT NULL",
                                 (issue,))]


def seen_at(con, t):
    """Jusqu'où l'activité GitHub du ticket a déjà été prise en compte."""
    if t["auto_seen_at"]:
        return t["auto_seen_at"]
    last = con.execute("SELECT MAX(ended_at) m FROM sessions WHERE issue=?", (t["issue"],)).fetchone()["m"]
    return last or t["updated_at"] or ""


def external_activity(issue, since, windows):
    """Heure du dernier commentaire postérieur à `since` qui ne vient ni d'une session ni du
    superviseur ; "" s'il n'y en a pas ; None si GitHub ne répond pas."""
    comments = gh_list(f"repos/{REPO}/issues/{issue}/comments" + (f"?since={since}" if since else ""))
    if comments is None:
        return None
    latest = ""
    for c in comments:
        ts = c.get("created_at") or ""
        if ts <= since or SESSION_MARKER in (c.get("body") or ""):
            continue
        if any(start <= ts <= end for start, end in windows):
            continue
        latest = max(latest, ts)
    return latest


def auto_candidates(con, dry=False):
    """(admissibles triés P0→P3 puis ancienneté, écartés avec leur motif) ; (None, motif) si GitHub
    est injoignable. Lecture seule, à part la mémorisation des activités déjà examinées (sauf `dry`)."""
    issues = gh_list(f"repos/{REPO}/issues?state=open&labels={AUTO_LABEL}")
    if issues is None:
        return None, [dict(issue=None, title="", reason="GitHub injoignable : aucune sélection automatique.")]
    prs = gh_list(f"repos/{REPO}/pulls?state=open")
    eligible, excluded = [], []
    for it in issues:
        if "pull_request" in it:
            continue
        n, title = it["number"], it.get("title", "")
        labels = {lab.get("name") for lab in it.get("labels", [])}

        def skip(reason):
            excluded.append(dict(issue=n, title=title, reason=reason))

        if AUTO_LABEL not in labels:
            skip(f"sans label {AUTO_LABEL}")
            continue

        ranks = sorted(PRIORITY_LABELS[lab] for lab in labels if lab in PRIORITY_LABELS)
        if len(ranks) != 1:
            skip("aucun label priority:P0…P3" if not ranks else "plusieurs labels priority:P*")
            continue
        if labels & EXCLUDE_LABELS:
            skip("label " + ", ".join(sorted(labels & EXCLUDE_LABELS)))
            continue
        t = task(con, n)
        rearm = ""
        if t:
            if t["status"] in ("READY", "RUNNING", "TESTING"):
                skip(f"déjà dans la file locale ({t['status']}{', ' + t['blockers'] if t['blockers'] else ''})")
                continue
            if t["auto_hold"]:
                skip(f"{t['status']}, retenu par le propriétaire (lqorch auto release {n} pour le libérer)")
                continue
            if t["status"] == "FAILED":
                skip("FAILED : relance manuelle seulement (lqorch add)")
                continue
            seen = seen_at(con, t)
            if (it.get("updated_at") or "") <= seen:
                skip(f"{t['status']} : aucune activité GitHub depuis {seen}")
                continue
            activity = external_activity(n, seen, session_windows(con, n))
            if activity is None:
                skip("commentaires GitHub illisibles")
                continue
            if not activity:
                # Tout ce qui a bougé vient des sessions : inutile de le relire au prochain tour.
                if not dry:
                    update_task(con, n, auto_seen_at=it.get("updated_at") or iso())
                skip(f"{t['status']} : rien de nouveau hors sessions depuis {seen}")
                continue
            rearm = activity
        concurrent = [p for p in prs or [] if re.search(rf"(^|/){n}-", (p.get("head") or {}).get("ref", ""))]
        if concurrent:
            skip(f"PR ouverte #{concurrent[0]['number']} ({concurrent[0]['head']['ref']}) : travail concurrent")
            continue
        deps = merged_deps(t["deps"] if t else "", it.get("body"))
        missing = deps_satisfied(con, deps)
        if missing:
            skip(f"dépendances ouvertes : {', '.join('#' + str(m) for m in missing)}")
            continue
        eligible.append(dict(issue=n, title=title, rank=ranks[0], deps=deps, rearm=rearm,
                             status=t["status"] if t else ""))
    eligible.sort(key=lambda c: (c["rank"], c["issue"]))
    return eligible, excluded


def gh_comment(issue, body):
    try:
        out = subprocess.run([GH, "issue", "comment", str(issue), "--repo", REPO, "--body", body],
                             capture_output=True, text=True, timeout=60)
        return out.returncode == 0
    except (subprocess.SubprocessError, OSError):
        return False


def auto_select(con):
    """File vide : arme le ticket agent:auto le plus prioritaire et le rend, ou None."""
    if not setting(con, "auto_select")[0]:
        return None
    eligible, excluded = auto_candidates(con)
    ctl_set(con, "auto_last", json.dumps(dict(at=iso(), eligible=eligible or [], excluded=excluded),
                                         ensure_ascii=False))
    if not eligible:
        return None
    c = eligible[0]
    n = c["issue"]
    why = (f"Sélection automatique (#836) : agent:auto, priority:P{c['rank']}, "
           + (f"nouvelle activité du propriétaire sur GitHub le {c['rearm']} (ticket {c['status']} jusque-là)"
              if c["rearm"] else "ticket nouveau pour le superviseur"))
    next_action = (why + ". Relire d'abord les commentaires GitHub récents : ils priment sur l'état enregistré. "
                   "Le label n'autorise ni déploiement, ni appel payant, ni opération réelle non prévus par le ticket.")
    t = task(con, n)
    if t:
        update_task(con, n, status="READY", attempts=0, not_before="", priority=c["rank"] * 10, deps=c["deps"],
                    blockers="", auto_seen_at=iso(),
                    next_action=next_action + (f" (Précédente : {t['next_action'][:300]})" if t["next_action"] else ""))
    else:
        con.execute("""INSERT INTO tasks(issue,title,priority,deps,note,next_action,auto_seen_at,updated_at)
                       VALUES(?,?,?,?,?,?,?,?)""", (n, c["title"], c["rank"] * 10, c["deps"], why, next_action,
                                                    iso(), iso()))
        con.commit()
    event(con, n, "auto_select", why)
    if setting(con, "auto_comment")[0]:
        others = ", ".join(f"#{e['issue']} (P{e['rank']})" for e in eligible[1:6]) or "aucun"
        ok = gh_comment(n, f"🤖 **lqorch — prise en charge automatique** : {why}.\n\n"
                           f"Une session démarre sur ce ticket. Les restrictions écrites ici restent applicables ; "
                           f"le label `agent:auto` n'autorise aucun déploiement, appel payant ou opération réelle "
                           f"qu'elles ne prévoient pas. Suivants admissibles : {others}.\n\n<!-- lqorch:auto -->")
        if not ok:
            event(con, n, "auto_select", "Commentaire GitHub de prise en charge non publié (gh en échec).")
    return task(con, n)


# ---------------------------------------------------------------- quota

def provider_usage(con):
    """Dernier `rate_limit_info` annoncé par Claude Code, avec son heure d'observation. Ce n'est
    qu'un instantané pris pendant la dernière session : l'usage interactif du propriétaire depuis
    n'y figure pas. Aucune autre source (pourcentages de l'interface claude.ai) n'est lue."""
    raw = ctl_get(con, "provider_usage")
    if raw:
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            pass
    # Superviseur antérieur à #380 : relire le dernier événement dans le journal le plus récent.
    last = con.execute("SELECT log_path FROM sessions WHERE log_path IS NOT NULL ORDER BY started_at DESC LIMIT 1").fetchone()
    path = Path(last["log_path"]) if last else None
    if not path or not path.exists():
        return {}
    info = {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if '"rate_limit_event"' in line:
                try:
                    info = json.loads(line).get("rate_limit_info") or info
                except json.JSONDecodeError:
                    pass
    return dict(info, observed_at=iso(datetime.fromtimestamp(path.stat().st_mtime, timezone.utc))) if info else {}


def next_utc_day():
    return datetime.fromtimestamp((int(time.time()) // 86400 + 1) * 86400, timezone.utc)


def start_blocker(con):
    """Pourquoi aucune session ne peut démarrer maintenant : (raison, reprise_iso) ou None.
    Les limites du fournisseur passent avant la politique locale et ne sont jamais contournées."""
    if ctl_get(con, "mode", "RUNNING") == "PAUSED":
        return ctl_get(con, "pause_reason") or "Pause demandée (lqorch resume pour reprendre).", ""
    until = ctl_get(con, "quota_until")
    if until and until > iso():
        return "Limite fournisseur signalée par Claude Code.", until
    cfg = settings(con)
    windows = provider_usage(con).get("unifiedWindows") or {}
    for window, key, label in (("five_hour", "max_five_hour_utilization", "5 h"),
                               ("seven_day", "max_seven_day_utilization", "7 jours")):
        win = windows.get(window) or {}
        util, reset = win.get("utilization"), win.get("resetsAt")
        if util is not None and reset and reset > time.time() and util >= cfg[key]:
            return (f"Fenêtre fournisseur {label} à {util:.0%} (seuil {cfg[key]:.0%}).",
                    iso(datetime.fromtimestamp(int(reset) + QUOTA_MARGIN_SECONDS, timezone.utc)))
    cap = cfg["max_sessions_per_day"]
    if policy_error(cfg):
        cap = SETTINGS["max_sessions_per_day"][1]
    if cap and sessions_today(con) >= cap:
        return f"Plafond local de {cap} sessions par jour atteint.", iso(next_utc_day())
    if cfg["max_cost_usd_per_day"] > 0 and cost_today(con) >= cfg["max_cost_usd_per_day"]:
        return f"Budget estimé du jour atteint ({cfg['max_cost_usd_per_day']:.2f} USD).", iso(next_utc_day())
    last = con.execute("SELECT MAX(started_at) m FROM sessions").fetchone()["m"]
    if last:
        ready = datetime.strptime(last, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp() \
            + cfg["min_seconds_between_sessions"]
        if ready > time.time():
            return "Écart minimal entre deux sessions.", iso(datetime.fromtimestamp(ready, timezone.utc))
    return None


# ---------------------------------------------------------------- session

def build_prompt(con, t, session_id, status_file):
    base = PROMPT_FILE.read_text(encoding="utf-8")
    history = con.execute("""SELECT started_at, outcome, detail FROM sessions WHERE issue=?
                             ORDER BY started_at DESC LIMIT 5""", (t["issue"],)).fetchall()
    hist = "\n".join(f"- {h['started_at']} : {h['outcome']} — {(h['detail'] or '')[:300]}" for h in history) or "- (aucune)"
    state = {k: t[k] for k in t.keys()}
    queue = [dict(issue=r["issue"], status=r["status"], title=r["title"]) for r in con.execute(
        "SELECT issue,status,title FROM tasks ORDER BY priority, issue").fetchall()]
    return (base.replace("{{ISSUE}}", str(t["issue"]))
            .replace("{{TITLE}}", t["title"] or "")
            .replace("{{STATE_JSON}}", json.dumps(state, ensure_ascii=False, indent=2))
            .replace("{{HISTORY}}", hist)
            .replace("{{QUEUE_JSON}}", json.dumps(queue, ensure_ascii=False))
            .replace("{{SESSION_ID}}", session_id)
            .replace("{{STATUS_FILE}}", str(status_file))
            .replace("{{WORKDIR}}", str(WORKDIR))
            .replace("{{LAUNCH_DIR}}", str(LAUNCH_DIR))
            .replace("{{STATE_DIR}}", str(STATE_DIR)))


def classify(exit_code, result, rate_events, killed):
    """Comment la session s'est terminée. Rend (issue_de_session, détail, quota_reprise_iso)."""
    reset = ""
    for ev in rate_events:
        info = ev.get("rate_limit_info", {})
        if info.get("status") not in (None, "allowed", "allowed_warning"):
            ts = info.get("resetsAt")
            if ts:
                reset = iso(datetime.fromtimestamp(int(ts) + QUOTA_MARGIN_SECONDS, timezone.utc))
            return "RATE_LIMIT", f"Limite fournisseur ({info.get('rateLimitType')}) jusqu'à {reset or '?'}", reset
    if killed:
        return ("STOPPED" if killed.startswith("Arrêt immédiat") else "TIMEOUT"), killed, ""
    if result is None:
        return "CRASH", f"Processus terminé sans résultat (code {exit_code}).", ""
    text = str(result.get("result") or "")
    status = result.get("api_error_status")
    if result.get("is_error"):
        low = text.lower()
        if status == 429 or "usage limit" in low or "rate limit" in low:
            return "RATE_LIMIT", text[:300], ""
        if status and int(status) >= 500 or "connection" in low or "overloaded" in low:
            return "TRANSIENT", text[:300], ""
        return "ERROR", text[:300], ""
    return "COMPLETED", text[:500], ""


def run_session(con, t):
    session_id = str(uuid.uuid4())
    log_path = LOG_DIR / f"{iso().replace(':', '')}-{t['issue']}-{session_id[:8]}.jsonl"
    status_file = LOG_DIR / f"{session_id}.status.json"
    prompt = build_prompt(con, t, session_id, status_file)
    con.execute("INSERT INTO sessions(id,issue,started_at,log_path) VALUES(?,?,?,?)",
                (session_id, t["issue"], iso(), str(log_path)))
    update_task(con, t["issue"], status="RUNNING", last_session=session_id, last_activity=iso(),
                attempts=t["attempts"] + 1)
    event(con, t["issue"], "session_start", f"Session {session_id} (tentative {t['attempts'] + 1})")
    env = dict(os.environ, LQ_SESSION_ID=session_id, LQ_STATUS_FILE=str(status_file), LQ_ISSUE=str(t["issue"]))
    cmd = [CLAUDE, "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--dangerously-skip-permissions", "--session-id", session_id]
    max_seconds = setting(con, "session_max_seconds")[0]
    idle_seconds = setting(con, "session_idle_seconds")[0]
    started = time.time()
    last_out = time.time()
    result = None
    rate_events = []
    killed = ""
    with open(log_path, "w", encoding="utf-8") as log:
        proc = subprocess.Popen(cmd, cwd=LAUNCH_DIR, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
        con.execute("UPDATE sessions SET pid=? WHERE id=?", (proc.pid, session_id))
        con.commit()
        lines = queue.Queue()

        def pump():
            for line in proc.stdout:
                lines.put(line)
            lines.put(None)

        reader = threading.Thread(target=pump, daemon=True)
        reader.start()
        finished = False
        while not finished:
            try:
                line = lines.get(timeout=2)
            except queue.Empty:
                line = ""
            while line is not None and line != "":
                last_out = time.time()
                log.write(line)
                try:
                    msg = json.loads(line)
                    if msg.get("type") == "result":
                        result = msg
                    elif msg.get("type") == "rate_limit_event":
                        rate_events.append(msg)
                        ctl_set(con, "provider_usage", json.dumps(
                            dict(msg.get("rate_limit_info") or {}, observed_at=iso()), ensure_ascii=False))
                except json.JSONDecodeError:
                    pass
                try:
                    line = lines.get_nowait()
                except queue.Empty:
                    line = ""
            if line is None:
                finished = True
            log.flush()
            if time.time() - last_out < 5:
                update_task(con, t["issue"], last_activity=iso())
            if finished:
                proc.wait()
                break
            reason = ""
            if ctl_get(con, "mode") == "STOP_NOW":
                reason = "Arrêt immédiat demandé (stop --now)."
            elif time.time() - started > max_seconds:
                reason = f"Durée maximale dépassée ({max_seconds // 60} min)."
            elif time.time() - last_out > idle_seconds:
                reason = f"Aucune activité depuis {idle_seconds} s."
            if reason:
                killed = reason
                event(con, t["issue"], "session_kill", reason)
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                    proc.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait()
                except ProcessLookupError:
                    pass
                break
    reader.join(timeout=10)
    proc.stdout.close()
    outcome, detail, reset = classify(proc.returncode, result, rate_events, killed)
    report = {}
    if status_file.exists():
        try:
            report = json.loads(status_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            report = {}
    cost = (result or {}).get("total_cost_usd")
    con.execute("UPDATE sessions SET ended_at=?, outcome=?, exit_code=?, detail=?, cost_usd=? WHERE id=?",
                (iso(), outcome, proc.returncode, (report.get("summary") or detail)[:2000], cost, session_id))
    con.commit()
    apply_outcome(con, t["issue"], outcome, detail, reset, report)
    # L'activité GitHub de la session (rapport, commentaires) ne doit pas réarmer le ticket (#836).
    update_task(con, t["issue"], auto_seen_at=iso())
    return outcome


def apply_outcome(con, issue, outcome, detail, reset, report):
    t = task(con, issue)
    event(con, issue, "session_end", f"{outcome} — {detail[:300]}")
    if outcome == "RATE_LIMIT":
        until = reset or iso(datetime.fromtimestamp(time.time() + 3600, timezone.utc))
        ctl_set(con, "quota_until", until)
        # Une limite n'est pas un échec du ticket : la tentative ne compte pas.
        update_task(con, issue, status="READY", attempts=max(0, t["attempts"] - 1),
                    next_action=f"Reprendre après la limite fournisseur ({until}).")
        return
    if outcome == "STOPPED" or report.get("status") in STATUSES:
        ctl_set(con, "consecutive_failures", 0)
    if outcome == "STOPPED":
        # Arrêt volontaire : ni échec, ni tentative ; le ticket sera repris tel quel après « resume ».
        update_task(con, issue, status="RUNNING", attempts=max(0, t["attempts"] - 1), not_before="",
                    next_action="Reprendre : session arrêtée volontairement (stop --now).")
        return
    if report.get("status") in STATUSES:
        fields = {k: report[k] for k in ("branch", "pr", "commit_sha", "summary", "tests", "blockers", "next_action")
                  if k in report and report[k] is not None}
        fields = {k: (v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)) for k, v in fields.items()}
        status = report["status"]
        if status in ("DONE", "WAITING_OWNER", "BLOCKED"):
            fields["attempts"] = 0
        update_task(con, issue, status=status, **fields)
        for extra in report.get("enqueue", []) or []:
            try:
                n = int(str(extra.get("issue")).lstrip("#"))
            except (TypeError, ValueError):
                continue
            if n and not task(con, n):
                con.execute("INSERT INTO tasks(issue,title,priority,deps,note,updated_at) VALUES(?,?,?,?,?,?)",
                            (n, extra.get("title", ""), int(extra.get("priority", 60)), extra.get("deps", ""),
                             extra.get("note", "ajouté par une session"), iso()))
                con.commit()
                event(con, n, "enqueued", f"Ajouté par la session sur #{issue}")
        return
    # Pas de rapport exploitable : tentative comptée, délai progressif, abandon après max_attempts.
    trip_breaker(con, outcome, detail)
    attempts = t["attempts"]
    if attempts >= setting(con, "max_attempts")[0]:
        update_task(con, issue, status="FAILED", blockers=f"{attempts} sessions sans rapport : {outcome} — {detail[:300]}",
                    next_action="Examiner les journaux de session, puis `lqorch add` pour relancer.")
        event(con, issue, "failed", f"Abandon après {attempts} tentatives.")
        return
    delay = {"TRANSIENT": 300, "CRASH": 900, "TIMEOUT": 600, "ERROR": 1800}.get(outcome, 900) * attempts * BACKOFF_SCALE
    update_task(con, issue, status="RUNNING" if outcome in ("TIMEOUT", "COMPLETED") else "READY",
                not_before=iso(datetime.fromtimestamp(time.time() + delay, timezone.utc)),
                next_action=f"Reprendre (dernière session : {outcome}).")


def trip_breaker(con, outcome, detail):
    """Compte les sessions consécutives sans rapport, tous tickets confondus ; au seuil, pause
    globale plutôt qu'une suite de sessions qui échouent toutes (panne, quota mal détecté…)."""
    failures = int(ctl_get(con, "consecutive_failures", "0") or 0) + 1
    ctl_set(con, "consecutive_failures", failures)
    limit = setting(con, "max_consecutive_failures")[0]
    if failures >= limit:
        ctl_set(con, "mode", "PAUSED")
        ctl_set(con, "pause_reason", f"Disjoncteur : {failures} sessions consécutives sans rapport "
                                     f"(dernière : {outcome} — {detail[:150]}). lqorch resume pour réarmer.")
        event(con, None, "breaker", f"Pause automatique après {failures} échecs consécutifs.")


# ---------------------------------------------------------------- boucle

def recover_interrupted(con):
    """Après un arrêt brutal du superviseur : une session orpheline est arrêtée (plus personne ne la
    surveille) et notée INTERRUPTED ; son ticket reste RUNNING et sera repris en premier, depuis
    l'état réel (Git, GitHub), jamais en rejouant ce que l'ancienne session disait avoir fait."""
    for s in con.execute("SELECT * FROM sessions WHERE ended_at IS NULL").fetchall():
        if s["pid"] and Path(f"/proc/{s['pid']}").exists():
            try:
                os.killpg(s["pid"], signal.SIGTERM)
                time.sleep(5)
                if Path(f"/proc/{s['pid']}").exists():
                    os.killpg(s["pid"], signal.SIGKILL)
            except ProcessLookupError:
                pass
        con.execute("UPDATE sessions SET ended_at=?, outcome='INTERRUPTED', detail=? WHERE id=?",
                    (iso(), "Superviseur redémarré pendant la session.", s["id"]))
        con.commit()
        event(con, s["issue"], "recovered", f"Session {s['id']} interrompue par un redémarrage du superviseur.")


def pause_for(con, seconds):
    """Attente qui rend la main dès qu'un arrêt, une reprise ou un rechargement est demandé."""
    end = time.time() + seconds
    start_mode = ctl_get(con, "mode", "RUNNING")
    while time.time() < end:
        if ctl_get(con, "mode", "RUNNING") != start_mode or ctl_get(con, "reload_requested"):
            return
        time.sleep(min(2, max(0.1, end - time.time())))


def sessions_today(con):
    day = iso()[:10]
    return con.execute("SELECT COUNT(*) c FROM sessions WHERE started_at LIKE ?", (day + "%",)).fetchone()["c"]


def cost_today(con):
    day = iso()[:10]
    return con.execute("SELECT COALESCE(SUM(cost_usd),0) s FROM sessions WHERE started_at LIKE ?",
                       (day + "%",)).fetchone()["s"]


def code_version():
    """Empreinte du fichier exécuté : `status` la compare à celle du superviseur en cours."""
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:12]


def reexec(con):
    """Recharge le code installé dans le même processus (même PID pour systemd). Appelé seulement
    entre deux sessions ; le verrou est libéré par l'exec (descripteur non hérité) puis repris."""
    ctl_set(con, "reload_requested", "")
    event(con, None, "supervisor", "Rechargement du code demandé : ré-exécution entre deux sessions.")
    con.close()
    os.execv(sys.executable, [sys.executable, str(Path(__file__).resolve()), "run"])


def supervise():
    con = db()
    lock = open(LOCK_PATH, "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("Un autre superviseur tourne déjà.", file=sys.stderr)
        return 0
    lock.write(str(os.getpid()))
    lock.flush()
    mode = ctl_get(con, "mode", "RUNNING")
    if mode in ("STOP", "STOP_NOW"):
        # Arrêt explicite : le service ne se réactive pas tout seul, même relancé par systemd.
        event(con, None, "supervisor", "Démarrage refusé : arrêt demandé (lqorch resume pour reprendre).")
        return 0
    recover_interrupted(con)
    ctl_set(con, "supervisor_pid", os.getpid())
    ctl_set(con, "supervisor_started", iso())
    ctl_set(con, "supervisor_version", code_version())
    if not ctl_get(con, "provider_usage"):
        usage = provider_usage(con)
        if usage:
            ctl_set(con, "provider_usage", json.dumps(usage, ensure_ascii=False))
    event(con, None, "supervisor", f"Superviseur démarré (code {code_version()}).")
    while True:
        mode = ctl_get(con, "mode", "RUNNING")
        if mode in ("STOP", "STOP_NOW"):
            event(con, None, "supervisor", "Arrêt demandé : superviseur arrêté.")
            ctl_set(con, "supervisor_pid", "")
            return 0
        if ctl_get(con, "reload_requested"):
            reexec(con)
        blocker = start_blocker(con)
        if blocker:
            reason, until = blocker
            ctl_set(con, "idle_reason", reason + (f" Reprise au plus tôt : {until}." if until else ""))
            pause_for(con, POLL_SECONDS)
            continue
        t = next_task(con)
        if t is None:
            t = auto_select(con)
        if t is None:
            last = ctl_get(con, "last_triage")
            if not last or time.time() - float(last) > TRIAGE_EVERY_SECONDS:
                ctl_set(con, "last_triage", time.time())
                if not task(con, TRIAGE):
                    con.execute("INSERT INTO tasks(issue,title,priority,updated_at) VALUES(0,'Tri du backlog',99,?)",
                                (iso(),))
                    con.commit()
                update_task(con, TRIAGE, status="READY", attempts=0, not_before="")
                continue
            later = con.execute("""SELECT issue, not_before FROM tasks WHERE status IN ('READY','RUNNING','TESTING')
                                   AND not_before > ? ORDER BY not_before LIMIT 1""", (iso(),)).fetchone()
            ctl_set(con, "idle_reason", f"En attente : #{later['issue']} reprend après {later['not_before']}." if later
                    else "Aucun ticket éligible : tous terminés, bloqués ou en attente du propriétaire.")
            pause_for(con, POLL_SECONDS * 10)
            continue
        ctl_set(con, "idle_reason", "")
        run_session(con, t)


# ---------------------------------------------------------------- CLI

def local(ts_iso):
    """Horodatage UTC suivi de l'heure locale de la machine, pour le propriétaire."""
    dt = datetime.strptime(ts_iso, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    return f"{ts_iso} ({dt.astimezone().strftime('%d/%m %H:%M %Z')})"


def cmd_status(con, _):
    mode = ctl_get(con, "mode", "RUNNING")
    pid = ctl_get(con, "supervisor_pid")
    alive = bool(pid) and Path(f"/proc/{pid}").exists()
    running = ctl_get(con, "supervisor_version")
    print(f"Mode : {mode} — superviseur {'actif (pid ' + pid + ', code ' + (running or '?') + ')' if alive else 'arrêté'}")
    if alive and running != code_version():
        print(f"  ⚠ code installé ({code_version()}) différent de celui en cours : "
              + ("« lqorch reload » l'appliquera entre deux sessions." if running else
                 "superviseur antérieur à #380, sans rechargement : redémarrage unique nécessaire (voir README)."))
    if ctl_get(con, "reload_requested"):
        print(f"  Rechargement demandé à {ctl_get(con, 'reload_requested')} : appliqué à la fin de la session en cours.")
    cur = con.execute("SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1").fetchone()
    if cur:
        t = task(con, cur["issue"])
        print(f"Session en cours : #{cur['issue']} {t['title'] if t else ''} — depuis {cur['started_at']}, "
              f"dernière activité {t['last_activity'] if t else '?'} — journal {cur['log_path']}")
    blocker = start_blocker(con)
    if blocker:
        print(f"Prochaine session : bloquée — {blocker[0]}" + (f" Reprise au plus tôt : {local(blocker[1])}." if blocker[1] else ""))
    else:
        print("Prochaine session : autorisée" + (" (après la session en cours)." if cur else "."))
    if ctl_get(con, "idle_reason") and not cur:
        print(f"Dernier motif d'attente du superviseur : {ctl_get(con, 'idle_reason')}")
    cfg = {name: setting(con, name) for name in SETTINGS}
    cap, cap_origin = cfg["max_sessions_per_day"]
    budget = cfg["max_cost_usd_per_day"][0]
    print(f"Politique locale (jour UTC, remise à zéro {local(iso(next_utc_day()))}) :")
    print(f"  sessions aujourd'hui : {sessions_today(con)} / {cap or 'sans plafond'} — origine : {cap_origin}"
          + (" ⚠ ignoré faute de garde-fou, plafond par défaut appliqué" if policy_error(settings(con)) else ""))
    print(f"  coût estimé du jour : {cost_today(con):.2f} USD (prix catalogue, pas une facture) / "
          + (f"{budget:.2f} USD ({cfg['max_cost_usd_per_day'][1]})" if budget > 0 else "pas de plafond"))
    print(f"  disjoncteur : {ctl_get(con, 'consecutive_failures', '0') or 0} / "
          f"{cfg['max_consecutive_failures'][0]} sessions consécutives sans rapport")
    usage = provider_usage(con)
    q = ctl_get(con, "quota_until")
    if q and q > iso():
        print(f"Limite fournisseur atteinte : aucune session avant {local(q)}")
    if usage:
        print(f"Fournisseur — dernière valeur annoncée par Claude Code ({usage.get('observed_at', '?')}) :")
        for window, key, label in (("five_hour", "max_five_hour_utilization", "fenêtre 5 h"),
                                   ("seven_day", "max_seven_day_utilization", "fenêtre 7 j")):
            win = (usage.get("unifiedWindows") or {}).get(window)
            if win and win.get("utilization") is not None:
                reset = iso(datetime.fromtimestamp(int(win["resetsAt"]), timezone.utc)) if win.get("resetsAt") else "?"
                print(f"  {label} : {win['utilization']:.0%} (seuil local {cfg[key][0]:.0%}), "
                      f"remise à zéro {local(reset) if reset != '?' else '?'}")
        print(f"  statut {usage.get('status', '?')}, dépassement payant : {usage.get('overageStatus', '?')}"
              + (f" ({usage['overageDisabledReason']})" if usage.get("overageDisabledReason") else ""))
        print("  Instantané de la dernière session : l'usage interactif depuis n'y figure pas ; "
              "le quota restant réel n'est pas connu.")
    else:
        print("Fournisseur : aucune donnée de quota observée (rien n'est déduit de l'interface claude.ai).")
    print(auto_summary(con))
    counts = con.execute("SELECT status, COUNT(*) c FROM tasks GROUP BY status").fetchall()
    print("Tickets : " + ", ".join(f"{r['status']} {r['c']}" for r in counts))
    print("Réglages : lqorch config")


def auto_summary(con):
    if not setting(con, "auto_select")[0]:
        return "File automatique agent:auto : désactivée (lqorch config set auto_select 1)."
    try:
        last = json.loads(ctl_get(con, "auto_last") or "{}")
    except json.JSONDecodeError:
        last = {}
    if not last:
        return "File automatique agent:auto : activée, pas encore d'examen (lqorch auto pour un examen à blanc)."
    elig = ", ".join(f"#{e['issue']} P{e['rank']}" for e in last.get("eligible", [])) or "aucun admissible"
    return (f"File automatique agent:auto : dernier examen {local(last['at'])} — {elig} ; "
            f"{len(last.get('excluded', []))} écarté(s) (détail : lqorch auto).")


def cmd_auto(con, args):
    if args.action in ("hold", "release"):
        if args.issue is None:
            print("usage : lqorch auto hold N [raison] | lqorch auto release N")
            return 2
        if not task(con, args.issue):
            state, title = issue_state(args.issue)
            con.execute("INSERT INTO tasks(issue,title,status,priority,updated_at) VALUES(?,?,'WAITING_OWNER',50,?)",
                        (args.issue, title, iso()))
            con.commit()
        hold = args.action == "hold"
        fields = dict(auto_hold=1 if hold else 0, auto_seen_at=iso())
        if hold and args.reason:
            fields["blockers"] = args.reason
        update_task(con, args.issue, **fields)
        event(con, args.issue, "control", f"auto {args.action}" + (f" : {args.reason}" if args.reason else ""))
        print(f"#{args.issue} : " + ("retenu, jamais sélectionné automatiquement." if hold else
                                    "libéré ; sélectionnable à la prochaine activité GitHub du propriétaire."))
        return 0
    cur = con.execute("SELECT issue FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1").fetchone()
    local_ready = [r["issue"] for r in con.execute(
        "SELECT issue FROM tasks WHERE status IN ('READY','RUNNING','TESTING') ORDER BY priority, issue")]
    print("File automatique (#836) — examen à blanc, rien n'est armé ni commenté.")
    print(f"  sélection automatique : {'activée' if setting(con, 'auto_select')[0] else 'DÉSACTIVÉE'}"
          f" ; commentaire de prise en charge : {'oui' if setting(con, 'auto_comment')[0] else 'non'}")
    print(f"  en cours : {'#' + str(cur['issue']) if cur else 'aucune session'}")
    print(f"  file locale (passe avant la file automatique) : {', '.join('#' + str(i) for i in local_ready) or 'vide'}")
    eligible, excluded = auto_candidates(con, dry=True)
    if eligible is None:
        print("  " + excluded[0]["reason"])
        return 1
    print("Admissibles, dans l'ordre où ils seront pris :")
    for i, e in enumerate(eligible, 1):
        print(f"  {i}. #{e['issue']} P{e['rank']} {e['title'][:70]}"
              + (f" — repris : activité propriétaire {e['rearm']}" if e["rearm"] else " — nouveau"))
    if not eligible:
        print("  aucun")
    print("Écartés :")
    for e in sorted(excluded, key=lambda x: x["issue"] or 0):
        print(f"  #{e['issue']} {e['title'][:50]} — {e['reason']}")
    return 0


def cmd_queue(con, _):
    for t in con.execute("SELECT * FROM tasks ORDER BY CASE status WHEN 'RUNNING' THEN 0 WHEN 'TESTING' THEN 1 "
                         "WHEN 'READY' THEN 2 ELSE 3 END, priority, issue").fetchall():
        extra = f" (après {t['not_before']})" if t["not_before"] and t["not_before"] > iso() else ""
        print(f"#{t['issue']:<4} {t['status']:<13} p{t['priority']:<3} {t['title'][:60]:<60}{extra}"
              f"{'  deps ' + t['deps'] if t['deps'] else ''}")
        if t["next_action"]:
            print(f"       → {t['next_action'][:150]}")


def cmd_mode(con, args):
    mode = {"pause": "PAUSED", "resume": "RUNNING", "stop": "STOP_NOW" if getattr(args, "now", False) else "STOP"}[args.cmd]
    ctl_set(con, "mode", mode)
    if args.cmd == "resume":
        # Réarme le disjoncteur : c'est une décision du propriétaire, jamais automatique.
        ctl_set(con, "pause_reason", "")
        ctl_set(con, "consecutive_failures", 0)
    event(con, None, "control", f"{args.cmd}{' --now' if mode == 'STOP_NOW' else ''}")
    print(f"Mode : {mode}")
    if args.cmd == "resume":
        print("Si le service est arrêté : sudo systemctl start lodyquests-orch")


def cmd_config(con, args):
    if args.action in ("set", "unset"):
        if args.name not in SETTINGS:
            print(f"Réglage inconnu. Réglages : {', '.join(SETTINGS)}")
            return 2
        if args.action == "set":
            try:
                value = parse_setting(args.name, args.value)
            except (TypeError, ValueError) as exc:
                print(f"Valeur refusée : {exc}")
                return 2
        candidate = settings(con)
        candidate[args.name] = value if args.action == "set" else SETTINGS[args.name][1]
        error = policy_error(candidate)
        if error:
            print(f"Refusé : {error}")
            return 2
        ctl_set(con, "cfg." + args.name, value if args.action == "set" else "")
        event(con, None, "config", f"{args.action} {args.name}" + (f" = {value}" if args.action == "set" else ""))
        print("Enregistré ; appliqué par le superviseur avant la prochaine session (aucun redémarrage).")
    for name, (env, default, low, high, doc) in SETTINGS.items():
        value, origin = setting(con, name)
        print(f"{name:<30} {value!s:<8} ({origin}; défaut {default}, bornes {low}–{high})\n    {doc}")
    return 0


def cmd_reload(con, _):
    ctl_set(con, "reload_requested", iso())
    event(con, None, "control", "reload")
    print("Rechargement demandé : le superviseur relira le code installé à la fin de la session en cours "
          "(ou dans les 2 s s'il est inactif). Aucune session n'est coupée.")


def cmd_skip(con, args):
    if not task(con, args.issue):
        print("Ticket absent de la file.")
        return
    update_task(con, args.issue, status="BLOCKED", auto_hold=1, auto_seen_at=iso(),
                blockers=args.reason or "Mis de côté par le propriétaire (skip).")
    event(con, args.issue, "control", "skip")
    print(f"#{args.issue} → BLOCKED")


def cmd_add(con, args):
    t = task(con, args.issue)
    if t:
        update_task(con, args.issue, status="READY", attempts=0, not_before="", priority=args.prio,
                    auto_hold=0, auto_seen_at=iso(),
                    deps=args.deps if args.deps is not None else t["deps"],
                    note=args.note if args.note is not None else t["note"])
    else:
        state, title = issue_state(args.issue)
        con.execute("INSERT INTO tasks(issue,title,priority,deps,note,updated_at) VALUES(?,?,?,?,?,?)",
                    (args.issue, title, args.prio, args.deps or "", args.note or "", iso()))
        con.commit()
    event(con, args.issue, "control", "add")
    print(f"#{args.issue} → READY (priorité {args.prio})")


def cmd_set(con, args):
    allowed = {"status", "priority", "deps", "branch", "pr", "commit_sha", "summary", "tests", "blockers",
               "next_action", "note", "attempts", "title", "not_before"}
    if args.field not in allowed:
        print("Champ inconnu.")
        return
    fields = {args.field: args.value}
    if args.field == "status":
        # Un statut posé à la main vaut prise en compte de l'activité GitHub jusqu'ici (#836).
        fields["auto_seen_at"] = iso()
    update_task(con, args.issue, **fields)
    event(con, args.issue, "control", f"set {args.field}")
    print("ok")


def cmd_log(con, args):
    q = "SELECT * FROM events" + (" WHERE issue=?" if args.issue else "") + " ORDER BY id DESC LIMIT 40"
    for e in reversed(con.execute(q, (args.issue,) if args.issue else ()).fetchall()):
        print(f"{e['at']} #{e['issue'] if e['issue'] is not None else '-':<4} {e['kind']:<14} {e['message'][:160]}")


def cmd_sessions(con, _):
    for s in con.execute("SELECT * FROM sessions ORDER BY started_at DESC LIMIT 20").fetchall():
        print(f"{s['started_at']} → {s['ended_at'] or '…':<20} #{s['issue']:<4} {s['outcome'] or 'EN COURS':<11} "
              f"{(s['detail'] or '')[:100]}")


def main():
    p = argparse.ArgumentParser(prog="lqorch")
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("status", "queue", "pause", "resume", "run", "sessions", "reload"):
        sub.add_parser(name)
    s = sub.add_parser("stop")
    s.add_argument("--now", action="store_true")
    s = sub.add_parser("skip")
    s.add_argument("issue", type=int)
    s.add_argument("reason", nargs="?", default="")
    s = sub.add_parser("add")
    s.add_argument("issue", type=int)
    s.add_argument("--prio", type=int, default=50)
    s.add_argument("--deps", default=None)
    s.add_argument("--note", default=None)
    s = sub.add_parser("set")
    s.add_argument("issue", type=int)
    s.add_argument("field")
    s.add_argument("value")
    s = sub.add_parser("config")
    s.add_argument("action", nargs="?", choices=("show", "set", "unset"), default="show")
    s.add_argument("name", nargs="?")
    s.add_argument("value", nargs="?")
    s = sub.add_parser("auto")
    s.add_argument("action", nargs="?", choices=("show", "hold", "release"), default="show")
    s.add_argument("issue", type=int, nargs="?")
    s.add_argument("reason", nargs="?", default="")
    s = sub.add_parser("log")
    s.add_argument("issue", type=int, nargs="?")
    args = p.parse_args()
    con = db()
    if args.cmd == "run":
        sys.exit(supervise())
    if args.cmd == "config" and args.action != "show" and (not args.name or (args.action == "set") != bool(args.value)):
        p.error("usage : lqorch config [show | set NOM VALEUR | unset NOM]")
    code = {"status": cmd_status, "queue": cmd_queue, "pause": cmd_mode, "resume": cmd_mode, "stop": cmd_mode,
            "skip": cmd_skip, "add": cmd_add, "set": cmd_set, "log": cmd_log, "sessions": cmd_sessions,
            "config": cmd_config, "reload": cmd_reload, "auto": cmd_auto}[args.cmd](con, args)
    sys.exit(code or 0)


if __name__ == "__main__":
    main()
