---
default_base_branch: main
branch_prefix: ai/
preview_url_template: "https://pnf-pr-{pr_num}.vdzonsoftware.nl"
preview_namespace_template: "pnf-pr-{pr_num}"
preview_db_secret_recipe: |
  # Previews draaien op een EIGEN per-PR Neon-branch (pr-<N>), niet op prod.
  # Het basis-secret wordt via Reflector naar elke pnf-pr-* namespace
  # gespiegeld; de preview-ns-labeller patcht daarin vervolgens
  # PNF_DATABASE_URL naar de branch-specifieke URL en zet de marker-key
  # PREVIEW_DB_BRANCH=pr-<N> (die marker voedt nog steeds de geïsoleerde
  # per-PR branch-DB, maar wordt sinds SF-282 niet meer door de
  # tester-login gebruikt). De tester logt in met een vaste test-user uit
  # hetzelfde secret (TESTER_USERNAME/TESTER_PASSWORD) — read-only, geen
  # DB-mutatie en geen guard-check meer. De claude-tester-SA heeft per
  # pnf-pr-* namespace secrets-read.
  echo "Test-user:            oc get secret newsfeed-api-keys -n pnf-pr-<N> -o jsonpath='{.data.TESTER_USERNAME}' | base64 -d"
  echo "Preview-branch-DB-URL: oc get secret newsfeed-api-keys -n pnf-pr-<N> -o jsonpath='{.data.PNF_DATABASE_URL}' | base64 -d"
  echo "Branch-marker:        oc get secret newsfeed-api-keys -n pnf-pr-<N> -o jsonpath='{.data.PREVIEW_DB_BRANCH}' | base64 -d"
---

# Deployment — Personal News Feed

## Architectuur

```
GitHub Actions ── builds ──► ghcr.io (public)
        │
        ├── pusht nieuwe SHA naar deploy/base/kustomization.yaml
        ▼
ArgoCD ◄── synct main ── namespace: personal-news-feed (OpenShift)
        ├── backend   Pod + Service (poort 8080) + Route (debug)
        │              + PVC (runtime-state, 5 Gi)
        ├── frontend  Pod + Service + Route ← gebruikers (news.vdzonsoftware.nl)
        ├── reader    Pod + Service + Route ← reader.vdzonsoftware.nl
        ├── cloudflared    (tunnel: *.vdzonsoftware.nl → ingressrouter → Route)
        └── Secret (via SealedSecret in git)
```

Data staat in externe PostgreSQL (Neon); de podcast-audio staat sinds migratie
`V5__podcast_audio_bytes.sql` als BYTEA in de DB. Het PVC houdt alleen
runtime-state / admin-cleanup paden.

De prod-Neon-endpoint staat sinds SF-1739 op scale-to-zero: `suspend_timeout_seconds=300`
en max 1 CU, gezet met het idempotente `deploy/neon-endpoint-config.sh` (credentials
uitsluitend uit `NEON_API_KEY`/`NEON_PROJECT_ID`). Zie runbook §6.1 voor draaien,
read-only verifiëren (`--verify`), cold-startgedrag en terugdraaien.

## Productie-URL

`https://news.vdzonsoftware.nl` (via Cloudflare Tunnel → OpenShift-ingressrouter
→ frontend-Route)

## Routering — Host-based via de ingressrouter

Cloudflare stuurt de wildcard `*.vdzonsoftware.nl` via de tunnel naar de
OpenShift-ingressrouter van het cluster; die kiest op de (ongewijzigd
doorgegeven) `Host`-header de bijbehorende `Route`. Er zit géén
nginx-tussenlaag meer in het pad.

- De productiehosts staan declaratief in de manifests:
  `deploy/base/frontend-route.yaml` (`news.vdzonsoftware.nl`) en
  `deploy/base/reader-route.yaml` (`reader.vdzonsoftware.nl`).
- Op beide staat `insecureEdgeTerminationPolicy: Allow` (niet `Redirect`),
  omdat de Cloudflare-connector de router cluster-intern via HTTP bereikt — een
  redirect naar HTTPS zou dat verkeer laten stuiteren.
  `deploy/base/backend-route.yaml` (debug) houdt bewust `Redirect`.
- Voor previews zet de `preview`-overlay op de frontend-Route een
  placeholder-host, die de ApplicationSet per PR invult naar
  `pnf-pr-<N>.vdzonsoftware.nl`.

Details: `deploy/README.md` (sectie "Preview-deploys per PR", punt 5) en
`runbook.md` §7.

## Preview-deploys per PR

Elke open PR met branch-prefix `ai/` krijgt automatisch een preview op:

```
https://pnf-pr-<N>.vdzonsoftware.nl
```

### Preview-JWT — ephemeral sleutel per pod (NIET de prod-sleutel)

Previews krijgen sinds SF-1542 **niet** meer de productie-JWT-sleutel mee:
de `preview`-overlay (`deploy/overlays/preview/kustomization.yaml`) zet
`APP_JWT_SECRET` leeg en laat de `secretKeyRef` naar
`newsfeed-api-keys`/`JWT_SECRET` vervallen. De backend genereert dan bij het
opstarten zelf een random ephemeral sleutel. Gevolgen voor de factory:

- Tokens uit een preview zijn **alleen daar** geldig, niet op productie
  (en omgekeerd) — code op een PR-branch kan geen prod-token meer smeden.
- Tokens vervallen bij pod-herstart. De tester en de e2e-runner loggen per
  run opnieuw in via de UI / `POST /api/auth/login`, dus dat is geen
  regressie; zie je onverwacht een 401 na een redeploy, log dan opnieuw in.
- Productie (`openshift`-overlay) blijft de vaste sleutel uit de
  SealedSecret gebruiken; er is geen nieuw secret of her-sealen nodig.

### Preview-DB — eigen per-PR Neon-branch (NIET prod)

Elke preview krijgt een **eigen, wegwerp-Neon-branch** `pr-<N>`, afgesplitst
van de productie-branch. Dat betekent:

- Flyway-migraties in een PR draaien op de **branch**, niet op prod-data.
- De branch levert de geïsoleerde testdata waarmee de tester de feature
  realistisch ziet. De tester muteert die branch niet meer: inloggen gaat
  via een vaste test-user uit het secret (zie "Tester-login" hieronder).
- Bij PR-close ruimt de `preview-ns-labeller` de branch (incl. testdata) op —
  maar pas nadat de preview-namespace daadwerkelijk verdwenen is **én** GitHub
  bevestigt dat de PR gesloten is. Zolang één van beide niet vaststaat blijft
  de branch staan.

Wiring (door `deploy/preview-ns-labeller/labeller.sh`):

1. Vraagt eerst bij GitHub de actuele PR-status op (`GET /repos/…/pulls/<N>`).
   Alleen bij een bevestigd open PR volgen de creatiestappen; deze check staat
   vóór élke creatiehandeling, dus ook vóór het (opnieuw) aanmaken en labelen
   van de namespace `pnf-pr-<N>`.
2. Maakt de namespace `pnf-pr-<N>` aan als die nog niet bestaat en (her)zet het
   label `argocd.argoproj.io/managed-by=argocd` (`kubectl create ns` /
   `kubectl label ns`), anders blokkeert de argocd-operator de preview.
3. Maakt de Neon-branch `pr-<N>` aan (parent = productie-branch).
4. Patcht `PNF_DATABASE_URL` in het `newsfeed-api-keys`-secret van
   `pnf-pr-<N>` naar de branch-URL, en zet de marker `PREVIEW_DB_BRANCH=pr-<N>`
   (`kubectl patch secret`).
5. Herstart de backend-pod (`kubectl delete pod -l app=backend`) zodat die de
   gepatchte `PNF_DATABASE_URL` oppikt; het Deployment respawnt 'm.

Het script maakt zélf **geen** RBAC aan: de Role/RoleBinding waarmee de
`claude-tester`-SA het secret in een `pnf-pr-*`-namespace mag lezen wordt via
GitOps beheerd in de repo `robberts-infrastructure`
(`manifests/root-app/apps/preview-ns-labeller-rbac.yaml`). De overige
kubectl-aanroepen in `labeller.sh` zijn read-only (`get ns`, `get secret`,
`get app`).

Vereist dat drie sleutels in het secret aanwezig zijn: `NEON_API_KEY`,
`NEON_PROJECT_ID` **en** `GITHUB_TOKEN`. De eerste twee zetten de Neon-mode
aan; ontbreken die, dan valt de labeller terug op alleen namespace-labeling
(geen branch, geen marker) en deelt de preview geen geïsoleerde branch-DB.

`GITHUB_TOKEN` is strenger, want de PR-statuscheck is **fail-closed**:
ontbreekt het token, faalt de curl of komt er geen HTTP 200 terug, dan is de
PR-status "onbekend" en voert de labeller voor die preview **géén enkele
mutatie** uit — geen namespace-label, geen Neon-branch, geen secret-patch en
geen cleanup. De preview blijft dan hangen op wat er al stond (in het ergste
geval dus zonder namespace en zonder branch-DB).

De tester-login zelf raakt de DB niet en blijft ongewijzigd werken via de
test-user-creds (zie "Tester-login" hieronder).

**Tester-login (vaste test-user, sinds SF-282).** De tester krijgt een
bruikbare preview-URL (`https://pnf-pr-<N>.vdzonsoftware.nl`) en logt daarop
via de Flutter-UI in met een vaste, dedicated test-user. De creds
`TESTER_USERNAME` / `TESTER_PASSWORD` staan in het `newsfeed-api-keys`-secret
(via Reflector in elke `pnf-pr-*`-namespace beschikbaar). Draait de tester
onder de claude-runner, dan leest `runner.sh` ze runtime read-only uit het
secret en exporteert `TESTER_USERNAME` / `TESTER_PASSWORD`. Draait de tester
onder een andere harness (bv. softwarefactory's `agent:local`-image, waar
`runner.sh` nooit draait), dan blijven die env-vars leeg en **leest de tester
ze zélf** read-only uit het namespace-secret (namespace/PR uit
`SF_PREVIEW_NAMESPACE` / `SF_PR_NUMBER` met fallback op `pnf-pr-<N>`). De login
doet **geen DB-mutatie, geen wachtwoord-reset en geen guard-check**. Ontbreken
of falen de creds, dan valt de tester terug op de wegwerp-account-flow
(`tester_<story-id>` registreren via de UI + `DELETE /api/account/me` aan het
eind). Zie `docs/factory/agents/tester.md`.

> De oude SF-229-flow (robbert-wachtwoord-reset + fail-closed
> `PREVIEW_DB_GUARD`-check) is hiermee vervallen. De per-PR Neon-branch en
> `preview-db-guard.py` zelf blijven ongewijzigd bestaan (geïsoleerde testdata
> per PR), maar zijn niet meer onderdeel van de tester-login. De optionele
> `PROD_DB_HOST`-env-var op de `jira-poller` voedde die guard en is daarmee
> legacy: ze speelt geen rol meer in de loginflow.

## Deploy-flow (dagelijks gebruik)

Push naar `main`:
1. GitHub Actions bouwt nieuwe backend-, frontend- en reader-images (`ghcr.io/robbertvdzon/personal-news-feed-{backend,frontend,reader}:sha-…`).
2. Workflow committet de nieuwe SHA in `deploy/base/kustomization.yaml`.
3. ArgoCD detecteert de manifest-wijziging, pods rollen automatisch.

## Secret wijzigen

```bash
cp deploy/secrets-cluster.env.example deploy/secrets-cluster.env
# Vul echte waarden in (gitignored)
./deploy/seal-secrets.sh
git add deploy/base/sealed-secret-api-keys.yaml
git commit -m "deploy: update sealed secrets"
git push
```

## Status checken

```bash
oc get pods -n personal-news-feed
oc logs -n personal-news-feed deploy/backend -f
oc get routes -n personal-news-feed
```
