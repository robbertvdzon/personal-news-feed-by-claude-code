# SF-2015 - [Audit] Deploy-documentatie in lijn brengen met de manifests van 7 augustus (preview-router weg, gedeelde ingress, GitHub-check)

## Story

[Audit] Deploy-documentatie in lijn brengen met de manifests van 7 augustus (preview-router weg, gedeelde ingress, GitHub-check)

<!-- refined-by-factory -->

## Samenvatting

Op 7 augustus is de manier waarop bezoekers bij de app uitkomen veranderd, en is
de automatische wegwerp-database per pull request strenger geworden. De
documentatie is toen niet meegegaan en beschrijft nu een situatie die niet meer
bestaat. Deze story werkt de drie deploy-documenten bij zodat ze weer kloppen.

Er verandert niets aan de werking van de app: alleen tekst en toelichtingen.

## Scope

Uitsluitend documentatie- en commentaarwijzigingen in `docs/factory/deployment.md`,
`deploy/README.md`, `runbook.md` en `deploy/overlays/preview/kustomization.yaml`.
Geen wijzigingen aan manifests (behalve comments), code, tests of gedrag.

1. **Verwijderd onderdeel uit de architectuurdiagrammen.** Haal de regel
   `preview-router (nginx, host-based routing voor PR-previews)` weg uit de drie
   diagrammen: `docs/factory/deployment.md:37`, `deploy/README.md:25`,
   `runbook.md:42`.
2. **Bestandenlijst.** Verwijder `preview-router-deployment.yaml` en
   `preview-router-config.yaml` uit de `base/`-lijst in `deploy/README.md:255-256`.
3. **Nieuwe routering beschrijven** in `deploy/README.md:181-183` (stap 5 van
   "Hoe het werkt") en `runbook.md:305`: Cloudflare stuurt de wildcard
   `*.vdzonsoftware.nl` naar de OpenShift-ingressrouter, die op de Host-header de
   juiste Route kiest. Productiehosts staan declaratief in
   `deploy/base/frontend-route.yaml` (`news.vdzonsoftware.nl`) en
   `deploy/base/reader-route.yaml` (`reader.vdzonsoftware.nl`); voor previews zet
   de overlay een placeholder-host die de ApplicationSet per PR invult. Vermeld
   dat `insecureEdgeTerminationPolicy` op beide Routes van `Redirect` naar `Allow`
   staat omdat de Cloudflare-connector de router cluster-intern via HTTP bereikt.
4. **Correctie preview-overlay-annotatie** in `deploy/README.md:262-265`: die
   noemt nu "geen Routes", terwijl de frontend-Route juist behouden blijft — alleen
   `backend-debug` en `reader` worden uit de preview verwijderd.
5. **Voorwaarde per-PR Neon-branch** in `docs/factory/deployment.md:97-101`:
   `GITHUB_TOKEN` toevoegen als derde benodigde sleutel naast `NEON_API_KEY` en
   `NEON_PROJECT_ID`, met het expliciete gevolg van het ontbreken ervan. Werk de
   wiring-stappen 1-3 bij zodat de GitHub-check erin staat, en pas de zin over
   opruimen bij PR-close aan (dat gebeurt pas nadat de namespace verdwenen is én
   GitHub bevestigt dat de PR gesloten is).
6. **Dezelfde verouderde voorwaarde** op twee plekken buiten de oorspronkelijke
   lijst: `deploy/README.md:214-216` en `runbook.md:180-182` stellen allebei nog dat
   alleen een ontbrekende `NEON_API_KEY` tot labeling-only degradeert. Trek die
   gelijk met punt 5.
7. **Comment-restanten** in `deploy/overlays/preview/kustomization.yaml`: regel 11
   ("Geen preview-router") vervalt; regel 2 verwijst naar het niet-bestaande
   `deploy/applicationset.yaml` en moet naar
   `robberts-infrastructure/manifests/root-app/apps/` wijzen; regel 16 noemt alleen
   namespace + image-tag terwijl de ApplicationSet ook de Route-host invult.

**Buiten scope:** `docs/stories/**` (historische storyverslagen, bewust een
momentopname), `deploy/base/backend-route.yaml` (houdt bewust
`insecureEdgeTerminationPolicy: Redirect`), en elke functionele wijziging aan
manifests of `labeller.sh`.

## Acceptance criteria

1. `grep -rn "preview-router" deploy/ docs/factory/ runbook.md` geeft nul treffers.
2. De drie architectuurdiagrammen (`docs/factory/deployment.md`,
   `deploy/README.md`, `runbook.md` §2) noemen alleen nog onderdelen die
   daadwerkelijk in `deploy/base/` staan: backend, frontend, reader, cloudflared
   en het Secret.
3. De `base/`-bestandenlijst in `deploy/README.md` komt 1-op-1 overeen met
   `ls deploy/base/` (13 bestanden).
4. `deploy/README.md` stap 5 en `runbook.md:305` beschrijven de routering via de
   OpenShift-ingressrouter op Host-header, met verwijzing naar de declaratieve
   hosts in `frontend-route.yaml`/`reader-route.yaml` en naar de per-PR
   host-patch door de ApplicationSet. Geen enkele doc noemt nog een nginx-
   tussenlaag voor preview-routing.
5. Beide documenten vermelden dat `insecureEdgeTerminationPolicy` op de frontend-
   en reader-Route `Allow` is, met de reden (Cloudflare-connector bereikt de
   router cluster-intern via HTTP), consistent met de comments in de manifests.
6. `deploy/README.md`'s beschrijving van `overlays/preview/` klopt met de patches
   in `deploy/overlays/preview/kustomization.yaml`: frontend-Route blijft (met
   per-PR host), `backend-debug`- en `reader`-Route, PVC, cloudflared en
   SealedSecret vervallen.
7. `docs/factory/deployment.md` noemt `GITHUB_TOKEN` als derde vereiste sleutel
   voor de per-PR Neon-branch en beschrijft het fail-closed gedrag correct:
   ontbreekt het token (of faalt de GitHub-call, of komt er geen HTTP 200), dan is
   de PR-status "onbekend" en voert de labeller voor die preview géén enkele
   mutatie uit — geen namespace-label, geen Neon-branch, geen secret-patch en geen
   cleanup.
8. De wiring-stappen in `docs/factory/deployment.md` beginnen met de GitHub-
   PR-statuscheck vóór elke creatiehandeling, en de opruim-zin beschrijft dat een
   branch pas verdwijnt nadat de namespace weg is én GitHub bevestigt dat de PR
   gesloten is.
9. `deploy/README.md:214-216` en `runbook.md:180-182` bevatten dezelfde,
   bijgewerkte voorwaarde als criterium 7 — geen enkele doc suggereert nog dat
   `NEON_API_KEY` + `NEON_PROJECT_ID` volstaan.
10. `grep -rn "applicationset.yaml" deploy/` verwijst nergens meer naar
    `deploy/applicationset.yaml`; de comment in
    `deploy/overlays/preview/kustomization.yaml` wijst naar
    `robberts-infrastructure/manifests/root-app/apps/` en noemt namespace,
    image-tag én Route-host als door de ApplicationSet ingevulde waarden.
11. `git diff --stat` raakt uitsluitend `docs/factory/deployment.md`,
    `deploy/README.md`, `runbook.md` en `deploy/overlays/preview/kustomization.yaml`;
    in dat laatste bestand wijzigen alleen commentregels — de gerenderde output van
    `kubectl kustomize deploy/overlays/preview` is byte-identiek aan die van vóór
    de wijziging.

## Aannames

- De ApplicationSet die de per-PR Route-host invult staat in
  `robberts-infrastructure` en is vanuit deze checkout niet inzichtelijk. De
  formulering baseert zich op wat hier wél verifieerbaar is: de placeholder-host
  `preview-host-must-be-set.invalid` in `deploy/overlays/preview/kustomization.yaml`
  en de comments in de Route-manifests. Docs beschrijven het daarom op dat
  abstractieniveau, zonder de exacte patch-syntax van de ApplicationSet te claimen.
- Historische storyverslagen onder `docs/stories/**` (o.a. SF-1690, SF-1542) noemen
  de preview-router nog; dat zijn bewuste momentopnames en blijven ongewijzigd.
- `deploy/base/backend-route.yaml` houdt `insecureEdgeTerminationPolicy: Redirect`
  — dat is de debug-route die niet via de gedeelde wildcard loopt en dus geen
  doc- of manifestwijziging vraagt.
- Docs-only story: er is geen build- of testverificatie van toepassing. Verificatie
  gebeurt via de greps en het `kubectl kustomize`-diff uit de acceptatiecriteria.
- Regelnummers in deze story gelden voor de huidige `main` (na 954d5f3) en kunnen
  bij eerdere edits verschuiven; de tekstuele beschrijving is leidend.

## Eindsamenvatting

# Eindsamenvatting SF-2015 — Deploy-documentatie in lijn met de manifests van 7 augustus

## Wat is gebouwd
Een zuivere documentatie-/commentaarwijziging in vier bestanden. Er is geen code, geen manifest-gedrag en geen test gewijzigd.

**`docs/factory/deployment.md`**
- `preview-router` uit het architectuurdiagram.
- Per-PR Neon-branch: `GITHUB_TOKEN` toegevoegd als derde vereiste sleutel naast `NEON_API_KEY` en `NEON_PROJECT_ID`, inclusief het fail-closed gedrag.
- Wiring-stappen herschikt: de GitHub-PR-statuscheck staat nu als stap 1, vóór élke creatiehandeling. Opruimzin bijgesteld: een branch verdwijnt pas als de namespace weg is **én** GitHub bevestigt dat de PR gesloten is.

**`deploy/README.md`**
- `preview-router` uit het diagram; de twee preview-router-manifests uit de `base/`-bestandenlijst (nu 13 entries, 1-op-1 met `ls deploy/base/`).
- Stap 5 van "Hoe het werkt" herschreven naar de huidige routering: Cloudflare stuurt de wildcard naar de OpenShift-ingressrouter, die op de Host-header de juiste Route kiest; hosts declaratief in `frontend-route.yaml`/`reader-route.yaml`, per-PR host via de placeholder die de ApplicationSet invult; `insecureEdgeTerminationPolicy: Allow` met motivering.
- Beschrijving van `overlays/preview/` gecorrigeerd (frontend-Route blíjft, met per-PR host).

**`runbook.md`** — diagram, §6 Database (dezelfde fail-closed voorwaarde) en §7 Cloudflare Tunnel gelijkgetrokken.

**`deploy/overlays/preview/kustomization.yaml`** — uitsluitend commentregels: verwijzing naar het niet-bestaande `deploy/applicationset.yaml` vervangen door het `robberts-infrastructure`-pad, bullet "Geen preview-router" vervallen, en Route-host toegevoegd aan de door de ApplicationSet ingevulde waarden.

## Gemaakte keuzes
- **Fail-closed strenger geformuleerd dan de storytekst.** De story sprak van "draait zonder eigen branch-DB"; uit `labeller.sh` blijkt dat de `github_pr_is_open`-gate vóór `ensure_ns_with_label` staat. Bij een ontbrekend token, gefaalde curl of non-200 voert de labeller dus *géén enkele* mutatie uit. De docs beschrijven dat nu exact.
- **Twee extra vindplaatsen meegenomen** buiten de oorspronkelijke lijst (`deploy/README.md` beperkingen-sectie en `runbook.md` §6), die nog stelden dat alleen `NEON_API_KEY` ontbrak.
- **Abstractieniveau ApplicationSet.** De ApplicationSet zit in een andere repo en is hier niet inzichtelijk; de docs beschrijven het mechanisme zonder exacte patch-syntax te claimen.

## Wat is getest
- Alle 11 acceptatiecriteria zijn twee keer onafhankelijk nagelopen (reviewer en tester) — allemaal groen.
- `grep -rn "preview-router" deploy/ docs/factory/ runbook.md` → 0 treffers; `grep -rn "applicationset.yaml" deploy/` wijst alleen nog naar het infrastructure-pad.
- **Bewijs van gedragsneutraliteit:** `kubectl kustomize deploy/overlays/preview` gerenderd op `main` (schone worktree) en op de branch → identieke md5 `bb7ae3f…`, 6701 bytes, `cmp` leeg. Alle gewijzigde regels in dat bestand beginnen met `#`.
- Fail-closed tekst is getoetst tegen de daadwerkelijke `labeller.sh`-code, niet alleen tegen de docs.
- Vangnet: `mvn clean verify` BUILD SUCCESS (106 unit + 61 e2e groen), `flutter test` 25 + 2 groen.

## Bewust niet gedaan
- `docs/stories/**` ongemoeid gelaten: historische storyverslagen zijn een bewuste momentopname en noemen de preview-router nog.
- `deploy/base/backend-route.yaml` houdt bewust `insecureEdgeTerminationPolicy: Redirect` (debug-route, loopt niet via de gedeelde wildcard).
- Geen functionele wijziging aan manifests of `labeller.sh`.
- Geen preview-/E2E-test: `gh` had in de testcontainer geen auth, dus er was geen PR-nummer en dus geen preview-URL. Niet blokkerend — de gerenderde manifests zijn aantoonbaar byte-identiek, dus er kan geen applicatiegedrag zijn veranderd.

<!-- deploy-summary:start -->
Er is niets veranderd aan de app zelf; alleen de interne beschrijvingen van hoe de app wordt uitgerold zijn bijgewerkt. Die tekst klopte niet meer met de werkelijke situatie sinds begin augustus. Je merkt hier als gebruiker niets van.
<!-- deploy-summary:end -->
