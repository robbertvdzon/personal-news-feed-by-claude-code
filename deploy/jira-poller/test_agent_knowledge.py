"""Unit-tests voor de /agent-knowledge endpoints. Mockt psycopg —
draaien zonder DB: `python3 -m unittest test_agent_knowledge`."""

import json
import os
import sys
import unittest
from unittest.mock import patch, MagicMock

os.environ.setdefault("FACTORY_DATABASE_URL", "postgresql://test/test")
os.environ.setdefault("JIRA_BASE_URL", "https://example.atlassian.net")
os.environ.setdefault("JIRA_EMAIL", "test@example.com")
os.environ.setdefault("JIRA_API_KEY", "test-key")
os.environ.setdefault("JIRA_PROJECT", "TEST")
os.environ.setdefault("REPO_URL", "https://example.com/repo.git")

sys.path.insert(0, os.path.dirname(__file__))
import poller  # noqa: E402


class _FakeCursor:
    """Capture SQL + voer fetchone/fetchall met de gegeven dataset uit."""
    def __init__(self, fetchall_data=None):
        self.executed: list[tuple] = []
        self._fetch = fetchall_data or []
    def execute(self, sql, params=()):
        self.executed.append((sql, params))
    def fetchall(self):
        return self._fetch
    def fetchone(self):
        return self._fetch[0] if self._fetch else None
    def __enter__(self): return self
    def __exit__(self, *a): return False


class _FakeConn:
    def __init__(self, fetchall_data=None):
        self.cur = _FakeCursor(fetchall_data)
        self.commits = 0
    def cursor(self): return self.cur
    def commit(self): self.commits += 1
    def __enter__(self): return self
    def __exit__(self, *a): return False


def _mock_connect(conn):
    return lambda *_a, **_kw: conn


class AgentKnowledgeGetTests(unittest.TestCase):
    def test_get_returns_tips_json(self):
        from datetime import datetime
        rows = [
            ("login", "tab-order", "Tab 5x naar register-knop",
             datetime(2026, 5, 16, 12, 0, 0), "KAN-42"),
            ("screenshots", "fullpage", "Gebruik --full-page",
             datetime(2026, 5, 16, 12, 5, 0), "KAN-43"),
        ]
        conn = _FakeConn(fetchall_data=rows)
        with poller.app.test_client() as c, \
             patch.object(poller, "psycopg") as pmock:
            pmock.connect = _mock_connect(conn)
            r = c.get("/agent-knowledge?role=tester")
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertEqual(body["role"], "tester")
        self.assertEqual(len(body["tips"]), 2)
        self.assertEqual(body["tips"][0]["key"], "tab-order")
        self.assertEqual(body["tips"][0]["updated_by_story"], "KAN-42")

    def test_get_returns_markdown_when_format_md(self):
        from datetime import datetime
        rows = [("login", "tab-order", "Tip A",
                 datetime(2026, 5, 16), "KAN-42")]
        conn = _FakeConn(fetchall_data=rows)
        with poller.app.test_client() as c, \
             patch.object(poller, "psycopg") as pmock:
            pmock.connect = _mock_connect(conn)
            r = c.get("/agent-knowledge?role=tester&format=md")
        self.assertEqual(r.status_code, 200)
        self.assertIn("text/markdown", r.headers["Content-Type"])
        body = r.data.decode()
        self.assertIn("# Tips & tricks voor de tester-agent", body)
        self.assertIn("## login", body)
        self.assertIn("### tab-order", body)
        self.assertIn("Tip A", body)

    def test_get_returns_placeholder_when_empty(self):
        conn = _FakeConn(fetchall_data=[])
        with poller.app.test_client() as c, \
             patch.object(poller, "psycopg") as pmock:
            pmock.connect = _mock_connect(conn)
            r = c.get("/agent-knowledge?role=refiner&format=md")
        self.assertEqual(r.status_code, 200)
        self.assertIn("Nog geen tips opgeslagen", r.data.decode())

    def test_get_rejects_unknown_role(self):
        with poller.app.test_client() as c:
            r = c.get("/agent-knowledge?role=admin")
        self.assertEqual(r.status_code, 400)

    def test_get_rejects_missing_role(self):
        with poller.app.test_client() as c:
            r = c.get("/agent-knowledge")
        self.assertEqual(r.status_code, 400)


class AgentKnowledgeUpdateTests(unittest.TestCase):
    def _post(self, body, fetchall_data=None):
        conn = _FakeConn(fetchall_data=fetchall_data)
        with poller.app.test_client() as c, \
             patch.object(poller, "psycopg") as pmock:
            pmock.connect = _mock_connect(conn)
            r = c.post("/agent-knowledge/update",
                       json=body)
        return r, conn

    def test_upsert_single_tip(self):
        r, conn = self._post({
            "role": "tester", "story_key": "KAN-42",
            "tips": [{"category": "login", "key": "tab-order",
                      "content": "Tab 5x"}],
        })
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertEqual(body["written"], 1)
        self.assertEqual(body["skipped"], 0)
        self.assertEqual(conn.commits, 1)
        # Verifieer dat de INSERT ON CONFLICT-statement gebruikt wordt
        # voor idempotente upsert.
        sql, params = conn.cur.executed[0]
        self.assertIn("ON CONFLICT", sql)
        self.assertEqual(params, ("tester", "login", "tab-order",
                                 "Tab 5x", "KAN-42"))

    def test_skip_empty_fields(self):
        r, _ = self._post({
            "role": "tester",
            "tips": [
                {"category": "", "key": "x", "content": "y"},
                {"category": "a", "key": "", "content": "y"},
                {"category": "a", "key": "b", "content": ""},
                {"category": "a", "key": "b", "content": "valid"},
            ],
        })
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertEqual(body["written"], 1)
        self.assertEqual(body["skipped"], 3)

    def test_skip_oversized_content(self):
        big = "X" * 10000
        r, _ = self._post({
            "role": "developer",
            "tips": [{"category": "a", "key": "b", "content": big}],
        })
        self.assertEqual(r.get_json()["skipped"], 1)
        self.assertEqual(r.get_json()["written"], 0)

    def test_reject_unknown_role(self):
        r, _ = self._post({"role": "admin", "tips": []})
        self.assertEqual(r.status_code, 400)

    def test_reject_non_list_tips(self):
        r, _ = self._post({"role": "tester", "tips": "not-a-list"})
        self.assertEqual(r.status_code, 400)

    def test_empty_tips_list_is_ok(self):
        r, _ = self._post({"role": "tester", "tips": []})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["written"], 0)

    def test_multiple_tips_all_written(self):
        r, conn = self._post({
            "role": "reviewer", "story_key": "KAN-99",
            "tips": [
                {"category": "smells", "key": "a", "content": "tip A"},
                {"category": "smells", "key": "b", "content": "tip B"},
                {"category": "perf", "key": "c", "content": "tip C"},
            ],
        })
        self.assertEqual(r.get_json()["written"], 3)
        self.assertEqual(len(conn.cur.executed), 3)


if __name__ == "__main__":
    unittest.main()
