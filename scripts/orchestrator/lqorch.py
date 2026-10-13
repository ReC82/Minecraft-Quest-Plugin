#!/usr/bin/env python3
"""LodyQuests — superviseur de sessions Claude Code (développement autonome entre sessions).

Une session Claude n'est qu'un ouvrier : elle traite UN ticket puis se termine. Le superviseur,
lui, survit aux sessions. Il garde l'état dans une base SQLite, choisit le travail suivant,
lance `claude -p` en mode non interactif avec un contexte reconstruit, surveille son activité,
classe la façon dont elle s'est terminée, et enchaîne.

Commandes :
  lqorch status            état du superviseur, session en cours, quota
  lqorch queue             prochains tickets
  lqorch pause | resume    ne plus démarrer / reprendre
  lqorch stop [--now]      arrêter (après la session en cours, ou tout de suite)
  lqorch skip N [raison]   mettre le ticket N de côté (BLOCKED) et passer au suivant
  lqorch add N [--prio P] [--deps 35,321] [--note "…"]   ajouter / remettre en file
  lqorch set N champ valeur                               corriger un champ d'un ticket
  lqorch log [N]           derniers événements
  lqorch sessions          dernières sessions
  lqorch run               boucle du superviseur (service systemd)

Aucune dépendance hors bibliothèque standard. Aucun secret n'est lu ni écrit ici : Claude Code
et `gh` utilisent leurs propres identifiants.
"""

import argparse
import fcntl
import json
import os
import queue
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

SESSION_MAX_SECONDS = int(os.environ.get("LQ_SESSION_MAX", 4 * 3600))
SESSION_IDLE_SECONDS = int(os.environ.get("LQ_SESSION_IDLE", 75 * 60))
MAX_ATTEMPTS = int(os.environ.get("LQ_MAX_ATTEMPTS", 3))
MAX_SESSIONS_PER_DAY = int(os.environ.get("LQ_MAX_SESSIONS_PER_DAY", 24))
POLL_SECONDS = int(os.environ.get("LQ_POLL", 30))
TRIAGE_EVERY_SECONDS = int(os.environ.get("LQ_TRIAGE_EVERY", 6 * 3600))
BACKOFF_SCALE = float(os.environ.get("LQ_BACKOFF_SCALE", 1))
QUOTA_MARGIN_SECONDS = int(os.environ.get("LQ_QUOTA_MARGIN", 120))

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
    return con


def ctl_get(con, key, default=""):
    r = con.execute("SELECT value FROM control WHERE key=?", (key,)).fetchone()
    return r["value"] if r else default


def ctl_set(con, key, value):
    con.execute("INSERT INTO control(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, str(value)))
    con.commit()


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
        out = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=60)
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


# ---------------------------------------------------------------- quota

def wait_for_quota(con):
    """Respecte la limite du fournisseur : jamais de relance avant la remise à zéro annoncée."""
    until = ctl_get(con, "quota_until")
    return bool(until and until > iso())


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
            elif time.time() - started > SESSION_MAX_SECONDS:
                reason = f"Durée maximale dépassée ({SESSION_MAX_SECONDS // 60} min)."
            elif time.time() - last_out > SESSION_IDLE_SECONDS:
                reason = f"Aucune activité depuis {SESSION_IDLE_SECONDS} s."
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
    # Pas de rapport exploitable : tentative comptée, délai progressif, abandon après MAX_ATTEMPTS.
    attempts = t["attempts"]
    if attempts >= MAX_ATTEMPTS:
        update_task(con, issue, status="FAILED", blockers=f"{attempts} sessions sans rapport : {outcome} — {detail[:300]}",
                    next_action="Examiner les journaux de session, puis `lqorch add` pour relancer.")
        event(con, issue, "failed", f"Abandon après {attempts} tentatives.")
        return
    delay = {"TRANSIENT": 300, "CRASH": 900, "TIMEOUT": 600, "ERROR": 1800}.get(outcome, 900) * attempts * BACKOFF_SCALE
    update_task(con, issue, status="RUNNING" if outcome in ("TIMEOUT", "COMPLETED") else "READY",
                not_before=iso(datetime.fromtimestamp(time.time() + delay, timezone.utc)),
                next_action=f"Reprendre (dernière session : {outcome}).")


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
    """Attente qui rend la main dès qu'un arrêt ou une reprise est demandé."""
    end = time.time() + seconds
    start_mode = ctl_get(con, "mode", "RUNNING")
    while time.time() < end:
        if ctl_get(con, "mode", "RUNNING") != start_mode:
            return
        time.sleep(min(2, max(0.1, end - time.time())))


def sessions_today(con):
    day = iso()[:10]
    return con.execute("SELECT COUNT(*) c FROM sessions WHERE started_at LIKE ?", (day + "%",)).fetchone()["c"]


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
    event(con, None, "supervisor", "Superviseur démarré.")
    while True:
        mode = ctl_get(con, "mode", "RUNNING")
        if mode in ("STOP", "STOP_NOW"):
            event(con, None, "supervisor", "Arrêt demandé : superviseur arrêté.")
            ctl_set(con, "supervisor_pid", "")
            return 0
        if mode == "PAUSED" or wait_for_quota(con):
            pause_for(con, POLL_SECONDS)
            continue
        if sessions_today(con) >= MAX_SESSIONS_PER_DAY:
            ctl_set(con, "idle_reason", f"Plafond de {MAX_SESSIONS_PER_DAY} sessions par jour atteint.")
            pause_for(con, 600)
            continue
        t = next_task(con)
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

def cmd_status(con, _):
    mode = ctl_get(con, "mode", "RUNNING")
    pid = ctl_get(con, "supervisor_pid")
    alive = bool(pid) and Path(f"/proc/{pid}").exists()
    print(f"Mode : {mode} — superviseur {'actif (pid ' + pid + ')' if alive else 'arrêté'}")
    q = ctl_get(con, "quota_until")
    if q and q > iso():
        print(f"Limite fournisseur : reprise après {q}")
    if ctl_get(con, "idle_reason"):
        print(f"Inactif : {ctl_get(con, 'idle_reason')}")
    cur = con.execute("SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1").fetchone()
    if cur:
        t = task(con, cur["issue"])
        print(f"Session en cours : #{cur['issue']} {t['title'] if t else ''} — depuis {cur['started_at']}, "
              f"dernière activité {t['last_activity'] if t else '?'} — journal {cur['log_path']}")
    counts = con.execute("SELECT status, COUNT(*) c FROM tasks GROUP BY status").fetchall()
    print("Tickets : " + ", ".join(f"{r['status']} {r['c']}" for r in counts))
    day = con.execute("SELECT COUNT(*) c, COALESCE(SUM(cost_usd),0) s FROM sessions WHERE started_at LIKE ?",
                      (iso()[:10] + "%",)).fetchone()
    print(f"Sessions aujourd'hui : {day['c']}")


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
    event(con, None, "control", f"{args.cmd}{' --now' if mode == 'STOP_NOW' else ''}")
    print(f"Mode : {mode}")
    if args.cmd == "resume":
        print("Si le service est arrêté : sudo systemctl start lodyquests-orch")


def cmd_skip(con, args):
    if not task(con, args.issue):
        print("Ticket absent de la file.")
        return
    update_task(con, args.issue, status="BLOCKED", blockers=args.reason or "Mis de côté par le propriétaire (skip).")
    event(con, args.issue, "control", "skip")
    print(f"#{args.issue} → BLOCKED")


def cmd_add(con, args):
    t = task(con, args.issue)
    if t:
        update_task(con, args.issue, status="READY", attempts=0, not_before="", priority=args.prio,
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
    update_task(con, args.issue, **{args.field: args.value})
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
    for name in ("status", "queue", "pause", "resume", "run", "sessions"):
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
    s = sub.add_parser("log")
    s.add_argument("issue", type=int, nargs="?")
    args = p.parse_args()
    con = db()
    if args.cmd == "run":
        sys.exit(supervise())
    {"status": cmd_status, "queue": cmd_queue, "pause": cmd_mode, "resume": cmd_mode, "stop": cmd_mode,
     "skip": cmd_skip, "add": cmd_add, "set": cmd_set, "log": cmd_log, "sessions": cmd_sessions}[args.cmd](con, args)


if __name__ == "__main__":
    main()
