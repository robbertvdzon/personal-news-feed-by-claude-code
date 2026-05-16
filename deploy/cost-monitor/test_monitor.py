"""Unit-tests voor de cost-monitor (KAN-41). Mockt JIRA + DB. Draaien:
`python3 -m unittest test_monitor`."""

import os
import sys
import unittest
from unittest.mock import patch, MagicMock

os.environ.setdefault("FACTORY_DATABASE_URL", "postgresql://test/test")
os.environ.setdefault("JIRA_BASE_URL", "https://example.atlassian.net")
os.environ.setdefault("JIRA_EMAIL", "test@example.com")
os.environ.setdefault("JIRA_API_KEY", "test-key")

# psycopg heeft op Python 3.14 (lokale dev) nog geen binary-wheel —
# mock 'm uit zodat de tests overal draaien zonder native deps. De
# tests gebruiken `check_one_story` direct, dus echte DB-connecties
# zijn niet nodig.
from unittest.mock import MagicMock  # noqa: E402
sys.modules.setdefault("psycopg", MagicMock())

sys.path.insert(0, os.path.dirname(__file__))

# Module heet 'cost-monitor.py' met streepje — kan niet direct geïmporteerd.
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location(
    "cost_monitor",
    os.path.join(os.path.dirname(__file__), "cost-monitor.py"),
)
cm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cm)


def _mock_issue(status: str = "AI IN PROGRESS", budget: int = 1000,
               phase: str = "developing"):
    return {
        "fields": {
            "status": {"name": status},
            "cf_token_budget": budget,
            "cf_tokens_used": 0,
            "cf_phase": phase,
            "cf_resume_phase": None,
        },
    }


class CheckOneStoryTests(unittest.TestCase):
    def _run(self, tokens_used: int, budget: int = 1000,
             status: str = "AI IN PROGRESS",
             existing_markers: tuple[str, ...] = (),
             phase: str = "developing"):
        comments: list[str] = []
        transitions: list[str] = []
        set_fields: list[dict] = []

        issue_resp = _mock_issue(status=status, budget=budget, phase=phase)

        def fake_get(path, params=None):
            if path.endswith("/comment"):
                return {"comments": [
                    {"body": {"type": "doc", "content": [
                        {"type": "paragraph", "content": [
                            {"type": "text", "text": m}]}]}}
                    for m in existing_markers
                ]}
            if "/transitions" in path:
                return {"transitions": [
                    {"id": "99", "to": {"name": "AI Needs Info"}}]}
            return issue_resp

        def fake_post(path, body):
            if path.endswith("/comment"):
                # Extract comment text from ADF
                txt = body["body"]["content"][0]["content"][0]["text"]
                comments.append(txt)
            elif "/transitions" in path:
                transitions.append("AI Needs Info")
            return True

        def fake_put(path, body):
            fields = body.get("fields", {})
            set_fields.append(dict(fields))
            return True

        with patch.object(cm, "field_id", side_effect=lambda s: f"cf_{s}"), \
             patch.object(cm, "fields_param", return_value="summary,status"), \
             patch.object(cm, "jira_get", side_effect=fake_get), \
             patch.object(cm, "jira_post", side_effect=fake_post), \
             patch.object(cm, "jira_put", side_effect=fake_put):
            cm.check_one_story("KAN-TEST", tokens_used)
        return {"comments": comments, "transitions": transitions,
                "set_fields": set_fields}

    def test_under_75_no_action(self):
        r = self._run(tokens_used=500)
        self.assertEqual(r["comments"], [])
        self.assertEqual(r["transitions"], [])

    def test_75_threshold_posts_warning(self):
        r = self._run(tokens_used=750)
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 75%", r["comments"][0])
        self.assertEqual(r["transitions"], [])

    def test_100_threshold_pauses(self):
        r = self._run(tokens_used=1000, phase="developing")
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 100%", r["comments"][0])
        self.assertEqual(r["transitions"], ["AI Needs Info"])
        # phase=awaiting-po + resume_phase=developing must be set
        flat_keys = []
        for u in r["set_fields"]:
            flat_keys.extend(u.keys())
        self.assertIn("cf_phase", flat_keys)
        self.assertIn("cf_resume_phase", flat_keys)

    def test_skip_paused_story(self):
        r = self._run(tokens_used=1000, status="AI Paused")
        self.assertEqual(r["comments"], [])
        self.assertEqual(r["transitions"], [])

    def test_skip_review_story(self):
        r = self._run(tokens_used=1000, status="AI IN REVIEW")
        self.assertEqual(r["comments"], [])

    def test_idempotent_100_no_second_pause(self):
        r = self._run(tokens_used=1200,
                     existing_markers=("[COST-MONITOR] 100%",))
        # Comments uit `existing_markers` simuleren oude markers; check_one
        # post niet opnieuw.
        self.assertEqual([c for c in r["comments"] if "[COST-MONITOR]" in c],
                         [])
        self.assertEqual(r["transitions"], [])

    def test_resume_phase_fallback_on_unknown(self):
        # Als phase niet een -ing form is, fallback naar 'developing'.
        r = self._run(tokens_used=2000, phase="reviewed-ok")
        # Find the field-update that sets resume_phase
        resume_updates = [u for u in r["set_fields"]
                         if "cf_resume_phase" in u]
        self.assertTrue(resume_updates)
        self.assertEqual(resume_updates[0]["cf_resume_phase"], "developing")

    def test_zero_budget_falls_back_to_default(self):
        # budget=0 → DEFAULT_TOKEN_BUDGET. Default=40000, used=32000 = 80%.
        used = int(cm.DEFAULT_TOKEN_BUDGET * 0.80)
        r = self._run(tokens_used=used, budget=0)
        self.assertEqual(len(r["comments"]), 1)
        self.assertIn("[COST-MONITOR] 75%", r["comments"][0])


if __name__ == "__main__":
    unittest.main()
