"""Unit-tests voor de BUDGET= / CONTINUE comment-triggers (KAN-40).
Mockt JIRA-IO; draaien: `python3 -m unittest test_budget_commands`."""

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


def _mock_issue(phase: str = "awaiting-po", budget: int = 1000):
    return {
        "key": "KAN-TEST",
        "fields": {
            "cf_level": 1,
            "cf_token_budget": budget,
            "cf_tokens_used": 0,
            "cf_phase": phase,
            "cf_resume_phase": "developing",
        },
    }


class BudgetCommandTests(unittest.TestCase):
    def _run(self, comment: str, phase: str = "awaiting-po",
             budget: int = 1000):
        set_fields: list[dict] = []
        transitions: list[tuple[str, str]] = []
        get_resp = MagicMock(status_code=200)
        get_resp.json.return_value = _mock_issue(phase=phase, budget=budget)

        def fake_jira(method, path, **_kw):
            if method == "GET" and "/rest/api/3/issue/" in path:
                return get_resp
            return MagicMock(status_code=200)

        with patch.object(poller, "_ai_field", side_effect=lambda s: f"cf_{s}"), \
             patch.object(poller, "jira", side_effect=fake_jira), \
             patch.object(poller, "set_ai_fields",
                          side_effect=lambda _k, u: set_fields.append(dict(u))), \
             patch.object(poller, "transition_issue",
                          side_effect=lambda _k, s: transitions.append((_k, s)) or True):
            result = poller.execute_budget_command("KAN-TEST", comment)

        return {"result": result, "set_fields": set_fields,
                "transitions": transitions}

    def test_no_pattern_returns_none(self):
        r = self._run("gewone tekst zonder commando")
        self.assertIsNone(r["result"])
        self.assertEqual(r["transitions"], [])

    def test_set_budget_sets_field_and_resumes(self):
        r = self._run("BUDGET=80000")
        self.assertIn("BUDGET=80000", r["result"])
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("token_budget", 80000), flat)
        self.assertEqual(r["transitions"], [("KAN-TEST", "AI Queued")])

    def test_continue_adds_50_percent(self):
        r = self._run("CONTINUE", budget=1000)
        self.assertIn("CONTINUE", r["result"])
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("token_budget", 1500), flat)
        self.assertEqual(r["transitions"], [("KAN-TEST", "AI Queued")])

    def test_continue_case_insensitive(self):
        r = self._run("continue", budget=2000)
        self.assertIsNotNone(r["result"])
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("token_budget", 3000), flat)

    def test_set_ignored_when_not_awaiting_po(self):
        r = self._run("BUDGET=80000", phase="developing")
        self.assertIn("genegeerd", r["result"])
        self.assertEqual(r["transitions"], [])
        self.assertEqual(r["set_fields"], [])

    def test_continue_ignored_when_not_awaiting_po(self):
        r = self._run("CONTINUE", phase="")
        self.assertIn("genegeerd", r["result"])
        self.assertEqual(r["transitions"], [])

    def test_inline_budget_in_paragraph_not_matched(self):
        # 'BUDGET=' in een zin moet NIET matchen — alleen aan begin van regel.
        r = self._run("we hebben een BUDGET=50000 afspraak gemaakt")
        self.assertIsNone(r["result"])

    def test_negative_budget_rejected(self):
        # Regex pakt alleen \d+ dus negatieve waarden matchen niet als
        # budget-pattern.  '0' wordt afgevangen via expliciete check.
        r = self._run("BUDGET=0")
        self.assertIn("FOUT", r["result"])
        self.assertEqual(r["transitions"], [])

    def test_set_budget_with_whitespace(self):
        r = self._run("BUDGET = 25000")
        self.assertIsNotNone(r["result"])
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("token_budget", 25000), flat)


if __name__ == "__main__":
    unittest.main()
