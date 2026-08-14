# Runbook — Personal News Feed

Operationele handleiding voor mensen én de Telegram-assistent. Concreet en
scanbaar. **Geen secret-waarden hier** (dit bestand staat in git) — alleen
namen van secrets en waar ze staan. Echte waarden: `deploy/secrets-cluster.env`
(gitignored, op de laptop).

> Stories voor dit project leven in **YouTrack** met key-prefix `NF-…`
> (oudere stories: `KAN-…` in Jira).

---

## 1. Wat is dit

Een zelf-gehoste, persoonlijke nieuwslezer met AI-curation, podcastgeneratie
en multi-user support. Spec-first gebouwd met Claude Code
(zie `specs/`).

**Tech stack**
- **Backend:** Spring Boot 4.x · Kotlin · Maven · Spring Modulith · poort `8080`
- **Frontends:** Flutter (Dart, Riverpod) — twee web-apps:
  - `frontend/` → de volledige app (publiek op `news.vdzonsoftware.nl`)
  - `frontend-reader/` → read-only reader-variant
- **DB:** PostgreSQL (Neon, cloud) — Flyway-migraties bij start
- **AI:** OpenAI (samenvatting/selectie/podcast — de app draait volledig
  op OpenAI) · Tavily (websearch) · ElevenLabs (podcast-TTS).
- **Media:** `ffmpeg` (mp3-compressie) in het backend-image

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
        ├── cloudflared    (tunnel: *.vdzonsoftware.nl → ingressrouter → Route)
        └── Secret newsfeed-api-keys (uit SealedSecret in git)

Data:  Postgres (Neon, extern) — audio-bytes sinds V5 óók in Postgres.
       Het PVC houdt alleen runtime-state / admin-cleanup paden.
```

- **GitOps:** ArgoCD synct elke commit op `main` vanaf `deploy/overlays/openshift`
  (→ `deploy/base`). Geen handmatige deploy-stap.
- **PR-previews:** elke `ai/*`-PR krijgt `https://pnf-pr-<N>.vdzonsoftware.nl`
  via een ArgoCD ApplicationSet (`robberts-infrastructure/manifests/root-app/apps/`,
  zie `deploy/README.md` voor het volledige verhaal) + de
  `preview-ns-labeller` (Neon DB-branch per preview).

---

## 3. Waar draait het

| Omgeving | Waar | URL |
|----------|------|-----|
| **Productie** | OpenShift ns **`personal-news-feed`** | https://news.vdzonsoftware.nl (full app) · https://reader.vdzonsoftware.nl (reader) |
| **PR-preview** | ns `pnf-pr-<N>` (per `ai/*`-PR) | `https://pnf-pr-<N>.vdzonsoftware.nl` |
| **Lokaal** | je laptop | backend `:8080`, frontend `:3000`, reader `:3100` |

- **Cluster:** Single-Node OpenShift (SNO) lab, API `https://api.sno.lab.vdzon.com:6443`
  (self-signed cert → `--insecure-skip-tls-verify`).
- ArgoCD draait in ns `argocd` / `openshift-gitops` (namespaced mode).

---

## 4. Lokaal draaien & testen

**Backend** (vanuit `newsfeedbackend/newsfeedbackend`) — standaard tegen een
**lokale database**, zodat je nooit per ongeluk op prod werkt:
```bash
docker compose -f docker-compose.dev.yml up -d      # vanuit de repo-root: lokale Postgres op :5433
export PNF_DATABASE_URL='jdbc:postgresql://localhost:5433/newsfeed?user=newsfeed&password=newsfeed'
cd newsfeedbackend/newsfeedbackend
mvn -DskipTests package
java -jar target/newsfeedbackend-*.jar      # poort 8080; Flyway migreert automatisch
# health: curl http://localhost:8080/actuator/health   → "status":"UP"
```
> AI-features werken lokaal alleen met echte API-keys (`PNF_OPENAI_API_KEY`
> etc. uit `deploy/secrets-cluster.env`); de rest van de app werkt zonder.
> Zonder `APP_JWT_SECRET` genereert de backend een ephemeral JWT-secret —
> prima lokaal, maar iedereen is na een herstart uitgelogd.
>
> **Alleen als je bewust tegen prod-data wilt** (voorzichtig!):
> `set -a; source ../../deploy/secrets-cluster.env; set +a` — dan wijst
> `PNF_DATABASE_URL` naar de **prod-Neon-DB**.

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

**Backend-tests** (vanuit `newsfeedbackend/newsfeedbackend`):
```bash
mvn test      # snelle unit tests (geen Docker nodig, ~15s)
mvn verify    # unit + volledige e2e-suite: start de hele app per testklasse
              # tegen een Testcontainers-Postgres; vereist Docker; ~7 min
```
- De e2e-tests (`src/test/kotlin/.../e2e/`) draaien de échte app met echte
  Postgres en Flyway; alleen externe diensten (OpenAI, Tavily, ElevenLabs,
  RSS-feeds) zijn gefaked. Geen secrets of netwerk nodig.
- `ModuleStructureTest` bewaakt de Spring Modulith module-grenzen; de
  allowlist is leeg en hoort leeg te blijven.
- Coverage-rapport na `mvn verify`: `target/site/jacoco-it/index.html`.
- Zie `docs/onboarding-senior-developer.md` voor de teststrategie en hoe je
  een nieuwe e2e-test schrijft.

**Browser-scenario's (handmatig/agent):** GIF-opname-scenario's in `e2e/` — zie
`e2e/readme.md`. Geen testframework; menselijk-leesbare scripts die (door een
agent) in Chrome worden afgespeeld. Volgorde: `start-scenario.md` →
feature-scenario → `cleanup-scenario.md`.

**Container-build lokaal:** `docker build` met `newsfeedbackend/newsfeedbackend/Dockerfile`
(multi-stage: Maven/JDK21 → Temurin JRE21 + ffmpeg).

---

## 5. Config & secrets

App-config staat in `newsfeedbackend/newsfeedbackend/src/main/resources/application.properties`;
secrets komen als **env-vars** binnen. AI-modellen per actie zijn overschrijfbaar
via `PNF_AI_MODEL_*` env-vars (defaults in application.properties).

Bestanden staan lokaal (gitignored). Voor de assistent worden ze read-only beschikbaar in, hierin staan alle secrets die nodig zijn
`/personal-news-feed-by-claude-code/private/`.


**Secret-bestanden lokaal:**
- `deploy/secrets-cluster.env` — alle echte waarden (gitignored)
- `deploy/secrets-cluster.env.example` — template met alleen de namen
- Laden in shell: `set -a; source deploy/secrets-cluster.env; set +a`
- In de cluster: dezelfde waarden in Secret **`newsfeed-api-keys`** (uit de
  SealedSecret `deploy/base/sealed-secret-api-keys.yaml`).

**Secrets / env-vars (naam — waarvoor):**
- `PNF_DATABASE_URL` — JDBC-URL naar prod-Postgres (Neon). Backend mapt 'm óók op `SPRING_DATASOURCE_URL`.
- `JWT_SECRET` — signing key voor JWT-auth (≥32 chars). In de app: `APP_JWT_SECRET`.
  Alleen de productie-overlay koppelt deze door; PR-previews zetten
  `APP_JWT_SECRET` bewust leeg en draaien op een ephemeral sleutel per pod
  (SF-1542, zie `deploy/README.md`).
- `PNF_OPENAI_API_KEY` — OpenAI (samenvatting/selectie/podcast/TTS-transcribe).
- `PNF_TAVILY_API_KEY` — Tavily websearch (ad-hoc nieuws-verzoeken).
- `PNF_ELEVENLABS_API_KEY` — ElevenLabs TTS voor podcast-audio.
- `TUNNEL_TOKEN` — Cloudflare-tunnel token (cloudflared-pod → publiceert `*.vdzonsoftware.nl`).
- `GITHUB_TOKEN` — PAT voor `gh`/`git push` naar deze repo (CI + ArgoCD PR-preview-generator).
  De `preview-ns-labeller` gebruikt 'm ook voor zijn fail-closed PR-statuscheck: zonder
  (werkend) token doet die voor een preview géén enkele mutatie — zie §6.
- `NEON_API_KEY` / `NEON_PROJECT_ID` — Neon API (DB-branches/beheer, o.a. preview-branches en `deploy/neon-endpoint-config.sh`, zie §6.1).
- `OPENSHIFT_API_TOKEN` — `oc login`-token voor het SNO-lab.

---

## 6. Database

- **Type:** PostgreSQL, gehost bij **Neon** (cloud). Prod draait op de
  default-branch; **elke PR-preview krijgt een eigen Neon-branch** (`pr-<N>`,
  aangemaakt/opgeruimd door de `preview-ns-labeller`, zie
  `deploy/preview-ns-labeller/`). Previews kunnen dus vrij migreren/testen
  zonder prod-data te raken. Kanttekening: de allereerste boot van een verse
  preview kan (max ~30s, tot de labeller het secret gepatcht heeft) nog de
  prod-URL uit het base-secret zien; zonder `NEON_API_KEY`/`NEON_PROJECT_ID`
  valt de labeller terug op namespace-labeling-only — dan draaien previews
  wél op prod; en de labeller heeft daarnaast `GITHUB_TOKEN` nodig voor zijn
  fail-closed PR-statuscheck — ontbreekt dat token, faalt de GitHub-call of
  komt er geen HTTP 200, dan is de PR-status "onbekend" en doet de labeller
  voor die preview géén enkele mutatie: geen namespace-label, geen
  Neon-branch, geen secret-patch en geen cleanup.
- **Migraties:** Flyway, automatisch bij backend-start
  (`src/main/resources/db/migration/`, t/m `V16`).
- **Belangrijke tabellen:** `users`, `rss_feeds`, `rss_items`, `feed_items`,
  `news_requests`, `topic_history`, `category_settings`, `external_calls`
  (AI-cost-log), `podcasts`, `podcast_feeds`, `podcast_episodes`,
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
psql "$PSQL_URL" -c "SELECT count(*) FROM feed_items WHERE username='robbert';"
```
> Read-only discipline: gebruik alleen `SELECT`. Er is geen aparte read-only
> rol; voorzichtig zijn met `UPDATE/DELETE` op de gedeelde prod-DB.

### 6.1 Neon-kosten: scale-to-zero + compute-cap (SF-1739)

De Neon-rekening loopt vrijwel volledig op **compute-tijd**, niet op opslag.
Drie dingen houden die tijd laag:

| Instelling | Waarde | Waarom |
|---|---|---|
| `suspend_timeout_seconds` | `300` | compute mag na 5 min stilte slapen |
| `autoscaling_limit_min_cu` | `0.25` | kleinste compute-eenheid |
| `autoscaling_limit_max_cu` | `1` | compute-cap; geen 8×-uitschieters |

Aan de applicatiekant hoort daarbij (staat in `application.properties`):
`spring.datasource.hikari.minimum-idle=0` + `idle-timeout=60000`, zodat de
connection-pool bij inactiviteit écht leegloopt (zonder `minimum-idle=0` is
`minimumIdle == maximumPoolSize` en doet `idle-timeout` niets, waardoor er
permanent 5 verbindingen openstaan en Neon nooit suspendt). Er is bewust
**geen** keepalive/validatie-timer, en er is sinds SF-1739 **geen** `@Scheduled`
meer die vaker dan één keer per uur de database raakt (de podcast-transcript-
verwerking is event-driven; alleen een uurlijkse recovery-job blijft over).
De Kubernetes-probes raken de DB niet: readiness/liveness gebruiken de
standaard Spring-groepen (`/actuator/health/{readiness,liveness}`) zonder
db-indicator — voeg dus **geen** `management.endpoint.health.group.*`-config toe.

**Script draaien (operatorstap; credentials staan alleen in het cluster-secret):**

```bash
# NEON_API_KEY + NEON_PROJECT_ID uit deploy/secrets-cluster.env (niet committen)
set -a; source deploy/secrets-cluster.env; set +a
export NEON_API_KEY NEON_PROJECT_ID
./deploy/neon-endpoint-config.sh
```

Het script zoekt zelf de read/write-endpoint van de default-(prod-)branch,
patcht alleen als er iets afwijkt (idempotent — meerdere keren draaien is
veilig) en print daarna de **teruggelezen** effectieve waarden.

**Read-only verifiëren (patcht niets):**

```bash
./deploy/neon-endpoint-config.sh --verify
```

Verwachte output bevat `"suspend_timeout_seconds": 300`,
`"autoscaling_limit_min_cu": 0.25` en `"autoscaling_limit_max_cu": 1`.
`current_state` is `idle` als de compute slaapt en `active` als 'ie draait.

**Cold start na suspend:** het eerste request nadat Neon geslapen heeft, wacht
op het opstarten van de compute (orde seconden). Dat is verwacht gedrag en
geaccepteerd; er is bewust geen warmhoudmechanisme gebouwd (dat zou het hele
doel ondermijnen). De backend heeft `connection-timeout=30000`, ruim genoeg.
Een openstaande frontend die blijft pollen kan de endpoint overigens wakker
houden — dat is normaal gebruik, geen misconfiguratie.

**Terugdraaien naar de oude waarden** (nooit suspenden, tot 8 CU):

```bash
SUSPEND_TIMEOUT_SECONDS=0 AUTOSCALING_MIN_CU=0.25 AUTOSCALING_MAX_CU=8 \
  ./deploy/neon-endpoint-config.sh
./deploy/neon-endpoint-config.sh --verify      # controleren
```

(`suspend_timeout_seconds=0` = Neon-default: nooit automatisch suspenden.)
Rol daarnaast de `minimum-idle`/`idle-timeout`-regels in
`application.properties` terug als je ook het pool-gedrag wilt herstellen.
Blijkt 1 CU in de praktijk te krap, dan mag de operator `AUTOSCALING_MAX_CU=2`
zetten — leg de meting waarop dat besluit rust hier vast.

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
  - Testworkflows op elke PR + push naar `main`: `backend-tests.yml`
    (`mvn verify`, paths-filter op `newsfeedbackend/**`) en
    `frontend-tests.yml` (twee jobs: `flutter test` in `frontend/` en in
    `frontend-reader/`, paths-filter op beide app-mappen).
  - Andere workflows: APK-builds (`build-apk`, `build-apk-reader`), de
    preview-labeller-image (`labeller-image.yml`) en `validate-pr.yml`
    (branch-/commit-conventie).
  - Auth: `GITHUB_TOKEN` uit de secrets-file → `GH_TOKEN="$GITHUB_TOKEN" gh ...`.
- **OpenShift** — SNO-lab, `oc login` met `OPENSHIFT_API_TOKEN` (zie §8).
- **ArgoCD** — GitOps; Application `personal-news-feed`
  (`robberts-infrastructure/manifests/root-app/apps/`, zie `deploy/README.md`)
  in ns `argocd`/`openshift-gitops`. `prune: true`, `selfHeal: true`.
- **Cloudflare Tunnel** — `cloudflared`-pod in de cluster, token `TUNNEL_TOKEN`.
  Public hostnames in het Cloudflare Zero-Trust dashboard: de wildcard
  `*.vdzonsoftware.nl` gaat naar de OpenShift-ingressrouter, die op de
  Host-header de juiste Route kiest (geen nginx-tussenlaag). Productiehosts
  staan declaratief in de manifests: `deploy/base/frontend-route.yaml`
  (`news.vdzonsoftware.nl`) en `deploy/base/reader-route.yaml`
  (`reader.vdzonsoftware.nl`); voor previews zet de `preview`-overlay op de
  frontend-Route een placeholder-host die de ApplicationSet per PR invult
  (`pnf-pr-<N>.vdzonsoftware.nl`). Op beide productie-Routes staat
  `insecureEdgeTerminationPolicy: Allow` (niet `Redirect`), omdat de
  Cloudflare-connector de router cluster-intern via HTTP bereikt;
  `deploy/base/backend-route.yaml` (debug) houdt bewust `Redirect`.
- **AI/SaaS:** OpenAI, Tavily, ElevenLabs, Neon, YouTrack (story-tracking).

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

**Preview hangt op "Pending":** de ArgoCD ApplicationSet pollt elke ~3 min
GitHub voor nieuwe/gewijzigde PR's (zie `deploy/README.md`
§"Preview-deploys per PR (S-06)") — even wachten lost dit meestal op. Orphan-namespace
opruimen: `oc delete ns pnf-pr-<N>`.

Blijft de namespace ook daarna weg (of komt er geen branch-DB), check dan de
labeller-logs op regels als "PR-status … onbekend": zijn PR-statuscheck is
fail-closed, dus bij een ontbrekend/ongeldig `GITHUB_TOKEN` of een GitHub-call
zonder HTTP 200 slaat hij álle mutaties over (geen namespace-label, geen
Neon-branch, geen secret-patch, geen cleanup). Het labeller-Deployment staat in
`robberts-infrastructure` (zie `deploy/README.md` §"Preview-deploys per PR (S-06)").

---

## 9. Conventies

- **Branches:** feature-/story-branches `ai/<KEY>` (bv. `ai/NF-123`) → PR → preview
  → merge naar `main`. Stories in YouTrack (`NF-…`).
- **Commits:** scope-prefix gebruikelijk (`feat`, `fix`, `ci`, `deploy`).
  CI-bumps heten `ci: bump images to sha-…`.
- **Deploy:** uitsluitend via GitOps — push naar `main`, GitHub Actions bouwt +
  bumpt `kustomization.yaml`, ArgoCD synct. **Nooit handmatig `oc apply`** voor
  app-resources (ArgoCD `selfHeal` draait het terug); secrets via `seal-secrets.sh`.
- **Logs:** runtime-logs via `oc logs` (zie §8). AI-call-kosten in tabel
  `external_calls`. Monitoring (lokaal): Prometheus/Grafana via
  `newsfeedbackend/newsfeedbackend/docker-compose-monitoring.yml`; backend
  exposeert `/actuator/prometheus`.
- **Specs/docs:** `specs/` is de source of truth (openapi + functional/technical).
```
