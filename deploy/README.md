# Deploy: OpenShift via ArgoCD

Personal News Feed wordt op een OpenShift-cluster gedraaid via GitOps:
ArgoCD watcht `main`, ziet wijzigingen in `deploy/overlays/openshift/`
en synct ze naar de namespace `personal-news-feed`.

## Architectuur

```
GitHub Actions ── builds ──► ghcr.io (public)
        │                         ▲
        ├── pusht nieuwe SHA naar │ pull
        ▼                         │
deploy/base/kustomization.yaml    │
        │                         │
        ▼                         │
ArgoCD ◄── synct main ──── git ──┘
        │
        ▼
OpenShift cluster (personal-news-feed)
  ├── backend Pod + Service + Route (debug)
  ├── frontend Pod + Service + Route ← gebruikers
  ├── PVC (audio files, 5 Gi)
  └── Secret (uit SealedSecret in git, ge-decrypt door cluster)
```

Data zelf staat in een externe Postgres (Neon); alleen audio-MP3's en
de runtime-state staan in het cluster.

## Eenmalige cluster-setup

### 1. Bootstrap

`deploy/bootstrap.sh` doet alle cluster-bootstrap in één keer (idempotent):

1. Installeert de Sealed Secrets controller
2. Haalt het public-cert op naar `deploy/cluster-cert.pem`
3. Installeert + configureert local-path-provisioner (default StorageClass,
   privileged helper-pod voor SELinux/RHCOS-compat, path naar `/var/lib`)
4. Maakt namespace `personal-news-feed` met de `argocd.argoproj.io/managed-by`-label
5. Apply't de ArgoCD `Application`

Vereisten: `oc` ingelogd, `kubeseal` geïnstalleerd. ArgoCD wordt verwacht
in namespace `argocd` (anders pas `ARGOCD_NS` aan in het script).

```bash
./deploy/bootstrap.sh
git add deploy/cluster-cert.pem
git commit -m "deploy: add cluster public cert"
git push
```

Het cert is **public**, mag in git. Het private keypaar blijft op het
cluster (`kube-system/sealed-secrets-key…`). Maak daar een offsite
backup van als je `oc get secret -n kube-system -l sealedsecrets.bitnami.com/sealed-secrets-key`
exporteert — anders ben je bij cluster-reinstall alle sealed-secrets
kwijt.

### 2. Cluster-secrets aanmaken en encrypten

```bash
cp deploy/secrets-cluster.env.example deploy/secrets-cluster.env
# Edit deploy/secrets-cluster.env in je IDE — vul de echte cluster-waarden in.
# Deze file is gitignored.

./deploy/seal-secrets.sh
# → produceert deploy/base/sealed-secret-api-keys.yaml

# Voeg toe aan deploy/base/kustomization.yaml onder `resources:`:
#   - sealed-secret-api-keys.yaml

git add deploy/base/sealed-secret-api-keys.yaml deploy/base/kustomization.yaml
git commit -m "deploy: add sealed api-keys"
git push
```

ArgoCD synct → controller decrypteert → `Secret/newsfeed-api-keys`
ontstaat in de namespace → backend pod start.

## Dagelijks gebruik

### Code-wijziging

Push naar `main`:
- GitHub Actions bouwt nieuwe images, pusht naar `ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend}:sha-…`
- Workflow committet de nieuwe SHA in `deploy/base/kustomization.yaml`
- ArgoCD detecteert de manifest-wijziging, doet `kubectl apply`, pods rollen
- Geen handmatige stap nodig

### Secret wijzigen

1. `deploy/secrets-cluster.env` op je laptop bijwerken
2. `./deploy/seal-secrets.sh`
3. `git commit deploy/base/sealed-secret-api-keys.yaml && git push`
4. ArgoCD synct, controller updatet de Secret. **Pod ziet de nieuwe
   waarde pas na een rollout** — restart triggeren met:
   ```bash
   oc rollout restart -n personal-news-feed deploy/backend
   ```

### Status checken

```bash
# ArgoCD-status
oc get application personal-news-feed -n openshift-gitops

# Pods
oc get pods -n personal-news-feed

# Logs
oc logs -n personal-news-feed deploy/backend -f
oc logs -n personal-news-feed deploy/frontend -f

# Route URLs
oc get routes -n personal-news-feed
```

## Cloudflare Tunnel — externe toegang

`cloudflared`-deployment is al in `deploy/base/cloudflared-deployment.yaml`.
Wat je nog moet doen:

1. **Domein op Cloudflare** met status "Active" (jouw `vdzonsoftware.nl`).
2. **Zero Trust → Networks → Tunnels → Create a tunnel** (Cloudflared type).
   - Geef 'm een naam, b.v. `personal-news-feed`.
   - Kopieer de **TUNNEL_TOKEN** uit het install-commando.
3. **Public hostname** in de tunnel-config:
   - Subdomain: `news`
   - Domain: `vdzonsoftware.nl`
   - Service: `HTTP` → `frontend.personal-news-feed.svc.cluster.local:8080`
4. **Token in de SealedSecret** zetten:
   ```bash
   # Edit deploy/secrets-cluster.env, voeg TUNNEL_TOKEN=eyJ... toe
   ./deploy/seal-secrets.sh
   git add deploy/base/sealed-secret-api-keys.yaml
   git commit -m "deploy: add cloudflare tunnel token"
   git push
   ```
5. ArgoCD synct, `cloudflared`-pod start, tunnel opent → `https://news.vdzonsoftware.nl` werkt vanaf elke browser, met geldig Cloudflare-cert.

Geen port-forwarding op je router nodig — alleen uitgaande connectie van het cluster naar Cloudflare.

## JIRA-integratie (S-03 + S-04)

`jira-poller` (in `deploy/jira-poller/`) draait permanent in de
`personal-news-feed`-namespace en pollt elke 30s je JIRA-bord op nieuwe
stories:

1. Issue komt in status **`AI Ready`** → poller pakt 'm op
2. Status wordt automatisch verzet naar **`AI IN PROGRESS`** (atomic claim)
3. `claude-runner` Job spawnt met de issue-description als `task.md`
4. Vanaf hier: zelfde flow als S-01 — branch `ai/<JIRA-KEY>`, PR, preview-deploy

**Concurrency-cap**: standaard max 2 parallelle stories (configureerbaar
via `MAX_CONCURRENT_JOBS` env-var). Overschot wacht in JIRA tot er
capaciteit is.

**Configuratie**: alles via env-vars op het `jira-poller` Deployment,
inclusief workspace-URL, project-key en status-namen. Aanpassen kost
één commit:

```yaml
# deploy/jira-poller/deployment.yaml
env:
  - name: JIRA_PROJECT
    value: "KAN"
  - name: JIRA_SOURCE_STATUS
    value: "AI Ready"        # exacte JIRA-naam, case-sensitive!
  - name: JIRA_TARGET_STATUS
    value: "AI IN PROGRESS"
```

**Wat gaat naar Claude**: de JIRA-issue's `description` (Atlassian
Document Format) wordt omgezet naar markdown en aangeboden als `task.md`.
Headings, lists, bold/italic, code-blocks blijven behouden.

## Preview-deploys per PR (S-06)

Elke open PR met branch-prefix `ai/` krijgt automatisch een eigen
preview op `https://pnf-pr-<N>.vdzonsoftware.nl` (waar `<N>` het
PR-nummer is). Bij merge/close wordt de preview opgeruimd.

**Hoe het werkt:**

1. **GitHub Actions** bouwt op elke `pull_request` event een image en
   tagt 'm met `sha-<short-sha>` van de PR's HEAD.
2. **ApplicationSet** (`deploy/applicationset.yaml`) pollt elke 3 min
   GitHub voor open PR's matching `^ai/.+$` en spawnt per PR een
   ArgoCD Application.
3. **Preview-ns-labeller** (`deploy/preview-ns-labeller/`) zorgt dat
   de bijbehorende namespace `pnf-pr-<N>` bestaat met de
   `argocd.argoproj.io/managed-by`-label (anders blokkeert de
   argocd-operator).
4. **Reflector** mirror't de `newsfeed-api-keys` Secret automatisch
   naar elke nieuwe `pnf-*`-namespace.
5. **Preview-router** (nginx in personal-news-feed) ontvangt
   `*.vdzonsoftware.nl` traffic via Cloudflare en route't host-based
   naar de juiste preview-namespace.

**Beperkingen:**

- **Alleen code-changes triggeren een preview.** PR's die alleen
  `specs/**` of `deploy/**` aanraken matchen niet de paths-filter
  van `build-images.yml` → er wordt geen image gebouwd → de preview
  blijft hangen op "Pending". Niet kritiek (geen runtime-impact
  om te previewen) maar wel verwarrend. Workaround: tijdelijk een
  trivial commit in `newsfeedbackend/**` of `frontend/**` toevoegen
  om de build te forceren.

- **Gedeelde database.** Alle previews praten met dezelfde Postgres
  als prod. In de ontwikkelfase OK; voor schema-migraties echter
  oppassen — een PR die migrations toevoegt past die direct op prod-
  data toe. Toekomstige verbetering: Neon-branches per preview.

- **Geen automatic preview cleanup van orphan namespaces.** Bij merge
  ruimt ArgoCD de Application + resources op (`prune: true`), de
  namespace zelf blijft achter als 'er nog ander state in zit. Voor
  een schone start: handmatig `oc delete ns pnf-pr-<N>` als de PR
  echt afgesloten is.

## Bestanden in deze map

```
deploy/
├── README.md                    ← deze file
├── argocd-application.yaml      ← één keer apply'en
├── cluster-cert.pem             ← public, voor lokaal `kubeseal`
├── seal-secrets.sh              ← .env → SealedSecret YAML
├── secrets-cluster.env.example  ← template
├── secrets-cluster.env          ← (gitignored) jouw waarden
├── base/
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── backend-deployment.yaml
│   ├── backend-service.yaml
│   ├── backend-route.yaml      ← optioneel/debug
│   ├── backend-pvc.yaml         ← audio storage
│   ├── frontend-deployment.yaml
│   ├── frontend-service.yaml
│   ├── frontend-route.yaml
│   └── sealed-secret-api-keys.yaml  ← na seal-secrets.sh
└── overlays/
    └── openshift/
        └── kustomization.yaml  ← cluster-specifieke patches
```
