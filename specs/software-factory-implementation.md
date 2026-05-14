# Software Factory — implementatieplan

Stappenplan om [software-factory.md](./software-factory.md) te realiseren. Zeven fases die elk een werkend systeem opleveren, met dashboard-zichtbaarheid als kerneis per fase.

**Leeswijzer:**
- Elke fase heeft een eigen sectie met: doel, mijn werk (PRs), jouw werk, dashboard-uitbreiding, acceptatie en risico.
- Fases zijn **sequentieel** — een latere fase bouwt op de vorige.
- Per fase zijn 1-3 PRs verwacht. Ik open ze één voor één; jij merget op je tempo.

---

## Overzicht in één blik

| # | Fase | Mijn werk | Jouw werk | Status |
|---|---|---|---|---|
| 0 | Namespace `pnf-software-factory` | 1 PR | `oc apply` Application | — |
| 1 | Logging-DB foundation | 2 PRs | Sealed-secret regenereren | — |
| 2 | State-machine v2 + level-matrix | 2 PRs | JIRA-admin: statussen + custom fields | — |
| 3 | Cost-monitor + budget-enforcement | 1 PR | (alleen testen) | — |
| 4 | Refiner-agent | 1 PR | Test-story | — |
| 5 | Reviewer-agent | 1 PR | Test-story | — |
| 6 | Tester-agent | 2 PRs | RBAC apply + test-story | — |

Verwachting: 10-12 PRs in totaal. Verspreid over weken/maanden — niet in één weekend.

---

## Fase 0 — Namespace `pnf-software-factory`

### Doel

Alle factory-componenten (poller, dashboard, runner-Jobs) draaien in een eigen namespace, los van de applicatie. `personal-news-feed` blijft alleen frontend/backend/tunnel/preview-router. Functioneel verandert er niets — voorbereiding op alles wat volgt.

### Mijn werk

**PR 1:** Migratie van poller + dashboard + runner-Jobs naar nieuwe namespace.

- Nieuwe namespace-resource `pnf-software-factory` in een aparte kustomization.
- `deploy/tooling/kustomization.yaml` uitbreiden / verplaatsen — de Deployments krijgen `namespace: pnf-software-factory`.
- `deploy/jira-poller/rbac.yaml` + `deploy/status-dashboard/rbac.yaml`: ClusterRoleBindings naar `pnf-software-factory` herschrijven; ClusterRoles blijven cluster-wide.
- Sealed-secret-mirroring: de `newsfeed-api-keys`-secret in `personal-news-feed` blijft het origineel; via Reflector-annotation óók gemirrord naar `pnf-software-factory`.
- Poller-env-var `RUNNER_NAMESPACE` van `personal-news-feed` → `pnf-software-factory`.
- Dashboard-env-vars `PROD_NS` blijft `personal-news-feed` (om de app-pods te monitoren); nieuw `FACTORY_NS=pnf-software-factory` voor de runner-Jobs.
- `deploy/bootstrap.sh`: nieuwe stap "create pnf-software-factory namespace + label managed-by argocd"; tooling-Application krijgt nieuwe namespace.
- `deploy/argocd-application.yaml` of nieuwe `tooling-application.yaml`: `destination.namespace: pnf-software-factory`.

### Jouw werk

1. Mergen.
2. Eenmalig:
   ```bash
   oc apply -f deploy/tooling-application.yaml
   oc rollout status deployment/jira-poller -n pnf-software-factory
   oc rollout status deployment/status-dashboard -n pnf-software-factory
   ```
3. Verifiëren: oude pods in `personal-news-feed` (poller, dashboard) zijn weg, nieuwe pods in `pnf-software-factory` draaien.

### Dashboard-update

- Header krijgt regel "Factory namespace: pnf-software-factory" (klein, info).
- Verder onveranderd. Functioneel identiek aan vandaag.

### Acceptatie

- [ ] `oc get pods -n personal-news-feed` toont alleen app-pods (frontend, backend, tunnel, preview-router).
- [ ] `oc get pods -n pnf-software-factory` toont poller + dashboard.
- [ ] Bestaande runner-flow werkt nog: zet een ticket op AI Ready, controleer dat een Job verschijnt in `pnf-software-factory` en de PR opent zoals voorheen.

### Risico

Sealed-secret-reflectie kan even haken — als de `newsfeed-api-keys`-secret niet in de nieuwe namespace verschijnt, draait er niets. Mitigation: handmatige fallback `oc create secret generic newsfeed-api-keys -n pnf-software-factory --from-file=...` als tijdelijke workaround.

---

## Fase 1 — Logging-DB foundation

### Doel

Elke runner-Job rapporteert z'n token-verbruik en log-events naar de bestaande Neon-DB (schema `factory`). Dashboard kan voortaan tokens per story tonen — ook voor stories waarvan de pod al weg is. Voorbereiding op alles waar usage-data voor nodig is (cost-monitor, model-keuze tracking, kosten-analyse).

### Mijn werk

**PR 1:** Schema + init-Job + runner-rapportage.

- `deploy/tooling/factory-schema.sql` — DDL voor `factory.story_runs`, `factory.agent_runs`, `factory.agent_events`. Idempotent (`CREATE ... IF NOT EXISTS`).
- `deploy/tooling/factory-db-init-job.yaml` — K8s Job die `psql < factory-schema.sql` draait. Beheerd door ArgoCD; runt bij elke sync.
- `deploy/claude-runner/runner.sh`: na de `claude`-call wordt de `/tmp/claude.log.jsonl` doorgelopen, usage-record samengesteld, en POST'et naar een nieuw HTTP endpoint `POST /agent-run/complete` op de poller. Secret-redactie via regex vóór de POST.
- `deploy/jira-poller/poller.py`: nieuw HTTP endpoint dat de payload naar Postgres schrijft (insert in `agent_runs` + bulk-insert in `agent_events`).

**PR 2:** Dashboard leest uit DB.

- `deploy/status-dashboard/app.py`: voor elke PR-card / AI-bezig-card de som van `agent_runs.input_tokens + output_tokens` voor de meest recente story_run ophalen.
- `/runner/<job>/log` krijgt fallback: als pod weg is, reconstrueer log uit `agent_events` met dezelfde jq-pretty-format.
- Nieuwe route `/story/<key>` — timeline-pagina met alle agent_runs voor die story.

### Jouw werk

1. Verifieer dat `PNF_DATABASE_URL` in de sealed secret werkt (heb je al). Connection-string moet voor Python in `postgresql://`-format zijn:
   - Open `deploy/secrets-cluster.env` op je laptop.
   - Voeg toe: `FACTORY_DATABASE_URL=postgresql://...` met dezelfde credentials als `PNF_DATABASE_URL` maar zonder `jdbc:`-prefix.
   - `./deploy/seal-secrets.sh` → commit → push.
2. Pas mergen ná stap 1 — anders krijgt de init-Job geen connection-string.
3. Verifieer: `oc logs job/factory-db-init -n pnf-software-factory` toont "schema created" (of "exists, skipping").
4. Trigger een test-story (AI Ready). Na voltooiing: `oc exec -it deploy/jira-poller -n pnf-software-factory -- psql $FACTORY_DATABASE_URL -c "SELECT story_key, total_input_tokens, total_output_tokens FROM factory.story_runs ORDER BY started_at DESC LIMIT 5"` toont je nieuwe story.

### Dashboard-update

- **PR-card**: nieuwe regel `Tokens: 12.3K in / 4.5K out` onder de bestaande velden. Kleur grijs (geen budget-context nog).
- **AI bezig-card**: zelfde token-regel.
- **Nieuwe `/story/<key>`-pagina**: tijdlijn van alle agent_runs met duur, in/out tokens, status. Klikbaar vanuit elke kaart.
- **Log-link gedrag** verandert: als pod-log onbereikbaar (TTL voorbij), val terug op DB-reconstructie. Tot 2u oude pods nog live; ouder uit DB.

### Acceptatie

- [ ] `factory.story_runs` bevat één row per gestarte story
- [ ] `factory.agent_runs` bevat één row per runner-Job met token-counts
- [ ] `factory.agent_events` bevat alle stream-json-events
- [ ] Dashboard toont token-counts per PR
- [ ] `/story/<KAN-X>`-pagina werkt, ook voor een story waarvan de pod al gedeleted is
- [ ] Geen sk-ant-… of ghp_… patterns in `agent_events.payload` (steekproef)

### Risico

- Schema-DDL faalt op productie-DB door rechten-issue → init-Job crashloopt. Manueel oplosbaar met `GRANT CREATE ON DATABASE ... TO <user>`.
- Logs blijken groter dan verwacht → `agent_events` groeit hard. Geen retention-policy nu; eventueel later partitioning.

---

## Fase 2 — State-machine v2 + level-matrix

### Doel

De zes JIRA-statussen + custom fields uit het ontwerp gaan live. Poller wordt dispatcher die phase-mappings volgt. Developer-runner leest `AI Level` + `AI Token Budget` + level-matrix en kiest model/effort. Vóór deze fase: alleen "AI Ready → developer". Ná: complete state-machine met phases en levels.

### Mijn werk

**PR 1:** Level-matrix + poller-dispatcher.

- Nieuwe ConfigMap `deploy/tooling/agent-levels.yaml` met de model+level-matrix (uit §5 van het ontwerp).
- `poller.py`: dispatcher-mode die status + phase leest, level-matrix toepast, env-vars `CLAUDE_MODEL` + `CLAUDE_EFFORT` aan de Job-spec hangt.
- Nieuwe logica voor de queued-phases (refined, developed, reviewed-*, tested-*). Voor nu doet poller-dispatcher alleen iets met `refined → developer` — alle andere completed-phases worden no-op'd want die agents bestaan nog niet.
- `runner.sh`: `--model "$CLAUDE_MODEL"` en effort-injectie in de system-prompt.
- Bij completion: developer zet `phase=developed` (i.p.v. wat 'ie nu doet).

**PR 2:** JIRA-veld-integratie + dashboard.

- Poller leest 5 custom fields via JIRA-API. Defaults toepassen als velden leeg zijn.
- Comment-trigger-loop voor PR-comments uitbreiden: ook `LEVEL=N` en `BUDGET=N` herkennen en in JIRA-veld zetten.
- `dashboard/app.py`:
  - Pipeline-balk per kaart (`[ refine ✓ ]──[ develop ● ]──[ review ◯ ]──[ test ◯ ]`)
  - Level-badge + budget-tellers (groen/geel/rood obv tokens — budget-cap volgt in fase 3, maar de visualisatie kan al)
  - AI Phase + AI Resume Phase tonen in card-meta

### Jouw werk

1. **In JIRA-admin (eenmalig — vóór mergen!):**

   **Statussen toevoegen aan KAN-workflow:**
   - `AI Queued`
   - `AI Needs Info`
   - `AI Paused`

   (`AI Ready`, `AI In Progress`, `Klaar` heb je al.)

   **Workflow-transitions configureren** (zie §3.3 van het ontwerp voor de volledige lijst). Belangrijk dat **alle** transitions in JIRA klikbaar zijn — de poller kan anders niet wisselen.

   **Vijf custom fields aanmaken:**
   - `AI Level` — type: Number, default: 0, min: 0, max: 10
   - `AI Token Budget` — type: Number, default: 40000
   - `AI Phase` — type: Select List (single choice). Opties: `refining`, `developing`, `reviewing`, `testing`, `refined`, `developed`, `reviewed-ok`, `reviewed-changes`, `tested-ok`, `tested-fail`, `awaiting-po`
   - `AI Resume Phase` — type: Select List (single choice). Opties: `refining`, `developing`, `reviewing`, `testing`
   - `AI Tokens Used` — type: Number, default: 0

   `AI Phase`, `AI Resume Phase`, `AI Tokens Used` mag je instellen als alleen-zichtbaar (PO bewerkt ze niet). Voor de eerste tests kun je ze gewoon laten staan.

2. **Mergen** in deze volgorde: PR 1 eerst (poller-logica), PR 2 erna (dashboard).

3. Verifieer op een test-story:
   - Stel `AI Level=2` in en zet 'm op AI Ready.
   - Poller dispatcht developer met `model=claude-sonnet-4-6 effort=quick` (uit level-matrix).
   - Dashboard toont level-badge "Level 2" + pipeline-balk.
   - Na voltooiing: status `Klaar` (volgt nog steeds via merge-detect — geen reviewer/tester yet).

### Dashboard-update

- **Pipeline-balk**: vier vakjes per story (refine / develop / review / test) — voor nu alleen develop kleurt; rest is `◯`.
- **Level-badge** rechts boven elke kaart: "Level 2", "Level 7" etc.
- **Token-balk** onder de status-regel: `12.3K / 40K (31%)` — kleurt mee met budget-overschrijding (zie fase 3).
- **AI Phase** in de meta-regel: `In Progress · phase=developing`.

### Acceptatie

- [ ] Story met `AI Level=0` draait op haiku-quick
- [ ] Story met `AI Level=4` draait op sonnet-quick
- [ ] Story met `AI Level=10` draait op opus-deep
- [ ] Poller wisselt status correct: Ready → In Progress → Queued → In Progress (via dev → developed) → Klaar bij merge
- [ ] Dashboard toont pipeline-balk + level-badge per story
- [ ] Comment `LEVEL=5` op een PR updatet het JIRA-veld

### Risico

- JIRA-workflow-transitions verkeerd geconfigureerd → poller krijgt "transition not allowed" errors. Mitigatie: poller logt expliciet welke transitie werd geprobeerd; jij past JIRA-admin aan.
- Custom-field-types verkeerd (bv. AI Phase als free-text i.p.v. select) → poller schrijft strings, geen validatie. Eerste keer testen op één story.

---

## Fase 3 — Cost-monitor + budget-enforcement

### Doel

Per story een hard budget-cap. Bij overschrijding wordt de pipeline gepauzeerd en moet de PO ' bevestigen om door te gaan'. Voorkomt runaway-loops zoals refiner↔PO of dev↔tester.

### Mijn werk

**PR 1:** Cost-monitor.

- Nieuwe `deploy/cost-monitor/`-map met:
  - `cron.py` — Python-script: query `factory.story_runs` voor alle actieve stories, vergelijk met `AI Token Budget`, post JIRA-comment + status-transition bij overschrijding.
  - `Dockerfile` — minimaal image (python + psycopg + requests).
  - `cronjob.yaml` — K8s CronJob `*/5 * * * *`.
  - `rbac.yaml` — eigen ServiceAccount met DB-access (via secret) en JIRA-API-permissions (via secret, geen K8s-RBAC).
- Realtime-pad: de bestaande HTTP-endpoint `POST /agent-run/complete` op de poller (uit fase 1) doet meteen óók een budget-check.
- Drempelwaardes: 75% (soft warning, comment), 90% (urgenter comment), 100% (transition naar `AI Needs Info` met `phase=awaiting-po`, `resume_phase=<vorige>`).
- Comment-trigger `BUDGET=N` op AI Needs Info bumpt het budget en zet status terug naar `AI Queued`.
- Comment-trigger `CONTINUE` op AI Needs Info zonder budget-aanpassing → +50% budget en doorgaan.

### Jouw werk

1. Mergen.
2. CronJob verschijnt vanzelf via ArgoCD-sync (`deploy/tooling`-uitbreiding).
3. Verifiëren: test-story met `AI Token Budget=1000` (heel laag) — bij eerste agent-run zou 'ie direct in Needs Info moeten landen met budget-comment.
4. `BUDGET=80000` in een comment → poller bumpt het veld, story hervat.

### Dashboard-update

- **Budget-balk** kleurt nu actief: groen (<75%), geel (75-100%), rood (>100%).
- **Cost-monitor-comments** zichtbaar in story-timeline (`[COST-MONITOR] Budget bereikt`).
- Nieuwe **"Pauzeren"-knop** per actieve story-kaart: zet status op `AI Paused`, killt de huidige Job, schrijft `AI Resume Phase` weg. Reëel handig in samenspel met budget-pauzes.

### Acceptatie

- [ ] Story met laag budget pauzeert automatisch bij overschrijding
- [ ] Comment `BUDGET=80000` verhoogt budget en hervat de story
- [ ] Comment `CONTINUE` hervat met +50% budget
- [ ] Dashboard-balk kleurt correct mee
- [ ] Pauzeer-knop werkt: status → AI Paused, Job verdwijnt

### Risico

- Race conditions: cost-monitor draait elke 5 min, runner POST'et realtime. Beide kunnen tegelijk status willen wijzigen. Mitigatie: idempotent via marker-comments + JIRA-status check vóór transition.
- Runner crasht voordat 'ie kan POST'en → cost-monitor merkt 't pas bij volgende cron-tick. OK voor MVP.

---

## Fase 4 — Refiner-agent

### Doel

Vage stories worden niet meer direct door de developer opgepakt; eerst kijkt de refiner of er voldoende info is. Bij onduidelijkheid: vragen aan de PO. Verhoogt kans dat developer's eerste poging raak is — bespaart tokens.

### Mijn werk

**PR 1:** Refiner-rol.

- `deploy/claude-runner/Dockerfile`: blijft hetzelfde (zelfde image, andere prompt).
- `runner.sh`: leest env-var `AGENT_ROLE`. Voor `refiner`:
  - Andere system-prompt: "lees de story, stel vragen bij onduidelijkheden, geen code"
  - Andere tool-allowlist via `--allowed-tools` (alleen JIRA-API-aanroepen via bash, geen Read/Edit/Bash voor het repo)
  - Bij voltooiing zet 'ie `phase=refined` (of `phase=awaiting-po` als 'er vragen zijn)
- `poller.py`: nieuwe dispatcher-tak voor `status=AI Ready` → spawn refiner met `AGENT_ROLE=refiner`. `phase=refined` → spawn developer.
- Comment-trigger detecteert PO-antwoord op AI Needs Info: status → AI Queued, poller pakt op via `resume_phase`.

### Jouw werk

1. Mergen.
2. Test-story: schrijf een vage one-liner ("voeg dark mode toe") + AI Level=0 + AI Ready. Verwacht:
   - Refiner-Job spawnt eerst
   - Refiner post een `[REFINER]`-comment met vragen
   - Story zit op AI Needs Info
3. Beantwoord de vragen in een nieuwe comment, transition status naar AI Queued.
4. Poller pakt refiner opnieuw op (alle context lees-baar). Refiner zegt "klaar" → `phase=refined`.
5. Developer wordt gespawnd.

### Dashboard-update

- **Pipeline-balk eerste vak (`refine`)** kleurt nu actief tijdens en na de refiner-stap.
- **AI bezig-card** toont refiner-state met log-link (al ingebouwd in fase 1).
- **`/story/<key>` timeline** toont refiner-runs apart van developer-runs.

### Acceptatie

- [ ] Vage story → refiner stelt vragen → status AI Needs Info
- [ ] PO beantwoordt → status AI Queued → poller dispatcht refiner opnieuw
- [ ] Refiner zegt "duidelijk" → phase=refined → developer pakt op
- [ ] Concrete story (geen vragen nodig) → refiner zet meteen `phase=refined`
- [ ] Token-usage van refiner zit in story-totaal

### Risico

- Refiner stelt te veel / te weinig vragen. Mitigation: itereren op system-prompt nadat we 'm een paar keer gebruikt hebben. Geen hard cap (gebruikerskeuze).
- Refiner schrijft per ongeluk code (system-prompt-override). Mitigatie: tool-allowlist op CLI-niveau; geen `Edit` / `Write` tools beschikbaar.

---

## Fase 5 — Reviewer-agent

### Doel

PRs worden niet meer direct gemerged of doorgestuurd naar tester; eerst leest de reviewer de diff en post comments. Code-kwaliteit-check + architectuur-check ineen. Bij wijzigingen: developer pakt opnieuw op via S-09 comment-iteratie-loop.

### Mijn werk

**PR 1:** Reviewer-rol.

- `runner.sh`: nieuwe role-branch `AGENT_ROLE=reviewer`:
  - System-prompt: "lees PR-diff via `gh pr diff`, beoordeel code-kwaliteit + architectuur + adherence aan conventions in CLAUDE.md, post `[REVIEWER]`-comments met concrete observaties"
  - Tool-allowlist: `gh` CLI + `Read` op het repo (read-only access)
  - Geen git-commits, geen `Edit`
  - Bij voltooiing: zet `phase=reviewed-ok` of `phase=reviewed-changes` (op basis van of er aanmerkingen zijn — Claude beslist zelf via een prompt-instructie aan het eind)
- `poller.py`: dispatcher-tak voor `phase=developed` → spawn reviewer. `phase=reviewed-ok` → spawn tester (voor nu: no-op, tester komt in fase 6). `phase=reviewed-changes` → spawn developer (loopback via S-09 comment-iteratie die al bestaat).

### Jouw werk

1. Mergen.
2. Test-story door de hele flow halen: refiner → developer (commit + PR) → reviewer.
3. Verifieer dat reviewer's comments verschijnen op de PR met `[REVIEWER]`-prefix.
4. Bij `reviewed-changes`: verifieer dat developer opnieuw start en de comments verwerkt.

### Dashboard-update

- **Pipeline-balk derde vak (`review`)** kleurt actief.
- **Reviewer-runs** in de story-timeline.
- Geen nieuwe UI-componenten — bestaande infrastructuur is voldoende.

### Acceptatie

- [ ] Na developer-success spawnt reviewer
- [ ] Reviewer post comments op de PR met `[REVIEWER]`-prefix
- [ ] Bij changes: developer pakt automatisch opnieuw op (S-09)
- [ ] Bij OK: tester zou nu moeten spawnen — werkt pas in fase 6, voor nu blijft 'ie op `AI Queued + phase=reviewed-ok`

### Risico

- Reviewer is te streng / te soepel. Itereer op de prompt.
- Reviewer-comments triggeren ongewenst S-09 op zichzelf (`@claude`-detect). Mitigatie: poller's S-09-trigger-logica negeert comments met agent-prefix (`[REVIEWER]`, `[TESTER]`).

---

## Fase 6 — Tester-agent

### Doel

Voor de merge: tester runt de preview-deploy in een headless Chrome, checkt DB-state, leest pod-logs. Bij bugs: dev pakt opnieuw op. Bij OK: ready for humane merge. **De gevaarlijkste agent qua blast-radius** — vergt extra zorg in RBAC en system-prompt.

### Mijn werk

**PR 1:** Tester-image + RBAC.

- Nieuwe `deploy/claude-tester/` map:
  - `Dockerfile` op basis van claude-runner-image + Chromium + Playwright + psql + oc CLI
  - `rbac.yaml`: aparte ServiceAccount `claude-tester` met cluster-wide read + schrijven in `pnf-pr-*` namespaces (zie §8.2 van het ontwerp voor exact verbs)
- GitHub Actions workflow voor de tester-image build (`tester-image.yml`) inclusief CI-bump.

**PR 2:** Tester-rol in runner.

- `runner.sh`: role-branch `AGENT_ROLE=tester`. Andere image (tester-image) en andere SA. System-prompt zoals in §8.3 van het ontwerp.
- `poller.py`: dispatcher-tak voor `phase=reviewed-ok` → spawn tester. `phase=tested-ok` → niets (wacht op humane merge). `phase=tested-fail` → spawn developer (loopback).

### Jouw werk

1. **Tester-RBAC eenmalig applien** (cluster-admin nodig — niet via ArgoCD vanwege namespaced-mode):
   ```bash
   oc apply -f deploy/claude-tester/rbac.yaml
   ```
2. Merge PR 1 (image-build).
3. Merge PR 2 (orchestratie).
4. Test-story door de volledige flow: refiner → developer → reviewer → tester. Verifieer:
   - Tester opent de preview-URL (`pnf-pr-<N>.vdzonsoftware.nl`)
   - Tester logt z'n acties via stream-json
   - Bij bug-detectie: comments op JIRA + PR, `phase=tested-fail`
   - Developer pakt automatisch op
5. **Echte productie-test**: probeer wat 'ie kan. Probeer als gebruiker 'm te misleiden om iets in prod te muteren — zou moeten falen op RBAC.

### Dashboard-update

- **Pipeline-balk vierde vak (`test`)** kleurt actief — **volledige pipeline-visualisatie nu compleet**.
- **Tester-runs** in story-timeline met aparte iconografie (browser-icon?).
- Nieuwe paginasecte in `/story/<key>`: lijst van getriggerde test-acties (zichtbaar uit de stream-json events).

### Acceptatie

- [ ] Tester spawned na een reviewed-ok PR
- [ ] Tester ziet de preview-URL en navigeert ernaartoe
- [ ] Bij bug: `tested-fail` → developer pakt op
- [ ] Bij OK: `tested-ok` → wacht op humane merge → na merge → Klaar
- [ ] Tester kan **niet** prod-pods muteren (test: prompt 'm "patch frontend deployment in personal-news-feed") — RBAC-403 verwacht

### Risico

- **Tester muteert iets onverwacht** in een `pnf-pr-*`-namespace. Schade beperkt (preview, wordt sowieso weggegooid), maar leerzaam. Itereer op system-prompt.
- **Tester loopt vast** in oneindige browser-flow. Hard timeout op de Job (bv. 15 min) voorkomt onbeperkte resource-consumptie.
- **Tester ziet prod-secrets** via cluster-wide read. Mitigation: RBAC excludes secrets buiten `pnf-pr-*` (zie §8.2 — `secrets` alleen read in pnf-pr-namespaces).

---

## Wat is "klaar"?

Na fase 6 draait de volledige pipeline. Een PO zet een vage story op AI Ready en krijgt over enkele minuten/uren een geteste PR ter goedkeuring — zonder verdere tussenkomst, tenzij de refiner vragen heeft of het budget op is.

**Wat niet in dit plan staat** (out of scope, kandidaten voor latere fase 7+):

- Architect als aparte rol (samengevoegd met reviewer per gebruikerskeuze)
- DB-retention / log-pruning
- Geavanceerde dashboard-filtering / full-text-search over logs
- Multi-tenant (meer dan één project tegelijk)
- Switch naar Claude Agent SDK i.p.v. CLI voor fijnere controle over thinking-budget
- Argo Workflows / Temporal als state-machine-runtime (als JIRA als state-machine begint te knellen)

Deze blijven open punten in [software-factory.md §12](./software-factory.md) en kunnen later opgepakt worden zonder de basis te verstoren.
