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
