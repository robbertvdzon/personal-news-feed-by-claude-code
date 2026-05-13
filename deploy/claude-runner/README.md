# Claude-runner

Autonome Claude Code-sessie in een pod. Gebruik:

```bash
./deploy/claude-runner/run-story.sh <story-id> <task.md>
```

Implementeert **S-01** uit `specs/ai-pipeline-roadmap.md`. Geen JIRA, geen
mobile, geen preview-deploys — alleen "geef een story, krijg een PR".

## Wat 't doet

1. Bouwt een K8s Job in `personal-news-feed`-namespace
2. Pod cloned de repo via een Personal Access Token (GitHub PAT)
3. Cre&euml;ert branch `ai/<story-id>` vanaf `main`
4. Draait Claude Code met de inhoud van `task.md` als input
5. Claude maakt code-wijzigingen + commits (regels in de system-prompt)
6. Pod pusht de branch en opent een PR

## Setup (&eacute;&eacute;nmalig)

### 1. GitHub PAT aanmaken

`https://github.com/settings/tokens` → **Generate new token (classic)**:
- Naam: `claude-runner-pnf`
- Expiration: bv. 90 dagen
- Scopes: alleen **`repo`**
- Generate → kopieer `ghp_...`-token

### 2. Toevoegen aan de sealed secret

Op je Mac, in de repo-root:

```bash
# Edit deploy/secrets-cluster.env, voeg toe:
GITHUB_TOKEN=ghp_xxxxxxxxxxxx

./deploy/seal-secrets.sh
git add deploy/base/sealed-secret-api-keys.yaml
git commit -m "deploy: add GITHUB_TOKEN for claude-runner"
git push
```

ArgoCD synct → secret heeft nu `GITHUB_TOKEN` als extra key.

### 3. Image laten bouwen

Eerste push van `deploy/claude-runner/**` triggert GitHub Actions
(`.github/workflows/runner-image.yml`). Image verschijnt op:
`ghcr.io/robbertvdzon/claude-runner:main`.

Maak 'm public via `https://github.com/users/robbertvdzon/packages/container/claude-runner/settings`
→ "Change visibility" → Public.

## Gebruik

### Een story draaien

```bash
# Schrijf de story in een markdown-bestand:
cat > /tmp/story.md <<'EOF'
# Voeg GET /api/version endpoint toe

De backend moet een nieuw endpoint hebben dat het korte git-SHA
en de Spring-version teruggeeft als JSON. Pad: `/api/version`.
Geen auth nodig (toevoegen aan de public-endpoints lijst).

Update de OpenAPI-spec en de backend-functional-spec.
EOF

# Draai 'm:
./deploy/claude-runner/run-story.sh add-version-endpoint /tmp/story.md
```

### Voortgang volgen

```bash
# Logs (live):
oc logs -n personal-news-feed -f job/claude-run-add-version-endpoint-<stamp>

# Status:
oc get jobs -n personal-news-feed -l app=claude-runner

# PR-URL (gh CLI lokaal):
gh pr list --search 'head:ai/add-version-endpoint'
```

### Aanpassen / opnieuw draaien

Een story opnieuw triggeren met dezelfde story-ID resulteert in
**nieuwe commits op dezelfde branch** + push (bestaande PR wordt
geüpdate, geen nieuwe). Handig voor de iteratie-loop (S-09).

## Troubleshooting

| Symptoom | Oorzaak | Fix |
|---|---|---|
| Job blijft Pending | Image-pull faalt | Check of ghcr-image public is |
| Pod CrashLoop, "GITHUB_TOKEN is leeg" | Secret-key ontbreekt | Token toevoegen via stap 2 hierboven |
| `git push: 403` | PAT mist `repo`-scope | Nieuwe token met juiste scope |
| Pod klaar maar geen commits (exit 2) | Claude vond niets te doen | Story specifieker maken |
| `mvn compile` faalt in pod | Claude maakte fout | Bekijk logs; vaak corrigeer Claude zichzelf bij retry |

## Beperkingen S-01

- Geen JIRA-koppeling (komt in S-03)
- Geen preview-deploy van de branch (komt in S-06)
- Geen mobile approve (komt in S-08)
- Frontend-changes niet ondersteund (Flutter-SDK niet in image)
- Concurrency-limiet niet afgedwongen (komt in S-04)
