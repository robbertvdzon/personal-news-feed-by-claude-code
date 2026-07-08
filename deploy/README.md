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

Er is geen app-specifieke bootstrap meer — alles voor deze app gaat via
GitOps vanuit `robberts-infrastructure` (het cluster-brede deel verhuisde
daar al op 2026-07-07 heen; op 2026-07-08 volgden de laatste twee
imperatieve stappen toen de ArgoCD-instance cluster-scoped werd):

```bash
# Eenmalig per cluster (niet per app), in robberts-infrastructure:
~/git/robberts-infrastructure/scripts/bootstrap/bootstrap-cluster.sh
~/git/robberts-infrastructure/scripts/bootstrap/bootstrap-apps.sh
```

`deploy/bootstrap.sh` hier is verouderd en doet niets meer. Wat het deed:

1. Namespace `personal-news-feed` aanmaken + labelen — overbodig:
   `CreateNamespace=true` werkt echt sinds ArgoCD cluster-scoped draait
   (zie `robberts-infrastructure/docs/architecture.md`, "Historie" voor het
   oude kip-en-ei).
2. preview-ns-labeller's RBAC applyen — verhuisd naar GitOps:
   `robberts-infrastructure/manifests/root-app/apps/preview-ns-labeller-rbac.yaml`.

De ArgoCD `Application`, de `ApplicationSet`, de `github-pr-token`-SealedSecret
en preview-ns-labeller's `Deployment` + `RBAC` staan allemaal in
`robberts-infrastructure/manifests/root-app/apps/` — één root-Application
beheert ze samen met de andere apps. Zie
`robberts-infrastructure/docs/disaster-recovery-playbook.md` stap 4.

Vereisten: `oc` ingelogd.

Het sealed-secrets public-cert staat sinds 2026-07-08 alleen nog in
`robberts-infrastructure/manifests/cluster-bootstrap/cluster-cert.pem`
(was hier een duplicaat dat kon verouderen — de sealed-secrets-key roteert
periodiek). `seal-secrets.sh` hieronder gebruikt die gedeelde kopie. Het
private keypaar blijft op het cluster (`kube-system/sealed-secrets-key…`).
Maak daar een offsite backup van als je
`oc get secret -n kube-system -l sealedsecrets.bitnami.com/sealed-secrets-key`
exporteert — anders ben je bij cluster-reinstall alle sealed-secrets kwijt.

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

## Preview-deploys per PR (S-06)

Elke open PR met branch-prefix `ai/` krijgt automatisch een eigen
preview op `https://pnf-pr-<N>.vdzonsoftware.nl` (waar `<N>` het
PR-nummer is). Bij merge/close wordt de preview opgeruimd.

**Hoe het werkt:**

1. **GitHub Actions** bouwt op elke `pull_request` event een image en
   tagt 'm met `sha-<short-sha>` van de PR's HEAD.
2. **ApplicationSet** (sinds 2026-07-08 in
   `robberts-infrastructure/manifests/root-app/apps/personal-news-feed-applicationset.yaml`)
   pollt elke 3 min GitHub voor open PR's matching `^ai/.+$` en spawnt per
   PR een ArgoCD Application.
3. **Preview-ns-labeller** (RBAC hier in `deploy/preview-ns-labeller/`,
   Deployment sinds 2026-07-08 in
   `robberts-infrastructure/manifests/root-app/apps/preview-ns-labeller-deployment.yaml`)
   zorgt dat de bijbehorende namespace `pnf-pr-<N>` bestaat met de
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

- **Database per preview.** De `preview-ns-labeller` maakt per
  preview een Neon-branch `pr-<N>` aan en patcht `PNF_DATABASE_URL`
  in het namespace-secret (KAN-55/SF-229) — previews migreren/testen
  dus op een eigen kopie, niet op prod. Restrisico's: (a) de eerste
  boot van een verse preview kan kort de prod-URL uit het base-secret
  zien totdat de labeller (30s-poll) gepatcht en de pod herstart
  heeft; (b) zonder NEON_API_KEY degradeert de labeller naar
  labeling-only en draaien previews wél op prod.

- **Geen automatic preview cleanup van orphan namespaces.** Bij merge/close
  ruimt ArgoCD de gegenereerde Application + resources netjes op
  (`prune: true`), maar de namespace zelf **altijd**: `CreateNamespace=true`
  wordt door ArgoCD niet als resource getrackt en dus nooit geprund (geen
  uitzondering-scenario, structureel — zie
  `robberts-infrastructure/docs/cluster-inventory.md` §8). Dit veroorzaakte
  29 stale `pnf-pr-*`-namespaces (opgeruimd 2026-07-08), nog niet
  structureel gefixt. Voor een schone start: handmatig
  `oc delete ns pnf-pr-<N>` als de PR echt afgesloten is, of periodiek
  cross-checken met `gh pr list --state open`.

## Bestanden in deze map

```
deploy/
├── README.md                    ← deze file
├── bootstrap.sh                 ← VEROUDERD (doet niets meer; alles via GitOps)
├── seal-secrets.sh              ← .env → SealedSecret YAML (cert komt uit robberts-infrastructure)
├── secrets-cluster.env.example  ← template
├── secrets-cluster.env          ← (gitignored) jouw waarden
├── preview-ns-labeller/
│   ├── rbac.yaml                ← VERHUISD naar robberts-infrastructure (pointer-file)
│   ├── labeller.sh
│   └── Dockerfile
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

De ArgoCD `Application`, `ApplicationSet`, `github-pr-token`-SealedSecret en
preview-ns-labeller's `Deployment` + `RBAC` staan **niet** meer in deze map —
die staan sinds 2026-07-08 in `robberts-infrastructure/manifests/root-app/apps/`
(app-of-apps-consolidatie, zie hierboven).
