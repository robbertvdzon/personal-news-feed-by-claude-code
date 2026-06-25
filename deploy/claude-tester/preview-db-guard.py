#!/usr/bin/env python3
"""Preview-DB veiligheidsguard voor de tester-agent (SF-229 / SF-237).

Doel: fail-closed verifiëren dat een DB-connection-string daadwerkelijk de
per-PR Neon-preview-branch (`pr-<PR_NUMBER>`) is en NIET productie, vóórdat
de tester ook maar één mutatie (bv. de robbert-wachtwoord-reset) uitvoert.

De guard is bewust streng: bij ontbrekende marker, gelijkheid aan de
bekende prod-host, of welke twijfel dan ook → ABORT (exit 3). Geen mutatie,
geen screenshots, subtaak afbreken.

Markers (positief signaal dat het de PR-branch is):
  1. --branch == "pr-<PR_NUMBER>"  (de labeller schrijft deze marker als
     aparte secret-key PREVIEW_DB_BRANCH in de pnf-pr-<N> namespace), of
  2. de letterlijke string "pr-<PR_NUMBER>" komt voor in de URL, of
  3. --prod-host is bekend én de URL-host wijkt ervan af.

Negatief signaal (altijd ABORT):
  * lege URL / ongeldig PR-nummer / onbepaalbare host
  * URL-host == --prod-host (dat IS productie)
  * geen enkele positieve marker (kan veiligheid niet garanderen)

Gebruik:
  preview-db-guard.py --url "$DB_URL" --pr "$PR_NUMBER" \
      [--prod-host news-prod.neon.tech] [--branch "$PREVIEW_DB_BRANCH"] \
      [--emit-psql-url]

Exit-codes: 0 = OK (preview-branch bevestigd), 3 = ABORT, 2 = usage-fout.
Met --emit-psql-url print de guard bij OK een psql-bruikbare libpq-URL op
stdout (jdbc:postgresql://… wordt geconverteerd naar postgresql://…).
"""

import argparse
import sys
import urllib.parse as up


class GuardAbort(Exception):
    pass


def parse_host(url: str) -> str:
    """Haal de hostname uit een libpq- of JDBC-URL. Lege string bij twijfel."""
    if not url:
        return ""
    u = url.strip()
    # JDBC: 'jdbc:postgresql://host:port/db?user=..&password=..' — credentials
    # zitten in de query-string, niet in de authority. Strip de jdbc-prefix
    # zodat urlparse de authority correct ziet.
    if u.startswith("jdbc:"):
        u = u[len("jdbc:"):]
    try:
        parsed = up.urlparse(u)
    except ValueError:
        return ""
    return (parsed.hostname or "").lower()


def jdbc_to_libpq(url: str) -> str:
    """Converteer een JDBC-URL naar een libpq-URL die psql begrijpt.

    jdbc:postgresql://host:port/db?user=X&password=Y&sslmode=require
      → postgresql://X:Y@host:port/db?sslmode=require

    Een URL die al libpq-vorm heeft (postgresql://) wordt ongewijzigd
    teruggegeven.
    """
    u = (url or "").strip()
    if not u:
        return ""
    if not u.startswith("jdbc:"):
        return u
    u = u[len("jdbc:"):]
    parsed = up.urlparse(u)
    qs = dict(up.parse_qsl(parsed.query))
    user = qs.pop("user", "") or (parsed.username or "")
    pwd = qs.pop("password", "") or (parsed.password or "")
    host = parsed.hostname or ""
    port = f":{parsed.port}" if parsed.port else ""
    db = (parsed.path or "").lstrip("/")
    auth = ""
    if user:
        auth = up.quote(user, safe="")
        if pwd:
            auth += ":" + up.quote(pwd, safe="")
        auth += "@"
    query = up.urlencode(qs)
    tail = f"?{query}" if query else ""
    return f"postgresql://{auth}{host}{port}/{db}{tail}"


def evaluate(url: str, pr_number: str, prod_host: str = "", branch: str = "") -> str:
    """Valideer de connection-string. Returnt de bevestigde host of raise't
    GuardAbort met een duidelijke reden."""
    if not url or not url.strip():
        raise GuardAbort("lege DB-URL — geen preview-branch om tegen te werken")

    pr = (pr_number or "").strip()
    if not pr.isdigit():
        raise GuardAbort(f"ongeldig/leeg PR_NUMBER ({pr_number!r}) — kan marker niet bepalen")

    host = parse_host(url)
    if not host:
        raise GuardAbort("kan host niet uit de DB-URL bepalen")

    prod_host = (prod_host or "").strip().lower()
    if prod_host and host == prod_host:
        raise GuardAbort(
            f"DB-host '{host}' is GELIJK aan de prod-host — dit is productie, NIET de preview-branch"
        )

    expected_marker = f"pr-{pr}"
    branch_ok = (branch or "").strip().lower() == expected_marker
    url_marker = expected_marker in url.lower()
    host_differs = bool(prod_host) and host != prod_host

    if not (branch_ok or url_marker or host_differs):
        raise GuardAbort(
            f"geen '{expected_marker}'-marker gevonden (branch-key={branch!r}) en "
            "prod-host onbekend → kan niet garanderen dat dit de preview-branch is. "
            "Is per-PR Neon-branching wel actief? (labeller moet PREVIEW_DB_BRANCH zetten)"
        )

    return host


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Preview-DB veiligheidsguard (fail-closed)")
    ap.add_argument("--url", required=True, help="DB connection-string (jdbc of libpq)")
    ap.add_argument("--pr", required=True, help="PR-nummer (verwacht marker pr-<PR>)")
    ap.add_argument("--prod-host", default="", help="bekende prod-host (optioneel, defense-in-depth)")
    ap.add_argument("--branch", default="", help="PREVIEW_DB_BRANCH-marker (optioneel)")
    ap.add_argument("--emit-psql-url", action="store_true",
                    help="print bij OK een psql-bruikbare libpq-URL op stdout")
    args = ap.parse_args(argv)

    try:
        host = evaluate(args.url, args.pr, args.prod_host, args.branch)
    except GuardAbort as e:
        sys.stderr.write(f"PREVIEW-DB-GUARD ABORT: {e}\n")
        return 3

    sys.stderr.write(f"PREVIEW-DB-GUARD OK: host '{host}' bevestigd als preview-branch pr-{args.pr.strip()}\n")
    if args.emit_psql_url:
        sys.stdout.write(jdbc_to_libpq(args.url) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
