"""Unit-tests voor de budget-monitor (KAN-39). Geen JIRA / DB nodig —
alle externe calls worden gemockt. Draaien: `python3 -m unittest test_budget`."""

import os
import sys
import unittest
from unittest.mock import patch, MagicMock

# poller.py importeert flask + psycopg + requests bij top-level. Voor de
# tests laten we ze importeren maar de bekende-IO-functies stubben we
# uit. FACTORY_DATABASE_URL moet niet-leeg zijn anders skipt
# check_budget_and_act direct.
os.environ.setdefault("FACTORY_DATABASE_URL", "postgresql://test/test")
os.environ.setdefault("JIRA_BASE_URL", "https://example.atlassian.net")
os.environ.setdefault("JIRA_EMAIL", "test@example.com")
os.environ.setdefault("JIRA_API_KEY", "test-key")
os.environ.setdefault("JIRA_PROJECT", "TEST")
os.environ.setdefault("REPO_URL", "https://example.com/repo.git")

sys.path.insert(0, os.path.dirname(__file__))
import poller  # noqa: E402


class _StubCursor:
    """Stub psycopg-cursor die `tokens` als (total_input+total_output) teruggeeft."""
    def __init__(self, total: int):
        self._total = total
    def execute(self, *_args, **_kwargs): pass
    def fetchone(self): return (self._total,)
    def __enter__(self): return self
    def __exit__(self, *a): return False


class _StubConn:
    def __init__(self, total: int): self._total = total
    def cursor(self): return _StubCursor(self._total)
    def __enter__(self): return self
    def __exit__(self, *a): return False


def _mock_psycopg_connect(total_tokens: int):
    """Geef een psycopg.connect-mock die _StubConn met totals teruggeeft."""
    return lambda *_a, **_kw: _StubConn(total_tokens)


def _mock_jira_get_issue(budget: int, phase: str = "developing",
                        tokens_used: int = 0):
    """Mock voor jira("GET", "/rest/api/3/issue/<key>"). Returnt response
    met fields die get_ai_fields kan lezen.

    Belangrijk: get_ai_fields gebruikt _ai_field(short) als field-ID.
    We mocken _ai_field zodat 't 'cf_<short>' teruggeeft en bouwen de
    fields dict overeenkomstig."""
    return {
        "key": "KAN-TEST",
        "fields": {
            "cf_level": 1,
            "cf_token_budget": budget,
            "cf_tokens_used": tokens_used,
            "cf_phase": phase,
            "cf_resume_phase": None,
        },
    }


class BudgetCheckTests(unittest.TestCase):
    def _run(self, used: int, budget: int, role: str = "developer",
             existing_markers: tuple[str, ...] = ()):
        """Roep check_budget_and_act met de gegeven scenario aan en
        verzamel alle calls. Return: dict met posted comments, transitions,
        en set_ai_fields-calls."""
        posted_comments: list[str] = []
        transitions: list[tuple[str, str]] = []
        set_fields: list[dict] = []
        get_issue_resp = MagicMock(status_code=200)
        get_issue_resp.json.return_value = _mock_jira_get_issue(budget)

        def fake_jira(method, path, **_kw):
            if method == "GET" and "/rest/api/3/issue/" in path:
                return get_issue_resp
            return MagicMock(status_code=200)

        def fake_set_ai_fields(_key, updates):
            set_fields.append(dict(updates))
            return True

        def fake_transition(key, status):
            transitions.append((key, status))
            return True

        def fake_post_comment(_key, text):
            posted_comments.append(text)
            return True

        def fake_has_marker(_key, needle):
            return any(needle in m for m in existing_markers)

        def fake_ai_field(short):
            return f"cf_{short}"

        with patch.object(poller, "psycopg") as pmock, \
             patch.object(poller, "_ai_field", side_effect=fake_ai_field), \
             patch.object(poller, "jira", side_effect=fake_jira), \
             patch.object(poller, "set_ai_fields", side_effect=fake_set_ai_fields), \
             patch.object(poller, "transition_issue", side_effect=fake_transition), \
             patch.object(poller, "jira_post_comment", side_effect=fake_post_comment), \
             patch.object(poller, "jira_has_comment_containing", side_effect=fake_has_marker):
            pmock.connect = _mock_psycopg_connect(used)
            poller.check_budget_and_act("KAN-TEST", story_run_id=1, role=role)

        return {
            "comments": posted_comments,
            "transitions": transitions,
            "set_fields": set_fields,
        }

    def test_under_75_no_comment(self):
        r = self._run(used=500, budget=1000)
        self.assertEqual(r["comments"], [])
        self.assertEqual(r["transitions"], [])

    def test_75_threshold_posts_warning(self):
        r = self._run(used=750, budget=1000)
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 75%", r["comments"][0])
        self.assertEqual(r["transitions"], [])

    def test_90_threshold_posts_warning(self):
        r = self._run(used=900, budget=1000)
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 90%", r["comments"][0])
        self.assertEqual(r["transitions"], [])

    def test_100_threshold_pauses(self):
        r = self._run(used=1000, budget=1000, role="developer")
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 100%", r["comments"][0])
        self.assertIn("BUDGET=", r["comments"][0])
        self.assertIn("CONTINUE", r["comments"][0])
        self.assertEqual(r["transitions"], [("KAN-TEST", "AI Needs Info")])
        # awaiting-po + resume_phase=developing in de field-updates
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("phase", "awaiting-po"), flat)
        self.assertIn(("resume_phase", "developing"), flat)

    def test_over_100_pauses(self):
        r = self._run(used=1500, budget=1000, role="refiner")
        self.assertEqual(r["transitions"], [("KAN-TEST", "AI Needs Info")])
        # role=refiner → resume_phase=refining
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("resume_phase", "refining"), flat)

    def test_idempotent_100_no_second_pause(self):
        # Markers al aanwezig → geen comment, geen transition
        r = self._run(used=1200, budget=1000,
                     existing_markers=("[COST-MONITOR] 100%",))
        self.assertEqual(r["comments"], [])
        self.assertEqual(r["transitions"], [])

    def test_idempotent_75_no_dupe_warning(self):
        r = self._run(used=800, budget=1000,
                     existing_markers=("[COST-MONITOR] 75%",))
        self.assertEqual(r["comments"], [])

    def test_75_marker_does_not_block_90(self):
        r = self._run(used=910, budget=1000,
                     existing_markers=("[COST-MONITOR] 75%",))
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 90%", r["comments"][0])

    def test_tokens_used_field_updated_always(self):
        r = self._run(used=300, budget=1000)
        flat = [f for u in r["set_fields"] for f in u.items()]
        self.assertIn(("tokens_used", 300), flat)

    def test_zero_budget_falls_back_to_default(self):
        # budget=0 → fallback naar DEFAULT_TOKEN_BUDGET. Met 30k used
        # tegen default 40k = 75%.
        used = int(poller.DEFAULT_TOKEN_BUDGET * 0.80)
        r = self._run(used=used, budget=0)
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 75%", r["comments"][0])


if __name__ == "__main__":
    unittest.main()
