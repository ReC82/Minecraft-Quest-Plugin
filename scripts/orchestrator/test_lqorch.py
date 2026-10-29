"""Tests du superviseur lqorch (#380) : garde-fous configurables, quota fournisseur, disjoncteur,
STOP et rechargement. Tout tourne avec un faux `claude`, un faux `gh` et un STATE_DIR temporaire :
jamais l'état réel du superviseur, jamais de vraie session.

    python3 -m unittest scripts/orchestrator/test_lqorch.py
"""

import importlib.util
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
LQORCH = HERE / "lqorch.py"

FAKE_CLAUDE = textwrap.dedent("""\
    #!/usr/bin/env python3
    import json, os, sys, time
    mode = os.environ.get("FAKE_MODE", "report")
    def out(obj):
        print(json.dumps(obj), flush=True)
    if mode == "crash":
        sys.exit(1)
    if mode == "sleep":
        out({"type": "system"})
        time.sleep(120)
        sys.exit(0)
    util = float(os.environ.get("FAKE_UTIL", "0.3"))
    reset = int(time.time()) + 3600
    status = "rejected" if mode == "ratelimit" else "allowed"
    out({"type": "rate_limit_event", "rate_limit_info": {
        "status": status, "resetsAt": reset, "rateLimitType": "five_hour", "overageStatus": "rejected",
        "unifiedWindows": {"five_hour": {"utilization": util, "resetsAt": reset},
                           "seven_day": {"utilization": 0.4, "resetsAt": reset + 86400}}}})
    if mode == "ratelimit":
        out({"type": "result", "is_error": True, "result": "usage limit", "api_error_status": 429})
        sys.exit(1)
    with open(os.environ["LQ_STATUS_FILE"], "w") as fh:
        json.dump({"status": "DONE", "summary": "faux travail", "tests": "aucun"}, fh)
    out({"type": "result", "is_error": False, "result": "ok", "total_cost_usd": float(os.environ.get("FAKE_COST", "2.5"))})
""")

FAKE_GH = "#!/bin/sh\necho '{\"state\": \"open\", \"title\": \"faux ticket\"}'\n"


def write_exec(path, content):
    path.write_text(content)
    path.chmod(0o755)
    return path


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.state = root / "state"
        self.env = dict(os.environ, LQ_STATE_DIR=str(self.state), LQ_LAUNCH_DIR=str(root), LQ_WORKDIR=str(root),
                        LQ_CLAUDE=str(write_exec(root / "claude", FAKE_CLAUDE)),
                        LQ_GH=str(write_exec(root / "gh", FAKE_GH)), LQ_POLL="1")
        for key in [k for k in self.env if k.startswith(("LQ_MAX", "LQ_MIN", "LQ_SESSION", "LQ_SNAPSHOT"))]:
            del self.env[key]
        self.mod = self.load()
        self.con = self.mod.db()
        self.con.execute("INSERT INTO tasks(issue,title,priority,updated_at) VALUES(7,'faux',10,'')")
        self.con.commit()

    def tearDown(self):
        self.con.close()
        self.tmp.cleanup()

    def load(self):
        saved = dict(os.environ)
        os.environ.clear()
        os.environ.update(self.env)
        try:
            spec = importlib.util.spec_from_file_location(f"lqorch_{id(self)}", LQORCH)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
        finally:
            os.environ.clear()
            os.environ.update(saved)

    def cli(self, *args, **extra_env):
        return subprocess.run([sys.executable, str(LQORCH), *args], env=dict(self.env, **extra_env),
                              capture_output=True, text=True, timeout=60)

    def add_session(self, minutes_ago, outcome="COMPLETED", cost=1.0):
        ts = self.mod.iso(self.mod.datetime.fromtimestamp(time.time() - minutes_ago * 60, self.mod.timezone.utc))
        if ts[:10] != self.mod.iso()[:10]:
            self.skipTest("trop près de minuit UTC pour simuler des sessions du jour")
        self.con.execute("INSERT INTO sessions(id,issue,started_at,ended_at,outcome,cost_usd) VALUES(?,7,?,?,?,?)",
                         (f"s{minutes_ago}-{time.time()}", ts, ts, outcome, cost))
        self.con.commit()

    def run_one(self, **fake_env):
        saved = dict(os.environ)
        os.environ.update(fake_env)
        try:
            return self.mod.run_session(self.con, self.mod.task(self.con, 7))
        finally:
            os.environ.clear()
            os.environ.update(saved)


class SettingsTest(Base):
    def test_default_is_the_historical_24(self):
        self.assertEqual(self.mod.setting(self.con, "max_sessions_per_day"), (24, "défaut du code"))

    def test_env_then_config_override_and_invalid_values_are_ignored(self):
        mod = self.mod
        self.enterContext(mock.patch.dict(os.environ, LQ_MAX_SESSIONS_PER_DAY="30"))
        self.assertEqual(mod.setting(self.con, "max_sessions_per_day"), (30, "env LQ_MAX_SESSIONS_PER_DAY"))
        mod.ctl_set(self.con, "cfg.max_sessions_per_day", "40")
        self.assertEqual(mod.setting(self.con, "max_sessions_per_day"), (40, "lqorch config"))
        mod.ctl_set(self.con, "cfg.max_sessions_per_day", "9999")
        self.assertEqual(mod.setting(self.con, "max_sessions_per_day"), (30, "env LQ_MAX_SESSIONS_PER_DAY"))

    def test_cli_set_validates_and_unset_restores_default(self):
        self.assertEqual(self.cli("config", "set", "max_sessions_per_day", "abc").returncode, 2)
        self.assertEqual(self.cli("config", "set", "min_seconds_between_sessions", "1").returncode, 2)
        self.assertEqual(self.cli("config", "set", "nope", "1").returncode, 2)
        self.assertEqual(self.cli("config", "set", "max_sessions_per_day", "40").returncode, 0)
        self.assertIn("/ 40 — origine : lqorch config", self.cli("status").stdout)
        self.assertEqual(self.cli("config", "unset", "max_sessions_per_day").returncode, 0)
        self.assertIn("/ 24 — origine : défaut du code", self.cli("status").stdout)

    def test_unlimited_sessions_require_a_safety_limit(self):
        self.assertEqual(self.cli("config", "set", "max_five_hour_utilization", "1").returncode, 0)
        self.assertEqual(self.cli("config", "set", "max_seven_day_utilization", "1").returncode, 0)
        refused = self.cli("config", "set", "max_sessions_per_day", "0")
        self.assertEqual(refused.returncode, 2)
        self.assertIn("garde-fou", refused.stdout)
        self.assertEqual(self.cli("config", "set", "max_cost_usd_per_day", "50").returncode, 0)
        self.assertEqual(self.cli("config", "set", "max_sessions_per_day", "0").returncode, 0)
        # Retirer ensuite le dernier garde-fou est refusé aussi.
        self.assertEqual(self.cli("config", "set", "max_cost_usd_per_day", "0").returncode, 2)

    def test_unguarded_unlimited_from_env_falls_back_to_default_cap(self):
        self.enterContext(mock.patch.dict(os.environ, LQ_MAX_SESSIONS_PER_DAY="0", LQ_MAX_FIVE_HOUR_UTILIZATION="1",
                                          LQ_MAX_SEVEN_DAY_UTILIZATION="1"))
        mod = self.mod
        self.assertEqual(mod.setting(self.con, "max_sessions_per_day")[0], 0)
        for i in range(24):
            self.add_session(100 + i)
        self.assertIn("Plafond local de 24", mod.start_blocker(self.con)[0])


class GateTest(Base):
    def test_cap_reached_blocks_until_next_utc_day_then_raised_cap_allows(self):
        mod = self.mod
        mod.ctl_set(self.con, "cfg.max_sessions_per_day", "2")
        self.add_session(30)
        self.add_session(20)
        reason, until = mod.start_blocker(self.con)
        self.assertIn("Plafond local de 2", reason)
        self.assertTrue(until.endswith("T00:00:00Z"))
        mod.ctl_set(self.con, "cfg.max_sessions_per_day", "3")
        self.assertIsNone(mod.start_blocker(self.con))

    def test_cost_budget(self):
        self.mod.ctl_set(self.con, "cfg.max_cost_usd_per_day", "5")
        self.add_session(30, cost=3)
        self.assertIsNone(self.mod.start_blocker(self.con))
        self.add_session(20, cost=3)
        self.assertIn("Budget estimé", self.mod.start_blocker(self.con)[0])

    def test_provider_utilization_threshold_and_window_reset(self):
        reset = int(time.time()) + 1800
        usage = {"status": "allowed", "unifiedWindows": {"five_hour": {"utilization": 0.95, "resetsAt": reset}}}
        self.mod.ctl_set(self.con, "provider_usage", json.dumps(usage))
        reason, until = self.mod.start_blocker(self.con)
        self.assertIn("5 h à 95%", reason)
        self.assertGreater(until, self.mod.iso())
        usage["unifiedWindows"]["five_hour"]["resetsAt"] = int(time.time()) - 10
        self.mod.ctl_set(self.con, "provider_usage", json.dumps(usage))
        self.assertIsNone(self.mod.start_blocker(self.con))

    def test_provider_limit_is_never_overridden_by_a_raised_cap(self):
        self.mod.ctl_set(self.con, "cfg.max_sessions_per_day", "500")
        self.mod.ctl_set(self.con, "quota_until", self.mod.iso(
            self.mod.datetime.fromtimestamp(time.time() + 600, self.mod.timezone.utc)))
        self.assertIn("Limite fournisseur", self.mod.start_blocker(self.con)[0])

    def test_min_gap_and_pause(self):
        self.add_session(0)
        self.assertIn("Écart minimal", self.mod.start_blocker(self.con)[0])
        self.mod.ctl_set(self.con, "mode", "PAUSED")
        self.assertIn("Pause", self.mod.start_blocker(self.con)[0])


class SessionTest(Base):
    def test_completed_session_records_cost_and_provider_usage(self):
        self.assertEqual(self.run_one(FAKE_UTIL="0.42", FAKE_COST="3.25"), "COMPLETED")
        self.assertEqual(self.mod.task(self.con, 7)["status"], "DONE")
        self.assertAlmostEqual(self.mod.cost_today(self.con), 3.25)
        usage = self.mod.provider_usage(self.con)
        self.assertEqual(usage["unifiedWindows"]["five_hour"]["utilization"], 0.42)
        self.assertIn("observed_at", usage)
        status = self.cli("status").stdout
        self.assertIn("fenêtre 5 h : 42%", status)
        self.assertIn("quota restant réel n'est pas connu", status)

    def test_rate_limit_waits_for_announced_reset_and_does_not_count_an_attempt(self):
        self.assertEqual(self.run_one(FAKE_MODE="ratelimit"), "RATE_LIMIT")
        self.assertGreater(self.mod.ctl_get(self.con, "quota_until"), self.mod.iso())
        self.assertEqual(self.mod.task(self.con, 7)["attempts"], 0)
        self.assertEqual(self.mod.ctl_get(self.con, "consecutive_failures", "0"), "0")
        self.assertIn("Limite fournisseur", self.mod.start_blocker(self.con)[0])

    def test_breaker_pauses_after_consecutive_failures_and_resume_rearms(self):
        self.mod.ctl_set(self.con, "cfg.max_consecutive_failures", "2")
        self.mod.ctl_set(self.con, "cfg.max_attempts", "10")
        self.assertEqual(self.run_one(FAKE_MODE="crash"), "CRASH")
        self.assertEqual(self.mod.ctl_get(self.con, "mode", "RUNNING"), "RUNNING")
        self.run_one(FAKE_MODE="crash")
        self.assertEqual(self.mod.ctl_get(self.con, "mode"), "PAUSED")
        self.assertIn("Disjoncteur", self.mod.start_blocker(self.con)[0])
        self.assertEqual(self.cli("resume").returncode, 0)
        self.assertEqual(self.mod.ctl_get(self.con, "consecutive_failures"), "0")
        self.assertEqual(self.mod.ctl_get(self.con, "pause_reason"), "")

    def test_success_resets_breaker(self):
        self.run_one(FAKE_MODE="crash")
        self.mod.update_task(self.con, 7, not_before="")
        self.run_one()
        self.assertEqual(self.mod.ctl_get(self.con, "consecutive_failures"), "0")


class SupervisorTest(Base):
    def start(self):
        proc = subprocess.Popen([sys.executable, str(LQORCH), "run"], env=self.env,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.addCleanup(proc.stdout.close)
        self.addCleanup(lambda: proc.poll() is None and (proc.kill(), proc.wait()))
        return proc

    def wait_for(self, predicate, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            con = sqlite3.connect(self.state / "state.db")
            con.row_factory = sqlite3.Row
            try:
                if predicate(con):
                    return
            finally:
                con.close()
            time.sleep(0.3)
        self.fail("condition jamais atteinte")

    @staticmethod
    def ctl(con, key):
        row = con.execute("SELECT value FROM control WHERE key=?", (key,)).fetchone()
        return row[0] if row else ""

    def test_cap_reached_then_raised_runs_next_session_then_stop(self):
        self.mod.ctl_set(self.con, "cfg.max_sessions_per_day", "1")
        self.add_session(5)
        proc = self.start()
        self.wait_for(lambda c: "Plafond local de 1" in self.ctl(c, "idle_reason"))
        self.assertEqual(self.cli("config", "set", "max_sessions_per_day", "2").returncode, 0)
        self.wait_for(lambda c: c.execute("SELECT status FROM tasks WHERE issue=7").fetchone()[0] == "DONE")
        self.wait_for(lambda c: "Plafond local de 2" in self.ctl(c, "idle_reason"))
        self.assertEqual(self.cli("stop").returncode, 0)
        self.assertEqual(proc.wait(timeout=15), 0)
        # Relancé (comme par systemd), il refuse de travailler tant que « resume » n'a pas été demandé.
        self.assertEqual(self.start().wait(timeout=15), 0)

    def test_stop_now_interrupts_session_without_counting_an_attempt(self):
        self.env["FAKE_MODE"] = "sleep"
        proc = self.start()
        self.wait_for(lambda c: c.execute("SELECT COUNT(*) FROM sessions WHERE ended_at IS NULL").fetchone()[0] == 1)
        self.assertEqual(self.cli("stop", "--now").returncode, 0)
        self.assertEqual(proc.wait(timeout=30), 0)
        self.wait_for(lambda c: c.execute("SELECT outcome FROM sessions").fetchone()[0] == "STOPPED")
        row = self.mod.task(self.con, 7)
        self.assertEqual((row["status"], row["attempts"]), ("RUNNING", 0))

    def test_reload_reexecs_between_sessions_keeping_pid_and_lock(self):
        self.mod.update_task(self.con, 7, status="DONE")
        self.mod.ctl_set(self.con, "last_triage", time.time())
        proc = self.start()
        self.wait_for(lambda c: self.ctl(c, "supervisor_version"))
        self.mod.ctl_set(self.con, "supervisor_version", "ancien")
        self.assertEqual(self.cli("reload").returncode, 0)
        self.wait_for(lambda c: self.ctl(c, "supervisor_version") not in ("", "ancien")
                      and not self.ctl(c, "reload_requested"))
        self.assertIsNone(proc.poll())
        self.assertEqual(self.mod.ctl_get(self.con, "supervisor_pid"), str(proc.pid))
        # Le verrou est toujours tenu : un second superviseur refuse de démarrer.
        second = self.start()
        self.assertEqual(second.wait(timeout=15), 0)
        self.assertIn("Un autre superviseur", second.stdout.read())
        self.cli("stop")
        self.assertEqual(proc.wait(timeout=15), 0)


if __name__ == "__main__":
    unittest.main()


class FakeGitHub:
    """Faux GitHub en mémoire pour la file automatique (#836) : issues, commentaires, PR ouvertes."""

    def __init__(self):
        self.issues, self.comments, self.prs, self.closed, self.posted, self.paths = {}, {}, [], set(), [], []

    def issue(self, n, *labels, updated="2026-10-25T07:00:00Z", body=""):
        self.issues[n] = {"number": n, "title": f"ticket {n}", "body": body, "updated_at": updated,
                          "labels": [{"name": lab} for lab in labels]}

    def comment(self, n, at, body="retour du propriétaire"):
        self.comments.setdefault(n, []).append({"created_at": at, "body": body})
        self.issues[n]["updated_at"] = max(self.issues[n]["updated_at"], at)

    def gh_json(self, path):
        self.paths.append(path)
        base = path.split("?")[0]
        page = int(path.split("page=")[-1]) if "page=" in path else 1
        if page > 1:
            return []
        if base.endswith("/issues"):
            assert "state=open" in path and "labels=agent:auto" in path
            return [i for n, i in sorted(self.issues.items()) if n not in self.closed]
        if base.endswith("/pulls"):
            return self.prs
        if base.endswith("/comments"):
            return self.comments.get(int(base.split("/")[-2]), [])
        n = int(base.split("/")[-1])
        if n in self.issues or n in self.closed:
            return {"state": "closed" if n in self.closed else "open", "title": f"ticket {n}"}
        return {"state": "open", "title": f"ticket {n}"}


class AutoSelectTest(Base):
    def setUp(self):
        super().setUp()
        self.con.execute("DELETE FROM tasks")
        self.con.commit()
        self.gh = FakeGitHub()
        for target, fake in (("gh_json", self.gh.gh_json),
                             ("gh_comment", lambda n, body: self.gh.posted.append((n, body)) or True)):
            patcher = mock.patch.object(self.mod, target, fake)
            patcher.start()
            self.addCleanup(patcher.stop)

    def local(self, n, status, ended="2026-10-25T06:00:00Z", **fields):
        self.con.execute("INSERT INTO tasks(issue,title,status,priority,updated_at) VALUES(?,?,?,50,?)",
                         (n, f"ticket {n}", status, ended))
        self.con.execute("INSERT INTO sessions(id,issue,started_at,ended_at,outcome) VALUES(?,?,?,?,'COMPLETED')",
                         (f"s{n}", n, "2026-10-25T05:00:00Z", ended))
        self.con.commit()
        if fields:
            self.mod.update_task(self.con, n, **fields)

    def picked(self):
        t = self.mod.auto_select(self.con)
        return t["issue"] if t else None

    def test_new_labelled_ticket_is_armed_ready_and_announced(self):
        self.gh.issue(734, "agent:auto", "priority:P1")
        t = self.mod.auto_select(self.con)
        self.assertEqual((t["issue"], t["status"], t["priority"]), (734, "READY", 10))
        self.assertIn("priority:P1", t["next_action"])
        self.assertEqual([n for n, _ in self.gh.posted], [734])
        self.assertIn("<!-- lqorch", self.gh.posted[0][1])
        # Armé une seule fois : la file locale le porte désormais, la sélection automatique ne le rejoue pas.
        self.assertIsNone(self.picked())
        self.assertEqual(self.mod.next_task(self.con)["issue"], 734)

    def test_priority_p0_to_p3_then_oldest_first(self):
        for n, prio in ((50, "P3"), (40, "P1"), (30, "P2"), (45, "P0"), (20, "P1")):
            self.gh.issue(n, "agent:auto", f"priority:{prio}")
        eligible, _ = self.mod.auto_candidates(self.con)
        self.assertEqual([e["issue"] for e in eligible], [45, 20, 40, 30, 50])

    def test_missing_or_double_priority_and_missing_auto_label_are_excluded(self):
        self.gh.issue(1, "agent:auto")
        self.gh.issue(2, "agent:auto", "priority:P0", "priority:P2")
        self.gh.issue(3, "priority:P0", "bug")
        self.gh.issue(4, "agent:auto", "priority:P2", "blocked")
        eligible, excluded = self.mod.auto_candidates(self.con)
        self.assertEqual(eligible, [])
        self.assertEqual({e["issue"] for e in excluded}, {1, 2, 3, 4})
        self.assertIsNone(self.picked())
        self.assertEqual(self.gh.posted, [])

    def test_closed_ticket_is_never_selected(self):
        self.gh.issue(9, "agent:auto", "priority:P0")
        self.gh.closed.add(9)
        self.assertIsNone(self.picked())

    def test_waiting_owner_without_new_activity_is_excluded(self):
        self.gh.issue(54, "agent:auto", "priority:P0", updated="2026-10-25T05:30:00Z")
        self.local(54, "WAITING_OWNER")
        self.assertIsNone(self.picked())

    def test_comments_from_sessions_never_rearm(self):
        self.gh.issue(56, "agent:auto", "priority:P0", updated="2026-10-25T05:00:00Z")
        self.local(56, "WAITING_OWNER")
        self.gh.comment(56, "2026-10-25T06:00:01Z", "rapport posté une seconde après la fin de session")
        self.gh.comment(56, "2026-10-25T09:00:00Z", "commentaire interactif\n<!-- lqorch-session x -->")
        self.assertIsNone(self.picked())
        # Activité déjà examinée : le tour suivant ne relit même plus les commentaires.
        calls = len(self.gh.paths)
        self.assertIsNone(self.picked())
        self.assertFalse(any("/comments" in p for p in self.gh.paths[calls:]))

    def test_owner_decision_after_last_session_rearms_once(self):
        self.gh.issue(829, "agent:auto", "priority:P1", updated="2026-10-25T05:00:00Z")
        self.local(829, "DONE", next_action="Propriétaire : TC-436")
        self.gh.comment(829, "2026-10-25T07:48:29Z", "## Décisions propriétaire — D1 …")
        t = self.mod.auto_select(self.con)
        self.assertEqual((t["issue"], t["status"]), (829, "READY"))
        self.assertIn("2026-10-25T07:48:29Z", t["next_action"])
        self.assertIn("TC-436", t["next_action"])
        # La session rend son rapport : la décision est prise en compte, plus de reprise en boucle.
        self.mod.update_task(self.con, 829, status="WAITING_OWNER", auto_seen_at=self.mod.iso())
        self.assertIsNone(self.picked())

    def test_owner_comment_during_another_tickets_session_rearms(self):
        self.gh.issue(734, "agent:auto", "priority:P1", updated="2026-10-25T05:00:00Z")
        self.local(734, "WAITING_OWNER")
        self.con.execute("INSERT INTO sessions(id,issue,started_at,ended_at,outcome) VALUES('s881',881,"
                         "'2026-10-29T15:31:44Z','2026-10-29T16:07:54Z','COMPLETED')")
        self.con.commit()
        self.gh.comment(734, "2026-10-29T16:00:20Z", "## Décision propriétaire — 29/10")
        self.assertEqual(self.picked(), 734)

    def test_held_ticket_is_excluded_even_with_owner_activity_until_released(self):
        self.gh.issue(54, "agent:auto", "priority:P0", updated="2026-10-25T05:00:00Z")
        self.local(54, "WAITING_OWNER")
        self.assertEqual(self.cli("auto", "hold", "54", "recette TC-327 E").returncode, 0)
        self.gh.comment(54, "2099-01-01T00:00:00Z")
        self.assertIsNone(self.picked())
        self.assertEqual(self.cli("auto", "release", "54").returncode, 0)
        self.assertEqual(self.picked(), 54)

    def test_dependencies_from_local_state_and_ticket_body(self):
        self.gh.issue(10, "agent:auto", "priority:P0", body="Contexte\n- **Dépend de** #900 et #901\n")
        self.gh.issue(11, "agent:auto", "priority:P1")
        self.gh.issue(900, "bug")
        self.gh.closed.add(901)
        self.assertEqual(self.picked(), 11)
        self.gh.closed.add(900)
        self.mod.update_task(self.con, 11, status="WAITING_OWNER", auto_seen_at="2099-01-01T00:00:00Z")
        self.assertEqual(self.picked(), 10)
        self.assertEqual(self.mod.task(self.con, 10)["deps"], "900,901")

    def test_dependency_on_waiting_owner_ticket_is_met(self):
        self.gh.issue(734, "agent:auto", "priority:P1")
        self.local(421, "WAITING_OWNER")
        self.con.execute("INSERT INTO tasks(issue,title,status,deps,updated_at) VALUES(734,'t','DONE','421',"
                         "'2026-10-25T05:00:00Z')")
        self.con.commit()
        self.gh.comment(734, "2026-10-25T07:51:13Z")
        self.assertEqual(self.picked(), 734)

    def test_open_pull_request_on_ticket_branch_is_concurrent_work(self):
        self.gh.issue(565, "agent:auto", "priority:P2")
        self.gh.prs = [{"number": 900, "head": {"ref": "feature/565-portails-ux"}}]
        self.assertIsNone(self.picked())
        self.gh.prs = [{"number": 901, "head": {"ref": "feature/5650-autre"}}]
        self.assertEqual(self.picked(), 565)

    def test_failed_ticket_and_local_queue_are_left_to_their_own_rules(self):
        self.gh.issue(1, "agent:auto", "priority:P0")
        self.gh.issue(2, "agent:auto", "priority:P0")
        self.local(1, "FAILED")
        self.local(2, "RUNNING")
        self.gh.comment(1, "2099-01-01T00:00:00Z")
        self.assertIsNone(self.picked())

    def test_disabled_selection_and_unreachable_github(self):
        self.gh.issue(7, "agent:auto", "priority:P0")
        self.mod.ctl_set(self.con, "cfg.auto_select", 0)
        self.assertIsNone(self.picked())
        self.mod.ctl_set(self.con, "cfg.auto_select", 1)
        with mock.patch.object(self.mod, "gh_json", lambda path: None):
            self.assertIsNone(self.picked())

    def test_empty_queue_runs_selected_ticket_then_moves_to_next_without_repeat(self):
        self.gh.issue(734, "agent:auto", "priority:P1")
        self.gh.issue(829, "agent:auto", "priority:P1")
        self.gh.issue(565, "agent:auto", "priority:P2")
        done = []
        for _ in range(4):
            t = self.mod.next_task(self.con) or self.mod.auto_select(self.con)
            if t is None:
                break
            self.assertEqual(self.mod.run_session(self.con, t), "COMPLETED")
            done.append(t["issue"])
        self.assertEqual(done, [734, 829, 565])
        self.assertEqual([n for n, _ in self.gh.posted], [734, 829, 565])
        # Les rapports des sessions (commentaires pendant la session) ne relancent rien.
        for n in done:
            self.gh.comment(n, self.mod.iso())
        self.assertIsNone(self.mod.next_task(self.con) or self.mod.auto_select(self.con))

    def test_dry_run_view_arms_nothing(self):
        self.gh.issue(7, "agent:auto", "priority:P0")
        with mock.patch.object(self.mod, "gh_json", self.gh.gh_json):
            self.assertEqual(self.mod.cmd_auto(self.con, mock.Mock(action="show", issue=None, reason="")), 0)
        self.assertIsNone(self.mod.task(self.con, 7))
        self.assertEqual(self.gh.posted, [])


class SnapshotTest(Base):
    """Instantané lu par la vue PlugAdmin (#907) : local, sans secret, jamais l'instantané réel."""

    def setUp(self):
        super().setUp()
        self.con.execute("DELETE FROM tasks")
        self.con.commit()
        self.gh = FakeGitHub()
        for target, fake in (("gh_json", self.gh.gh_json), ("gh_comment", lambda n, body: True)):
            patcher = mock.patch.object(self.mod, target, fake)
            patcher.start()
            self.addCleanup(patcher.stop)

    local = AutoSelectTest.local
    picked = AutoSelectTest.picked

    def test_test_state_dir_never_targets_the_real_snapshot(self):
        self.assertEqual(self.mod.SNAPSHOT_PATH, self.state / "queue.json")

    def test_written_atomically_readable_and_without_logs_or_pids(self):
        self.local(12, "WAITING_OWNER", blockers="recette TC-1")
        self.con.execute("UPDATE sessions SET log_path='/home/x/secret.jsonl', pid=4242, cost_usd=9")
        self.con.commit()
        self.assertTrue(self.mod.write_snapshot(self.con, force=True))
        path = self.mod.SNAPSHOT_PATH
        self.assertEqual(oct(path.stat().st_mode & 0o777), "0o644")
        self.assertEqual([p.name for p in path.parent.glob(".queue.json*")], [])
        raw = path.read_text(encoding="utf-8")
        for leak in ("secret.jsonl", "4242", "log_path", "cost"):
            self.assertNotIn(leak, raw)
        data = json.loads(raw)
        self.assertEqual(data["version"], 1)
        ticket = next(t for t in data["tasks"] if t["issue"] == 12)
        self.assertEqual((ticket["status"], ticket["blockers"]), ("WAITING_OWNER", "recette TC-1"))
        self.assertEqual(data["sessions"][0]["issue"], 12)
        self.assertEqual(self.gh.paths, [], "l'instantané ne doit jamais appeler GitHub")

    def test_throttled_unless_forced(self):
        self.assertTrue(self.mod.write_snapshot(self.con, force=True))
        self.assertFalse(self.mod.write_snapshot(self.con))
        self.assertTrue(self.mod.write_snapshot(self.con, force=True))

    def test_auto_examination_keeps_exclusion_reasons_and_known_rank(self):
        self.gh.issue(4, "agent:auto", "priority:P2", "blocked")
        self.gh.issue(5, "agent:auto")
        self.gh.issue(6, "agent:auto", "priority:P1")
        self.assertEqual(self.picked(), 6)
        auto = self.mod.snapshot(self.con)["auto"]
        self.assertTrue(auto["examined_at"])
        self.assertEqual([(e["issue"], e["rank"]) for e in auto["eligible"]], [(6, 1)])
        excluded = {e["issue"]: e for e in auto["excluded"]}
        self.assertEqual((excluded[4]["rank"], excluded[4]["reason"]), (2, "label blocked"))
        self.assertIsNone(excluded[5]["rank"])

    def test_unwritable_target_is_reported_once_without_stopping(self):
        missing = self.state / "absent" / "queue.json"
        with mock.patch.object(self.mod, "SNAPSHOT_PATH", missing):
            self.assertFalse(self.mod.write_snapshot(self.con, force=True))
            self.assertFalse(self.mod.write_snapshot(self.con, force=True))
        self.assertIn("absent", self.mod.ctl_get(self.con, "snapshot_error"))
        events = self.con.execute("SELECT COUNT(*) c FROM events WHERE message LIKE 'Instantané PlugAdmin%'")
        self.assertEqual(events.fetchone()["c"], 1)
        self.assertTrue(self.mod.write_snapshot(self.con, force=True))
        self.assertEqual(self.mod.ctl_get(self.con, "snapshot_error"), "")

    def test_reports_are_matched_on_the_exact_ticket_number(self):
        names = ["2026-10-29_2000_vue-lqorch-907.md", "2026-10-28_1000_fix-9070-autre.md",
                 "2026-10-27_0900_907-premier-lot.md", "2026-10-26_0900_ticket-90.md"]
        self.assertEqual(self.mod.reports_for(907, names),
                         ["2026-10-29_2000_vue-lqorch-907.md", "2026-10-27_0900_907-premier-lot.md"])
        self.assertEqual(self.mod.reports_for(0, names), [])

    def test_cli_prints_and_writes(self):
        out = self.cli("snapshot")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(json.loads(out.stdout)["version"], 1)
        out = self.cli("snapshot", "--write")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertTrue((self.state / "queue.json").exists())
