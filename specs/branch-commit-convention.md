# Branch- en commit-conventie

Standaardisatie voor branches en commit-messages, zodat de AI-flow
(AI-gegenereerde branches → PR → preview-deploys) eenduidig kan werken.

## Branch-namen

| Prefix | Wanneer | Voorbeeld |
|---|---|---|
| `ai/` | Automatisch gegenereerd (AI-flow) | `ai/PNF-42`, `ai/poc-version-endpoint` |
| `feat/` | Mens-geïnitieerde feature (vrij format) | `feat/add-export-button` |
| `fix/` | Mens-geïnitieerde bugfix | `fix/login-crash` |
| `chore/` | Refactor, deploy-config, tooling | `chore/upgrade-spring-4.1` |
| `docs/` | Alleen documentatie | `docs/update-readme` |

**Regels:**
- Letters (hoofd + klein), cijfers en `-`
- Geen spaties of underscores
- Format: `^(ai|feat|fix|chore|docs)/[A-Za-z0-9][A-Za-z0-9-]*$`
- JIRA-keys (b.v. `KAN-8`) mogen hoofdletters bevatten en blijven onveranderd

## Commit-messages

### Op `ai/<id>`-branches (afgedwongen)

Elke commit op een `ai/<id>`-branch **moet** beginnen met de story-id:

```
<id>: <korte omschrijving in een zin>
```

Voorbeelden:
```
PNF-42: add /api/version endpoint
poc-001: update OpenAPI spec for version endpoint
```

De CI valideert dit op pull-requests (zie `.github/workflows/validate-pr.yml`).

### Op andere branches (richtlijn, niet afgedwongen)

[Conventional Commits](https://www.conventionalcommits.org/) is een
aanrader maar wordt niet hard gecheckt:

```
feat: add export button
fix(login): handle empty password
chore: bump kotlin to 2.2.30
```

### Op `main` (na squash-merge)

GitHub voegt automatisch `(#<pr-num>)` toe aan de squash-merge-commit:

```
PNF-42: add /api/version endpoint (#42)
```

Dit format is automatisch en hoeft niet apart afgedwongen te worden.

## PR-titels

PR-titel volgt hetzelfde format als de eerste commit-message — dat wordt
ook de squash-merge-commit.

## Waarom deze conventie

- **Traceerbaarheid**: elke commit/PR is direct te koppelen aan een story-id
- **Automation**: preview-deploys (S-06) gebruiken de branch-naam als
  subdomein-suffix; consistent format = voorspelbare URL's
- **JIRA-koppeling** (S-07): zoek-substitutie op de id om JIRA-tickets te
  vinden
