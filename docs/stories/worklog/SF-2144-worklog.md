# SF-2144 - Worklog

Story-context bij eerste pickup:
Deploy-docs gelijktrekken met de manifests (tunnel, routering, labeller)

Documentatie-only wijziging; raak geen .yaml, .sh, .kt of .dart aan. (1) deploy/README.md tunnel-setup stap 3: vervang de public hostname 'Subdomain news' + 'Service HTTP -> frontend.personal-news-feed.svc.cluster.local:8080' door één wildcard-hostname *.vdzonsoftware.nl die naar de ingressrouter van het cluster gaat, die op de oorspronkelijke Host-header de Route kiest; benoem dat daarmee news., reader. en alle pnf-pr-<N>.-previews bereikbaar zijn en verwijs naar de bestaande routeringsuitleg in hetzelfde bestand (sectie 'Preview-deploys per PR', punt 5) en runbook.md §7; trek stap 5 mee zodat die niet meer alleen news.vdzonsoftware.nl noemt. De DNS-naam/poort van de ingressrouter staat NERGENS in deze checkout: beschrijf de bestemming functioneel; noem je toch een voorbeeldwaarde, markeer die expliciet als 'controleer in je eigen cluster', niet als vastgesteld feit. (2) docs/factory/deployment.md: voeg rond 'Productie-URL' een korte alinea toe over de Host-based routering via de ingressrouter, met minimaal de wildcard *.vdzonsoftware.nl, de Host-header-gebaseerde Route-keuze, geen nginx-tussenlaag, deploy/base/frontend-route.yaml (news.) en deploy/base/reader-route.yaml (reader.), insecureEdgeTerminationPolicy: Allow met de reden (Cloudflare-connector bereikt de router cluster-intern via HTTP) en de preview-placeholderhost die de ApplicationSet per PR invult; vervang daarbij de formulering 'via Cloudflare Tunnel -> OpenShift-frontend'. (3) docs/factory/deployment.md labeller-wiring (nu punten 1-4 rond regel 90-101): breng de lijst in lijn met wat labeller.sh echt doet - fail-closed GitHub-statuscheck vóór elke creatiehandeling, namespace pnf-pr-<N> aanmaken en labelen, Neon-branch pr-<N> aanmaken, PNF_DATABASE_URL + marker PREVIEW_DB_BRANCH in het secret patchen, backend-pod herstarten (delete pod -l app=backend); verwijder de claim dat het script een Role/RoleBinding maakt (het script bevat 0 voorkomens van Role/RoleBinding) en vermeld dat die RBAC via GitOps BEHEERD WORDT IN de repo robberts-infrastructure (die formulering, niet 'bestaat daar'). Laat de frontmatter-regels over de claude-tester-SA (secrets-read per pnf-pr-* namespace) ongewijzigd: dat is een uitspraak over de clusterstaat. (4) Diagramregel 'cloudflared (tunnel: *.vdzonsoftware.nl -> in-cluster services)' in deploy/README.md, docs/factory/deployment.md en runbook.md: laat alle drie de ingressrouter noemen in plaats van in-cluster services, met behoud van de ASCII-uitlijning per bestand. (5) deploy/README.md, punt 3 van 'Hoe het werkt': de zinsnede 'Preview-ns-labeller (RBAC hier in deploy/preview-ns-labeller/, ...)' is stale - rbac.yaml is een leeggehaald pointer-bestand; laat RBAC én Deployment naar robberts-infrastructure verwijzen en noem dat in die map alleen labeller.sh en Dockerfile echt staan. Taal blijft Nederlands, bestaande opmaakstijl (kopniveaus, code-fences, diagrammen) blijft behouden; docs/stories/** blijft als historisch archief ongemoeid. Regelnummers uit de story kunnen schuiven zodra je tekst toevoegt - de tekstuele beschrijving is leidend. Sluit af met een repo-brede grep op svc.cluster.local, 'in-cluster services', 'OpenShift-frontend' en RoleBinding-claims in doc-context om restjes te vangen, lees de drie documenten in samenhang na op onderlinge tegenspraak, doe een zelfreview van de diff (git diff --stat mag uitsluitend .md tonen) en werk het worklog bij.

Stappenplan:
[x]: read issue and target docs
[x]: implement requested changes
[x]: run relevant tests
[x]: update story-log with results

Done / rationale:
- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.
- **SF-2145 (developer, documentatie-only).** Vijf punten uit de story doorgevoerd
  in exact drie `.md`-bestanden; geen `.yaml`/`.sh`/`.kt`/`.dart` geraakt.

  1. `deploy/README.md`, tunnel-setup stap 3: de losse public hostname
     (`Subdomain: news` → `frontend.…svc.cluster.local:8080`) is vervangen door
     één wildcard `*.vdzonsoftware.nl` naar de ingressrouter van het cluster, die
     op de doorgegeven `Host`-header de `Route` kiest. Expliciet benoemd dat die
     ene regel `news.`, `reader.` én alle `pnf-pr-<N>.`-previews bedient, met
     verwijzing naar "Preview-deploys per PR" punt 5 en `runbook.md` §7. De
     concrete DNS-naam/poort van de router staat nergens in deze checkout; de
     bestemming is daarom functioneel beschreven (geen voorbeeldwaarde opgenomen,
     zodat AC1 ook letterlijk klopt: nul `svc.cluster.local`-treffers in
     tunnel-context). Stap 5 noemt nu alle drie de hostgroepen i.p.v. alleen
     `news.`.
  2. `docs/factory/deployment.md`: nieuwe sectie "Routering — Host-based via de
     ingressrouter" direct na "Productie-URL", met de wildcard, de
     Host-header-gebaseerde Route-keuze, "geen nginx-tussenlaag",
     `deploy/base/frontend-route.yaml` / `reader-route.yaml`,
     `insecureEdgeTerminationPolicy: Allow` mét reden (Cloudflare-connector
     bereikt de router cluster-intern via HTTP) en de preview-placeholderhost die
     de ApplicationSet per PR invult. De formulering "via Cloudflare Tunnel →
     OpenShift-frontend" op de Productie-URL-regel is weg.
  3. `docs/factory/deployment.md`, labeller-wiring: de lijst komt nu overeen met
     de werkelijke kubectl-handelingen in `labeller.sh` (fail-closed
     GitHub-statuscheck → `create ns`/`label ns` → Neon-branch → `patch secret`
     met `PNF_DATABASE_URL` + `PREVIEW_DB_BRANCH` → `delete pod -l app=backend`).
     De claim dat het script een Role/RoleBinding maakt is verwijderd (het script
     bevat nul voorkomens van `Role`/`RoleBinding`); die RBAC wordt nu genoemd als
     "via GitOps beheerd in de repo `robberts-infrastructure`". De frontmatter
     over de `claude-tester`-SA (clusterstaat) is bewust ongewijzigd.
  4. De drie diagramregels (`deploy/README.md`, `docs/factory/deployment.md`,
     `runbook.md`) noemen nu `ingressrouter → Route` i.p.v. "in-cluster services",
     met behoud van de ASCII-uitlijning per bestand.
  5. `deploy/README.md` punt 3 van "Hoe het werkt": RBAC én Deployment verwijzen
     nu allebei naar `robberts-infrastructure`, met de opmerking dat in
     `deploy/preview-ns-labeller/` alleen `labeller.sh` en `Dockerfile` echt
     staan (`rbac.yaml` is een pointer-bestand).

- Verificatie: `grep` op `in-cluster services`, `OpenShift-frontend` en
  `svc.cluster.local` in `deploy/`, `docs/factory/`, `runbook.md`, `README.md`
  levert nul treffers; de enige `RoleBinding`-treffer is de nieuwe zin die
  expliciet zegt dat het script die *niet* maakt. `git diff --stat` toont
  uitsluitend `.md`; `git diff -- deploy/base deploy/overlays
  deploy/preview-ns-labeller/labeller.sh` is leeg. De drie documenten zijn in
  samenhang nagelezen: ze vertellen hetzelfde routeringsverhaal als het
  bestaande, correcte `runbook.md` §7. Vangnet `mvn -B --no-transfer-progress
  clean verify` gedraaid als regressiecheck (docs-only wijziging, geen
  code-impact).

- **[REVIEWER] SF-2145 — akkoord.** Volledige story-diff (`git diff main...HEAD`)
  beoordeeld: 3 doc-bestanden + story-log + worklog, uitsluitend `.md` (AC8;
  `git diff main...HEAD -- deploy/base deploy/overlays
  deploy/preview-ns-labeller/labeller.sh` is leeg). AC1/5 nagemeten: nul treffers
  op `svc.cluster.local`, `in-cluster services` en `OpenShift-frontend` in
  `deploy/`, `docs/factory/`, `runbook.md`, `README.md`; alle drie de
  diagramregels noemen `ingressrouter → Route`. AC4 tegen `labeller.sh`
  geverifieerd: de gedocumenteerde volgorde (fail-closed `github_pr_is_open` →
  `ensure_ns_with_label` (`create ns`/`label ns` :219-224) → Neon-branch →
  `patch secret` :237/:256 → `delete pod -l app=backend` :245) komt overeen met
  de hoofdloop (:280-372); nul voorkomens van `Role`/`RoleBinding` in het script.
  AC3/6 tegen de manifests gecheckt: `frontend-route.yaml:21` en
  `reader-route.yaml:21` staan op `Allow`, `backend-route.yaml:22` op `Redirect`,
  `overlays/preview/kustomization.yaml:48` zet
  `preview-host-must-be-set.invalid`; `deploy/preview-ns-labeller/rbac.yaml` is
  inderdaad een pointer-bestand. AC7: `deploy/README.md` punt 5,
  `docs/factory/deployment.md` en `runbook.md` §7 vertellen hetzelfde verhaal
  zonder tegenspraak. Testbewijs: `[FACTORY VERIFICATION EVIDENCE]`
  `backend-maven-verify` passed/exit 0, `testedTreeSha`
  `d12655d…` = tree van developercommit `f266363` — hoort dus bij deze revisie.

- **[TESTER] SF-2146 — story-brede test: akkoord.** Docs-only story; alle acht
  AC's onafhankelijk nagemeten, plus een live routeringscheck op de preview.

  - AC1: `grep -rn 'svc.cluster.local' deploy/README.md docs/factory/deployment.md
    runbook.md README.md` → 0 treffers (exit 1). Ook `OpenShift-frontend` → 0
    treffers in `deploy/`, `docs/factory/`, `runbook.md`, `README.md`.
  - AC2: `deploy/README.md:142-155` beschrijft één public hostname
    `Subdomain: *` / `Domain: vdzonsoftware.nl` naar de ingressrouter, met
    expliciete opsomming van `news.`, `reader.` én `pnf-pr-<N>.` en verwijzing
    naar "Preview-deploys per PR" punt 5 + `runbook.md` §7. Geen concrete
    router-DNS-naam genoemd (conform de aanname in de story). Stap 5 (`:166-170`)
    noemt nu alle drie de hostgroepen. Geen tegenspraak meer met `:199-212`.
  - AC3: nieuwe sectie `docs/factory/deployment.md:53-73` bevat de wildcard, de
    Host-header-gebaseerde Route-keuze, "geen nginx-tussenlaag",
    `deploy/base/frontend-route.yaml`, `deploy/base/reader-route.yaml` en
    `insecureEdgeTerminationPolicy: Allow` mét reden. Tegen de manifests
    gecontroleerd: `frontend-route.yaml:11/:21` = `news.vdzonsoftware.nl` +
    `Allow`, `reader-route.yaml:11/:21` = `reader.vdzonsoftware.nl` + `Allow`,
    `backend-route.yaml:22` = `Redirect`, `overlays/preview/kustomization.yaml:48`
    = placeholder `preview-host-must-be-set.invalid`. Alle vijf claims kloppen.
  - AC4: `grep -nE 'Role|RoleBinding' deploy/preview-ns-labeller/labeller.sh` → 0
    treffers (de twee schijnbare hits van een ruimere regex zijn `NEON_ROLE`
    op `:37`/`:170`). De gedocumenteerde volgorde in `deployment.md:116-137`
    komt exact overeen met de hoofdloop `:288-372`: `github_pr_is_open` (fail-closed,
    `continue` vóór `ensure_ns_with_label`) → `create ns`/`label ns` (`:219-224`) →
    Neon-branch → `patch secret` (`:237`, `:256`) → `delete pod -l app=backend`
    (`:245`). Overige kubectl-aanroepen zijn read-only (`get ns`, `get secret`,
    `get app`) — zoals de doc nu zegt. RBAC staat er als "via GitOps beheerd in
    `robberts-infrastructure`".
  - AC5: `deploy/README.md:24`, `docs/factory/deployment.md:36` en `runbook.md:41`
    luiden alle drie `(tunnel: *.vdzonsoftware.nl → ingressrouter → Route)`;
    `in-cluster services` komt buiten `docs/stories/**` nergens meer voor.
  - AC6: `deploy/README.md:188-192` verwijst voor RBAC én Deployment naar
    `robberts-infrastructure` en benoemt `rbac.yaml` als pointer-bestand — komt
    overeen met de daadwerkelijke inhoud van `deploy/preview-ns-labeller/rbac.yaml`
    en de mapinhoud (alleen `Dockerfile`, `labeller.sh`, `rbac.yaml`).
  - AC7: de vier documenten nagelezen in samenhang; identiek routeringsverhaal,
    geen tegenspraak. Geen `preview-router`-resten meer buiten `docs/stories/**`.
  - AC8: `git diff --stat main...HEAD` toont uitsluitend `.md`
    (`git diff --name-only main...HEAD | grep -v '\.md$'` is leeg) en
    `git diff main...HEAD -- deploy/base deploy/overlays
    deploy/preview-ns-labeller/labeller.sh` is leeg. De buiten-scope frontmatter
    (`preview_db_secret_recipe`, `claude-tester`-SA) is ongewijzigd.

  **Live gedragsbewijs voor het gedocumenteerde routeringsverhaal** (preview
  `pnf-pr-226`): `news.`, `reader.` en `pnf-pr-226.vdzonsoftware.nl` resolven alle
  drie naar dezelfde Cloudflare-IP's (`104.21.90.25`, `172.67.151.135`) en geven
  alle drie HTTP 200 — dus één wildcard-record/tunnel voor alle hosts, precies zoals
  stap 3 nu beschrijft. Doorslaggevend: een onbestaande host onder dezelfde wildcard
  (`https://pnf-pr-999999.vdzonsoftware.nl/`) geeft **HTTP 503 met de
  OpenShift-router-foutpagina** ("Application is not available", "Route and path
  matches"). Kwam de tunnel rechtstreeks bij de frontend-Service uit — zoals de oude
  stap 3 beschreef — dan had die host gewoon 200 + de Flutter-app gegeven. Het 503-
  antwoord bewijst dus dat de eindbestemming de OpenShift-ingressrouter is die op de
  Host-header een Route zoekt. De herschreven documentatie beschrijft het werkelijke
  productiegedrag.

  Geen browser-screenshots: de story raakt geen frontend-code (0 gewijzigde
  `.dart`-bestanden), dus visueel bewijs is niet van toepassing; Playwright is in
  deze container niet geïnstalleerd. Geen login/DB-mutatie nodig of gedaan.
  Vangnet: docs-only diff zonder code-impact; `backend-maven-verify` stond op
  exit 0 voor de developer-tree en de harness draait het revisiegebonden opnieuw
  na deze run. Geen bevindingen — akkoord.
