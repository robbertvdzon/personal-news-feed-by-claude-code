# Runbook — Personal News Feed

Operationele handleiding voor mensen én de Telegram-assistent. Concreet en
scanbaar. **Geen secret-waarden hier** (dit bestand staat in git) — alleen
namen van secrets en waar ze staan. Echte waarden: `deploy/secrets-cluster.env`
(gitignored, op de laptop).

> Stories voor dit project leven in **YouTrack** met key-prefix `NF-…`
> (oudere stories: `KAN-…` in Jira, `SF-…` voor de Software Factory zelf).

---

## 1. Wat is dit

Een zelf-gehoste, persoonlijke nieuwslezer met AI-curation, podcastgeneratie,
events-ontdekking en multi-user support. Spec-first gebouwd met Claude Code
(zie `specs/`).

**Tech stack**
- **Backend:** Spring Boot 4.x · Kotlin · Maven · Spring Modulith · poort `8080`
- **Frontends:** Flutter (Dart, Riverpod) — twee web-apps:
  - `frontend/` → de volledige app (publiek op `news.vdzonsoftware.nl`)
  - `frontend-reader/` → read-only reader-variant
  - `frontend-dashboard/` → status-dashboard van de Software Factory
- **DB:** PostgreSQL (Neon, cloud) — Flyway-migraties bij start
- **AI:** OpenAI (samenvatting/selectie/podcast/events — sinds SF-115/116 draait
  de app volledig op OpenAI) · Tavily (websearch) · ElevenLabs (podcast-TTS).
  Anthropic-key blijft alleen voor de Software-Factory runner, niet voor de app.
- **Media:** `ffmpeg` (mp3-compressie) + `yt-dlp` (video-audio fallback) in het backend-image

---

## 2. Architectuur

```
GitHub Actions ── build images ──► ghcr.io (public)
        │ commit nieuwe SHA in deploy/base/kustomization.yaml
        ▼
ArgoCD (watcht main) ── sync ──► OpenShift ns: personal-news-feed
        ├── backend   Pod + Service + Route + PVC (audio/state, RWO)
        ├── frontend  Pod + Service + Route   ← gebruikers (news.vdzonsoftware.nl)
        ├── reader    Pod + Service + Route   ← reader.vdzonsoftware.nl
        ├── cloudflared    (tunnel: *.vdzonsoftware.nl → in-cluster services)
        ├── preview-router (nginx, host-based routing voor PR-previews)
        └── Secret newsfeed-api-keys (uit SealedSecret in git)

Data:  Postgres (Neon, extern) — audio-bytes sinds V5 óók in Postgres.
       Het PVC houdt alleen runtime-state / admin-cleanup paden.
```

- **GitOps:** ArgoCD synct elke commit op `main` vanaf `deploy/overlays/openshift`
  (→ `deploy/base`). Geen handmatige deploy-stap.
- **Software Factory:** een aparte laag (YouTrack-poller + `claude-runner` Jobs)
  bouwt stories autonoom. Zie `docs/factory/` en `deploy/youtrack/`,
  `deploy/jira-poller/`, `deploy/claude-runner/`.
- **PR-previews:** elke `ai/*`-PR krijgt `https://pnf-pr-<N>.vdzonsoftware.nl`
  via een ArgoCD ApplicationSet (`deploy/applicationset.yaml`).

---

## 3. Waar draait het

| Omgeving | Waar | URL |
|----------|------|-----|
| **Productie** | OpenShift ns **`personal-news-feed`** | https://news.vdzonsoftware.nl (full app) · https://reader.vdzonsoftware.nl (reader) |
| **PR-preview** | ns `pnf-pr-<N>` (per `ai/*`-PR) | `https://pnf-pr-<N>.vdzonsoftware.nl` |
| **Software Factory** | ns `pnf-software-factory`, `youtrack` | YouTrack-route in cluster |
| **Lokaal** | je laptop | backend `:8080`, frontend `:3000`, reader `:3100` |

- **Cluster:** Single-Node OpenShift (SNO) lab, API `https://api.sno.lab.vdzon.com:6443`
  (self-signed cert → `--insecure-skip-tls-verify`).
- ArgoCD draait in ns `argocd` / `openshift-gitops` (namespaced mode).

---

## 4. Lokaal draaien & testen

**Backend** (vanuit `newsfeedbackend/newsfeedbackend`):
```bash
cd newsfeedbackend/newsfeedbackend
set -a; source ../../deploy/secrets-cluster.env; set +a   # laadt PNF_* env-vars
mvn -DskipTests package
java -jar target/newsfeedbackend-*.jar      # poort 8080, leest ./data
# health: curl http://localhost:8080/actuator/health   → "status":"UP"
```
> Zonder `PNF_DATABASE_URL` start hij wel maar zonder DB. Met de prod-`PNF_DATABASE_URL`
> uit de secrets-file praat je lokaal direct tegen de **prod-Neon-DB** — pas op.

**Frontend** (volledige app, poort 3000):
```bash
cd frontend
make serve-ext     # flutter run -d web-server --web-port 3000 --dart-define=API_BASE_URL=http://localhost:8080
```

**Reader** (read-only, poort 3100):
```bash
cd frontend-reader
make serve         # --web-port 3100, API_BASE_URL=http://localhost:8080
```

**Inloggen om te testen**
- Open `http://localhost:3000`, login-scherm verschijnt.
- Er zijn **geen vaste testaccounts**: maak via **"Account aanmaken"** een verse
  user aan. Conventie in de e2e-scenario's: gebruikersnaam `e2e_<DATETIME>`,
  wachtwoord `e2etest` (zie `e2e/scenarios/start-scenario.md`). Auth is JWT
  (TTL 30 dagen), elke user heeft volledig eigen data.
- Echte gebruikers/wachtwoorden staan **niet** in git — ze leven in de
  `users`-tabel (bcrypt). Voor prod-toegang: vraag de eigenaar of maak een
  eigen account aan op de live-app.

**E2E-tests:** browser-scenario's met GIF-opname in `e2e/` — zie `e2e/readme.md`.
Geen testframework; menselijk-leesbare scripts die (door een agent) in Chrome
worden afgespeeld. Volgorde: `start-scenario.md` → feature-scenario → `cleanup-scenario.md`.

**Container-build lokaal:** `docker build` met `newsfeedbackend/newsfeedbackend/Dockerfile`
(multi-stage: Maven/JDK21 → Temurin JRE21 + ffmpeg + yt-dlp).

---

## 5. Config & secrets

App-config staat in `newsfeedbackend/newsfeedbackend/src/main/resources/application.properties`;
secrets komen als **env-vars** binnen. AI-modellen per actie zijn overschrijfbaar
via `PNF_AI_MODEL_*` env-vars (defaults in application.properties).

**Secret-bestanden lokaal:**
- `deploy/secrets-cluster.env` — alle echte waarden (gitignored)
- `deploy/secrets-cluster.env.example` — template met alleen de namen
- Laden in shell: `set -a; source deploy/secrets-cluster.env; set +a`
- In de cluster: dezelfde waarden in Secret **`newsfeed-api-keys`** (uit de
  SealedSecret `deploy/base/sealed-secret-api-keys.yaml`).

**Secrets / env-vars (naam — waarvoor):**
- `PNF_DATABASE_URL` — JDBC-URL naar prod-Postgres (Neon). Backend mapt 'm óók op `SPRING_DATASOURCE_URL`.
- `JWT_SECRET` — signing key voor JWT-auth (≥32 chars). In de app: `APP_JWT_SECRET`.
- `PNF_OPENAI_API_KEY` — OpenAI (samenvatting/selectie/podcast/events/TTS-transcribe).
- `PNF_TAVILY_API_KEY` — Tavily websearch (ad-hoc zoeken + events-discovery).
- `PNF_ELEVENLABS_API_KEY` — ElevenLabs TTS voor podcast-audio.
- `PNF_ANTHROPIC_API_KEY` — **niet** door de app gebruikt; alleen de Software-Factory runner.
- `TUNNEL_TOKEN` — Cloudflare-tunnel token (cloudflared-pod → publiceert `*.vdzonsoftware.nl`).
- `GITHUB_TOKEN` — PAT voor `gh`/`git push` naar deze repo (CI + factory).
- `ATLASSIAN_API_KEY` — Jira API-key (oudere KAN-stories, jira-poller).
- `SF_YOUTRACK_BASE_URL` / `SF_YOUTRACK_TOKEN` — YouTrack-API voor de Software Factory.
- `CLAUDE_CODE_OAUTH_TOKEN` — Claude Code token voor de factory-runner-Jobs (`claude setup-token`).
- `CLAUDE_AI_OAUTH_CREDENTIALS_JSON` — volledige Claude OAuth-creds voor interactive/remote-control sessies.
- `FACTORY_DATABASE_URL` — aparte DB voor de factory / poller-state.
- `DASHBOARD_ADMIN_PASSWORD` — admin-login van het status-dashboard.
- `NEON_API_KEY` / `NEON_PROJECT_ID` — Neon API (DB-branches/beheer).
- `OPENSHIFT_API_TOKEN` — `oc login`-token voor het SNO-lab.

---

## 6. Database

- **Type:** PostgreSQL, gehost bij **Neon** (cloud). Eén gedeelde DB voor prod én
  alle PR-previews (let op bij schema-migraties — die raken prod-data direct).
- **Migraties:** Flyway, automatisch bij backend-start
  (`src/main/resources/db/migration/`, t/m `V14`).
- **Belangrijke tabellen:** `users`, `rss_feeds`, `rss_items`, `feed_items`,
  `news_requests`, `topic_history`, `category_settings`, `external_calls`
  (AI-cost-log), `podcasts`, `podcast_feeds`, `podcast_episodes`,
  `events`, `event_videos`, `event_preferences`, `event_denylist`,
  `shedlock` (scheduler-lock), `flyway_schema_history`.

**Verbinden (read-only query vanaf de laptop / assistent):**
De Neon-DB is **direct over internet** bereikbaar met `PNF_DATABASE_URL` — geen
tunnel nodig. De JDBC-URL is niet psql-compatible, dus converteren:

```bash
export PATH="/opt/homebrew/opt/libpq/bin:$PATH"   # psql (brew install libpq als 'ie ontbreekt)
set -a; source deploy/secrets-cluster.env; set +a
JDBC="$PNF_DATABASE_URL"
HOST=$(echo "$JDBC" | sed -E 's|jdbc:postgresql://([^/]+)/.*|\1|')
DB=$(echo   "$JDBC" | sed -E 's|jdbc:postgresql://[^/]+/([^?]+)\?.*|\1|')
USER=$(echo "$JDBC" | grep -oE 'user=[^&]+'     | cut -d= -f2)
PASS=$(echo "$JDBC" | grep -oE 'password=[^&]+' | cut -d= -f2)
PSQL_URL="postgresql://${USER}:${PASS}@${HOST}/${DB}?sslmode=require"
psql "$PSQL_URL" -c "\dt"                         # lijst tabellen
psql "$PSQL_URL" -c "SELECT count(*) FROM events WHERE username='robbert';"
```
> Read-only discipline: gebruik alleen `SELECT`. Er is geen aparte read-only
> rol; voorzichtig zijn met `UPDATE/DELETE` op de gedeelde prod-DB.

> **Over de Cloudflare-tunnel + DB:** `TUNNEL_TOKEN` dient om de cluster-**frontends**
> publiek te maken (`*.vdzonsoftware.nl`), **niet** om de DB te bereiken — de
> prod-DB (Neon) staat al publiek op internet en wordt direct benaderd zoals
> hierboven. TODO: bevestigen of er daarnaast een aparte DB-tunnel bestaat;
> in deze repo is die niet gevonden.

---

## 7. Externe systemen

- **GitHub** — repo `robbertvdzon/personal-news-feed-by-claude-code`.
  - CI in `.github/workflows/`. `build-images.yml` bouwt backend/frontend/reader
    en pusht naar `ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend,reader}:sha-<short>`
    (+ `:main` op main). Op een push naar `main` committet de job daarna de
    nieuwe SHA in `deploy/base/kustomization.yaml` (`.github/scripts/bump-images.sh`).
  - Andere workflows: APK-builds, en images voor factory-onderdelen
    (`runner`, `tester`, `labeller`, `jira-poller`, `status-dashboard`, `cost-monitor`).
  - Auth: `GITHUB_TOKEN` uit de secrets-file → `GH_TOKEN="$GITHUB_TOKEN" gh ...`.
- **OpenShift** — SNO-lab, `oc login` met `OPENSHIFT_API_TOKEN` (zie §8).
- **ArgoCD** — GitOps; Application `personal-news-feed` (`deploy/argocd-application.yaml`)
  in ns `argocd`/`openshift-gitops`. `prune: true`, `selfHeal: true`.
- **Cloudflare Tunnel** — `cloudflared`-pod in de cluster, token `TUNNEL_TOKEN`.
  Public hostnames in het Cloudflare Zero-Trust dashboard → in-cluster services
  (`news`/`reader`/`pnf-pr-*` → `frontend`/`reader`/`preview-router` op `:8080`).
- **AI/SaaS:** OpenAI, Tavily, ElevenLabs, Neon, YouTrack/Jira (Atlassian).

---

## 8. Veelvoorkomende taken / troubleshooting

**Inloggen op de cluster:**
```bash
TOKEN=$(grep '^OPENSHIFT_API_TOKEN=' deploy/secrets-cluster.env | cut -d= -f2- | tr -d '"'"'"'')
# eerst evt: rmdir ~/.kube/config   (kan als lege dir bestaan → "is a directory")
oc login --token="$TOKEN" --server=https://api.sno.lab.vdzon.com:6443 --insecure-skip-tls-verify=true
```
Login schrijft `~/.kube/config` (persistent tussen Bash-calls).

**Is de laatste deploy gelukt?**
```bash
oc get application personal-news-feed -n openshift-gitops    # SYNCED / HEALTHY?
oc get pods -n personal-news-feed                            # alle pods Running/Ready?
oc rollout status -n personal-news-feed deploy/backend
# Laatste gebouwde SHA staat in deploy/base/kustomization.yaml (images: newTag)
git -C . log --oneline -5    # vergelijk met de laatste 'ci: bump images...' commit
```
Live versie via API: `curl https://news.vdzonsoftware.nl/api/version` (BUILD_SHA/BUILD_TIME).

**Logs bekijken op OpenShift:**
```bash
oc logs -n personal-news-feed deploy/backend -f
oc logs -n personal-news-feed deploy/frontend -f
oc logs -n personal-news-feed deploy/cloudflared --tail=100
```

**Prod-DB read-only query:** zie §6.

**Secret gewijzigd → pod ziet het pas na rollout:**
```bash
# 1) deploy/secrets-cluster.env bijwerken  2) ./deploy/seal-secrets.sh
# 3) git commit deploy/base/sealed-secret-api-keys.yaml && git push  4) ArgoCD synct
oc rollout restart -n personal-news-feed deploy/backend
```

**App herstarten / forceren:**
```bash
oc rollout restart -n personal-news-feed deploy/backend
oc rollout restart -n personal-news-feed deploy/frontend
```

**Webapp openen/screenshotten:** `https://news.vdzonsoftware.nl` (full),
`https://reader.vdzonsoftware.nl` (reader). Health: `/actuator/health`.

**Preview hangt op "Pending":** waarschijnlijk raakte de PR alleen `specs/**` of
`deploy/**` → geen image-build (path-filter in `build-images.yml`). Workaround:
triviale commit in `newsfeedbackend/**` of `frontend/**`. Orphan-namespace
opruimen: `oc delete ns pnf-pr-<N>`.

---

## 9. Conventies

- **Branches:** feature-/story-branches `ai/<KEY>` (bv. `ai/NF-123`) → PR → preview
  → merge naar `main`. Stories in YouTrack (`NF-…`); de poller claimt een story
  door 'm van `AI Ready` naar `AI IN PROGRESS` te zetten.
- **Commits:** scope-prefix gebruikelijk (`feat`, `fix`, `ci`, `deploy`, `SF-…`).
  CI-bumps heten `ci: bump images to sha-…`.
- **Deploy:** uitsluitend via GitOps — push naar `main`, GitHub Actions bouwt +
  bumpt `kustomization.yaml`, ArgoCD synct. **Nooit handmatig `oc apply`** voor
  app-resources (ArgoCD `selfHeal` draait het terug); secrets via `seal-secrets.sh`.
- **Logs:** runtime-logs via `oc logs` (zie §8). AI-call-kosten in tabel
  `external_calls`. Monitoring (lokaal): Prometheus/Grafana via
  `newsfeedbackend/newsfeedbackend/docker-compose-monitoring.yml`; backend
  exposeert `/actuator/prometheus`.
- **Specs/docs:** `specs/` is de source of truth (openapi + functional/technical).
  Software-Factory-docs in `docs/factory/`; story-worklogs in `docs/stories/`.
```
