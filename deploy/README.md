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
  ├── backend Pod + Service + Route (debug) + PVC (runtime-state, 5 Gi)
  ├── frontend Pod + Service + Route ← gebruikers (news.vdzonsoftware.nl)
  ├── reader Pod + Service + Route   ← reader.vdzonsoftware.nl
  ├── cloudflared    (tunnel: *.vdzonsoftware.nl → ingressrouter → Route)
  └── Secret (uit SealedSecret in git, ge-decrypt door cluster)
```

Data zelf staat in een externe Postgres (Neon) — inclusief de podcast-audio,
die sinds migratie `V5__podcast_audio_bytes.sql` als BYTEA in de database
staat. Het PVC houdt alleen runtime-state / admin-cleanup paden.

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

De twee imperatieve stappen die hier ooit een eigen bootstrap-script nodig
hadden, zijn allebei vervallen:

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
- GitHub Actions bouwt nieuwe images, pusht naar `ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend,reader}:sha-…`
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

Voor de eenmalige Google OAuth-, GitHub Actions- en Android signing-inrichting zie
[`docs/google-login-setup.md`](../docs/google-login-setup.md).

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
3. **Eén public hostname** in de tunnel-config — een wildcard die naar de
   ingressrouter van het cluster wijst:
   - Subdomain: `*`
   - Domain: `vdzonsoftware.nl` (dus `*.vdzonsoftware.nl`)
   - Service: `HTTP` → de OpenShift-ingressrouter van je cluster (dus
     **niet** rechtstreeks een app-Service). De concrete DNS-naam en poort van
     die router staan bewust niet in deze repo — die zijn cluster-specifiek;
     zoek ze op in je eigen cluster (ingress-controller in
     `openshift-ingress`).

   De tunnel geeft de oorspronkelijke `Host`-header ongewijzigd door, en de
   ingressrouter kiest daarop de bijbehorende `Route`. Deze ene regel bedient
   dus `news.vdzonsoftware.nl`, `reader.vdzonsoftware.nl` én alle
   `pnf-pr-<N>.vdzonsoftware.nl`-previews; er zijn geen losse public hostnames
   per app of per PR nodig. Zie "Preview-deploys per PR", punt 5 hieronder en
   `runbook.md` §7 voor het volledige routeringsverhaal.
4. **Token in de SealedSecret** zetten:
   ```bash
   # Edit deploy/secrets-cluster.env, voeg TUNNEL_TOKEN=eyJ... toe
   ./deploy/seal-secrets.sh
   git add deploy/base/sealed-secret-api-keys.yaml
   git commit -m "deploy: add cloudflare tunnel token"
   git push
   ```
5. ArgoCD synct, `cloudflared`-pod start, tunnel opent → alle hosts onder
   `*.vdzonsoftware.nl` waarvoor een `Route` bestaat werken vanaf elke browser,
   met geldig Cloudflare-cert: `https://news.vdzonsoftware.nl`,
   `https://reader.vdzonsoftware.nl` en elke actieve
   `https://pnf-pr-<N>.vdzonsoftware.nl`-preview.

Geen port-forwarding op je router nodig — alleen uitgaande connectie van het cluster naar Cloudflare.

## Preview-deploys per PR

GitHub Actions bouwt de PR-image. De ApplicationSet in `robberts-infrastructure`
maakt `pnf-pr-<N>` en de route `https://pnf-pr-<N>.vdzonsoftware.nl`.

### Preview-database op de gedeelde non-productionserver

Sinds 15 september 2026 krijgt iedere preview een lege database
`pnf_pr_<nummer>_<namespace-uid-hash>` op `postgres.postgres-nonproduction.svc`.
De controller in `robberts-infrastructure` maakt de rol en het Secret
`preview-postgres` aan. De backend wacht op dat Secret en gebruikt TLS met
`verify-full`. Flyway maakt het schema; er wordt geen productiedata gekopieerd.

De ApplicationSet maakt de namespace. De bestaande preview-reconciler verwijdert
haar na de PR-lifecyclecontrole. De databasecontroller verwijdert vervolgens de
bij die namespace-UID behorende database na een uur grace. Een heropende PR met
een nieuwe namespace krijgt een andere database en een ander wachtwoord.

`newsfeed-api-keys` blijft uitsluitend in `personal-news-feed`. De oude Neon-labeller
is uitgeschakeld en heeft geen secretrechten. Preview-AI-verkeer gebruikt de
Agent Runtime-acceptatieomgeving met een eigen, daarvoor gescopete credential.
Productie blijft op Neon.

### Tester-login en testdata

Test uitsluitend de preview-URL. Gebruik de bestaande preview-AI-toegang als de
harness die veilig aanbiedt, of registreer via de UI een wegwerpaccount
`tester_<lowercase-story-id>`. Maak benodigde synthetische data via de preview-UI
of de daarvoor bedoelde preview-API. Verwijder het wegwerpaccount na de test.
Er bestaat geen uit productie gekopieerde testgebruiker en er is geen
`TESTER_USERNAME`/`TESTER_PASSWORD` uit `newsfeed-api-keys` nodig. Geen
productielogins, productiecredentials, rechtstreekse wachtwoordresets of SQL-datawijzigingen.

De JWT-sleutel is tijdelijk en uniek per backendpod. Na een podherstart logt
een tester opnieuw in. Eerste provisioning kan enkele minuten duren; ontbreekt
`preview-postgres`, controleer de databasecontroller in `postgres-nonproduction`.
Er wordt nooit teruggevallen op de productieverbinding.

## Bestanden in deze map

```
deploy/
├── README.md                    ← deze file
├── seal-secrets.sh              ← .env → SealedSecret YAML (cert komt uit robberts-infrastructure)
├── neon-endpoint-config.sh      ← Neon-endpoint op suspend=300s / 0.25–1 CU (idempotent, zie runbook §6.1)
├── secrets-cluster.env.example  ← template
├── secrets-cluster.env          ← (gitignored) jouw waarden
├── preview-ns-labeller/
│   ├── rbac.yaml                ← VERHUISD naar robberts-infrastructure (pointer-file)
│   ├── labeller.sh
│   └── Dockerfile
├── base/
│   ├── kustomization.yaml
│   ├── backend-deployment.yaml
│   ├── backend-service.yaml
│   ├── backend-route.yaml       ← optioneel/debug
│   ├── backend-pvc.yaml         ← runtime-state / admin-cleanup paden
│   ├── frontend-deployment.yaml
│   ├── frontend-service.yaml
│   ├── frontend-route.yaml
│   ├── reader-deployment.yaml
│   ├── reader-service.yaml
│   ├── reader-route.yaml        ← reader.vdzonsoftware.nl
│   ├── cloudflared-deployment.yaml   ← tunnel *.vdzonsoftware.nl
│   └── sealed-secret-api-keys.yaml  ← na seal-secrets.sh
└── overlays/
    ├── openshift/
    │   └── kustomization.yaml  ← cluster-specifieke patches (productie)
    └── preview/
        └── kustomization.yaml  ← per-PR preview: frontend-Route blijft (met
                                   per-PR host), backend-debug- en reader-Route,
                                   PVC, cloudflared en SealedSecret vervallen;
                                   emptyDir i.p.v. PVC en een ephemeral
                                   JWT-sleutel (SF-1542)
```

De ArgoCD `Application`, `ApplicationSet`, `github-pr-token`-SealedSecret en
preview-ns-labeller's `Deployment` + `RBAC` staan **niet** meer in deze map —
die staan sinds 2026-07-08 in `robberts-infrastructure/manifests/root-app/apps/`
(app-of-apps-consolidatie, zie hierboven).
