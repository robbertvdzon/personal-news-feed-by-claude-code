# Software Factory — Specs (v2)

## 1. Doel

De **software factory** is een autonome pijplijn die Jira-stories door een
keten van AI-agents loodst: van refinen → ontwikkelen → reviewen → testen.
Een ticket dat in Jira de status **AI** krijgt, wordt door de factory
opgepakt en doorloopt automatisch alle fases tot het ticket succesvol
getest is — of vastloopt op een vraag voor de gebruiker, of het budget
opraakt.

Deze v2 is een **complete herimplementatie in Kotlin** van de bestaande
Python-implementatie. Onderaan staan de bestaande Python-bestanden ter
referentie; bedoeling is dat de Kotlin-versie deze vervangt zonder dat
features verloren gaan.

---

## 2. Architectuur op hoofdlijnen

- **Taal/stack:** Kotlin, JDK 21, Spring Boot, Maven multi-module,
  Spring Modulith.
- **Orchestrator:** één Spring Boot service die polt, dispatcht,
  K8s-Jobs spawnt, recovery doet, en HTTP-endpoints biedt voor de
  agents (usage-rapportage, tips-database).
- **Jira-detectie:** polling (geen webhooks). De orchestrator polt elke
  **15 seconden** alle tickets met status `AI`.
- **Agent-runtime:** elke agent draait als losse **Docker container**
  (één K8s Job per agent-run) die wordt gestart, zijn taak uitvoert
  (ticket bijwerken), en weer stopt. Twee images: een **agent-base**
  (refiner/developer/reviewer) en een **agent-tester** die extra
  browser- en cluster-tooling meebrengt.
- **Isolatie per ticket:** elke agent-run werkt op een eigen
  shallow git-clone van de target-repo, zodat parallelle tickets
  elkaar niet raken.
- **AI-aanroep:** agents praten met een AI-model via een CLI tool
  (placeholder; concrete tool wordt in een latere iteratie vastgelegd
  — zie §11).
- **Persistentie:** **Neon Postgres**, één database, eigen schema
  `factory`. Schema-migraties via Flyway.

---

## 3. Jira-integratie

### 3.1 Status

Er zijn meerdere statussen nodig — niet alleen `AI` en `Done`. Zonder
extra statussen kan de orchestrator niet onderscheiden tussen "agent
draait" en "wacht op gebruiker", en kan een gepauzeerde story niet
betrouwbaar hervat worden.

| Status           | Betekenis                                                                          | Wie zet 'm                                |
|------------------|------------------------------------------------------------------------------------|-------------------------------------------|
| `AI`             | Nieuwe story, opgepakt door de factory.                                            | gebruiker (initiële markering)            |
| `AI Queued`      | Een agent-fase is afgerond; de orchestrator dispatcht zo de volgende agent.        | agent (na completion) of gebruiker        |
| `AI In Progress` | Een agent-Job draait nu echt in K8s.                                               | orchestrator (alleen die mag deze status) |
| `AI Needs Info`  | Een agent heeft een vraag of het budget is op — gebruiker is aan zet.              | agent of cost-monitor                     |
| `AI Paused`      | Handmatig stilgezet (lopende Job wordt hard-gekilled).                             | gebruiker (of dashboard later)            |
| `Done`           | Pipeline klaar (eindstatus na `tested-succesfully` of na een handmatig commando).  | orchestrator                              |

**Regel:** alleen de orchestrator mag status op `AI In Progress` zetten.
Daarmee is "draait er iets?" altijd betrouwbaar.

### 3.2 Custom fields

| Veld              | Type                         | Default | Doel                                                                |
|-------------------|------------------------------|---------|---------------------------------------------------------------------|
| `AI Phase`        | enum (zie §4)                | leeg    | Fijnmazige state binnen de factory.                                 |
| `AI Resume Phase` | enum (active-phases)         | leeg    | Naar welke active-phase resumen na `AI Needs Info` / `AI Paused`.   |
| `AI Level`        | number 0–10                  | 0       | Welke `(model, effort)`-matrix de agents gebruiken (zie §6).        |
| `AI Token Budget` | number                       | 40000   | Hard cap op totaal token-verbruik (alle agents samen, zie §7).      |
| `AI Tokens Used`  | number                       | 0       | Lopend totaal, onderhouden door orchestrator/cost-monitor.          |
| `AgentStartedAt`  | timestamp                    | leeg    | Wanneer de huidige actieve agent startte (voor hang-detectie).      |
| `AgentType`       | string                       | leeg    | Welke agent op dit moment draait — handig voor monitoring.          |

`AI Phase`, `AI Resume Phase`, `AI Tokens Used`, `AgentStartedAt` en
`AgentType` zijn systeem-velden (PO komt er niet aan). `AI Level` en
`AI Token Budget` mag de PO bewerken op elke status.

### 3.3 Comment-conventie

Elke agent prefixt z'n Jira-comments met z'n rol — mensvriendelijk én
filterbaar door de orchestrator:

```
[REFINER]      Vraag over de export-feature: …
[DEVELOPER]    Implementatie klaar, branch ai/KAN-42, PR #123 open.
[REVIEWER]     Op regel 42 mist een try/catch rond …
[TESTER]       Reproductie: open /admin/users, klik "+", crasht …
[COST-MONITOR] Budget bereikt: 47K/40K tokens. Verhoog of bevestig.
```

De orchestrator herkent agent-comments aan deze prefix en negeert ze
bij eventuele user-input-detectie (anders triggert een reviewer-comment
de developer steeds opnieuw).

### 3.4 Feedback van de gebruiker — comment-tracking met reacties

Een agent (vooral de refiner, maar in principe elke rol) kan vragen
stellen aan de gebruiker. De gebruiker antwoordt in een Jira-comment.
Bij re-spawn moet de agent kunnen onderscheiden welke comments hij al
verwerkt heeft en welke nieuw zijn — anders raakt hij de draad kwijt
bij meerdere antwoord-rondes.

**Mechanisme:** zodra een agent een gebruiker-comment heeft gelezen
én verwerkt, zet hij een **reactie** op die comment (bijvoorbeeld 👀
of ✅). Bij een volgende run weet hij: alleen comments **zonder** mijn
eigen reactie zijn nieuw. Comments waarop hij al heeft gereageerd zijn
afgehandeld.

- De Jira-comment-reactie is **visueel zichtbaar** voor de gebruiker,
  zodat die weet dat zijn antwoord is opgepikt.
- De reactie wordt gezet via de Jira REST API. Als reactie-endpoints
  in een toekomstige Jira-versie niet beschikbaar blijken, valt de
  agent terug op een **marker-tag in de body van zijn eigen
  reply-comment** (bv. een verborgen suffix `<!-- ack:<comment_id> -->`)
  of een tabel in de factory-DB die per (ticket, comment_id, role) een
  `processed_at`-record bijhoudt.
- Dezelfde regel geldt voor de **developer** bij `[REVIEWER]`- en
  `[TESTER]`-comments: hij plaatst een reactie zodra hij die feedback
  heeft verwerkt, zodat hij bij een volgende loopback alleen nieuwe
  feedback hoeft te lezen.

---

## 4. Phase state machine

### 4.1 Phase-waardes

**Active phases** (er draait een Job — orchestrator zet deze):

| Phase        | Betekenis                  |
|--------------|----------------------------|
| `refining`   | Refiner-Job draait.        |
| `developing` | Developer-Job draait.      |
| `reviewing`  | Reviewer-Job draait.       |
| `testing`    | Tester-Job draait.         |

**Completed phases** (Job klaar — agents zetten deze):

| Phase                                  | Betekenis                                              | Orchestrator dispatcht hierna |
|----------------------------------------|--------------------------------------------------------|-------------------------------|
| `refined-with-questions-for-user`      | Refiner heeft openstaande vragen.                      | niets (wacht op gebruiker)    |
| `refined-finished`                     | Refinement klaar, klaar om te ontwikkelen.             | developer → `developing`      |
| `developed`                            | Developer-code in branch + PR open.                    | reviewer → `reviewing`        |
| `reviewed-with-feedback-for-developer` | Reviewer heeft op- of aanmerkingen.                    | developer → `developing`      |
| `review-finished`                      | Review akkoord.                                        | tester → `testing`            |
| `tested-with-feedback-for-developer`   | Tester vond bug(s).                                    | developer → `developing`      |
| `tested-succesfully`                   | Eindstatus: alles klaar.                               | status → `Done`               |

**Speciale phases:**

| Phase             | Betekenis                                                                                       |
|-------------------|-------------------------------------------------------------------------------------------------|
| `awaiting-po`     | Een agent wacht op de gebruiker (gecombineerd met status `AI Needs Info`). `AI Resume Phase` houdt vast welke active-phase hervat moet worden. |
| `questions-answered` | Gebruiker heeft vragen beantwoord — markeert "klaar om weer te dispatchen". Orchestrator vertaalt dit naar de actieve `*ing`-phase via `AI Resume Phase`. |

### 4.2 Transitietabel (door de orchestrator, behalve waar anders aangegeven)

```
(leeg)                                 → start refiner    → AI In Progress + phase=refining
refined-with-questions-for-user        → (wachten op gebruiker, status=AI Needs Info)
questions-answered                     → start refiner    → AI In Progress + phase=refining
refined-finished                       → start developer  → AI In Progress + phase=developing
developed                              → start reviewer   → AI In Progress + phase=reviewing
reviewed-with-feedback-for-developer   → start developer  → AI In Progress + phase=developing
review-finished                        → start tester     → AI In Progress + phase=testing
tested-with-feedback-for-developer     → start developer  → AI In Progress + phase=developing
tested-succesfully                     → status=Done
awaiting-po                            → status=AI Needs Info (niets doen tot gebruiker reageert)
```

Agents zetten zelf de "klare" phase (`developed`, `review-finished`,
etc.) bij voltooiing. De orchestrator detecteert die bij de volgende
poll, zet status op `AI In Progress`, schrijft de bijbehorende
`*ing`-phase en start de volgende agent.

---

## 5. Orchestrator

### 5.1 Polling

- **Poll-interval:** 15 seconden.
- **Per cyclus:** haal alle tickets op met Jira-status in
  `{AI, AI Queued, AI In Progress, AI Needs Info, AI Paused}`. Voor
  elk ticket:
  1. Bepaal aan de hand van Phase + Status wat de volgende actie is
     (zie §4.2).
  2. Als een agent gestart moet worden (concurrency-cap toelaat — zie
     §5.4): zet `AI Phase` op de actieve waarde (`refining`,
     `developing`, …), zet `AgentStartedAt` op nu, zet `AgentType`,
     en start de bijbehorende K8s-Job.
  3. Als Phase een `*ing`-waarde is (er hoort een agent te draaien):
     check K8s of de Job nog bestaat en actief is. Zo niet → §5.3
     stuck-detection.
  4. Als Phase een wacht-op-gebruiker waarde is
     (`refined-with-questions-for-user`, `awaiting-po`): niets doen.
  5. Als Phase `tested-succesfully` is: status → `Done` afronden.

### 5.2 Merge-detectie

Naast de phase-driven dispatch monitort de orchestrator open PR's
van actieve stories. Als een mens een PR mergt en de bijbehorende
story op `tested-succesfully` staat (of de gebruiker mergde voortijdig
via een handmatig commando — zie §10), zet de orchestrator de status
op `Done` en sluit het story-run-record af.

### 5.3 Stuck-detection & recovery

Phase staat op `*ing` maar er draait geen K8s-Job (gecrashte agent,
node-eviction, OOM, etc.). De orchestrator scant elke cyclus op deze
inconsistentie en herstelt:

- **Forward-recovery** — als er een succesvolle agent-run in de DB
  staat voor de huidige phase maar de phase nog niet bijgewerkt is:
  zet de bijbehorende completed-phase. (Voorbeeld: refiner liep
  succesvol, postte een comment, maar crashte voor hij `AI Phase`
  kon updaten → orchestrator zet `refined-finished` alsnog.)
- **Backward-retry** — als de laatste agent-run gefaald is met een
  transient fout (HTTP 429, "API error 500", "rate limit", "timeout"
  in de samenvatting): zet phase terug naar de vorige completed-phase
  zodat dezelfde agent opnieuw start. Hard cap: **max 2 opeenvolgende
  transient retries** per rol per story; daarna `AI Needs Info` met
  een uitlegcomment.
- **Hard timeout** — `AgentStartedAt` ouder dan **30 minuten** zonder
  voortgang (configureerbaar): log + markeer. In de eerste iteratie
  alleen loggen + status op `AI Needs Info`; later eventueel
  automatisch kill + retry.

### 5.4 Concurrency

Caps per rol (configureerbaar, defaults):

```
MAX_PARALLEL_REFINER   = 1
MAX_PARALLEL_DEVELOPER = 2
MAX_PARALLEL_REVIEWER  = 2
MAX_PARALLEL_TESTER    = 1   # tester is duur door browser-pod
MAX_PARALLEL_TOTAAL    = 4   # globale veiligheid
```

Bij hitting van een cap: stories die gedispatched zouden worden
blijven in `AI Queued` (of `AI`) staan; volgende poll-tick probeert
opnieuw.

Per **PR** geldt bovendien een cap van **1 actieve Job**, om te
voorkomen dat twee dispatch-paden tegelijk dezelfde branch verbouwen.

---

## 6. Agents

### 6.1 Algemeen

Alle agents:

- Lezen het ticket (inclusief comments + reacties) uit Jira.
- Hebben toegang tot de tips-database (lezen + schrijven, alleen
  eigen rol — zie §8).
- Hebben toegang tot een AI-model via een CLI tool (placeholder).
- Werken aan een eigen shallow git-clone van de target-repo.
- Schrijven hun resultaat terug naar Jira: nieuwe Phase + eventuele
  comment (met `[ROLE]`-prefix).
- Plaatsen reacties op user-comments die ze hebben verwerkt (§3.4).
- Rapporteren bij voltooiing token-usage + events naar de
  orchestrator (HTTP `POST /agent-run/complete`) — voor cost-monitor
  en observability.
- Exit-code 0 = succes, non-zero = fout (orchestrator logt en
  triggert stuck-detection bij volgende cyclus).

### 6.2 Refiner

- Input: ruwe story + alle prior comments (met respect voor reacties
  uit §3.4 — eerder verwerkte comments mag hij negeren).
- Output: opgeschoonde story (acceptatie-criteria, scope-afbakening)
  → Phase `refined-finished`,
  óf openstaande vragen als comment → Phase
  `refined-with-questions-for-user` + status `AI Needs Info`.
- **Tool-allowlist:** alleen Jira-API + read op de repo; **geen**
  edit/write tools (zodat de refiner per ongeluk geen code schrijft).
- Belangrijk: bij een tweede ronde refinen leest hij **alleen**
  user-comments zonder zijn reactie. Comments waarop hij al
  gereageerd heeft zijn afgehandeld.

### 6.3 Developer

- Input: refined story + review- en test-feedback uit comments
  (alleen die zonder zijn reactie zijn nieuw).
- Krijgt via env-var `DEVELOPER_LOOPBACK_REASON` een hint mee als hij
  vanuit een review- of test-loopback is gespawnd: "lees eerst het
  laatste `[REVIEWER]`/`[TESTER]`-comment".
- Output: code-wijzigingen in een branch (`ai/<TICKET_KEY>`), commit
  + push, GitHub PR open of bestaande PR updaten → Phase `developed`.
- Markeert verwerkte reviewer-/tester-comments met een reactie (§3.4).

### 6.4 Reviewer

- Input: de PR-diff (via `gh pr diff` of equivalent) + refined story.
- **Tool-allowlist:** read-only op repo, `gh` CLI, Jira-API; **geen**
  edit/write, **geen** git push.
- Output: review-feedback als comment (met `[REVIEWER]`-prefix) →
  Phase `reviewed-with-feedback-for-developer`,
  óf goedkeuring → Phase `review-finished`.

### 6.5 Tester

De gevaarlijkste agent qua blast-radius — verdient extra grenzen.

- Image: **agent-tester** (zie §11).
- **Tool-allowlist:** Playwright/Chromium voor headless browser,
  `psql` tegen de preview-DB, `kubectl`/`oc` met read-only RBAC
  cluster-wide + schrijfrechten alleen in `pnf-pr-*`-namespaces.
- Wacht aan het begin tot de **preview-deploy** van de PR live is
  (HTTP 200 op `pnf-pr-<num>.<host>`, max 10 min polling per 15s —
  zie §9).
- Krijgt `PREVIEW_URL` + `PREVIEW_DB_URL` als env-vars mee.
- Output: bij bug(s) → comment met reproductie-stappen + logs →
  Phase `tested-with-feedback-for-developer`. Bij OK → Phase
  `tested-succesfully`.
- **System-prompt-grenzen** (in aanvulling op RBAC):
  - **MAG NIET**: infrastructuur muteren in `personal-news-feed`,
    git-commits maken, secrets aanpassen, prod-namespace muteren.
  - **MAG WEL**: lezen van alles in `personal-news-feed` en
    `pnf-pr-*`, `oc exec` in pnf-pr-pods, DB-queries lezen + schrijven
    in de preview-DB, een pod-restart forceren in een pnf-pr-namespace.

---

## 7. AI CLI tool & model-routing

### 7.1 Placeholder-interface

De agent-code definieert een Kotlin-interface `AiClient` met één
implementatie die een externe CLI binary aanroept. Concrete keuze
(bv. Claude Code CLI, Aider, een eigen wrapper) wordt in een volgende
iteratie vastgelegd, samen met het wire-protocol (stdin/stdout JSON?
command-line prompts?).

### 7.2 Level-matrix

Per ticket geldt een `AI Level` (0–10). Een ConfigMap mapt elk level
naar een `(model, effort)`-combinatie per rol. Cheapest first.

```yaml
models:
  cheap:    { model: claude-haiku-4-5,   effort: quick   }
  cheap+:   { model: claude-haiku-4-5,   effort: default }
  mid:      { model: claude-sonnet-4-6,  effort: quick   }
  mid+:     { model: claude-sonnet-4-6,  effort: default }
  mid++:    { model: claude-sonnet-4-6,  effort: deep    }
  premium:  { model: claude-opus-4-7,    effort: default }
  premium+: { model: claude-opus-4-7,    effort: deep    }

levels:
  0:  { refiner: cheap,  developer: cheap,    reviewer: cheap,    tester: cheap   }
  1:  { refiner: cheap,  developer: cheap+,   reviewer: cheap,    tester: cheap   }
  2:  { refiner: cheap,  developer: mid,      reviewer: cheap,    tester: cheap   }
  3:  { refiner: cheap,  developer: mid,      reviewer: cheap+,   tester: cheap+  }
  4:  { refiner: cheap+, developer: mid+,     reviewer: mid,      tester: mid     }
  5:  { refiner: cheap+, developer: mid+,     reviewer: mid+,     tester: mid+    }
  6:  { refiner: mid,    developer: mid++,    reviewer: mid+,     tester: mid+    }
  7:  { refiner: mid,    developer: premium,  reviewer: mid+,     tester: mid+    }
  8:  { refiner: mid,    developer: premium,  reviewer: mid++,    tester: mid+    }
  9:  { refiner: mid+,   developer: premium+, reviewer: premium,  tester: mid++   }
  10: { refiner: mid+,   developer: premium+, reviewer: premium+, tester: premium }
```

De orchestrator leest het level bij dispatch, mapt het via deze
ConfigMap, en geeft `CLAUDE_MODEL` + `CLAUDE_EFFORT` als env-vars
mee aan de Job.

### 7.3 Override via comment

De gebruiker kan `AI Level` op elk moment aanpassen via het
Jira-veld, of via een comment-trigger `LEVEL=N` (zie §10.2).

---

## 8. Tips-database (agent-knowledge)

Agents bewaren herbruikbare kennis. Bv. de tester ontdekt hoe je in
een specifieke applicatie inlogt — die kennis slaat hij op zodat hij
dat de volgende keer niet opnieuw hoeft uit te zoeken.

- **Eén tabel** `factory.agent_knowledge` met `(role, category, key)`
  als unieke sleutel. Upsert-semantiek: last-writer-wins.
- **Toegang:** elke agent leest/schrijft alleen records van zijn
  eigen rol. Afgedwongen door de HTTP-endpoints (`GET
  /agent-knowledge?role=<role>` en `POST /agent-knowledge/update`).
- **Flow:** runner pakt aan het begin de tips op (geserialiseerd als
  markdown) en zet ze in `/work/repo/.agent-tips.md` zodat de
  AI-CLI ze in z'n context kan opnemen. Aan het einde schrijft de
  agent eventuele nieuwe/gewijzigde tips als JSON-blok in zijn
  output; de runner POST't die naar de orchestrator.
- **Velden** (zie ook §13): `id`, `role`, `category`, `key`,
  `content`, `created_at`, `updated_at`, `updated_by_story`.

---

## 9. PR-flow & preview-deploy

### 9.1 Branch & PR

- Branch-naam: `ai/<TICKET_KEY>` (configureerbaar `BRANCH_PREFIX`).
- Developer doet `git clone --depth 50` en checkt de branch uit (of
  maakt 'm aan vanaf `BASE_BRANCH`, default `main`).
- Bij voltooiing: `git push` + (indien nog niet bestaand)
  `gh pr create`. Bestaande PR wordt vanzelf bijgewerkt door de push.
- Bij loopback (review/test → developer): dezelfde branch en PR
  worden hergebruikt — geen nieuwe PR per iteratie.

### 9.2 Preview-deploy per PR

Voor elke open PR wordt automatisch een **preview-deploy** in een
eigen namespace `pnf-pr-<num>` opgeslingerd (door externe tooling;
buiten scope van deze spec, maar de factory leunt erop).

- De tester krijgt `PREVIEW_URL` (`pnf-pr-<num>.<host>`) en
  `PREVIEW_DB_URL` (apart Postgres-schema per PR) als env-vars.
- De tester wacht tot de preview HTTP 200 geeft voor hij begint.
- De orchestrator ruimt de pnf-pr-namespace op bij merge of bij een
  handmatig commando (delete/re-implement — zie §10).

---

## 10. Handmatige bediening via comments

De gebruiker kan op elk moment via Jira-comments ingrijpen. De
orchestrator scant alle actieve stories per poll-cyclus op
commando-comments en triggers, en is idempotent (een verwerkte
comment krijgt een marker-reactie of marker-suffix zodat 'ie maar
één keer wordt uitgevoerd).

### 10.1 Commando's

| Comment                         | Effect                                                                                                                |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `@claude:command:pause`         | Kill lopende Job (hard); status → `AI Paused`; `AI Resume Phase` blijft de huidige active-phase.                      |
| `@claude:command:delete`        | Kill jobs, sluit PR + branch, delete preview-namespace, prepend `(CANCELLED)` aan titel, status → `Done`.             |
| `@claude:command:merge`         | Squash-merge de PR, kill jobs, delete preview-namespace, status → `Done`.                                             |
| `@claude:command:re-implement`  | Kill jobs, sluit PR, delete preview-namespace, delete agent-comments, status → `AI` (start opnieuw vanaf het begin).  |

Hervatten uit `AI Paused`: gebruiker zet de status terug op
`AI Queued`. De orchestrator leest `AI Resume Phase` en dispatcht
die agent opnieuw.

### 10.2 Triggers in vrije comments

| Patroon       | Effect                                                                                                          |
|---------------|-----------------------------------------------------------------------------------------------------------------|
| `LEVEL=N`     | Zet `AI Level` op N (0–10).                                                                                     |
| `BUDGET=N`    | Zet `AI Token Budget` op N tokens (absoluut). Gebruikelijk om de pipeline te hervatten uit `AI Needs Info`.     |
| `CONTINUE`    | Verhoogt `AI Token Budget` met +50% en hervat. Alleen actief op stories in `AI Needs Info` na een budget-stop.  |

### 10.3 PR-comment iteratie

Na het openen van een PR kan iemand in de PR-comments verder
ingrijpen met een `@claude`-mention. De orchestrator scant open
PR's op zulke triggers:

- Idempotentie via GitHub-comment-reacties:
  - 👀 = "claimed" (orchestrator heeft 'm opgepikt)
  - 🚀 = "done" (developer succesvol verwerkt)
  - 😕 = "failed"
- Context-build: alle PR-comments sinds de laatste 🚀-reactie worden
  als task-bundel doorgegeven aan een developer-Job in `mode=comment`.
- Comments met agent-prefix (`[REVIEWER]`, `[TESTER]`, …) worden
  expliciet **genegeerd** als trigger — anders zou de developer
  zichzelf loopen.

---

## 11. Docker images

Drie images, gescheiden zodat agents alleen pullen wat ze nodig
hebben.

| Image                 | Basis                      | Extra                                                            | Wie gebruikt 'm                  |
|-----------------------|----------------------------|------------------------------------------------------------------|----------------------------------|
| `factory-orchestrator`| `eclipse-temurin:21-jre`   | Kotlin fat-jar van de orchestrator + Flyway-migraties.           | orchestrator-service (Deployment)|
| `agent-base`          | `eclipse-temurin:21-jdk`   | Node 22, git, GitHub CLI (`gh`), `jq`, AI CLI, Kotlin agent-CLI. | refiner, developer, reviewer     |
| `agent-tester`        | `agent-base`               | Playwright + Chromium, `psql`, `kubectl`, `oc`, screenshot-helper.| tester                          |

Notities:

- **Eén entrypoint per image, rol via env-var.** `agent-base` heeft
  als ENTRYPOINT de Kotlin agent-CLI; `AGENT_TYPE`
  (`refiner`/`developer`/`reviewer`) bepaalt welke prompt + tool-set
  + completion-phase de agent gebruikt.
- **`agent-tester` erft de ENTRYPOINT** van `agent-base`; alleen de
  rol-detectie + extra tooling is anders.
- OpenShift-vriendelijk: UID 1001 + GID 0, schrijfbare workdir
  (`/work`), Maven- en npm-caches in apart pad.
- Multi-stage builds voor de Kotlin jars (Maven-cache als layer,
  fat-jar in een slanke final layer).
- CI tagt op `:sha-<commit>` én `:main`; deployments verwijzen naar
  de SHA-tag voor reproduceerbaarheid.

---

## 12. K8s-runner

Per agent-Job:

- **Job-spec:** `restartPolicy=Never`, `backoffLimit=0` (we doen zelf
  recovery in plaats van K8s te laten retryen), `ttlSecondsAfterFinished=7200`
  (logs 2 uur live tailbaar).
- **Resources:** typische requests 250 m CPU / 512 Mi RAM, limits
  2000 m / 2 Gi.
- **Labels:** `app=factory-agent`, `story-key=<KAN-XX>`, `role=<role>`,
  `mode=story|comment`, optioneel `pr-num=<num>`.
- **ServiceAccount:** default voor refiner/developer/reviewer;
  aparte `agent-tester` SA voor de tester (met de RBAC uit §6.5).
- **Env-vars** (selectie): `TICKET_KEY`, `JOB_NAME`,
  `FACTORY_ORCHESTRATOR_URL`, `AGENT_TYPE`, `AI_LEVEL`, `CLAUDE_MODEL`,
  `CLAUDE_EFFORT`, `REPO_URL`, `BASE_BRANCH`, `BRANCH_PREFIX`,
  `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_FIELD_AI_PHASE`,
  `JIRA_FIELD_AI_RESUME_PHASE`, `JIRA_FIELD_AGENT_STARTED_AT`,
  `JIRA_FIELD_AGENT_TYPE`, `PREVIEW_URL`, `PREVIEW_DB_URL`,
  `PR_NUMBER`, `DEVELOPER_LOOPBACK_REASON`, plus secrets
  (`GITHUB_TOKEN`, `JIRA_API_KEY`, AI-CLI-credentials).
- **Task-payload:** de orchestrator schrijft een ConfigMap met
  `task.md` (de samengestelde context: story + relevante comments
  + tips) en mount die in de Job. ConfigMap krijgt een
  ownerReference naar de Job zodat hij auto-cleaned wordt.

De runner-flow zelf: `git clone` → tips ophalen → AI CLI aanroepen
→ output verwerken → Jira bijwerken (phase + comment + reacties op
verwerkte user-comments) → `POST /agent-run/complete` naar de
orchestrator → exit.

---

## 13. Persistentie (Neon Postgres)

Eén Neon-database, eigen schema `factory`. Migraties via Flyway
(versioned SQL onder `db/migration/`). Connection-string via env-var
`FACTORY_DATABASE_URL` (`postgresql://…`-formaat).

### 13.1 Tabellen

```sql
CREATE SCHEMA IF NOT EXISTS factory;

-- Eén row per pipeline-run van een ticket.
CREATE TABLE factory.story_runs (
  id                          BIGSERIAL PRIMARY KEY,
  story_key                   TEXT NOT NULL,
  started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at                    TIMESTAMPTZ,
  final_status                TEXT,                 -- 'Done', 'paused', 'budget-exceeded', ...
  total_input_tokens          INTEGER NOT NULL DEFAULT 0,
  total_output_tokens         INTEGER NOT NULL DEFAULT 0,
  total_cache_read_tokens     INTEGER NOT NULL DEFAULT 0,
  total_cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
  total_cost_usd_est          NUMERIC(10,4) NOT NULL DEFAULT 0.0
);

-- Eén row per K8s-Job/agent-run.
CREATE TABLE factory.agent_runs (
  id                          BIGSERIAL PRIMARY KEY,
  story_run_id                BIGINT NOT NULL REFERENCES factory.story_runs(id) ON DELETE CASCADE,
  role                        TEXT NOT NULL,        -- 'refiner' | 'developer' | 'reviewer' | 'tester'
  job_name                    TEXT NOT NULL,
  model                       TEXT,
  effort                      TEXT,
  level                       SMALLINT,
  started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at                    TIMESTAMPTZ,
  outcome                     TEXT,                 -- 'success' | 'failed' | 'questions' | 'killed'
  input_tokens                INTEGER NOT NULL DEFAULT 0,
  output_tokens               INTEGER NOT NULL DEFAULT 0,
  cache_read_input_tokens     INTEGER NOT NULL DEFAULT 0,
  cache_creation_input_tokens INTEGER NOT NULL DEFAULT 0,
  num_turns                   INTEGER NOT NULL DEFAULT 0,
  duration_ms                 INTEGER NOT NULL DEFAULT 0,
  cost_usd_est                NUMERIC(10,4) NOT NULL DEFAULT 0.0,
  summary_text                TEXT                  -- wat de agent als comment op Jira plaatste
);

-- Eén row per stream-event uit de AI CLI (debug/replay).
CREATE TABLE factory.agent_events (
  id              BIGSERIAL PRIMARY KEY,
  agent_run_id    BIGINT NOT NULL REFERENCES factory.agent_runs(id) ON DELETE CASCADE,
  ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
  kind            TEXT NOT NULL,
  payload         JSONB NOT NULL
);

-- Tips per rol.
CREATE TABLE factory.agent_knowledge (
  id                BIGSERIAL PRIMARY KEY,
  role              TEXT NOT NULL,
  category          TEXT NOT NULL,
  key               TEXT NOT NULL,
  content           TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by_story  TEXT,
  UNIQUE (role, category, key)
);

-- Verwerkte user-comments (fallback voor §3.4 als Jira-reacties
-- onbruikbaar zijn).
CREATE TABLE factory.processed_comments (
  id            BIGSERIAL PRIMARY KEY,
  story_key     TEXT NOT NULL,
  comment_id    TEXT NOT NULL,
  role          TEXT NOT NULL,
  processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (story_key, comment_id, role)
);
```

### 13.2 Secret-redactie

Vóór events naar `agent_events.payload` geschreven worden, draait een
regex-filter dat de volgende patronen vervangt door `<REDACTED>`:

- `sk-ant-(api03|oat01)-[A-Za-z0-9_-]+` (Anthropic-keys)
- `ghp_[A-Za-z0-9]{36,}` (GitHub PATs)
- `eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+` (JWT's)
- `postgresql://[^/]*:[^@]*@` (credentials in connection-strings)

Niet waterdicht, maar reduceert het ongelukken-risico aanzienlijk.
Bewaartermijn: vooralsnog ongelimiteerd.

---

## 14. Cost-monitor & token-budget

### 14.1 Wat hij doet

- Sommeert per actieve story het token-verbruik uit `factory.agent_runs`.
- Vergelijkt met `AI Token Budget` (default 40.000).
- Drempels:
  - **≥ 75 %** → comment `[COST-MONITOR] 75% bereikt …`, geen status-wijziging.
  - **≥ 90 %** → comment `[COST-MONITOR] 90% bereikt …`, geen status-wijziging.
  - **≥ 100 %** → comment `[COST-MONITOR] 100% bereikt — pauzeer.`,
    status → `AI Needs Info`, phase → `awaiting-po`,
    `AI Resume Phase` ← huidige active-phase.

Idempotent: bestaande `[COST-MONITOR] N%`-markers blokkeren
herhaalde posts van dezelfde drempel.

### 14.2 Twee triggers

- **Realtime** — de runner POST't z'n usage naar
  `POST /agent-run/complete` op de orchestrator direct na completion.
  De orchestrator update `factory.agent_runs` + draait meteen de
  budget-check.
- **Sanity-net cronjob** — elke 5 minuten een job die hetzelfde scan
  doet over alle actieve `story_runs`. Vangt het geval op dat de
  runner crasht vóór hij kan rapporteren.

### 14.3 Hervatten

- `BUDGET=N` in een comment op een story in `AI Needs Info`: zet het
  budget absoluut, status terug naar `AI Queued`, orchestrator
  hervat via `AI Resume Phase`.
- `CONTINUE` zonder argument: `AI Token Budget *= 1.5`, status →
  `AI Queued`, hervatten.

---

## 15. RBAC & secrets

- **Orchestrator-SA**: read/write Jira (via API-key), read/write op
  `factory.*`-tabellen in Neon, full CRUD op K8s Jobs + ConfigMaps in
  de factory-namespace, delete op preview-namespaces voor cleanup,
  read op GitHub PR's voor merge-detectie.
- **Agent-base SA** (refiner/developer/reviewer): geen K8s-RBAC
  nodig — alleen Jira-API + GitHub via secret.
- **Agent-tester SA**: cluster-wide read op pods, logs, services,
  configmaps, secrets, namespaces, events; create op `pods/exec`
  alleen in `pnf-pr-*`-namespaces; delete op pods alleen in
  `pnf-pr-*`. NetworkPolicy beperkt egress.
- **Secrets**: één Kubernetes Secret met alle credentials
  (`JIRA_API_KEY`, `GITHUB_TOKEN`, AI-CLI-credentials,
  `FACTORY_DATABASE_URL`), gemount als env-vars in
  orchestrator-Deployment en in elke agent-Job via `secretKeyRef`.
  Beheerd via SealedSecrets in de deploy-repo.

---

## 16. Open punten / later te beslissen

- **Concrete AI CLI** en het wire-protocol (stdin/stdout JSON?
  command-line prompts?).
- **Eindstatus na `tested-succesfully`** (`Done` of `Closed` —
  afhankelijk van Jira-workflow).
- **Drempelwaarde voor "agent is vastgelopen"** (default-voorstel:
  30 min) + gedrag (alleen loggen vs kill + retry vs comment naar
  gebruiker).
- **Retry-beleid op de loop-back** (review/test → developer): nu
  ongelimiteerd; later eventueel max N pogingen + escalatie naar
  `AI Needs Info` met een samenvatting.
- **Jira-comment-reactie API**: of `eyes`/`checkmark` werkbaar zijn
  via de REST-API in onze Jira-versie. Zo niet, dan vervalt §3.4
  terug naar de `processed_comments`-tabel.
- **Dashboard** is bewust buiten scope in v2 (komt later).
- **Interactieve Claude-sessies** (KAN-61 in de huidige Python-impl)
  zijn ook buiten scope in v2.
