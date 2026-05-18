# claude-interactive

Long-running pods met de Claude Code CLI in `/remote`-modus,
handmatig gestart vanuit de status-dashboard "Claude"-tab (KAN-61).

## Onderdelen

| Bestand          | Inhoud                                                         |
|------------------|----------------------------------------------------------------|
| `entrypoint.sh`  | Verse git-clone + welkomstprompt + `claude /remote`-bootstrap. |
| `rbac.yaml`      | ServiceAccount `claude-interactive` + ClusterRoleBinding naar `cluster-admin`. |

## Image

Hergebruikt `ghcr.io/robbertvdzon/claude-tester:main` — die heeft al
Claude CLI, kubectl, oc, psql, git, expect/script en Playwright. Het
`entrypoint.sh` in deze directory is de canonieke versie; de status-
dashboard backend houdt ditzelfde script inline (zie `app.py` →
`_INTERACTIVE_ENTRYPOINT_SH`) en injecteert 'm op de Job-container via
`command: ["bash", "-c", <script>]`. Daardoor is er géén ConfigMap-
mount nodig en heeft het dashboard ook geen `configmaps/create`-RBAC
nodig.

## Lifecycle

1. PO klikt "+ Nieuwe sessie" in de dashboard-tab → `POST /api/v1/claude-sessions`.
2. Backend bouwt een Job (entrypoint inline in `command`) en past 'm toe in `pnf-software-factory`.
3. Pod start, kloont `main`, registreert zich onder het PO-account via `CLAUDE_CODE_OAUTH_TOKEN`.
4. Sessie verschijnt binnen ~30 sec in de Claude-app van de PO.
5. Stop-knop: `DELETE /api/v1/claude-sessions/<name>` → `kubectl delete job` (ownerRef ruimt de pod op).
6. Crash (geen stop-knop): `restartPolicy=OnFailure` herstart de pod automatisch (verse clone).

## Cap

Max **3** actieve sessies tegelijk (hard cap in backend). Reden: één
sessie trekt op het 5-uurs Anthropic Max-quotum samen met de factory-
runs; vier of meer zou de OAuth-pool leeg trekken.

## Risico's

- Admin-RBAC = een verkeerd `oc delete deployment -n personal-news-feed
  backend` schiet de prod-app om. De welkomstprompt benoemt dit
  expliciet, maar er is geen technische guard. Mitigatie volgt in v2
  (safe/admin-toggle).
- De pod heeft toegang tot alle DB-credentials uit
  `newsfeed-api-keys`. Dat is bewust — anders kun je de sessie net zo
  goed lokaal draaien — maar onthoud dat een gecompromitteerde sessie
  dezelfde blast radius heeft als een gecompromitteerde admin-laptop.
