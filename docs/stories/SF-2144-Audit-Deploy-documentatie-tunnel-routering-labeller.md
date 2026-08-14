# SF-2144 - [Audit] Deploy-documentatie: tunnel-setup, routering en labeller-stap gelijktrekken met de manifests

## Story

[Audit] Deploy-documentatie: tunnel-setup, routering en labeller-stap gelijktrekken met de manifests

<!-- refined-by-factory -->

## Samenvatting

Documentatie-only opschoning van de resterende drift na de routeringsverbouwing
(preview-router weg, overstap op de OpenShift-ingressrouter). SF-2015 heeft het
grootste deel opgeruimd; dit is de rest. Er verandert niets aan code, manifests,
scripts of gedrag — alleen tekst.

## Scope

1. **`deploy/README.md` — tunnel-setup stap 3.** Beschreef één public hostname
   (`Subdomain: news` → `frontend.personal-news-feed.svc.cluster.local:8080`),
   dus rechtstreeks naar de frontend-Service, langs de ingressrouter heen — in
   tegenspraak met de routeringsuitleg verderop in hetzelfde bestand. Herschreven
   naar de wildcard-vorm: één public hostname `*.vdzonsoftware.nl` naar de
   ingressrouter van het cluster, die op de oorspronkelijke `Host`-header de
   `Route` kiest. Stap 5 meegetrokken (de tunnel opent niet alleen `news.`).
2. **`docs/factory/deployment.md` — routering ontbrak volledig.** Nieuwe sectie
   na "Productie-URL" met de wildcard, de Host-header-gebaseerde Route-keuze,
   geen nginx-tussenlaag, de declaratieve hosts in
   `deploy/base/frontend-route.yaml` / `reader-route.yaml`,
   `insecureEdgeTerminationPolicy: Allow` mét reden, en de preview-placeholderhost
   die de ApplicationSet per PR invult.
3. **`docs/factory/deployment.md` — labeller-stap.** Stap 4 beweerde dat
   `labeller.sh` per `pnf-pr-*` namespace een Role/RoleBinding maakt; het script
   bevat nul voorkomens van `Role`/`RoleBinding`. Wiring-lijst gelijkgetrokken met
   de werkelijke kubectl-handelingen; de RBAC wordt genoemd als "via GitOps
   beheerd in `robberts-infrastructure`".
4. **Drie diagramregels.** `cloudflared (tunnel: *.vdzonsoftware.nl → in-cluster
   services)` in `deploy/README.md`, `docs/factory/deployment.md` en `runbook.md`
   suggereerde dat de tunnel rechtstreeks bij de services uitkomt.
5. **`deploy/README.md` — RBAC-drift.** "Preview-ns-labeller (RBAC hier in
   `deploy/preview-ns-labeller/`, …)" was stale: `rbac.yaml` is daar sinds
   2026-07-08 een leeggehaald pointer-bestand.

**Buiten scope:** alle code, manifests, scripts en workflows; de
frontmatter-regels over de `claude-tester`-SA (uitspraak over de clusterstaat);
`docs/stories/**` (historisch archief); `deploy/README.md`'s
`cloudflared-deployment.yaml ← tunnel *.vdzonsoftware.nl` (noemt geen services).

## Acceptance criteria

1. Geen enkel deploy-document beschrijft nog een tunnel die rechtstreeks naar een
   Service wijst (`grep -rn 'svc.cluster.local'` geen treffer in tunnel-context).
2. `deploy/README.md` stap 3 beschrijft één public hostname `*.vdzonsoftware.nl`
   naar de ingressrouter, met expliciete vermelding van `news.`, `reader.` en de
   `pnf-pr-<N>.`-previews, en spreekt de routeringssectie niet meer tegen.
3. `docs/factory/deployment.md` beschrijft de Host-based routering en noemt
   minimaal: de wildcard, de Host-header-gebaseerde Route-keuze,
   `frontend-route.yaml`, `reader-route.yaml` en
   `insecureEdgeTerminationPolicy: Allow`. "via Cloudflare Tunnel →
   OpenShift-frontend" komt niet meer voor.
4. De labeller-stap komt overeen met de kubectl-handelingen in `labeller.sh`:
   geen Role/RoleBinding-claim; wél de GitHub-statuscheck, namespace
   aanmaken/labelen, `PNF_DATABASE_URL` + `PREVIEW_DB_BRANCH` patchen en de
   backend-pod-herstart.
5. De drie diagramregels vermelden alle drie de ingressrouter.
6. `deploy/README.md` verwijst niet meer naar `deploy/preview-ns-labeller/` als
   vindplaats van de RBAC.
7. De vier documenten zijn onderling consistent.
8. Geen functionele wijziging: `git diff --stat` raakt uitsluitend `.md`.

## Aannames

- De DNS-naam/poort van de ingressrouter staat nergens in deze checkout. De
  herschreven stap 3 beschrijft de bestemming daarom puur functioneel, zonder
  voorbeeldwaarde — zo klopt criterium 1 ook letterlijk.
- Het gedrag in productie is vandaag al de wildcard-vorm; deze story documenteert
  bestaand gedrag en vraagt geen Cloudflare-wijziging.
- Verificatie is tekstueel (grep + leesbaarheidscheck); er wijzigt geen manifest.
  Het backend-vangnet is als regressiecheck wel gedraaid.
