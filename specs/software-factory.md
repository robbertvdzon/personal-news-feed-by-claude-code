# Software Factory — design

Dit document beschrijft de **software factory**: een pipeline van LLM-agents die JIRA-stories autonoom oppakt, ontwerpt, ontwikkelt, reviewt en test. Op de software factory worden alle features van Personal News Feed gebouwd. Het is een ontwerpdocument, geen implementatie-handleiding — de stapsgewijze uitrol staat in een aparte `implementation-plan.md` (volgt).

De bestaande "claude-runner"-flow (één developer-agent die een JIRA "AI Ready" naar PR brengt) is een onderdeel hiervan en wordt uitgebreid; zie [ai-pipeline-roadmap.md](./ai-pipeline-roadmap.md) voor de historische stappen S-01 t/m S-09 waarmee dit fundament is gelegd.

---

## 1. Doelen

1. **Stories autonoom door de pipeline laten lopen.** Een PO schrijft een korte beschrijving in JIRA en zet 'm op AI Ready. Daarna draait alles automatisch tot een mergebare, geteste PR — of tot het stopt en om input vraagt.
2. **Volledige observability.** Voor elke story is achteraf precies te zien welke agent wat heeft gedaan, welke tools hij gebruikte, hoeveel tokens, en waarom hij stopte.
3. **Kosten onder controle.** Per story een budget in tokens. Wordt 'ie overschreden, dan stopt de pipeline en vraagt om bevestiging.
4. **Modelkeuze per story.** Een goedkope flow voor experimenten, een dure flow voor belangrijk werk.
5. **Veilige tester.** De test-agent mag breed in het cluster rondkijken — maar niet stiekem prod-infrastructuur muteren.

---

## 2. De agents

Vijf rollen. Vier zijn LLM-agents (Claude-pods), één is een regelaar (Python-script, geen LLM).

| Rol | Type | Wat hij doet | Tools / scope |
|---|---|---|---|
| **Refiner** | LLM | Leest de ruwe story, identificeert ambiguïteiten, stelt vragen aan de PO via JIRA-comments. Geen code. | Alleen JIRA-API |
| **Developer** | LLM | Implementeert de refined story. Commits + PR. Bestaande claude-runner. | Repo + git + JIRA |
| **Reviewer** | LLM | Leest de PR-diff, checkt code-kwaliteit + architectuur, post comments op de PR. Geen pushes. | Repo (read) + GitHub PR-API |
| **Tester** | LLM | Test de preview-deploy met headless Chrome + DB-toegang + `oc`-commands. Rapporteert bevindingen als JIRA + PR-comments. Geen pushes, geen infra-mutaties. | Cluster-wide read, schrijven in `pnf-pr-*`, leesrechten DB, headless browser |
| **Cost-monitor** | Regelaar | Som token-verbruik per story na elke agent-run. Bij budget-overschrijding: pauzeer. | DB + JIRA-API |

**Concurrency-limieten per rol** (instelbaar):
- refiner: 5 parallel
- developer: 2 parallel
- reviewer: 3 parallel
- tester: 1 parallel (browser-pods zijn zwaar)
- cost-monitor: cron, geen concurrency-issue

---

## 3. JIRA — statussen en custom fields

### 3.1 Statussen op het bord

Vijf statussen, zichtbaar voor de PO:

```
AI Ready          PO heeft 'm net ingeschoten
AI In Progress    één van de agents werkt eraan (refiner, dev, reviewer, tester — onderscheid via custom field)
AI Needs Info     wacht op PO; kan vanaf elke agent komen
AI Paused         handmatig stilgezet, vanaf elke status bereikbaar
Klaar             merged + getest
```

**Waarom maar één "In Progress"-status** in plaats van per-fase: het JIRA-bord blijft overzichtelijk, en alle nuance staat in custom fields die de orchestratie gebruikt.

### 3.2 Custom fields

| Veld | Type | Default | Waarvoor |
|---|---|---|---|
| `AI Level` | number 0-10 | `0` (cheapest) | Welke model/effort-matrix de agents pakken |
| `AI Token Budget` | number | `40000` | Sommatie-cap voor alle agent-runs samen |
| `AI Phase` | enum: `refining`, `developing`, `reviewing`, `testing` | (poller zet) | Welke agent op dit moment actief is |
| `AI Resume Phase` | enum (zelfde opties) | (poller zet) | Naar welke fase resumen na pauze of Needs Info |
| `AI Tokens Used` | number | `0` | Som van usage tot nu toe (cost-monitor onderhoudt) |

`AI Phase` en `AI Resume Phase` mogen system-only zijn — de PO komt er niet aan. `AI Level` en `AI Token Budget` mag de PO bewerken op elke status.

### 3.3 Workflow-transitions

Alleen de belangrijkste — volledige beschrijving in JIRA-admin.

```
AI Ready        → AI In Progress    (refiner pakt op)
AI In Progress  → AI Needs Info     (een agent loopt vast → PO aan zet)
AI In Progress  → AI In Progress    (interne fase-overgang: refining → developing etc.)
AI In Progress  → AI Paused         (handmatig)
AI Needs Info   → AI In Progress    (PO heeft beantwoord)
AI Needs Info   → AI Paused         (handmatig)
AI Paused       → AI In Progress    (resume via AI Resume Phase)
AI Paused       → AI Needs Info     (alsnog vraag formuleren)
AI In Progress  → Klaar             (merge + tests passed)
```

---

## 4. Orchestratie — hoe een story door de pipeline loopt

```
PO maakt issue → AI Ready
        │
        ▼  poller polt elke 30s
        │  spawns: refiner-Job (AI Phase=refining)
        │
   ┌────┴─────────────────────────────────────────────────────┐
   │                       Refiner                            │
   │  • leest story                                           │
   │  • genoeg info?                                          │
   │    ja → transition AI Phase=developing, status In Progress
   │    nee → post [REFINER] vragen, transition naar Needs Info
   │           AI Resume Phase=refining                       │
   └──────────────────────────────────────────────────────────┘
        │ (bij Needs Info)              │ (bij Refined)
        ▼                                ▼
   PO antwoordt → AI In Progress    Developer-Job spawnt
   poller ziet AI Resume Phase=     • clone, branch, claude code
   refining → refiner pakt opnieuw    --print, --output-format
   op met volle context                stream-json met --verbose
                                    • commit + push + PR open
                                    • AI Phase=reviewing

   ┌────────────────────────────────────────────────────────────┐
   │                       Reviewer                             │
   │  • leest PR-diff                                           │
   │  • post review-comments [REVIEWER]                         │
   │  • alles ok → AI Phase=testing                             │
   │  • wijzigingen nodig → AI Phase=developing (dev pakt op    │
   │    via bestaande S-09 comment-iteratie-loop)               │
   └────────────────────────────────────────────────────────────┘
        │                                       ▲
        ▼                                       │
   ┌────────────────────────────────────────────┐
   │                       Tester              │
   │  • leest PR-titel + AC's uit JIRA          │
   │  • runt Chrome tegen pnf-pr-<num>.…        │
   │  • checkt DB, pod-logs                     │
   │  • alles ok → laat het zo                  │
   │  • fout gevonden → [TESTER] op PR + JIRA, │
   │    AI Phase=developing                     │
   └────────────────────────────────────────────┘
        │
        ▼  (mens merget de PR)
   poller ziet merge → status Klaar
```

### 4.1 Comment-conventie

Elke agent prefixt z'n JIRA- en PR-comments met z'n rol:

```
[REFINER] Vraag over de export-feature: ...
[REVIEWER] Op regel 42 mist een try/catch rond ...
[TESTER] Reproductie: open /admin/users, klik "+", crasht ...
[COST-MONITOR] Budget bereikt: 47K/40K tokens. Verhoog of bevestig.
```

Mens-leesbaar, en orchestratie kan filteren ("toon alleen [TESTER]-comments uit deze cyclus").

### 4.2 Pauze-semantiek

- **Handmatig pauzeren** (dashboard-knop, of JIRA-transition): de huidige Job wordt **hard gekilled** (`oc delete job`). Pending commits gaan verloren. Branch blijft zoals 'ie was bij de laatste push.
- **Resume**: `AI Resume Phase` wordt gevolgd. Als 'ie op `developing` stond, pakt de developer-agent de bestaande `ai/KAN-XX`-branch op en gaat verder.

### 4.3 AI Needs Info kan vanaf elke agent

Vier scenario's:

| Wie vraagt? | Wanneer | `AI Resume Phase` zet poller op |
|---|---|---|
| Refiner | Story is te vaag | `refining` |
| Developer | Hij komt iets tegen wat niet voorzien was | `developing` |
| Reviewer | Architectuur-keuze waar 'ie geen mandaat over heeft | `reviewing` |
| Tester | Onduidelijke acceptatie-criteria, test geeft ander gedrag dan verwacht maar onduidelijk of dat een bug is | `testing` |

PO beantwoordt → transition naar `AI In Progress` → poller pakt op vanaf `AI Resume Phase`.

---

## 5. Model + effort per story — de level-matrix

### 5.1 Werking

Elke story heeft een `AI Level` (default 0). Per level definieert een ConfigMap welke `(model, effort)`-combinatie elke rol krijgt. Cheapest first.

`deploy/tooling/agent-levels.yaml`:

```yaml
models:
  cheap:    { model: claude-haiku-4-5,  effort: quick   }
  cheap+:   { model: claude-haiku-4-5,  effort: default }
  mid:      { model: claude-sonnet-4-6, effort: quick   }
  mid+:     { model: claude-sonnet-4-6, effort: default }
  mid++:    { model: claude-sonnet-4-6, effort: deep    }
  premium:  { model: claude-opus-4-7,   effort: default }
  premium+: { model: claude-opus-4-7,   effort: deep    }

levels:
  0:  { refiner: cheap,  developer: cheap,   reviewer: cheap,   tester: cheap   }
  1:  { refiner: cheap,  developer: cheap+,  reviewer: cheap,   tester: cheap   }
  2:  { refiner: cheap,  developer: mid,     reviewer: cheap,   tester: cheap   }
  3:  { refiner: cheap,  developer: mid,     reviewer: cheap+,  tester: cheap+  }
  4:  { refiner: cheap+, developer: mid+,    reviewer: mid,     tester: mid     }
  5:  { refiner: cheap+, developer: mid+,    reviewer: mid+,    tester: mid+    }
  6:  { refiner: mid,    developer: mid++,   reviewer: mid+,    tester: mid+    }
  7:  { refiner: mid,    developer: premium, reviewer: mid+,    tester: mid+    }
  8:  { refiner: mid,    developer: premium, reviewer: mid++,   tester: mid+    }
  9:  { refiner: mid+,   developer: premium+, reviewer: premium, tester: mid++   }
  10: { refiner: mid+,   developer: premium+, reviewer: premium+, tester: premium }
```

De ladder is bewust niet-lineair: levels 0-3 voor goedkoop experimenteren, 4-5 voor "doe gewoon je werk", 6+ voor opschalen, 10 voor "doe alsof het ertoe doet". Met 11 niveaus heb je genoeg tussenklikjes om te tunen.

### 5.2 "Effort"-mapping

Op het moment van schrijven biedt de Claude CLI in `--print`-mode geen directe knop voor extended-thinking-budget. We bouwen 'effort' eerst via system-prompt-instructie ("denk maximaal X tokens hardop na"). Als dit niet voldoende controle geeft, migreren we de runner van CLI naar de **Claude Agent SDK** waar `thinking.budget_tokens` wel beschikbaar is.

### 5.3 Override-flow

De PO kan `AI Level` aanpassen op elk moment. De refiner kan in een comment voorstellen om 'm omhoog te zetten:

```
[REFINER] Deze story raakt de auth-flow — minimaal level 5 lijkt me passend.
Reageer met 'LEVEL=5' om te accepteren.
```

De poller scant inkomende comments op patterns als `LEVEL=N` en `BUDGET=N` en updatet de bijbehorende JIRA-velden automatisch.

---

## 6. Cost-monitor

### 6.1 Wat hij doet

- Sommeert `input_tokens + output_tokens` per story uit de logging-DB.
- Vergelijkt met `AI Token Budget`.
- Bij ≥ 100%: transition naar **AI Needs Info** + JIRA-comment:
  > `[COST-MONITOR] Budget bereikt: 47K verbruikt van 40K. Verhoog `AI Token Budget` of bevestig om door te gaan ('CONTINUE' in een comment).`
- Bij 75% / 90%: alleen comment, geen status-transition.

### 6.2 Twee triggers

- **Per agent-run** (realtime): de runner POST't z'n usage aan een HTTP-endpoint op de poller direct na completion. Cost-monitor draait dan z'n check.
- **Cron elke 5 min** (vangnet): als een runner crasht voordat hij kan rapporteren, ziet de cron 'm alsnog (uitlezen uit DB blijft mogelijk via een 'final state'-event dat de runner schrijft).

### 6.3 Wat telt mee

| Bron | Telt mee? |
|---|---|
| Refiner-tokens | ✓ |
| Developer-tokens | ✓ |
| Reviewer-tokens | ✓ |
| Tester-tokens | ✓ |
| Cost-monitor zelf | n.v.t. (geen LLM) |
| Re-runs na PR-comment-loop (S-09) | ✓ (alles binnen één story-cyclus telt) |

---

## 7. Logging-DB

### 7.1 Locatie

Bestaande **Neon Postgres** (zelfde DB als `PNF_DATABASE_URL`), nieuw schema `factory`. Eén connection-string wordt hergebruikt; voor de Python-poller komt er een tweede sealed-secret-entry met de `postgresql://`-prefix (zonder `jdbc:`).

### 7.2 Schema

```sql
CREATE SCHEMA IF NOT EXISTS factory;

CREATE TABLE IF NOT EXISTS factory.story_runs (
  id              BIGSERIAL PRIMARY KEY,
  story_key       TEXT NOT NULL,
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at        TIMESTAMPTZ,
  final_status    TEXT,                            -- bv. 'Klaar', 'paused', 'budget-exceeded'
  total_input_tokens   INTEGER NOT NULL DEFAULT 0,
  total_output_tokens  INTEGER NOT NULL DEFAULT 0,
  total_cost_usd_est   NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

CREATE TABLE IF NOT EXISTS factory.agent_runs (
  id              BIGSERIAL PRIMARY KEY,
  story_run_id    BIGINT NOT NULL REFERENCES factory.story_runs(id),
  role            TEXT NOT NULL,                   -- 'refiner', 'developer', ...
  job_name        TEXT NOT NULL,                   -- K8s Job-naam
  model           TEXT,                            -- 'claude-sonnet-4-6'
  effort          TEXT,                            -- 'quick' | 'default' | 'deep'
  level           SMALLINT,                        -- snapshot van AI Level
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at        TIMESTAMPTZ,
  outcome         TEXT,                            -- 'success', 'failed', 'killed', 'questions'
  input_tokens    INTEGER NOT NULL DEFAULT 0,
  output_tokens   INTEGER NOT NULL DEFAULT 0,
  cost_usd_est    NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

CREATE TABLE IF NOT EXISTS factory.agent_events (
  id              BIGSERIAL PRIMARY KEY,
  agent_run_id    BIGINT NOT NULL REFERENCES factory.agent_runs(id),
  ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
  kind            TEXT NOT NULL,                   -- 'system', 'assistant', 'tool_use', 'tool_result', 'result'
  payload         JSONB NOT NULL                   -- de complete event-record uit stream-json
);

CREATE INDEX IF NOT EXISTS agent_runs_story_idx ON factory.agent_runs(story_run_id);
CREATE INDEX IF NOT EXISTS agent_events_run_idx ON factory.agent_events(agent_run_id, ts);
CREATE INDEX IF NOT EXISTS story_runs_key_idx ON factory.story_runs(story_key);
```

### 7.3 Migratie-strategie

Schema-DDL leeft in `deploy/tooling/factory-schema.sql`. Een **init-Job** (`factory-db-init`) draait bij elke ArgoCD-sync; `CREATE ... IF NOT EXISTS` is idempotent.

Voor toekomstige wijzigingen: nieuwe `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...`-regels onderaan toevoegen. Bij meer dan triviale migraties switchen naar Alembic.

### 7.4 Secret-redactie

Vóór events naar `agent_events.payload` schrijven, draait een regex-filter over de log-tekst die de volgende patronen vervangt door `<REDACTED>`:

- `sk-ant-(api03|oat01)-[A-Za-z0-9_-]+` (Anthropic-keys)
- `ghp_[A-Za-z0-9]{36,}` (GitHub PATs)
- `eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+` (JWTs)
- `postgresql://[^/]*:[^@]*@` (credentials in connection-strings)

Niet waterdicht, maar reduceert het ongelukken-risico aanzienlijk. Bewaartermijn: voor altijd.

---

## 8. Tester-rol — specifieke uitwerking

De tester is de gevaarlijkste agent qua blast-radius en verdient een eigen sectie.

### 8.1 Image

Eigen Docker-image `ghcr.io/robbertvdzon/claude-tester` op basis van `claude-runner`, plus:

- Chromium + Playwright (Python-bindings)
- `psql` CLI
- `oc` / `kubectl` CLI
- Een base set Python-scripts in `/usr/local/lib/factory/` voor veel-voorkomende test-acties (login-flow, screenshot, DB-query)

### 8.2 RBAC

Eigen ServiceAccount `claude-tester` met **cluster-wide read** + **schrijven in pnf-pr-* + read op prod**:

```yaml
- apiGroups: [""]
  resources: ["pods", "pods/log", "services", "endpoints", "configmaps", "secrets", "namespaces"]
  verbs: ["get", "list", "watch"]
  # secrets: read voor pr-namespaces — handig voor test-creds; expliciet wel
  # afgeschermd via NetworkPolicy zodat de tester niet uit z'n namespace kan
- apiGroups: [""]
  resources: ["pods/exec"]
  verbs: ["create"]
  # — alleen toegestaan in pnf-pr-*-namespaces via Role-binding-scope
```

(Volledige RBAC-yaml in de implementatie-PR.)

### 8.3 System-prompt-grenzen

In aanvulling op de RBAC krijgt de tester een **expliciete prompt-restrictie**:

> Je doel is testen, niet bouwen of patchen. Je mag NOOIT:
> - infrastructuur (Deployments, Services, ConfigMaps) muteren
> - Git-commits maken
> - secrets aanpassen
> - prod-namespace muteren (`personal-news-feed`)
>
> Je MAG:
> - lezen van alles in `personal-news-feed` en `pnf-pr-*`
> - `oc exec` in pnf-pr-pods om iets uit te proberen
> - DB-queries lezen + schrijven in de preview-DB
> - `oc delete pod` om een pod-restart te forceren in een pnf-pr-namespace
>
> Bij ontdekking van een bug: documenteer reproductie-stappen, suggereer een fix in een [TESTER]-comment, en zet `AI Phase` op `developing`. Pas geen code aan.

### 8.4 Test-DB

Per preview-deploy wordt al een eigen DB-schema gebruikt (zelfde Neon-DB, ander schema per `pnf-pr-N`). Tester krijgt de connection-string van het juiste schema via env-var, ingespoten door de poller.

### 8.5 Iteratie-limiet

Geen hard limit (per gebruikersbeslissing) — wel monitoring via cost-monitor. Als dev↔test pingpong > 5 rondes: automatic comment "veel iteraties — handmatige triage suggestie".

---

## 9. Dashboard

### 9.1 Hoofdpagina — visuele flow per story

Op de bestaande "AI bezig"-sectie krijgt elke kaart een **pipeline-balk** en **kosten-info**:

```
🤖 KAN-42 — Voeg dark mode toe                                  [Pauzeer]
AI In Progress · phase=developing · 4m geleden gestart · Level 3 · Log →
[ refine ✓ ]──[ develop ● ]──[ review ◯ ]──[ test ◯ ]
Tokens: 12.3K in / 4.5K out (42% van 40K budget)
```

Tokens-balk kleurt:
- groen < 75%
- geel 75-100%
- rood > 100% (= status zit op Needs Info wegens budget)

CSS-only, geen JS — past in het bestaande dashboard-patroon.

### 9.2 Story-detail-pagina `/story/<key>`

Een aparte pagina die per story de volledige geschiedenis toont:

- Header: story-titel, link naar JIRA, current status, current AI Phase
- Knoppen: "Pauzeer", "Hervat", "Set level", "Set budget"
- Tijdlijn van alle `agent_runs` op een verticale as:
  ```
  ┌─ 10:42  refiner       0:14  ✓ success   1.2K in / 0.4K out  →  Log
  ├─ 10:43  refiner       0:23  ⚠ questions  0.8K in / 0.5K out  →  Log
  │  (PO antwoordt om 11:02)
  ├─ 11:03  refiner       0:08  ✓ success   0.5K in / 0.2K out  →  Log
  ├─ 11:04  developer     2:41  ✓ success   8.4K in / 2.1K out  →  Log
  ├─ 11:08  reviewer      0:38  ⚠ comments   1.5K in / 0.8K out  →  Log
  ├─ 11:10  developer     1:12  ✓ success   3.2K in / 0.9K out  →  Log  (S-09 iteratie)
  └─ 11:13  tester        4:55  ⚠ bug        2.1K in / 1.4K out  →  Log
  ```
- Cumulatief: **17.7K in / 6.3K out (60% van 40K budget) · ~$0.08 equivalent**
- Log-link gaat naar `/runner/<job_name>/log` voor agents die nog een pod hebben, of `/agent-run/<id>/log` voor historische runs uit de DB.

### 9.3 Logs uit DB i.p.v. pod

Het bestaande `/runner/<job>/log`-endpoint blijft, maar krijgt fallback naar de DB:
1. Pod nog actief / TTL nog niet voorbij → live pod-log (zoals nu).
2. Pod weg → reconstrueer uit `agent_events`-tabel; reproduceer dezelfde jq-pretty-format als de live-logs.

Daarmee blijft elke log eeuwig toegankelijk vanuit het dashboard, ook lang na de 2u-pod-TTL.

### 9.4 Filters en zoeken (latere uitbreiding)

- Filter op rol (alleen tester-runs tonen)
- Full-text-zoek over `agent_events.payload` (welke story heeft ooit `flutter_bootstrap.js` aangeraakt)
- Voor later — geen prio in eerste implementatie.

---

## 10. Namespace-indeling

Drie namespaces, duidelijk gescheiden:

| Namespace | Inhoud |
|---|---|
| `personal-news-feed` | De **applicatie zelf**: frontend, backend, tunnel, preview-router. |
| `pnf-software-factory` (nieuw) | De **factory**: jira-poller, status-dashboard, alle claude-runner Jobs (developer/refiner/reviewer/tester), cost-monitor CronJob, factory-db-init-Job. |
| `pnf-pr-<N>` | Per open PR een **preview-deploy** van de app. |

`preview-ns-labeller` blijft in `argocd` namespace (waar 'ie nu draait).

---

## 11. Concurrency en quota

Per rol een eigen cap. Standaardinstelling in poller-config:

```
MAX_PARALLEL_REFINER=5
MAX_PARALLEL_DEVELOPER=2
MAX_PARALLEL_REVIEWER=3
MAX_PARALLEL_TESTER=1
```

Bij hitting van de cap: nieuwe stories blijven op `AI Ready` of `AI In Progress` met `AI Phase` ongewijzigd; volgende poll-tick probeert opnieuw.

**Globaal token-quota** wordt niet door de poller bewaakt — dat is de verantwoordelijkheid van Anthropic's API zelf (HTTP 429 bij overschrijding) plus de cost-monitor per story. Bij 429 retry'et de runner met exponential backoff (3 pogingen); blijft 't falen, dan transition naar AI Needs Info met `[RUNNER] HTTP 429 — Anthropic-quota uitgeput, probeer over X uur opnieuw.`

---

## 12. Risico's en open punten

| Risico | Impact | Mitigatie |
|---|---|---|
| Tester muteert prod-infra ondanks prompt-restrictie | Hoog | RBAC schrijft alleen in pnf-pr-*; NetworkPolicy beperkt egress |
| Dev↔tester infinite loop | Medium | Cost-monitor pakt 't sowieso op via budget; soft warning na 5 rondes |
| Anthropic-quota uitgeput | Medium | 429-retry + transition naar Needs Info met duidelijke melding |
| Refiner stelt eindeloos vragen | Laag | Geen hard cap (gebruikerskeuze), maar cost-monitor pakt 't op |
| Logs bevatten secrets | Medium | Regex-redactie vóór DB-write; niet waterdicht |
| Pause op het verkeerde moment | Laag | Hard-kill verliest uncommitted werk — bewust, gedocumenteerd |
| JIRA als state-machine schaalt slecht | Laag (nu), medium (later) | Bij > 10 statussen migreren naar Argo Workflows of Temporal — voor nu adequaat |

---

## 13. Wat dit document *niet* doet

- Geen volgordestappen of timelines — die staan in een aparte `implementation-plan.md`.
- Geen concrete YAML/Python — die komen pas in PR's.
- Geen API-contracten op endpoint-niveau — die volgen per fase als 'er een endpoint nodig is.
- Geen kalibratie-richtlijnen voor het optimale `AI Token Budget` — die ontwikkelen we empirisch nadat de eerste paar stories gedraaid hebben.
- Geen tweede tenant / tweede repo — buiten scope.

---

## 14. Verwijzingen

- [ai-pipeline-roadmap.md](./ai-pipeline-roadmap.md) — historische S-01 t/m S-09 (claude-runner-fundament)
- [branch-commit-convention.md](./branch-commit-convention.md) — branch-naming + commit-format
- [backend-functional-spec.md](./backend-functional-spec.md) — applicatie-gedrag (buiten deze factory)
- Bestaande implementatie:
  - [deploy/jira-poller/poller.py](../deploy/jira-poller/poller.py) — wordt uitgebreid tot orchestrator
  - [deploy/claude-runner/runner.sh](../deploy/claude-runner/runner.sh) — wordt uitgebreid voor rol-injectie + DB-rapportage
  - [deploy/status-dashboard/app.py](../deploy/status-dashboard/app.py) — wordt uitgebreid voor pipeline-visualisatie
