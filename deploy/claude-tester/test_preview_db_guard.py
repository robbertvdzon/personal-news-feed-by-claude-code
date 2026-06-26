"""Unit-tests voor de preview-DB veiligheidsguard (SF-229 / SF-237).

Draaien: `python3 -m unittest test_preview_db_guard` (geen externe deps).

Dekt de AC2-eis: een (dry-run) tegen een prod-achtige URL → ABORT zónder
mutatie, en een geldige preview-branch → OK.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import importlib

guard = importlib.import_module("preview-db-guard")


PROD_HOST = "ep-prod-aaaa-12345678.eu-central-1.aws.neon.tech"
PR_HOST = "ep-pr-bbbb-87654321.eu-central-1.aws.neon.tech"

PROD_JDBC = (
    f"jdbc:postgresql://{PROD_HOST}/neondb?user=neondb_owner&password=secret&sslmode=require"
)
PR_JDBC = (
    f"jdbc:postgresql://{PR_HOST}/neondb?user=neondb_owner&password=secret&sslmode=require"
)


class TestParseHost(unittest.TestCase):
    def test_jdbc(self):
        self.assertEqual(guard.parse_host(PROD_JDBC), PROD_HOST)

    def test_libpq(self):
        self.assertEqual(
            guard.parse_host("postgresql://u:p@some-host.neon.tech/db?sslmode=require"),
            "some-host.neon.tech",
        )

    def test_empty(self):
        self.assertEqual(guard.parse_host(""), "")
        self.assertEqual(guard.parse_host("   "), "")


class TestJdbcToLibpq(unittest.TestCase):
    def test_conversion_roundtrip_fields(self):
        out = guard.jdbc_to_libpq(PR_JDBC)
        self.assertTrue(out.startswith("postgresql://"))
        self.assertIn("neondb_owner:secret@", out)
        self.assertIn(PR_HOST, out)
        self.assertIn("/neondb", out)
        self.assertIn("sslmode=require", out)
        # Credentials horen niet meer als losse query-params voor te komen.
        self.assertNotIn("user=", out)
        self.assertNotIn("password=", out)

    def test_libpq_passthrough(self):
        url = "postgresql://u:p@h.neon.tech/db?sslmode=require"
        self.assertEqual(guard.jdbc_to_libpq(url), url)

    def test_special_chars_in_password(self):
        jdbc = "jdbc:postgresql://h.neon.tech/db?user=neondb_owner&password=p%40ss%2Fword&sslmode=require"
        out = guard.jdbc_to_libpq(jdbc)
        # Wachtwoord blijft URL-encoded zodat psql 't correct parst.
        self.assertIn("p%40ss%2Fword", out)


class TestEvaluate(unittest.TestCase):
    # --- ABORT-gevallen (fail-closed) ---
    def test_empty_url_aborts(self):
        with self.assertRaises(guard.GuardAbort):
            guard.evaluate("", "42", prod_host=PROD_HOST, branch="pr-42")

    def test_invalid_pr_aborts(self):
        with self.assertRaises(guard.GuardAbort):
            guard.evaluate(PR_JDBC, "", prod_host=PROD_HOST, branch="pr-42")
        with self.assertRaises(guard.GuardAbort):
            guard.evaluate(PR_JDBC, "abc", prod_host=PROD_HOST, branch="pr-abc")

    def test_prod_host_match_aborts(self):
        # AC2: dry-run tegen prod-achtige URL → ABORT.
        with self.assertRaises(guard.GuardAbort) as cm:
            guard.evaluate(PROD_JDBC, "42", prod_host=PROD_HOST, branch="")
        self.assertIn("productie", str(cm.exception).lower())

    def test_no_marker_unknown_prod_aborts(self):
        # Geen branch-marker, marker niet in URL, prod-host onbekend → ABORT.
        with self.assertRaises(guard.GuardAbort):
            guard.evaluate(PR_JDBC, "42", prod_host="", branch="")

    def test_wrong_branch_marker_unknown_prod_aborts(self):
        with self.assertRaises(guard.GuardAbort):
            guard.evaluate(PR_JDBC, "42", prod_host="", branch="pr-99")

    # --- OK-gevallen ---
    def test_branch_marker_matches(self):
        host = guard.evaluate(PR_JDBC, "42", prod_host="", branch="pr-42")
        self.assertEqual(host, PR_HOST)

    def test_branch_marker_case_insensitive(self):
        host = guard.evaluate(PR_JDBC, "42", prod_host="", branch="PR-42")
        self.assertEqual(host, PR_HOST)

    def test_marker_in_url(self):
        url = "jdbc:postgresql://ep-pr-42-xyz.neon.tech/db?user=u&password=p"
        host = guard.evaluate(url, "42", prod_host="", branch="")
        self.assertEqual(host, "ep-pr-42-xyz.neon.tech")

    def test_host_differs_from_prod(self):
        # Branch-key ontbreekt maar host wijkt af van bekende prod-host → OK.
        host = guard.evaluate(PR_JDBC, "42", prod_host=PROD_HOST, branch="")
        self.assertEqual(host, PR_HOST)


class TestCli(unittest.TestCase):
    def test_ok_exit_zero(self):
        rc = guard.main(["--url", PR_JDBC, "--pr", "42", "--branch", "pr-42"])
        self.assertEqual(rc, 0)

    def test_abort_exit_three(self):
        rc = guard.main(["--url", PROD_JDBC, "--pr", "42", "--prod-host", PROD_HOST])
        self.assertEqual(rc, 3)

    def test_emit_psql_url(self):
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = guard.main(["--url", PR_JDBC, "--pr", "42", "--branch", "pr-42", "--emit-psql-url"])
        self.assertEqual(rc, 0)
        out = buf.getvalue().strip()
        self.assertTrue(out.startswith("postgresql://"))
        self.assertIn(PR_HOST, out)


if __name__ == "__main__":
    unittest.main()
