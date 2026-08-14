# SF-2144 - [Audit] Deploy-documentatie: tunnel-setup, routering en labeller-stap gelijktrekken met de manifests

## Story

[Audit] Deploy-documentatie: tunnel-setup, routering en labeller-stap gelijktrekken met de manifests

<!-- refined-by-factory -->

## Scope

Documentatie-only opschoning van de resterende drift na de routeringsverbouwing (preview-router verwijderd, overstap op de OpenShift-ingressrouter). SF-2015 heeft het grootste deel opgeruimd; dit is de rest. Er wordt geen code, manifest of script gewijzigd.

**1. `deploy/README.md:142-145` — tunnel-setup stap 3 (belangrijkste punt).**
Stap 3 van "Cloudflare Tunnel — externe toegang" beschrijft nu één public hostname (`Subdomain: news`, `Domain: vdzonsoftware.nl`, `Service: HTTP → frontend.personal-news-feed.svc.cluster.local:8080`). Dat gaat rechtstreeks naar de frontend-Service, langs de ingressrouter heen, en spreekt `:183-198` in hetzelfde bestand tegen. Herschrijf stap 3 naar de wildcard-vorm: één public hostname `*.vdzonsoftware.nl` die naar de ingressrouter van het cluster wijst, die op de oorspronkelijke Host-header de bijbehorende Route kiest. Benoem expliciet dat deze ene regel `news.`, `reader.` én alle `pnf-pr-<N>.`-previews bedient, en verwijs naar `:183-198` / `runbook.md` §7 voor het volledige verhaal. Houd stap 5 consistent (de tunnel opent niet alleen `news.vdzonsoftware.nl`).

**2. `docs/factory/deployment.md` — routering ontbreekt volledig.**
Voeg een korte sectie/alinea toe (bijvoorbeeld direct na "Productie-URL") die beschrijft: Cloudflare stuurt de wildcard `*.vdzonsoftware.nl` naar de OpenShift-ingressrouter; die kiest op de Host-header de Route; er zit geen nginx-tussenlaag meer in het pad; de productiehosts staan declaratief in `deploy/base/frontend-route.yaml` (`news.vdzonsoftware.nl`) en `deploy/base/reader-route.yaml` (`reader.vdzonsoftware.nl`); op beide staat `insecureEdgeTerminationPolicy: Allow` (niet `Redirect`) omdat de Cloudflare-connector de router cluster-intern via HTTP bereikt; de `preview`-overlay zet op de frontend-Route een placeholder-host die de ApplicationSet per PR invult naar `pnf-pr-<N>.vdzonsoftware.nl`. Verwijs voor de details naar `deploy/README.md` (sectie "Preview-deploys per PR", punt 5) en `runbook.md` §7. Corrigeer daarbij `:51` ("via Cloudflare Tunnel → OpenShift-frontend") naar de ingressrouter-formulering.

**3. `docs/factory/deployment.md:100-101` — labeller-stap 4 klopt niet.**
Stap 4 beweert dat `labeller.sh` per `pnf-pr-*` namespace een Role/RoleBinding maakt voor de `claude-tester`-SA. Het script doet dat niet: het bevat nul voorkomens van `Role`/`RoleBinding`; de muterende kubectl-aanroepen zijn uitsluitend `label ns` (`labeller.sh:219`, `:224`), `create ns` (`:223`), `patch secret` (`:237`, `:256`) en `delete pod -l app=backend` (`:245`) — de overige aanroepen zijn read-only (`get ns`, `get secret`, `get app`). Herschrijf de wiring-lijst zodat die overeenkomt met de werkelijke handelingen, en vermeld dat de Role/RoleBinding voor de `claude-tester`-SA via GitOps **beheerd wordt in de repo `robberts-infrastructure`** (formuleer het als "beheerd in", niet als "bestaat daar" — dat is vanuit deze checkout niet verifieerbaar).

**4. Drie diagramregels.**
De regel `cloudflared (tunnel: *.vdzonsoftware.nl → in-cluster services)` in `deploy/README.md:24`, `docs/factory/deployment.md:36` en `runbook.md:41` suggereert dat de tunnel rechtstreeks bij de services uitkomt. Maak in alle drie duidelijk dat het verkeer naar de OpenShift-ingressrouter gaat, die op de Host-header de Route kiest.

**5. `deploy/README.md:172` — zelfde RBAC-drift.**
De regel "**Preview-ns-labeller** (RBAC hier in `deploy/preview-ns-labeller/`, …)" is stale: `deploy/preview-ns-labeller/rbac.yaml` is sinds 2026-07-08 een leeggehaald pointer-bestand dat naar `robberts-infrastructure/manifests/root-app/apps/preview-ns-labeller-rbac.yaml` verwijst. Alleen `labeller.sh` en `Dockerfile` staan nog echt in die map. Werk de zinsnede bij zodat RBAC én Deployment naar `robberts-infrastructure` verwijzen.

### Buiten scope
- Alle code, manifests, scripts en workflows — er wijzigt geen enkel `.yaml`, `.sh`, `.kt` of `.dart`-bestand.
- `docs/factory/deployment.md:14-16` (frontmatter `preview_db_secret_recipe`, "De claude-tester-SA heeft per `pnf-pr-*` namespace secrets-read"): dit is een uitspraak over de clusterstaat, niet over wat `labeller.sh` doet, en blijft correct staan.
- `docs/stories/**` (afgeronde story-verslagen en worklogs, waaronder `SF-229-*`) — historisch archief, wordt niet herschreven.
- `deploy/README.md:274` (`cloudflared-deployment.yaml ← tunnel *.vdzonsoftware.nl`) — noemt geen services en is niet misleidend.
- Alles wat SF-2015 al heeft opgeruimd; de bestaande, correcte teksten in `deploy/README.md:183-198` en `runbook.md` §7 zijn de bron van waarheid en blijven inhoudelijk ongewijzigd.

## Acceptance criteria

1. **Geen enkel deploy-document beschrijft nog een tunnel die rechtstreeks naar een Service wijst.** Concreet meetbaar: `grep -rn 'svc.cluster.local' deploy/README.md docs/factory/deployment.md runbook.md README.md` levert geen treffer meer op in de context van de Cloudflare-tunnel-configuratie.
2. **`deploy/README.md` stap 3 van de tunnel-setup beschrijft één public hostname `*.vdzonsoftware.nl` naar de ingressrouter**, met een expliciete vermelding dat daarmee `news.`, `reader.` en alle `pnf-pr-<N>.`-previews bereikbaar zijn. Stap 3 spreekt `:183-198` van hetzelfde bestand nergens meer tegen.
3. **`docs/factory/deployment.md` beschrijft de Host-based routering via de ingressrouter** en noemt daarbij minimaal: de wildcard `*.vdzonsoftware.nl`, de Host-header-gebaseerde Route-keuze, `deploy/base/frontend-route.yaml`, `deploy/base/reader-route.yaml` en `insecureEdgeTerminationPolicy: Allow`. De formulering "via Cloudflare Tunnel → OpenShift-frontend" (`:51`) komt niet meer voor.
4. **De labeller-stap in `docs/factory/deployment.md` komt overeen met de kubectl-handelingen die daadwerkelijk in `deploy/preview-ns-labeller/labeller.sh` staan**: geen Role/RoleBinding-claim meer over het script; wél de GitHub-statuscheck, namespace aanmaken/labelen, het patchen van `PNF_DATABASE_URL` + `PREVIEW_DB_BRANCH` in het secret en de backend-pod-herstart. De Role/RoleBinding wordt genoemd als "beheerd via GitOps in `robberts-infrastructure`".
5. **De drie diagramregels zijn bijgewerkt**: `deploy/README.md:24`, `docs/factory/deployment.md:36` en `runbook.md:41` vermelden alle drie de ingressrouter in plaats van "in-cluster services".
6. **`deploy/README.md` verwijst niet meer naar `deploy/preview-ns-labeller/` als vindplaats van de RBAC**; RBAC en Deployment wijzen beide naar `robberts-infrastructure`.
7. **De vier documenten zijn onderling consistent**: `deploy/README.md`, `docs/factory/deployment.md` en `runbook.md` §7 vertellen hetzelfde routeringsverhaal, zonder elkaar tegen te spreken.
8. **Geen functionele wijziging**: `git diff --stat` raakt uitsluitend `.md`-bestanden. `git diff -- deploy/base deploy/overlays deploy/preview-ns-labeller/labeller.sh` is leeg.

## Aannames

- **Concrete Service-waarde van de ingressrouter.** De DNS-naam/poort van de OpenShift-ingressrouter staat nergens in deze checkout (`runbook.md` §7 en `deploy/README.md:183-198` beschrijven het bewust functioneel, zonder naam; er is geen treffer op `openshift-ingress`/`router-internal`/`router-default` in de repo). De herschreven stap 3 beschrijft de bestemming daarom functioneel ("de ingressrouter van het cluster"). Wordt er toch een concrete waarde genoemd (bijvoorbeeld de OpenShift-standaard `router-internal-default.openshift-ingress.svc.cluster.local:80`), dan moet die expliciet gemarkeerd zijn als te controleren in het eigen cluster, niet als vastgesteld feit uit deze repo.
- Het gedrag van de tunnel in productie is vandaag al de wildcard-vorm (`deploy/README.md:183-198` en `runbook.md:309-320` beschrijven de huidige situatie). Deze story documenteert bestaand gedrag; er wordt niets aan de Cloudflare-configuratie gewijzigd of gevraagd.
- De taal blijft Nederlands en de bestaande opmaakstijl van elk document (kopniveaus, code-fences, ASCII-diagrammen) blijft behouden; alleen de betrokken regels/alinea's wijzigen.
- Verificatie is puur tekstueel (grep + leesbaarheidscheck). Er zijn geen build-, test- of `kubectl kustomize`-stappen nodig, omdat er geen manifest wijzigt; `.factory/verification.yaml` (backend-maven-verify) is niet van toepassing op deze wijziging maar mag ongewijzigd blijven draaien.
- Regelnummers in de storytekst zijn geverifieerd tegen de huidige `main`-staat; wijken ze af doordat een eerdere edit regels verschuift, dan is de tekstuele beschrijving leidend boven het regelnummer.

## Eindsamenvatting

## Eindsamenvatting SF-2144 — Deploy-documentatie gelijktrekken met de manifests

**Wat is gebouwd (documentatie-only, 3 `.md`-bestanden)**

1. **`deploy/README.md` — tunnel-setup stap 3 herschreven.** De oude losse public hostname (`Subdomain: news` → `frontend.…svc.cluster.local:8080`) is vervangen door één wildcard `*.vdzonsoftware.nl` die naar de OpenShift-ingressrouter wijst; die kiest op de ongewijzigd doorgegeven `Host`-header de bijbehorende `Route`. Expliciet benoemd dat deze ene regel `news.`, `reader.` én alle `pnf-pr-<N>.`-previews bedient, met verwijzing naar "Preview-deploys per PR" punt 5 en `runbook.md` §7. Stap 5 noemt nu alle drie de hostgroepen in plaats van alleen `news.`.
2. **`docs/factory/deployment.md` — nieuwe sectie "Routering — Host-based via de ingressrouter"** direct na "Productie-URL": wildcard, Host-header-gebaseerde Route-keuze, geen nginx-tussenlaag, `frontend-route.yaml` / `reader-route.yaml`, `insecureEdgeTerminationPolicy: Allow` mét reden (Cloudflare-connector bereikt de router cluster-intern via HTTP) en de preview-placeholderhost die de ApplicationSet per PR invult. De foute formulering "via Cloudflare Tunnel → OpenShift-frontend" is weg.
3. **Labeller-stap gecorrigeerd.** De claim dat `labeller.sh` per namespace een Role/RoleBinding aanmaakt was onjuist (nul voorkomens in het script). De lijst beschrijft nu de werkelijke handelingen: fail-closed GitHub-statuscheck → namespace aanmaken/labelen → Neon-branch → `patch secret` (`PNF_DATABASE_URL` + `PREVIEW_DB_BRANCH`) → backend-pod herstarten; overige aanroepen zijn read-only. De RBAC staat er nu als "via GitOps beheerd in `robberts-infrastructure`".
4. **Drie diagramregels** (`deploy/README.md`, `docs/factory/deployment.md`, `runbook.md`) luiden nu `*.vdzonsoftware.nl → ingressrouter → Route` in plaats van "in-cluster services", met behoud van de ASCII-uitlijning.
5. **RBAC-vindplaats in `deploy/README.md`** rechtgezet: RBAC én Deployment verwijzen naar `robberts-infrastructure`; in `deploy/preview-ns-labeller/` staan alleen `labeller.sh` en `Dockerfile` nog echt (`rbac.yaml` is een pointer-bestand).

**Keuzes**

- De concrete DNS-naam/poort van de ingressrouter staat nergens in deze checkout en is daarom **functioneel** beschreven, met de aanwijzing om die in het eigen cluster op te zoeken (`openshift-ingress`) — niet als vastgesteld feit gepresenteerd.
- De frontmatter over de `claude-tester`-SA (uitspraak over clusterstaat, buiten scope) is bewust ongewijzigd gelaten.
- `docs/stories/**` is als historisch archief niet herschreven.

**Wat is getest**

- Alle 8 acceptatiecriteria zijn onafhankelijk nagemeten door reviewer én tester: nul treffers meer op `svc.cluster.local`, `in-cluster services` en `OpenShift-frontend`; alle doc-claims zijn tegen de echte manifests en `labeller.sh` geverifieerd (routes op `Allow`, backend-route op `Redirect`, preview-placeholderhost, nul `Role`/`RoleBinding` in het script).
- `git diff --stat` raakt uitsluitend `.md`; de diff over `deploy/base`, `deploy/overlays` en `labeller.sh` is leeg — geen functionele wijziging.
- **Live gedragsbewijs** op preview `pnf-pr-226`: `news.`, `reader.` en `pnf-pr-226.` resolven naar dezelfde Cloudflare-IP's en geven alle drie HTTP 200; een onbestaande host onder dezelfde wildcard geeft **HTTP 503 met de OpenShift-router-foutpagina**. Dat bewijst dat de eindbestemming de ingressrouter is en niet de frontend-Service — precies zoals de nieuwe tekst beschrijft.
- Vangnet `backend-maven-verify` op exit 0 (regressiecheck; docs-only, geen code-impact).

**Bewust niet gedaan**

- Geen code, manifests, scripts of workflows gewijzigd; geen Cloudflare-configuratie aangepast (bestaand gedrag is gedocumenteerd, niet veranderd).
- Geen concrete router-Service-waarde vastgelegd; geen browser-screenshots (nul `.dart`-wijzigingen).
