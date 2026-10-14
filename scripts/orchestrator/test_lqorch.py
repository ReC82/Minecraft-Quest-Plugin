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
        for key in [k for k in self.env if k.startswith(("LQ_MAX", "LQ_MIN", "LQ_SESSION"))]:
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
